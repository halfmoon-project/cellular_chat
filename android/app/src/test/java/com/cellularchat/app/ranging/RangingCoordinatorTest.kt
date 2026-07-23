package com.cellularchat.app.ranging

import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.protocol.CapabilitySet
import com.cellularchat.app.core.protocol.RangingMethod
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeOutput : RangingCoordinator.Output {
    val proximities = mutableListOf<ProximityBand>()
    var unavailable: String? = null
    var directions = 0
    var scheduledDelay: Long? = null
    private var scheduledAction: (() -> Unit)? = null

    override fun onDirection(measurement: Measurement) { directions++ }
    override fun onDistance(measurement: Measurement) = Unit
    override fun onProximity(band: ProximityBand) { proximities.add(band) }
    override fun onRangingUnavailable(detail: String) { unavailable = detail }
    override fun onSignalLost() = Unit
    override fun sendSessionMessage(msgType: Long, body: CborMap) = Unit
    override fun scheduleRetry(delayMillis: Long, action: () -> Unit) {
        scheduledDelay = delayMillis
        scheduledAction = action
    }

    fun runScheduled() { scheduledAction?.invoke() }
}

class RangingCoordinatorTest {
    private val android = CapabilitySet(CapabilitySet.OS_ANDROID, "16", "2.0.0")
    private val androidUwb = android.copy(uwbPresent = true)

    @Test
    fun selectsRssiWhenNoUwbAndBandsDriveTheUi() {
        val output = FakeOutput()
        val coordinator = RangingCoordinator(output)
        assertEquals(RangingMethod.BLE_RSSI, coordinator.select(android, android))
        coordinator.start(UUID.randomUUID(), oobInitiator = false)
        repeat(5) { coordinator.feedRssi(-50) }
        assertEquals(ProximityBand.VERY_NEAR, output.proximities.last())
    }

    @Test
    fun uwbMethodWithoutControllerDegradesToProximityAndRetries() {
        val output = FakeOutput()
        // Both Android + UWB present selects the OOB path; with no injected
        // controller it must fall back to proximity and schedule a UWB retry.
        val coordinator = RangingCoordinator(output, rawUwb = null, androidOob = null)
        assertEquals(RangingMethod.UWB_ANDROID_OOB, coordinator.select(androidUwb, androidUwb))
        coordinator.start(UUID.randomUUID(), oobInitiator = true)
        assertTrue(output.unavailable != null)
        assertEquals(5_000L, output.scheduledDelay)

        // Once in the fallback, RSSI drives proximity.
        repeat(5) { coordinator.feedRssi(-90) }
        assertEquals(ProximityBand.FAR, output.proximities.last())
    }

    @Test
    fun stopHaltsProximityUpdates() {
        val output = FakeOutput()
        val coordinator = RangingCoordinator(output)
        coordinator.select(android, android)
        coordinator.start(UUID.randomUUID(), oobInitiator = false)
        coordinator.stop()
        val before = output.proximities.size
        coordinator.feedRssi(-40)
        assertEquals(before, output.proximities.size)
    }
}
