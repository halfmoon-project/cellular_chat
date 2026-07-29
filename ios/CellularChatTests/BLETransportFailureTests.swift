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

    // MARK: §9 notify backpressure

    /// A refused fragment must stay queued. Dropping it stalls the peer's
    /// reassembly into the 10-second §9 budget, which surfaces to the user as
    /// "went out of range" rather than as a send failure.
    func testRefusedFragmentStaysQueuedWithItsSuccessors() {
        let frags = (0..<5).map { Data([UInt8($0)]) }
        var sent: [Data] = []
        // The transmit queue accepts two, then fills.
        let remaining = BLETransport.drain(frags) { frag in
            guard sent.count < 2 else { return false }
            sent.append(frag)
            return true
        }
        XCTAssertEqual(sent, Array(frags.prefix(2)))
        XCTAssertEqual(remaining, Array(frags.suffix(3)))
    }

    /// Once the radio is ready again the rest goes out, in order, exactly once.
    func testResumeDrainsTheRemainderInOrder() {
        let frags = (0..<5).map { Data([UInt8($0)]) }
        var sent: [Data] = []
        var accept = 2
        var remaining = BLETransport.drain(frags) { frag in
            guard accept > 0 else { return false }
            accept -= 1; sent.append(frag); return true
        }
        accept = .max
        remaining = BLETransport.drain(remaining) { sent.append($0); return true }
        XCTAssertTrue(remaining.isEmpty)
        XCTAssertEqual(sent, frags)
    }

    func testDrainOfAnEmptyQueueIsANoOp() {
        var calls = 0
        let remaining = BLETransport.drain([]) { _ in calls += 1; return true }
        XCTAssertTrue(remaining.isEmpty)
        XCTAssertEqual(calls, 0)
    }
}
