import Foundation

/// Pairing driver (PROTOCOL_V2.md §6): NNpsk0 handshake, then the
/// pair_bind / pair_proof / pair_complete / pair_abort transport exchange with
/// staged pair data, fingerprint, §6 ordering, and §14 rejection rules.
public final class PairingProtocol {

    private enum Phase { case handshake, binding, proving, done, aborted }

    public let role: PairRole
    private let pairId: [UInt8]
    private let localStaticPub: [UInt8]
    private let pairingPsk: [UInt8]
    private let localMaxVersion: UInt64

    private var hs: HandshakeState?
    private var pairingHandshakeHash: [UInt8]?
    private var sendCipher: CipherState?
    private var recvCipher: CipherState?
    private var phase: Phase = .handshake

    private var peerStaticPub: [UInt8]?
    private var peerMaxVersion: UInt64?
    private var pairRootValue: [UInt8]?
    private var proofSent = false
    private var proofVerified = false
    private var completeSent = false
    private var completeReceived = false

    public var handshakeHash: [UInt8]? { pairingHandshakeHash }
    public var pairRoot: [UInt8]? { pairRootValue }
    /// The peer's bound static public key, pinned into the PairRecord after
    /// pair_bind (§2/§6). Nil until the peer's pair_bind has been accepted.
    public var peerStaticKey: [UInt8]? { peerStaticPub }
    /// min(local, peer) protocol version recorded in the PairRecord (§6).
    public var negotiatedVersion: UInt64? {
        guard let peer = peerMaxVersion else { return nil }
        return min(localMaxVersion, peer)
    }

    public init(role: PairRole,
                pairId: [UInt8],
                prologue: [UInt8],
                pairingPsk: [UInt8],
                ephemeralPriv: [UInt8],
                localStaticPub: [UInt8],
                localMaxVersion: UInt64 = 2) throws {
        self.role = role
        self.pairId = pairId
        self.localStaticPub = localStaticPub
        self.pairingPsk = pairingPsk
        self.localMaxVersion = localMaxVersion
        // NNpsk0: joiner (role B) is the initiator; inviter (role A) is responder.
        hs = try HandshakeState(
            pattern: .nnpsk0, initiator: role == .b, prologue: prologue,
            e: ephemeralPriv, psk: pairingPsk)
    }

    // MARK: Handshake

    /// Role B: produce message 1. Role A: produce message 2 (after reading msg 1).
    public func writeHandshake() throws -> [UInt8] {
        guard let hs, phase == .handshake else { throw ProtocolError.wrongMessagePhase }
        let msg = try hs.writeMessage([])
        guard msg.count <= RecordLimits.maxNoiseMessage else { throw ProtocolError.oversizeRecord }
        let record = Records.make(.pairingHandshake, payload: msg)
        if role == .a { finishHandshake() }   // responder completes after msg 2
        return record
    }

    public func readHandshake(_ record: [UInt8]) throws {
        guard let hs, phase == .handshake else { throw ProtocolError.wrongMessagePhase }
        let (type, payload) = try Records.parse(record)
        guard type == .pairingHandshake else { throw ProtocolError.wrongMessagePhase }
        _ = try hs.readMessage(payload)   // throws aeadFailure on wrong PSK
        if role == .b { finishHandshake() }   // initiator completes after reading msg 2
    }

    private func finishHandshake() {
        guard let hs else { return }
        pairingHandshakeHash = hs.handshakeHash
        let (c1, c2) = hs.split()
        switch role {
        case .b:   // initiator
            sendCipher = c1
            recvCipher = c2
        case .a:   // responder
            recvCipher = c1
            sendCipher = c2
        }
        phase = .binding
    }

    // MARK: pair_bind

    public func sendPairBind() throws -> [UInt8] {
        guard phase == .binding, let sendCipher else { throw ProtocolError.wrongMessagePhase }
        let roleCode: UInt64 = role == .a ? 1 : 2
        let body = CBOR.map([
            CBORPair(.uint(1), .uint(roleCode)),
            CBORPair(.uint(2), .bytes(localStaticPub)),
            CBORPair(.uint(3), .uint(localMaxVersion)),
        ])
        return try emit(msgType: PairMsgType.pairBind.rawValue, body: body, cipher: sendCipher)
    }

    public func receivePairBind(_ record: [UInt8]) throws {
        guard phase == .binding, let recvCipher else { throw ProtocolError.wrongMessagePhase }
        let env = try accept(record, cipher: recvCipher)
        guard env.msgType == PairMsgType.pairBind.rawValue else { throw ProtocolError.schemaViolation }
        let body = env.body
        // Strict fixed schema: exactly {1,2,3}; extra unknown keys rejected (§14).
        guard case let .map(pairs) = body, pairs.count == 3,
              let peerRoleRaw = body.value(forKey: 1)?.asUInt,
              let peerStatic = body.value(forKey: 2)?.asBytes, peerStatic.count == 32,
              let peerMax = body.value(forKey: 3)?.asUInt else {
            throw ProtocolError.schemaViolation
        }
        // Peer's declared role must be the expected opposite (§6).
        let expectedPeerRole: UInt64 = role == .a ? 2 : 1
        guard peerRoleRaw == expectedPeerRole else { throw ProtocolError.schemaViolation }
        // maxVersion < 2 aborts (§6). No downgrade.
        guard peerMax >= 2 else { throw ProtocolError.unsupportedVersion }
        peerStaticPub = peerStatic
        peerMaxVersion = peerMax
    }

