package com.cellularchat.app.ranging

import android.Manifest
import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager
import android.ranging.RangingCapabilities
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.RangingSession
import android.ranging.oob.DeviceHandle
import android.ranging.oob.OobInitiatorRangingConfig
import android.ranging.oob.OobResponderRangingConfig
import android.ranging.oob.TransportHandle
import com.cellularchat.app.core.RangingErrorCodes
import java.util.UUID
import java.util.concurrent.Executor

/**
 * Android-to-Android precise ranging via RangingManager default OOB
 * (PROTOCOL_V2.md §12 `uwb_android_oob`, IMPLEMENTATION_PLAN.md §8). Uses
 * `RANGING_SESSION_OOB` with `RANGING_MODE_HIGH_ACCURACY_PREFERRED`; the OOB
 * bytes ride over the authenticated session as `oob_data` messages through a
 * [TransportHandle] bridge. The UI is driven only by the technology and nullable
 * measurements the platform actually reports.
 */
@TargetApi(36)
class AndroidOobController(
    private val context: Context,
    private val callbacks: RawUwbController.Callbacks,
    sendOobData: (ByteArray) -> Unit,
) {
    private val manager = context.getSystemService(RangingManager::class.java)
    private var availability = RangingCapabilities.NOT_SUPPORTED
    private var capabilitiesRegistered = false
    private var session: RangingSession? = null
    private var generation = 0
    private val bridge = OobTransportBridge(sendOobData)

    private val capabilitiesCallback = RangingManager.RangingCapabilitiesCallback { capabilities ->
        availability = capabilities.technologyAvailability[RangingManager.UWB]
            ?: RangingCapabilities.NOT_SUPPORTED
    }

    fun ensureCapabilities(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RANGING) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (capabilitiesRegistered) return true
        val rangingManager = manager ?: return false
        return runCatching {
            rangingManager.registerCapabilitiesCallback(context.mainExecutor, capabilitiesCallback)
            capabilitiesRegistered = true
            true
        }.getOrDefault(false)
    }

    /** Feeds an inbound `oob_data` payload to the platform ranging stack. */
    fun onOobData(bytes: ByteArray) = bridge.deliver(bytes)

    fun start(initiator: Boolean, peerUuid: UUID) {
        if (!ensureCapabilities()) {
            return callbacks.onError(RangingErrorCodes.UNSUPPORTED, "RANGING permission or service unavailable")
        }
        val preference = runCatching {
            val peerDevice = RangingDevice.Builder().setUuid(peerUuid).build()
            val handle = DeviceHandle.Builder(peerDevice, bridge).build()
            if (initiator) {
                val config = OobInitiatorRangingConfig.Builder()
                    .addDeviceHandle(handle)
                    .setRangingMode(OobInitiatorRangingConfig.RANGING_MODE_HIGH_ACCURACY_PREFERRED)
                    .build()
                RangingPreference.Builder(RangingPreference.DEVICE_ROLE_INITIATOR, config).build()
            } else {
                val config = OobResponderRangingConfig.Builder(handle).build()
                RangingPreference.Builder(RangingPreference.DEVICE_ROLE_RESPONDER, config).build()
            }
        }.getOrElse {
            return callbacks.onError(RangingErrorCodes.PLATFORM_ERROR, it.message ?: "failed to build OOB profile")
        }

        stop()
        generation += 1
        val current = generation
        val created = runCatching {
            manager?.createRangingSession(context.mainExecutor, sessionCallback(current))
        }.getOrNull()
        if (created == null) {
            callbacks.onError(RangingErrorCodes.PLATFORM_ERROR, "could not create OOB session")
            return
        }
        session = created
        runCatching { created.start(preference) }
            .onFailure { callbacks.onError(RangingErrorCodes.PLATFORM_ERROR, it.message ?: "OOB start failed") }
    }

    private fun sessionCallback(gen: Int) = object : RangingSession.Callback {
        override fun onOpened() = Unit
        override fun onOpenFailed(reason: Int) {
            if (isCurrent(gen)) callbacks.onError(RangingErrorCodes.PLATFORM_ERROR, "OOB open failed ($reason)")
        }

        override fun onStarted(peer: RangingDevice, technology: Int) = Unit

        override fun onStopped(peer: RangingDevice, technology: Int) {
            if (isCurrent(gen)) callbacks.onStopped()
        }

        override fun onClosed(reason: Int) {
            if (isCurrent(gen)) callbacks.onStopped()
        }

        override fun onResults(peer: RangingDevice, data: RangingData) {
            if (!isCurrent(gen)) return
            callbacks.onMeasurement(
                data.distance?.measurement,
                data.azimuth?.measurement,
                data.elevation?.measurement,
            )
        }
    }

    private fun isCurrent(gen: Int): Boolean = gen == generation && session != null

    fun stop() {
        val closing = session
        generation += 1
        session = null
        runCatching { closing?.stop() }
        runCatching { closing?.close() }
    }

    fun close() {
        stop()
        if (capabilitiesRegistered) {
            runCatching { manager?.unregisterCapabilitiesCallback(capabilitiesCallback) }
            capabilitiesRegistered = false
        }
    }
}

/**
 * Bridges the platform [TransportHandle] to our authenticated `oob_data`
 * session messages. Outbound `sendData` is forwarded to the session sink;
 * inbound `oob_data` is delivered through the registered receive callback.
 */
private class OobTransportBridge(
    private val sendOobData: (ByteArray) -> Unit,
) : TransportHandle {
    @Volatile
    private var executor: Executor? = null

    @Volatile
    private var receiver: TransportHandle.ReceiveCallback? = null

    override fun registerReceiveCallback(executor: Executor, callback: TransportHandle.ReceiveCallback) {
        this.executor = executor
        this.receiver = callback
    }

    override fun sendData(data: ByteArray) = sendOobData(data)

    override fun close() {
        receiver = null
        executor = null
    }

    fun deliver(data: ByteArray) {
        val callback = receiver ?: return
        val runOn = executor
        if (runOn != null) runOn.execute { callback.onReceiveData(data) } else callback.onReceiveData(data)
    }
}
