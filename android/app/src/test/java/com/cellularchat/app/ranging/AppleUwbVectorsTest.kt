package com.cellularchat.app.ranging

import com.cellularchat.app.core.Vectors
import com.cellularchat.app.core.hex
import com.cellularchat.app.core.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Drives apple_uwb_vectors.json against the existing AppleNiProtocol codec. */
class AppleUwbVectorsTest {
    private val v = Vectors.json("apple_uwb_vectors.json")

    @Test
    fun accessoryConfigMatchesFixture() {
        val accessory = v.getJSONObject("accessoryConfig")
        val built = AppleNiProtocol.buildAccessoryConfigurationData(hex(accessory.getString("localAddressMsbFirstHex")))
        assertEquals(accessory.getString("dataHex"), built.toHex())
    }

    @Test
    fun shareableParsesToExactFields() {
        val shareable = v.getJSONObject("shareable")
        val parsed = AppleNiProtocol.parseShareableConfiguration(hex(shareable.getString("dataHex")))
        val expected = shareable.getJSONObject("parsed")
        assertEquals(expected.getLong("sessionId"), parsed.sessionId.toLong() and 0xffffffffL)
        assertEquals(expected.getInt("preambleIndex"), parsed.preambleIndex)
        assertEquals(expected.getInt("channel"), parsed.channel)
        assertEquals(expected.getInt("slotsPerRound"), parsed.slotsPerRound)
        assertEquals(expected.getInt("slotDurationRstu"), parsed.slotDurationRstu)
        assertEquals(expected.getInt("rangingIntervalMs"), parsed.rangingIntervalMs)
        assertEquals(expected.getBoolean("hoppingEnabled"), parsed.hoppingEnabled)
        assertEquals(expected.getString("staticStsIvHex"), parsed.staticStsIv.toHex())
        assertEquals(expected.getString("peerAddressMsbFirstHex"), parsed.peerAddress.toHex())
        assertEquals(
            shareable.getString("androidSessionKeyInfoHex"),
            AppleNiProtocol.androidSessionKeyInfo(parsed.staticStsIv).toHex(),
        )
    }

    @Test
    fun shareableRejectCasesThrow() {
        val reject = v.getJSONArray("shareableReject")
        for (i in 0 until reject.length()) {
            val entry = reject.getJSONObject(i)
            assertThrows(
                "should reject: ${entry.getString("reason")}",
                IllegalArgumentException::class.java,
            ) {
                AppleNiProtocol.parseShareableConfiguration(hex(entry.getString("dataHex")))
            }
        }
    }
}
