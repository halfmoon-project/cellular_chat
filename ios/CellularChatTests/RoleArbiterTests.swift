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

    // MARK: Find-session initiator side (§4/§9/§10)

    func testUnknownOrCrossPlatformKeepsInitiatorDefault() {
        let big = [UInt8](repeating: 0xFF, count: 32)
        let small = [UInt8](repeating: 0x00, count: 32)
        // A not-yet-known peer keeps the default iOS-initiator direction whatever the keys say.
        XCTAssertTrue(RoleArbiter.localIsInitiatorSide(peerPlatform: nil, localStatic: big, peerStatic: small))
        XCTAssertTrue(RoleArbiter.localIsInitiatorSide(peerPlatform: nil, localStatic: small, peerStatic: big))
        // A cross-platform (Android) peer likewise keeps iOS as the initiator.
        XCTAssertTrue(RoleArbiter.localIsInitiatorSide(peerPlatform: .android, localStatic: big, peerStatic: small))
    }

    func testSamePlatformInitiatorSideDerivedFromKeys() {
        let small: [UInt8] = [0x00, 0x10]
        let big: [UInt8] = [0x00, 0x20]
        // iOS↔iOS: the bytewise-smaller key is the initiator side (central/subscriber/initiator).
        XCTAssertTrue(RoleArbiter.localIsInitiatorSide(peerPlatform: .ios, localStatic: small, peerStatic: big))
        XCTAssertFalse(RoleArbiter.localIsInitiatorSide(peerPlatform: .ios, localStatic: big, peerStatic: small))
    }

    // MARK: Duplicate resolution from this device's view (§10)

    func testKeepNewDuplicateResolvesByInitiatorKey() {
        let small: [UInt8] = [0x01, 0x00]
        let big: [UInt8] = [0x01, 0x01]
        // We initiated the new connection: it wins iff our key is the smaller one.
        XCTAssertTrue(RoleArbiter.keepNewDuplicate(localInitiatedNew: true, localStatic: small, peerStatic: big))
        XCTAssertFalse(RoleArbiter.keepNewDuplicate(localInitiatedNew: true, localStatic: big, peerStatic: small))
        // The peer initiated the new connection: it wins iff the peer's key is smaller.
        XCTAssertTrue(RoleArbiter.keepNewDuplicate(localInitiatedNew: false, localStatic: big, peerStatic: small))
        XCTAssertFalse(RoleArbiter.keepNewDuplicate(localInitiatedNew: false, localStatic: small, peerStatic: big))
    }
}
