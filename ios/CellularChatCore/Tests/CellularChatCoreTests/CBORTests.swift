import XCTest
@testable import CellularChatCore

final class CBORTests: XCTestCase {

    func testAcceptRoundTripsCanonically() throws {
        let v = Vectors.loadObject("cbor_vectors.json")
        let accept = v["accept"] as! [[String: Any]]
        XCTAssertFalse(accept.isEmpty)
        for entry in accept {
            let hex = entry["hex"] as! String
            let bytes = hexToBytes(hex)
            let decoded = try CBORCoder.decode(bytes)
            let reencoded = CBORCoder.encode(decoded)
            XCTAssertEqual(bytesToHex(reencoded), hex,
                           "canonical round-trip failed for diag \(entry["diag"] ?? "?")")
        }
    }

    func testRejectCasesThrow() {
        let v = Vectors.loadObject("cbor_vectors.json")
        let reject = v["reject"] as! [[String: Any]]
        XCTAssertFalse(reject.isEmpty)
        for entry in reject {
            let hex = entry["hex"] as! String
            let reason = entry["reason"] as! String
            XCTAssertThrowsError(try CBORCoder.decode(hexToBytes(hex)),
                                 "should reject: \(reason)") { error in
                XCTAssertTrue(error is ProtocolError, "wrong error type for \(reason): \(error)")
            }
        }
    }
}
