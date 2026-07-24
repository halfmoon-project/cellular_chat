import XCTest
import CellularChatCore
@testable import CellularChat

/// Camera-assist coaching text (Work Package C item 7): shown only while a UWB
/// method is active and camera assistance is supported, and only before a
/// direction is resolved. Text guidance only.
final class FindCoachingTests: XCTestCase {

    func testCoachesDuringUWBBeforeDirection() {
        XCTAssertNotNil(FindCoaching.cameraAssistText(method: .niPeer, state: .distanceOnly,
                                                      cameraAssistSupported: true))
        XCTAssertNotNil(FindCoaching.cameraAssistText(method: .uwbAppleInterop, state: .rangingStarting,
                                                      cameraAssistSupported: true))
    }

    func testSilentOnceDirectionResolved() {
        XCTAssertNil(FindCoaching.cameraAssistText(method: .niPeer, state: .directionAvailable,
                                                   cameraAssistSupported: true))
    }

    func testSilentWithoutCameraAssistance() {
        XCTAssertNil(FindCoaching.cameraAssistText(method: .niPeer, state: .distanceOnly,
                                                   cameraAssistSupported: false))
    }

    func testSilentForNonUWBMethods() {
        XCTAssertNil(FindCoaching.cameraAssistText(method: .bleRssi, state: .distanceOnly,
                                                   cameraAssistSupported: true))
        XCTAssertNil(FindCoaching.cameraAssistText(method: nil, state: .distanceOnly,
                                                   cameraAssistSupported: true))
    }
}
