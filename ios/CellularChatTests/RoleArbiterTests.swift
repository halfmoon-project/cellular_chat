import XCTest
import CellularChatCore
@testable import CellularChat

/// Connection-ownership tie-breaks (PROTOCOL_V2.md §9/§10).
final class RoleArbiterTests: XCTestCase {

    func testCrossPlatformMakesIOSCentral() {
        // iOS is always the central/scanner in a cross-platform pair, regardless
        // of key ordering.
        let bigKey = [UInt8](repeating: 0xFF, count: 32)
        let smallKey = [UInt8](repeating: 0x00, count: 32)
        XCTAssertTrue(RoleArbiter.localIsBLECentral(localOS: .ios, peerOS: .android,
                                                    localStatic: bigKey, peerStatic: smallKey))
        XCTAssertFalse(RoleArbiter.localIsBLECentral(localOS: .android, peerOS: .ios,
                                                     localStatic: smallKey, peerStatic: bigKey))
    }

    func testSamePlatformUsesSmallerKey() {
        let small: [UInt8] = [0x00, 0x10]
        let big: [UInt8] = [0x00, 0x20]
        XCTAssertTrue(RoleArbiter.localIsBLECentral(localOS: .ios, peerOS: .ios,
                                                    localStatic: small, peerStatic: big))
        XCTAssertFalse(RoleArbiter.localIsBLECentral(localOS: .ios, peerOS: .ios,
                                                     localStatic: big, peerStatic: small))
    }

    func testDuplicateKeepsSmallerInitiator() {
        let small: [UInt8] = [0x01, 0x00, 0x00]
        let big: [UInt8] = [0x01, 0x00, 0x01]
        XCTAssertTrue(RoleArbiter.keepLocalInitiatedDuplicate(localStatic: small, peerStatic: big))
        XCTAssertFalse(RoleArbiter.keepLocalInitiatedDuplicate(localStatic: big, peerStatic: small))
    }

    func testBytewiseLessIsPrefixOrdered() {
        XCTAssertTrue(RoleArbiter.bytewiseLess([0x01, 0x02], [0x01, 0x02, 0x00]))  // prefix is smaller
        XCTAssertFalse(RoleArbiter.bytewiseLess([0x01, 0x02], [0x01, 0x02]))       // equal is not less
    }
}
