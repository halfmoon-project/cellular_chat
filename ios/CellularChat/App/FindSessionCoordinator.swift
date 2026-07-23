import Foundation
import Combine
import CellularChatCore

/// Top-level Find session controller. Binds identity/pairing, transport, and
/// ranging to the core `FindStateMachine` reducer (PROTOCOL_V2.md §10). Every
/// transition carries a §13 reason; entering `signalLost` clears the last
/// measurement so a stale value is never shown.
@MainActor
final class FindSessionCoordinator: ObservableObject {

    @Published private(set) var state: FindState = .idle
    @Published private(set) var reason: ReasonCode?
    @Published private(set) var selectedPair: PairRecord?

    let pairStore: PairStore
    let ranging = RangingCoordinator()
    let background = FindLiveActivityController()
    private let transports = TransportCoordinator()
    private let localCaps = LocalCapabilities.current()

    private var runner: SessionRunner?
    private var activeTransport: PeerTransport?
    private var cancellables: Set<AnyCancellable> = []

    // §10 recovery loop: remember what we are finding so a lost link can back off
    // and re-enter transport search until the find deadline passes.
    private var activePair: PairRecord?
    private var deadline: Date?
    private var retryBackoff = BoundedBackoff()
    private var retryTask: Task<Void, Never>?

    init(pairStore: PairStore) {
        self.pairStore = pairStore
        background.onExpired = { [weak self] in self?.apply(.deadline, reason: .expired) }
        ranging.$measurement
            .receive(on: RunLoop.main)
            .sink { [weak self] m in self?.onMeasurement(m) }
            .store(in: &cancellables)
    }

    /// User text describing why we are in the current state.
    var statusText: String {
        switch state {
        case .idle: return "찾기를 시작하려면 상대를 선택하세요."
        case .arming: return "찾기 준비 중"
        case .searching: return "검색 중 · 아직 직접 무선 범위 안에 있지 않습니다"
        case .p2pConnecting: return "상대 기기에 연결 중"
        case .authenticating: return "상대 기기 인증 중"
        case .connected: return "연결됨"
        case .rangingStarting: return "거리 측정 시작 중"
        case .directionAvailable: return "방향 안내 중"
        case .distanceOnly: return "거리만 표시 중"
        case .proximityOnly: return "근접도만 표시 중"
        case .connectedOnly: return "연결됨 · 측정 불가"
        case .signalLost: return "신호가 끊겼습니다 · 다시 찾는 중"
        case .retryWait: return "잠시 후 다시 시도합니다"
        case .stopped: return "중지됨"
        case .expired: return "찾기 시간이 만료되었습니다"
        case .failed: return reason?.userText ?? "실패했습니다"
        }
    }

    // MARK: control

    func arm(pair: PairRecord, duration: TimeInterval = FindLiveActivityController.defaultDuration) {
        guard !pair.revoked else { return }
        selectedPair = pair
        activePair = pair
        deadline = Date().addingTimeInterval(duration)
        retryBackoff.reset()
        ranging.stop()
        apply(.arm, reason: nil)
        background.arm(duration: duration)
        apply(.armed, reason: nil)
        beginSearch(pair: pair)
    }

    func stop() {
        teardown(reason: .userStopped)
        apply(.userStop, reason: .userStopped)
    }

    // MARK: discovery → session

    private func beginSearch(pair: PairRecord) {
        let remaining = deadline?.timeIntervalSinceNow ?? 0
        guard remaining > 0 else { apply(.deadline, reason: .expired); return }
        guard let root = try? pairStore.pairRoot(pair) else {
            apply(.fatal, reason: .radioUnavailable); return
        }
        let ourDiscKey = Derivations.discoveryKey(pairRoot: root, role: pair.pairRole)
        let peerDiscKey = Derivations.discoveryKey(pairRoot: root, role: pair.peerRole)

        let localToken: () -> [UInt8] = {
            let epoch = Discovery.epoch(unixSeconds: UInt64(Date().timeIntervalSince1970))
            return Discovery.token(discoveryKey: ourDiscKey, epoch: epoch, role: pair.pairRole)
        }
        let acceptsPeer: ([UInt8]) -> Bool = { candidate in
            Discovery.accepts(candidate: candidate, discoveryKey: peerDiscKey,
                              unixSeconds: UInt64(Date().timeIntervalSince1970), role: pair.peerRole)
        }

        // iOS defaults to the Noise-initiator side: BLE central / Wi-Fi Aware
        // subscriber (§4/§8). Same-platform role tie-break via RoleArbiter needs
        // the peer OS, which is learned only in the session capability exchange.
        let ble = BLETransport(role: .central, localToken: localToken, acceptsPeerToken: acceptsPeer)
        let aware = WiFiAwareTransport(role: .subscriber)
        let nearby = NearbyConnectionsTransport()
        let all: [PeerTransport] = [aware, nearby, ble]

        Task { [weak self] in
            guard let self else { return }
            guard let winner = await self.transports.select(transports: all) else {
                // No transport this pass: stay in searching (the honest "not yet
                // in direct radio range") and retry with backoff until the deadline.
                await MainActor.run { self.scheduleSearchRetry() }
                return
            }
            await MainActor.run { self.startSession(over: winner, pair: pair, root: root, duration: remaining) }
        }
    }

