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
}
