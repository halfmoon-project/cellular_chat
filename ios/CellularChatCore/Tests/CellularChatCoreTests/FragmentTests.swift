import XCTest
@testable import CellularChatCore

/// fragment_vectors.json (PROTOCOL_V2.md §9): MTU 23/185/512 plus the exact
/// malformed error taxonomy.
final class FragmentTests: XCTestCase {

    private func expectedError(_ name: String) -> ProtocolError {
        switch name {
        case "noRecordInProgress": return .fragNoRecordInProgress
        case "unexpectedFirst": return .fragUnexpectedFirst
        case "badCounter": return .fragBadCounter
        case "declaredLengthInvalid": return .fragDeclaredLengthInvalid
        case "lengthMismatch": return .fragLengthMismatch
        case "emptyChunk": return .fragEmptyChunk
        default: fatalError("unknown error name \(name)")
        }
    }

    func testFragmentAndReassemble() throws {
        let v = Vectors.loadObject("fragment_vectors.json")
        for c in v["cases"] as! [[String: Any]] {
            let name = c["name"] as! String
            let mtu = (c["mtu"] as! NSNumber).intValue
            let record = hexToBytes(c["recordHex"] as! String)
            let expected = (c["fragments"] as! [String])

            // Fragmenter produces exactly the fixture fragments.
            let produced = Fragmentation.fragment(record: record, mtu: mtu).map(bytesToHex)
            XCTAssertEqual(produced, expected, "fragmentation mismatch for \(name)")

            // Reassembler reconstructs the original record.
            let reasm = FragmentReassembler(clock: { 0 })
            var result: [UInt8]? = nil
            for frag in expected {
                result = try reasm.push(hexToBytes(frag))
            }
            XCTAssertEqual(result.map(bytesToHex), c["recordHex"] as? String, "reassembly mismatch for \(name)")
        }
    }

    func testMalformedFragmentsRejected() {
        let v = Vectors.loadObject("fragment_vectors.json")
        for c in v["malformed"] as! [[String: Any]] {
            let name = c["name"] as! String
            let expected = expectedError(c["error"] as! String)
            let frags = (c["fragments"] as! [String]).map(hexToBytes)
            let reasm = FragmentReassembler(clock: { 0 })

            var thrown: ProtocolError? = nil
            do {
                for frag in frags { _ = try reasm.push(frag) }
            } catch let e as ProtocolError {
                thrown = e
            } catch {
                XCTFail("\(name): unexpected error \(error)")
            }
            XCTAssertEqual(thrown, expected, "malformed case \(name)")
        }
    }

    func testReassemblyTimeout() {
        // A record in progress that exceeds the 10-second budget is rejected.
        var clockValue: TimeInterval = 0
        let reasm = FragmentReassembler(clock: { clockValue })
        let cases = Vectors.loadObject("fragment_vectors.json")["cases"] as! [[String: Any]]
        let longCase = cases.first { $0["name"] as! String == "longRecordMtu185" }!
        let record = hexToBytes(longCase["recordHex"] as! String)
        let frags = Fragmentation.fragment(record: record, mtu: 185)
        XCTAssertNil(try? reasm.push(frags[0]))
        clockValue = 11
        assertThrows(.fragTimeout, try reasm.push(frags[1]))
    }
}
