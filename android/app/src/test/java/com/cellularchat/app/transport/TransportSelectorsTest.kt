package com.cellularchat.app.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSelectorsTest {
    private fun key(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun crossPlatformAndroidIsAlwaysPeripheral() {
        // Local is Android (localIsIos=false), peer is iOS: iOS must be central.
        assertFalse(
            BleRoleSelector.localIsCentral(
                localStatic = key(0x00),
                peerStatic = key(0xff),
                localIsIos = false,
                peerIsIos = true,
            ),
        )
    }

    @Test
    fun crossPlatformIosIsAlwaysCentral() {
        assertTrue(
            BleRoleSelector.localIsCentral(
                localStatic = key(0xff),
                peerStatic = key(0x00),
                localIsIos = true,
                peerIsIos = false,
            ),
        )
    }

    @Test
    fun samePlatformSmallerKeyIsCentral() {
        assertTrue(
            BleRoleSelector.localIsCentral(key(0x01, 0x02), key(0x01, 0x03), false, false),
        )
        assertFalse(
            BleRoleSelector.localIsCentral(key(0x01, 0x03), key(0x01, 0x02), false, false),
        )
    }

    @Test
    fun tieBreakIsUnsignedAndByteWise() {
        // 0x80 must be treated as 128 (> 0x7f), not as a negative value.
        assertTrue(BleRoleSelector.localIsCentral(key(0x7f), key(0x80), false, false))
        assertFalse(BleRoleSelector.localIsCentral(key(0x80), key(0x7f), false, false))
    }

    @Test
    fun duplicateResolverKeepsSmallerInitiator() {
        assertTrue(DuplicateConnectionResolver.shouldKeep(key(0x01), key(0x02)))
        assertFalse(DuplicateConnectionResolver.shouldKeep(key(0x02), key(0x01)))
        // Ties keep the candidate.
        assertTrue(DuplicateConnectionResolver.shouldKeep(key(0x05), key(0x05)))
    }

    @Test
    fun duplicateGuardKeepsIncumbentForSamePairAndNewerSmallerInitiator() {
        // FindController wires shouldKeep(incumbent, newcomer). Same-pair
        // duplicates share the deterministic initiator static (a tie) → the
        // already-active incumbent is kept, the newcomer is closed with duplicate.
        assertTrue(DuplicateConnectionResolver.shouldKeep(key(0x0a, 0x0b), key(0x0a, 0x0b)))
        // A newcomer whose initiator sorts smaller wins over the incumbent.
        assertFalse(DuplicateConnectionResolver.shouldKeep(key(0x0a, 0x0c), key(0x0a, 0x0b)))
    }
}
