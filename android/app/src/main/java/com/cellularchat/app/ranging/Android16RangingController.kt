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
import android.util.Base64
import com.cellularchat.app.network.PeerInfo
import java.util.UUID
import org.json.JSONObject

/** API 36 platform RangingManager implementation for Apple NI accessory interoperability. */
@TargetApi(36)
class Android16RangingController(
    private val context: Context,
    private val listener: RangingBridge.Listener,
) : RangingBridge {
    private val manager = context.getSystemService(RangingManager::class.java)
    private var uwbCapabilities: UwbRangingCapabilities? = null
    private var availability = RangingCapabilities.NOT_SUPPORTED
    private var session: RangingSession? = null
    private var activePeer: PeerInfo? = null
    private var localAddress: UwbAddress? = null
    private var peerCapabilities: JSONObject? = null
    private var accessoryConfigSent = false
    private var capabilitiesRegistered = false
    private var pendingStart: PendingStart? = null
    private var sessionGeneration = 0

    private val capabilitiesCallback = RangingManager.RangingCapabilitiesCallback { capabilities ->
        uwbCapabilities = capabilities.uwbCapabilities
        availability = capabilities.technologyAvailability[RangingManager.UWB]
            ?: RangingCapabilities.NOT_SUPPORTED
        listener.onRangingCapabilitiesChanged()
        maybeSendAccessoryConfig()
        val pending = pendingStart
        pendingStart = null
        if (pending != null) beginNegotiation(pending.peer, pending.notifyRemote)
    }

    init {
        if (context.checkSelfPermission(Manifest.permission.RANGING) == PackageManager.PERMISSION_GRANTED) {
            ensureCapabilitiesRegistered()
        }
    }

    override fun handshakeCapabilities(): Set<String> = buildSet {
        add("chat")
        add("file")
        if (isUwbUsable()) {
            add("apple-accessory-ni")
            add("android-raw-uwb")
        }
    }

    override fun capabilitiesMessage(): JSONObject {
        val uwb = uwbCapabilities
        val usable = isUwbUsable()
        return JSONObject()
            .put("v", 1)
            .put("type", "ranging_capabilities")
            .put("applePeerNI", false)
            .put("appleAccessoryNI", usable)
            .put("androidRawUwb", usable)
            .put("distance", usable && uwb?.isDistanceMeasurementSupported == true)
            .put("direction", usable && uwb?.isAzimuthalAngleSupported == true)
    }

    override fun onPeerConnected(peer: PeerInfo) = Unit

    override fun handleMessage(peer: PeerInfo, message: JSONObject): Boolean {
        when (message.optString("type")) {
            "ranging_capabilities" -> {
                if (activePeer == null || activePeer?.deviceId == peer.deviceId) {
                    peerCapabilities = JSONObject(message.toString())
                }
                return true
            }
            "ranging_start" -> {
                beginNegotiation(peer, notifyRemote = false)
                return true
            }
            "ranging_stop" -> {
                if (activePeer?.deviceId == peer.deviceId ||
                    pendingStart?.peer?.deviceId == peer.deviceId
                ) {
                    requestStop(peer, notifyRemote = false)
                }
                return true
            }
            "ni_shareable_config" -> {
                if (activePeer?.deviceId != peer.deviceId) return true
                val raw = runCatching { Base64.decode(message.getString("data"), Base64.DEFAULT) }
                    .getOrElse {
                        fail("iPhone UWB 설정 디코딩 실패")
                        return true
                    }
                startRawRanging(peer, raw)
                return true
            }
            "ni_accessory_config", "ni_discovery_token" -> return true
        }
        return false
    }

    override fun requestStart(peer: PeerInfo) {
        beginNegotiation(peer, notifyRemote = true)
    }

    override fun requestStop(peer: PeerInfo?, notifyRemote: Boolean) {
        val activeId = activePeer?.deviceId
        val pendingId = pendingStart?.peer?.deviceId
        if (peer != null && peer.deviceId != activeId && peer.deviceId != pendingId) return
        val target = peer ?: activePeer ?: pendingStart?.peer
        if (notifyRemote && target != null) {
            listener.sendRangingMessage(target.deviceId, message("ranging_stop"))
        }
        pendingStart = null
        val closingSession = session
        sessionGeneration += 1
        session = null
        activePeer = null
        localAddress = null
        accessoryConfigSent = false
        peerCapabilities = null
        runCatching { closingSession?.stop() }
        runCatching { closingSession?.close() }
        listener.onRangingReading(RangingReading(RangingState.UNSUPPORTED, "정밀 찾기 중지됨"))
    }

    override fun close() {
        requestStop(activePeer, notifyRemote = false)
        if (capabilitiesRegistered) {
            runCatching { manager?.unregisterCapabilitiesCallback(capabilitiesCallback) }
            capabilitiesRegistered = false
        }
    }

    private fun beginNegotiation(peer: PeerInfo, notifyRemote: Boolean) {
        if (context.checkSelfPermission(Manifest.permission.RANGING) != PackageManager.PERMISSION_GRANTED) {
            if (!notifyRemote) listener.sendRangingMessage(peer.deviceId, message("ranging_stop"))
            fail("주변 기기 거리 측정 권한이 필요합니다.")
            return
        }
        if (!ensureCapabilitiesRegistered()) {
            if (!notifyRemote) listener.sendRangingMessage(peer.deviceId, message("ranging_stop"))
            return
        }
        if (uwbCapabilities == null) {
            pendingStart = PendingStart(peer, notifyRemote)
            listener.onRangingReading(RangingReading(RangingState.SEARCHING, "UWB 기능 확인 중"))
            return
        }
        if (!isUwbUsable()) {
            val reason = when (availability) {
                RangingCapabilities.DISABLED_USER -> "기기 설정에서 UWB가 꺼져 있습니다."
                RangingCapabilities.DISABLED_REGULATORY -> "현재 지역에서는 UWB를 사용할 수 없습니다."
                else -> "이 Android 기기는 UWB 정밀 찾기를 지원하지 않습니다."
            }
            if (!notifyRemote) listener.sendRangingMessage(peer.deviceId, message("ranging_stop"))
            listener.onRangingReading(RangingReading(RangingState.UNSUPPORTED, reason))
            return
        }
        if (peer.platform != "ios") {
            if (!notifyRemote) listener.sendRangingMessage(peer.deviceId, message("ranging_stop"))
            listener.onRangingReading(
                RangingReading(
                    RangingState.UNSUPPORTED,
                    "현재 정밀 방향 찾기는 iPhone–Android 조합에서 지원됩니다.",
                ),
            )
            return
        }
        if (activePeer?.deviceId != peer.deviceId) {
            requestStop(activePeer, notifyRemote = activePeer != null)
            activePeer = peer
            localAddress = UwbAddress.createRandomShortAddress()
            accessoryConfigSent = false
        }
        if (notifyRemote) listener.sendRangingMessage(peer.deviceId, message("ranging_start"))
        listener.onRangingReading(RangingReading(RangingState.SEARCHING, "iPhone과 UWB 설정 교환 중"))
        maybeSendAccessoryConfig()
    }

    private fun maybeSendAccessoryConfig() {
        val peer = activePeer ?: return
        val address = localAddress ?: return
        if (!isUwbUsable() || accessoryConfigSent) return
        val remoteSupportsAccessory = peerCapabilities?.optBoolean("appleAccessoryNI", false)
            ?: peer.handshakeCapabilities.contains("apple-accessory-ni")
        if (!remoteSupportsAccessory) {
            fail("상대 iPhone이 Accessory Nearby Interaction을 지원하지 않습니다.")
            return
        }
        val config = AppleNiProtocol.buildAccessoryConfigurationData(address.addressBytes)
        accessoryConfigSent = true
        listener.sendRangingMessage(
            peer.deviceId,
            message("ni_accessory_config")
                .put("data", Base64.encodeToString(config, Base64.NO_WRAP)),
        )
    }

    private fun startRawRanging(peer: PeerInfo, bytes: ByteArray) {
        val local = localAddress ?: run {
            fail("로컬 UWB 주소가 없습니다.")
            return
        }
        val parsed = runCatching { AppleNiProtocol.parseShareableConfiguration(bytes) }
            .getOrElse {
                fail("iPhone UWB 설정이 올바르지 않습니다: ${it.message}")
                return
            }
        if (parsed.slotsPerRound != AppleNiProtocol.REQUESTED_SLOTS_PER_ROUND ||
            parsed.slotDurationRstu != AppleNiProtocol.REQUESTED_SLOT_DURATION_RSTU ||
            parsed.rangingIntervalMs != AppleNiProtocol.REQUESTED_RANGING_INTERVAL_MS ||
            !parsed.hoppingEnabled
        ) {
            fail("iPhone이 Android와 호환되지 않는 UWB 타이밍을 선택했습니다.")
            return
        }
        val caps = uwbCapabilities ?: run {
            fail("UWB 기능 정보를 아직 받지 못했습니다.")
            return
        }
        if (UwbRangingParams.CONFIG_UNICAST_DS_TWR !in caps.supportedConfigIds ||
            parsed.channel !in caps.supportedChannels ||
            parsed.preambleIndex !in caps.supportedPreambleIndexes ||
            RawRangingDevice.UPDATE_RATE_NORMAL !in caps.supportedRangingUpdateRates ||
            UwbRangingParams.DURATION_2_MS !in caps.supportedSlotDurations ||
            DataNotificationConfig.NOTIFICATION_CONFIG_ENABLE !in
            caps.supportedNotificationConfigurations
        ) {
            fail("기기가 iPhone과 필요한 UWB 고정 프로필을 지원하지 않습니다.")
            return
        }

        val preference = runCatching {
            val peerDevice = RangingDevice.Builder().setUuid(UUID.fromString(peer.deviceId)).build()
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
            fail("UWB 프로필 생성 실패: ${it.message}")
            return
        }

        val oldSession = session
        sessionGeneration += 1
        val generation = sessionGeneration
        session = null
        runCatching { oldSession?.stop() }
        runCatching { oldSession?.close() }
        val created = runCatching {
            manager?.createRangingSession(context.mainExecutor, sessionCallback(generation))
        }.getOrElse {
            fail("Android가 UWB 세션을 만들지 못했습니다: ${it.message}")
            return
        }
        if (created == null) {
            fail("Android가 UWB 세션을 만들지 못했습니다.")
            return
        }
        session = created
        listener.onRangingReading(RangingReading(RangingState.SEARCHING, "UWB 신호를 찾는 중"))
        runCatching { created.start(preference) }
            .onFailure { fail("UWB 시작 실패: ${it.message}") }
    }

    private fun sessionCallback(generation: Int) = object : RangingSession.Callback {
        override fun onOpened() {
            if (!isCurrentSession(generation)) return
            listener.onRangingReading(RangingReading(RangingState.SEARCHING, "UWB 세션 열림 · 상대 신호 대기 중"))
        }

        override fun onOpenFailed(reason: Int) {
            if (isCurrentSession(generation)) fail("UWB 세션 열기 실패 (코드 $reason)")
        }

        override fun onStarted(peer: RangingDevice, technology: Int) {
            if (!isCurrentSession(generation)) return
            listener.onRangingReading(RangingReading(RangingState.SEARCHING, "UWB 연결됨 · 측정 중"))
        }

        override fun onStopped(peer: RangingDevice, technology: Int) {
            if (isCurrentSession(generation)) fail("UWB 측정이 중지되었습니다.")
        }

        override fun onClosed(reason: Int) {
            if (isCurrentSession(generation)) fail("UWB 세션 종료 (코드 $reason)")
        }

        override fun onResults(peer: RangingDevice, data: RangingData) {
            if (!isCurrentSession(generation)) return
            val distance = data.distance?.measurement
            val azimuth = data.azimuth?.measurement
            val elevation = data.elevation?.measurement
            val state = when {
                azimuth != null -> RangingState.DIRECTION_AVAILABLE
                distance != null -> RangingState.DISTANCE_ONLY
                else -> RangingState.SEARCHING
            }
            val message = when (state) {
                RangingState.DIRECTION_AVAILABLE -> "방향 사용 가능"
                RangingState.DISTANCE_ONLY -> "이 기기는 현재 거리만 제공합니다."
                else -> "UWB 측정값을 기다리는 중"
            }
            listener.onRangingReading(
                RangingReading(state, message, distance, azimuth, elevation),
            )
        }
    }

    private fun isCurrentSession(generation: Int): Boolean =
        generation == sessionGeneration && session != null && activePeer != null

    private fun isUwbUsable(): Boolean {
        val caps = uwbCapabilities ?: return false
        return availability == RangingCapabilities.ENABLED &&
            caps.isDistanceMeasurementSupported &&
            UwbRangingParams.CONFIG_UNICAST_DS_TWR in caps.supportedConfigIds
    }

    private fun ensureCapabilitiesRegistered(): Boolean {
        if (capabilitiesRegistered) return true
        val rangingManager = manager ?: run {
            fail("Android가 UWB 거리 측정 서비스를 제공하지 않습니다.")
            return false
        }
        return runCatching {
            rangingManager.registerCapabilitiesCallback(context.mainExecutor, capabilitiesCallback)
            capabilitiesRegistered = true
            true
        }.getOrElse {
            fail("UWB 기능 확인 실패: ${it.message}")
            false
        }
    }

    private fun fail(message: String) {
        val target = activePeer ?: pendingStart?.peer
        pendingStart = null
        val closingSession = session
        sessionGeneration += 1
        session = null
        activePeer = null
        localAddress = null
        accessoryConfigSent = false
        if (target != null) listener.sendRangingMessage(target.deviceId, message("ranging_stop"))
        runCatching { closingSession?.stop() }
        runCatching { closingSession?.close() }
        listener.onRangingReading(RangingReading(RangingState.FAILED, message))
    }

    private fun message(type: String): JSONObject =
        JSONObject().put("v", 1).put("type", type)

    private data class PendingStart(val peer: PeerInfo, val notifyRemote: Boolean)
}
