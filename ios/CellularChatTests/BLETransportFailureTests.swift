import XCTest
import CoreBluetooth
@testable import CellularChat

/// CoreBluetooth manager-state → connect-failure mapping (Work Package B item 1).
/// A denied authorization must surface `.permissionRequired` so the Find UI shows
/// the settings path; a genuinely unavailable radio stays `.radioUnavailable`.
final class BLETransportFailureTests: XCTestCase {

    func testUnauthorizedMapsToPermissionRequired() {
        XCTAssertEqual(BLETransport.connectFailure(for: .unauthorized), .permissionRequired)
    }

    func testUnsupportedMapsToRadioUnavailable() {
        XCTAssertEqual(BLETransport.connectFailure(for: .unsupported), .radioUnavailable)
    }

    func testTransientStatesKeepWaiting() {
        // Powered off / resetting / unknown may still transition on: no failure yet.
        XCTAssertNil(BLETransport.connectFailure(for: .poweredOff))
        XCTAssertNil(BLETransport.connectFailure(for: .resetting))
        XCTAssertNil(BLETransport.connectFailure(for: .unknown))
    }

    func testPoweredOnIsNotAFailure() {
        XCTAssertNil(BLETransport.connectFailure(for: .poweredOn))
    }
}
