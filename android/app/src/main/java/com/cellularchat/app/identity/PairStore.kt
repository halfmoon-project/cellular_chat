package com.cellularchat.app.identity

import java.io.File

/**
 * App-private, Keystore-sealed persistence of committed [PairRecord]s. Survives
 * ordinary restarts and reboots (IMPLEMENTATION_PLAN.md §2). Local revocation
 * flips [PairRecord.revoked]; a revoked record never authenticates a session.
 *
 * Not thread-confined by itself: callers mutate on the main thread.
 */
class PairStore(
    private val file: File,
    private val cipher: MasterCipher,
) {
    private val records: MutableMap<String, PairRecord> = linkedMapOf()
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        runCatching {
            val opened = cipher.open(file.readBytes())
            PairRecordCodec.decode(opened).forEach { records[it.pairIdHex()] = it }
        }
        // A corrupt or unopenable blob is treated as an empty store rather than a
        // crash; the user can re-pair. The security check is the pinned key, not
        // the store's readability.
    }

    fun all(): List<PairRecord> {
        ensureLoaded()
        return records.values.toList()
    }

    fun active(): List<PairRecord> = all().filter { !it.revoked }

    fun get(pairId: ByteArray): PairRecord? {
        ensureLoaded()
        return records[pairId.joinToString("") { "%02x".format(it) }]
    }

    fun upsert(record: PairRecord) {
        ensureLoaded()
        records[record.pairIdHex()] = record
        persist()
    }

    /** Local revocation: keeps the record but blocks future authentication. */
    fun revoke(pairId: ByteArray) {
        ensureLoaded()
        val key = pairId.joinToString("") { "%02x".format(it) }
        records[key]?.let { records[key] = it.copy(revoked = true) }
        persist()
    }

    fun remove(pairId: ByteArray) {
        ensureLoaded()
        records.remove(pairId.joinToString("") { "%02x".format(it) })
        persist()
    }

    private fun persist() {
        val sealed = cipher.seal(PairRecordCodec.encode(records.values.toList()))
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(file)) {
            file.writeBytes(sealed)
            tmp.delete()
        }
    }

    companion object {
        fun default(filesDir: File, cipher: MasterCipher = KeystoreMasterCipher()): PairStore =
            PairStore(File(filesDir, "pairs.cbor.enc"), cipher)
    }
}
