package com.cellularchat.app.ranging

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure approaching/receding trend algorithm. The parameters are fixed and
 * identical to iOS, and the regression's x-axis is SECONDS, so a fixture is
 * (values, timestamps, prior trend) and fully determines the enum outputs;
 * these assertions are the cross-platform expected values.
 */
class RssiTrendTest {
    private val cadenceMillis = 1_500L // the central's RSSI poll interval

    /** Feeds [values] at a fixed cadence starting from t = 0. */
    private fun feed(filter: RssiProximityFilter, values: List<Int>, stepMillis: Long = cadenceMillis) {
        values.forEachIndexed { i, v -> filter.update(v, i * stepMillis) }
    }

    @Test
    fun fewerThanMinSamplesIsSteadyLow() {
        val filter = RssiProximityFilter()
        feed(filter, listOf(-70, -70, -70)) // k = 3 < MIN_SAMPLES_FOR_TREND
        assertEquals(RssiTrend.STEADY, filter.trend)
        assertEquals(TrendConfidence.LOW, filter.trendConfidence)
    }

    @Test
    fun risingSignalIsApproachingHigh() {
        val filter = RssiProximityFilter()
        // +2 dB per 1.5 s = 1.33 dB/s, past the 0.6 dB/s entry threshold.
        feed(filter, (0 until 20).map { -100 + 2 * it })
        assertEquals(RssiTrend.APPROACHING, filter.trend)
        assertEquals(TrendConfidence.HIGH, filter.trendConfidence)
    }

    @Test
    fun fallingSignalIsRecedingHigh() {
        val filter = RssiProximityFilter()
        feed(filter, (0 until 20).map { -60 - 2 * it })
        assertEquals(RssiTrend.RECEDING, filter.trend)
        assertEquals(TrendConfidence.HIGH, filter.trendConfidence)
    }

    @Test
    fun flatSignalCrossesToHighConfidenceAtSixSamples() {
        val filter = RssiProximityFilter()
        feed(filter, List(5) { -70 }) // k = 5 < HIGH_CONF_MIN_SAMPLES
        assertEquals(RssiTrend.STEADY, filter.trend)
        assertEquals(TrendConfidence.LOW, filter.trendConfidence)
        filter.update(-70, 5 * cadenceMillis) // k = 6, span 7.5 s, no residual
        assertEquals(RssiTrend.STEADY, filter.trend)
        assertEquals(TrendConfidence.HIGH, filter.trendConfidence)
    }

    /**
     * Samples arriving in a burst describe too short an arc to fit a speed to,
     * however many there are: the slope would be an artefact of the burst.
     */
    @Test
    fun burstOfSamplesOverAShortSpanIsSteadyLow() {
        val filter = RssiProximityFilter()
        feed(filter, (0 until 8).map { -80 + 2 * it }, stepMillis = 200L) // span 1.4 s < 3 s
        assertEquals(RssiTrend.STEADY, filter.trend)
        assertEquals(TrendConfidence.LOW, filter.trendConfidence)
    }

    @Test
    fun noisySignalIsLowConfidence() {
        val filter = RssiProximityFilter()
        // Alternating extremes give a large residual variance (> the 4 dB² cap).
        feed(filter, (0 until 14).map { if (it % 2 == 0) -50 else -90 })
        assertEquals(TrendConfidence.LOW, filter.trendConfidence)
    }

    /**
     * The entry threshold is what separates a walk from RSSI noise, so pin both
     * sides of it (avoiding an exact-equality fixture, which floats can't hold).
     */
    @Test
    fun entryThresholdSeparatesWalkFromNoise() {
        // RSSI is whole dBm, so vary the cadence rather than the step: a
        // fractional ramp truncates into a staircase and fits its own slope.
        // 14 samples so the median window is past its warm-up (a partly-filled
        // window flattens the ramp and would understate the slope).
        val over = RssiProximityFilter()
        feed(over, (0 until 14).map { -80 + it }, stepMillis = 1_500L) // 0.67 dB/s
        assertEquals(RssiTrend.APPROACHING, over.trend)
        val under = RssiProximityFilter()
        feed(under, (0 until 14).map { -80 + it }, stepMillis = 3_000L) // 0.33 dB/s
        assertEquals(RssiTrend.STEADY, under.trend)
    }

