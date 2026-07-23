import Foundation
import Combine
import CellularChatCore

/// Bounded exponential backoff (PROTOCOL_V2.md §12): initial 5 s, ×2, cap 60 s.
struct BoundedBackoff {
    let initial: TimeInterval
    let factor: Double
    let cap: TimeInterval
    private var current: TimeInterval?

    init(initial: TimeInterval = 5, factor: Double = 2, cap: TimeInterval = 60) {
        self.initial = initial
        self.factor = factor
        self.cap = cap
    }

    mutating func next() -> TimeInterval {
        let value = current.map { min($0 * factor, cap) } ?? initial
        current = value
        return value
    }

    mutating func reset() { current = nil }
}

/// Ranging method selection + degradation (PROTOCOL_V2.md §12). Selects the
/// method deterministically from both CapabilitySets, drives the platform
/// ranger, and on UWB invalidation/sample loss keeps the authenticated session,
/// falls back immediately to BLE RSSI proximity, and retries UWB with bounded
/// backoff. A ranging failure is never surfaced as an auth/pairing failure.
@MainActor
final class RangingCoordinator: ObservableObject {

    @Published private(set) var measurement: Measurement?
    @Published private(set) var selection: RangingSelection?
    @Published private(set) var stateText: String = "찾는 중"

    /// Sends an authenticated ranging session message (§8); wired by the session
    /// layer. The coordinator owns the monotonic `attemptId` and stamps it into
    /// every ranging body (§10/§12).
    var sendMessage: ((SessionMsgType, CBOR) -> Void)?

    private let peerRanger = ApplePeerRanger()
    private let interopRanger = AndroidInteropRanger()
    private let rssiFilter = RSSIProximityFilter()
    private var backoff = BoundedBackoff()
    private var retryTask: Task<Void, Never>?
    private var active = false

    // Ranging-attempt negotiation state (§8 offer/accept/start, §10/§12).
    // `attemptCounter` yields a monotonically increasing attemptId per sid;
    // `startedAttempt` gives (sid, attemptId) idempotency so a duplicate
    // start/stop is a no-op. `isOfferer` is the ni_peer ranging controller.
    private var attemptCounter: UInt64 = 0
    private var currentAttemptId: UInt64?
    private var startedAttempt: UInt64?
    private var isOfferer = false

    init() {
        peerRanger.onSendToken = { [weak self] data in self?.sendRangingData(.niToken, data) }
        peerRanger.onMeasurement = { [weak self] m in self?.handleUWB(measurement: m) }
        interopRanger.onSendShareable = { [weak self] data in self?.sendRangingData(.appleShareable, data) }
        interopRanger.onMeasurement = { [weak self] m in self?.handleUWB(measurement: m) }
    }

    private func nextAttemptId() -> UInt64 { attemptCounter += 1; return attemptCounter }

    private func sendMap(_ type: SessionMsgType, _ pairs: [CBORPair]) { sendMessage?(type, .map(pairs)) }

    /// Sends `ni_token`/`apple_shareable` for the current attempt (§10/§12).
    private func sendRangingData(_ type: SessionMsgType, _ data: Data) {
        guard let id = currentAttemptId else { return }
        sendMap(type, [CBORPair(.uint(1), .uint(id)), CBORPair(.uint(2), .bytes(Array(data)))])
    }

    // MARK: lifecycle

    func start(local: CapabilitySet, peer: CapabilitySet, isInitiator: Bool) {
        let selection = RangingSelector.select(local: local, peer: peer)
        self.selection = selection
        active = true
        backoff.reset()
        attemptCounter = 0
        currentAttemptId = nil
        startedAttempt = nil
        // The ni_peer ranging controller (offerer) is the Noise initiator; every
        // other method is peer-driven (Android sends apple_config directly, never
        // an offer) or a local fallback, so iOS offers only for ni_peer.
        isOfferer = (selection.method == .niPeer) && isInitiator
        beginAttempt()
    }

    func stop() {
        active = false
        retryTask?.cancel()
        retryTask = nil
        peerRanger.stop()
        interopRanger.stop()
        rssiFilter.reset()
        measurement = nil
        stateText = "중지됨"
    }

    /// Starts one ranging attempt with a fresh `attemptId` (§12). The offerer
    /// drives ranging_offer → ranging_start; the controlee waits for the peer's
    /// offer (ni_peer) or apple_config (uwb_apple_interop).
    private func beginAttempt() {
        guard active, let selection else { return }
        switch selection.method {
        case .niPeer:
            if isOfferer {
                let id = nextAttemptId()
                currentAttemptId = id
                stateText = "UWB 측정 협상 중"
                sendMap(.rangingOffer, [CBORPair(.uint(1), .uint(id)),
                                        CBORPair(.uint(2), .uint(RangingMethod.niPeer.rawValue))])
            } else {
                stateText = "UWB 방향/거리 측정 준비 중"   // await ranging_offer
            }
        case .uwbAppleInterop:
            stateText = "UWB 연동 측정 준비 중 (실기기 검증 필요)"   // await apple_config
        case .uwbAndroidOob:
            fallbackToRSSI()   // Android-to-Android only; degrade on an iOS device.
        case .bleRssi:
            stateText = "근접도만 측정 (BLE)"   // driven by feedRSSI(_:)
        }
    }

    // MARK: inbound ranging messages (from the authenticated session)

