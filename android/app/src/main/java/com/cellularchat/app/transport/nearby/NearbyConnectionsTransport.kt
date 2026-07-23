package com.cellularchat.app.transport.nearby

import android.content.Context
import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.transport.PeerTransport
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

/**
 * Nearby Connections transport (IMPLEMENTATION_PLAN.md §8). P2P_POINT_TO_POINT
 * strategy; BYTES payloads carry exactly one record each (PROTOCOL_V2.md §5).
 * The advertising/discovery endpoint name carries NO static identity — it is the
 * rotating token hex (§7). Availability requires Google Play services; absence
 * simply falls through to BLE without any security change.
 */
class NearbyConnectionsTransport(
    private val context: Context,
    private val advertise: Boolean,
    private val endpointName: String, // rotating token hex, never a static id
    private val onLinkReady: () -> Unit,
) : PeerTransport {

    override val tag: String = "nearby"

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private var endpointId: String? = null
    private var listener: PeerTransport.Listener? = null
    private var connected = false

    override fun setListener(listener: PeerTransport.Listener) {
        this.listener = listener
    }

    fun isAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    fun start(): Boolean {
        if (!isAvailable()) return false
        return runCatching {
            if (advertise) startAdvertising() else startDiscovery()
            true
        }.getOrDefault(false)
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(endpointName, SERVICE_ID, lifecycleCallback, options)
            .addOnFailureListener { fail(ReasonCodes.RADIO_UNAVAILABLE) }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnFailureListener { fail(ReasonCodes.RADIO_UNAVAILABLE) }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            if (endpointId != null) return
            runCatching { client.stopDiscovery() }
            client.requestConnection(endpointName, id, lifecycleCallback)
                .addOnFailureListener { fail(ReasonCodes.TRANSPORT_LOST) }
        }

        override fun onEndpointLost(id: String) {
            if (id == endpointId) fail(ReasonCodes.TRANSPORT_LOST)
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            client.acceptConnection(id, payloadCallback)
                .addOnFailureListener { fail(ReasonCodes.TRANSPORT_LOST) }
        }

        override fun onConnectionResult(id: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                endpointId = id
                connected = true
                runCatching { client.stopAdvertising() }
                runCatching { client.stopDiscovery() }
                onLinkReady()
            } else {
                fail(ReasonCodes.TRANSPORT_LOST)
            }
        }

        override fun onDisconnected(id: String) {
            if (connected) fail(ReasonCodes.TRANSPORT_LOST)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            val record = payload.asBytes() ?: return
            runCatching { com.cellularchat.app.core.protocol.Records.validate(record) }
                .onSuccess { listener?.onRecord(record) }
                .onFailure { fail(ReasonCodes.PROTOCOL_ERROR) }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) = Unit
    }

    override fun send(record: ByteArray) {
        val id = endpointId ?: return
        runCatching { client.sendPayload(id, Payload.fromBytes(record)) }
    }

    private fun fail(reason: Int) {
        listener?.onLinkLost(reason)
    }

    override fun close() {
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { endpointId?.let { client.disconnectFromEndpoint(it) } }
        runCatching { client.stopAllEndpoints() }
        endpointId = null
        connected = false
    }

    companion object {
        private const val SERVICE_ID = "com.cellularchat.app.find"
        private val STRATEGY: Strategy = Strategy.P2P_POINT_TO_POINT
    }
}
