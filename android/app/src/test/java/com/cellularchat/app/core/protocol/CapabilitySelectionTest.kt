package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.Vectors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilitySelectionTest {
    private val v = Vectors.json("capability_selection.json")

    private fun capability(obj: JSONObject): CapabilitySet = CapabilitySet(
        os = if (obj.getString("os") == "ios") CapabilitySet.OS_IOS else CapabilitySet.OS_ANDROID,
        osVersion = "",
        appVersion = "",
        uwbPresent = obj.getBoolean("uwbPresent"),
        uwbAzimuth = obj.getBoolean("uwbAzimuth"),
        appleInteropUwb = obj.getBoolean("appleInteropUwb"),
        niEdm = obj.getBoolean("niEdm"),
    )

    @Test
    fun selectionMatchesFixtureAndIsSymmetric() {
        val cases = v.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val local = capability(case.getJSONObject("local"))
            val peer = capability(case.getJSONObject("peer"))
            val expectedMethod = case.getInt("method")
            val expectedEdm = case.getBoolean("edm")

            val forward = RangingSelector.select(local, peer)
            assertEquals("case $i method", expectedMethod, forward.method)
            assertEquals("case $i edm", expectedEdm, forward.edm)

            val reverse = RangingSelector.select(peer, local)
            assertEquals("case $i method symmetric", expectedMethod, reverse.method)
            assertEquals("case $i edm symmetric", expectedEdm, reverse.edm)
        }
    }
}
