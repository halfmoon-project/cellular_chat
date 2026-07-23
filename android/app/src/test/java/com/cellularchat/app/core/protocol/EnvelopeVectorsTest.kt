package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.AeadException
import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.core.Vectors
import com.cellularchat.app.core.hex
import com.cellularchat.app.core.toHex
import com.cellularchat.app.core.crypto.X25519
import com.cellularchat.app.core.noise.HandshakeState
import com.cellularchat.app.core.noise.NoisePattern
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeVectorsTest {
    private val v = Vectors.json("envelope_vectors.json")
    private val session = v.getJSONObject("session")

    private val pairId = hex(Vectors.json("derivation_vectors.json").getString("pairIdHex"))
    private val pairRoot = hex(Vectors.json("derivation_vectors.json").getString("pairRootHex"))
    private val transportTag = session.getString("transportTag")
    private val initStaticPriv = hex(session.getString("initStaticPrivHex"))
    private val respStaticPriv = hex(session.getString("respStaticPrivHex"))
    private val initStaticPub = X25519.derivePublic(initStaticPriv)
    private val respStaticPub = X25519.derivePublic(respStaticPriv)
    private val sid = hex(session.getString("sidHex"))

    private fun newInitiator() = SecureSession.initiator(
        pairId, transportTag, initStaticPriv, respStaticPub, pairRoot, sid,
        fixedEphemeralPrivate = hex(session.getString("initEphemeralPrivHex")),
    )

    private fun newResponder() = SecureSession.responder(
        pairId, transportTag, respStaticPriv, initStaticPub, pairRoot,
        fixedEphemeralPrivate = hex(session.getString("respEphemeralPrivHex")),
    )

    /** Runs the fresh IKpsk2 handshake and returns the established sessions. */
    private fun establish(): Pair<SecureSession, SecureSession> {
        val init = newInitiator()
        val resp = newResponder()
        val msg1 = init.startHandshake()
        assertEquals("msg1", session.getString("msg1RecordHex"), msg1.toHex())
        resp.readHandshakeInit(msg1)
        val msg2 = resp.writeHandshakeResponse()
        assertEquals("msg2", session.getString("msg2RecordHex"), msg2.toHex())
        init.readHandshakeResponse(msg2)
        return init to resp
    }

    @Test
    fun handshakeAndSplitKeysMatchFixture() {
        val (init, resp) = establish()
        assertEquals(session.getString("handshakeHashHex"), init.handshakeHash().toHex())
        assertEquals(session.getString("handshakeHashHex"), resp.handshakeHash().toHex())

        val (initToResp, respToInit) = init.transportKeys()
        assertEquals(session.getString("kInitToRespHex"), initToResp.toHex())
        assertEquals(session.getString("kRespToInitHex"), respToInit.toHex())
        assertArrayEquals(initToResp, resp.transportKeys().first)
        assertArrayEquals(respToInit, resp.transportKeys().second)
    }

    @Test
    fun transportRecordsReplayExactBothDirections() {
        val (init, resp) = establish()
        val records = session.getJSONArray("transportRecords")
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            val plaintext = SessionEnvelopeCodec.decode(hex(record.getString("plaintextHex")))
            val expectedRecord = record.getString("recordHex")
            val msgType = record.getLong("msgType")
            val seq = record.getLong("seq")

            val sender = if (record.getString("sender") == "init") init else resp
            val receiver = if (record.getString("sender") == "init") resp else init

            val produced = sender.send(msgType, plaintext.body)
            assertEquals("record $i ($msgType)", expectedRecord, produced.toHex())

            val received = receiver.receive(hex(expectedRecord))!!
            assertEquals("record $i msgType", msgType, received.msgType)
            assertEquals("record $i seq", seq, received.seq)
            assertArrayEquals("record $i sid", sid, received.sid)
        }
    }

    @Test
    fun wrongPskPairingResponderFailsReadingMsg1() {
        val failure = failure("wrongPskPairing")
        val responder = HandshakeState(
            pattern = NoisePattern.NN_PSK0,
            initiator = false,
            prologue = hex(failure.getString("pairingPrologueHex")),
            psk = hex(failure.getString("pskHex")),
            fixedEphemeralPrivate = hex(failure.getString("respEphemeralPrivHex")),
        )
        val msg1 = hex(failure.getString("msg1RecordHex")).copyOfRange(1, hex(failure.getString("msg1RecordHex")).size)
        assertThrows(ProtocolException::class.java) { responder.readMessage(msg1) }
    }

    @Test
    fun substitutedStaticIsRejectedByPinningBeforeAnyResponse() {
        val failure = failure("substitutedStatic")
        val responder = newResponder()
        val record = hex(failure.getString("msg1RecordHex"))
        val error = assertThrows(ProtocolException::class.java) { responder.readHandshakeInit(record) }
        assertEquals(ReasonCodes.IDENTITY_MISMATCH, error.reason)
    }

    @Test
    fun bitFlipFailsAead() {
        val (_, resp) = establish()
        val failure = failure("bitFlip")
        assertThrows(AeadException::class.java) { resp.receive(hex(failure.getString("recordHex"))) }
    }

    @Test
    fun replayFailsOnSecondDelivery() {
        val (_, resp) = establish()
        val failure = failure("replay")
        val record = hex(failure.getString("recordHex"))
        val first = resp.receive(record)
        assertTrue(first != null)
        assertThrows(AeadException::class.java) { resp.receive(record) }
    }

    private fun failure(name: String): org.json.JSONObject {
        val failures = v.getJSONArray("failures")
        for (i in 0 until failures.length()) {
            val entry = failures.getJSONObject(i)
            if (entry.getString("case") == name) return entry
        }
        error("missing failure case $name")
    }
}
