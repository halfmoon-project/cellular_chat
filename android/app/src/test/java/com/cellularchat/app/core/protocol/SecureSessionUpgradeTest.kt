package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.cborMapOf
import com.cellularchat.app.core.crypto.X25519
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Feature A.6: a transport-upgrade responder pre-initialized with the logical
 * `sid` enforces sid EQUALITY on the first K message instead of adopting it, so a
 * first-message `sid != S` aborts transport K.
 */
class SecureSessionUpgradeTest {
    private class Fixture {
        val random = SecureRandom()
        val initPriv = X25519.generatePrivate()
        val respPriv = X25519.generatePrivate()
        val initPub = X25519.derivePublic(initPriv)
        val respPub = X25519.derivePublic(respPriv)
        val pairRoot = ByteArray(32).also { random.nextBytes(it) }
        val pairId = ByteArray(16).also { random.nextBytes(it) }
    }

    private fun handshake(initiator: SecureSession, responder: SecureSession) {
        responder.readHandshakeInit(initiator.startHandshake())
        initiator.readHandshakeResponse(responder.writeHandshakeResponse())
    }

    @Test
    fun upgradeResponderAcceptsMatchingSid() {
        val f = Fixture()
        val sid = ByteArray(16).also { f.random.nextBytes(it) }
        val initiator = SecureSession.initiator(f.pairId, "aware", f.initPriv, f.respPub, f.pairRoot, sid)
        val responder = SecureSession.responderForUpgrade(f.pairId, "aware", f.respPriv, f.initPub, f.pairRoot, sid)
        handshake(initiator, responder)

        val ready = initiator.send(SessionMsgType.SESSION_READY, cborMapOf(3L to CborInt(2)))
        val envelope = responder.receive(ready)
        assertEquals(SessionMsgType.SESSION_READY, envelope!!.msgType)
    }

    @Test
    fun upgradeResponderRejectsWrongSid() {
        val f = Fixture()
        val boundSid = ByteArray(16).also { f.random.nextBytes(it) }
        val otherSid = ByteArray(16).also { f.random.nextBytes(it) }
        val initiator = SecureSession.initiator(f.pairId, "aware", f.initPriv, f.respPub, f.pairRoot, otherSid)
        // Responder is pinned to boundSid: the first message's sid must equal it.
        val responder = SecureSession.responderForUpgrade(f.pairId, "aware", f.respPriv, f.initPub, f.pairRoot, boundSid)
        handshake(initiator, responder)

        val ready = initiator.send(SessionMsgType.SESSION_READY, cborMapOf(3L to CborInt(2)))
        assertThrows(ProtocolException::class.java) { responder.receive(ready) }
    }
}
