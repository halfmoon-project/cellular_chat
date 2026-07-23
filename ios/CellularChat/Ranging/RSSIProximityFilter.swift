import Foundation

/// Filters raw BLE RSSI into coarse proximity bands (PROTOCOL_V2.md §12):
/// rolling median over the last N samples plus hysteresis so the band does not
/// flap on a single noisy reading. RSSI is never shown as an exact distance and
/// never produces a direction/arrow.
final class RSSIProximityFilter {
    private let window: Int
    private let margin: Double          // hysteresis, dBm
    private var samples: [Double] = []
    private(set) var band: ProximityBand = .unknown

    // Band boundaries in dBm (a higher/less-negative value means closer).
    private let veryNearThreshold = -55.0
    private let nearThreshold = -75.0

    init(window: Int = 5, hysteresis: Double = 5) {
        self.window = window
        self.margin = hysteresis
    }

    @discardableResult
    func add(rssi: Double) -> ProximityBand {
        samples.append(rssi)
        if samples.count > window { samples.removeFirst(samples.count - window) }
        band = classify(median: Self.median(samples), current: band)
        return band
    }

    func reset() {
        samples.removeAll()
        band = .unknown
    }

    private func rawBand(_ median: Double) -> ProximityBand {
        if median >= veryNearThreshold { return .veryNear }
        if median >= nearThreshold { return .near }
        return .far
    }

    private func classify(median: Double, current: ProximityBand) -> ProximityBand {
        let raw = rawBand(median)
        // First real reading, or no change: accept directly.
        if current == .unknown || raw == current { return raw }
        // Otherwise require the median to clear the crossed boundary by `margin`.
        switch (current, raw) {
        case (.far, _):
            if median >= veryNearThreshold + margin { return .veryNear }
            if median >= nearThreshold + margin { return .near }
            return current
        case (.near, .veryNear):
            return median >= veryNearThreshold + margin ? .veryNear : current
        case (.near, .far):
            return median < nearThreshold - margin ? .far : current
        case (.veryNear, _):
            if median < nearThreshold - margin { return .far }
            if median < veryNearThreshold - margin { return .near }
            return current
        default:
            return raw
        }
    }

    static func median(_ xs: [Double]) -> Double {
        guard !xs.isEmpty else { return 0 }
        let s = xs.sorted()
        let n = s.count
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2
    }
}
