import XCTest
@testable import CellularChatCore

/// derivation_vectors.json (PROTOCOL_V2.md §2).
final class DerivationTests: XCTestCase {

    func testAllDerivations() throws {
        let d = Vectors.loadObject("derivation_vectors.json")
        func hb(_ k: String) -> [UInt8] { hexToBytes(d[k] as! String) }

        let secret = hb("secretHex")
        let pairId = hb("pairIdHex")
        let staticAPriv = hb("staticAPrivHex")
        let staticAPub = hb("staticAPubHex")
        let staticBPriv = hb("staticBPrivHex")
        let staticBPub = hb("staticBPubHex")
        let hPairing = hb("pairingHandshakeHashHex")

        // Public keys derive from the private keys (X25519 with clamping).
        XCTAssertEqual(bytesToHex(try Crypto.x25519PublicKey(fromPrivate: staticAPriv)),
                       d["staticAPubHex"] as! String)
        XCTAssertEqual(bytesToHex(try Crypto.x25519PublicKey(fromPrivate: staticBPriv)),
                       d["staticBPubHex"] as! String)

        let pairingPsk = Derivations.pairingPsk(secret: secret)
        XCTAssertEqual(bytesToHex(pairingPsk), d["pairingPskHex"] as! String)

        let pairRoot = Derivations.pairRoot(
            pairingHandshakeHash: hPairing, pairingPsk: pairingPsk,
            staticA: staticAPub, staticB: staticBPub)
        XCTAssertEqual(bytesToHex(pairRoot), d["pairRootHex"] as! String)

        XCTAssertEqual(bytesToHex(Derivations.sessionPsk(pairRoot: pairRoot)),
                       d["sessionPskHex"] as! String)
        XCTAssertEqual(bytesToHex(Derivations.discoveryKey(pairRoot: pairRoot, role: .a)),
                       d["discKeyAHex"] as! String)
        XCTAssertEqual(bytesToHex(Derivations.discoveryKey(pairRoot: pairRoot, role: .b)),
                       d["discKeyBHex"] as! String)
        XCTAssertEqual(bytesToHex(Derivations.confirmMAC(pairRoot: pairRoot, role: .a)),
                       d["confirmAHex"] as! String)
        XCTAssertEqual(bytesToHex(Derivations.confirmMAC(pairRoot: pairRoot, role: .b)),
                       d["confirmBHex"] as! String)
        XCTAssertEqual(bytesToHex(Derivations.fingerprint(pairId: pairId, staticA: staticAPub, staticB: staticBPub)),
                       d["fingerprintHex"] as! String)
        XCTAssertEqual(Derivations.fingerprintDisplay(pairId: pairId, staticA: staticAPub, staticB: staticBPub),
                       d["fingerprintDisplay"] as! String)
    }
}
