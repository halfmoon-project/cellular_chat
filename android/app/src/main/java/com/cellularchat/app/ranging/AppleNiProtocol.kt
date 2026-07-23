package com.cellularchat.app.ranging

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Apple Nearby Interaction Accessory/UWB interoperability R4 byte codec. */
object AppleNiProtocol {
    private const val OUTER_MAJOR = 1
    private const val OUTER_MINOR = 0
    private const val INNER_MAJOR = 2
    private const val INNER_MINOR = 0
    private const val INNER_SIZE = 32

    const val REQUESTED_SLOT_DURATION_RSTU = 2_400
    const val REQUESTED_SLOTS_PER_ROUND = 6
    const val REQUESTED_RANGING_INTERVAL_MS = 240

    data class ShareableConfig(
        val sessionId: Int,
        val preambleIndex: Int,
        val channel: Int,
        val slotsPerRound: Int,
        val slotDurationRstu: Int,
        val rangingIntervalMs: Int,
        val hoppingEnabled: Boolean,
        val staticStsIv: ByteArray,
        /** Apple short address, in the MSB-first order Android UwbAddress accepts. */
        val peerAddress: ByteArray,
    )

    /**
     * Builds Accessory Configuration Data (outer v1.0, UWB config v2.0).
     * Multi-octet fields are little-endian on the Apple wire.
     */
    fun buildAccessoryConfigurationData(localAddressMsbFirst: ByteArray): ByteArray {
        require(localAddressMsbFirst.size == 2)
        val output = ByteBuffer.allocate(16 + INNER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        output.putShort(OUTER_MAJOR.toShort())
        output.putShort(OUTER_MINOR.toShort())
        output.put(20) // User Interactive update rate.
        output.put(ByteArray(10)) // Outer RFU.
        output.put(INNER_SIZE.toByte())

        output.putShort(INNER_MAJOR.toShort())
        output.putShort(INNER_MINOR.toShort())
        output.putInt(0) // Manufacturer-specific identifier.
        output.putInt(0) // UWB chipset model identifier.
        output.putInt(0x0002_0000) // Middleware version marker.
        output.put(1) // Initiator/controller.
        output.put(localAddressMsbFirst[1]) // SOURCE_ADDRESS u16 LE.
        output.put(localAddressMsbFirst[0])
        output.putShort(50) // Maximum clock drift, PPM.
        output.putInt(0) // Inner RFU.
        output.put(1) // Request hopping.
        output.putShort(REQUESTED_SLOTS_PER_ROUND.toShort())
        output.putShort(REQUESTED_SLOT_DURATION_RSTU.toShort())
        output.putShort(REQUESTED_RANGING_INTERVAL_MS.toShort())
        return output.array()
    }

    /** Parses the 35-byte Apple R4 v2 shareable configuration payload. */
    fun parseShareableConfiguration(data: ByteArray): ShareableConfig {
        require(data.size >= 35) { "Apple shareable configuration is truncated" }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val major = buffer.getShort(0).toInt() and 0xffff
        val minor = buffer.getShort(2).toInt() and 0xffff
        require(major == INNER_MAJOR) { "Unsupported Apple UWB config major version: $major" }
        require(minor == INNER_MINOR) { "Unsupported Apple UWB config minor version: $minor" }
        val remaining = data[4].toInt() and 0xff
        require(remaining == 30 && data.size >= remaining + 5) { "Invalid Apple UWB config length" }
        require(data.copyOfRange(30, 34).all { it.toInt() == 0 }) {
            "Apple UWB config RFU bytes must be zero"
        }
        val sessionId = buffer.getInt(7)
        val preamble = data[11].toInt() and 0xff
        require(preamble in 9..12) { "Unsupported UWB preamble: $preamble" }
        val channel = data[12].toInt() and 0xff
        val slots = buffer.getShort(13).toInt() and 0xffff
        val slotDuration = buffer.getShort(15).toInt() and 0xffff
        val rangingInterval = buffer.getShort(17).toInt() and 0xffff
        val iv = data.copyOfRange(20, 26)
        val peerAddress = byteArrayOf(data[27], data[26])
        val hoppingMode = data[34].toInt() and 0xff
        require(hoppingMode == 1) { "Unsupported Apple UWB hopping mode: $hoppingMode" }
        return ShareableConfig(
            sessionId,
            preamble,
            channel,
            slots,
            slotDuration,
            rangingInterval,
            true,
            iv,
            peerAddress,
        )
    }

    /** Android static-STS layout: Apple Vendor ID 0x004c (network order), then 6-byte IV. */
    fun androidSessionKeyInfo(staticStsIv: ByteArray): ByteArray {
        require(staticStsIv.size == 6)
        return byteArrayOf(0x00, 0x4c) + staticStsIv
    }
}