    private func startSession(over transport: PeerTransport, pair: PairRecord, root: [UInt8], duration: TimeInterval) {
        apply(.peerFound, reason: nil)
        apply(.transportConnected, reason: nil)
        activeTransport = transport

        guard let priv = try? DeviceKeyStore().staticPrivateKey(pairId: pair.pairId)?.rawRepresentation else {
            apply(.fatal, reason: .authFailed); return
        }
        let deadline = UInt64(Date().timeIntervalSince1970 + duration)
        do {
            let runner = try SessionRunner(
                role: .initiator, pair: pair, pairRoot: root, localStaticPriv: Array(priv),
                transport: transport, localCaps: localCaps, ranging: ranging, findDeadline: deadline)
            runner.onAuthenticated = { [weak self] in
                self?.retryBackoff.reset()   // link re-established; start backoff fresh
                self?.apply(.authenticated, reason: nil)
            }
            runner.onConnected = { [weak self] in self?.apply(.rangingStarting, reason: nil) }
            runner.onFatal = { [weak self] reason in self?.handleFatal(reason) }
            self.runner = runner
            runner.start()
        } catch {
            apply(.fatal, reason: .authFailed)
        }
    }

    // MARK: events

    private func onMeasurement(_ m: Measurement?) {
        guard let m else {
            apply(.signalLost, reason: .transportLost)
            return
        }
        if m.horizontalAngleRadians != nil {
            apply(.sampleDirection, reason: nil)
        } else if m.distanceMeters != nil {
            apply(.sampleDistance, reason: nil)
        } else if m.proximity != nil {
            apply(.sampleProximity, reason: nil)
        }
    }

    private func handleFatal(_ reason: ReasonCode) {
        // A ranging failure never reaches here; only transport/auth faults do.
        if reason == .authFailed || reason == .identityMismatch {
            teardown(reason: reason)
            apply(.fatal, reason: reason)
        } else {
            enterSignalLostAndRetry(reason: reason)
        }
    }

    // MARK: §10 recovery loop

    /// A lost link (not an auth/identity fault) drops to signalLost, tears down
    /// the dead transport while keeping the find session, then backs off and
    /// re-enters transport search until the find deadline (§10).
    private func enterSignalLostAndRetry(reason: ReasonCode) {
        apply(.signalLost, reason: reason)
        guard state == .signalLost else { return }   // ignore from an unlinked state
        runner = nil
        transports.teardown(reason: reason)
        activeTransport = nil
        ranging.stop()
        apply(.retryWait, reason: reason)            // signalLost -> retryWait
        scheduleRetry(fromRetryWait: true)
    }

    /// Discovery found no transport this pass; retry from `searching`.
    private func scheduleSearchRetry() { scheduleRetry(fromRetryWait: false) }

    private func scheduleRetry(fromRetryWait: Bool) {
        guard let pair = activePair, let deadline, Date() < deadline else {
            apply(.deadline, reason: .expired); return
        }
        let expected: FindState = fromRetryWait ? .retryWait : .searching
        guard state == expected else { return }
        let delay = retryBackoff.next()
        retryTask?.cancel()
        retryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard let self, !Task.isCancelled else { return }
            await MainActor.run {
                guard self.state == expected else { return }
                guard let deadline = self.deadline, Date() < deadline else {
                    self.apply(.deadline, reason: .expired); return
                }
                if fromRetryWait { self.apply(.retryElapsed, reason: nil) }   // retryWait -> searching
                guard self.state == .searching else { return }
                self.beginSearch(pair: pair)
            }
        }
    }

    private func teardown(reason: ReasonCode) {
        retryTask?.cancel()
        retryTask = nil
        runner?.sendDisconnect(reason: reason)
        runner = nil
        transports.teardown(reason: reason)
        activeTransport = nil
        ranging.stop()
        background.stop()
    }

    /// Apply an event through the shared reducer; ignore invalid pairs (§10).
    private func apply(_ event: FindEvent, reason: ReasonCode?) {
        guard let next = FindStateMachine.reduce(state, event) else { return }
        if let reason { self.reason = reason }
        state = next
        if next == .signalLost { /* core clears; UI never shows stale */ }
        if next == .stopped || next == .expired || next == .failed {
            teardown(reason: reason ?? .normal)
        }
    }
}
