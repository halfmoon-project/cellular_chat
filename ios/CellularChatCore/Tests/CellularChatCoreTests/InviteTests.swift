import XCTest
@testable import CellularChatCore

/// invite_vectors.json (PROTOCOL_V2.md §4).
final class InviteTests: XCTestCase {

    func testValidInvitation() throws {
        let v = Vectors.loadObject("invite_vectors.json")
        let now = (v["nowUnixSeconds"] as! NSNumber).uint64Value
        let valid = v["valid"] as! [String: Any]

        let parsed = try Invitation.parse(text: valid["text"] as! String, nowUnixSeconds: now)
        XCTAssertEqual(bytesToHex(parsed.pairId), valid["pairIdHex"] as! String)
        XCTAssertEqual(bytesToHex(parsed.secret), valid["secretHex"] as! String)
        XCTAssertEqual(parsed.createdAt, (valid["createdAt"] as! NSNumber).uint64Value)

        // Encode round-trips to the fixture bytes and text.
        XCTAssertEqual(bytesToHex(parsed.encodedCBOR()), valid["cborHex"] as! String)
        XCTAssertEqual(parsed.text(), valid["text"] as! String)
    }

    func testRejectCases() {
        let v = Vectors.loadObject("invite_vectors.json")
        let now = (v["nowUnixSeconds"] as! NSNumber).uint64Value
        for entry in v["reject"] as! [[String: Any]] {
            let text = entry["text"] as! String
            let reason = entry["reason"] as! String
            assertThrows(.invalidInvitation,
                         try Invitation.parse(text: text, nowUnixSeconds: now),
                         "should reject: \(reason)")
        }
    }
}
