package com.cellularchat.app.ranging

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppleNiProtocolTest {
    @Test
    fun accessoryConfigUsesV2RequestableTiming() {
        val data = AppleNiProtocol.buildAccessoryConfigurationData(
            byteArrayOf(0xaa.toByte(), 0xbb.toByte()),
        )
        assertEquals(48, data.size)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, buffer.getShort(0).toInt())
        assertEquals(20, data[4].toInt())
        assertEquals(32, data[15].toInt())
        assertEquals(2, buffer.getShort(16).toInt())
        assertEquals(1, data[32].toInt())
        assertEquals(0xbb.toByte(), data[33])
        assertEquals(0xaa.toByte(), data[34])
        assertEquals(6, buffer.getShort(42).toInt())
        assertEquals(2_400, buffer.getShort(44).toInt())
        assertEquals(240, buffer.getShort(46).toInt())
    }

    @Test
    fun shareableConfigParserUsesDocumentedOffsets() {
        val data = ByteBuffer.allocate(35).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 2)
            putShort(2, 0)
            put(4, 30)
            put(5, 'K'.code.toByte())
            put(6, 'R'.code.toByte())
            putInt(7, 0x12345678)
            put(11, 11)
            put(12, 9)
            putShort(13, 6)
            putShort(15, 2_400)
            putShort(17, 240)
            put(19, 3)
            for (index in 0 until 6) put(20 + index, (index + 1).toByte())
            put(26, 0x34)
            put(27, 0x12)
            putShort(28, 50)
            put(34, 1)
        }.array()
        val parsed = AppleNiProtocol.parseShareableConfiguration(data)
        assertEquals(0x12345678, parsed.sessionId)
        assertEquals(11, parsed.preambleIndex)
        assertEquals(9, parsed.channel)
        assertEquals(2_400, parsed.slotDurationRstu)
        assertEquals(6, parsed.slotsPerRound)
        assertEquals(240, parsed.rangingIntervalMs)
        assertEquals(true, parsed.hoppingEnabled)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), parsed.staticStsIv)
        assertArrayEquals(byteArrayOf(0x12, 0x34), parsed.peerAddress)
        assertArrayEquals(
            byteArrayOf(0x00, 0x4c, 1, 2, 3, 4, 5, 6),
            AppleNiProtocol.androidSessionKeyInfo(parsed.staticStsIv),
        )
    }

    @Test
    fun rejectsTruncatedShareableConfig() {
        assertThrows(IllegalArgumentException::class.java) {
            AppleNiProtocol.parseShareableConfiguration(ByteArray(34))
        }
    }

    @Test
    fun rejectsIncompatibleV2TimingExtension() {
        val data = ByteArray(35).apply {
            this[0] = 2
            this[4] = 30
            this[7] = 1
            this[11] = 11
            this[12] = 9
            this[34] = 0
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppleNiProtocol.parseShareableConfiguration(data)
        }
    }
}
