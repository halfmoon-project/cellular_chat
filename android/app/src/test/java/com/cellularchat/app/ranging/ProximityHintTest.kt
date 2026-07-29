package com.cellularchat.app.ranging

import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.cbor.cborMapOf
import com.cellularchat.app.core.protocol.CapabilitySet
import com.cellularchat.app.core.protocol.SessionMsgType
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §12 `proximity_hint` (msgType 25). Only the BLE central has a link-RSSI API,
 * so without this message the peripheral-role device — deterministically the
 * same phone for a given pair (§9) — shows a permanently empty proximity screen
 * on a BLE-only pair.
 */
class ProximityHintTest {

    private class Recorder : RangingCoordinator.Output {
        val sent = mutableListOf<Pair<Long, CborMap>>()
        val proximities = mutableListOf<ProximityBand>()
        override fun onDirection(measurement: Measurement) = Unit
        override fun onDistance(measurement: Measurement) = Unit
        override fun onProximity(band: ProximityBand, trend: RssiTrend, confidence: TrendConfidence) {
            proximities.add(band)
        }
        override fun onRangingUnavailable(detail: String) = Unit
        override fun onTechnology(technology: Int) = Unit
        override fun onSignalLost() = Unit
        override fun sendSessionMessage(msgType: Long, body: CborMap) { sent.add(msgType to body) }
        override fun scheduleRetry(delayMillis: Long, action: () -> Unit) = Unit
    }

    private val android = CapabilitySet(CapabilitySet.OS_ANDROID, "16", "2.0.0")

    private fun start(): Pair<RangingCoordinator, Recorder> {
        val out = Recorder()
        val coordinator = RangingCoordinator(out)
        coordinator.select(android, android)   // no UWB either side -> ble_rssi
        coordinator.start(UUID.randomUUID(), oobInitiator = false)
        return coordinator to out
    }

    private fun hints(out: Recorder): List<Long> =
        out.sent.filter { it.first == SessionMsgType.PROXIMITY_HINT }
            .mapNotNull { (it.second[1L] as? CborInt)?.value }

    @Test
    fun bandChangeIsAnnouncedOnceNotPerSample() {
        val (coordinator, out) = start()
        repeat(10) { coordinator.feedRssi(-90, atMillis = it * 1000L) }
        repeat(10) { coordinator.feedRssi(-48, atMillis = (10 + it) * 1000L) }
        // far(1) then veryNear(3) — one message per band, not one per sample.
        assertEquals(listOf(1L, 3L), hints(out))
    }

    @Test
    fun aSteadyLinkAnnouncesNothingAfterTheFirstBand() {
        val (coordinator, out) = start()
        repeat(30) { coordinator.feedRssi(-90, atMillis = it * 1000L) }
        assertEquals(listOf(1L), hints(out))
    }

    @Test
    fun receivedHintIsDisplayedVerbatim() {
        val (coordinator, out) = start()
        coordinator.onSessionMessage(
            SessionMsgType.PROXIMITY_HINT, cborMapOf(1L to CborInt(3L)),
        )
        assertEquals(ProximityBand.VERY_NEAR, out.proximities.last())
    }

    @Test
    fun anUnknownBandCodeIsIgnored() {
        val (coordinator, out) = start()
        val before = out.proximities.size
        coordinator.onSessionMessage(
            SessionMsgType.PROXIMITY_HINT, cborMapOf(1L to CborInt(9L)),
        )
        assertEquals(before, out.proximities.size)
    }

    @Test
    fun theWireCodesMatchTheSpecTable() {
        // §5: 0=unknown 1=far 2=near 3=veryNear. Both platforms encode this.
        assertEquals(0L, ProximityBand.UNKNOWN.wireCode())
        assertEquals(1L, ProximityBand.FAR.wireCode())
        assertEquals(2L, ProximityBand.NEAR.wireCode())
        assertEquals(3L, ProximityBand.VERY_NEAR.wireCode())
        assertTrue(ProximityBand.entries.all { ProximityBand.fromWireCode(it.wireCode()) == it })
    }
}
