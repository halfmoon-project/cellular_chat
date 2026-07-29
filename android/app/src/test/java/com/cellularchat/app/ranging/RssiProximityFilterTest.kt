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
        // §12-pinned: veryNear >= -55, near >= -75, hysteresis 5.
        val filter = RssiProximityFilter()
        assertEquals(ProximityBand.VERY_NEAR, feed(filter, -50, 5))
        // -58 is past the -55 boundary but within hysteresis of the entry level:
        // the band must hold at VERY_NEAR rather than flicker to NEAR.
        assertEquals(ProximityBand.VERY_NEAR, feed(filter, -58, 5))
        // Only once the median drops below -60 does it demote.
        assertEquals(ProximityBand.NEAR, feed(filter, -62, 5))
        // Coming back to -58 must NOT immediately re-promote (needs >= -50).
        assertEquals(ProximityBand.NEAR, feed(filter, -58, 5))
        // A clearly strong signal re-promotes.
        assertEquals(ProximityBand.VERY_NEAR, feed(filter, -48, 5))
    }
}
