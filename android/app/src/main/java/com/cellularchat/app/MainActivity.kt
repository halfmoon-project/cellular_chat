package com.cellularchat.app

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cellularchat.app.files.FileTransferManager
import com.cellularchat.app.files.ReceivedFileProvider
import com.cellularchat.app.network.LocalPeerManager
import com.cellularchat.app.network.PeerInfo
import com.cellularchat.app.network.RoomIdentity
import com.cellularchat.app.protocol.Protocol
import com.cellularchat.app.ranging.Android16RangingController
import com.cellularchat.app.ranging.RangingBridge
import com.cellularchat.app.ranging.RangingReading
import com.cellularchat.app.ranging.RangingState
import com.cellularchat.app.ranging.UnsupportedRangingBridge
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONObject

class MainActivity : Activity(),
    LocalPeerManager.Listener,
    FileTransferManager.Listener,
    RangingBridge.Listener {

    private lateinit var loginScroll: ScrollView
    private lateinit var sessionPanel: LinearLayout
    private lateinit var displayNameInput: EditText
    private lateinit var connectionIdInput: EditText
    private lateinit var networkStatus: TextView
    private lateinit var directionView: DirectionView
    private lateinit var distanceText: TextView
    private lateinit var rangingStatus: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var shareLastFileButton: Button

    private var identity: RoomIdentity? = null
    @Volatile private var peerManager: LocalPeerManager? = null
    private var fileTransferManager: FileTransferManager? = null
    private var rangingBridge: RangingBridge? = null
    private var lastReceivedFile: File? = null
    private var pendingRangingPeerId: String? = null
    private var discoveryStatus = "연결 준비 중"
    private val connectedPeers = linkedMapOf<String, PeerInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        val preferences = getSharedPreferences("identity", MODE_PRIVATE)
        displayNameInput.setText(preferences.getString("display_name", ""))

        findViewById<Button>(R.id.connectButton).setOnClickListener { connect() }
        findViewById<Button>(R.id.sendButton).setOnClickListener { sendChat() }
        findViewById<Button>(R.id.attachButton).setOnClickListener { chooseFile() }
        findViewById<Button>(R.id.leaveButton).setOnClickListener { leaveSession() }
        findViewById<Button>(R.id.startRangingButton).setOnClickListener { startRanging() }
        findViewById<Button>(R.id.stopRangingButton).setOnClickListener {
            rangingBridge?.requestStop(null)
        }
        shareLastFileButton.setOnClickListener { shareLastReceivedFile() }
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChat()
                true
            } else {
                false
            }
        }
        connectionIdInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                connect()
                true
            } else {
                false
            }
        }
    }

    private fun bindViews() {
        loginScroll = findViewById(R.id.loginScroll)
        sessionPanel = findViewById(R.id.sessionPanel)
        displayNameInput = findViewById(R.id.displayNameInput)
        connectionIdInput = findViewById(R.id.connectionIdInput)
        networkStatus = findViewById(R.id.networkStatus)
        directionView = findViewById(R.id.directionView)
        distanceText = findViewById(R.id.distanceText)
        rangingStatus = findViewById(R.id.rangingStatus)
        chatScroll = findViewById(R.id.chatScroll)
        chatContainer = findViewById(R.id.chatContainer)
        messageInput = findViewById(R.id.messageInput)
        shareLastFileButton = findViewById(R.id.shareLastFileButton)
    }

    private fun connect() {
        if (peerManager != null) return
        val displayName = displayNameInput.text.toString().trim().take(40)
        if (displayName.isEmpty()) {
            displayNameInput.error = "표시 이름을 입력하세요."
            return
        }
        val normalizedId = runCatching {
            Protocol.normalizeConnectionId(connectionIdInput.text.toString())
        }.getOrElse {
            connectionIdInput.error = it.message
            return
        }
        val roomIdentity = RoomIdentity(
            displayName = displayName,
            normalizedId = normalizedId,
            roomHash = Protocol.roomHash(normalizedId),
            authKey = Protocol.authKey(normalizedId),
            deviceId = LocalPeerManager.persistentDeviceId(this),
        )

        val ranging = createRangingBridge()
        val files = FileTransferManager(this, { peerManager }, this)
        val localPeers = LocalPeerManager(
            context = this,
            identity = roomIdentity,
            handshakeCapabilities = { ranging.handshakeCapabilities() },
            listener = this,
        )
        identity = roomIdentity
        rangingBridge = ranging
        fileTransferManager = files
        peerManager = localPeers
        val started = runCatching { localPeers.start() }.isSuccess
        if (!started) {
            ranging.close()
            files.close()
            localPeers.stop()
            identity = null
            rangingBridge = null
            fileTransferManager = null
            peerManager = null
            Toast.makeText(this, "로컬 연결을 시작하지 못했습니다.", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences("identity", MODE_PRIVATE)
            .edit()
            .putString("display_name", displayName)
            .apply()
        connectionIdInput.setText("")
        loginScroll.visibility = View.GONE
        sessionPanel.visibility = View.VISIBLE
        addSystemMessage("같은 Wi‑Fi 또는 인터넷 없는 로컬 핫스팟에서 상대를 찾습니다.")
    }

    private fun createRangingBridge(): RangingBridge {
        val hasUwb = packageManager.hasSystemFeature("android.hardware.uwb")
        return if (Build.VERSION.SDK_INT >= 36 && hasUwb) {
            createAndroid16RangingBridge()
        } else {
            UnsupportedRangingBridge(
                this,
                "방향 찾기에는 Android 16과 UWB 지원 기기가 필요합니다.",
            )
        }
    }

    @TargetApi(36)
    private fun createAndroid16RangingBridge(): RangingBridge =
        Android16RangingController(this, this)

    private fun sendChat() {
        val localIdentity = identity ?: return
        val text = messageInput.text.toString()
        if (text.isBlank()) return
        if (text.toByteArray(StandardCharsets.UTF_8).size > Protocol.MAX_CHAT_TEXT_BYTES) {
            addSystemMessage("메시지는 UTF-8 기준 8,000바이트까지 보낼 수 있습니다.")
            return
        }
        if (connectedPeers.isEmpty()) {
            addSystemMessage("아직 연결된 상대가 없습니다.")
            return
        }
        val message = JSONObject()
            .put("v", Protocol.VERSION)
            .put("type", "chat")
            .put("messageId", UUID.randomUUID().toString().lowercase())
            .put("senderId", localIdentity.deviceId)
            .put("senderName", localIdentity.displayName)
            .put("timestamp", System.currentTimeMillis())
            .put("text", text)
        peerManager?.broadcast(message)
        addChatMessage(localIdentity.displayName, text, own = true)
        messageInput.setText("")
    }

    private fun chooseFile() {
        if (connectedPeers.isEmpty()) {
            addSystemMessage("파일을 보내려면 먼저 상대와 연결하세요.")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(intent, REQUEST_FILE)
    }

    @Deprecated("Activity result API is sufficient for this dependency-free sample")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        fileTransferManager?.offer(uri)
    }

    private fun startRanging() {
        val peer = connectedPeers.values.firstOrNull()
        if (peer == null) {
            onRangingReading(RangingReading(RangingState.FAILED, "먼저 상대 기기와 연결하세요."))
            return
        }
        if (rangingBridge !is UnsupportedRangingBridge &&
            Build.VERSION.SDK_INT >= 36 &&
            checkSelfPermission(Manifest.permission.RANGING) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingRangingPeerId = peer.deviceId
            requestPermissions(arrayOf(Manifest.permission.RANGING), REQUEST_RANGING_PERMISSION)
            return
        }
        rangingBridge?.requestStart(peer)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RANGING_PERMISSION) return
        val peerId = pendingRangingPeerId
        pendingRangingPeerId = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            connectedPeers[peerId]?.let { rangingBridge?.requestStart(it) }
        } else {
            onRangingReading(RangingReading(RangingState.FAILED, "방향 찾기 권한이 거부되었습니다."))
        }
    }

    override fun onStatus(message: String) {
        if (peerManager == null) return
        discoveryStatus = message
        updateNetworkStatus()
    }

    override fun onPeerConnected(peer: PeerInfo) {
        if (peerManager == null) return
        connectedPeers[peer.deviceId] = peer
        updateNetworkStatus()
        addSystemMessage("${peer.displayName}님과 연결되었습니다. (${peer.platform})")
        rangingBridge?.onPeerConnected(peer)
        rangingBridge?.capabilitiesMessage()?.let { peerManager?.sendTo(peer.deviceId, it) }
    }

    override fun onPeerDisconnected(peer: PeerInfo) {
        if (peerManager == null) return
        connectedPeers.remove(peer.deviceId)
        if (pendingRangingPeerId == peer.deviceId) pendingRangingPeerId = null
        fileTransferManager?.peerDisconnected(peer.deviceId)
        rangingBridge?.requestStop(peer, notifyRemote = false)
        updateNetworkStatus()
        addSystemMessage("${peer.displayName}님과 연결이 끊겼습니다.")
    }

    override fun onMessage(peer: PeerInfo, message: JSONObject) {
        if (peerManager == null) return
        if (fileTransferManager?.handle(peer, message) == true) return
        runOnUiThread { handleInteractiveMessage(peer, message) }
    }

    private fun handleInteractiveMessage(peer: PeerInfo, message: JSONObject) {
        if (rangingBridge?.handleMessage(peer, message) == true) return
        if (message.optString("type") != "chat") return
        val chat = runCatching {
            val messageId = message.getString("messageId").lowercase()
            require(UUID.fromString(messageId).toString() == messageId)
            require(message.getString("senderId") == peer.deviceId)
            message.getLong("timestamp")
            message.getString("text").also {
                require(
                    it.isNotEmpty() &&
                        it.toByteArray(StandardCharsets.UTF_8).size <= Protocol.MAX_CHAT_TEXT_BYTES,
                )
            }
        }.getOrElse {
            addSystemMessage("잘못된 채팅 메시지를 무시했습니다.")
            return
        }
        addChatMessage(peer.displayName, chat, own = false)
    }

    override fun onError(message: String) {
        if (peerManager == null) return
        discoveryStatus = message
        updateNetworkStatus()
        addSystemMessage(message)
    }

    override fun onFatalError(message: String) {
        if (peerManager == null) return
        leaveSession(message)
    }

    override fun sendRangingMessage(deviceId: String, message: JSONObject) {
        peerManager?.sendTo(deviceId, message)
    }

    override fun onRangingCapabilitiesChanged() {
        rangingBridge?.capabilitiesMessage()?.let { peerManager?.broadcast(it) }
    }

    override fun onRangingReading(reading: RangingReading) {
        runOnUiThread {
            directionView.setReading(reading)
            distanceText.text = reading.distanceMeters?.let { "%.2f m".format(it) } ?: "—"
            val measurements = buildList {
                reading.azimuthDegrees?.let { add("방위 %.1f°".format(it)) }
                reading.elevationDegrees?.let { add("고도 %.1f°".format(it)) }
            }
            rangingStatus.text = if (measurements.isEmpty()) {
                reading.message
            } else {
                "${reading.message} · ${measurements.joinToString(" · ")}"
            }
            directionView.contentDescription = when {
                reading.azimuthDegrees != null -> "상대 방향 %.1f도".format(reading.azimuthDegrees)
                else -> "방향 데이터 없음"
            }
        }
    }

    override fun onFileOffer(peer: PeerInfo, offer: FileTransferManager.IncomingOffer) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            AlertDialog.Builder(this)
                .setTitle("파일 받기")
                .setMessage(
                    "${peer.displayName}님이 ${offer.name} " +
                        "(${FileTransferManager.humanSize(offer.size)}) 파일을 보냅니다.",
                )
                .setNegativeButton("거절") { _, _ ->
                    fileTransferManager?.respond(peer, offer, accepted = false)
                }
                .setPositiveButton("받기") { _, _ ->
                    fileTransferManager?.respond(peer, offer, accepted = true)
                }
                .setOnCancelListener {
                    fileTransferManager?.respond(peer, offer, accepted = false)
                }
                .show()
        }
    }

    override fun onTransferEvent(message: String) {
        runOnUiThread { addSystemMessage(message) }
    }

    override fun onFileReceived(file: File) {
        runOnUiThread {
            lastReceivedFile = file
            shareLastFileButton.visibility = View.VISIBLE
        }
    }

    private fun shareLastReceivedFile() {
        val file = lastReceivedFile ?: return
        val uri: Uri = ReceivedFileProvider.uriFor(this, file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(contentResolver.getType(uri) ?: "application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newUri(contentResolver, file.name, uri)
        startActivity(Intent.createChooser(intent, "받은 파일 공유"))
    }

    private fun updateNetworkStatus() {
        val count = connectedPeers.size
        networkStatus.text = if (count == 0) discoveryStatus else "$discoveryStatus · 연결 ${count}명"
    }

    private fun addChatMessage(sender: String, text: String, own: Boolean) {
        val bubble = TextView(this).apply {
            this.text = "$sender\n$text"
            setTextColor(getColor(if (own) R.color.hm_action_primary_fg else R.color.hm_fg_default))
            textSize = 15f
            setPadding(
                dimen(R.dimen.hm_space_3),
                dimen(R.dimen.hm_space_2),
                dimen(R.dimen.hm_space_3),
                dimen(R.dimen.hm_space_2),
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
            background = roundedBackground(
                getColor(if (own) R.color.hm_action_primary_bg else R.color.hm_bg_muted),
            )
            setTextIsSelectable(true)
        }
        bubble.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = if (own) Gravity.END else Gravity.START
            setMargins(
                dimen(R.dimen.hm_space_3),
                dimen(R.dimen.hm_space_1),
                dimen(R.dimen.hm_space_3),
                dimen(R.dimen.hm_space_1),
            )
        }
        chatContainer.addView(bubble)
        scrollChatToBottom()
    }

    private fun addSystemMessage(message: String) {
        val label = TextView(this).apply {
            text = message
            setTextColor(getColor(R.color.hm_fg_muted))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(
                dimen(R.dimen.hm_space_3),
                dimen(R.dimen.hm_space_2),
                dimen(R.dimen.hm_space_3),
                dimen(R.dimen.hm_space_2),
            )
        }
        label.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        chatContainer.addView(label)
        scrollChatToBottom()
    }

    private fun scrollChatToBottom() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun roundedBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = resources.getDimension(R.dimen.hm_radius_xl)
    }

    private fun dimen(id: Int): Int = resources.getDimensionPixelSize(id)

    private fun leaveSession(reason: String? = null) {
        val ranging = rangingBridge
        val manager = peerManager
        val files = fileTransferManager
        ranging?.close()
        manager?.stop()
        files?.close()
        rangingBridge = null
        peerManager = null
        fileTransferManager = null
        identity = null
        pendingRangingPeerId = null
        connectedPeers.clear()
        discoveryStatus = "연결 준비 중"
        lastReceivedFile = null
        chatContainer.removeAllViews()
        messageInput.setText("")
        shareLastFileButton.visibility = View.GONE
        directionView.setReading(RangingReading(RangingState.UNSUPPORTED, "정밀 찾기 중지됨"))
        distanceText.text = "—"
        rangingStatus.text = "지원 여부 확인 중"
        sessionPanel.visibility = View.GONE
        loginScroll.visibility = View.VISIBLE
        if (reason != null) Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        rangingBridge?.close()
        peerManager?.stop()
        fileTransferManager?.close()
        rangingBridge = null
        fileTransferManager = null
        peerManager = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_FILE = 100
        private const val REQUEST_RANGING_PERMISSION = 101
    }
}
