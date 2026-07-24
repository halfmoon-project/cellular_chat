package com.cellularchat.app.ui

import com.cellularchat.app.ranging.ProximityBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FindHapticsTest {
    @Test
    fun closerDistancePulsesFaster() {
        val near = FindHaptics.intervalMillis(0.5, null)!!
        val far = FindHaptics.intervalMillis(8.0, null)!!
        assertTrue("near ($near) should be a shorter interval than far ($far)", near < far)
    }

    @Test
    fun distanceIsClampedAndMonotonic() {
        // Beyond the 10 m clamp the cadence stops getting slower.
        assertEquals(FindHaptics.intervalMillis(10.0, null), FindHaptics.intervalMillis(50.0, null))
        // At 0 m it is the fastest.
        assertTrue(FindHaptics.intervalMillis(0.0, null)!! < FindHaptics.intervalMillis(1.0, null)!!)
    }

    @Test
    fun proximityBandsScaleByCloseness() {
        val veryNear = FindHaptics.intervalMillis(null, ProximityBand.VERY_NEAR)!!
        val near = FindHaptics.intervalMillis(null, ProximityBand.NEAR)!!
        val far = FindHaptics.intervalMillis(null, ProximityBand.FAR)!!
        assertTrue(veryNear < near)
        assertTrue(near < far)
    }

    @Test
    fun unknownOrAbsentMeasurementProducesNoPulse() {
        assertNull(FindHaptics.intervalMillis(null, ProximityBand.UNKNOWN))
        assertNull(FindHaptics.intervalMillis(null, null))
    }

    @Test
    fun distanceTakesPrecedenceOverBand() {
        // A precise distance drives cadence even if a band is also present.
        assertEquals(
            FindHaptics.intervalMillis(0.0, null),
            FindHaptics.intervalMillis(0.0, ProximityBand.FAR),
        )
    }
}
