package com.cellularchat.app.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.cellularchat.app.network.LocalPeerManager
import com.cellularchat.app.network.PeerInfo
import com.cellularchat.app.protocol.Protocol
import com.cellularchat.app.protocol.Protocol.toHex
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class FileTransferManager(
    private val context: Context,
    private val peerManager: () -> LocalPeerManager?,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onFileOffer(peer: PeerInfo, offer: IncomingOffer)
        fun onTransferEvent(message: String)
        fun onFileReceived(file: File)
    }

    data class IncomingOffer(
        val transferId: String,
        val name: String,
        val size: Long,
        val sha256: String,
    )

    private data class OutgoingFile(
        val transferId: String,
        val uri: Uri,
        val name: String,
        val size: Long,
        val sha256: String,
        val recipients: ConcurrentHashMap<String, OutgoingState>,
    )

    private enum class OutgoingState { AWAITING, SENDING }

    private data class IncomingFile(
        val peerId: String,
        val transferId: String,
        val name: String,
        val expectedSize: Long,
        val expectedHash: String,
        val partFile: File,
        val finalFile: File,
        val output: OutputStream,
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
        var nextIndex: Int = 0,
        var receivedBytes: Long = 0,
    )

    private val outgoing = ConcurrentHashMap<String, OutgoingFile>()
    private val incoming = ConcurrentHashMap<String, IncomingFile>()
    private val pendingOffers = ConcurrentHashMap.newKeySet<String>()
    private val reservedFinalPaths = mutableSetOf<String>()
    private val outgoingSlotOccupied = AtomicBoolean(false)
    private val senderExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val receiverExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun offer(uri: Uri) {
        if (!outgoingSlotOccupied.compareAndSet(false, true)) {
            listener.onTransferEvent("먼저 제안한 파일 전송을 완료하세요.")
            return
        }
        senderExecutor.execute {
            runCatching {
                val displayName = queryName(uri)
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "파일을 열 수 없습니다." }
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        digest.update(buffer, 0, count)
                        size += count
                        require(size <= Protocol.MAX_FILE_BYTES) {
                            "파일은 100 MB까지 보낼 수 있습니다."
                        }
                    }
                }
                val manager = requireNotNull(peerManager()) { "연결된 상대가 없습니다." }
                val recipientIds = manager.peers().map { it.deviceId }.distinct()
                require(recipientIds.isNotEmpty()) { "연결된 상대가 없습니다." }
                val recipients = ConcurrentHashMap<String, OutgoingState>().apply {
                    recipientIds.forEach { put(it, OutgoingState.AWAITING) }
                }
                val transfer = OutgoingFile(
                    UUID.randomUUID().toString().lowercase(),
                    uri,
                    displayName,
                    size,
                    digest.digest().toHex(),
                    recipients,
                )
                outgoing[transfer.transferId] = transfer
                val message = message("file_offer")
                    .put("transferId", transfer.transferId)
                    .put("name", transfer.name)
                    .put("size", transfer.size)
                    .put("sha256", transfer.sha256)
                recipientIds.forEach { peerId ->
                    if (!manager.sendTo(peerId, message)) recipients.remove(peerId)
                }
                require(recipients.isNotEmpty()) { "연결된 상대가 없습니다." }
                listener.onTransferEvent("파일 제안 전송: ${transfer.name} (${humanSize(size)})")
            }.onFailure {
                outgoing.clear()
                outgoingSlotOccupied.set(false)
                listener.onTransferEvent("파일 준비 실패: ${it.message}")
            }
        }
    }

    fun handle(peer: PeerInfo, message: JSONObject): Boolean {
        when (message.optString("type")) {
            "file_offer" -> {
                val offer = runCatching {
                    IncomingOffer(
                        canonicalUuid(message.getString("transferId")),
                        sanitizeName(message.getString("name")),
                        message.getLong("size").also {
                            require(it in 0..Protocol.MAX_FILE_BYTES)
                        },
                        message.getString("sha256").lowercase().also {
                            require(it.matches(Regex("[0-9a-f]{64}")))
                        },
                    )
                }.getOrElse {
                    runCatching { canonicalUuid(message.optString("transferId")) }
                        .getOrNull()
                        ?.let { transferId ->
                            peerManager()?.sendTo(
                                peer.deviceId,
                                message("file_accept")
                                    .put("transferId", transferId)
                                    .put("accepted", false),
                            )
                        }
                    listener.onTransferEvent("잘못된 파일 제안을 거부했습니다.")
                    return true
                }
                if (!pendingOffers.add(key(peer.deviceId, offer.transferId))) return true
                listener.onFileOffer(peer, offer)
                return true
            }
            "file_accept" -> {
                val transferId = message.optString("transferId")
                val transfer = outgoing[transferId] ?: return true
                if (message.optBoolean("accepted", false)) {
                    if (transfer.recipients.replace(
                            peer.deviceId,
                            OutgoingState.AWAITING,
                            OutgoingState.SENDING,
                        )
                    ) {
                        sendFile(peer, transfer)
                    }
                } else if (transfer.recipients.remove(
                        peer.deviceId,
                        OutgoingState.AWAITING,
                    )
                ) {
                    listener.onTransferEvent("${peer.displayName}님이 파일을 받지 않았습니다.")
                    cleanupOutgoing(transfer)
                }
                return true
            }
            "file_chunk" -> {
                runReceiverTask { receiveChunk(peer, JSONObject(message.toString())) }
                return true
            }
            "file_complete" -> {
                runReceiverTask { completeReceive(peer, JSONObject(message.toString())) }
                return true
            }
        }
        return false
    }

    fun respond(peer: PeerInfo, offer: IncomingOffer, accepted: Boolean) {
        if (!pendingOffers.remove(key(peer.deviceId, offer.transferId))) return
        if (!accepted) {
            peerManager()?.sendTo(
                peer.deviceId,
                message("file_accept")
                    .put("transferId", offer.transferId)
                    .put("accepted", false),
            )
            return
        }
        receiverExecutor.execute {
            var reservedFile: File? = null
            var preparedState: IncomingFile? = null
            runCatching {
                val transferKey = key(peer.deviceId, offer.transferId)
                require(!incoming.containsKey(transferKey)) { "이미 수신 중인 파일입니다." }
                val directory = ReceivedFileProvider.receivedDirectory(context)
                require(offer.size <= directory.usableSpace) { "저장 공간이 부족합니다." }
                val finalFile = availableFile(directory, offer.name)
                check(reservedFinalPaths.add(finalFile.canonicalPath))
                reservedFile = finalFile
                val partFile = File(directory, ".${finalFile.name}.${offer.transferId}.part")
                val state = IncomingFile(
                    peer.deviceId,
                    offer.transferId,
                    offer.name,
                    offer.size,
                    offer.sha256,
                    partFile,
                    finalFile,
                    FileOutputStream(partFile),
                )
                val prior = incoming.putIfAbsent(transferKey, state)
                if (prior != null) {
                    state.output.close()
                    state.partFile.delete()
                    error("이미 수신 중인 파일입니다.")
                }
                preparedState = state
                check(
                    peerManager()?.sendTo(
                        peer.deviceId,
                        message("file_accept")
                            .put("transferId", offer.transferId)
                            .put("accepted", true),
                    ) == true,
                ) { "상대와 연결이 끊겼습니다." }
                listener.onTransferEvent("${peer.displayName}님에게서 ${offer.name} 받는 중")
            }.onFailure {
                preparedState?.let { state ->
                    incoming.remove(key(state.peerId, state.transferId), state)
                    runCatching { state.output.close() }
                    state.partFile.delete()
                }
                reservedFile?.let { reservedFinalPaths.remove(it.canonicalPath) }
                peerManager()?.sendTo(
                    peer.deviceId,
                    message("file_accept")
                        .put("transferId", offer.transferId)
                        .put("accepted", false),
                )
                listener.onTransferEvent("파일 수신 준비 실패: ${it.message}")
            }
        }
    }

    fun peerDisconnected(peerId: String) {
        receiverExecutor.execute {
            incoming.values
                .filter { it.peerId == peerId }
                .forEach { abortReceive(it, "연결이 끊겨 파일 수신을 중단했습니다.") }
            pendingOffers.removeIf { it.startsWith("$peerId:") }
            outgoing.values.forEach { transfer ->
                transfer.recipients.remove(peerId)
                cleanupOutgoing(transfer)
            }
        }
    }

    private fun sendFile(peer: PeerInfo, transfer: OutgoingFile) {
        senderExecutor.execute {
            try {
                runCatching {
                var index = 0
                context.contentResolver.openInputStream(transfer.uri).use { input ->
                    requireNotNull(input)
                    val buffer = ByteArray(Protocol.MAX_FILE_CHUNK_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        val data = Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP)
                        val sent = peerManager()?.sendToBlocking(
                            peer.deviceId,
                            message("file_chunk")
                                .put("transferId", transfer.transferId)
                                .put("index", index)
                                .put("data", data),
                        ) == true
                        check(sent) { "연결이 끊겼습니다." }
                        index += 1
                    }
                }
                check(
                    peerManager()?.sendToBlocking(
                        peer.deviceId,
                        message("file_complete")
                            .put("transferId", transfer.transferId)
                            .put("chunkCount", index),
                    ) == true,
                )
                    listener.onTransferEvent("${peer.displayName}님에게 ${transfer.name} 전송 완료")
                }.onFailure { listener.onTransferEvent("파일 전송 실패: ${it.message}") }
            } finally {
                transfer.recipients.remove(peer.deviceId, OutgoingState.SENDING)
                cleanupOutgoing(transfer)
            }
        }
    }

    private fun receiveChunk(peer: PeerInfo, message: JSONObject) {
        val transferId = message.optString("transferId")
        val state = incoming[key(peer.deviceId, transferId)] ?: return
        runCatching {
            val index = message.getInt("index")
            require(index == state.nextIndex) { "파일 청크 순서가 올바르지 않습니다." }
            val bytes = Base64.decode(message.getString("data"), Base64.DEFAULT)
            require(bytes.size <= Protocol.MAX_FILE_CHUNK_BYTES)
            require(state.receivedBytes + bytes.size <= state.expectedSize)
            state.output.write(bytes)
            state.digest.update(bytes)
            state.receivedBytes += bytes.size
            state.nextIndex += 1
        }.onFailure { abortReceive(state, "파일 수신 실패: ${it.message}") }
    }

    private fun completeReceive(peer: PeerInfo, message: JSONObject) {
        val transferId = message.optString("transferId")
        val state = incoming.remove(key(peer.deviceId, transferId)) ?: return
        runCatching {
            require(message.getInt("chunkCount") == state.nextIndex)
            state.output.close()
            require(state.receivedBytes == state.expectedSize) { "파일 크기가 일치하지 않습니다." }
            require(state.digest.digest().toHex() == state.expectedHash) { "파일 해시가 일치하지 않습니다." }
            require(state.partFile.renameTo(state.finalFile)) { "완성된 파일을 저장하지 못했습니다." }
            reservedFinalPaths.remove(state.finalFile.canonicalPath)
            listener.onTransferEvent("파일 저장 완료: ${state.finalFile.name}")
            listener.onFileReceived(state.finalFile)
        }.onFailure {
            runCatching { state.output.close() }
            state.partFile.delete()
            reservedFinalPaths.remove(state.finalFile.canonicalPath)
            listener.onTransferEvent("파일 검증 실패: ${it.message}")
        }
    }

    private fun abortReceive(state: IncomingFile, message: String) {
        incoming.remove(key(state.peerId, state.transferId), state)
        runCatching { state.output.close() }
        state.partFile.delete()
        reservedFinalPaths.remove(state.finalFile.canonicalPath)
        listener.onTransferEvent(message)
    }

    override fun close() {
        incoming.values.forEach { state ->
            runCatching { state.output.close() }
            state.partFile.delete()
        }
        incoming.clear()
        pendingOffers.clear()
        reservedFinalPaths.clear()
        outgoing.clear()
        outgoingSlotOccupied.set(false)
        senderExecutor.shutdownNow()
        receiverExecutor.shutdownNow()
    }

    private fun queryName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return sanitizeName(cursor.getString(0) ?: "file")
                }
            }
        return sanitizeName(uri.lastPathSegment ?: "file")
    }

    private fun sanitizeName(raw: String): String {
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ 가-힣-]"), "_")
            .trim(' ', '.')
            .take(120)
        return cleaned.ifEmpty { "file" }
    }

    private fun availableFile(directory: File, name: String): File {
        val initial = File(directory, name)
        if (!initial.exists() && initial.canonicalPath !in reservedFinalPaths) return initial
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = File(directory, "$stem ($index)$extension")
            if (!candidate.exists() && candidate.canonicalPath !in reservedFinalPaths) return candidate
            index += 1
        }
    }

    private fun canonicalUuid(value: String): String = UUID.fromString(value).toString().lowercase()
    private fun cleanupOutgoing(transfer: OutgoingFile) {
        if (transfer.recipients.isEmpty() && outgoing.remove(transfer.transferId, transfer)) {
            outgoingSlotOccupied.set(false)
        }
    }
    private fun runReceiverTask(block: () -> Unit) {
        runCatching { receiverExecutor.submit(block).get() }
            .onFailure { listener.onTransferEvent("파일 수신 처리 실패: ${it.message}") }
    }
    private fun key(peerId: String, transferId: String) = "$peerId:$transferId"
    private fun message(type: String) = JSONObject().put("v", 1).put("type", type)

    companion object {
        fun humanSize(bytes: Long): String = when {
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
            else -> "$bytes B"
        }
    }
}
