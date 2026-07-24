package com.cellularchat.app.ranging

import com.cellularchat.app.core.cbor.CborBytes
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.cbor.cborMapOf
import com.cellularchat.app.core.protocol.CapabilitySet
import com.cellularchat.app.core.protocol.RangingMethod
import com.cellularchat.app.core.protocol.SessionMsgType
import org.junit.Assert.assertEquals
import org.junit.Test

private class MismatchOutput : RangingCoordinator.Output {
    var mismatches = 0
    override fun onDirection(measurement: Measurement) = Unit
    override fun onDistance(measurement: Measurement) = Unit
    override fun onProximity(band: ProximityBand, trend: RssiTrend, confidence: TrendConfidence) = Unit
    override fun onRangingUnavailable(detail: String) = Unit
    override fun onSignalLost() = Unit
    override fun onTechnology(technology: Int) = Unit
    override fun sendSessionMessage(msgType: Long, body: CborMap) = Unit
    override fun scheduleRetry(delayMillis: Long, action: () -> Unit) = Unit
    override fun onCapabilityMismatch() { mismatches++ }
}

/**
 * Feature B.2.2–B.2.4: the ranging intake raises `capabilityMismatch` (never a
 * silent drop) for a method outside the mutually-supported set, an accept that
 * diverges from its offer, or an implicit offer whose implied method is
 * unsupported.
 */
class RangingCapabilityMismatchTest {
    private val android = CapabilitySet(CapabilitySet.OS_ANDROID, "16", "2.0.0")
    private val androidUwb = android.copy(uwbPresent = true)

    private fun coordinator(output: MismatchOutput) = RangingCoordinator(output)

    @Test
    fun offerForUnsupportedMethodRaisesMismatch() {
        val output = MismatchOutput()
        val c = coordinator(output)
        c.select(android, android) // BLE_RSSI only
        c.onSessionMessage(
            SessionMsgType.RANGING_OFFER,
            cborMapOf(1L to CborInt(1), 2L to CborInt(RangingMethod.NI_PEER.toLong())),
        )
        assertEquals(1, output.mismatches)
    }

    @Test
    fun acceptDivergingFromOfferRaisesMismatch() {
        val output = MismatchOutput()
        val c = coordinator(output)
        c.select(androidUwb, androidUwb) // UWB_ANDROID_OOB supported
        c.onSessionMessage(
            SessionMsgType.RANGING_OFFER,
            cborMapOf(1L to CborInt(1), 2L to CborInt(RangingMethod.UWB_ANDROID_OOB.toLong())),
        )
        assertEquals("supported offer is accepted", 0, output.mismatches)
        // A ble_rssi accept is itself supported, but it diverges from the offer.
        c.onSessionMessage(
            SessionMsgType.RANGING_ACCEPT,
            cborMapOf(1L to CborInt(1), 2L to CborInt(RangingMethod.BLE_RSSI.toLong())),
        )
        assertEquals(1, output.mismatches)
    }

    @Test
    fun appleConfigImplyingUnsupportedMethodRaisesMismatch() {
        val output = MismatchOutput()
        val c = coordinator(output)
        c.select(android, android) // not mixed OS, no interop
        c.onSessionMessage(
            SessionMsgType.APPLE_CONFIG,
            cborMapOf(1L to CborInt(1), 2L to CborBytes(ByteArray(48))),
        )
        assertEquals(1, output.mismatches)
    }

    @Test
    fun oobDataImplyingUnsupportedMethodRaisesMismatch() {
        val output = MismatchOutput()
        val c = coordinator(output)
        c.select(android, android) // no UWB present -> uwb_android_oob unsupported
        c.onSessionMessage(
            SessionMsgType.OOB_DATA,
            cborMapOf(1L to CborInt(1), 2L to CborBytes(ByteArray(8))),
        )
        assertEquals(1, output.mismatches)
    }

    @Test
    fun validOfferAndMatchingAcceptDoNotRaise() {
        val output = MismatchOutput()
        val c = coordinator(output)
        c.select(androidUwb, androidUwb)
        c.onSessionMessage(
            SessionMsgType.RANGING_OFFER,
            cborMapOf(1L to CborInt(1), 2L to CborInt(RangingMethod.UWB_ANDROID_OOB.toLong())),
        )
        c.onSessionMessage(
            SessionMsgType.RANGING_ACCEPT,
            cborMapOf(1L to CborInt(1), 2L to CborInt(RangingMethod.UWB_ANDROID_OOB.toLong())),
        )
        assertEquals(0, output.mismatches)
    }
}
