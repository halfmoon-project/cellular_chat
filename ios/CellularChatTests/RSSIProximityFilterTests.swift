import XCTest
@testable import CellularChat

/// Rolling-median + hysteresis RSSI filtering into proximity bands (PROTOCOL_V2.md §12).
final class RSSIProximityFilterTests: XCTestCase {

    func testMedianIgnoresSingleOutlier() {
        let filter = RSSIProximityFilter(window: 5, hysteresis: 5)
        // Strong signals with one dropout; the median stays in the near/veryNear band.
        for v in [-50.0, -52.0, -95.0, -51.0, -50.0] { filter.add(rssi: v) }
        XCTAssertEqual(filter.band, .veryNear)
    }

    func testClassifiesFarWhenWeak() {
        let filter = RSSIProximityFilter()
        for _ in 0..<5 { filter.add(rssi: -90) }
        XCTAssertEqual(filter.band, .far)
    }

    func testHysteresisPreventsFlappingAtBoundary() {
        let filter = RSSIProximityFilter(window: 3, hysteresis: 5)
        // Settle firmly in `near`.
        for _ in 0..<3 { filter.add(rssi: -65) }
        XCTAssertEqual(filter.band, .near)
        // A reading just past the veryNear boundary (-55) without clearing the
        // +5 margin must NOT promote the band.
        filter.add(rssi: -54)
        filter.add(rssi: -54)
        filter.add(rssi: -54)
        XCTAssertEqual(filter.band, .near)
        // Clearing the margin (>= -50) promotes to veryNear.
        for _ in 0..<3 { filter.add(rssi: -49) }
        XCTAssertEqual(filter.band, .veryNear)
    }

    func testResetReturnsToUnknown() {
        let filter = RSSIProximityFilter()
        filter.add(rssi: -40)
        filter.reset()
        XCTAssertEqual(filter.band, .unknown)
    }

    func testMedianEvenCount() {
        XCTAssertEqual(RSSIProximityFilter.median([-60, -40]), -50)
        XCTAssertEqual(RSSIProximityFilter.median([-70, -50, -60]), -60)
    }

    // MARK: - Trend

    /// The x-axis is SECONDS, so a fixture is (values, timestamps, prior trend).
    /// These expected enum values MUST match the Android implementation.
    private let cadence = 1.5   // the central's RSSI poll interval

    private func history(_ ys: [Double], dt: Double = 1.5) -> [(t: Double, rssi: Double)] {
        ys.enumerated().map { (t: Double($0.offset) * dt, rssi: $0.element) }
    }

    func testTrendRisingIsApproachingHighConfidence() {
        // +1 dB per 1.5 s = 0.667 dB/s >= the 0.6 entry threshold, no residual.
        let (trend, conf) = RSSIProximityFilter.regressionTrend(
            history((0..<8).map { -80.0 + Double($0) }), current: .steady)
        XCTAssertEqual(trend, .approaching)
        XCTAssertEqual(conf, .high)
    }

    func testTrendFallingIsReceding() {
        let (trend, conf) = RSSIProximityFilter.regressionTrend(
            history((0..<8).map { -70.0 - Double($0) }), current: .steady)
        XCTAssertEqual(trend, .receding)
        XCTAssertEqual(conf, .high)
    }

    func testTrendFlatIsSteady() {
        let (trend, _) = RSSIProximityFilter.regressionTrend(
            history([Double](repeating: -70, count: 8)), current: .steady)
        XCTAssertEqual(trend, .steady)
    }

    func testTrendTooFewSamplesIsSteadyLow() {
        let (trend, conf) = RSSIProximityFilter.regressionTrend(
            history([-80, -79, -78]), current: .steady)   // k=3 < 4
        XCTAssertEqual(trend, .steady)
        XCTAssertEqual(conf, .low)
    }

    /// Samples arriving in a burst describe too short an arc to fit a speed to,
    /// however many there are: the slope would be an artefact of the burst.
    func testTrendShortTimeSpanIsSteadyLow() {
        let (trend, conf) = RSSIProximityFilter.regressionTrend(
            history((0..<8).map { -80.0 + 2.0 * Double($0) }, dt: 0.2), current: .steady)
        XCTAssertEqual(trend, .steady)
        XCTAssertEqual(conf, .low)
    }

