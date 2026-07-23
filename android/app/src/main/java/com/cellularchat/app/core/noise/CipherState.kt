package com.cellularchat.app.core.noise

import com.cellularchat.app.core.crypto.ChaChaPoly

/** Noise rev34 CipherState for ChaCha20-Poly1305. */
class CipherState {
    private var key: ByteArray? = null
    var nonce: Long = 0
        private set

    fun initializeKey(k: ByteArray?) {
        key = k
        nonce = 0
    }

    fun hasKey(): Boolean = key != null

    /** Exposes the raw key so drivers can surface split traffic keys to tests. */
    internal fun keyBytes(): ByteArray? = key?.copyOf()

    fun setNonce(n: Long) {
        nonce = n
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext
        val ciphertext = ChaChaPoly.encrypt(k, nonce, ad, plaintext)
        nonce++
        return ciphertext
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext
        val plaintext = ChaChaPoly.decrypt(k, nonce, ad, ciphertext)
        nonce++
        return plaintext
    }
}
