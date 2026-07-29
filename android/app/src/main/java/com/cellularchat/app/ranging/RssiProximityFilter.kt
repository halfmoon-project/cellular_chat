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
    private val veryNearThresholdDb: Int = -55,
    private val nearThresholdDb: Int = -75,
    private val hysteresisDb: Int = 5,
) {
    private val window = ArrayDeque<Int>()
    private var band: ProximityBand = ProximityBand.UNKNOWN

    /** Recent (timestamp seconds, window median dBm) pairs driving the trend. */
    private val medianHistory = ArrayDeque<Pair<Double, Double>>()

    var trend: RssiTrend = RssiTrend.STEADY
        private set
    var trendConfidence: TrendConfidence = TrendConfidence.LOW
        private set

    fun current(): ProximityBand = band

    fun reset() {
        clearHistory()
        band = ProximityBand.UNKNOWN
    }

    /**
     * Feeds one raw RSSI reading (dBm) and returns the stabilized band.
     * [atMillis] is wall-clock, stamped as close to the radio read as possible:
     * the trend regresses over seconds, so a queue hop compresses its arc.
     */
    fun update(rssiDb: Int, atMillis: Long = System.currentTimeMillis()): ProximityBand {
        val timestamp = atMillis / 1000.0
        medianHistory.lastOrNull()?.let { (last, _) ->
            val elapsed = timestamp - last
            if (elapsed < 0 || elapsed > GAP_RESET_SECONDS) clearHistory()
        }
        window.addLast(rssiDb)
        while (window.size > windowSize) window.removeFirst()
        val median = medianOf(window)
        band = nextBand(band, median)
        updateTrend(timestamp)
        return band
    }

    /** Drops the sampling history but keeps the band: a stale window must not
     * feed either the median or the regression after a gap. */
    private fun clearHistory() {
        window.clear()
        medianHistory.clear()
        trend = RssiTrend.STEADY
        trendConfidence = TrendConfidence.LOW
    }

    // --- approaching/receding trend with confidence ---

    /**
     * Appends the current window's median to [medianHistory] and re-runs the
     * least-squares regression. Band and trend share the one §12 median.
     */
    private fun updateTrend(timestamp: Double) {
        medianHistory.addLast(timestamp to medianOf(window))
        while (medianHistory.size > MEDIAN_HISTORY) medianHistory.removeFirst()
        recomputeTrend()
    }

    private fun recomputeTrend() {
        val k = medianHistory.size
        if (k < MIN_SAMPLES_FOR_TREND) {
            trend = RssiTrend.STEADY
            trendConfidence = TrendConfidence.LOW
            return
        }
        val points = medianHistory.toList()
        val span = points.last().first - points.first().first
        if (span < MIN_SPAN_SECONDS) {
            trend = RssiTrend.STEADY
            trendConfidence = TrendConfidence.LOW
            return
        }
        val meanT = points.sumOf { it.first } / k
        val meanY = points.sumOf { it.second } / k
        var stt = 0.0
        var sty = 0.0
        for ((t, y) in points) {
            val dt = t - meanT
            stt += dt * dt
            sty += dt * (y - meanY)
        }
        val slope = sty / stt

        // Hysteresis: hold at the exit threshold, reverse only at the entry one.
        trend = when (trend) {
            RssiTrend.APPROACHING -> when {
                slope >= EXIT_SLOPE_DB_PER_SEC -> RssiTrend.APPROACHING
                slope <= -ENTER_SLOPE_DB_PER_SEC -> RssiTrend.RECEDING
                else -> RssiTrend.STEADY
            }
            RssiTrend.RECEDING -> when {
                slope <= -EXIT_SLOPE_DB_PER_SEC -> RssiTrend.RECEDING
                slope >= ENTER_SLOPE_DB_PER_SEC -> RssiTrend.APPROACHING
                else -> RssiTrend.STEADY
            }
            RssiTrend.STEADY -> when {
                slope >= ENTER_SLOPE_DB_PER_SEC -> RssiTrend.APPROACHING
                slope <= -ENTER_SLOPE_DB_PER_SEC -> RssiTrend.RECEDING
                else -> RssiTrend.STEADY
            }
        }

        var residualSq = 0.0
        for ((t, y) in points) {
            val r = y - (meanY + slope * (t - meanT))
            residualSq += r * r
        }
        val residualVariance = residualSq / maxOf(k - 2, 1)
        val cap = if (trendConfidence == TrendConfidence.HIGH) {
            RESIDUAL_VARIANCE_EXIT
        } else {
            RESIDUAL_VARIANCE_ENTER
        }
        trendConfidence = if (
            k >= HIGH_CONF_MIN_SAMPLES &&
            span >= HIGH_CONF_MIN_SPAN_SECONDS &&
            residualVariance <= cap
        ) {
            TrendConfidence.HIGH
        } else {
            TrendConfidence.LOW
        }
    }

    /**
     * Averaged-middle median, pinned by §12 so both platforms classify a given
     * sequence identically. Used for the band AND the trend: the band used to
     * take the upper-middle Int instead, which disagreed with iOS on every
     * even-sized window (i.e. while the window is still filling).
     */
    private fun medianOf(values: Collection<Int>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2].toDouble()
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /**
     * §12 band classification. Changing bands requires the median to clear the
     * crossed boundary by [hysteresisDb]; staying put needs nothing. Pinned
     * against `shared/vectors/rssi_bands.json`, which both platforms consume —
     * this used to be a looser rule than iOS's and the two phones of one pair
     * reported different bands at the same distance.
     */
    private fun nextBand(currentBand: ProximityBand, median: Double): ProximityBand {
        val raw = rawBand(median)
        // First real reading, or no change: accept directly.
        if (currentBand == ProximityBand.UNKNOWN || raw == currentBand) return raw
        val vn = veryNearThresholdDb
        val near = nearThresholdDb
        val m = hysteresisDb
        return when {
            currentBand == ProximityBand.FAR -> when {
                median >= vn + m -> ProximityBand.VERY_NEAR
                median >= near + m -> ProximityBand.NEAR
                else -> currentBand
            }
            currentBand == ProximityBand.NEAR && raw == ProximityBand.VERY_NEAR ->
                if (median >= vn + m) ProximityBand.VERY_NEAR else currentBand
            currentBand == ProximityBand.NEAR && raw == ProximityBand.FAR ->
                if (median < near - m) ProximityBand.FAR else currentBand
            currentBand == ProximityBand.VERY_NEAR -> when {
                median < near - m -> ProximityBand.FAR
                median < vn - m -> ProximityBand.NEAR
                else -> currentBand
            }
            else -> raw
        }
    }

    private fun rawBand(median: Double): ProximityBand = when {
        median >= veryNearThresholdDb -> ProximityBand.VERY_NEAR
        median >= nearThresholdDb -> ProximityBand.NEAR
        else -> ProximityBand.FAR
    }

    companion object {
        // Trend parameters, pinned in code on both platforms so a given
        // (RSSI, timestamp) sequence yields identical enums. No external spec
        // defines them — shared/PROTOCOL_V2.md §12 covers only the bands.
        const val MEDIAN_HISTORY = 10
        const val MIN_SAMPLES_FOR_TREND = 4
        const val HIGH_CONF_MIN_SAMPLES = 6

        /**
         * Regression is over SECONDS, so the thresholds are a physical speed,
         * not a per-sample step: a 1 m/s walk at distance d gives ~8.7/d dB/s.
         * Enter at 0.6 dB/s (walking within ~14 m); measured static-noise slope
         * sd is 0.25 dB/s at 4 dB RSSI noise and 0.38 dB/s at 6 dB, so a lower
         * entry threshold sits inside the noise floor and labels a still peer.
         */
        const val ENTER_SLOPE_DB_PER_SEC = 0.6

        /** Hold a label until the slope decays to half the entry threshold;
         * reversing to the opposite label still needs a full entry crossing. */
        const val EXIT_SLOPE_DB_PER_SEC = 0.3

        const val MIN_SPAN_SECONDS = 3.0
        const val HIGH_CONF_MIN_SPAN_SECONDS = 6.0

        /** Residual variance about the fitted line, unbiased (k-2) divisor.
         * 4 dB² ≈ 2 dB residual std; above that the link is bouncing
         * (multipath/body blocking) and the slope means nothing. Hysteresis here
         * too — the label is hidden at LOW, so a hard threshold would blink a
         * latched trend on and off as the variance crosses it. */
        const val RESIDUAL_VARIANCE_ENTER = 4.0
        const val RESIDUAL_VARIANCE_EXIT = 8.0

        /** A hole this long (backgrounding, link stall, dropped reads) means the
         * old samples describe a different situation: start over rather than
         * regress across the gap. A backwards clock step resets for the same
         * reason. */
        const val GAP_RESET_SECONDS = 10.0
    }
}
