package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.Vectors
import com.cellularchat.app.core.hex
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.cborMapOf
import com.cellularchat.app.core.crypto.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureSessionExtraTest {
    private val session = Vectors.json("envelope_vectors.json").getJSONObject("session")
    private val pairId = hex(Vectors.json("derivation_vectors.json").getString("pairIdHex"))
    private val pairRoot = hex(Vectors.json("derivation_vectors.json").getString("pairRootHex"))
    private val tag = session.getString("transportTag")
    private val initStaticPriv = hex(session.getString("initStaticPrivHex"))
    private val respStaticPriv = hex(session.getString("respStaticPrivHex"))
    private val initStaticPub = X25519.derivePublic(initStaticPriv)
    private val respStaticPub = X25519.derivePublic(respStaticPriv)
    private val sid = hex(session.getString("sidHex"))

    private fun newInitiator() = SecureSession.initiator(
        pairId, tag, initStaticPriv, respStaticPub, pairRoot, sid,
        fixedEphemeralPrivate = hex(session.getString("initEphemeralPrivHex")),
    )

    private fun newResponder() = SecureSession.responder(
        pairId, tag, respStaticPriv, initStaticPub, pairRoot,
        fixedEphemeralPrivate = hex(session.getString("respEphemeralPrivHex")),
    )

    private fun establish(): Pair<SecureSession, SecureSession> {
        val init = newInitiator()
        val resp = newResponder()
        val msg1 = init.startHandshake()
        resp.readHandshakeInit(msg1)
        val msg2 = resp.writeHandshakeResponse()
        init.readHandshakeResponse(msg2)
        return init to resp
    }

    private fun ping(n: Long) = cborMapOf(1L to CborInt(n))

    @Test
    fun counterOverflowGuardStopsBefore2Pow32() {
        val (init, _) = establish()
        init.debugSetSendCounter(SecureSession.MAX_NONCE - 1)
        init.send(SessionMsgType.PING, ping(1)) // last legal counter (2^32 - 1)
        assertThrows(ProtocolException::class.java) { init.send(SessionMsgType.PING, ping(2)) }
    }

    @Test
    fun oversizeAndZeroLengthRecordsAreRejected() {
        assertThrows(ProtocolException::class.java) { Records.validate(ByteArray(Records.MAX_RECORD + 1)) }
        assertThrows(ProtocolException::class.java) { Records.validate(ByteArray(0)) }
    }

    @Test
    fun handshakeRecordAfterCompletionIsRejected() {
        val (init, _) = establish()
        val staleHandshakeRecord = Records.build(Records.SESSION_HANDSHAKE, ByteArray(48))
        assertThrows(ProtocolException::class.java) { init.receive(staleHandshakeRecord) }
    }

    @Test
    fun transportMessageBeforeCompletionIsRejected() {
        val init = newInitiator()
        assertThrows(ProtocolException::class.java) { init.send(SessionMsgType.PING, ping(1)) }
        val resp = newResponder()
        assertThrows(ProtocolException::class.java) {
            resp.receive(Records.build(Records.SESSION_TRANSPORT, ByteArray(32)))
        }
    }

    @Test
    fun unknownMsgTypeBelow128IsFatalAndAtLeast128IsIgnored() {
        val fatalPair = establish()
        val record = fatalPair.first.send(99L, ping(1))
        assertThrows(ProtocolException::class.java) { fatalPair.second.receive(record) }

        val ignoredPair = establish()
        val ignoredRecord = ignoredPair.first.send(200L, ping(1))
        assertNull(ignoredPair.second.receive(ignoredRecord))
    }
}
