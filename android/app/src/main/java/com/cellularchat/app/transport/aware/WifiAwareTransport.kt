package com.cellularchat.app.transport.aware

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper
import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.core.protocol.StreamFraming
import com.cellularchat.app.transport.PeerTransport
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wi-Fi Aware (NAN) transport (IMPLEMENTATION_PLAN.md §8, PROTOCOL_V2.md §5/§8).
 * Fixed service name "cellfind"; a ConnectivityManager data path carries a TCP
 * socket framed with u32BE record framing from core. The publisher is the data-
 * path responder (Noise responder); the subscriber is the initiator.
 *
 * Availability is a strict runtime check (FEATURE_WIFI_AWARE +
 * WifiAwareManager.isAvailable). Resource exhaustion or availability loss closes
 * the link and lets the coordinator re-enter arbitration — it is never a pairing
 * error.
 */
class WifiAwareTransport(
    private val context: Context,
    private val publisher: Boolean,
    private val onLinkReady: () -> Unit,
) : PeerTransport {

    override val tag: String = "aware"

    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(WifiAwareManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var peerHandle: PeerHandle? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var listener: PeerTransport.Listener? = null
    private val closed = AtomicBoolean(false)

    override fun setListener(listener: PeerTransport.Listener) {
        this.listener = listener
    }

    fun isAvailable(): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) return false
        return manager?.isAvailable == true
    }

    fun start(): Boolean {
        if (!isAvailable()) return false
        return runCatching {
            manager!!.attach(attachCallback, handler)
            true
        }.getOrDefault(false)
    }

    private val attachCallback = object : AttachCallback() {
        override fun onAttached(session: WifiAwareSession) {
            if (closed.get()) {
                runCatching { session.close() }
                return
            }
            awareSession = session
            if (publisher) startPublish(session) else startSubscribe(session)
        }

        override fun onAttachFailed() {
            fail(ReasonCodes.RADIO_UNAVAILABLE)
        }
    }

    private fun startPublish(session: WifiAwareSession) {
        val config = PublishConfig.Builder().setServiceName(SERVICE_NAME).build()
        runCatching { session.publish(config, publishCallback, handler) }
            .onFailure { fail(ReasonCodes.RADIO_UNAVAILABLE) }
    }

    private fun startSubscribe(session: WifiAwareSession) {
        val config = SubscribeConfig.Builder().setServiceName(SERVICE_NAME).build()
        runCatching { session.subscribe(config, subscribeCallback, handler) }
            .onFailure { fail(ReasonCodes.RADIO_UNAVAILABLE) }
    }

    private val publishCallback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(session: PublishDiscoverySession) {
            publishSession = session
        }

        override fun onMessageReceived(handle: PeerHandle, message: ByteArray) {
            peerHandle = handle
            requestPublisherNetwork()
        }
    }

    private val subscribeCallback = object : DiscoverySessionCallback() {
        override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
            subscribeSession = session
        }

        override fun onServiceDiscovered(handle: PeerHandle, ssi: ByteArray?, matchFilter: MutableList<ByteArray>?) {
            peerHandle = handle
            // Nudge the publisher so it learns our handle, then set up the data path.
            runCatching { subscribeSession?.sendMessage(handle, 0, HELLO) }
            requestSubscriberNetwork(handle)
        }
    }

    private fun requestPublisherNetwork() {
        val session = publishSession ?: return
        val server = runCatching { ServerSocket(0) }.getOrNull() ?: return fail(ReasonCodes.TRANSPORT_LOST)
        serverSocket = server
        val specifier = WifiAwareNetworkSpecifier.Builder(session)
            .setPort(server.localPort)
            .build()
        requestNetwork(specifier, publisherSocketProvider = { server })
    }

    private fun requestSubscriberNetwork(handle: PeerHandle) {
        val session = subscribeSession ?: return
        val specifier = WifiAwareNetworkSpecifier.Builder(session, handle).build()
        requestNetwork(specifier, publisherSocketProvider = null)
    }

    private fun requestNetwork(
        specifier: WifiAwareNetworkSpecifier,
        publisherSocketProvider: (() -> ServerSocket)?,
    ) {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (publisherSocketProvider != null) acceptOn(publisherSocketProvider())
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (publisherSocketProvider != null) return
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                connectOn(network, info)
            }

            override fun onLost(network: Network) {
                fail(ReasonCodes.TRANSPORT_LOST)
            }
        }
        networkCallback = callback
        runCatching { connectivity?.requestNetwork(request, callback) }
            .onFailure { fail(ReasonCodes.RADIO_UNAVAILABLE) }
    }

    private fun acceptOn(server: ServerSocket) {
        Thread {
            val accepted = runCatching { server.accept() }.getOrNull()
            if (accepted == null) {
                fail(ReasonCodes.TRANSPORT_LOST)
                return@Thread
            }
            bindSocket(accepted)
        }.apply { isDaemon = true }.start()
    }

    private fun connectOn(network: Network, info: WifiAwareNetworkInfo) {
        if (socket != null) return
        Thread {
            val addr = info.peerIpv6Addr
            val port = info.port
            val connected = runCatching {
                network.socketFactory.createSocket(addr, port)
            }.getOrNull()
            if (connected == null) {
                fail(ReasonCodes.TRANSPORT_LOST)
                return@Thread
            }
            bindSocket(connected)
        }.apply { isDaemon = true }.start()
    }

    private fun bindSocket(newSocket: Socket) {
        if (closed.get()) {
            runCatching { newSocket.close() }
            return
        }
        socket = newSocket
        onLinkReady()
        startReader(newSocket)
    }

    private fun startReader(activeSocket: Socket) {
        Thread {
            val input = runCatching { activeSocket.getInputStream() }.getOrNull() ?: return@Thread
            while (!closed.get()) {
                val record = runCatching { StreamFraming.read(input) }.getOrElse {
                    if (!closed.get()) fail(ReasonCodes.TRANSPORT_LOST)
                    return@Thread
                }
                listener?.onRecord(record)
            }
        }.apply { isDaemon = true }.start()
    }

    override fun send(record: ByteArray) {
        val activeSocket = socket ?: return
        Thread {
            runCatching { StreamFraming.write(activeSocket.getOutputStream(), record) }
                .onFailure { if (!closed.get()) fail(ReasonCodes.TRANSPORT_LOST) }
        }.apply { isDaemon = true }.start()
    }

    private fun fail(reason: Int) {
        if (closed.get()) return
        listener?.onLinkLost(reason)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { networkCallback?.let { connectivity?.unregisterNetworkCallback(it) } }
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
        runCatching { publishSession?.close() }
        runCatching { subscribeSession?.close() }
        runCatching { awareSession?.close() }
    }

    companion object {
        const val SERVICE_NAME = "cellfind"
        private val HELLO = byteArrayOf('c'.code.toByte(), 'f'.code.toByte())
    }
}
