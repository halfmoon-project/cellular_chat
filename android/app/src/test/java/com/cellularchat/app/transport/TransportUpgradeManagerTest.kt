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
import com.cellularchat.app.core.protocol.TransportCode
import com.cellularchat.app.core.protocol.TransportUpgradeCodec
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature A: the BLE→aware transport upgrade driven over two in-memory
 * transports. Proves same-`sid` continuity, ranging repoint only after both
 * `session_ready`, BLE retention, failure isolation (decline / K handshake
 * failure / timeout leave the working transport untouched and emit no
 * switchover), and duplicate-attempt idempotency.
 */
class TransportUpgradeManagerTest {
    private val random = SecureRandom()

    /** In-memory transport that also propagates a local close as peer link-loss. */
    private class Link(override val tag: String) : PeerTransport {
        var peer: Link? = null
        private var listener: PeerTransport.Listener? = null
        var closed = false
            private set

        override fun setListener(listener: PeerTransport.Listener) { this.listener = listener }
        override fun send(record: ByteArray) { peer?.deliver(record) }
        fun deliver(record: ByteArray) { if (!closed) listener?.onRecord(record) }
        override fun close() {
            if (closed) return
            closed = true
            peer?.notifyLost()
        }
        private fun notifyLost() {
            if (closed) return
            listener?.onLinkLost(ReasonCodes.TRANSPORT_LOST)
        }
    }

    private class Sched {
        data class Task(val delay: Long, val action: () -> Unit)
        val tasks = mutableListOf<Task>()
        fun schedule(delay: Long, action: () -> Unit): TransportUpgradeManager.Cancel {
            val t = Task(delay, action)
            tasks.add(t)
            return TransportUpgradeManager.Cancel { tasks.remove(t) }
        }
        fun fire(delay: Long) = tasks.filter { it.delay == delay }.toList().forEach { it.action() }
    }

    /** Pairs the two K endpoints and readies the host (responder) before the connector. */
    private class KNet {
        private class Pending(val initiator: Boolean, val end: Link, val onReady: (PeerTransport) -> Unit)
        private val pending = mutableListOf<Pending>()
        fun open(initiator: Boolean, onReady: (PeerTransport) -> Unit) {
            pending.add(Pending(initiator, Link("aware"), onReady))
            if (pending.size < 2) return
            val a = pending[0]
            val b = pending[1]
            a.end.peer = b.end
            b.end.peer = a.end
            pending.clear()
            val host = if (!a.initiator) a else b
            val conn = if (host === a) b else a
            host.onReady(host.end)
            conn.onReady(conn.end)
        }
    }

    private fun caps() = CapabilitySet(CapabilitySet.OS_ANDROID, "16", "2.0.0", wifiAware = true)

    private class Setup(
        val sid: ByteArray,
        val driverManager: TransportUpgradeManager,
        val driverWorkLink: Link,
        val responderWorkLink: Link,
        val driverSched: Sched,
        val switchovers: MutableList<Pair<String, Long>>,
        val sidsPassedToBuildRunner: MutableList<ByteArray>,
    )

