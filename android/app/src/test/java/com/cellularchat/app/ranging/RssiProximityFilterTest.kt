package com.cellularchat.app.ranging

import org.junit.Assert.assertEquals
import org.junit.Test

class RssiProximityFilterTest {
    private fun feed(filter: RssiProximityFilter, value: Int, times: Int): ProximityBand {
        var band = ProximityBand.UNKNOWN
        repeat(times) { band = filter.update(value) }
        return band
    }

    @Test
    fun startsUnknown() {
        assertEquals(ProximityBand.UNKNOWN, RssiProximityFilter().current())
    }

    @Test
    fun strongSignalIsVeryNearWeakIsFar() {
        assertEquals(ProximityBand.VERY_NEAR, feed(RssiProximityFilter(), -50, 5))
        assertEquals(ProximityBand.FAR, feed(RssiProximityFilter(), -90, 5))
    }

    @Test
    fun medianRejectsASingleSpike() {
        val filter = RssiProximityFilter()
        filter.update(-50)
        filter.update(-50)
        filter.update(-50)
        filter.update(-95) // one spike
        val band = filter.update(-50)
        // Median of [-50,-50,-50,-95,-50] is -50, so the spike does not move the band.
        assertEquals(ProximityBand.VERY_NEAR, band)
    }

    @Test
    fun hysteresisPreventsFlickerNearABoundary() {
        val filter = RssiProximityFilter() // veryNear >= -60, near >= -80, H = 4
        assertEquals(ProximityBand.VERY_NEAR, feed(filter, -50, 5))
        // -62 is past the -60 boundary but within hysteresis of the entry level:
        // the band must hold at VERY_NEAR rather than flicker to NEAR.
        assertEquals(ProximityBand.VERY_NEAR, feed(filter, -62, 5))
        // Only once the median clears -64 does it demote.
        assertEquals(ProximityBand.NEAR, feed(filter, -66, 5))
        // Coming back to -62 must NOT immediately re-promote (needs >= -56).
        assertEquals(ProximityBand.NEAR, feed(filter, -62, 5))
        // A clearly strong signal re-promotes.
        assertEquals(ProximityBand.VERY_NEAR, feed(filter, -54, 5))
    }
}
