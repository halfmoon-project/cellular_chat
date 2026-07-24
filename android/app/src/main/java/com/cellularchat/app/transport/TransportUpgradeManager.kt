package com.cellularchat.app.transport

import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.protocol.CapabilitySet
import com.cellularchat.app.core.protocol.SessionEnvelope
import com.cellularchat.app.core.protocol.SessionMsgType
import com.cellularchat.app.core.protocol.TransportCode
import com.cellularchat.app.core.protocol.TransportUpgradeCodec
import com.cellularchat.app.ranging.BackoffSchedule

/**
 * BLE→higher-preference transport upgrade for one logical Find session
 * (PROTOCOL_V2.md §10, Feature A). The **upgrade driver** is the current
 * session's Noise initiator; only it evaluates and sends `transport_upgrade`. The
 * responder answers `transport_ack` and, on acceptance, hosts the new transport
 * `K` as the Noise responder pre-initialized with the same `sid`.
 *
 * The manager owns exactly the upgrade lifecycle: it never touches the working
 * transport, and a decline, a K handshake failure, a K sid/capability mismatch,
 * or a 10 s timeout tears down only `K` and backs off. Switchover (repointing
 * ranging + retaining/closing the previous transport) is delegated to [Deps]; it
 * happens only after BOTH `session_ready` are exchanged on `K`.
 *
 * Pure with respect to radios and time — every side effect is injected — so it is
 * unit-tested end to end over a pair of in-memory transports.
 */
