import XCTest
import CellularChatCore
@testable import CellularChat

/// PairRecord persistence codec (Work Package A item 2): the Android-parity
/// `epoch` and the optional `peerPlatform`/`pairingHandle` fields round-trip, and
/// records written before these fields existed still decode to their defaults.
final class PairRecordCodecTests: XCTestCase {

    func testRoundTripPreservesNewFields() throws {
        let record = PairRecord(
            pairId: [1, 2, 3], roleCode: 1, peerStaticPub: [9, 9],
            negotiatedVersion: 2, alias: "친구", createdAt: 42,
            epoch: 7, peerPlatform: .android, pairingHandle: "wa-handle-1",
            revoked: false)
        let data = try JSONEncoder().encode(record)
        let decoded = try JSONDecoder().decode(PairRecord.self, from: data)
        XCTAssertEqual(decoded, record)
        XCTAssertEqual(decoded.epoch, 7)
        XCTAssertEqual(decoded.peerPlatform, .android)
        XCTAssertEqual(decoded.pairingHandle, "wa-handle-1")
    }

    func testDecodesLegacyRecordWithoutNewFieldsAsDefaults() throws {
        // A record persisted before epoch/peerPlatform/pairingHandle existed.
        let legacy = """
        {"pairId":[1,2,3],"roleCode":2,"peerStaticPub":[9,9],\
        "negotiatedVersion":2,"alias":"x","createdAt":1,"revoked":true}
        """
        let decoded = try JSONDecoder().decode(PairRecord.self, from: Data(legacy.utf8))
        XCTAssertEqual(decoded.epoch, 0)
        XCTAssertNil(decoded.peerPlatform)
        XCTAssertNil(decoded.pairingHandle)
        XCTAssertTrue(decoded.revoked)
        XCTAssertEqual(decoded.alias, "x")
    }
}
