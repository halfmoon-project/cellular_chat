package com.cellularchat.app.ranging

/**
 * Turns raw BLE RSSI into stable proximity bands (PROTOCOL_V2.md §12,
 * IMPLEMENTATION_PLAN.md Phase 4). Two stages:
 *
 * 1. A rolling median over the last [windowSize] samples removes single-sample
 *    spikes.
 * 2. Hysteresis: a band boundary must be crossed by [hysteresisDb] before the
 *    reported band changes, so a signal hovering on a threshold does not flicker.
 *
 * Output is only ever a band — never an exact distance and never an angle.
 */
class RssiProximityFilter(
    private val windowSize: Int = 5,
    private val veryNearThresholdDb: Int = -60,
    private val nearThresholdDb: Int = -80,
    private val hysteresisDb: Int = 4,
) {
    private val window = ArrayDeque<Int>()
    private var band: ProximityBand = ProximityBand.UNKNOWN

    fun current(): ProximityBand = band

    fun reset() {
        window.clear()
        band = ProximityBand.UNKNOWN
    }

    /** Feeds one raw RSSI reading (dBm) and returns the stabilized band. */
    fun update(rssiDb: Int): ProximityBand {
        window.addLast(rssiDb)
        while (window.size > windowSize) window.removeFirst()
        val median = medianOf(window)
        band = nextBand(band, median)
        return band
    }

    private fun medianOf(values: Collection<Int>): Int {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun nextBand(currentBand: ProximityBand, median: Int): ProximityBand {
        // Boundaries are widened in the direction of the current band so that
        // leaving a band requires overshooting its entry threshold.
        val veryNearEntry = veryNearThresholdDb
        val nearEntry = nearThresholdDb
        return when (currentBand) {
            ProximityBand.VERY_NEAR ->
                if (median < veryNearEntry - hysteresisDb) demoteFrom(median) else ProximityBand.VERY_NEAR
            ProximityBand.NEAR -> when {
                median >= veryNearEntry + hysteresisDb -> ProximityBand.VERY_NEAR
                median < nearEntry - hysteresisDb -> ProximityBand.FAR
                else -> ProximityBand.NEAR
            }
            ProximityBand.FAR ->
                if (median >= nearEntry + hysteresisDb) promoteFrom(median) else ProximityBand.FAR
            ProximityBand.UNKNOWN -> rawBand(median)
        }
    }

    private fun rawBand(median: Int): ProximityBand = when {
        median >= veryNearThresholdDb -> ProximityBand.VERY_NEAR
        median >= nearThresholdDb -> ProximityBand.NEAR
        else -> ProximityBand.FAR
    }

    private fun demoteFrom(median: Int): ProximityBand =
        if (median >= nearThresholdDb) ProximityBand.NEAR else ProximityBand.FAR

    private fun promoteFrom(median: Int): ProximityBand =
        if (median >= veryNearThresholdDb) ProximityBand.VERY_NEAR else ProximityBand.NEAR
}
