import XCTest
@testable import CellularChatCore

/// capability_selection.json (PROTOCOL_V2.md §12). Selection is symmetric.
final class CapabilityTests: XCTestCase {

    private func caps(_ dict: [String: Any]) -> CapabilitySet {
        let os: OSKind = (dict["os"] as! String) == "ios" ? .ios : .android
        func flag(_ k: String) -> Bool { (dict[k] as? Bool) ?? false }
        return CapabilitySet(
            os: os,
            uwbPresent: flag("uwbPresent"),
            uwbAzimuth: flag("uwbAzimuth"),
            appleInteropUwb: flag("appleInteropUwb"),
            niEdm: flag("niEdm"))
    }

    func testRangingSelectionMatrix() {
        let v = Vectors.loadObject("capability_selection.json")
        let cases = v["cases"] as! [[String: Any]]
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let local = caps(c["local"] as! [String: Any])
            let peer = caps(c["peer"] as! [String: Any])
            let expectedMethod = RangingMethod(rawValue: (c["method"] as! NSNumber).uint64Value)!
            let expectedEdm = c["edm"] as! Bool

            let sel = RangingSelector.select(local: local, peer: peer)
            XCTAssertEqual(sel.method, expectedMethod, "method for \(c)")
            XCTAssertEqual(sel.edm, expectedEdm, "edm for \(c)")

            // Symmetric: swapping local/peer yields the same result.
            let swapped = RangingSelector.select(local: peer, peer: local)
            XCTAssertEqual(swapped.method, expectedMethod, "symmetry method for \(c)")
            XCTAssertEqual(swapped.edm, expectedEdm, "symmetry edm for \(c)")
        }
    }

    func testCapabilitySetForwardCompatibility() throws {
        // Unknown keys ignored; missing keys default to false/empty (§11).
        let cbor = CBOR.map([
            CBORPair(.uint(1), .uint(2)),       // os = ios
            CBORPair(.uint(8), .bool(true)),    // uwbPresent
            CBORPair(.uint(99), .text("future")), // unknown key: ignored
        ])
        let set = try CapabilitySet.decode(cbor)
        XCTAssertEqual(set.os, .ios)
        XCTAssertTrue(set.uwbPresent)
        XCTAssertFalse(set.wifiAware)
        XCTAssertEqual(set.osVersion, "")
    }
}
