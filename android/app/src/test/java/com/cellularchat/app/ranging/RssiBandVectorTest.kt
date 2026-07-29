package com.cellularchat.app.ranging

import com.cellularchat.app.core.Vectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consumes `shared/vectors/rssi_bands.json` (PROTOCOL_V2.md §12) against the
 * real [RssiProximityFilter]. The band parameters — window, median rule, entry
 * thresholds and hysteresis — used to live only in each app's code and had
 * silently diverged (this side was -60/-80 with 4 dB hysteresis and an
 * upper-middle median), so the two phones of one pair reported different bands
 * at the same distance. This fixture is the regression guard.
 */
class RssiBandVectorTest {

    private val fixture = Vectors.json("rssi_bands.json")

    /**
     * The filter's own defaults must BE the pinned §12 parameters — a fixture
     * that only passes with hand-supplied constructor arguments would not catch
     * a drifting default, which is exactly how the platforms diverged.
     */
    @Test
    fun defaultsMatchThePinnedParameters() {
        val p = fixture.getJSONObject("params")
        assertEquals(-55.0, p.getDouble("veryNearEntryDbm"), 0.0)
        assertEquals(-75.0, p.getDouble("nearEntryDbm"), 0.0)
        assertEquals(5.0, p.getDouble("hysteresisDb"), 0.0)
        assertEquals(5, p.getInt("window"))
        assertEquals("averaged-middle", p.getString("median"))
    }

    @Test
    fun everyCaseClassifiesIdenticallyToTheReference() {
        val cases = fixture.getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val samples = case.getJSONArray("samplesDbm")
            val expected = case.getJSONArray("expectedBands")
            // Default-constructed on purpose: the defaults are the contract.
            val filter = RssiProximityFilter()
            for (s in 0 until samples.length()) {
                // A fixed 1 s cadence keeps every sample inside the 10 s gap
                // reset, so the band path runs without the trend interfering.
                val band = filter.update(samples.getInt(s), atMillis = s * 1000L)
                assertEquals("$name[$s]", expected.getString(s), band.wireName())
            }
        }
    }

    private fun ProximityBand.wireName(): String = when (this) {
        ProximityBand.VERY_NEAR -> "veryNear"
        ProximityBand.NEAR -> "near"
        ProximityBand.FAR -> "far"
        ProximityBand.UNKNOWN -> "unknown"
    }
}
