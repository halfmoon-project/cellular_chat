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

    // MARK: - Trend (shared contract Feature C)

    /// The pure least-squares trend is fully determined by the fixed parameters,
    /// so these expected enum values MUST match the Android implementation.
    func testTrendRisingIsApproachingHighConfidence() {
        let y = (0..<8).map { -80.0 + Double($0) }        // slope +1, no residual
        let (trend, conf) = RSSIProximityFilter.regressionTrend(y)
        XCTAssertEqual(trend, .approaching)
        XCTAssertEqual(conf, .high)
    }

    func testTrendFallingIsReceding() {
        let y = (0..<8).map { -70.0 - Double($0) }        // slope -1
        let (trend, conf) = RSSIProximityFilter.regressionTrend(y)
        XCTAssertEqual(trend, .receding)
        XCTAssertEqual(conf, .high)
    }

    func testTrendFlatIsSteady() {
        let (trend, _) = RSSIProximityFilter.regressionTrend([Double](repeating: -70, count: 8))
        XCTAssertEqual(trend, .steady)
    }

    func testTrendTooFewSamplesIsSteadyLow() {
        let (trend, conf) = RSSIProximityFilter.regressionTrend([-80, -79, -78])   // k=3 < 4
        XCTAssertEqual(trend, .steady)
        XCTAssertEqual(conf, .low)
    }

    func testTrendNoisyRisingIsApproachingButLowConfidence() {
        // slope ≈ 1.14 (>= +0.5 → approaching) but residual variance ≈ 22.85 > 16.
        let y: [Double] = [-75, -83, -71, -79, -67, -75]
        let (trend, conf) = RSSIProximityFilter.regressionTrend(y)
        XCTAssertEqual(trend, .approaching)
        XCTAssertEqual(conf, .low)
    }

    func testTrendSlopeBoundaryIsApproaching() {
        let y = (0..<6).map { -80.0 + 0.5 * Double($0) }  // slope exactly +0.5
        let (trend, _) = RSSIProximityFilter.regressionTrend(y)
        XCTAssertEqual(trend, .approaching)
    }

    func testIntegratedRisingRampReportsApproaching() {
        let filter = RSSIProximityFilter()
        for v in stride(from: -95.0, through: -55.0, by: 2.0) { filter.add(rssi: v) }
        XCTAssertEqual(filter.trend, .approaching)
        XCTAssertEqual(filter.trendConfidence, .high)
    }

    func testResetClearsTrend() {
        let filter = RSSIProximityFilter()
        for v in stride(from: -95.0, through: -55.0, by: 2.0) { filter.add(rssi: v) }
        filter.reset()
        XCTAssertEqual(filter.trend, .steady)
        XCTAssertEqual(filter.trendConfidence, .low)
    }
}