    private fun setup(
        responderTargets: Set<String> = setOf("aware"),
        responderKWrongKey: Boolean = false,
    ): Setup {
        val pairId = ByteArray(16).also { random.nextBytes(it) }
        val pairRoot = ByteArray(32).also { random.nextBytes(it) }
        val sid = ByteArray(16).also { random.nextBytes(it) }
        val driverPriv = X25519.generatePrivate()
        val driverPub = X25519.derivePublic(driverPriv)
        val respPriv = X25519.generatePrivate()
        val respPub = X25519.derivePublic(respPriv)
        val caps = caps()
        val readyBody = { cborMapOf(1L to caps.toCbor(), 2L to CborInt(0), 3L to CborInt(2)) }

        val driverWork = Link("ble")
        val responderWork = Link("ble")
        driverWork.peer = responderWork
        responderWork.peer = driverWork

        val driverMgrRef = arrayOfNulls<TransportUpgradeManager>(1)
        val responderMgrRef = arrayOfNulls<TransportUpgradeManager>(1)

        val driverWorkRunner = SecureSessionRunner(
            driverWork,
            SecureSession.initiator(pairId, "ble", driverPriv, respPub, pairRoot, sid),
            isInitiator = true,
            sessionReadyBody = readyBody,
            events = object : SecureSessionRunner.Events {
                override fun onAuthenticated(peerSessionReady: SessionEnvelope) = Unit
                override fun onSessionMessage(envelope: SessionEnvelope) = Unit
                override fun onClosed(reason: Int) = Unit
                override fun onTransportControl(envelope: SessionEnvelope) { driverMgrRef[0]?.onControl(envelope) }
            },
        )
        val responderWorkRunner = SecureSessionRunner(
            responderWork,
            SecureSession.responder(pairId, "ble", respPriv, driverPub, pairRoot),
            isInitiator = false,
            sessionReadyBody = readyBody,
            events = object : SecureSessionRunner.Events {
                override fun onAuthenticated(peerSessionReady: SessionEnvelope) = Unit
                override fun onSessionMessage(envelope: SessionEnvelope) = Unit
                override fun onClosed(reason: Int) = Unit
                override fun onTransportControl(envelope: SessionEnvelope) { responderMgrRef[0]?.onControl(envelope) }
            },
        )
        responderWorkRunner.start()
        driverWorkRunner.start() // authenticates both working runners

        val knet = KNet()
        val driverSched = Sched()
        val responderSched = Sched()
        val switchovers = mutableListOf<Pair<String, Long>>()
        val sids = mutableListOf<ByteArray>()
        val driverActive = arrayOf("ble")
        val responderActive = arrayOf("ble")

        val driverDeps = object : TransportUpgradeManager.Deps {
            override fun activeTag() = driverActive[0]
            override fun availableTargets() = setOf("aware")
            override fun sendControl(msgType: Long, body: CborMap) { driverWorkRunner.sendMessage(msgType, body) }
            override fun openTransport(tag: String, initiator: Boolean, onReady: (PeerTransport) -> Unit, onFailed: () -> Unit) =
                knet.open(initiator, onReady)
            override fun buildRunner(tag: String, transport: PeerTransport, initiator: Boolean, sid: ByteArray, events: SecureSessionRunner.Events): SecureSessionRunner {
                sids.add(sid)
                return SecureSessionRunner(
                    transport,
                    SecureSession.initiator(pairId, tag, driverPriv, respPub, pairRoot, sid),
                    isInitiator = true,
                    sessionReadyBody = readyBody,
                    events = events,
                )
            }
            override fun switchover(newRunner: SecureSessionRunner, newTag: String, attemptId: Long) {
                driverActive[0] = newTag
                switchovers.add(newTag to attemptId)
            }
            override fun schedule(delayMillis: Long, action: () -> Unit) = driverSched.schedule(delayMillis, action)
        }
        val responderDeps = object : TransportUpgradeManager.Deps {
            override fun activeTag() = responderActive[0]
            override fun availableTargets() = responderTargets
            override fun sendControl(msgType: Long, body: CborMap) { responderWorkRunner.sendMessage(msgType, body) }
            override fun openTransport(tag: String, initiator: Boolean, onReady: (PeerTransport) -> Unit, onFailed: () -> Unit) =
                knet.open(initiator, onReady)
            override fun buildRunner(tag: String, transport: PeerTransport, initiator: Boolean, sid: ByteArray, events: SecureSessionRunner.Events): SecureSessionRunner {
                sids.add(sid)
                val pinned = if (responderKWrongKey) X25519.derivePublic(X25519.generatePrivate()) else driverPub
                return SecureSessionRunner(
                    transport,
                    SecureSession.responderForUpgrade(pairId, tag, respPriv, pinned, pairRoot, sid),
                    isInitiator = false,
                    sessionReadyBody = readyBody,
                    events = events,
                )
            }
            override fun switchover(newRunner: SecureSessionRunner, newTag: String, attemptId: Long) {
                responderActive[0] = newTag
                switchovers.add(newTag to attemptId)
            }
            override fun schedule(delayMillis: Long, action: () -> Unit) = responderSched.schedule(delayMillis, action)
        }

        val driverMgr = TransportUpgradeManager(sid, isDriver = true, peerCaps = caps, deps = driverDeps)
        val responderMgr = TransportUpgradeManager(sid, isDriver = false, peerCaps = caps, deps = responderDeps)
        driverMgrRef[0] = driverMgr
        responderMgrRef[0] = responderMgr

        return Setup(sid, driverMgr, driverWork, responderWork, driverSched, switchovers, sids)
    }

