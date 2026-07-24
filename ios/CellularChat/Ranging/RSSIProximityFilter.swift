import Foundation

/// Local approaching/steady/receding trend over the RSSI median history (shared
/// contract Feature C). Advisory text only; RSSI never yields a direction/arrow.
enum RSSITrend { case approaching, steady, receding }
enum TrendConfidence { case low, high }

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

    // Trend regression state (shared contract Feature C). A separate FIFO of the
    // most recent median values (Double dBm), IN ADDITION TO the raw band window.
    static let medianHistoryCapacity = 10       // MEDIAN_HISTORY
    static let minSamplesForTrend = 4           // MIN_SAMPLES_FOR_TREND
    static let highConfMinSamples = 6           // HIGH_CONF_MIN_SAMPLES
    static let approachingSlope = 0.5           // +dB per sample
    static let recedingSlope = -0.5             // -dB per sample
    static let residualVarianceCap = 16.0       // dB² (residual std ≈ 4 dB)

    private var medianHistory: [Double] = []
    private(set) var trend: RSSITrend = .steady
    private(set) var trendConfidence: TrendConfidence = .low

    init(window: Int = 5, hysteresis: Double = 5) {
        self.window = window
        self.margin = hysteresis
    }

    @discardableResult
    func add(rssi: Double) -> ProximityBand {
        samples.append(rssi)
        if samples.count > window { samples.removeFirst(samples.count - window) }
        // The trend history uses the averaged-middle median of the CURRENT raw
        // window (pinned cross-platform), independent of how the band rounds.
        medianHistory.append(Self.median(samples))
        if medianHistory.count > Self.medianHistoryCapacity {
            medianHistory.removeFirst(medianHistory.count - Self.medianHistoryCapacity)
        }
        (trend, trendConfidence) = Self.regressionTrend(medianHistory)
        band = classify(median: Self.median(samples), current: band)
        return band
    }

    func reset() {
        samples.removeAll()
        medianHistory.removeAll()
        band = .unknown
        trend = .steady
        trendConfidence = .low
    }

    /// Pure least-squares trend over the median history, oldest→newest. The exact
    /// parameters (shared contract Feature C) make this cross-platform-identical.
    static func regressionTrend(_ y: [Double]) -> (RSSITrend, TrendConfidence) {
        let k = y.count
        guard k >= minSamplesForTrend else { return (.steady, .low) }
        let meanX = Double(k - 1) / 2
        let meanY = y.reduce(0, +) / Double(k)
        var sxx = 0.0, sxy = 0.0
        for i in 0..<k {
            let dx = Double(i) - meanX
            sxx += dx * dx
            sxy += dx * (y[i] - meanY)
        }
        let slope = sxy / sxx
        let trend: RSSITrend
        if slope >= approachingSlope { trend = .approaching }
        else if slope <= recedingSlope { trend = .receding }
        else { trend = .steady }
        var residualSq = 0.0
        for i in 0..<k {
            let r = y[i] - (meanY + slope * (Double(i) - meanX))
            residualSq += r * r
        }
        let residualVariance = residualSq / Double(k)
        let confidence: TrendConfidence =
            (k >= highConfMinSamples && residualVariance <= residualVarianceCap) ? .high : .low
        return (trend, confidence)
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
