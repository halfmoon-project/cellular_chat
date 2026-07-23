package com.cellularchat.app.identity

import com.cellularchat.app.core.cbor.Cbor
import com.cellularchat.app.core.cbor.CborArray
import com.cellularchat.app.core.cbor.CborBool
import com.cellularchat.app.core.cbor.CborBytes
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.cbor.CborText
import com.cellularchat.app.core.cbor.CborValue
import com.cellularchat.app.core.cbor.cborMapOf

/**
 * Deterministic-CBOR serialization for the persisted pair database. Pure and
 * unit-testable so the on-disk format is verified independently of the Keystore
 * cipher that seals it.
 */
object PairRecordCodec {
    fun encode(records: List<PairRecord>): ByteArray =
        Cbor.encode(CborArray(records.map { it.toCbor() }))

    fun decode(bytes: ByteArray): List<PairRecord> {
        val array = Cbor.decode(bytes) as? CborArray ?: return emptyList()
        return array.items.map { fromCbor(it as CborMap) }
    }

    private fun PairRecord.toCbor(): CborMap = cborMapOf(
        1L to CborBytes(pairId),
        2L to CborInt(role.toLong()),
        3L to CborBytes(localStaticPrivate),
        4L to CborBytes(peerStaticPublic),
        5L to CborBytes(pairRoot),
        6L to CborInt(negotiatedVersion.toLong()),
        7L to CborText(alias),
        8L to CborInt(createdAt),
        9L to CborInt(epoch),
        10L to CborBool(revoked),
    )

    private fun fromCbor(map: CborMap): PairRecord = PairRecord(
        pairId = bytes(map[1L]),
        role = int(map[2L]),
        localStaticPrivate = bytes(map[3L]),
        peerStaticPublic = bytes(map[4L]),
        pairRoot = bytes(map[5L]),
        negotiatedVersion = int(map[6L]),
        alias = (map[7L] as? CborText)?.value ?: "",
        createdAt = long(map[8L]),
        epoch = long(map[9L]),
        revoked = (map[10L] as? CborBool)?.value ?: false,
    )

    private fun bytes(v: CborValue?): ByteArray =
        (v as? CborBytes)?.value ?: error("expected byte string")

    private fun int(v: CborValue?): Int =
        ((v as? CborInt)?.value ?: error("expected integer")).toInt()

    private fun long(v: CborValue?): Long =
        (v as? CborInt)?.value ?: 0L
}
