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

    /// Message senders wired to the authenticated session by the session layer.
    var sendNiToken: ((Data) -> Void)?
    var sendAppleShareable: ((Data) -> Void)?

    private let peerRanger = ApplePeerRanger()
    private let interopRanger = AndroidInteropRanger()
    private let rssiFilter = RSSIProximityFilter()
    private var backoff = BoundedBackoff()
    private var retryTask: Task<Void, Never>?
    private var active = false

    init() {
        peerRanger.onSendToken = { [weak self] data in self?.sendNiToken?(data) }
        peerRanger.onMeasurement = { [weak self] m in self?.handleUWB(measurement: m) }
        interopRanger.onSendShareable = { [weak self] data in self?.sendAppleShareable?(data) }
        interopRanger.onMeasurement = { [weak self] m in self?.handleUWB(measurement: m) }
    }

    // MARK: lifecycle

    func start(local: CapabilitySet, peer: CapabilitySet) {
        let selection = RangingSelector.select(local: local, peer: peer)
        self.selection = selection
        active = true
        backoff.reset()
        beginSelectedMethod(selection)
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

    private func beginSelectedMethod(_ selection: RangingSelection) {
        switch selection.method {
        case .niPeer:
            stateText = "UWB 방향/거리 측정 준비 중"
            peerRanger.start(enableEDM: selection.edm)
        case .uwbAppleInterop:
            stateText = "UWB 연동 측정 준비 중 (실기기 검증 필요)"
            // Android sends apple_config first; we wait for receiveAppleConfig(_:).
        case .uwbAndroidOob:
            // Android-to-Android only; not applicable to an iOS local device.
            fallbackToRSSI()
        case .bleRssi:
            stateText = "근접도만 측정 (BLE)"
        }
    }

    // MARK: inbound ranging messages (from the authenticated session)

    func receiveNiToken(_ data: Data) { peerRanger.receivePeerToken(data) }
    func receiveAppleConfig(_ data: Data) { interopRanger.start(appleConfig: data) }

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
            await MainActor.run {
                if selection.method == .niPeer { self.peerRanger.start(enableEDM: selection.edm) }
                // uwb_apple_interop restarts when Android re-sends apple_config.
            }
        }
    }
}
