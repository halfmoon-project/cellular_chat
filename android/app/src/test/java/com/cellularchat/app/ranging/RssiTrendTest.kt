package com.cellularchat.app.ranging

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Feature C: the pure approaching/receding trend algorithm. The parameters are
 * fixed and identical to iOS, so a given RSSI input sequence fully determines the
 * enum outputs; these assertions are the cross-platform expected values.
 */
class RssiTrendTest {
    private fun feed(filter: RssiProximityFilter, values: List<Int>) {
        values.forEach { filter.update(it) }
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
        // Rising by 2 dB/sample: the window medians rise linearly (slope +2).
        feed(filter, (0 until 20).map { -100 + 2 * it })
        assertEquals(RssiTrend.APPROACHING, filter.trend)
        assertEquals(TrendConfidence.HIGH, filter.trendConfidence)
    }

    @Test
    fun fallingSignalIsRecedingHigh() {
        val filter = RssiProximityFilter()
        // Falling by 2 dB/sample: slope -2.
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
        filter.update(-70) // k = 6, residual variance 0 -> high
        assertEquals(RssiTrend.STEADY, filter.trend)
        assertEquals(TrendConfidence.HIGH, filter.trendConfidence)
    }

    @Test
    fun noisySignalIsLowConfidence() {
        val filter = RssiProximityFilter()
        // Alternating extremes give a large residual variance (> the 16 dB² cap).
        feed(filter, (0 until 14).map { if (it % 2 == 0) -50 else -90 })
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
