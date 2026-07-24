package com.cellularchat.app.ranging

import com.cellularchat.app.core.Vectors
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.cbor.cborMapOf
import com.cellularchat.app.core.hex
import com.cellularchat.app.core.protocol.CapabilitySet
import com.cellularchat.app.core.protocol.RangingMethod
import com.cellularchat.app.core.protocol.SessionMsgType
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingOutput : RangingCoordinator.Output {
    val proximities = mutableListOf<ProximityBand>()
    var directions = 0
    var distances = 0
    var unavailable: String? = null

    override fun onDirection(measurement: Measurement) { directions++ }
    override fun onDistance(measurement: Measurement) { distances++ }
    override fun onProximity(band: ProximityBand, trend: RssiTrend, confidence: TrendConfidence) {
        proximities.add(band)
    }
    override fun onRangingUnavailable(detail: String) { unavailable = detail }
    override fun onSignalLost() = Unit
    override fun onTechnology(technology: Int) = Unit
    override fun sendSessionMessage(msgType: Long, body: CborMap) = Unit
    override fun scheduleRetry(delayMillis: Long, action: () -> Unit) = Unit
}

/**
 * Consumes `shared/vectors/duplicate_ops.json` (PROTOCOL_V2.md §10:379-382,
 * §12:469-470). The (sid, attemptId)-keyed ranging_offer/accept/start/stop
 * lifecycle is a `ni_peer` exchange that only ever occurs iOS<->iOS; the Android
 * `RangingCoordinator` is always the OOB controller, so its correct honoring of
 * the idempotency rule is to treat every one of those inbound messages as a no-op
 * — producing no session effect and no error — while its own fallback keeps
 * working and a duplicate stop stays harmless.
 */
class DuplicateOpsTest {
    private val android = CapabilitySet(CapabilitySet.OS_ANDROID, "16", "2.0.0")
    private val fixture = Vectors.json("duplicate_ops.json")

    private fun opToMsgType(op: String): Long = when (op) {
        "ranging_offer" -> SessionMsgType.RANGING_OFFER
        "ranging_accept" -> SessionMsgType.RANGING_ACCEPT
        "ranging_start" -> SessionMsgType.RANGING_START
        "ranging_stop" -> SessionMsgType.RANGING_STOP
        "ranging_error" -> SessionMsgType.RANGING_ERROR
        else -> error("unknown op $op")
    }

    @Test
    fun niPeerLifecycleOpsAreIdempotentNoOpsOnAndroid() {
        assertEquals(16, hex(fixture.getString("sidHex")).size)
        val cases = fixture.getJSONArray("cases")
        assertTrue("fixture has no cases", cases.length() > 0)

        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val ops = case.getJSONArray("ops")

            val output = RecordingOutput()
            val coordinator = RangingCoordinator(output)   // no UWB controllers
            assertEquals(RangingMethod.BLE_RSSI, coordinator.select(android, android))
            coordinator.start(UUID.randomUUID(), oobInitiator = false)

            for (j in 0 until ops.length()) {
                val op = ops.getJSONObject(j)
                val body = cborMapOf(1L to CborInt(op.getLong("attemptId")))
                // Must not throw and must produce no ranging output.
                coordinator.onSessionMessage(opToMsgType(op.getString("op")), body)
            }

            assertEquals("$name emitted a direction", 0, output.directions)
            assertEquals("$name emitted a distance", 0, output.distances)
            assertNull("$name flagged ranging unavailable", output.unavailable)

            // The coordinator is not wedged: its RSSI fallback still drives the UI.
            repeat(5) { coordinator.feedRssi(-50) }
            assertEquals("$name proximity", ProximityBand.VERY_NEAR, output.proximities.last())

            // A duplicate stop is a no-op, never an error.
            coordinator.stop()
            coordinator.stop()
        }
    }
}
