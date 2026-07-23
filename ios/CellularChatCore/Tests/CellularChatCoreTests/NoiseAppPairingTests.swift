import XCTest
@testable import CellularChatCore

/// noise_app_vectors.json: full pairing replay both roles via PairingProtocol.
/// All record bytes and the handshake hash exact (PROTOCOL_V2.md §6/§15).
final class NoiseAppPairingTests: XCTestCase {

    private struct Fixtures {
        let prologue, psk, initEph, respEph, pairId, staticAPub, staticBPub: [UInt8]
    }

    private func loadFixtures() -> ([String: Any], Fixtures) {
        let p = Vectors.loadObject("noise_app_vectors.json")["pairing"] as! [String: Any]
        let d = Vectors.loadObject("derivation_vectors.json")
        return (p, Fixtures(
            prologue: hexToBytes(p["prologueHex"] as! String),
            psk: hexToBytes(p["pskHex"] as! String),
            initEph: hexToBytes(p["initEphemeralPrivHex"] as! String),
            respEph: hexToBytes(p["respEphemeralPrivHex"] as! String),
            pairId: hexToBytes(d["pairIdHex"] as! String),
            staticAPub: hexToBytes(d["staticAPubHex"] as! String),
            staticBPub: hexToBytes(d["staticBPubHex"] as! String)))
    }

    func testFullPairingReplay() throws {
        let (p, f) = loadFixtures()
        let d = Vectors.loadObject("derivation_vectors.json")

        // Role B = joiner/initiator, Role A = inviter/responder.
        let b = try PairingProtocol(role: .b, pairId: f.pairId, prologue: f.prologue,
                                    pairingPsk: f.psk, ephemeralPriv: f.initEph, localStaticPub: f.staticBPub)
        let a = try PairingProtocol(role: .a, pairId: f.pairId, prologue: f.prologue,
                                    pairingPsk: f.psk, ephemeralPriv: f.respEph, localStaticPub: f.staticAPub)

        // Handshake.
        let msg1 = try b.writeHandshake()
        XCTAssertEqual(bytesToHex(msg1), p["msg1RecordHex"] as! String)
        try a.readHandshake(msg1)
        let msg2 = try a.writeHandshake()
        XCTAssertEqual(bytesToHex(msg2), p["msg2RecordHex"] as! String)
        try b.readHandshake(msg2)

        let hh = p["handshakeHashHex"] as! String
        XCTAssertEqual(bytesToHex(b.handshakeHash!), hh)
        XCTAssertEqual(bytesToHex(a.handshakeHash!), hh)

        let records = p["transportRecords"] as! [[String: Any]]
        func recHex(_ i: Int) -> String { records[i]["recordHex"] as! String }

        // pair_bind both ways.
        let bBind = try b.sendPairBind()
        XCTAssertEqual(bytesToHex(bBind), recHex(0))
        try a.receivePairBind(bBind)
        let aBind = try a.sendPairBind()
        XCTAssertEqual(bytesToHex(aBind), recHex(1))
        try b.receivePairBind(aBind)

        // Stage pairRoot and cross-check against derivation vector.
        try b.stagePairRoot()
        try a.stagePairRoot()
        let pairRootHex = d["pairRootHex"] as! String
        XCTAssertEqual(bytesToHex(b.pairRoot!), pairRootHex)
        XCTAssertEqual(bytesToHex(a.pairRoot!), pairRootHex)
        XCTAssertEqual(b.negotiatedVersion, 2)
        XCTAssertEqual(a.negotiatedVersion, 2)

        // pair_proof both ways (constant-time MAC verify inside receive).
        let bProof = try b.sendPairProof()
        XCTAssertEqual(bytesToHex(bProof), recHex(2))
        try a.receivePairProof(bProof)
        let aProof = try a.sendPairProof()
        XCTAssertEqual(bytesToHex(aProof), recHex(3))
        try b.receivePairProof(aProof)

        // Fingerprint shown on both screens must match.
        XCTAssertEqual(try b.fingerprintDisplay(), d["fingerprintDisplay"] as! String)
        XCTAssertEqual(try a.fingerprintDisplay(), d["fingerprintDisplay"] as! String)

        // pair_complete both ways.
        let bDone = try b.sendPairComplete()
        XCTAssertEqual(bytesToHex(bDone), recHex(4))
        try a.receivePairComplete(bDone)
        let aDone = try a.sendPairComplete()
        XCTAssertEqual(bytesToHex(aDone), recHex(5))
        try b.receivePairComplete(aDone)
    }

    /// A peer that advertises maxVersion < 2 aborts (PROTOCOL_V2.md §6, no downgrade).
    func testPairBindBelowMinVersionRejected() throws {
        let (_, f) = loadFixtures()
        // B declares maxVersion 1.
        let b = try PairingProtocol(role: .b, pairId: f.pairId, prologue: f.prologue,
                                    pairingPsk: f.psk, ephemeralPriv: f.initEph,
                                    localStaticPub: f.staticBPub, localMaxVersion: 1)
        let a = try PairingProtocol(role: .a, pairId: f.pairId, prologue: f.prologue,
                                    pairingPsk: f.psk, ephemeralPriv: f.respEph, localStaticPub: f.staticAPub)
        let msg1 = try b.writeHandshake()
        try a.readHandshake(msg1)
        let msg2 = try a.writeHandshake()
        try b.readHandshake(msg2)
        let bind = try b.sendPairBind()
        assertThrows(.unsupportedVersion, try a.receivePairBind(bind))
    }
}
