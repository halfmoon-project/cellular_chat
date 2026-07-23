package com.cellularchat.app.identity

import com.cellularchat.app.core.crypto.Derivations
import com.cellularchat.app.core.crypto.X25519

/**
 * A committed pairing (PROTOCOL_V2.md §2, IMPLEMENTATION_PLAN.md §5). All key
 * material is pair-specific and is persisted only through [PairStore], which
 * wraps this record with an Android Keystore AES-GCM key — never in plain
 * SharedPreferences.
 */
data class PairRecord(
    val pairId: ByteArray,             // 16 bytes
    val role: Int,                     // 1 = A (inviter), 2 = B (joiner)
    val localStaticPrivate: ByteArray, // 32-byte X25519 private key
    val peerStaticPublic: ByteArray,   // 32-byte pinned peer static public key
    val pairRoot: ByteArray,           // 32 bytes
    val negotiatedVersion: Int,
    val alias: String,
    val createdAt: Long,               // unix seconds
    val epoch: Long = 0,
    val revoked: Boolean = false,
) {
    val roleByte: Byte get() = if (role == 1) Derivations.ROLE_A else Derivations.ROLE_B
    val peerRoleByte: Byte get() = if (role == 1) Derivations.ROLE_B else Derivations.ROLE_A

    /** This device's own static public key, derived from the stored private key. */
    fun localStaticPublic(): ByteArray = X25519.derivePublic(localStaticPrivate)

    fun pairIdHex(): String = pairId.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean =
        other is PairRecord && other.pairId.contentEquals(pairId)

    override fun hashCode(): Int = pairId.contentHashCode()
}
