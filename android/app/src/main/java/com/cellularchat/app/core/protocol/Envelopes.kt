package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.cbor.Cbor
import com.cellularchat.app.core.cbor.CborBytes
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap

/** Pairing transport message types (PROTOCOL_V2.md §6). */
object PairingMsgType {
    const val PAIR_BIND = 64L
    const val PAIR_PROOF = 65L
    const val PAIR_COMPLETE = 66L
    const val PAIR_ABORT = 67L
}

/** Session transport message types (PROTOCOL_V2.md §8). */
object SessionMsgType {
    const val SESSION_READY = 1L
    const val PING = 2L
    const val PONG = 3L
    const val DISCONNECT = 4L
    const val CAPABILITIES = 5L
    const val TRANSPORT_UPGRADE = 6L
    const val TRANSPORT_ACK = 7L
    const val RANGING_OFFER = 16L
    const val RANGING_ACCEPT = 17L
    const val RANGING_START = 18L
    const val RANGING_STOP = 19L
    const val RANGING_ERROR = 20L
    const val APPLE_CONFIG = 21L
    const val APPLE_SHAREABLE = 22L
    const val NI_TOKEN = 23L
    const val OOB_DATA = 24L
    const val FIND_ACTIVE = 32L
    const val FIND_STOPPING = 33L
    const val FIND_EXPIRED = 34L

    val KNOWN = setOf(
        SESSION_READY, PING, PONG, DISCONNECT, CAPABILITIES, TRANSPORT_UPGRADE, TRANSPORT_ACK,
        RANGING_OFFER, RANGING_ACCEPT, RANGING_START, RANGING_STOP, RANGING_ERROR,
        APPLE_CONFIG, APPLE_SHAREABLE, NI_TOKEN, OOB_DATA, FIND_ACTIVE, FIND_STOPPING, FIND_EXPIRED,
    )
}

/** `pairingEnvelope = {1: msgType, 2: seq, 3: body}` (§6). */
data class PairingEnvelope(val msgType: Long, val seq: Long, val body: CborMap)

/** `sessionEnvelope = {1: msgType, 2: seq, 3: sid, 4: body}` (§8). */
data class SessionEnvelope(val msgType: Long, val seq: Long, val sid: ByteArray, val body: CborMap)

object PairingEnvelopeCodec {
    fun encode(envelope: PairingEnvelope): ByteArray = Cbor.encode(
        CborMap(
            listOf(
                CborInt(1) to CborInt(envelope.msgType),
                CborInt(2) to CborInt(envelope.seq),
                CborInt(3) to envelope.body,
            ),
        ),
    )

    fun decode(bytes: ByteArray): PairingEnvelope {
        val map = Cbor.decode(bytes) as? CborMap ?: throw ProtocolException("pairing envelope must be a map")
        requireKeys(map, setOf(1L, 2L, 3L), "pairing envelope")
        val msgType = uint(map[1L], "msgType")
        val seq = uint(map[2L], "seq")
        val body = map[3L] as? CborMap ?: throw ProtocolException("pairing envelope body must be a map")
        return PairingEnvelope(msgType, seq, body)
    }
}

object SessionEnvelopeCodec {
    fun encode(envelope: SessionEnvelope): ByteArray = Cbor.encode(
        CborMap(
            listOf(
                CborInt(1) to CborInt(envelope.msgType),
                CborInt(2) to CborInt(envelope.seq),
                CborInt(3) to CborBytes(envelope.sid),
                CborInt(4) to envelope.body,
            ),
        ),
    )

    fun decode(bytes: ByteArray): SessionEnvelope {
        val map = Cbor.decode(bytes) as? CborMap ?: throw ProtocolException("session envelope must be a map")
        requireKeys(map, setOf(1L, 2L, 3L, 4L), "session envelope")
        val msgType = uint(map[1L], "msgType")
        val seq = uint(map[2L], "seq")
        val sid = (map[3L] as? CborBytes)?.value ?: throw ProtocolException("session envelope sid must be bytes")
        if (sid.size != 16) throw ProtocolException("session envelope sid must be 16 bytes")
        val body = map[4L] as? CborMap ?: throw ProtocolException("session envelope body must be a map")
        return SessionEnvelope(msgType, seq, sid, body)
    }
}

private fun requireKeys(map: CborMap, allowed: Set<Long>, context: String) {
    val keys = map.entries.map {
        (it.first as? CborInt)?.value ?: throw ProtocolException("$context keys must be integers")
    }
    if (keys.toSet() != allowed) throw ProtocolException("$context has an unexpected key set")
}

private fun uint(value: com.cellularchat.app.core.cbor.CborValue?, field: String): Long {
    val v = (value as? CborInt)?.value ?: throw ProtocolException("$field must be an integer")
    if (v < 0) throw ProtocolException("$field must be unsigned")
    return v
}
