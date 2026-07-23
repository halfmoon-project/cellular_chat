package com.cellularchat.app.ranging

/** Coarse proximity bands from BLE RSSI (PROTOCOL_V2.md §12). Never a distance. */
enum class ProximityBand { VERY_NEAR, NEAR, FAR, UNKNOWN }

/**
 * A single fresh ranging sample. Only fields the platform actually reported are
 * non-null; a missing angle degrades to distance-only, a missing distance to
 * proximity-only (§12). RSSI proximity carries only [proximity].
 */
data class Measurement(
    val method: Int, // RangingMethod.* from core
    val distanceMeters: Double? = null,
    val azimuthDegrees: Double? = null,
    val elevationDegrees: Double? = null,
    val proximity: ProximityBand? = null,
)

/**
 * Bounded exponential backoff for UWB retry after invalidation or sustained
 * sample loss (PROTOCOL_V2.md §12): initial 5 s, ×2, capped at 60 s.
 */
class BackoffSchedule(
    private val initialMillis: Long = 5_000,
    private val factor: Long = 2,
    private val capMillis: Long = 60_000,
) {
    private var current = initialMillis

    fun nextDelayMillis(): Long {
        val delay = current
        current = minOf(current * factor, capMillis)
        return delay
    }

    fun reset() {
        current = initialMillis
    }
}
