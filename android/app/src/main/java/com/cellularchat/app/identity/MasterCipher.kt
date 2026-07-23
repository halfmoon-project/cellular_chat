package com.cellularchat.app.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals/opens the pair database blob. Split behind an interface so the pure
 * serialization ([PairRecordCodec]) is testable without the Android Keystore.
 */
interface MasterCipher {
    /** Returns iv(12) || ciphertext || tag. */
    fun seal(plaintext: ByteArray): ByteArray

    /** Inverse of [seal]. */
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM sealing with a non-exportable key held in the Android Keystore
 * (PROTOCOL_V2.md §2 Android storage requirement). No key bytes ever leave the
 * TEE/StrongBox; only the wrapped blob touches app-private storage.
 */
class KeystoreMasterCipher(
    private val alias: String = "cellfind.pairdb.master",
) : MasterCipher {
    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) { "unexpected GCM IV length" }
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_LENGTH) { "sealed blob too short" }
        val iv = sealed.copyOfRange(0, IV_LENGTH)
        val ciphertext = sealed.copyOfRange(IV_LENGTH, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
