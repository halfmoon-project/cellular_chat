package com.cellularchat.app

import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.core.protocol.FindState
import com.cellularchat.app.core.protocol.RangingMethod
import com.cellularchat.app.ranging.Measurement
import com.cellularchat.app.ranging.ProximityBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FindSessionCoordinatorTest {
    private fun coordinator(): Pair<FindSessionCoordinator, () -> FindUiState> {
        var last = FindUiState()
        val c = FindSessionCoordinator { last = it }
        return c to { last }
    }

    @Test
    fun happyPathToDirectionAvailable() {
        val (c, state) = coordinator()
        c.arm(deadlineMillis = 1_000)
        assertEquals(FindState.SEARCHING, state().state)
        assertEquals(1_000L, state().deadlineMillis)

        c.onPeerFound()
        assertEquals(FindState.P2P_CONNECTING, state().state)
        c.onTransportConnected()
        assertEquals(FindState.AUTHENTICATING, state().state)
        c.onAuthenticated()
        assertEquals(FindState.CONNECTED, state().state)
        c.onRangingStarting()
        assertEquals(FindState.RANGING_STARTING, state().state)

        c.onDirection(Measurement(RangingMethod.UWB_ANDROID_OOB, distanceMeters = 2.0, azimuthDegrees = 30.0))
        assertEquals(FindState.DIRECTION_AVAILABLE, state().state)
        assertEquals(30.0, state().measurement?.azimuthDegrees)
        assertNull(state().proximity)
    }

    @Test
    fun signalLostClearsMeasurementAndCarriesReason() {
        val (c, state) = coordinator()
        c.arm(1_000)
        c.onPeerFound(); c.onTransportConnected(); c.onAuthenticated(); c.onRangingStarting()
        c.onDistance(Measurement(RangingMethod.UWB_ANDROID_OOB, distanceMeters = 3.0))
        assertEquals(FindState.DISTANCE_ONLY, state().state)

        c.onSignalLost(ReasonCodes.TRANSPORT_LOST)
        assertEquals(FindState.SIGNAL_LOST, state().state)
        assertEquals(ReasonCodes.TRANSPORT_LOST, state().reason)
        assertNull("stale measurement must be cleared", state().measurement)
        assertNull(state().proximity)
    }

    @Test
    fun proximityOnlyPathSetsBandNotMeasurement() {
        val (c, state) = coordinator()
        c.arm(1_000)
        c.onPeerFound(); c.onTransportConnected(); c.onAuthenticated(); c.onRangingStarting()
        c.onProximity(ProximityBand.NEAR)
        assertEquals(FindState.PROXIMITY_ONLY, state().state)
        assertEquals(ProximityBand.NEAR, state().proximity)
        assertNull(state().measurement)
    }

    @Test
    fun retryWaitReturnsToSearching() {
        val (c, state) = coordinator()
        c.arm(1_000)
        c.onPeerFound(); c.onTransportConnected(); c.onAuthenticated()
        c.onSignalLost()
        assertEquals(FindState.SIGNAL_LOST, state().state)
        c.onRetryWait()
        assertEquals(FindState.RETRY_WAIT, state().state)
        c.onRetryElapsed()
        assertEquals(FindState.SEARCHING, state().state)
    }

    @Test
    fun invalidTransitionsAreIgnored() {
        val (c, state) = coordinator()
        // A sample before arming is not a valid transition from idle.
        c.onDirection(Measurement(RangingMethod.BLE_RSSI))
        assertEquals(FindState.IDLE, state().state)
        assertNull(state().measurement)
    }

    @Test
    fun userStopIsTerminalWithReason() {
        val (c, state) = coordinator()
        c.arm(1_000)
        c.stop(ReasonCodes.USER_STOPPED)
        assertEquals(FindState.STOPPED, state().state)
        assertEquals(ReasonCodes.USER_STOPPED, state().reason)
    }
}
