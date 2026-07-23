package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.crypto.Derivations
import com.cellularchat.app.core.noise.CipherState
import com.cellularchat.app.core.noise.HandshakeState
import com.cellularchat.app.core.noise.NoisePattern
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Drives IKpsk2 (PROTOCOL_V2.md §8) for either role and then encrypts/decrypts
 * session transport envelopes with per-direction counters. Enforces the §14
 * rejection rules: pinned-static check before any response, seq==counter, sid
 * match, oversize, unknown-msgType policy, and the counter overflow guard.
 */
class SecureSession private constructor(
    private val role: Role,
    pairId: ByteArray,
    transportTag: String,
    private val localStaticPrivate: ByteArray,
    private val pinnedPeerStatic: ByteArray,
    pairRoot: ByteArray,
    private var sessionId: ByteArray?,
    fixedEphemeralPrivate: ByteArray?,
) {
    enum class Role { INITIATOR, RESPONDER }

    /** A received message; [ignored] is set for forward-compatible msgType ≥ 128. */
    data class Incoming(val envelope: SessionEnvelope, val ignored: Boolean)

    private val prologue = "cellfind/v2/session".toByteArray(StandardCharsets.US_ASCII) +
        pairId + transportTag.toByteArray(StandardCharsets.US_ASCII)

    private val handshake = HandshakeState(
        pattern = NoisePattern.IK_PSK2,
        initiator = role == Role.INITIATOR,
        prologue = prologue,
        psk = Derivations.sessionPsk(pairRoot),
        localStaticPrivate = localStaticPrivate,
        remoteStaticPublic = if (role == Role.INITIATOR) pinnedPeerStatic else null,
        fixedEphemeralPrivate = fixedEphemeralPrivate,
    )

    private var initToResp: CipherState? = null
    private var respToInit: CipherState? = null

    private val sendCipher: CipherState
        get() = (if (role == Role.INITIATOR) initToResp else respToInit)
            ?: throw ProtocolException("transport message before handshake completed")
    private val recvCipher: CipherState
        get() = (if (role == Role.INITIATOR) respToInit else initToResp)
            ?: throw ProtocolException("transport message before handshake completed")

    val isEstablished: Boolean get() = initToResp != null

    fun handshakeHash(): ByteArray = handshake.handshakeHash()

    // --- IKpsk2 handshake ---

    /** Initiator: produce record 0x02 (session_hello / msg1). */
    fun startHandshake(): ByteArray {
        require(role == Role.INITIATOR) { "only the initiator starts the handshake" }
        return Records.build(Records.SESSION_HANDSHAKE, handshake.writeMessage(ByteArray(0)))
    }

    /**
     * Responder: consume record 0x02 (msg1). Performs the pinning check on the
     * initiator's transmitted static BEFORE any response can be produced.
     */
    fun readHandshakeInit(record: ByteArray) {
        require(role == Role.RESPONDER) { "only the responder reads msg1" }
        expectRecordType(record, Records.SESSION_HANDSHAKE)
        handshake.readMessage(Records.payload(record))
        val transmitted = handshake.remoteStaticKey()
            ?: throw ProtocolException("initiator static key missing", ReasonCodes.IDENTITY_MISMATCH)
        if (!MessageDigest.isEqual(transmitted, pinnedPeerStatic)) {
            throw ProtocolException("initiator static does not match the pinned peer key", ReasonCodes.IDENTITY_MISMATCH)
        }
    }

    /** Responder: produce record 0x02 (session_auth / msg2) and complete. */
    fun writeHandshakeResponse(): ByteArray {
        require(role == Role.RESPONDER) { "only the responder writes msg2" }
        val record = Records.build(Records.SESSION_HANDSHAKE, handshake.writeMessage(ByteArray(0)))
        finishHandshake()
        return record
    }

    /** Initiator: consume record 0x02 (msg2) and complete. */
    fun readHandshakeResponse(record: ByteArray) {
        require(role == Role.INITIATOR) { "only the initiator reads msg2" }
        expectRecordType(record, Records.SESSION_HANDSHAKE)
        handshake.readMessage(Records.payload(record))
        finishHandshake()
    }

    private fun finishHandshake() {
        val (c1, c2) = handshake.splitStates ?: throw ProtocolException("handshake not complete")
        initToResp = c1
        respToInit = c2
    }

    /** (initiator→responder key, responder→initiator key) after the handshake. */
    fun transportKeys(): Pair<ByteArray, ByteArray> {
        val c1 = initToResp ?: throw ProtocolException("handshake not complete")
        val c2 = respToInit ?: throw ProtocolException("handshake not complete")
        return c1.keyBytes()!! to c2.keyBytes()!!
    }

    // --- session transport ---

    fun send(msgType: Long, body: CborMap): ByteArray {
        val cipher = sendCipher
        guardNonce(cipher)
        val sid = sessionId ?: throw ProtocolException("session id not established")
        val plaintext = SessionEnvelopeCodec.encode(SessionEnvelope(msgType, cipher.nonce, sid, body))
        if (plaintext.size > Records.MAX_PLAINTEXT_ENVELOPE) {
            throw ProtocolException("session envelope exceeds the plaintext limit")
        }
        val ciphertext = cipher.encryptWithAd(EMPTY_AD, plaintext)
        return Records.build(Records.SESSION_TRANSPORT, ciphertext)
    }

    /** Returns the decrypted envelope, or null when a msgType ≥ 128 is ignored. */
    fun receive(record: ByteArray): SessionEnvelope? {
        val incoming = receiveDetailed(record)
        return if (incoming.ignored) null else incoming.envelope
    }

    fun receiveDetailed(record: ByteArray): Incoming {
        expectRecordType(record, Records.SESSION_TRANSPORT)
        val cipher = recvCipher
        guardNonce(cipher)
        val expectedSeq = cipher.nonce
        val plaintext = cipher.decryptWithAd(EMPTY_AD, Records.payload(record))
        val envelope = SessionEnvelopeCodec.decode(plaintext)
        if (envelope.seq != expectedSeq) throw ProtocolException("seq does not match the counter")
        val currentSid = sessionId
        if (currentSid == null) {
            sessionId = envelope.sid
        } else if (!envelope.sid.contentEquals(currentSid)) {
            throw ProtocolException("session id mismatch")
        }
        if (envelope.msgType == SessionMsgType.SESSION_READY) {
            val maxVersion = (envelope.body[3L] as? CborInt)?.value
            if (maxVersion != null && maxVersion < 2) {
                throw ProtocolException("protocol downgrade below version 2", ReasonCodes.CAPABILITY_MISMATCH)
            }
        }
        if (envelope.msgType !in SessionMsgType.KNOWN) {
            if (envelope.msgType < 128) throw ProtocolException("unknown session msgType ${envelope.msgType}")
            return Incoming(envelope, ignored = true)
        }
        return Incoming(envelope, ignored = false)
    }

    private fun guardNonce(cipher: CipherState) {
        if (cipher.nonce >= MAX_NONCE) {
            throw ProtocolException("counter overflow; re-handshake required before 2^32")
        }
    }

    private fun expectRecordType(record: ByteArray, expected: Int) {
        if (Records.recordType(record) != expected) {
            throw ProtocolException("unexpected record type for this phase")
        }
    }

    /** Test hook to exercise the counter overflow guard without 2^32 sends. */
    internal fun debugSetSendCounter(value: Long) {
        sendCipher.setNonce(value)
    }

    companion object {
        private val EMPTY_AD = ByteArray(0)
        const val MAX_NONCE: Long = 1L shl 32

        fun initiator(
            pairId: ByteArray,
            transportTag: String,
            localStaticPrivate: ByteArray,
            pinnedPeerStatic: ByteArray,
            pairRoot: ByteArray,
            sid: ByteArray,
            fixedEphemeralPrivate: ByteArray? = null,
        ) = SecureSession(
            Role.INITIATOR, pairId, transportTag, localStaticPrivate, pinnedPeerStatic,
            pairRoot, sid, fixedEphemeralPrivate,
        )

        fun responder(
            pairId: ByteArray,
            transportTag: String,
            localStaticPrivate: ByteArray,
            pinnedPeerStatic: ByteArray,
            pairRoot: ByteArray,
            fixedEphemeralPrivate: ByteArray? = null,
        ) = SecureSession(
            Role.RESPONDER, pairId, transportTag, localStaticPrivate, pinnedPeerStatic,
            pairRoot, null, fixedEphemeralPrivate,
        )
    }
}
