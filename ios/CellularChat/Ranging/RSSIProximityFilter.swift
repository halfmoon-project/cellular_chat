import Foundation

/// Local approaching/steady/receding trend over the RSSI median history.
/// Advisory text only; RSSI never yields a direction/arrow.
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

    // Trend regression parameters. These are pinned in code on both platforms so
    // a given (RSSI, timestamp) sequence yields identical enums; no external
    // spec defines them — `shared/PROTOCOL_V2.md` §12 covers only the bands.
    static let medianHistoryCapacity = 10       // MEDIAN_HISTORY
    static let minSamplesForTrend = 4           // MIN_SAMPLES_FOR_TREND
    static let highConfMinSamples = 6           // HIGH_CONF_MIN_SAMPLES
    /// Regression is over SECONDS, so the thresholds are a physical speed, not a
    /// per-sample step: a 1 m/s walk at distance d gives ~8.7/d dB/s. Enter at
    /// 0.6 dB/s (walking within ~14 m); measured static-noise slope sd is
    /// 0.25 dB/s at 4 dB RSSI noise, 0.38 dB/s at 6 dB, so a lower entry
    /// threshold sits inside the noise floor and labels a stationary peer.
    static let enterSlopeDbPerSecond = 0.6
    /// Hysteresis: once labelled, hold the label until the slope decays to half
    /// the entry threshold. Flipping to the OPPOSITE label still needs a full
    /// entry crossing.
    static let exitSlopeDbPerSecond = 0.3
    static let minSpanSeconds = 3.0             // no trend from a shorter arc
    static let highConfMinSpanSeconds = 6.0
    /// Residual variance of the median history about the fitted line, using the
    /// unbiased (k-2) divisor. 4 dB² ≈ 2 dB residual std: above that the link is
    /// bouncing (multipath/body blocking) and the slope means nothing.
    /// Hysteresis here too — the label is hidden at LOW, so a hard threshold
    /// would blink a latched trend on and off as the variance crosses it.
    static let residualVarianceEnter = 4.0
    static let residualVarianceExit = 8.0
    /// A hole this long (backgrounding, link stall, dropped reads) means the old
    /// samples describe a different situation: start the history over rather than
    /// regress across the gap. A backwards clock step resets for the same reason.
    static let gapResetSeconds = 10.0

    private var medianHistory: [(t: Double, rssi: Double)] = []
    private(set) var trend: RSSITrend = .steady
    private(set) var trendConfidence: TrendConfidence = .low

    init(window: Int = 5, hysteresis: Double = 5) {
        self.window = window
        self.margin = hysteresis
    }

    /// Feeds one raw reading. `timestamp` is wall-clock seconds, stamped as close
    /// to the radio read as possible (a queue hop compresses the regression arc).
    @discardableResult
    func add(rssi: Double, at timestamp: TimeInterval = Date().timeIntervalSince1970) -> ProximityBand {
        if let last = medianHistory.last {
            let elapsed = timestamp - last.t
            if elapsed < 0 || elapsed > Self.gapResetSeconds { clearHistory() }
        }
        samples.append(rssi)
        if samples.count > window { samples.removeFirst(samples.count - window) }
        // The trend history uses the averaged-middle median of the CURRENT raw
        // window (pinned cross-platform), independent of how the band rounds.
        medianHistory.append((timestamp, Self.median(samples)))
        if medianHistory.count > Self.medianHistoryCapacity {
            medianHistory.removeFirst(medianHistory.count - Self.medianHistoryCapacity)
        }
        (trend, trendConfidence) = Self.regressionTrend(
            medianHistory, current: trend, confidence: trendConfidence)
        band = classify(median: Self.median(samples), current: band)
        return band
    }

    func reset() {
        clearHistory()
        band = .unknown
    }

    /// Drops the sampling history but keeps the band: a stale window must not
    /// feed either the median or the regression after a gap.
    private func clearHistory() {
        samples.removeAll()
        medianHistory.removeAll()
        trend = .steady
        trendConfidence = .low
    }

    /// Pure least-squares trend over the median history, oldest→newest, with time
    /// (seconds) as the x-axis. `current`/`confidence` supply the hysteresis state
    /// (both thresholds are directional). The exact parameters are pinned so this
    /// is cross-platform-identical.
    static func regressionTrend(
        _ history: [(t: Double, rssi: Double)],
        current: RSSITrend,
        confidence: TrendConfidence = .low
    ) -> (RSSITrend, TrendConfidence) {
        let k = history.count
        guard k >= minSamplesForTrend else { return (.steady, .low) }
        let span = history[k - 1].t - history[0].t
        guard span >= minSpanSeconds else { return (.steady, .low) }
        let meanT = history.reduce(0) { $0 + $1.t } / Double(k)
        let meanY = history.reduce(0) { $0 + $1.rssi } / Double(k)
        var stt = 0.0, sty = 0.0
        for p in history {
            let dt = p.t - meanT
            stt += dt * dt
            sty += dt * (p.rssi - meanY)
        }
        let slope = sty / stt

        // Hysteresis: hold at the exit threshold, reverse only at the entry one.
        let trend: RSSITrend
        switch current {
        case .approaching:
            trend = slope >= exitSlopeDbPerSecond ? .approaching
                : (slope <= -enterSlopeDbPerSecond ? .receding : .steady)
        case .receding:
            trend = slope <= -exitSlopeDbPerSecond ? .receding
                : (slope >= enterSlopeDbPerSecond ? .approaching : .steady)
        case .steady:
            trend = slope >= enterSlopeDbPerSecond ? .approaching
                : (slope <= -enterSlopeDbPerSecond ? .receding : .steady)
        }

        var residualSq = 0.0
        for p in history {
            let r = p.rssi - (meanY + slope * (p.t - meanT))
            residualSq += r * r
        }
        let residualVariance = residualSq / Double(max(k - 2, 1))
        let cap = confidence == .high ? residualVarianceExit : residualVarianceEnter
        let newConfidence: TrendConfidence =
            (k >= highConfMinSamples && span >= highConfMinSpanSeconds
                && residualVariance <= cap) ? .high : .low
        return (trend, newConfidence)
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
