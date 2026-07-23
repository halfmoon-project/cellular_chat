package com.cellularchat.app.transport

import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.cbor.cborMapOf
import com.cellularchat.app.core.crypto.X25519
import com.cellularchat.app.core.protocol.CapabilitySet
import com.cellularchat.app.core.protocol.SecureSession
import com.cellularchat.app.core.protocol.SessionEnvelope
import com.cellularchat.app.core.protocol.SessionMsgType
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two runners over a synchronous in-memory link drive a full authenticated session. */
private class LinkedTransport(override val tag: String) : PeerTransport {
    var peer: LinkedTransport? = null
    private var listener: PeerTransport.Listener? = null
    override fun setListener(listener: PeerTransport.Listener) { this.listener = listener }
    override fun send(record: ByteArray) { peer?.deliver(record) }
    fun deliver(record: ByteArray) { listener?.onRecord(record) }
    override fun close() = Unit
}

class SecureSessionRunnerTest {
    private val random = SecureRandom()

    private fun caps(): CborMap = cborMapOf(
        1L to CapabilitySet(CapabilitySet.OS_ANDROID, "16", "2.0.0", uwbPresent = true).toCbor(),
        2L to CborInt(0),
        3L to CborInt(2),
    )

    @Test
    fun completesHandshakeAndRoutesMessages() {
        val initPriv = X25519.generatePrivate()
        val respPriv = X25519.generatePrivate()
        val initPub = X25519.derivePublic(initPriv)
        val respPub = X25519.derivePublic(respPriv)
        val pairRoot = ByteArray(32).also { random.nextBytes(it) }
        val pairId = ByteArray(16).also { random.nextBytes(it) }
        val sid = ByteArray(16).also { random.nextBytes(it) }

        val initiatorTransport = LinkedTransport("ble")
        val responderTransport = LinkedTransport("ble")
        initiatorTransport.peer = responderTransport
        responderTransport.peer = initiatorTransport

        var initiatorAuthed = false
        var responderAuthed = false
        var responderReceived: SessionEnvelope? = null

        val initiatorSession = SecureSession.initiator(pairId, "ble", initPriv, respPub, pairRoot, sid)
        val responderSession = SecureSession.responder(pairId, "ble", respPriv, initPub, pairRoot)

        val initiatorRunner = SecureSessionRunner(
            initiatorTransport, initiatorSession, isInitiator = true, sessionReadyBody = { caps() },
            events = object : SecureSessionRunner.Events {
                override fun onAuthenticated(peerSessionReady: SessionEnvelope) { initiatorAuthed = true }
                override fun onSessionMessage(envelope: SessionEnvelope) = Unit
                override fun onClosed(reason: Int) = Unit
            },
        )
        val responderRunner = SecureSessionRunner(
            responderTransport, responderSession, isInitiator = false, sessionReadyBody = { caps() },
            events = object : SecureSessionRunner.Events {
                override fun onAuthenticated(peerSessionReady: SessionEnvelope) { responderAuthed = true }
                override fun onSessionMessage(envelope: SessionEnvelope) { responderReceived = envelope }
                override fun onClosed(reason: Int) = Unit
            },
        )

        responderRunner.start()
        initiatorRunner.start()

        assertTrue("initiator authenticated", initiatorAuthed)
        assertTrue("responder authenticated", responderAuthed)

        // A post-auth application message is delivered and decrypted in order.
        initiatorRunner.sendMessage(SessionMsgType.PING, cborMapOf(1L to CborInt(7)))
        val received = responderReceived
        assertNotNull(received)
        assertEquals(SessionMsgType.PING, received!!.msgType)
        assertEquals(7L, (received.body[1L] as CborInt).value)
    }

    @Test
    fun wrongPinnedKeyTearsDownResponder() {
        val initPriv = X25519.generatePrivate()
        val respPriv = X25519.generatePrivate()
        val respPub = X25519.derivePublic(respPriv)
        val wrongPeer = X25519.derivePublic(X25519.generatePrivate()) // not the initiator's key
        val pairRoot = ByteArray(32).also { random.nextBytes(it) }
        val pairId = ByteArray(16).also { random.nextBytes(it) }
        val sid = ByteArray(16).also { random.nextBytes(it) }

        val a = LinkedTransport("ble")
        val b = LinkedTransport("ble")
        a.peer = b
        b.peer = a

        var responderClosed = false
        val initiatorSession = SecureSession.initiator(pairId, "ble", initPriv, respPub, pairRoot, sid)
        // Responder pins the WRONG peer key: the transmitted static must not match.
        val responderSession = SecureSession.responder(pairId, "ble", respPriv, wrongPeer, pairRoot)

        val initiatorRunner = SecureSessionRunner(
            a, initiatorSession, true, { caps() },
            object : SecureSessionRunner.Events {
                override fun onAuthenticated(peerSessionReady: SessionEnvelope) = Unit
                override fun onSessionMessage(envelope: SessionEnvelope) = Unit
                override fun onClosed(reason: Int) = Unit
            },
        )
        val responderRunner = SecureSessionRunner(
            b, responderSession, false, { caps() },
            object : SecureSessionRunner.Events {
                override fun onAuthenticated(peerSessionReady: SessionEnvelope) = Unit
                override fun onSessionMessage(envelope: SessionEnvelope) = Unit
                override fun onClosed(reason: Int) { responderClosed = true }
            },
        )
        responderRunner.start()
        initiatorRunner.start()

        assertTrue("responder rejected the unpinned initiator", responderClosed)
    }

    @Test
    fun nonSessionReadyFirstMessageTearsDownResponder() {
        val initPriv = X25519.generatePrivate()
        val respPriv = X25519.generatePrivate()
        val initPub = X25519.derivePublic(initPriv)
        val respPub = X25519.derivePublic(respPriv)
        val pairRoot = ByteArray(32).also { random.nextBytes(it) }
        val pairId = ByteArray(16).also { random.nextBytes(it) }
        val sid = ByteArray(16).also { random.nextBytes(it) }

        val initiatorTransport = LinkedTransport("ble")
        val responderTransport = LinkedTransport("ble")
        initiatorTransport.peer = responderTransport
        responderTransport.peer = initiatorTransport

        val initiatorSession = SecureSession.initiator(pairId, "ble", initPriv, respPub, pairRoot, sid)
        val responderSession = SecureSession.responder(pairId, "ble", respPriv, initPub, pairRoot)

        var responderClosedReason: Int? = null
        val responderRunner = SecureSessionRunner(
            responderTransport, responderSession, isInitiator = false, sessionReadyBody = { caps() },
            events = object : SecureSessionRunner.Events {
                override fun onAuthenticated(peerSessionReady: SessionEnvelope) = Unit
                override fun onSessionMessage(envelope: SessionEnvelope) = Unit
                override fun onClosed(reason: Int) { responderClosedReason = reason }
            },
        )
        responderRunner.start()

        // A hand-driven initiator that completes the handshake but then sends a
        // ping (not session_ready) as its first transport message.
        initiatorTransport.setListener(object : PeerTransport.Listener {
            override fun onRecord(record: ByteArray) { initiatorSession.readHandshakeResponse(record) }
            override fun onLinkLost(reason: Int) = Unit
        })
        initiatorTransport.send(initiatorSession.startHandshake())
        initiatorTransport.send(initiatorSession.send(SessionMsgType.PING, cborMapOf(1L to CborInt(1))))

        assertEquals(ReasonCodes.PROTOCOL_ERROR, responderClosedReason)
    }
}
