package com.cellularchat.app.ranging

import com.cellularchat.app.core.cbor.CborBytes
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.cbor.cborMapOf
import com.cellularchat.app.core.protocol.CapabilitySet
import com.cellularchat.app.core.protocol.CapabilityTranscript
import com.cellularchat.app.core.protocol.RangingMethod
import com.cellularchat.app.core.protocol.RangingSelector
import com.cellularchat.app.core.protocol.SessionMsgType
import java.util.UUID

/**
 * Ranging selection + fallback ladder (PROTOCOL_V2.md §12,
 * IMPLEMENTATION_PLAN.md §8). Picks a method deterministically from both
 * CapabilitySets, drives the matching controller, and always keeps BLE RSSI as
 * the fallback. On UWB failure it degrades to proximity immediately and retries
 * UWB with bounded backoff — a ranging failure is never reported as a pairing
 * or authentication failure.
 *
 * The two UWB controllers are injected so this orchestration is exercised
 * without hardware; the RSSI path is fully pure.
 */
class RangingCoordinator(
    private val output: Output,
    private val rawUwb: RawUwbController? = null,
    private val androidOob: AndroidOobController? = null,
    private val filter: RssiProximityFilter = RssiProximityFilter(),
    private val backoff: BackoffSchedule = BackoffSchedule(),
) {
    interface Output {
        fun onDirection(measurement: Measurement)
        fun onDistance(measurement: Measurement)

        /** An RSSI-derived proximity band, with its advisory trend (Feature C). */
        fun onProximity(band: ProximityBand, trend: RssiTrend, confidence: TrendConfidence)
        fun onRangingUnavailable(detail: String)
        fun onSignalLost()

        /** The platform actually started ranging with [technology] (a
         * RangingManager technology constant) so the UI can show it (§8/§12). */
        fun onTechnology(technology: Int)

        /** Emit an authenticated session message (apple_config/oob_data/…). */
        fun sendSessionMessage(msgType: Long, body: CborMap)

        /** Schedule [action] after [delayMillis]; injectable for tests. */
        fun scheduleRetry(delayMillis: Long, action: () -> Unit)

        /**
         * A §14 capability-transcript violation observed in a ranging message
         * (Feature B.2.2–B.2.4): the session layer must disconnect with
         * `capabilityMismatch` and fail the logical session — never a fallback.
         */
        fun onCapabilityMismatch() {}
    }

    private var method: Int = RangingMethod.BLE_RSSI
    private var attemptId: Long = 0
    private var peerUuid: UUID? = null
    private var oobInitiator: Boolean = false
    private var stopped = false

    // The two CapabilitySets bound to this logical session (Feature B); the
    // mutually-supported-method predicate is computed from them.
    private var localCaps: CapabilitySet? = null
    private var peerCaps: CapabilitySet? = null
    // Offered method per attemptId, to catch an accept whose method diverges (B.2.3).
    private val offeredMethods = mutableMapOf<Long, Int>()

    /** True while the RSSI band path is driving the UI (the fallback or the
     * selected method); cleared once a UWB attempt produces a fresh sample. */
    private var proximityActive = false

    // Background-ranging gating (§8): non-UWB ranging is foreground-only, and
    // background UWB runs only when the local device reports support. When the
    // gate says stop, ranging is paused (controllers stopped) while the
    // authenticated session stays up; it resumes on return to the foreground.
    private var foreground = true
    private var backgroundRangingSupported = false
    private var paused = false

    /** Selects the method for this pair (identical result on both phones, §12). */
    fun select(local: CapabilitySet, peer: CapabilitySet): Int {
        localCaps = local
        peerCaps = peer
        method = RangingSelector.select(local, peer).method
        backgroundRangingSupported = local.backgroundRanging
        return method
    }

    /**
     * Reports the app/service foreground state (§8). Backgrounding pauses ranging
     * unless the gate permits it (background UWB with device support); returning
     * to the foreground resumes from a fresh attempt. The Noise session is never
     * torn down here — only ranging work is gated.
     */
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (stopped) return
        if (value) {
            if (paused) {
                paused = false
                filter.reset()
                backoff.reset()
                beginAttempt()
            }
        } else if (!BackgroundRangingGate.shouldRange(false, method, backgroundRangingSupported)) {
            pauseRanging()
        }
    }

    private fun pauseRanging() {
        if (paused) return
        paused = true
        proximityActive = false
        runCatching { rawUwb?.stop() }
        runCatching { androidOob?.stop() }
        filter.reset()
    }

    /**
     * Begins ranging for the chosen method. [peerUuid] is this device's install
     * id of the peer (RangingDevice UUID); [oobInitiator] is the OOB role.
     */
    fun start(peerUuid: UUID, oobInitiator: Boolean) {
        this.peerUuid = peerUuid
        this.oobInitiator = oobInitiator
        stopped = false
        paused = false
        backoff.reset()
        filter.reset()
        beginAttempt()
    }

    private fun beginAttempt() {
        if (stopped) return
        attemptId += 1
        when (method) {
            RangingMethod.UWB_APPLE_INTEROP -> startAppleInterop()
            RangingMethod.UWB_ANDROID_OOB -> startAndroidOob()
            // BLE_RSSI (or NI_PEER, impossible on Android) is the proximity path.
            else -> { proximityActive = true; emitProximityIfAny() }
        }
    }

    private fun startAppleInterop() {
        val controller = rawUwb ?: return useRssiFallback("이 기기는 UWB를 지원하지 않습니다.")
        val config = controller.buildAccessoryConfig() ?: return useRssiFallback("UWB를 사용할 수 없습니다.")
        output.sendSessionMessage(
            SessionMsgType.APPLE_CONFIG,
            cborMapOf(1L to CborInt(attemptId), 2L to CborBytes(config)),
        )
        // Awaiting apple_shareable; RSSI keeps the UI alive until UWB locks.
        emitProximityIfAny()
    }

    private fun startAndroidOob() {
        val controller = androidOob ?: return useRssiFallback("이 기기는 UWB를 지원하지 않습니다.")
        val uuid = peerUuid ?: return useRssiFallback("상대 식별자가 없습니다.")
        controller.start(oobInitiator, uuid)
        emitProximityIfAny()
    }

    /**
     * Routes an inbound ranging session message. Before adopting a negotiation
     * message it enforces the capability transcript (Feature B.2.2–B.2.4): a
     * method (or implied method) outside the mutually-supported set, or an accept
     * that diverges from its offer, raises `capabilityMismatch` up to the session
     * layer instead of being silently dropped.
     */
    fun onSessionMessage(msgType: Long, body: CborMap) {
        when (msgType) {
            SessionMsgType.RANGING_OFFER -> {
                val method = methodField(body) ?: return // no method: legacy no-op (§10 idempotency)
                if (!methodSupported(method)) return raiseCapabilityMismatch()
                (body[1L] as? CborInt)?.value?.let { offeredMethods[it] = method }
            }
            SessionMsgType.RANGING_ACCEPT -> {
                val method = methodField(body) ?: return
                if (!methodSupported(method)) return raiseCapabilityMismatch()
                val attempt = (body[1L] as? CborInt)?.value
                val offered = attempt?.let { offeredMethods[it] }
                if (offered != null && offered != method) return raiseCapabilityMismatch()
            }
            SessionMsgType.APPLE_CONFIG -> {
                // Implicit offer for uwb_apple_interop (B.2.4).
                if (!methodSupported(RangingMethod.UWB_APPLE_INTEROP)) return raiseCapabilityMismatch()
            }
            SessionMsgType.NI_TOKEN -> {
                // Implicit offer for ni_peer (B.2.4).
                if (!methodSupported(RangingMethod.NI_PEER)) return raiseCapabilityMismatch()
            }
            SessionMsgType.APPLE_SHAREABLE -> {
                val controller = rawUwb ?: return
                val uuid = peerUuid ?: return
                val data = (body[2L] as? CborBytes)?.value ?: return
                controller.startFromShareable(data, uuid)
            }
            SessionMsgType.OOB_DATA -> {
                // Implicit offer for uwb_android_oob (B.2.4): verify before adopting.
                if (!methodSupported(RangingMethod.UWB_ANDROID_OOB)) return raiseCapabilityMismatch()
                val data = (body[2L] as? CborBytes)?.value ?: return
                androidOob?.onOobData(data)
            }
        }
    }

    private fun methodField(body: CborMap): Int? = (body[2L] as? CborInt)?.value?.toInt()

    private fun raiseCapabilityMismatch() = output.onCapabilityMismatch()

    /** The mutually-supported-method predicate over both bound CapabilitySets (B.2.2). */
    private fun methodSupported(candidate: Int): Boolean {
        val local = localCaps ?: return true // pre-select: cannot evaluate; never false-positive
        val peer = peerCaps ?: return true
        return CapabilityTranscript.methodSupported(local, peer, candidate)
    }

    /** Feeds one raw BLE RSSI reading (dBm); drives the UI only on the band path. */
    fun feedRssi(rssiDb: Int) {
        if (stopped) return
        val band = filter.update(rssiDb)
        // RSSI is non-UWB, so it is foreground-only (§8). The advisory trend
        // (Feature C) accompanies every RSSI-derived proximity value.
        if (proximityActive && foreground) output.onProximity(band, filter.trend, filter.trendConfidence)
    }

    private fun emitProximityIfAny() {
        val band = filter.current()
        if (proximityActive && foreground && band != ProximityBand.UNKNOWN) {
            output.onProximity(band, filter.trend, filter.trendConfidence)
        }
    }

    private fun useRssiFallback(detail: String) {
        // Keep the authenticated link; degrade to proximity and retry UWB later.
        proximityActive = true
        output.onRangingUnavailable(detail)
        emitProximityIfAny()
        if (method != RangingMethod.BLE_RSSI) scheduleUwbRetry()
    }

    private fun scheduleUwbRetry() {
        if (stopped) return
        val delay = backoff.nextDelayMillis()
        output.scheduleRetry(delay) { if (!stopped) beginAttempt() }
    }

    /** Callbacks a UWB controller reports through. */
    val uwbCallbacks: RawUwbController.Callbacks = object : RawUwbController.Callbacks {
        override fun onStarted(technology: Int) {
            if (stopped) return
            // Surface the technology the platform ACTUALLY selected, not the
            // requested method (§8/§12).
            output.onTechnology(technology)
        }

        override fun onMeasurement(distanceMeters: Double?, azimuthDegrees: Double?, elevationDegrees: Double?) {
            if (stopped) return
            backoff.reset()
            when {
                azimuthDegrees != null -> {
                    proximityActive = false
                    output.onDirection(Measurement(method, distanceMeters, azimuthDegrees, elevationDegrees))
                }
                distanceMeters != null -> {
                    proximityActive = false
                    output.onDistance(Measurement(method, distanceMeters))
                }
                // Neither yet: hold with proximity so nothing stale is shown.
                else -> emitProximityIfAny()
            }
        }

        override fun onError(rangingErrorCode: Int, detail: String) {
            if (stopped) return
            useRssiFallback(detail)
        }

        override fun onStopped() {
            if (stopped) return
            // Feature C.8: clear the trend on signal loss so nothing stale shows.
            filter.reset()
            output.onSignalLost()
            scheduleUwbRetry()
        }
    }

    fun stop() {
        stopped = true
        runCatching { rawUwb?.stop() }
        runCatching { androidOob?.stop() }
        filter.reset()
    }

    fun close() {
        stop()
        runCatching { rawUwb?.close() }
        runCatching { androidOob?.close() }
    }
}
