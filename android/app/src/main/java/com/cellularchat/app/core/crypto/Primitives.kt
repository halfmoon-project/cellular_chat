package com.cellularchat.app.core.crypto

import com.cellularchat.app.core.AeadException
import com.cellularchat.app.core.ProtocolException
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.XECPrivateKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** SHA-256, HMAC-SHA256 and RFC 5869 HKDF-SHA256. */
object Hashes {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // HMAC forbids an empty key; SymmetricState never mixes with one but be explicit.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    /** RFC 5869 HKDF-SHA256, output length [length]. Used for pairRoot (§2). */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }
}

/** X25519 raw-key operations built on the JDK "XDH" provider. */
object X25519 {
    private val PARAMS = NamedParameterSpec("X25519")
    private val BASE_POINT = BigInteger.valueOf(9)

    fun generatePrivate(random: SecureRandom = SecureRandom()): ByteArray {
        val raw = ByteArray(32)
        random.nextBytes(raw)
        return raw
    }

    fun privateKey(raw: ByteArray): XECPrivateKey {
        require(raw.size == 32) { "X25519 private key must be 32 bytes" }
        val spec = XECPrivateKeySpec(PARAMS, raw)
        return KeyFactory.getInstance("XDH").generatePrivate(spec) as XECPrivateKey
    }

    fun publicKey(raw: ByteArray): PublicKey {
        require(raw.size == 32) { "X25519 public key must be 32 bytes" }
        val u = raw.copyOf()
        u[31] = (u[31].toInt() and 0x7f).toByte()
        for (i in 0 until 16) {
            val t = u[i]; u[i] = u[31 - i]; u[31 - i] = t
        }
        val spec = XECPublicKeySpec(PARAMS, BigInteger(1, u))
        return KeyFactory.getInstance("XDH").generatePublic(spec)
    }

    /** Raw RFC 7748 public key for a raw private key (DH with the base point u=9). */
    fun derivePublic(privateRaw: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("XDH")
        agreement.init(privateKey(privateRaw))
        agreement.doPhase(KeyFactory.getInstance("XDH").generatePublic(XECPublicKeySpec(PARAMS, BASE_POINT)), true)
        return agreement.generateSecret()
    }

    fun dh(privateRaw: ByteArray, peerPublicRaw: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("XDH")
        agreement.init(privateKey(privateRaw))
        agreement.doPhase(publicKey(peerPublicRaw), true)
        return agreement.generateSecret()
    }
}

/** ChaCha20-Poly1305 (IETF, 12-byte nonce = 4 zero bytes || u64LE(counter)). */
object ChaChaPoly {
    private const val KEY_ALG = "ChaCha20"

    @Volatile
    private var transformation: String? = null

    private fun newCipher(): Cipher {
        transformation?.let { return Cipher.getInstance(it) }
        for (name in listOf("ChaCha20-Poly1305", "ChaCha20/Poly1305/NoPadding")) {
            try {
                val cipher = Cipher.getInstance(name)
                transformation = name
                return cipher
            } catch (_: GeneralSecurityException) {
                // Try the next spelling (host JDK vs Conscrypt).
            }
        }
        throw ProtocolException("ChaCha20-Poly1305 is unavailable")
    }

    fun nonce(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        var value = counter
        for (i in 0 until 8) {
            nonce[4 + i] = (value and 0xff).toByte()
            value = value ushr 8
        }
        return nonce
    }

    fun encrypt(key: ByteArray, counter: Long, ad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = newCipher()
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, KEY_ALG), IvParameterSpec(nonce(counter)))
        if (ad.isNotEmpty()) cipher.updateAAD(ad)
        return cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, counter: Long, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        return try {
            val cipher = newCipher()
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, KEY_ALG), IvParameterSpec(nonce(counter)))
            if (ad.isNotEmpty()) cipher.updateAAD(ad)
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw AeadException("AEAD authentication failed")
        }
    }
}
