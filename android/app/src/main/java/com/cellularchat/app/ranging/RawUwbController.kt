package com.cellularchat.app.ranging

import android.Manifest
import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager
import android.ranging.DataNotificationConfig
import android.ranging.RangingCapabilities
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.RangingSession
import android.ranging.SessionConfig
import android.ranging.raw.RawInitiatorRangingConfig
import android.ranging.raw.RawRangingDevice
import android.ranging.uwb.UwbAddress
import android.ranging.uwb.UwbComplexChannel
import android.ranging.uwb.UwbRangingCapabilities
import android.ranging.uwb.UwbRangingParams
import com.cellularchat.app.core.RangingErrorCodes
import java.util.UUID

/**
 * Android side of the Apple UWB interop path (PROTOCOL_V2.md §12
 * `uwb_apple_interop`, UWB_INTEROP.md). Android is the raw-UWB
 * initiator/controller (`RANGING_SESSION_RAW`). Adapted from the prototype
 * Android16RangingController: the crown-jewel accessory/shareable profile logic
 * is preserved, but transport-layer JSON/PeerInfo coupling is removed — the
 * coordinator now supplies the accessory config to send and the shareable bytes
 * the iPhone returned over the authenticated session.
 *
 * Every RangingManager touch is capability- and permission-checked; missing
 * hardware degrades rather than crashes.
 */