    /// Routes an inbound ranging session message (§8), mirroring the Android
    /// peer's `onSessionMessage`. Unknown fields and duplicate operations are
    /// safe no-ops (§10 (sid, attemptId) idempotency).
    func handleSessionMessage(_ type: SessionMsgType, body: CBOR) {
        guard active else { return }
        let attemptId = body.value(forKey: 1)?.asUInt
        switch type {
        case .rangingOffer:
            guard let id = attemptId,
                  let raw = body.value(forKey: 2)?.asUInt,
                  let method = RangingMethod(rawValue: raw) else { return }
            onRangingOffer(attemptId: id, method: method)
        case .rangingAccept:
            if let id = attemptId { onRangingAccept(attemptId: id) }
        case .rangingStart:
            if let id = attemptId { onRangingStart(attemptId: id) }
        case .rangingStop:
            if let id = attemptId { onRangingStop(attemptId: id) }
        case .rangingError:
            if let id = attemptId { onRangingError(attemptId: id) }
        case .niToken:
            if let id = attemptId, let data = body.value(forKey: 2)?.asBytes {
                onNiToken(attemptId: id, data: Data(data))
            }
        case .appleConfig:
            if let id = attemptId, let data = body.value(forKey: 2)?.asBytes {
                onAppleConfig(attemptId: id, data: Data(data))
            }
        default:
            break   // apple_shareable / oob_data are outbound-only on iOS
        }
    }

    // Controlee: the peer offered ranging → accept and await ranging_start (§8).
    private func onRangingOffer(attemptId: UInt64, method: RangingMethod) {
        currentAttemptId = attemptId
        sendMap(.rangingAccept, [CBORPair(.uint(1), .uint(attemptId)),
                                 CBORPair(.uint(2), .uint(method.rawValue))])
    }

    // Offerer: the peer accepted → send ranging_start and begin the method.
    private func onRangingAccept(attemptId: UInt64) {
        guard attemptId == currentAttemptId else { return }
        sendMap(.rangingStart, [CBORPair(.uint(1), .uint(attemptId))])
        beginMethod(attemptId: attemptId)
    }

    // Controlee: the offerer started the attempt → begin the method.
    private func onRangingStart(attemptId: UInt64) {
        guard attemptId == currentAttemptId else { return }
        beginMethod(attemptId: attemptId)
    }

    // A duplicate (sid, attemptId) start is an idempotent no-op (§10).
    private func beginMethod(attemptId: UInt64) {
        guard active, startedAttempt != attemptId else { return }
        startedAttempt = attemptId
        if selection?.method == .niPeer { peerRanger.start(enableEDM: selection?.edm ?? false) }
    }

    private func onNiToken(attemptId: UInt64, data: Data) {
        guard attemptId == currentAttemptId else { return }
        peerRanger.receivePeerToken(data)
    }

    // Android drives uwb_apple_interop by sending apple_config directly (no
    // offer); adopt its attemptId so apple_shareable echoes it (§10/§12).
    private func onAppleConfig(attemptId: UInt64, data: Data) {
        guard startedAttempt != attemptId else { return }   // idempotent
        currentAttemptId = attemptId
        startedAttempt = attemptId
        interopRanger.start(appleConfig: data)
    }

    // Peer stopped this attempt: keep the session, drop the ranger (§10/§12).
    private func onRangingStop(attemptId: UInt64) {
        guard startedAttempt == attemptId else { return }   // idempotent / stale
        startedAttempt = nil
        switch selection?.method {
        case .niPeer?: peerRanger.stop()
        case .uwbAppleInterop?: interopRanger.stop()
        default: break
        }
    }

    // Peer reported a ranging error: never an auth failure — degrade + retry (§12).
    private func onRangingError(attemptId: UInt64) {
        guard attemptId == currentAttemptId else { return }
        startedAttempt = nil
        fallbackToRSSI()
        scheduleUWBRetry()
    }

    /// Feed a raw RSSI reading from the BLE link for the proximity fallback (§12).
    func feedRSSI(_ rssi: Double) {
        guard active else { return }
        let band = rssiFilter.add(rssi: rssi)
        // Only surface RSSI proximity when UWB is not currently providing a sample.
        if selection?.method == .bleRssi || measurement == nil || measurement?.proximity != nil {
            measurement = Measurement(timestamp: Date(), method: .bleRssi,
                                      distanceMeters: nil, horizontalAngleRadians: nil, proximity: band)
        }
    }

    // MARK: UWB result handling + backoff

    private func handleUWB(measurement m: Measurement?) {
        guard active else { return }
        if let m {
            backoff.reset()
            measurement = m
            stateText = m.horizontalAngleRadians != nil ? "방향 측정 중"
                : (m.distanceMeters != nil ? "거리 측정 중" : "신호 탐색 중")
        } else {
            // UWB sample lost/invalidated: keep the session, drop to RSSI now,
            // retry UWB after bounded backoff.
            fallbackToRSSI()
            scheduleUWBRetry()
        }
    }

    private func fallbackToRSSI() {
        measurement = Measurement(timestamp: Date(), method: .bleRssi,
                                  distanceMeters: nil, horizontalAngleRadians: nil,
                                  proximity: rssiFilter.band)
        stateText = "UWB 신호 없음 · 근접도만 표시"
    }

    private func scheduleUWBRetry() {
        guard let selection, selection.method == .niPeer || selection.method == .uwbAppleInterop else { return }
        retryTask?.cancel()
        let delay = backoff.next()
        retryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard let self, self.active, !Task.isCancelled else { return }
            // A retry uses a fresh attemptId (§12): the offerer re-offers, the
            // controlee awaits the peer's new offer / apple_config.
            await MainActor.run { self.beginAttempt() }
        }
    }
}
