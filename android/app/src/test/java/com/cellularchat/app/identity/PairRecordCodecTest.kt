package com.cellularchat.app.identity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PairRecordCodecTest {
    private fun record(role: Int, revoked: Boolean) = PairRecord(
        pairId = ByteArray(16) { it.toByte() },
        role = role,
        localStaticPrivate = ByteArray(32) { (it + 1).toByte() },
        peerStaticPublic = ByteArray(32) { (it + 2).toByte() },
        pairRoot = ByteArray(32) { (it + 3).toByte() },
        negotiatedVersion = 2,
        alias = "친구",
        createdAt = 1_700_000_000,
        epoch = 5,
        revoked = revoked,
    )

    @Test
    fun roundTripPreservesEveryField() {
        val records = listOf(record(1, revoked = false), record(2, revoked = true))
        val decoded = PairRecordCodec.decode(PairRecordCodec.encode(records))
        assertEquals(2, decoded.size)
        for (i in records.indices) {
            val expected = records[i]
            val actual = decoded[i]
            assertArrayEquals(expected.pairId, actual.pairId)
            assertEquals(expected.role, actual.role)
            assertArrayEquals(expected.localStaticPrivate, actual.localStaticPrivate)
            assertArrayEquals(expected.peerStaticPublic, actual.peerStaticPublic)
            assertArrayEquals(expected.pairRoot, actual.pairRoot)
            assertEquals(expected.negotiatedVersion, actual.negotiatedVersion)
            assertEquals(expected.alias, actual.alias)
            assertEquals(expected.createdAt, actual.createdAt)
            assertEquals(expected.epoch, actual.epoch)
            assertEquals(expected.revoked, actual.revoked)
        }
    }

    @Test
    fun emptyListRoundTrips() {
        assertEquals(0, PairRecordCodec.decode(PairRecordCodec.encode(emptyList())).size)
    }
}
