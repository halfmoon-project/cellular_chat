package com.cellularchat.app.ranging

import com.cellularchat.app.core.cbor.CborBytes
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.cbor.cborMapOf
import com.cellularchat.app.core.protocol.CapabilitySet
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
        fun onProximity(band: ProximityBand)
        fun onRangingUnavailable(detail: String)
        fun onSignalLost()

        /** Emit an authenticated session message (apple_config/oob_data/…). */
        fun sendSessionMessage(msgType: Long, body: CborMap)

        /** Schedule [action] after [delayMillis]; injectable for tests. */
        fun scheduleRetry(delayMillis: Long, action: () -> Unit)
    }

    private var method: Int = RangingMethod.BLE_RSSI
    private var attemptId: Long = 0
    private var peerUuid: UUID? = null
    private var oobInitiator: Boolean = false
    private var stopped = false

    /** True while the RSSI band path is driving the UI (the fallback or the
     * selected method); cleared once a UWB attempt produces a fresh sample. */
    private var proximityActive = false

    /** Selects the method for this pair (identical result on both phones, §12). */
    fun select(local: CapabilitySet, peer: CapabilitySet): Int {
        method = RangingSelector.select(local, peer).method
        return method
    }

    /**
     * Begins ranging for the chosen method. [peerUuid] is this device's install
     * id of the peer (RangingDevice UUID); [oobInitiator] is the OOB role.
     */
    fun start(peerUuid: UUID, oobInitiator: Boolean) {
        this.peerUuid = peerUuid
        this.oobInitiator = oobInitiator
        stopped = false
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

    /** Routes an inbound ranging session message. */
    fun onSessionMessage(msgType: Long, body: CborMap) {
        when (msgType) {
            SessionMsgType.APPLE_SHAREABLE -> {
                val controller = rawUwb ?: return
                val uuid = peerUuid ?: return
                val data = (body[2L] as? CborBytes)?.value ?: return
                controller.startFromShareable(data, uuid)
            }
            SessionMsgType.OOB_DATA -> {
                val data = (body[2L] as? CborBytes)?.value ?: return
                androidOob?.onOobData(data)
            }
        }
    }

    /** Feeds one raw BLE RSSI reading (dBm); drives the UI only on the band path. */
    fun feedRssi(rssiDb: Int) {
        if (stopped) return
        val band = filter.update(rssiDb)
        if (proximityActive) output.onProximity(band)
    }

    private fun emitProximityIfAny() {
        val band = filter.current()
        if (proximityActive && band != ProximityBand.UNKNOWN) output.onProximity(band)
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
