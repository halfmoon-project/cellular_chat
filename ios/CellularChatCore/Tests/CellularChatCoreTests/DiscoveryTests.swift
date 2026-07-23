import XCTest
@testable import CellularChatCore

/// discovery_vectors.json (PROTOCOL_V2.md §7) plus the acceptance-window helper.
final class DiscoveryTests: XCTestCase {

    func testTokensExact() {
        let dv = Vectors.loadObject("discovery_vectors.json")
        let der = Vectors.loadObject("derivation_vectors.json")
        let discKeyA = hexToBytes(der["discKeyAHex"] as! String)
        let discKeyB = hexToBytes(der["discKeyBHex"] as! String)

        let unix = (dv["unixSeconds"] as! NSNumber).uint64Value
        XCTAssertEqual(Discovery.epoch(unixSeconds: unix), (dv["epoch"] as! NSNumber).uint64Value)

        for entry in dv["tokens"] as! [[String: Any]] {
            let role: PairRole = (entry["role"] as! String) == "A" ? .a : .b
            let key = role == .a ? discKeyA : discKeyB
            let epoch = (entry["epoch"] as! NSNumber).uint64Value
            let token = Discovery.token(discoveryKey: key, epoch: epoch, role: role)
            XCTAssertEqual(bytesToHex(token), entry["tokenHex"] as! String)
        }
    }

    func testAcceptanceWindow() {
        let der = Vectors.loadObject("derivation_vectors.json")
        let discKeyA = hexToBytes(der["discKeyAHex"] as! String)
        let dv = Vectors.loadObject("discovery_vectors.json")
        let unix = (dv["unixSeconds"] as! NSNumber).uint64Value
        let e = Discovery.epoch(unixSeconds: unix)

        let accepted = Discovery.acceptanceTokens(discoveryKey: discKeyA, unixSeconds: unix, role: .a)
        XCTAssertEqual(accepted.count, 3)
        // Current and both adjacent epochs are accepted.
        for ep in [e - 1, e, e + 1] {
            let t = Discovery.token(discoveryKey: discKeyA, epoch: ep, role: .a)
            XCTAssertTrue(Discovery.accepts(candidate: t, discoveryKey: discKeyA, unixSeconds: unix, role: .a))
        }
        // An epoch outside the window (E+2) is rejected.
        let outside = Discovery.token(discoveryKey: discKeyA, epoch: e + 2, role: .a)
        XCTAssertFalse(Discovery.accepts(candidate: outside, discoveryKey: discKeyA, unixSeconds: unix, role: .a))
        // A random token is rejected.
        XCTAssertFalse(Discovery.accepts(candidate: [UInt8](repeating: 0, count: 16),
                                         discoveryKey: discKeyA, unixSeconds: unix, role: .a))
    }
}
