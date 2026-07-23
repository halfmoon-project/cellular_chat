package com.cellularchat.app.core.crypto

import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.cbor.Cbor
import com.cellularchat.app.core.cbor.CborArray
import com.cellularchat.app.core.cbor.CborBytes
import com.cellularchat.app.core.cbor.CborInt
import java.util.Base64

/** Invitation payload and text codec (PROTOCOL_V2.md §4). */
data class Invitation(
    val pairId: ByteArray,
    val secret: ByteArray,
    val createdAt: Long,
) {
    companion object {
        const val VERSION = 2L
        const val PREFIX = "CF2:"
        const val MAX_AGE_SECONDS = 900L
        const val MAX_FUTURE_SKEW_SECONDS = 120L

        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()

        fun encode(invitation: Invitation): String {
            require(invitation.pairId.size == 16) { "pairId must be 16 bytes" }
            require(invitation.secret.size == 32) { "secret must be 32 bytes" }
            val cbor = Cbor.encode(
                CborArray(
                    listOf(
                        CborInt(VERSION),
                        CborBytes(invitation.pairId),
                        CborBytes(invitation.secret),
                        CborInt(invitation.createdAt),
                    ),
                ),
            )
            return PREFIX + ENCODER.encodeToString(cbor)
        }

        /** Parses and validates an invitation against the injected [nowUnixSeconds]. */
        fun parse(text: String, nowUnixSeconds: Long): Invitation {
            if (!text.startsWith(PREFIX)) throw ProtocolException("invitation prefix must be CF2:")
            val cbor = try {
                DECODER.decode(text.substring(PREFIX.length))
            } catch (e: IllegalArgumentException) {
                throw ProtocolException("invitation is not valid base64url")
            }
            val array = Cbor.decode(cbor) as? CborArray
                ?: throw ProtocolException("invitation must be a CBOR array")
            if (array.items.size != 4) throw ProtocolException("invitation must have 4 fields")
            val version = (array.items[0] as? CborInt)?.value
                ?: throw ProtocolException("invitation version must be an integer")
            if (version != VERSION) throw ProtocolException("unsupported invitation version")
            val pairId = (array.items[1] as? CborBytes)?.value
                ?: throw ProtocolException("pairId must be a byte string")
            if (pairId.size != 16) throw ProtocolException("pairId must be 16 bytes")
            val secret = (array.items[2] as? CborBytes)?.value
                ?: throw ProtocolException("secret must be a byte string")
            if (secret.size != 32) throw ProtocolException("secret must be 32 bytes")
            val createdAt = (array.items[3] as? CborInt)?.value
                ?: throw ProtocolException("createdAt must be an integer")
            if (nowUnixSeconds - createdAt > MAX_AGE_SECONDS) throw ProtocolException("invitation is expired")
            if (createdAt - nowUnixSeconds > MAX_FUTURE_SKEW_SECONDS) throw ProtocolException("invitation createdAt is in the future")
            return Invitation(pairId, secret, createdAt)
        }
    }
}
