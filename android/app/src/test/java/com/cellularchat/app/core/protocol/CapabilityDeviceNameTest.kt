package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.cbor.CborText
import org.junit.Assert.assertEquals
import org.junit.Test

/** §11 key 15 `deviceName`: round-trip, forward compatibility, 40-char cap. */
class CapabilityDeviceNameTest {

    @Test
    fun roundTrips() {
        val set = CapabilitySet(CapabilitySet.OS_ANDROID, "16", "2.0.0", deviceName = "Galaxy S24")
        assertEquals("Galaxy S24", CapabilitySet.fromCbor(set.toCbor()).deviceName)
    }

    @Test
    fun missingKeyDecodesAsEmpty() {
        // An older peer that never sends key 15 must still decode (§11).
        val map = CborMap(listOf(CborInt(1) to CborInt(CapabilitySet.OS_IOS.toLong())))
        assertEquals("", CapabilitySet.fromCbor(map).deviceName)
    }

    @Test
    fun oversizeNameIsCappedOnSendAndReceive() {
        val long = "가".repeat(100)
        assertEquals(
            40,
            (CapabilitySet(CapabilitySet.OS_IOS, "26", "2.0.0", deviceName = long).toCbor()[15L] as CborText)
                .value.length,
        )
        val map = CborMap(
            listOf(
                CborInt(1) to CborInt(CapabilitySet.OS_IOS.toLong()),
                CborInt(15) to CborText(long),
            ),
        )
        assertEquals(40, CapabilitySet.fromCbor(map).deviceName.length)
    }
}
