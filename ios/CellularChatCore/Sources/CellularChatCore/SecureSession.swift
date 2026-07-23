import Foundation

/// Session driver (PROTOCOL_V2.md §8): IKpsk2 with pinned-peer verification
/// BEFORE any response, then per-direction AEAD envelopes with seq==counter and
/// sid checks, version checks, and unknown-msgType handling.
public final class SecureSession {

    public enum Role { case initiator, responder }

    public enum Inbound: Equatable {
        case message(msgType: UInt64, body: CBOR)
        case ignored   // unknown msgType >= 128 (forward-compatible extension)
    }

    private let role: Role
    private let pinnedPeer: [UInt8]

    private var hs: HandshakeState?
    private var sendCipher: CipherState?
    private var recvCipher: CipherState?
    private var handshakeComplete = false

    private var sidValue: [UInt8]?
    private var didFirstSend = false
    private var didFirstReceive = false

    public var sid: [UInt8]? { sidValue }
    public private(set) var handshakeHash: [UInt8]?
    var sendKey: [UInt8]? { sendCipher?.k }   // internal: for vector assertions
    var recvKey: [UInt8]? { recvCipher?.k }

    /// - Parameters:
    ///   - localStaticPriv: this device's pair static private key.
    ///   - pinnedPeerStaticPub: the pinned peer static public key (§2).
    ///   - sid: required for the initiator; the responder adopts the initiator's.
    public init(role: Role,
                prologue: [UInt8],
                sessionPsk: [UInt8],
                localStaticPriv: [UInt8],
                pinnedPeerStaticPub: [UInt8],
                ephemeralPriv: [UInt8],
                sid: [UInt8]? = nil) throws {
        self.role = role
        self.pinnedPeer = pinnedPeerStaticPub
        self.sidValue = sid
        switch role {
        case .initiator:
            hs = try HandshakeState(
                pattern: .ikpsk2, initiator: true, prologue: prologue,
                s: localStaticPriv, e: ephemeralPriv, rs: pinnedPeerStaticPub, psk: sessionPsk)
        case .responder:
            hs = try HandshakeState(
                pattern: .ikpsk2, initiator: false, prologue: prologue,
                s: localStaticPriv, e: ephemeralPriv, rs: nil, psk: sessionPsk)
        }
    }

    // MARK: Handshake

    /// Initiator: produce message 1 record. Responder: produce message 2 record
    /// (only after `readHandshake` accepted message 1 and pinning passed).
    public func writeHandshake() throws -> [UInt8] {
        guard let hs, !handshakeComplete else { throw ProtocolError.wrongMessagePhase }
        let msg = try hs.writeMessage([])
        guard msg.count <= RecordLimits.maxNoiseMessage else { throw ProtocolError.oversizeRecord }
        let record = Records.make(.sessionHandshake, payload: msg)
        if role == .responder {
            finishHandshake()   // responder completes after writing msg2
        }
        return record
    }

    /// Responder: read message 1, then verify the transmitted static equals the
    /// pinned peer key BEFORE any response. Initiator: read message 2.
    public func readHandshake(_ record: [UInt8]) throws {
        guard let hs, !handshakeComplete else { throw ProtocolError.wrongMessagePhase }
        let (type, payload) = try Records.parse(record)
        guard type == .sessionHandshake else { throw ProtocolError.wrongMessagePhase }
        _ = try hs.readMessage(payload)
        if role == .responder {
            guard let learned = hs.rs,
                  Crypto.constantTimeEqual(learned, pinnedPeer) else {
                throw ProtocolError.pinnedKeyMismatch
            }
        } else {
            finishHandshake()   // initiator completes after reading msg2
        }
    }

    private func finishHandshake() {
        guard let hs else { return }
        handshakeHash = hs.handshakeHash
        let (c1, c2) = hs.split()
        switch role {
        case .initiator:
            sendCipher = c1   // initiator -> responder
            recvCipher = c2
        case .responder:
            recvCipher = c1
            sendCipher = c2   // responder -> initiator
        }
        handshakeComplete = true
    }

    // MARK: Transport

    public func send(msgType: UInt64, body: CBOR) throws -> [UInt8] {
        guard handshakeComplete, let sendCipher, let sid = sidValue else {
            throw ProtocolError.wrongMessagePhase
        }
        if !didFirstSend && msgType != SessionMsgType.sessionReady.rawValue {
            throw ProtocolError.schemaViolation   // first transport message MUST be session_ready
        }
        let env = SessionEnvelope(msgType: msgType, seq: sendCipher.n, sid: sid, body: body)
        let plain = env.encoded()
        guard plain.count <= RecordLimits.maxPlaintextEnvelope else { throw ProtocolError.oversizeRecord }
        let ct = try sendCipher.encryptWithAd([], plain)
        didFirstSend = true
        return Records.make(.sessionTransport, payload: ct)
    }

    public func receive(_ record: [UInt8]) throws -> Inbound {
        let (type, payload) = try Records.parse(record)
        guard type == .sessionTransport else {
            // Handshake record after completion, or any wrong-phase record (§14).
            throw ProtocolError.wrongMessagePhase
        }
        guard handshakeComplete, let recvCipher else { throw ProtocolError.wrongMessagePhase }

        let expectedCounter = recvCipher.n
        let plain = try recvCipher.decryptWithAd([], payload)   // throws aeadFailure / counterOverflow
        let env = try SessionEnvelope.decode(plain)

        guard env.seq == expectedCounter else { throw ProtocolError.counterMismatch }
        if let sid = sidValue {
            guard Crypto.constantTimeEqual(env.sid, sid) else { throw ProtocolError.sidMismatch }
        } else {
            sidValue = env.sid   // responder adopts the initiator's sid
        }

        if !didFirstReceive && env.msgType != SessionMsgType.sessionReady.rawValue {
            throw ProtocolError.schemaViolation
        }
        didFirstReceive = true

        // Version negotiation: any field claiming < 2 is rejected (§8).
        if env.msgType == SessionMsgType.sessionReady.rawValue {
            guard let maxVersion = env.body.value(forKey: 3)?.asUInt, maxVersion >= 2 else {
                throw ProtocolError.unsupportedVersion
            }
        }

        // Unknown-message rule (§8): < 128 fatal, >= 128 ignored.
        if SessionMsgType(rawValue: env.msgType) == nil {
            if env.msgType < 128 { throw ProtocolError.schemaViolation }
            return .ignored
        }
        return .message(msgType: env.msgType, body: env.body)
    }
}
