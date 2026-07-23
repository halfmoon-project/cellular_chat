package com.cellularchat.app.network

import android.content.Context
import android.annotation.TargetApi
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.cellularchat.app.protocol.FrameCodec
import com.cellularchat.app.protocol.Protocol
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

data class RoomIdentity(
    val displayName: String,
    val normalizedId: String,
    val roomHash: String,
    val authKey: ByteArray,
    val deviceId: String,
)

data class PeerInfo(
    val deviceId: String,
    val displayName: String,
    val platform: String,
    val handshakeCapabilities: Set<String>,
)

class LocalPeerManager(
    context: Context,
    private val identity: RoomIdentity,
    private val handshakeCapabilities: () -> Set<String>,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(message: String)
        fun onPeerConnected(peer: PeerInfo)
        fun onPeerDisconnected(peer: PeerInfo)
        fun onMessage(peer: PeerInfo, message: JSONObject)
        fun onError(message: String)
        fun onFatalError(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newCachedThreadPool()
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connections = ConcurrentHashMap<String, PeerConnection>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val pendingInboundHandshakes = AtomicInteger(0)

    private var serverSocket: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registered = false
    private var discovering = false
    private var registrationRequested = false
    private var discoveryRequested = false

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            if (stopped.get()) {
                runCatching { nsdManager.unregisterService(this) }
                return
            }
            registered = true
            status("공유 중 · ${serviceInfo.serviceName}")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            registrationRequested = false
            fatal("서비스 광고 실패 ($errorCode)")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            registered = false
            registrationRequested = false
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            error("서비스 광고 종료 실패 ($errorCode)")
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            if (stopped.get()) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
                return
            }
            discovering = true
            status("같은 네트워크에서 같은 ID를 찾는 중")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (stopped.get() ||
                serviceInfo.serviceType.trimEnd('.') != Protocol.SERVICE_TYPE.trimEnd('.')
            ) return
            val advertised = advertisedIdentity(serviceInfo)
            if (advertised != null) {
                if (advertised.roomPrefix != identity.roomHash.take(12)) return
                if (advertised.deviceId == identity.deviceId) return
                if (identity.deviceId <= advertised.deviceId) return
            } else if (!serviceInfo.serviceName.startsWith("cc1-${identity.roomHash.take(12)}-")) {
                return
            }
            resolveQueue.offer(serviceInfo)
            resolveNext()
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

        override fun onDiscoveryStopped(serviceType: String) {
            discovering = false
            discoveryRequested = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discovering = false
            discoveryRequested = false
            fatal("주변 검색 시작 실패 ($errorCode)")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            error("주변 검색 종료 실패 ($errorCode)")
        }
    }

    fun start() {
        if (stopped.get()) return
        try {
            val server = ServerSocket(0)
            server.reuseAddress = true
            serverSocket = server
            multicastLock = wifiManager?.createMulticastLock("cellchat-nsd")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            worker.execute { acceptLoop(server) }

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = Protocol.serviceName(identity.roomHash, identity.deviceId)
                serviceType = Protocol.SERVICE_TYPE
                port = server.localPort
                setAttribute("v", "1")
                setAttribute("room", identity.roomHash)
                setAttribute("device", identity.deviceId)
                setAttribute("platform", "android")
            }
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            registrationRequested = true
            nsdManager.discoverServices(
                Protocol.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
            discoveryRequested = true
        } catch (exception: Exception) {
            stop()
            throw exception
        }
    }

    fun peers(): List<PeerInfo> = connections.values.mapNotNull { it.peerInfo }

    fun sendTo(deviceId: String, message: JSONObject): Boolean {
        val connection = connections[deviceId] ?: return false
        return connection.sendAsync(message)
    }

    fun sendToBlocking(deviceId: String, message: JSONObject): Boolean {
        val connection = connections[deviceId] ?: return false
        return connection.sendBlocking(message)
    }

    fun broadcast(message: JSONObject) {
        connections.keys.forEach { sendTo(it, JSONObject(message.toString())) }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching {
            if (discoveryRequested || discovering) nsdManager.stopServiceDiscovery(discoveryListener)
        }
        runCatching {
            if (registrationRequested || registered) nsdManager.unregisterService(registrationListener)
        }
        discoveryRequested = false
        registrationRequested = false
        runCatching { serverSocket?.close() }
        connections.values.toList().forEach { it.close() }
        connections.clear()
        runCatching { multicastLock?.release() }
        worker.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!stopped.get()) {
            try {
                val socket = server.accept()
                configureSocket(socket)
                if (pendingInboundHandshakes.incrementAndGet() > MAX_PENDING_HANDSHAKES) {
                    pendingInboundHandshakes.decrementAndGet()
                    socket.close()
                    continue
                }
                try {
                    worker.execute {
                        PeerConnection(socket, false, null) {
                            pendingInboundHandshakes.decrementAndGet()
                        }.run()
                    }
                } catch (exception: RuntimeException) {
                    pendingInboundHandshakes.decrementAndGet()
                    socket.close()
                    throw exception
                }
            } catch (exception: Exception) {
                if (!stopped.get()) error("연결 수신 실패: ${exception.message}")
                return
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (stopped.get()) {
            resolveQueue.clear()
            resolving.set(false)
            return
        }
        if (!resolving.compareAndSet(false, true)) return
        val service = resolveQueue.poll()
        if (service == null) {
            resolving.set(false)
            return
        }
        try {
            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving.set(false)
                    resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolving.set(false)
                    if (!stopped.get()) handleResolved(serviceInfo)
                    resolveNext()
                }
            })
        } catch (_: RuntimeException) {
            resolving.set(false)
            resolveNext()
        }
    }

    private fun handleResolved(serviceInfo: NsdServiceInfo) {
        if (stopped.get()) return
        val fullRoom = textAttribute(serviceInfo, "room")
        if (fullRoom != null && !Protocol.isEqual(fullRoom, identity.roomHash)) return
        val advertised = advertisedIdentity(serviceInfo) ?: return
        if (advertised.deviceId == identity.deviceId || identity.deviceId <= advertised.deviceId) return
        if (connections.containsKey(advertised.deviceId)) return
        if (!connecting.add(advertised.deviceId)) return
        val host = resolvedHost(serviceInfo) ?: run {
            connecting.remove(advertised.deviceId)
            return
        }
        val socket = runCatching { socketFor(serviceInfo) }.getOrElse {
            connecting.remove(advertised.deviceId)
            return
        }
        try {
            worker.execute {
                try {
                socket.connect(InetSocketAddress(host, serviceInfo.port), 5_000)
                configureSocket(socket)
                PeerConnection(socket, true, advertised.deviceId).run()
                } catch (_: Exception) {
                    runCatching { socket.close() }
                    // Discovery may report stale endpoints; keep searching silently.
                } finally {
                    connecting.remove(advertised.deviceId)
                }
            }
        } catch (_: RuntimeException) {
            connecting.remove(advertised.deviceId)
            runCatching { socket.close() }
        }
    }

    private fun configureSocket(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = 15_000
    }

    private fun resolvedHost(serviceInfo: NsdServiceInfo) =
        if (Build.VERSION.SDK_INT >= 34) {
            resolvedHostApi34(serviceInfo)
        } else {
            @Suppress("DEPRECATION")
            serviceInfo.host
        }

    @TargetApi(34)
    private fun resolvedHostApi34(serviceInfo: NsdServiceInfo) =
        serviceInfo.hostAddresses.firstOrNull()

    private fun socketFor(serviceInfo: NsdServiceInfo): Socket =
        if (Build.VERSION.SDK_INT >= 33) socketForApi33(serviceInfo) else Socket()

    @TargetApi(33)
    private fun socketForApi33(serviceInfo: NsdServiceInfo): Socket =
        serviceInfo.network?.socketFactory?.createSocket() ?: Socket()

    private inner class PeerConnection(
        private val socket: Socket,
        private val outbound: Boolean,
        private val expectedRemoteId: String?,
        private val onHandshakeFinished: (() -> Unit)? = null,
    ) {
        private val closed = AtomicBoolean(false)
        private val input = socket.getInputStream()
        private val output = socket.getOutputStream()
        private val writeLock = Any()
        private val writer: ExecutorService = Executors.newSingleThreadExecutor()
        @Volatile var peerInfo: PeerInfo? = null

        fun run() {
            var handshakeReported = false
            try {
                val authenticatedPeer = if (outbound) clientHandshake() else serverHandshake()
                onHandshakeFinished?.invoke()
                handshakeReported = true
                peerInfo = authenticatedPeer
                val prior = connections.putIfAbsent(authenticatedPeer.deviceId, this)
                if (prior != null) throw IllegalStateException("duplicate connection")
                socket.soTimeout = 0
                mainHandler.post { listener.onPeerConnected(authenticatedPeer) }
                while (!closed.get()) {
                    val message = readJson()
                    listener.onMessage(authenticatedPeer, message)
                }
            } catch (_: Exception) {
                // Socket close, failed authentication and stale discovery all end here.
            } finally {
                if (!handshakeReported) onHandshakeFinished?.invoke()
                close()
            }
        }

        fun sendAsync(message: JSONObject): Boolean {
            if (closed.get()) return false
            val copy = JSONObject(message.toString())
            return runCatching {
                writer.execute {
                    runCatching { sendDirect(copy) }
                        .onFailure { close() }
                }
                true
            }.getOrElse {
                close()
                false
            }
        }

        fun sendBlocking(message: JSONObject): Boolean {
            if (closed.get()) return false
            val copy = JSONObject(message.toString())
            return runCatching {
                writer.submit<Boolean> {
                    sendDirect(copy)
                    true
                }.get(30, TimeUnit.SECONDS)
            }.getOrElse {
                close()
                false
            }
        }

        private fun sendDirect(message: JSONObject) {
            require(message.optInt("v", -1) == Protocol.VERSION)
            require(message.optString("type").isNotEmpty())
            synchronized(writeLock) {
                check(!closed.get()) { "connection closed" }
                FrameCodec.write(output, message.toString())
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { socket.close() }
            writer.shutdown()
            val peer = peerInfo ?: return
            if (connections.remove(peer.deviceId, this)) {
                mainHandler.post { listener.onPeerDisconnected(peer) }
            }
        }

        private fun clientHandshake(): PeerInfo {
            check(outbound)
            val clientNonce = Protocol.randomNonce()
            sendDirect(
                baseMessage("hello")
                    .put("deviceId", identity.deviceId)
                    .put("displayName", identity.displayName)
                    .put("platform", "android")
                    .put("roomHash", identity.roomHash)
                    .put("clientNonce", clientNonce)
                    .put("capabilities", JSONArray(handshakeCapabilities().toList().sorted())),
            )
            val challenge = readJson("challenge")
            val serverId = canonicalUuid(challenge.getString("deviceId"))
            check(serverId == expectedRemoteId)
            check(identity.deviceId > serverId)
            check(challenge.getString("clientNonce") == clientNonce)
            val serverNonce = validatedNonce(challenge.getString("serverNonce"))
            val proof = Protocol.proof(
                "client",
                identity.authKey,
                identity.deviceId,
                serverId,
                clientNonce,
                serverNonce,
            )
            sendDirect(baseMessage("auth").put("proof", proof))
            val authOk = readJson("auth_ok")
            val expectedProof = Protocol.proof(
                "server",
                identity.authKey,
                identity.deviceId,
                serverId,
                clientNonce,
                serverNonce,
            )
            check(Protocol.isEqual(authOk.getString("proof"), expectedProof))
            return peerFrom(challenge, serverId)
        }

        private fun serverHandshake(): PeerInfo {
            check(!outbound)
            val hello = readJson("hello")
            val clientId = canonicalUuid(hello.getString("deviceId"))
            check(identity.deviceId < clientId)
            check(Protocol.isEqual(hello.getString("roomHash"), identity.roomHash))
            val clientNonce = validatedNonce(hello.getString("clientNonce"))
            val serverNonce = Protocol.randomNonce()
            sendDirect(
                baseMessage("challenge")
                    .put("deviceId", identity.deviceId)
                    .put("displayName", identity.displayName)
                    .put("platform", "android")
                    .put("clientNonce", clientNonce)
                    .put("serverNonce", serverNonce)
                    .put("capabilities", JSONArray(handshakeCapabilities().toList().sorted())),
            )
            val auth = readJson("auth")
            val expectedProof = Protocol.proof(
                "client",
                identity.authKey,
                clientId,
                identity.deviceId,
                clientNonce,
                serverNonce,
            )
            check(Protocol.isEqual(auth.getString("proof"), expectedProof))
            val serverProof = Protocol.proof(
                "server",
                identity.authKey,
                clientId,
                identity.deviceId,
                clientNonce,
                serverNonce,
            )
            sendDirect(baseMessage("auth_ok").put("proof", serverProof))
            return peerFrom(hello, clientId)
        }

        private fun readJson(expectedType: String? = null): JSONObject {
            val message = JSONObject(FrameCodec.read(input))
            check(message.optInt("v", -1) == Protocol.VERSION)
            val type = message.optString("type")
            check(type.isNotEmpty())
            if (expectedType != null) check(type == expectedType)
            return message
        }
    }

    private data class AdvertisedIdentity(val roomPrefix: String, val deviceId: String)

    private fun advertisedIdentity(info: NsdServiceInfo): AdvertisedIdentity? {
        val room = textAttribute(info, "room")
        val device = textAttribute(info, "device")
        if (room != null && room.length == 64 && device != null) {
            return runCatching { AdvertisedIdentity(room.take(12), canonicalUuid(device)) }.getOrNull()
        }
        val match = SERVICE_NAME.matchEntire(info.serviceName.lowercase()) ?: return null
        val hex = match.groupValues[2]
        val uuid = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
        return AdvertisedIdentity(match.groupValues[1], canonicalUuid(uuid))
    }

    private fun peerFrom(message: JSONObject, deviceId: String): PeerInfo {
        val name = message.optString("displayName").trim().take(80).ifEmpty { "익명" }
        val platform = message.optString("platform").take(20)
        check(platform == "ios" || platform == "android")
        val capabilities = buildSet {
            val array = message.optJSONArray("capabilities") ?: JSONArray()
            for (index in 0 until array.length()) add(array.optString(index))
        }
        return PeerInfo(deviceId, name, platform, capabilities)
    }

    private fun validatedNonce(value: String): String {
        val decoded = java.util.Base64.getDecoder().decode(value)
        check(decoded.size == 16)
        return value
    }

    private fun canonicalUuid(value: String): String {
        val lower = value.lowercase()
        check(UUID.fromString(lower).toString() == lower)
        return lower
    }

    private fun textAttribute(info: NsdServiceInfo, key: String): String? =
        info.attributes[key]?.toString(StandardCharsets.UTF_8)

    private fun baseMessage(type: String): JSONObject =
        JSONObject().put("v", Protocol.VERSION).put("type", type)

    private fun status(message: String) {
        mainHandler.post { listener.onStatus(message) }
    }

    private fun error(message: String) {
        mainHandler.post { listener.onError(message) }
    }

    private fun fatal(message: String) {
        stop()
        mainHandler.post { listener.onFatalError(message) }
    }

    companion object {
        private const val MAX_PENDING_HANDSHAKES = 16
        private val SERVICE_NAME = Regex("^cc1-([0-9a-f]{12})-([0-9a-f]{32})$")

        fun persistentDeviceId(context: Context): String {
            val preferences = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
            val existing = preferences.getString("device_uuid", null)?.lowercase()
            val canonical = existing?.let {
                runCatching { UUID.fromString(it).toString().lowercase() }.getOrNull()
            }
            if (canonical != null) {
                if (canonical != existing) preferences.edit().putString("device_uuid", canonical).apply()
                return canonical
            }
            val created = UUID.randomUUID().toString().lowercase()
            preferences.edit().putString("device_uuid", created).apply()
            return created
        }
    }
}
