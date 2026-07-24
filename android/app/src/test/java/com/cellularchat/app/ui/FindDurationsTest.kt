package com.cellularchat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindDurationsTest {
    @Test
    fun offersFifteenThirtyOneAndTwoHours() {
        assertEquals(listOf(15, 30, 60, 120), FindDurations.minuteOptions)
    }

    @Test
    fun defaultIsThirtyMinutes() {
        assertEquals(30, FindDurations.DEFAULT_MINUTES)
        assertEquals(30, FindDurations.minuteOptions[FindDurations.defaultIndex])
    }

    @Test
    fun millisConversion() {
        assertEquals(30 * 60 * 1000L, FindDurations.millis(30))
        assertEquals(2 * 60 * 60 * 1000L, FindDurations.millis(120))
    }

    @Test
    fun maxIsTwoHoursPerProtocol() {
        // PROTOCOL_V2.md §10: max Find duration is 2 hours.
        assertTrue(FindDurations.minuteOptions.max() <= 120)
    }
}
