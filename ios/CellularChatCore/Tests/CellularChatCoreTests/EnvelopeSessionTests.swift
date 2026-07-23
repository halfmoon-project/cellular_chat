import XCTest
@testable import CellularChatCore

/// envelope_vectors.json: full session replay via SecureSession; split keys
/// exact; all transport records exact; the four failure cases (§8/§14).
final class EnvelopeSessionTests: XCTestCase {

    private struct Session {
        let prologue, psk, sid, initStaticPriv, respStaticPriv, initEph, respEph: [UInt8]
        let staticAPub, staticBPub: [UInt8]
        let json: [String: Any]
    }

    private func loadSession() throws -> Session {
        let root = Vectors.loadObject("envelope_vectors.json")
        let s = root["session"] as! [String: Any]
        let initStaticPriv = hexToBytes(s["initStaticPrivHex"] as! String)   // B
        let respStaticPriv = hexToBytes(s["respStaticPrivHex"] as! String)   // A
        return Session(
            prologue: hexToBytes(s["prologueHex"] as! String),
            psk: hexToBytes(s["pskHex"] as! String),
            sid: hexToBytes(s["sidHex"] as! String),
            initStaticPriv: initStaticPriv,
            respStaticPriv: respStaticPriv,
            initEph: hexToBytes(s["initEphemeralPrivHex"] as! String),
            respEph: hexToBytes(s["respEphemeralPrivHex"] as! String),
            staticAPub: try Crypto.x25519PublicKey(fromPrivate: respStaticPriv),
            staticBPub: try Crypto.x25519PublicKey(fromPrivate: initStaticPriv),
            json: s)
    }

    /// Runs the handshake and returns the two handshaked sessions plus fixtures.
    private func handshaked() throws -> (init_: SecureSession, resp: SecureSession, s: Session) {
        let s = try loadSession()
        let initSession = try SecureSession(
            role: .initiator, prologue: s.prologue, sessionPsk: s.psk,
            localStaticPriv: s.initStaticPriv, pinnedPeerStaticPub: s.staticAPub,
            ephemeralPriv: s.initEph, sid: s.sid)
        let respSession = try SecureSession(
            role: .responder, prologue: s.prologue, sessionPsk: s.psk,
            localStaticPriv: s.respStaticPriv, pinnedPeerStaticPub: s.staticBPub,
            ephemeralPriv: s.respEph, sid: nil)

        let msg1 = try initSession.writeHandshake()
        XCTAssertEqual(bytesToHex(msg1), s.json["msg1RecordHex"] as! String)
        try respSession.readHandshake(msg1)
        let msg2 = try respSession.writeHandshake()
        XCTAssertEqual(bytesToHex(msg2), s.json["msg2RecordHex"] as! String)
        try initSession.readHandshake(msg2)
        return (initSession, respSession, s)
    }

    func testFullSessionReplay() throws {
        let (initSession, respSession, s) = try handshaked()

        XCTAssertEqual(bytesToHex(initSession.handshakeHash!), s.json["handshakeHashHex"] as! String)
        XCTAssertEqual(bytesToHex(respSession.handshakeHash!), s.json["handshakeHashHex"] as! String)

        // Split keys exact (per-direction).
        XCTAssertEqual(bytesToHex(initSession.sendKey!), s.json["kInitToRespHex"] as! String)
        XCTAssertEqual(bytesToHex(respSession.sendKey!), s.json["kRespToInitHex"] as! String)
        XCTAssertEqual(bytesToHex(initSession.recvKey!), s.json["kRespToInitHex"] as! String)
        XCTAssertEqual(bytesToHex(respSession.recvKey!), s.json["kInitToRespHex"] as! String)

        let records = s.json["transportRecords"] as! [[String: Any]]
        for rec in records {
            let sender = rec["sender"] as! String
            let plainHex = rec["plaintextHex"] as! String
            let recordHex = rec["recordHex"] as! String
            let env = try SessionEnvelope.decode(hexToBytes(plainHex))

            let (tx, rx) = sender == "init" ? (initSession, respSession) : (respSession, initSession)
            let produced = try tx.send(msgType: env.msgType, body: env.body)
            XCTAssertEqual(bytesToHex(produced), recordHex, "send \(sender) type \(env.msgType)")

            let inbound = try rx.receive(hexToBytes(recordHex))
            XCTAssertEqual(inbound, .message(msgType: env.msgType, body: env.body),
                           "receive \(sender) type \(env.msgType)")
        }
    }

    // MARK: - Failure cases

    func testWrongPskPairingFailsToReadMsg1() throws {
        let f = (Vectors.loadObject("envelope_vectors.json")["failures"] as! [[String: Any]])
            .first { $0["case"] as? String == "wrongPskPairing" }!
        let wrongPsk = hexToBytes(f["pskHex"] as! String)
        let prologue = hexToBytes(f["pairingPrologueHex"] as! String)
        let respEph = hexToBytes(f["respEphemeralPrivHex"] as! String)
        let msg1 = hexToBytes(f["msg1RecordHex"] as! String)

        // Responder (role A) with the wrong PSK must fail AEAD reading pairing msg1.
        let a = try PairingProtocol(
            role: .a, pairId: [UInt8](repeating: 0, count: 16), prologue: prologue,
            pairingPsk: wrongPsk, ephemeralPriv: respEph,
            localStaticPub: [UInt8](repeating: 0, count: 32))
        assertThrows(.aeadFailure, try a.readHandshake(msg1))
    }

    func testSubstitutedStaticRejectedByPinning() throws {
        let s = try loadSession()
        let f = (Vectors.loadObject("envelope_vectors.json")["failures"] as! [[String: Any]])
            .first { $0["case"] as? String == "substitutedStatic" }!
        let attackerMsg1 = hexToBytes(f["msg1RecordHex"] as! String)

        // Responder pins peer = staticB; the attacker presents a different static.
        let resp = try SecureSession(
            role: .responder, prologue: s.prologue, sessionPsk: s.psk,
            localStaticPriv: s.respStaticPriv, pinnedPeerStaticPub: s.staticBPub,
            ephemeralPriv: s.respEph, sid: nil)
        assertThrows(.pinnedKeyMismatch, try resp.readHandshake(attackerMsg1))
    }

    func testBitFlipFailsAEAD() throws {
        let (_, respSession, _) = try handshaked()
        let f = (Vectors.loadObject("envelope_vectors.json")["failures"] as! [[String: Any]])
            .first { $0["case"] as? String == "bitFlip" }!
        let corrupted = hexToBytes(f["recordHex"] as! String)
        assertThrows(.aeadFailure, try respSession.receive(corrupted))
    }

    func testReplayFailsOnSecondDelivery() throws {
        let (_, respSession, _) = try handshaked()
        let f = (Vectors.loadObject("envelope_vectors.json")["failures"] as! [[String: Any]])
            .first { $0["case"] as? String == "replay" }!
        let record = hexToBytes(f["recordHex"] as! String)
        // First delivery succeeds.
        _ = try respSession.receive(record)
        // Second delivery: counter has advanced, so AEAD fails.
        assertThrows(.aeadFailure, try respSession.receive(record))
    }
}