    /**
     * The label is hidden at LOW confidence, so the residual-variance gate is
     * hysteretic too: a variance between the two caps sustains a shown label but
     * is not clean enough to start showing one. windowSize = 1 so the median
     * history is exactly the input and the variance is the one computed here.
     */
    @Test
    fun confidenceHysteresisHoldsBetweenTheCaps() {
        // Residual variance ≈ 6.6 dB²: past the 4.0 entry, under the 8.0 exit.
        val wobble = listOf(0, 3, -3, 0, 3, -3, 0, 3, -3, 0).map { -70 + it }

        val fresh = RssiProximityFilter(windowSize = 1)
        feed(fresh, wobble)
        assertEquals(TrendConfidence.LOW, fresh.trendConfidence)

        val settled = RssiProximityFilter(windowSize = 1)
        feed(settled, List(10) { -70 })
        assertEquals(TrendConfidence.HIGH, settled.trendConfidence)
        // Same 10-sample history as `fresh`, only the prior confidence differs.
        wobble.forEachIndexed { i, v -> settled.update(v, (10 + i) * cadenceMillis) }
        assertEquals(TrendConfidence.HIGH, settled.trendConfidence)
    }

    /**
     * Hysteresis: a slope between the exit and entry thresholds sustains an
     * existing label but cannot start one.
     */
    @Test
    fun hysteresisHoldsALabelBelowTheEntryThreshold() {
        val filter = RssiProximityFilter()
        // Climb hard enough to latch APPROACHING (2 dB per 1.5 s).
        feed(filter, (0 until 10).map { -100 + 2 * it })
        assertEquals(RssiTrend.APPROACHING, filter.trend)
        // Then ease off to 0.4 dB/s: above the 0.3 exit, below the 0.6 entry.
        var t = 10 * cadenceMillis
        var v = -82
        repeat(10) { v += 2; t += 5_000L; filter.update(v, t) }
        assertEquals(RssiTrend.APPROACHING, filter.trend)
    }

    /**
     * A hole longer than the gap threshold must not be regressed across: the
     * pre-gap samples describe a different situation entirely.
     */
    @Test
    fun gapResetsTheTrendHistory() {
        val filter = RssiProximityFilter()
        feed(filter, (0 until 20).map { -100 + 2 * it })
        assertEquals(RssiTrend.APPROACHING, filter.trend)
        val afterGap = 19 * cadenceMillis + (RssiProximityFilter.GAP_RESET_SECONDS * 1000).toLong() + 1_000
        filter.update(-62, afterGap)
        assertEquals(RssiTrend.STEADY, filter.trend)
        assertEquals(TrendConfidence.LOW, filter.trendConfidence)
    }

    /** A backwards wall-clock step is a gap too — never a huge negative slope. */
    @Test
    fun backwardsClockResetsTheTrendHistory() {
        val filter = RssiProximityFilter()
        feed(filter, (0 until 20).map { -100 + 2 * it })
        filter.update(-62, 0L)
        assertEquals(RssiTrend.STEADY, filter.trend)
        assertEquals(TrendConfidence.LOW, filter.trendConfidence)
    }

    @Test
    fun resetClearsTrend() {
        val filter = RssiProximityFilter()
        feed(filter, (0 until 20).map { -100 + 2 * it })
        assertEquals(RssiTrend.APPROACHING, filter.trend)
        filter.reset()
        assertEquals(RssiTrend.STEADY, filter.trend)
        assertEquals(TrendConfidence.LOW, filter.trendConfidence)
    }
}
