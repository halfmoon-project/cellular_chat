package com.cellularchat.app.transport

import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.protocol.Records
import com.cellularchat.app.core.protocol.SecureSession
import com.cellularchat.app.core.protocol.SessionEnvelope
import com.cellularchat.app.core.protocol.SessionMsgType

/**
 * Runs the core IKpsk2 [SecureSession] over an established [PeerTransport]
 * (PROTOCOL_V2.md §8). Handles the handshake, then the mandatory session_ready
 * exchange (initiator first), then routes authenticated transport messages to
 * the caller. A §14 rejection tears the link down with a §13 reason rather than
 * crashing.
 *
 * Pure with respect to radios — over a pair of in-memory transports it drives a
 * complete authenticated session, so it is unit-testable end to end.
 */
class SecureSessionRunner(
    private val transport: PeerTransport,
    private val session: SecureSession,
    private val isInitiator: Boolean,
    private val sessionReadyBody: () -> CborMap,
    private val events: Events,
) : PeerTransport.Listener {

    interface Events {
        /** Both session_ready messages exchanged; the peer is authenticated. */
        fun onAuthenticated(peerSessionReady: SessionEnvelope)
        fun onSessionMessage(envelope: SessionEnvelope)
        fun onClosed(reason: Int)
    }

    private var sentReady = false
    private var authenticated = false
    private var closed = false

    fun start() {
        transport.setListener(this)
        if (isInitiator) {
            send(session.startHandshake())
        }
    }

    /** Sends an authenticated session transport message. */
    fun sendMessage(msgType: Long, body: CborMap) {
        if (closed) return
        guard { send(session.send(msgType, body)) }
    }

    override fun onRecord(record: ByteArray) {
        if (closed) return
        guard {
            when (Records.recordType(record)) {
                Records.SESSION_HANDSHAKE -> onHandshake(record)
                Records.SESSION_TRANSPORT -> onTransport(record)
                else -> throw ProtocolException("unexpected record type for session")
            }
        }
    }

    override fun onLinkLost(reason: Int) {
        fail(reason)
    }

    private fun onHandshake(record: ByteArray) {
        if (isInitiator) {
            session.readHandshakeResponse(record) // msg2
            afterEstablished()
        } else {
            session.readHandshakeInit(record) // msg1 (pinned-static check inside)
            send(session.writeHandshakeResponse()) // msg2
            afterEstablished()
        }
    }

    private fun afterEstablished() {
        // The initiator's first transport message MUST be session_ready (§8).
        if (isInitiator && !sentReady) sendReady()
    }

    private fun onTransport(record: ByteArray) {
        val envelope = session.receive(record) ?: return // ignored msgType >= 128
        // §8: the initiator's first transport message MUST be session_ready. Any
        // other message before authentication is a fatal protocol error (§14).
        if (!authenticated && envelope.msgType != SessionMsgType.SESSION_READY) {
            throw ProtocolException("first session message must be session_ready", ReasonCodes.PROTOCOL_ERROR)
        }
        if (envelope.msgType == SessionMsgType.SESSION_READY) {
            if (!isInitiator && !sentReady) sendReady() // responder replies before anything else.
            if (!authenticated) {
                authenticated = true
                events.onAuthenticated(envelope)
            }
            return
        }
        events.onSessionMessage(envelope)
    }

    private fun sendReady() {
        sentReady = true
        send(session.send(SessionMsgType.SESSION_READY, sessionReadyBody()))
    }

    private fun send(record: ByteArray) {
        transport.send(record)
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: ProtocolException) {
            fail(e.reason)
        } catch (e: Exception) {
            fail(ReasonCodes.PROTOCOL_ERROR)
        }
    }

    private fun fail(reason: Int) {
        if (closed) return
        closed = true
        runCatching { transport.close() }
        events.onClosed(reason)
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { transport.close() }
    }
}
