import XCTest
@testable import CellularChatCore

/// Extra §5/§8/§14 rules: counter overflow, oversize records, stream framing,
/// and handshake/transport phase-ordering rejections.
final class SecurityRulesTests: XCTestCase {

    func testCounterOverflowGuard() {
        // Must re-handshake before n = 2^32 (PROTOCOL_V2.md §1).
        let cs = CipherState([UInt8](repeating: 0x42, count: 32))
        cs.n = 0xFFFF_FFFF   // 2^32 - 1: last legal counter
        XCTAssertNoThrow(try cs.encryptWithAd([], [0x00]))
        // n is now 2^32 -> the next operation must be refused.
        assertThrows(.counterOverflow, try cs.encryptWithAd([], [0x00]))
    }

    func testOversizeAndZeroLengthRecords() {
        assertThrows(.zeroLengthRecord, try Records.parse([]))
        let oversize = [UInt8](repeating: 0x03, count: RecordLimits.maxRecord + 1)
        assertThrows(.oversizeRecord, try Records.parse(oversize))
        assertThrows(.unknownRecordType, try Records.parse([0x7F, 0x00]))
    }

    func testStreamFramingRejectsBadLength() {
        // Declared length 0 or > 65536 is fatal (§5).
        assertThrows(.streamLengthInvalid, try Records.readStream([0x00, 0x00, 0x00, 0x00]))
        assertThrows(.streamLengthInvalid, try Records.readStream([0x00, 0x01, 0x00, 0x01]))

        // Round-trip a valid frame.
        let record: [UInt8] = [0x03, 0xAA, 0xBB]
        let framed = Records.frameForStream(record)
        let (records, consumed) = try! Records.readStream(framed)
        XCTAssertEqual(records, [record])
        XCTAssertEqual(consumed, framed.count)

        // A partial frame is left unconsumed for the next read.
        let partial = Array(framed.dropLast())
        let (recs2, consumed2) = try! Records.readStream(partial)
        XCTAssertTrue(recs2.isEmpty)
        XCTAssertEqual(consumed2, 0)
    }

    func testTransportBeforeHandshakeRejected() throws {
        let s = try makeInitiator()
        // A transport record before the handshake completes is a phase violation (§14).
        let fakeTransport = Records.make(.sessionTransport, payload: [UInt8](repeating: 0, count: 32))
        assertThrows(.wrongMessagePhase, try s.receive(fakeTransport))
    }

    func testHandshakeAfterCompleteRejected() throws {
        let (initSession, _, _) = try completedHandshake()
        // A handshake record after completion is a phase violation (§14).
        let fakeHandshake = Records.make(.sessionHandshake, payload: [UInt8](repeating: 0, count: 48))
        assertThrows(.wrongMessagePhase, try initSession.readHandshake(fakeHandshake))
        // As is any handshake-type record routed through receive().
        assertThrows(.wrongMessagePhase, try initSession.receive(fakeHandshake))
    }

    // MARK: helpers

    private func sessionFixtures() -> (prologue: [UInt8], psk: [UInt8], sid: [UInt8],
                                       initStatic: [UInt8], respStatic: [UInt8],
                                       initEph: [UInt8], respEph: [UInt8]) {
        let s = Vectors.loadObject("envelope_vectors.json")["session"] as! [String: Any]
        return (hexToBytes(s["prologueHex"] as! String),
                hexToBytes(s["pskHex"] as! String),
                hexToBytes(s["sidHex"] as! String),
                hexToBytes(s["initStaticPrivHex"] as! String),
                hexToBytes(s["respStaticPrivHex"] as! String),
                hexToBytes(s["initEphemeralPrivHex"] as! String),
                hexToBytes(s["respEphemeralPrivHex"] as! String))
    }

    private func makeInitiator() throws -> SecureSession {
        let f = sessionFixtures()
        let peer = try Crypto.x25519PublicKey(fromPrivate: f.respStatic)
        return try SecureSession(role: .initiator, prologue: f.prologue, sessionPsk: f.psk,
                                 localStaticPriv: f.initStatic, pinnedPeerStaticPub: peer,
                                 ephemeralPriv: f.initEph, sid: f.sid)
    }

    private func completedHandshake() throws -> (SecureSession, SecureSession, Void) {
        let f = sessionFixtures()
        let staticAPub = try Crypto.x25519PublicKey(fromPrivate: f.respStatic)
        let staticBPub = try Crypto.x25519PublicKey(fromPrivate: f.initStatic)
        let initSession = try SecureSession(role: .initiator, prologue: f.prologue, sessionPsk: f.psk,
                                            localStaticPriv: f.initStatic, pinnedPeerStaticPub: staticAPub,
                                            ephemeralPriv: f.initEph, sid: f.sid)
        let respSession = try SecureSession(role: .responder, prologue: f.prologue, sessionPsk: f.psk,
                                            localStaticPriv: f.respStatic, pinnedPeerStaticPub: staticBPub,
                                            ephemeralPriv: f.respEph, sid: nil)
        let msg1 = try initSession.writeHandshake()
        try respSession.readHandshake(msg1)
        let msg2 = try respSession.writeHandshake()
        try initSession.readHandshake(msg2)
        return (initSession, respSession, ())
    }
}
