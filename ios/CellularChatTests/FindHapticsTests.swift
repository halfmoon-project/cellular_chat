import XCTest
import CellularChatCore
@testable import CellularChat

/// Proximity-driven haptic cadence (Work Package C item 1): closer = faster,
/// driven only by fresh distance/proximity; no signal = silent.
final class FindHapticsTests: XCTestCase {

    private func distance(_ d: Double) -> CellularChat.Measurement {
        CellularChat.Measurement(timestamp: Date(), method: .niPeer, distanceMeters: d,
                                 horizontalAngleRadians: nil, proximity: nil)
    }
    private func band(_ b: ProximityBand) -> CellularChat.Measurement {
        CellularChat.Measurement(timestamp: Date(), method: .bleRssi, distanceMeters: nil,
                                 horizontalAngleRadians: nil, proximity: b)
    }

    @MainActor
    func testCloserDistancePulsesFaster() throws {
        let near = try XCTUnwrap(FindHaptics.interval(for: distance(0)))
        let far = try XCTUnwrap(FindHaptics.interval(for: distance(10)))
        XCTAssertEqual(near, 0.25, accuracy: 0.001)
        XCTAssertEqual(far, 1.5, accuracy: 0.001)
        XCTAssertLessThan(near, far)
        XCTAssertEqual(FindHaptics.interval(for: distance(100)), 1.5)   // clamped
    }

    @MainActor
    func testProximityBandsMapCloserToFaster() {
        XCTAssertEqual(FindHaptics.interval(for: band(.veryNear)), 0.3)
        XCTAssertEqual(FindHaptics.interval(for: band(.near)), 0.8)
        XCTAssertEqual(FindHaptics.interval(for: band(.far)), 1.5)
    }

    @MainActor
    func testNoTruthfulSampleStaysSilent() {
        XCTAssertNil(FindHaptics.interval(for: band(.unknown)))
        XCTAssertNil(FindHaptics.interval(for: CellularChat.Measurement(
            timestamp: Date(), method: .bleRssi, distanceMeters: nil,
            horizontalAngleRadians: nil, proximity: nil)))
    }
}
