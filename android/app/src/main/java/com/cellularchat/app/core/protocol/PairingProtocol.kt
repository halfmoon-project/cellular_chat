package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.core.cbor.CborBytes
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.cbor.CborValue
import com.cellularchat.app.core.cbor.cborMapOf
import com.cellularchat.app.core.crypto.Derivations
import com.cellularchat.app.core.crypto.X25519
import com.cellularchat.app.core.noise.CipherState
import com.cellularchat.app.core.noise.HandshakeState
import com.cellularchat.app.core.noise.NoisePattern
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The staged (not-yet-persisted) PairRecord produced by pairing (§2, §6). */
data class StagedPairData(
    val role: PairingProtocol.Role,
    val pairId: ByteArray,
    val staticA: ByteArray,
    val staticB: ByteArray,
    val pairRoot: ByteArray,
    val negotiatedVersion: Long,
    val fingerprint: ByteArray,
    val fingerprintDisplay: String,
)

/**
 * Drives NNpsk0 (PROTOCOL_V2.md §6) plus pair_bind/pair_proof/pair_complete/
 * pair_abort for either role. Role B (joiner) is the Noise initiator; role A
 * (inviter) is the responder. Enforces §6 ordering and the §14 rejection rules.
 */
class PairingProtocol private constructor(
    val role: Role,
    private val pairId: ByteArray,
    private val pairingPsk: ByteArray,
    private val localStaticPrivate: ByteArray,
    private val localMaxVersion: Long,
    fixedEphemeralPrivate: ByteArray?,
) {
    enum class Role(val value: Long) { A(1), B(2) }

    enum class Type { BIND, PROOF, COMPLETE, ABORT }

    data class Received(val type: Type, val envelope: PairingEnvelope)

    private val localStaticPublic = X25519.derivePublic(localStaticPrivate)

    private val handshake = HandshakeState(
        pattern = NoisePattern.NN_PSK0,
        initiator = role == Role.B,
        prologue = "cellfind/v2/pairing".toByteArray(StandardCharsets.US_ASCII) + pairId,
        psk = pairingPsk,
        fixedEphemeralPrivate = fixedEphemeralPrivate,
    )

    private var initToResp: CipherState? = null
    private var respToInit: CipherState? = null

    private val sendCipher: CipherState
        get() = (if (role == Role.B) initToResp else respToInit)
            ?: throw ProtocolException("pairing transport before handshake completed")
    private val recvCipher: CipherState
        get() = (if (role == Role.B) respToInit else initToResp)
            ?: throw ProtocolException("pairing transport before handshake completed")

    private val peerRole: Role = if (role == Role.A) Role.B else Role.A

    private var peerStaticPublic: ByteArray? = null
    private var negotiatedVersion: Long? = null
    private var staged: StagedPairData? = null

    // Strict §6 ordering: each side sends and receives bind → proof → complete.
    private var sendStep = 0
    private var recvStep = 0

    val handshakeHash: ByteArray get() = handshake.handshakeHash()
    val stagedPairData: StagedPairData? get() = staged

    // --- NNpsk0 handshake ---

    /** B: produce record 0x01 (pair_hello / msg1). */
    fun startHandshake(): ByteArray {
        require(role == Role.B) { "only role B starts the pairing handshake" }
        return Records.build(Records.PAIRING_HANDSHAKE, handshake.writeMessage(ByteArray(0)))
    }

    /** A: consume record 0x01 (pair_hello / msg1). */
    fun readHello(record: ByteArray) {
        require(role == Role.A) { "only role A reads pair_hello" }
        expectHandshake(record)
        handshake.readMessage(Records.payload(record))
    }

    /** A: produce record 0x01 (pair_challenge / msg2) and complete. */
    fun writeChallenge(): ByteArray {
        require(role == Role.A) { "only role A writes pair_challenge" }
        val record = Records.build(Records.PAIRING_HANDSHAKE, handshake.writeMessage(ByteArray(0)))
        finishHandshake()
        return record
    }

    /** B: consume record 0x01 (pair_challenge / msg2) and complete. */
    fun readChallenge(record: ByteArray) {
        require(role == Role.B) { "only role B reads pair_challenge" }
        expectHandshake(record)
        handshake.readMessage(Records.payload(record))
        finishHandshake()
    }

    private fun finishHandshake() {
        val (c1, c2) = handshake.splitStates ?: throw ProtocolException("pairing handshake not complete")
        initToResp = c1
        respToInit = c2
    }

    // --- pairing transport (record 0x04) ---

    fun sendBind(): ByteArray {
        requireSendStep(0)
        val body = cborMapOf(
            1L to CborInt(role.value),
            2L to CborBytes(localStaticPublic),
            3L to CborInt(localMaxVersion),
        )
        sendStep = 1
        return sendPairing(PairingMsgType.PAIR_BIND, body)
    }

    fun sendProof(): ByteArray {
        requireSendStep(1)
        val pairRoot = requireStaged().pairRoot
        val mac = Derivations.confirmMac(pairRoot, roleByte(role))
        sendStep = 2
        return sendPairing(PairingMsgType.PAIR_PROOF, cborMapOf(1L to CborBytes(mac)))
    }

    fun sendComplete(): ByteArray {
        requireSendStep(2)
        sendStep = 3
        return sendPairing(PairingMsgType.PAIR_COMPLETE, CborMap(emptyList()))
    }

    fun sendAbort(reason: Int): ByteArray =
        sendPairing(PairingMsgType.PAIR_ABORT, cborMapOf(1L to CborInt(reason.toLong())))

    /** Consume a pairing transport record and apply §6/§14 validation. */
    fun receive(record: ByteArray): Received {
        val envelope = decryptPairing(record)
        when (envelope.msgType) {
            PairingMsgType.PAIR_BIND -> handleBind(envelope)
            PairingMsgType.PAIR_PROOF -> handleProof(envelope)
            PairingMsgType.PAIR_COMPLETE -> handleComplete(envelope)
            PairingMsgType.PAIR_ABORT -> {
                requireKeys(envelope.body, setOf(1L), "pair_abort")
                return Received(Type.ABORT, envelope)
            }
            else -> throw ProtocolException("unknown pairing msgType ${envelope.msgType}")
        }
        return Received(typeFor(envelope.msgType), envelope)
    }

    private fun handleBind(envelope: PairingEnvelope) {
        requireRecvStep(0)
        requireKeys(envelope.body, setOf(1L, 2L, 3L), "pair_bind")
        val declaredRole = uint(envelope.body[1L], "role")
        if (declaredRole != peerRole.value) throw ProtocolException("unexpected peer role", ReasonCodes.PROTOCOL_ERROR)
        val staticPub = bytes(envelope.body[2L], "staticPub")
        if (staticPub.size != 32) throw ProtocolException("static key must be 32 bytes")
        val peerMax = uint(envelope.body[3L], "maxVersion")
        if (peerMax < 2) throw ProtocolException("peer maxVersion below 2", ReasonCodes.PROTOCOL_ERROR)
        peerStaticPublic = staticPub
        negotiatedVersion = minOf(localMaxVersion, peerMax)
        deriveStaged()
        recvStep = 1
    }

    private fun handleProof(envelope: PairingEnvelope) {
        requireRecvStep(1)
        requireKeys(envelope.body, setOf(1L), "pair_proof")
        val mac = bytes(envelope.body[1L], "mac")
        if (mac.size != 32) throw ProtocolException("mac must be 32 bytes")
        val expected = Derivations.confirmMac(requireStaged().pairRoot, roleByte(peerRole))
        if (!MessageDigest.isEqual(mac, expected)) {
            throw ProtocolException("pairing key confirmation failed", ReasonCodes.AUTH_FAILED)
        }
        recvStep = 2
    }

    private fun handleComplete(envelope: PairingEnvelope) {
        requireRecvStep(2)
        requireKeys(envelope.body, emptySet(), "pair_complete")
        recvStep = 3
    }

    private fun deriveStaged() {
        val peer = peerStaticPublic ?: return
        val staticA = if (role == Role.A) localStaticPublic else peer
        val staticB = if (role == Role.B) localStaticPublic else peer
        val pairRoot = Derivations.pairRoot(handshake.handshakeHash(), pairingPsk, staticA, staticB)
        val fingerprint = Derivations.fingerprint(pairId, staticA, staticB)
        staged = StagedPairData(
            role = role,
            pairId = pairId,
            staticA = staticA,
            staticB = staticB,
            pairRoot = pairRoot,
            negotiatedVersion = negotiatedVersion ?: localMaxVersion,
            fingerprint = fingerprint,
            fingerprintDisplay = Derivations.fingerprintDisplay(fingerprint),
        )
    }

    private fun sendPairing(msgType: Long, body: CborMap): ByteArray {
        val cipher = sendCipher
        val plaintext = PairingEnvelopeCodec.encode(PairingEnvelope(msgType, cipher.nonce, body))
        if (plaintext.size > Records.MAX_PLAINTEXT_ENVELOPE) throw ProtocolException("pairing envelope too large")
        val ciphertext = cipher.encryptWithAd(EMPTY_AD, plaintext)
        return Records.build(Records.PAIRING_TRANSPORT, ciphertext)
    }

    private fun decryptPairing(record: ByteArray): PairingEnvelope {
        if (Records.recordType(record) != Records.PAIRING_TRANSPORT) {
            throw ProtocolException("expected a pairing transport record")
        }
        val cipher = recvCipher
        val expectedSeq = cipher.nonce
        val plaintext = cipher.decryptWithAd(EMPTY_AD, Records.payload(record))
        val envelope = PairingEnvelopeCodec.decode(plaintext)
        if (envelope.seq != expectedSeq) throw ProtocolException("pairing seq does not match the counter")
        return envelope
    }

    private fun expectHandshake(record: ByteArray) {
        if (Records.recordType(record) != Records.PAIRING_HANDSHAKE) {
            throw ProtocolException("expected a pairing handshake record")
        }
    }

    private fun requireSendStep(step: Int) {
        if (sendStep != step) throw ProtocolException("pairing send out of order")
    }

    private fun requireRecvStep(step: Int) {
        if (recvStep != step) throw ProtocolException("pairing receive out of order")
    }

    private fun requireStaged(): StagedPairData =
        staged ?: throw ProtocolException("pair root not derived yet")

    private fun typeFor(msgType: Long): Type = when (msgType) {
        PairingMsgType.PAIR_BIND -> Type.BIND
        PairingMsgType.PAIR_PROOF -> Type.PROOF
        PairingMsgType.PAIR_COMPLETE -> Type.COMPLETE
        else -> Type.ABORT
    }

    private fun roleByte(r: Role): Byte = if (r == Role.A) Derivations.ROLE_A else Derivations.ROLE_B

    companion object {
        private val EMPTY_AD = ByteArray(0)

        fun roleB(
            pairId: ByteArray,
            pairingPsk: ByteArray,
            localStaticPrivate: ByteArray,
            localMaxVersion: Long = 2,
            fixedEphemeralPrivate: ByteArray? = null,
        ) = PairingProtocol(Role.B, pairId, pairingPsk, localStaticPrivate, localMaxVersion, fixedEphemeralPrivate)

        fun roleA(
            pairId: ByteArray,
            pairingPsk: ByteArray,
            localStaticPrivate: ByteArray,
            localMaxVersion: Long = 2,
            fixedEphemeralPrivate: ByteArray? = null,
        ) = PairingProtocol(Role.A, pairId, pairingPsk, localStaticPrivate, localMaxVersion, fixedEphemeralPrivate)
    }
}

private fun requireKeys(map: CborMap, allowed: Set<Long>, context: String) {
    val keys = map.entries.map {
        (it.first as? CborInt)?.value ?: throw ProtocolException("$context keys must be integers")
    }
    if (keys.toSet() != allowed || keys.size != allowed.size) {
        throw ProtocolException("$context has an unexpected key set")
    }
}

private fun uint(value: CborValue?, field: String): Long {
    val v = (value as? CborInt)?.value ?: throw ProtocolException("$field must be an integer")
    if (v < 0) throw ProtocolException("$field must be unsigned")
    return v
}

private fun bytes(value: CborValue?, field: String): ByteArray =
    (value as? CborBytes)?.value ?: throw ProtocolException("$field must be a byte string")