@TargetApi(36)
class RawUwbController(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onMeasurement(distanceMeters: Double?, azimuthDegrees: Double?, elevationDegrees: Double?)
        fun onError(rangingErrorCode: Int, detail: String)
        fun onStopped()
    }

    private val manager = context.getSystemService(RangingManager::class.java)
    private var uwbCapabilities: UwbRangingCapabilities? = null
    private var availability = RangingCapabilities.NOT_SUPPORTED
    private var session: RangingSession? = null
    private var localAddress: UwbAddress? = null
    private var capabilitiesRegistered = false
    private var generation = 0

    private val capabilitiesCallback = RangingManager.RangingCapabilitiesCallback { capabilities ->
        uwbCapabilities = capabilities.uwbCapabilities
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

    fun isUwbUsable(): Boolean {
        val caps = uwbCapabilities ?: return false
        return availability == RangingCapabilities.ENABLED &&
            caps.isDistanceMeasurementSupported &&
            UwbRangingParams.CONFIG_UNICAST_DS_TWR in caps.supportedConfigIds
    }

    /** Builds the 48-byte accessory config to hand to the peer as `apple_config`. */
    fun buildAccessoryConfig(): ByteArray? {
        if (!ensureCapabilities() || !isUwbUsable()) return null
        val address = UwbAddress.createRandomShortAddress()
        localAddress = address
        return runCatching {
            AppleNiProtocol.buildAccessoryConfigurationData(address.addressBytes)
        }.getOrNull()
    }

    /** Starts raw ranging from the iPhone's 35-byte `apple_shareable` bytes. */
    fun startFromShareable(shareable: ByteArray, peerUuid: UUID) {
        val local = localAddress ?: return callbacks.onError(RangingErrorCodes.PLATFORM_ERROR, "no local UWB address")
        val parsed = runCatching { AppleNiProtocol.parseShareableConfiguration(shareable) }.getOrElse {
            return callbacks.onError(RangingErrorCodes.CONFIG_REJECTED, it.message ?: "bad shareable config")
        }
        if (parsed.slotsPerRound != AppleNiProtocol.REQUESTED_SLOTS_PER_ROUND ||
            parsed.slotDurationRstu != AppleNiProtocol.REQUESTED_SLOT_DURATION_RSTU ||
            parsed.rangingIntervalMs != AppleNiProtocol.REQUESTED_RANGING_INTERVAL_MS ||
            !parsed.hoppingEnabled
        ) {
            return callbacks.onError(RangingErrorCodes.CONFIG_REJECTED, "incompatible UWB timing")
        }
        val caps = uwbCapabilities
            ?: return callbacks.onError(RangingErrorCodes.UNSUPPORTED, "UWB capabilities unknown")
        if (UwbRangingParams.CONFIG_UNICAST_DS_TWR !in caps.supportedConfigIds ||
            parsed.channel !in caps.supportedChannels ||
            parsed.preambleIndex !in caps.supportedPreambleIndexes ||
            RawRangingDevice.UPDATE_RATE_NORMAL !in caps.supportedRangingUpdateRates ||
            UwbRangingParams.DURATION_2_MS !in caps.supportedSlotDurations ||
            DataNotificationConfig.NOTIFICATION_CONFIG_ENABLE !in caps.supportedNotificationConfigurations
        ) {
            return callbacks.onError(RangingErrorCodes.UNSUPPORTED, "device lacks the required UWB profile")
        }

        val preference = runCatching {
            val peerDevice = RangingDevice.Builder().setUuid(peerUuid).build()
            val uwbParams = UwbRangingParams.Builder(
                parsed.sessionId,
                UwbRangingParams.CONFIG_UNICAST_DS_TWR,
                local,
                UwbAddress.fromBytes(parsed.peerAddress),
            )
                .setComplexChannel(
                    UwbComplexChannel.Builder()
                        .setChannel(parsed.channel)
                        .setPreambleIndex(parsed.preambleIndex)
                        .build(),
                )
                .setRangingUpdateRate(RawRangingDevice.UPDATE_RATE_NORMAL)
                .setSessionKeyInfo(AppleNiProtocol.androidSessionKeyInfo(parsed.staticStsIv))
                .setSlotDuration(UwbRangingParams.DURATION_2_MS)
                .build()
            val rawDevice = RawRangingDevice.Builder()
                .setRangingDevice(peerDevice)
                .setUwbRangingParams(uwbParams)
                .build()
            RangingPreference.Builder(
                RangingPreference.DEVICE_ROLE_INITIATOR,
                RawInitiatorRangingConfig.Builder().addRawRangingDevice(rawDevice).build(),
            )
                .setSessionConfig(
                    SessionConfig.Builder()
                        .setAngleOfArrivalNeeded(caps.isAzimuthalAngleSupported)
                        .setDataNotificationConfig(
                            DataNotificationConfig.Builder()
                                .setNotificationConfigType(DataNotificationConfig.NOTIFICATION_CONFIG_ENABLE)
                                .build(),
                        )
                        .build(),
                )
                .build()
        }.getOrElse {
            return callbacks.onError(RangingErrorCodes.PLATFORM_ERROR, it.message ?: "failed to build UWB profile")
        }

        stop()
        generation += 1
        val current = generation
        val created = runCatching {
            manager?.createRangingSession(context.mainExecutor, sessionCallback(current))
        }.getOrNull()
        if (created == null) {
            callbacks.onError(RangingErrorCodes.PLATFORM_ERROR, "could not create UWB session")
            return
        }
        session = created
        runCatching { created.start(preference) }
            .onFailure { callbacks.onError(RangingErrorCodes.PLATFORM_ERROR, it.message ?: "UWB start failed") }
    }

    private fun sessionCallback(gen: Int) = object : RangingSession.Callback {
        override fun onOpened() = Unit
        override fun onOpenFailed(reason: Int) {
            if (isCurrent(gen)) callbacks.onError(RangingErrorCodes.PLATFORM_ERROR, "UWB open failed ($reason)")
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

    companion object {
        private val NO_CALLBACKS = object : Callbacks {
            override fun onMeasurement(distanceMeters: Double?, azimuthDegrees: Double?, elevationDegrees: Double?) = Unit
            override fun onError(rangingErrorCode: Int, detail: String) = Unit
            override fun onStopped() = Unit
        }

        // The device's raw-UWB profile is a stable, process-level fact. A single
        // probe controller registers the RangingCapabilities callback once (no
        // per-call leak) and its async result populates before Find reaches
        // session_ready.
        @Volatile
        private var probe: RawUwbController? = null

        /**
         * True only when the raw-UWB stack reports support for the Apple interop
         * profile (§11 `appleInteropUwb`) — the same [isUwbUsable] gate, never a
         * bare `FEATURE_UWB` presence check. Conservative (false) until the
         * capabilities callback has fired, so it never over-claims.
         */
        @TargetApi(36)
        fun interopProfileSupported(context: Context): Boolean {
            val controller = probe ?: synchronized(this) {
                probe ?: RawUwbController(context.applicationContext, NO_CALLBACKS).also { probe = it }
            }
            controller.ensureCapabilities()
            return controller.isUwbUsable()
        }
    }
}
