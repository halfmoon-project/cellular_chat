import XCTest
import NearbyInteraction
import simd
@testable import CellularChat

/// The arrow's axis convention: an angle that is right/left-flipped points users
/// the wrong way, which is worse than showing nothing.
final class NIAngleTests: XCTestCase {

    private func angle(_ d: simd_float3?, _ h: Float? = nil) -> Double? {
        NIAngle.horizontalRadians(direction: d, horizontalAngle: h)
    }

    func testDirectionVectorMapsToDeviceYaw() {
        XCTAssertEqual(angle(simd_float3(0, 0, -1))!, 0, accuracy: 1e-6)          // ahead
        XCTAssertEqual(angle(simd_float3(1, 0, 0))!, .pi / 2, accuracy: 1e-6)     // right
        XCTAssertEqual(angle(simd_float3(-1, 0, 0))!, -.pi / 2, accuracy: 1e-6)   // left
        XCTAssertEqual(abs(angle(simd_float3(0, 0, 1))!), .pi, accuracy: 1e-6)    // behind
    }

    func testVerticalComponentDoesNotChangeYaw() {
        let tilted = simd_normalize(simd_float3(1, 3, -1))
        XCTAssertEqual(angle(tilted)!, .pi / 4, accuracy: 1e-6)
    }

    func testFallsBackToHorizontalAngleOnlyWhenDirectionIsMissing() {
        XCTAssertEqual(angle(nil, 1.25)!, 1.25, accuracy: 1e-6)
        XCTAssertEqual(angle(simd_float3(0, 0, -1), 1.25)!, 0, accuracy: 1e-6)
        XCTAssertNil(angle(nil, nil))
    }

    /// A denied NI permission must not read like a device without UWB.
    func testFailureReasonSeparatesPermissionFromOtherFailures() {
        let denied = NIAngle.failureReason(NIError(.userDidNotAllow))
        XCTAssertEqual(denied, "근거리 상호작용 권한 거부됨")
        XCTAssertNotEqual(denied, NIAngle.failureReason(NIError(.unsupportedPlatform)))
        XCTAssertFalse(NIAngle.failureReason(CocoaError(.fileNoSuchFile)).isEmpty)
    }
}
