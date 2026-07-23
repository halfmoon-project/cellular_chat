package com.cellularchat.app.ranging

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffScheduleTest {
    @Test
    fun doublesFromFiveSecondsAndCapsAtSixty() {
        val backoff = BackoffSchedule()
        assertEquals(5_000, backoff.nextDelayMillis())
        assertEquals(10_000, backoff.nextDelayMillis())
        assertEquals(20_000, backoff.nextDelayMillis())
        assertEquals(40_000, backoff.nextDelayMillis())
        assertEquals(60_000, backoff.nextDelayMillis())
        assertEquals(60_000, backoff.nextDelayMillis())
    }

    @Test
    fun resetReturnsToInitial() {
        val backoff = BackoffSchedule()
        backoff.nextDelayMillis()
        backoff.nextDelayMillis()
        backoff.reset()
        assertEquals(5_000, backoff.nextDelayMillis())
    }
}
