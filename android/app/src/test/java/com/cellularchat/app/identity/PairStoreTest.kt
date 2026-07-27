package com.cellularchat.app.identity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pass-through cipher: exercises store logic without the Android Keystore. */
private class PlainCipher : MasterCipher {
    override fun seal(plaintext: ByteArray): ByteArray = plaintext.copyOf()
    override fun open(sealed: ByteArray): ByteArray = sealed.copyOf()
}

class PairStoreTest {
    private fun record(id: Byte) = PairRecord(
        pairId = ByteArray(16) { id },
        role = 1,
        localStaticPrivate = ByteArray(32) { 1 },
        peerStaticPublic = ByteArray(32) { 2 },
        pairRoot = ByteArray(32) { 3 },
        negotiatedVersion = 2,
        alias = "P$id",
        createdAt = 1,
    )

    @Test
    fun persistsAcrossReopen() {
        val file = File.createTempFile("pairs", ".enc").also { it.delete() }
        PairStore(file, PlainCipher()).upsert(record(1))
        // A fresh instance (simulating an app restart) loads the sealed blob.
        val reopened = PairStore(file, PlainCipher())
        assertEquals(1, reopened.all().size)
        assertNotNull(reopened.get(ByteArray(16) { 1 }))
        file.delete()
    }

    @Test
    fun revokeExcludesFromActiveButKeepsRecord() {
        val file = File.createTempFile("pairs", ".enc").also { it.delete() }
        val store = PairStore(file, PlainCipher())
        store.upsert(record(1))
        store.revoke(ByteArray(16) { 1 })
        assertTrue(store.active().isEmpty())
        assertEquals(1, store.all().size)
        assertTrue(store.get(ByteArray(16) { 1 })!!.revoked)
        file.delete()
    }

    @Test
    fun renamePersistsWithoutDuplicating() {
        val file = File.createTempFile("pairs", ".enc").also { it.delete() }
        PairStore(file, PlainCipher()).apply {
            upsert(record(1))
            // Pair Settings alias rename path: upsert a copy with the same pairId.
            upsert(get(ByteArray(16) { 1 })!!.copy(alias = "새이름"))
        }
        val reopened = PairStore(file, PlainCipher())
        assertEquals(1, reopened.all().size)
        assertEquals("새이름", reopened.get(ByteArray(16) { 1 })!!.alias)
        file.delete()
    }

    /** §11 deviceName adoption: fills in an unnamed pair, never overwrites a name
     * the user chose. */
    @Test
    fun adoptPeerDeviceNameOnlyReplacesTheDefaultAlias() {
        val file = File.createTempFile("pairs", ".enc").also { it.delete() }
        val pairId = ByteArray(16) { 1 }
        val store = PairStore(file, PlainCipher())
        store.upsert(record(1).copy(alias = PairRecord.DEFAULT_ALIAS))

        store.adoptPeerDeviceName(pairId, "   ") // blank: no-op
        assertEquals(PairRecord.DEFAULT_ALIAS, store.get(pairId)!!.alias)

        store.adoptPeerDeviceName(pairId, "상현의 iPhone")
        assertEquals("상현의 iPhone", store.get(pairId)!!.alias)

        // Already named (by the user or by an earlier adoption): never overwritten.
        store.adoptPeerDeviceName(pairId, "다른 이름")
        assertEquals("상현의 iPhone", store.get(pairId)!!.alias)

        assertEquals("상현의 iPhone", PairStore(file, PlainCipher()).get(pairId)!!.alias)
        file.delete()
    }

    @Test
    fun corruptBlobIsTreatedAsEmpty() {
        val file = File.createTempFile("pairs", ".enc")
        file.writeBytes(byteArrayOf(1, 2, 3)) // not a valid CBOR array
        val store = PairStore(file, PlainCipher())
        assertFalse(store.all().isNotEmpty())
        file.delete()
    }
}
