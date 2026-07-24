package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.cbor.CborBool
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.cbor.cborMapOf

/**
 * Transport codes carried in `transport_upgrade`/`transport_ack` (PROTOCOL_V2.md
 * §8/§10, Feature A). Fixed on both platforms: aware=1, nearby=2, ble=3. Kept
 * next to [SessionMsgType]/[PeerTransport] so the code↔tag mapping is one source
 * of truth.
 */
object TransportCode {
    const val AWARE = 1L
    const val NEARBY = 2L
    const val BLE = 3L

    /** Prologue transport tag for a code, or null when the code is unknown. */
    fun tag(code: Long): String? = when (code) {
        AWARE -> "aware"
        NEARBY -> "nearby"
        BLE -> "ble"
        else -> null
    }

    /** Wire code for a transport tag, or null when the tag is unknown. */
    fun code(tag: String): Long? = when (tag) {
        "aware" -> AWARE
        "nearby" -> NEARBY
        "ble" -> BLE
        else -> null
    }
}

/** Decoded `transport_upgrade` body: `{1: transport, 2: attemptId}` (A.1). */
data class TransportUpgradeBody(val transport: Long, val attemptId: Long)

/** Decoded `transport_ack` body: `{1: transport, 2: attemptId, 3: accepted}` (A.1). */
data class TransportAckBody(val transport: Long, val attemptId: Long, val accepted: Boolean)

/**
 * Encodes/decodes the transport-upgrade control bodies. A missing field, a wrong
 * type, or non-canonical CBOR is a genuine §14 violation and throws
 * [ProtocolException]; a well-typed-but-undesired value is NOT rejected here (the
 * responder declines it with `accepted=false`).
 */
object TransportUpgradeCodec {
    fun encodeUpgrade(transport: Long, attemptId: Long): CborMap =
        cborMapOf(1L to CborInt(transport), 2L to CborInt(attemptId))

    fun encodeAck(transport: Long, attemptId: Long, accepted: Boolean): CborMap =
        cborMapOf(1L to CborInt(transport), 2L to CborInt(attemptId), 3L to CborBool(accepted))

    fun decodeUpgrade(body: CborMap): TransportUpgradeBody {
        val transport = uint(body[1L], "transport_upgrade transport")
        val attemptId = uint(body[2L], "transport_upgrade attemptId")
        return TransportUpgradeBody(transport, attemptId)
    }

    fun decodeAck(body: CborMap): TransportAckBody {
        val transport = uint(body[1L], "transport_ack transport")
        val attemptId = uint(body[2L], "transport_ack attemptId")
        val accepted = (body[3L] as? CborBool)?.value
            ?: throw ProtocolException("transport_ack accepted must be a bool")
        return TransportAckBody(transport, attemptId, accepted)
    }

    private fun uint(value: com.cellularchat.app.core.cbor.CborValue?, field: String): Long {
        val v = (value as? CborInt)?.value ?: throw ProtocolException("$field must be an integer")
        if (v < 0) throw ProtocolException("$field must be unsigned")
        return v
    }
}