    func testTrendNoisyRisingIsApproachingButLowConfidence() {
        // slope ≈ 0.76 dB/s (>= 0.6 → approaching) but the residual variance
        // about that line is ≈ 34.3 dB², far past the 4 dB² cap.
        let (trend, conf) = RSSIProximityFilter.regressionTrend(
            history([-75, -83, -71, -79, -67, -75]), current: .steady)
        XCTAssertEqual(trend, .approaching)
        XCTAssertEqual(conf, .low)
    }

    /// The label is hidden at LOW confidence, so the residual-variance gate is
    /// hysteretic too: a variance between the two caps sustains a shown label
    /// but is not clean enough to start showing one.
    func testConfidenceHysteresisHoldsBetweenTheCaps() {
        // Residual variance ≈ 5.33 dB²: past the 4.0 entry, under the 8.0 exit.
        let y = history([-74, -74, -70, -70, -74, -74])
        XCTAssertEqual(RSSIProximityFilter.regressionTrend(y, current: .steady, confidence: .low).1, .low)
        XCTAssertEqual(RSSIProximityFilter.regressionTrend(y, current: .steady, confidence: .high).1, .high)
    }

    /// Hysteresis: a slope between the exit and entry thresholds sustains an
    /// existing label but cannot start one, and cannot reverse one either.
    func testTrendHysteresisHoldsButDoesNotStartOrReverse() {
        let rising = history((0..<8).map { -80.0 + 0.45 * 1.5 * Double($0) })  // +0.45 dB/s
        XCTAssertEqual(RSSIProximityFilter.regressionTrend(rising, current: .steady).0, .steady)
        XCTAssertEqual(RSSIProximityFilter.regressionTrend(rising, current: .approaching).0, .approaching)
        XCTAssertEqual(RSSIProximityFilter.regressionTrend(rising, current: .receding).0, .steady)
    }

    /// The entry threshold is what separates a walk from RSSI noise, so pin both
    /// sides of it (avoiding an exact-equality fixture, which floats can't hold).
    func testTrendEntryThresholdSeparatesWalkFromNoise() {
        let over = (0..<6).map { -80.0 + 0.62 * 1.5 * Double($0) }
        XCTAssertEqual(RSSIProximityFilter.regressionTrend(history(over), current: .steady).0, .approaching)
        let under = (0..<6).map { -80.0 + 0.58 * 1.5 * Double($0) }
        XCTAssertEqual(RSSIProximityFilter.regressionTrend(history(under), current: .steady).0, .steady)
    }

    func testIntegratedRisingRampReportsApproaching() {
        let filter = RSSIProximityFilter()
        for (i, v) in stride(from: -95.0, through: -55.0, by: 2.0).enumerated() {
            filter.add(rssi: v, at: Double(i) * cadence)     // +2 dB / 1.5 s = 1.33 dB/s
        }
        XCTAssertEqual(filter.trend, .approaching)
        XCTAssertEqual(filter.trendConfidence, .high)
    }

    /// A hole longer than the gap threshold must not be regressed across: the
    /// pre-gap samples describe a different situation entirely.
    func testGapResetsTheTrendHistory() {
        let filter = RSSIProximityFilter()
        for (i, v) in stride(from: -95.0, through: -55.0, by: 2.0).enumerated() {
            filter.add(rssi: v, at: Double(i) * cadence)
        }
        XCTAssertEqual(filter.trend, .approaching)
        let lastT = Double(20) * cadence
        filter.add(rssi: -55, at: lastT + RSSIProximityFilter.gapResetSeconds + 1)
        XCTAssertEqual(filter.trend, .steady)
        XCTAssertEqual(filter.trendConfidence, .low)
    }

    /// A backwards wall-clock step is a gap too — never a huge negative slope.
    func testBackwardsClockResetsTheTrendHistory() {
        let filter = RSSIProximityFilter()
        for (i, v) in stride(from: -95.0, through: -55.0, by: 2.0).enumerated() {
            filter.add(rssi: v, at: Double(i) * cadence)
        }
        filter.add(rssi: -55, at: 0)
        XCTAssertEqual(filter.trend, .steady)
        XCTAssertEqual(filter.trendConfidence, .low)
    }

    func testResetClearsTrend() {
        let filter = RSSIProximityFilter()
        for (i, v) in stride(from: -95.0, through: -55.0, by: 2.0).enumerated() {
            filter.add(rssi: v, at: Double(i) * cadence)
        }
        filter.reset()
        XCTAssertEqual(filter.trend, .steady)
        XCTAssertEqual(filter.trendConfidence, .low)
    }
}
