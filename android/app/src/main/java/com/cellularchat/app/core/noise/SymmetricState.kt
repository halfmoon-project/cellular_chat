package com.cellularchat.app.core.noise

import com.cellularchat.app.core.crypto.Hashes
import java.nio.charset.StandardCharsets

/** Noise rev34 SymmetricState for SHA-256 (HASHLEN = 32). */
class SymmetricState(protocolName: String) {
    private var chainingKey: ByteArray
    private var hash: ByteArray
    val cipherState = CipherState()

    init {
        val name = protocolName.toByteArray(StandardCharsets.US_ASCII)
        hash = if (name.size <= 32) name.copyOf(32) else Hashes.sha256(name)
        chainingKey = hash.copyOf()
        cipherState.initializeKey(null)
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val (ck, temp) = hkdf2(chainingKey, inputKeyMaterial)
        chainingKey = ck
        cipherState.initializeKey(temp)
    }

    fun mixHash(data: ByteArray) {
        hash = Hashes.sha256(hash, data)
    }

    fun mixKeyAndHash(inputKeyMaterial: ByteArray) {
        val (ck, tempHash, tempKey) = hkdf3(chainingKey, inputKeyMaterial)
        chainingKey = ck
        mixHash(tempHash)
        cipherState.initializeKey(tempKey)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipherState.encryptWithAd(hash, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipherState.decryptWithAd(hash, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    fun handshakeHash(): ByteArray = hash.copyOf()

    fun split(): Pair<CipherState, CipherState> {
        val (t1, t2) = hkdf2(chainingKey, ByteArray(0))
        val c1 = CipherState().apply { initializeKey(t1) }
        val c2 = CipherState().apply { initializeKey(t2) }
        return c1 to c2
    }

    private fun hkdf2(ck: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = Hashes.hmacSha256(ck, ikm)
        val o1 = Hashes.hmacSha256(temp, byteArrayOf(0x01))
        val o2 = Hashes.hmacSha256(temp, o1 + 0x02.toByte())
        return o1 to o2
    }

    private fun hkdf3(ck: ByteArray, ikm: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val temp = Hashes.hmacSha256(ck, ikm)
        val o1 = Hashes.hmacSha256(temp, byteArrayOf(0x01))
        val o2 = Hashes.hmacSha256(temp, o1 + 0x02.toByte())
        val o3 = Hashes.hmacSha256(temp, o2 + 0x03.toByte())
        return Triple(o1, o2, o3)
    }
}
