package com.cellularchat.app.ranging

import com.cellularchat.app.network.PeerInfo
import org.json.JSONObject

enum class RangingState {
    UNSUPPORTED,
    DISTANCE_ONLY,
    DIRECTION_AVAILABLE,
    SEARCHING,
    FAILED,
}

data class RangingReading(
    val state: RangingState,
    val message: String,
    val distanceMeters: Double? = null,
    val azimuthDegrees: Double? = null,
    val elevationDegrees: Double? = null,
)

interface RangingBridge : AutoCloseable {
    fun handshakeCapabilities(): Set<String>
    fun capabilitiesMessage(): JSONObject
    fun onPeerConnected(peer: PeerInfo)
    fun handleMessage(peer: PeerInfo, message: JSONObject): Boolean
    fun requestStart(peer: PeerInfo)
    fun requestStop(peer: PeerInfo?, notifyRemote: Boolean = true)
    override fun close()

    interface Listener {
        fun sendRangingMessage(deviceId: String, message: JSONObject)
        fun onRangingCapabilitiesChanged()
        fun onRangingReading(reading: RangingReading)
    }
}

class UnsupportedRangingBridge(
    private val listener: RangingBridge.Listener,
    private val reason: String,
) : RangingBridge {
    override fun handshakeCapabilities(): Set<String> = setOf("chat", "file")

    override fun capabilitiesMessage(): JSONObject = JSONObject()
        .put("v", 1)
        .put("type", "ranging_capabilities")
        .put("applePeerNI", false)
        .put("appleAccessoryNI", false)
        .put("androidRawUwb", false)
        .put("distance", false)
        .put("direction", false)

    override fun onPeerConnected(peer: PeerInfo) = Unit

    override fun handleMessage(peer: PeerInfo, message: JSONObject): Boolean {
        val type = message.optString("type")
        if (type == "ranging_start") {
            listener.sendRangingMessage(
                peer.deviceId,
                JSONObject().put("v", 1).put("type", "ranging_stop"),
            )
            listener.onRangingReading(RangingReading(RangingState.UNSUPPORTED, reason))
        }
        return type in RANGING_MESSAGES
    }

    override fun requestStart(peer: PeerInfo) {
        listener.onRangingReading(RangingReading(RangingState.UNSUPPORTED, reason))
    }

    override fun requestStop(peer: PeerInfo?, notifyRemote: Boolean) = Unit
    override fun close() = Unit

    companion object {
        val RANGING_MESSAGES = setOf(
            "ranging_capabilities",
            "ranging_start",
            "ranging_stop",
            "ni_discovery_token",
            "ni_accessory_config",
            "ni_shareable_config",
        )
    }
}