class TransportUpgradeManager(
    private val sid: ByteArray,
    private val isDriver: Boolean,
    private val peerCaps: CapabilitySet,
    private val deps: Deps,
    private val backoff: BackoffSchedule = BackoffSchedule(),
) {
    interface Deps {
        /** Tag of the transport currently carrying ranging/state traffic. */
        fun activeTag(): String

        /** Locally-available upgrade targets right now (never includes "ble"). */
        fun availableTargets(): Set<String>

        /** Send a control message on the WORKING (active) transport's runner. */
        fun sendControl(msgType: Long, body: CborMap)

        /** Establish transport [tag]; [initiator] connects, else it hosts. */
        fun openTransport(
            tag: String,
            initiator: Boolean,
            onReady: (PeerTransport) -> Unit,
            onFailed: () -> Unit,
        )

        /** Build (do not start) a K runner reusing [sid]; sets [events] on it. */
        fun buildRunner(
            tag: String,
            transport: PeerTransport,
            initiator: Boolean,
            sid: ByteArray,
            events: SecureSessionRunner.Events,
        ): SecureSessionRunner

        /** Both session_ready exchanged on K: repoint ranging, retain/close previous. */
        fun switchover(newRunner: SecureSessionRunner, newTag: String, attemptId: Long)

        /** Schedule [action] after [delayMillis]; the handle cancels it. */
        fun schedule(delayMillis: Long, action: () -> Unit): Cancel

        /** A session message on a runner that has already become the active one. */
        fun onActiveSessionMessage(envelope: SessionEnvelope) {}

        /** The active (upgraded) transport was lost; revert per A.7. */
        fun onActiveTransportLost(reason: Int) {}
    }

    fun interface Cancel {
        fun cancel()
    }

    private class Attempt(val id: Long, val tag: String) {
        var runner: SecureSessionRunner? = null
        var timeout: Cancel? = null
        var switched: Boolean = false
    }

    private var upgradeAttemptId: Long = 0
    private var inFlight: Attempt? = null
    private var backingOff = false

    // Responder idempotency: the cached ack for the current (sid, attemptId).
    private var handledAttemptId: Long? = null
    private var cachedAck: CborMap? = null

    /** Driver-side periodic evaluation (A.3); call on the 5 s cadence. */
    fun evaluate() {
        if (!isDriver || inFlight != null || backingOff) return
        val active = deps.activeTag()
        val target = deps.availableTargets()
            .filter { TransportPreference.shouldUpgrade(active, it) && peerAdvertises(it) }
            .minByOrNull { TransportPreference.index(it) }
            ?: return
        startAttempt(target)
    }

    private fun peerAdvertises(tag: String): Boolean = when (tag) {
        "aware" -> peerCaps.wifiAware
        "nearby" -> peerCaps.nearbyConnections
        else -> false
    }

    private fun startAttempt(tag: String) {
        val code = TransportCode.code(tag) ?: return
        upgradeAttemptId += 1
        val attempt = Attempt(upgradeAttemptId, tag)
        inFlight = attempt
        // Arm the 10 s timeout before sending, so a synchronous ack+handshake can
        // cancel it on switchover (A.8).
        attempt.timeout = deps.schedule(ATTEMPT_TIMEOUT_MS) { abandon(attempt.id) }
        deps.sendControl(
            SessionMsgType.TRANSPORT_UPGRADE,
            TransportUpgradeCodec.encodeUpgrade(code, attempt.id),
        )
    }

    /**
     * Handle a `transport_upgrade`/`transport_ack` that arrived on the working
     * transport. A malformed body throws [com.cellularchat.app.core.ProtocolException],
     * which the caller surfaces to the working runner's guard (a §14 teardown).
     */
    fun onControl(envelope: SessionEnvelope) {
        when (envelope.msgType) {
            SessionMsgType.TRANSPORT_UPGRADE -> if (!isDriver) onUpgrade(envelope.body)
            SessionMsgType.TRANSPORT_ACK -> if (isDriver) onAck(envelope.body)
        }
    }

    // --- driver ---

    private fun onAck(body: CborMap) {
        val ack = TransportUpgradeCodec.decodeAck(body)
        val attempt = inFlight ?: return
        if (ack.attemptId != attempt.id) {
            abandon(attempt.id) // a stale/mismatched ack for a live attempt: abandon it.
            return
        }
        if (!ack.accepted) {
            abandon(attempt.id) // graceful decline: keep the working transport, back off.
            return
        }
        deps.openTransport(
            attempt.tag,
            initiator = true,
            onReady = { transport ->
                val cur = inFlight
                if (cur == null || cur.id != attempt.id) {
                    runCatching { transport.close() }
                    return@openTransport
                }
                startKRunner(cur, transport, initiator = true)
            },
            onFailed = { abandon(attempt.id) },
        )
    }

    // --- responder ---

    private fun onUpgrade(body: CborMap) {
        val upgrade = TransportUpgradeCodec.decodeUpgrade(body)
        // Idempotency (A.9): re-send the cached ack; never open a second K.
        if (handledAttemptId == upgrade.attemptId) {
            cachedAck?.let { deps.sendControl(SessionMsgType.TRANSPORT_ACK, it) }
            return
        }
        val tag = TransportCode.tag(upgrade.transport)
        val hostable = tag != null && tag != "ble" &&
            TransportPreference.shouldUpgrade(deps.activeTag(), tag) &&
            deps.availableTargets().contains(tag)
        val ack = TransportUpgradeCodec.encodeAck(upgrade.transport, upgrade.attemptId, hostable)
        handledAttemptId = upgrade.attemptId
        cachedAck = ack
        deps.sendControl(SessionMsgType.TRANSPORT_ACK, ack)
        // A well-typed but undesired transport is declined, not a failure (A.5.3).
        // `hostable` already implies `tag != null`, so it smart-casts below.
        if (!hostable) return
        val attempt = Attempt(upgrade.attemptId, tag)
        inFlight = attempt
        attempt.timeout = deps.schedule(ATTEMPT_TIMEOUT_MS) { abandon(attempt.id) }
        deps.openTransport(
            tag,
            initiator = false,
            onReady = { transport ->
                val cur = inFlight
                if (cur == null || cur.id != attempt.id) {
                    runCatching { transport.close() }
                    return@openTransport
                }
                startKRunner(cur, transport, initiator = false)
            },
            onFailed = { abandon(attempt.id) },
        )
    }

    // --- shared K lifecycle ---

    private fun startKRunner(attempt: Attempt, transport: PeerTransport, initiator: Boolean) {
        val runner = deps.buildRunner(attempt.tag, transport, initiator, sid, kEvents(attempt))
        attempt.runner = runner
        runCatching { runner.start() }
    }

    private fun kEvents(attempt: Attempt) = object : SecureSessionRunner.Events {
        override fun onAuthenticated(peerSessionReady: SessionEnvelope) {
            // A.6: the K session_ready CapabilitySet MUST equal the bound peer
            // set; a difference aborts transport K ONLY (never the logical session).
            val kPeerCaps = runCatching {
                (peerSessionReady.body[1L] as? CborMap)?.let { CapabilitySet.fromCbor(it) }
            }.getOrNull()
            if (kPeerCaps != null && kPeerCaps != peerCaps) {
                abandon(attempt.id)
                return
            }
            switchTo(attempt)
        }

        override fun onSessionMessage(envelope: SessionEnvelope) {
            // Ranging traffic is served only after switchover (A.7).
            if (attempt.switched) deps.onActiveSessionMessage(envelope)
        }

        override fun onClosed(reason: Int) {
            if (attempt.switched) {
                deps.onActiveTransportLost(reason)
            } else {
                abandon(attempt.id) // a K handshake failure: tear down K only.
            }
        }

        override fun onTransportControl(envelope: SessionEnvelope) {
            // Once K is the active transport it also carries upgrade control.
            if (attempt.switched) onControl(envelope)
        }
    }

    private fun switchTo(attempt: Attempt) {
        val runner = attempt.runner ?: return
        attempt.timeout?.cancel()
        attempt.switched = true
        inFlight = null
        backoff.reset() // reset the upgrade backoff after a successful upgrade (A.8).
        deps.switchover(runner, attempt.tag, attempt.id)
    }

    private fun abandon(id: Long) {
        val attempt = inFlight ?: return
        if (attempt.id != id) return
        attempt.timeout?.cancel()
        runCatching { attempt.runner?.close() }
        inFlight = null
        if (isDriver) {
            backingOff = true
            deps.schedule(backoff.nextDelayMillis()) { backingOff = false }
        }
    }

    /** After a revert-to-BLE (A.7) the upgrade backoff resets and evaluation resumes. */
    fun onRevertToBle() {
        inFlight?.timeout?.cancel()
        inFlight = null
        backingOff = false
        backoff.reset()
    }

    companion object {
        const val ATTEMPT_TIMEOUT_MS = 10_000L
    }
}
