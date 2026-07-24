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

    // Feature C: a separate FIFO of recent trend medians (Double dBm) feeds the
    // approaching/receding regression. It is IN ADDITION to the raw band window.
    private val medianHistory = ArrayDeque<Double>()

    var trend: RssiTrend = RssiTrend.STEADY
        private set
    var trendConfidence: TrendConfidence = TrendConfidence.LOW
        private set

    fun current(): ProximityBand = band

    fun reset() {
        window.clear()
        medianHistory.clear()
        band = ProximityBand.UNKNOWN
        trend = RssiTrend.STEADY
        trendConfidence = TrendConfidence.LOW
    }

    /** Feeds one raw RSSI reading (dBm) and returns the stabilized band. */
    fun update(rssiDb: Int): ProximityBand {
        window.addLast(rssiDb)
        while (window.size > windowSize) window.removeFirst()
        val median = medianOf(window)
        band = nextBand(band, median)
        updateTrend()
        return band
    }

    // --- Feature C: approaching/receding trend with confidence ---

    /**
     * Appends the current window's averaged-middle median (as a Double) to
     * [medianHistory] and re-runs the least-squares regression (C.3–C.6). This
     * median definition is used ONLY for the trend and is independent of the
     * band's own (upper-middle) median, so the trend is cross-platform-identical.
     */
    private fun updateTrend() {
        medianHistory.addLast(trendMedianOf(window))
        while (medianHistory.size > MEDIAN_HISTORY) medianHistory.removeFirst()
        recomputeTrend()
    }

    private fun trendMedianOf(values: Collection<Int>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2].toDouble()
        } else {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }
    }

    private fun recomputeTrend() {
        val y = medianHistory.toList()
        val k = y.size
        if (k < MIN_SAMPLES_FOR_TREND) {
            trend = RssiTrend.STEADY
            trendConfidence = TrendConfidence.LOW
            return
        }
        val meanX = (k - 1) / 2.0
        val meanY = y.sum() / k
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until k) {
            val dx = i - meanX
            sxx += dx * dx
            sxy += dx * (y[i] - meanY)
        }
        val slope = sxy / sxx
        trend = when {
            slope >= APPROACHING_SLOPE -> RssiTrend.APPROACHING
            slope <= RECEDING_SLOPE -> RssiTrend.RECEDING
            else -> RssiTrend.STEADY
        }
        var residualSq = 0.0
        for (i in 0 until k) {
            val fit = meanY + slope * (i - meanX)
            val r = y[i] - fit
            residualSq += r * r
        }
        val residualVariance = residualSq / k
        trendConfidence = if (k >= HIGH_CONF_MIN_SAMPLES && residualVariance <= RESIDUAL_VARIANCE_CAP) {
            TrendConfidence.HIGH
        } else {
            TrendConfidence.LOW
        }
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

    companion object {
        // Feature C fixed parameters — identical on iOS and Android.
        const val MEDIAN_HISTORY = 10
        const val MIN_SAMPLES_FOR_TREND = 4
        const val HIGH_CONF_MIN_SAMPLES = 6
        const val APPROACHING_SLOPE = 0.5
        const val RECEDING_SLOPE = -0.5
        const val RESIDUAL_VARIANCE_CAP = 16.0
    }
}
