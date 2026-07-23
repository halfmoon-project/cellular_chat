package com.cellularchat.app.core.cbor

/** Small sealed model of the canonical CBOR core profile (PROTOCOL_V2.md §3). */
sealed interface CborValue

/** Unsigned or negative integer. Stored as the signed value it denotes. */
data class CborInt(val value: Long) : CborValue

data class CborBytes(val value: ByteArray) : CborValue

data class CborText(val value: String) : CborValue

data class CborArray(val items: List<CborValue>) : CborValue

/** Map entries preserve decode order (which is canonical ascending key order). */
data class CborMap(val entries: List<Pair<CborValue, CborValue>>) : CborValue {
    /** Convenience lookup for the small-uint keys every protocol map uses. */
    operator fun get(key: Long): CborValue? =
        entries.firstOrNull { (it.first as? CborInt)?.value == key }?.second
}

data class CborBool(val value: Boolean) : CborValue

data object CborNull : CborValue

/** Builders for the integer-keyed maps used throughout the protocol. */
fun cborMapOf(vararg entries: Pair<Long, CborValue>): CborMap =
    CborMap(entries.map { CborInt(it.first) as CborValue to it.second })