    /// Derive and stage pairRoot once both statics are bound (§2/§6). Both sides
    /// must have exchanged pair_bind first.
    public func stagePairRoot() throws {
        guard phase == .binding, let peerStaticPub, let h = pairingHandshakeHash else {
            throw ProtocolError.wrongMessagePhase
        }
        let (staticA, staticB) = orderedStatics(peer: peerStaticPub)
        pairRootValue = Derivations.pairRoot(
            pairingHandshakeHash: h, pairingPsk: pairingPsk, staticA: staticA, staticB: staticB)
        phase = .proving
    }

    // MARK: pair_proof

    public func sendPairProof() throws -> [UInt8] {
        guard phase == .proving, !proofSent, let sendCipher, let root = pairRootValue else {
            throw ProtocolError.wrongMessagePhase
        }
        let mac = Derivations.confirmMAC(pairRoot: root, role: role)
        let body = CBOR.map([CBORPair(.uint(1), .bytes(mac))])
        let record = try emit(msgType: PairMsgType.pairProof.rawValue, body: body, cipher: sendCipher)
        proofSent = true
        return record
    }

    /// Verifies the peer's confirm MAC in constant time (§6). A mismatch aborts
    /// without revealing which field mismatched.
    public func receivePairProof(_ record: [UInt8]) throws {
        guard phase == .proving, !proofVerified, let recvCipher, let root = pairRootValue else {
            throw ProtocolError.wrongMessagePhase
        }
        let env = try accept(record, cipher: recvCipher)
        guard env.msgType == PairMsgType.pairProof.rawValue else { throw ProtocolError.schemaViolation }
        guard case let .map(pairs) = env.body, pairs.count == 1,
              let mac = env.body.value(forKey: 1)?.asBytes, mac.count == 32 else {
            throw ProtocolError.schemaViolation
        }
        let peerRole: PairRole = role == .a ? .b : .a
        let expected = Derivations.confirmMAC(pairRoot: root, role: peerRole)
        guard Crypto.constantTimeEqual(mac, expected) else { throw ProtocolError.macMismatch }
        proofVerified = true
    }

    // MARK: fingerprint + completion

    public func fingerprintDisplay() throws -> String {
        guard let peerStaticPub else { throw ProtocolError.wrongMessagePhase }
        let (staticA, staticB) = orderedStatics(peer: peerStaticPub)
        return Derivations.fingerprintDisplay(pairId: pairId, staticA: staticA, staticB: staticB)
    }

    /// True once this side has both sent and received pair_complete (§6 step 4):
    /// the point at which the committed PairRecord is persisted.
    public var isCommitted: Bool { phase == .done }

    public func sendPairComplete() throws -> [UInt8] {
        // Only after both confirm MACs have been exchanged (§6 step 3 -> 4).
        guard phase == .proving, proofSent, proofVerified, !completeSent, let sendCipher else {
            throw ProtocolError.wrongMessagePhase
        }
        let record = try emit(msgType: PairMsgType.pairComplete.rawValue, body: .map([]), cipher: sendCipher)
        completeSent = true
        maybeCommit()
        return record
    }

    public func receivePairComplete(_ record: [UInt8]) throws {
        guard phase == .proving, proofSent, proofVerified, !completeReceived, let recvCipher else {
            throw ProtocolError.wrongMessagePhase
        }
        let env = try accept(record, cipher: recvCipher)
        guard env.msgType == PairMsgType.pairComplete.rawValue else { throw ProtocolError.schemaViolation }
        guard case let .map(pairs) = env.body, pairs.isEmpty else { throw ProtocolError.schemaViolation }
        completeReceived = true
        maybeCommit()
    }

    private func maybeCommit() {
        if completeSent && completeReceived { phase = .done }
    }

    public func sendPairAbort(reason: ReasonCode) throws -> [UInt8] {
        guard let sendCipher else { throw ProtocolError.wrongMessagePhase }
        phase = .aborted
        let body = CBOR.map([CBORPair(.uint(1), .uint(reason.rawValue))])
        return try emit(msgType: PairMsgType.pairAbort.rawValue, body: body, cipher: sendCipher)
    }

    // MARK: helpers

    private func orderedStatics(peer: [UInt8]) -> (a: [UInt8], b: [UInt8]) {
        role == .a ? (localStaticPub, peer) : (peer, localStaticPub)
    }

    private func emit(msgType: UInt64, body: CBOR, cipher: CipherState) throws -> [UInt8] {
        let env = PairingEnvelope(msgType: msgType, seq: cipher.n, body: body)
        let plain = env.encoded()
        guard plain.count <= RecordLimits.maxPlaintextEnvelope else { throw ProtocolError.oversizeRecord }
        let ct = try cipher.encryptWithAd([], plain)
        return Records.make(.pairingTransport, payload: ct)
    }

    private func accept(_ record: [UInt8], cipher: CipherState) throws -> PairingEnvelope {
        let (type, payload) = try Records.parse(record)
        guard type == .pairingTransport else { throw ProtocolError.wrongMessagePhase }
        let expected = cipher.n
        let plain = try cipher.decryptWithAd([], payload)
        let env = try PairingEnvelope.decode(plain)
        guard env.seq == expected else { throw ProtocolError.counterMismatch }
        return env
    }
}
