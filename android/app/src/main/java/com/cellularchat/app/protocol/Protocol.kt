package com.cellularchat.app.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Protocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_cellchat._tcp."
    const val MAX_FRAME_BYTES = 1_048_576
    const val MAX_FILE_CHUNK_BYTES = 49_152
    const val MAX_CHAT_TEXT_BYTES = 8_000
    const val MAX_FILE_BYTES = 100L * 1_024 * 1_024

    private val validConnectionId = Regex("[A-Z0-9-]{6,32}")

    fun normalizeConnectionId(raw: String): String {
        val normalized = raw.trim(' ').map { character ->
            if (character in 'a'..'z') character.uppercaseChar() else character
        }.joinToString("")
        require(validConnectionId.matches(normalized)) {
            "연결 ID는 영문, 숫자, 하이픈 6~32자여야 합니다."
        }
        return normalized
    }

    fun roomHash(normalizedId: String): String =
        sha256(normalizedId.toByteArray(StandardCharsets.UTF_8)).toHex()

    fun authKey(normalizedId: String): ByteArray = sha256(
        "cellchat-v1\u0000$normalizedId".toByteArray(StandardCharsets.UTF_8),
    )

    fun proof(
        role: String,
        authKey: ByteArray,
        clientDeviceId: String,
        serverDeviceId: String,
        clientNonce: String,
        serverNonce: String,
    ): String {
        require(role == "client" || role == "server")
        val transcript = listOf(
            role,
            "v1",
            clientDeviceId,
            serverDeviceId,
            clientNonce,
            serverNonce,
        ).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(authKey, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(transcript))
    }

    fun randomNonce(random: SecureRandom = SecureRandom()): String {
        val nonce = ByteArray(16)
        random.nextBytes(nonce)
        return Base64.getEncoder().encodeToString(nonce)
    }

    fun isEqual(left: String, right: String): Boolean = MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.UTF_8),
        right.toByteArray(StandardCharsets.UTF_8),
    )

    fun serviceName(roomHash: String, canonicalDeviceId: String): String =
        "cc1-${roomHash.take(12)}-${canonicalDeviceId.replace("-", "")}".lowercase()

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}

object FrameCodec {
    fun write(output: OutputStream, json: String) {
        val payload = json.toByteArray(StandardCharsets.UTF_8)
        require(payload.isNotEmpty()) { "Empty frames are forbidden" }
        require(payload.size <= Protocol.MAX_FRAME_BYTES) { "Frame is too large" }
        DataOutputStream(output).apply {
            writeInt(payload.size)
            write(payload)
            flush()
        }
    }

    @Throws(EOFException::class)
    fun read(input: InputStream): String {
        val stream = DataInputStream(input)
        val length = stream.readInt()
        require(length in 1..Protocol.MAX_FRAME_BYTES) { "Invalid frame length: $length" }
        val payload = ByteArray(length)
        stream.readFully(payload)
        return payload.toString(StandardCharsets.UTF_8)
    }
}
