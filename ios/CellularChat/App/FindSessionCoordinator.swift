import Foundation
import Combine
import UIKit
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
    private let haptics = FindHaptics()

    /// VoiceOver announcement sink; overridable in tests. Defaults to posting a
    /// UIAccessibility announcement so state/band changes are read aloud.
    var announce: (String) -> Void = { UIAccessibility.post(notification: .announcement, argument: $0) }
    private var lastAnnouncedBand: ProximityBand?

    private var runner: SessionRunner?
    private var activeTransport: PeerTransport?
    private var cancellables: Set<AnyCancellable> = []

    // §10 recovery loop: remember what we are finding so a lost link can back off
    // and re-enter transport search until the find deadline passes.
    private var activePair: PairRecord?
    private var deadline: Date?
    private var retryBackoff = BoundedBackoff()
    private var retryTask: Task<Void, Never>?

    // §10 transport upgrade (Feature A). The driver is the current session's Noise
    // initiator; only it starts upgrades. State bound to the logical `sid`.
    private var activeIsInitiator = false
    private var activeRoot: [UInt8]?
    private var activeLocalStaticPriv: [UInt8] = []
    private var boundPeerCaps: CapabilitySet?
    private var logicalSid: [UInt8]?
    private var upgradeCounter: UInt64 = 0
    private var upgradeBackoff = BoundedBackoff()   // separate from the ranging/retry backoff
    private var upgradeBackoffUntil: Date?
    private var upgradeTimer: Task<Void, Never>?
    private var upgradeTimeout: Task<Void, Never>?
    private var upgradeInFlight: UInt64?            // driver: attemptId in flight
    private var pendingUpgradeKind: TransportKind?
    private var kAckAccepted = false
    private var kConnected = false
    private var kRunner: SessionRunner?
    private var kTransport: PeerTransport?
    private var retainedRunner: SessionRunner?      // idle BLE control fallback
    private var retainedTransport: PeerTransport?
    // Responder-side idempotency (A.9): cached ack per (sid, attemptId).
    private var upgradeAckCache: (attemptId: UInt64, code: UInt64, accepted: Bool)?

    /// Builds an upgrade transport of `kind` (Noise initiator = subscriber side).
    /// Overridable in tests with in-memory transports. `ble` is never a target.
    var makeUpgradeTransport: (TransportKind, Bool) -> PeerTransport? = { kind, asInitiator in
        switch kind {
        case .wifiAware: return WiFiAwareTransport(role: asInitiator ? .subscriber : .publisher)
        case .nearby: return NearbyConnectionsTransport()
        case .ble: return nil
        }
    }

    /// Runtime-available upgrade transports (§10 eligibility condition 2).
    /// Overridable in tests.
    var upgradeAvailability: () -> Set<TransportKind> = {
        var kinds: Set<TransportKind> = []
        if WiFiAwareTransport(role: .subscriber).isAvailable { kinds.insert(.wifiAware) }
        if NearbyConnectionsTransport().isAvailable { kinds.insert(.nearby) }
        return kinds
    }

    /// Connected/ranging family states in which the driver evaluates upgrades (§10).
    private static let upgradeFamilyStates: Set<FindState> = [
        .connected, .rangingStarting, .directionAvailable, .distanceOnly, .proximityOnly, .connectedOnly,
    ]

    init(pairStore: PairStore) {
        self.pairStore = pairStore
        background.onExpired = { [weak self] in self?.apply(.deadline, reason: .expired) }
        // §14 (Feature B): a ranging message with an out-of-transcript method is a
        // hard capabilityMismatch, not a ranging fallback.
        ranging.onCapabilityMismatch = { [weak self] in self?.handleFatal(.capabilityMismatch) }
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
        case .searching: return "검색 중 · 아직 직접 무선 범위 안에 있지 않습니다. 상대가 없다는 뜻이 아닙니다."
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
        // A revoked pair never arms a session (PROTOCOL_V2.md §8); surface the
        // §13 revoked reason instead of failing silently.
        guard !pair.revoked else { reason = .revoked; return }
        selectedPair = pair
        activePair = pair
        deadline = Date().addingTimeInterval(duration)
        retryBackoff.reset()
        ranging.stop()
        apply(.arm, reason: nil)
        background.arm(duration: duration, alias: pair.alias)
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

        // Same-platform pairs derive BLE central/peripheral, Noise initiator/
        // responder, and Wi-Fi Aware subscriber/publisher deterministically from
        // the pinned keys so the two iPhones pick opposite roles (§4/§9/§10). A
        // cross-platform or not-yet-known peer keeps today's default iOS-initiator
        // direction: BLE central / Wi-Fi Aware subscriber / Noise initiator.
        let localStatic = (try? DeviceKeyStore().staticPublicKey(pairId: pair.pairId)) ?? []
        let isInitiator = RoleArbiter.localIsInitiatorSide(
            peerPlatform: pair.peerPlatform, localStatic: localStatic, peerStatic: pair.peerStaticPub)

        let ble = BLETransport(role: isInitiator ? .central : .peripheral,
                               localToken: localToken, acceptsPeerToken: acceptsPeer)
        let aware = WiFiAwareTransport(role: isInitiator ? .subscriber : .publisher)
        let nearby = NearbyConnectionsTransport()
        let all: [PeerTransport] = [aware, nearby, ble]

        Task { [weak self] in
            guard let self else { return }
            guard let winner = await self.transports.select(transports: all) else {
                await MainActor.run { self.handleNoTransport() }
                return
            }
            await MainActor.run {
                self.startSession(over: winner, pair: pair, root: root,
                                  duration: remaining, isInitiator: isInitiator)
            }
        }
    }

    /// Transport selection found nothing this pass. A denied Bluetooth permission
    /// is not "not yet in range": surface the §13 permissionRequired reason so the
    /// UI shows the settings path. Otherwise stay in searching (the honest "not
    /// yet in direct radio range") and retry with backoff until the deadline (§10).
    private func handleNoTransport() {
        if !LocalCapabilities.bleCentralAvailable() {
            apply(.fatal, reason: .permissionRequired)
        } else {
            scheduleSearchRetry()
        }
    }

    private func startSession(over transport: PeerTransport, pair: PairRecord, root: [UInt8],
                              duration: TimeInterval, isInitiator: Bool) {
        // §10 duplicate-connection guard: a second authenticated connection for an
        // already-active pair is reconciled by the arbiter — keep the one whose
        // Noise initiator holds the bytewise-smaller static key, close the loser
        // with `duplicate`. (Single-transport arbitration makes this defensive.)
        if runner != nil {
            let localStatic = (try? DeviceKeyStore().staticPublicKey(pairId: pair.pairId)) ?? []
            let keepNew = RoleArbiter.keepNewDuplicate(
                localInitiatedNew: isInitiator, localStatic: localStatic, peerStatic: pair.peerStaticPub)
            guard keepNew else { transport.disconnect(reason: .duplicate); return }
            teardown(reason: .duplicate)
        }

        apply(.peerFound, reason: nil)
        apply(.transportConnected, reason: nil)
        activeTransport = transport

        guard let priv = try? DeviceKeyStore().staticPrivateKey(pairId: pair.pairId)?.rawRepresentation else {
            apply(.fatal, reason: .authFailed); return
        }
        // Retain the driver context (§10 Feature A): who is the Noise initiator, the
        // pair root, and this device's static key, for building an upgrade runner.
        activeIsInitiator = isInitiator
        activeRoot = root
        activeLocalStaticPriv = Array(priv)
        let deadline = UInt64(Date().timeIntervalSince1970 + duration)
        do {
            let runner = try SessionRunner(
                role: isInitiator ? .initiator : .responder, pair: pair, pairRoot: root,
                localStaticPriv: Array(priv), transport: transport, localCaps: localCaps,
                ranging: ranging, findDeadline: deadline)
            runner.onAuthenticated = { [weak self] in
                self?.retryBackoff.reset()   // link re-established; start backoff fresh
                self?.apply(.authenticated, reason: nil)
            }
            runner.onPeerCapabilities = { [weak self] caps in
                // Bind the peer's first CapabilitySet to the logical session
                // (§14/§10) and persist the peer's platform (§11) so the next arm
                // can derive the same-platform transport roles (§9/§10).
                self?.boundPeerCaps = caps
                self?.pairStore.setPeerPlatform(caps.os, pairId: pair.pairId)
            }
            runner.onConnected = { [weak self] in
                guard let self else { return }
                self.runner?.activateRanging()   // bind ranging to the primary runner
                self.logicalSid = self.runner?.sid
                self.apply(.rangingStarting, reason: nil)
                self.startUpgradeEvaluation()    // driver-only 5 s upgrade timer (§10)
            }
            wireUpgradeCallbacks(runner)
            self.runner = runner
            runner.start()
        } catch {
            apply(.fatal, reason: .authFailed)
        }
    }

    /// Wire the fatal + transport-upgrade control callbacks shared by the primary
    /// runner and any runner promoted to active by a switchover (§10).
    private func wireUpgradeCallbacks(_ r: SessionRunner) {
        r.onFatal = { [weak self] reason in self?.handleActiveFatal(reason) }
        r.onTransportUpgrade = { [weak self] code, attemptId in
            self?.handleIncomingUpgrade(code: code, attemptId: attemptId)
        }
        r.onTransportAck = { [weak self] code, attemptId, accepted in
            self?.handleTransportAck(code: code, attemptId: attemptId, accepted: accepted)
        }
    }

    // MARK: events

    private func onMeasurement(_ m: Measurement?) {
        guard let m else {
            // Stale-clear/signal loss: pulses and band announcements must not
            // outlive a real measurement.
            haptics.stop()
            lastAnnouncedBand = nil
            apply(.signalLost, reason: .transportLost)
            return
        }
        haptics.update(for: m)
        // Announce a proximity band the first time it changes (a significant,
        // non-spammy event); precise distance changes are not announced.
        if let band = m.proximity, band != lastAnnouncedBand {
            lastAnnouncedBand = band
            announce(band.label)
            background.update(statusText: statusText, proximityLabel: band.label)
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
        // A ranging failure never reaches here; only transport/auth/capability
        // faults do. capabilityMismatch is a HARD failure (§14, Feature B): it
        // tears down the logical session and goes to `failed`, never retried.
        if reason == .authFailed || reason == .identityMismatch || reason == .capabilityMismatch {
            teardown(reason: reason)
            apply(.fatal, reason: reason)
        } else {
            enterSignalLostAndRetry(reason: reason)
        }
    }

    /// Fatal on the ACTIVE runner. If the upgraded transport is lost while a
    /// retained BLE control session is still authenticated, revert in place —
    /// no new handshake, no SIGNAL_LOST (§10) — otherwise fall through.
    private func handleActiveFatal(_ reason: ReasonCode) {
        if (reason == .transportLost || reason == .timeout),
           let bleRunner = retainedRunner, let bleTransport = retainedTransport {
            runner?.deactivateRanging()
            activeTransport?.disconnect(reason: reason)
            runner = bleRunner
            activeTransport = bleTransport
            retainedRunner = nil
            retainedTransport = nil
            bleRunner.activateRanging()
            wireUpgradeCallbacks(bleRunner)
            self.reason = .transportLost          // telemetry only; no state transition
            upgradeBackoff.reset()
            upgradeBackoffUntil = nil             // resume upgrade evaluation
            return
        }
        handleFatal(reason)
    }

    /// The retained idle BLE control fallback dropped (§10 A.7). It is not the
    /// active transport, so forget it and leave the upgraded runner untouched —
    /// this must never enter the revert branch of `handleActiveFatal`.
    private func handleRetainedFatal() {
        retainedRunner = nil
        retainedTransport = nil
    }

    // MARK: §10 transport upgrade (Feature A)

    private func nextUpgradeAttemptId() -> UInt64 { upgradeCounter += 1; return upgradeCounter }
    private func deadlineUnix() -> UInt64 { UInt64((deadline ?? Date()).timeIntervalSince1970) }

    /// The best strictly-better, locally-available, peer-advertised upgrade target
    /// (§10 eligibility). Pure so the decision is unit-testable. `ble` is never a
    /// target. Mirrors `TransportCoordinator.shouldUpgrade` (strict index order).
    static func upgradeCandidate(activeKind: TransportKind,
                                 availableKinds: Set<TransportKind>,
                                 peerCaps: CapabilitySet,
                                 order: [TransportKind] = TransportCoordinator.defaultOrder) -> TransportKind? {
        guard let activeIndex = order.firstIndex(of: activeKind) else { return nil }
        for (i, kind) in order.enumerated() where i < activeIndex {
            guard kind != .ble, availableKinds.contains(kind) else { continue }
            let advertised: Bool
            switch kind {
            case .wifiAware: advertised = peerCaps.wifiAware
            case .nearby: advertised = peerCaps.nearbyConnections
            case .ble: advertised = false
            }
            if advertised { return kind }
        }
        return nil
    }

    /// Responder decision (§10 A.5.3): accept only a well-typed, non-ble,
    /// strictly-better, locally-available target. Pure/testable.
    static func upgradeAckAccepted(code: UInt64, activeKind: TransportKind,
                                   availableKinds: Set<TransportKind>,
                                   order: [TransportKind] = TransportCoordinator.defaultOrder) -> Bool {
        guard let kind = TransportKind(upgradeCode: code), kind != .ble,
              let ai = order.firstIndex(of: activeKind),
              let ci = order.firstIndex(of: kind), ci < ai else { return false }
        return availableKinds.contains(kind)
    }

    struct UpgradeAckDecision: Equatable { let code: UInt64; let accepted: Bool; let openK: Bool }

    /// Responder ack decision with idempotency (§10 A.9): a duplicate
    /// `(sid, attemptId)` re-sends the cached ack and NEVER opens a second K.
    /// Pure/testable — the caller performs the actual send and K setup.
    static func decideUpgradeAck(attemptId: UInt64, code: UInt64, activeKind: TransportKind,
                                 availableKinds: Set<TransportKind>,
                                 cache: (attemptId: UInt64, code: UInt64, accepted: Bool)?) -> UpgradeAckDecision {
        if let cache, cache.attemptId == attemptId {
            return UpgradeAckDecision(code: cache.code, accepted: cache.accepted, openK: false)
        }
        let accepted = upgradeAckAccepted(code: code, activeKind: activeKind, availableKinds: availableKinds)
        return UpgradeAckDecision(code: code, accepted: accepted, openK: accepted)
    }

    private func startUpgradeEvaluation() {
        guard activeIsInitiator, upgradeTimer == nil else { return }
        upgradeTimer = Task { [weak self] in
            while true {
                try? await Task.sleep(nanoseconds: 5 * 1_000_000_000)
                guard let self, !Task.isCancelled else { return }
                await MainActor.run { self.evaluateUpgrade() }
            }
        }
    }

    private func stopUpgradeEvaluation() {
        upgradeTimer?.cancel()
        upgradeTimer = nil
    }

    /// Driver: pick an upgrade target and begin one attempt (§10). At most one
    /// attempt is in flight; a fresh attempt waits out the upgrade backoff.
    private func evaluateUpgrade() {
        guard activeIsInitiator, upgradeInFlight == nil,
              Self.upgradeFamilyStates.contains(state) else { return }
        if let until = upgradeBackoffUntil, Date() < until { return }
        guard let activeKind = runner?.transportKind, let peerCaps = boundPeerCaps,
              let candidate = Self.upgradeCandidate(activeKind: activeKind,
                                                    availableKinds: upgradeAvailability(),
                                                    peerCaps: peerCaps) else { return }
        beginUpgrade(to: candidate)
    }

    private func beginUpgrade(to kind: TransportKind) {
        guard let workingRunner = runner, let sid = logicalSid, let pair = activePair,
              let root = activeRoot, let peerCaps = boundPeerCaps,
              let transport = makeUpgradeTransport(kind, true) else { return }
        let a = nextUpgradeAttemptId()
        upgradeInFlight = a
        pendingUpgradeKind = kind
        kAckAccepted = false
        kConnected = false
        // (1) Announce the upgrade on the WORKING transport; (2) begin establishing
        // K as the Noise initiator reusing `sid` — but hold the handshake until the
        // peer's transport_ack accepts (A.5).
        workingRunner.sendTransportUpgrade(code: kind.upgradeCode, attemptId: a)
        startUpgradeTimeout(attemptId: a)
        do {
            let kr = try SessionRunner(role: .initiator, pair: pair, pairRoot: root,
                                       localStaticPriv: activeLocalStaticPriv, transport: transport,
                                       localCaps: localCaps, ranging: ranging, findDeadline: deadlineUnix(),
                                       upgradeSid: sid, expectedPeerCaps: peerCaps)
            kr.onConnected = { [weak self] in self?.completeUpgrade() }
            kr.onFatal = { [weak self] _ in self?.abandonUpgrade() }
            self.kRunner = kr
            self.kTransport = transport
            Task { [weak self] in
                let result = await transport.connect()
                await MainActor.run {
                    guard let self, self.upgradeInFlight == a else { return }
                    switch result {
                    case .success: self.kConnected = true; self.maybeStartKHandshake()
                    case .failure: self.abandonUpgrade()
                    }
                }
            }
        } catch { abandonUpgrade() }
    }

    private func handleTransportAck(code: UInt64, attemptId: UInt64, accepted: Bool) {
        // A stale/foreign (sid, attemptId) ack is a no-op (A.9); only the matching
        // in-flight attempt is acted on.
        guard let inFlight = upgradeInFlight, attemptId == inFlight,
              pendingUpgradeKind?.upgradeCode == code else { return }
        if accepted { kAckAccepted = true; maybeStartKHandshake() }
        else { abandonUpgrade() }        // graceful decline: keep the working transport
    }

    private func maybeStartKHandshake() {
        guard kAckAccepted, kConnected, let kr = kRunner else { return }
        kr.start()   // initiator emits IKpsk2 msg 1 on K
    }

    /// Responder (A.5.3): answer transport_upgrade on the working transport and,
    /// when accepted, stand up K as the Noise responder pre-initialized with `sid`.
    private func handleIncomingUpgrade(code: UInt64, attemptId: UInt64) {
        guard let workingRunner = runner, let activeKind = runner?.transportKind else { return }
        let decision = Self.decideUpgradeAck(attemptId: attemptId, code: code, activeKind: activeKind,
                                             availableKinds: upgradeAvailability(), cache: upgradeAckCache)
        upgradeAckCache = (attemptId: attemptId, code: decision.code, accepted: decision.accepted)
        workingRunner.sendTransportAck(code: decision.code, attemptId: attemptId, accepted: decision.accepted)
        // A duplicate (openK == false) re-sends the cached ack and stops here (A.9).
        guard decision.openK, let kind = TransportKind(upgradeCode: decision.code), let sid = logicalSid,
              let pair = activePair, let root = activeRoot, let peerCaps = boundPeerCaps,
              let transport = makeUpgradeTransport(kind, false) else { return }
        teardownKAttempt()   // drop any stale half-open K before opening a new one
        do {
            let kr = try SessionRunner(role: .responder, pair: pair, pairRoot: root,
                                       localStaticPriv: activeLocalStaticPriv, transport: transport,
                                       localCaps: localCaps, ranging: ranging, findDeadline: deadlineUnix(),
                                       upgradeSid: sid, expectedPeerCaps: peerCaps)
            kr.onConnected = { [weak self] in self?.completeUpgrade() }
            kr.onFatal = { [weak self] _ in self?.abandonUpgrade() }
            self.kRunner = kr
            self.kTransport = transport
            kr.start()                              // responder wires callbacks, waits for msg1
            Task { _ = await transport.connect() }  // publisher starts listening on K
        } catch { abandonUpgrade() }
    }

    /// Switchover (§10): both `session_ready` exchanged on K. Repoint ranging to K,
    /// retain BLE as the control fallback (or close a non-BLE previous transport),
    /// and emit NO Find state transition.
    private func completeUpgrade() {
        guard let kr = kRunner, let kt = kTransport, let oldRunner = runner else { return }
        upgradeTimeout?.cancel()
        upgradeTimeout = nil
        oldRunner.deactivateRanging()
        if oldRunner.transportKind == .ble {
            retainedRunner = oldRunner          // keep BLE authenticated + idle
            retainedTransport = activeTransport
            // The retained runner keeps its transport.onClosed → onFatal wiring, but
            // it is now the IDLE fallback: its own loss must NOT revert the healthy
            // upgraded transport (§10 A.7). Repoint it to just forget the fallback.
            oldRunner.onFatal = { [weak self] _ in self?.handleRetainedFatal() }
        } else {
            oldRunner.sendDisconnect(reason: .upgraded)
            activeTransport?.disconnect(reason: .upgraded)
            retainedRunner = nil
            retainedTransport = nil
        }
        runner = kr
        activeTransport = kt
        kr.activateRanging()
        wireUpgradeCallbacks(kr)
        kRunner = nil
        kTransport = nil
        kAckAccepted = false
        kConnected = false
        pendingUpgradeKind = nil
        upgradeInFlight = nil
        upgradeAckCache = nil
        upgradeBackoff.reset()
        upgradeBackoffUntil = nil
        reason = .upgraded          // telemetry / selectedTransport only (§10)
    }

    /// Tear down only the half-open K attempt and back off; the working transport
    /// and the Find state machine are untouched (§10 A.8).
    private func abandonUpgrade() {
        teardownKAttempt()
        pendingUpgradeKind = nil
        upgradeInFlight = nil
        upgradeBackoffUntil = Date().addingTimeInterval(upgradeBackoff.next())
    }

    private func teardownKAttempt() {
        upgradeTimeout?.cancel()
        upgradeTimeout = nil
        kRunner?.deactivateRanging()
        kTransport?.disconnect(reason: .transportLost)
        kRunner = nil
        kTransport = nil
        kAckAccepted = false
        kConnected = false
    }

    private func startUpgradeTimeout(attemptId a: UInt64) {
        upgradeTimeout?.cancel()
        upgradeTimeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 10 * 1_000_000_000)
            guard let self, !Task.isCancelled else { return }
            await MainActor.run {
                guard self.upgradeInFlight == a else { return }
                self.abandonUpgrade()
            }
        }
    }

    private func clearUpgradeState() {
        stopUpgradeEvaluation()
        upgradeTimeout?.cancel()
        upgradeTimeout = nil
        kTransport?.disconnect(reason: .normal)
        kRunner = nil
        kTransport = nil
        retainedTransport?.disconnect(reason: .normal)
        retainedRunner = nil
        retainedTransport = nil
        upgradeInFlight = nil
        pendingUpgradeKind = nil
        kAckAccepted = false
        kConnected = false
        upgradeAckCache = nil
        upgradeBackoffUntil = nil
        upgradeBackoff.reset()
        boundPeerCaps = nil
        logicalSid = nil
    }

    // MARK: §10 recovery loop

    /// A lost link (not an auth/identity fault) drops to signalLost, tears down
    /// the dead transport while keeping the find session, then backs off and
    /// re-enters transport search until the find deadline (§10).
    private func enterSignalLostAndRetry(reason: ReasonCode) {
        apply(.signalLost, reason: reason)
        guard state == .signalLost else { return }   // ignore from an unlinked state
        runner = nil
        activeTransport?.disconnect(reason: reason)  // may be an upgraded transport
        clearUpgradeState()
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
        retainedRunner?.sendDisconnect(reason: reason)   // §14: also tear down a
        activeTransport?.disconnect(reason: reason)       // retained BLE fallback
        clearUpgradeState()
        transports.teardown(reason: reason)
        activeTransport = nil
        ranging.stop()
        background.stop()
        haptics.stop()
        lastAnnouncedBand = nil
    }

    /// Apply an event through the shared reducer; ignore invalid pairs (§10).
    private func apply(_ event: FindEvent, reason: ReasonCode?) {
        guard let next = FindStateMachine.reduce(state, event) else { return }
        let previous = state
        if let reason { self.reason = reason }
        state = next
        if next != previous {
            announce(statusText)   // read each state change aloud
            background.update(statusText: statusText, proximityLabel: lastAnnouncedBand?.label)
        }
        if next == .signalLost { /* core clears; UI never shows stale */ }
        if next == .stopped || next == .expired || next == .failed {
            teardown(reason: reason ?? .normal)
        }
    }
}
