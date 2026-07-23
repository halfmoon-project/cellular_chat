package com.cellularchat.app.core.crypto

import java.nio.charset.StandardCharsets

/** Pair-specific key derivations (PROTOCOL_V2.md §2). All outputs are 32 bytes. */
object Derivations {
    const val ROLE_A: Byte = 0x41
    const val ROLE_B: Byte = 0x42

    fun pairingPsk(secret: ByteArray): ByteArray =
        Hashes.hmacSha256(secret, ascii("cellfind/v2 pairing psk"))

    fun pairRoot(pairingHandshakeHash: ByteArray, pairingPsk: ByteArray, staticA: ByteArray, staticB: ByteArray): ByteArray =
        Hashes.hkdf(
            salt = pairingHandshakeHash,
            ikm = pairingPsk,
            info = ascii("cellfind/v2 pair root") + staticA + staticB,
        )

    fun sessionPsk(pairRoot: ByteArray): ByteArray =
        Hashes.hmacSha256(pairRoot, ascii("cellfind/v2 session psk"))

    fun discoveryKey(pairRoot: ByteArray, role: Byte): ByteArray {
        val label = if (role == ROLE_A) "cellfind/v2 discovery A" else "cellfind/v2 discovery B"
        return Hashes.hmacSha256(pairRoot, ascii(label))
    }

    fun confirmMac(pairRoot: ByteArray, role: Byte): ByteArray =
        Hashes.hmacSha256(pairRoot, ascii("cellfind/v2 confirm") + role)

    fun fingerprint(pairId: ByteArray, staticA: ByteArray, staticB: ByteArray): ByteArray =
        Hashes.sha256(ascii("cellfind/v2 fingerprint") + pairId + staticA + staticB)

    /** Six-digit display code from the fingerprint (§2). */
    fun fingerprintDisplay(fingerprint: ByteArray): String {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (fingerprint[i].toLong() and 0xff)
        val mod = java.lang.Long.remainderUnsigned(value, 1_000_000L)
        return mod.toString().padStart(6, '0')
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(StandardCharsets.US_ASCII)
}