    @Test
    fun upgradesToAwareWithSameSidAndRetainsBle() {
        val s = setup()
        s.driverManager.evaluate() // drives the whole upgrade synchronously

        assertEquals("both sides switch over", 2, s.switchovers.size)
        assertTrue("switchover targets aware", s.switchovers.all { it.first == "aware" })
        // Same logical sid on both K runners (sid continuity, A.6).
        assertEquals(2, s.sidsPassedToBuildRunner.size)
        assertTrue("K reuses the logical sid", s.sidsPassedToBuildRunner.all { it.contentEquals(s.sid) })
        // The BLE working transports are retained (the manager never closes them).
        assertFalse(s.driverWorkLink.closed)
        assertFalse(s.responderWorkLink.closed)
    }

    @Test
    fun declineLeavesWorkingTransportAndStateUntouched() {
        val s = setup(responderTargets = emptySet()) // responder cannot host aware -> declines
        s.driverManager.evaluate()

        assertEquals("no switchover on decline", 0, s.switchovers.size)
        assertFalse(s.driverWorkLink.closed)
        assertFalse(s.responderWorkLink.closed)
        assertTrue("driver backs off", s.driverSched.tasks.any { it.delay == 5_000L })
    }

    @Test
    fun kHandshakeFailureLeavesWorkingTransportUntouched() {
        val s = setup(responderKWrongKey = true) // K responder pins the wrong key
        s.driverManager.evaluate()

        assertEquals("no switchover on K failure", 0, s.switchovers.size)
        assertFalse("BLE working transport survives a K failure", s.driverWorkLink.closed)
        assertFalse(s.responderWorkLink.closed)
    }

    @Test
    fun timeoutAbandonsAttemptWithoutDisturbingWorking() {
        val sched = Sched()
        val switchovers = mutableListOf<Pair<String, Long>>()
        var controlSent = 0
        val deps = object : TransportUpgradeManager.Deps {
            override fun activeTag() = "ble"
            override fun availableTargets() = setOf("aware")
            override fun sendControl(msgType: Long, body: CborMap) { controlSent++ } // ack never arrives
            override fun openTransport(tag: String, initiator: Boolean, onReady: (PeerTransport) -> Unit, onFailed: () -> Unit) =
                error("no K without an ack")
            override fun buildRunner(tag: String, transport: PeerTransport, initiator: Boolean, sid: ByteArray, events: SecureSessionRunner.Events) =
                error("no runner without an ack")
            override fun switchover(newRunner: SecureSessionRunner, newTag: String, attemptId: Long) { switchovers.add(newTag to attemptId) }
            override fun schedule(delayMillis: Long, action: () -> Unit) = sched.schedule(delayMillis, action)
        }
        val mgr = TransportUpgradeManager(ByteArray(16), isDriver = true, peerCaps = caps(), deps = deps)
        mgr.evaluate()
        assertEquals("upgrade was offered", 1, controlSent)

        sched.fire(TransportUpgradeManager.ATTEMPT_TIMEOUT_MS) // 10 s elapse with no ack
        assertEquals("no switchover on timeout", 0, switchovers.size)
        assertTrue("driver backs off after a timeout", sched.tasks.any { it.delay == 5_000L })
    }

    @Test
    fun duplicateUpgradeIsANoOp() {
        val sched = Sched()
        var opened = 0
        val acks = mutableListOf<CborMap>()
        val deps = object : TransportUpgradeManager.Deps {
            override fun activeTag() = "ble"
            override fun availableTargets() = setOf("aware")
            override fun sendControl(msgType: Long, body: CborMap) {
                if (msgType == SessionMsgType.TRANSPORT_ACK) acks.add(body)
            }
            override fun openTransport(tag: String, initiator: Boolean, onReady: (PeerTransport) -> Unit, onFailed: () -> Unit) { opened++ }
            override fun buildRunner(tag: String, transport: PeerTransport, initiator: Boolean, sid: ByteArray, events: SecureSessionRunner.Events) =
                error("no runner until the K transport is ready")
            override fun switchover(newRunner: SecureSessionRunner, newTag: String, attemptId: Long) = Unit
            override fun schedule(delayMillis: Long, action: () -> Unit) = sched.schedule(delayMillis, action)
        }
        val sid = ByteArray(16)
        val mgr = TransportUpgradeManager(sid, isDriver = false, peerCaps = caps(), deps = deps)
        val upgrade = SessionEnvelope(
            SessionMsgType.TRANSPORT_UPGRADE, 0, sid,
            TransportUpgradeCodec.encodeUpgrade(TransportCode.AWARE, 1),
        )
        mgr.onControl(upgrade)
        mgr.onControl(upgrade) // duplicate (sid, attemptId=1)

        assertEquals("duplicate opened no second K", 1, opened)
        assertEquals("cached ack re-sent on the duplicate", 2, acks.size)
    }
}
