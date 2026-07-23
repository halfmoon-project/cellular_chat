package com.cellularchat.app

import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.core.protocol.FindEvent
import com.cellularchat.app.core.protocol.FindState
import com.cellularchat.app.core.protocol.FindStateMachine
import com.cellularchat.app.ranging.Measurement
import com.cellularchat.app.ranging.ProximityBand

/**
 * Observable snapshot of a Find session for the UI (single source of truth).
 * The Activity/Service never invents measurements: [measurement]/[proximity]
 * are cleared on `signalLost`, so stale values are never shown.
 */
data class FindUiState(
    val state: FindState = FindState.IDLE,
    val reason: Int = ReasonCodes.NORMAL,
    val measurement: Measurement? = null,
    val proximity: ProximityBand? = null,
    val deadlineMillis: Long? = null,
)

/**
 * Drives the shared Find reducer (PROTOCOL_V2.md §10) and owns the session's
 * observable state. Ties transport and ranging callbacks to reducer events;
 * every transition carries a §13 reason. Entering `signalLost` clears the last
 * measurement so no stale value is displayed.
 *
 * Pure with respect to time and radios: it only reduces events, so it is fully
 * unit-testable with fake capability/transport/ranging drivers.
 */
class FindSessionCoordinator(
    private val onState: (FindUiState) -> Unit,
) {
    @Volatile
    var uiState: FindUiState = FindUiState()
        private set

    /** Applies [event] with a §13 [reason]; ignores transitions the reducer rejects. */
    fun dispatch(event: FindEvent, reason: Int = ReasonCodes.NORMAL) = dispatchWith(event, reason)

    /**
     * Reduces [event]; on a valid transition applies [mutate] (e.g. attaching a
     * fresh measurement) and emits. Rejected transitions change nothing, so an
     * out-of-order sample never leaks a measurement onto a stale state.
     */
    @Synchronized
    private fun dispatchWith(
        event: FindEvent,
        reason: Int = ReasonCodes.NORMAL,
        mutate: (FindUiState) -> FindUiState = { it },
    ) {
        val transition = FindStateMachine.reduce(uiState.state, event)
        if (!transition.valid) return
        var next = mutate(uiState).copy(state = transition.next, reason = reason)
        if (transition.next == FindState.SIGNAL_LOST) {
            next = next.copy(measurement = null, proximity = null)
        }
        emit(next)
    }

    // --- Lifecycle (transport-independent) ---

    fun arm(deadlineMillis: Long) {
        dispatch(FindEvent.ARM)
        emit(uiState.copy(deadlineMillis = deadlineMillis))
        dispatch(FindEvent.ARMED)
    }

    fun onPeerFound() = dispatch(FindEvent.PEER_FOUND)

    fun onTransportConnected() = dispatch(FindEvent.TRANSPORT_CONNECTED)

    fun onAuthenticated() = dispatch(FindEvent.AUTHENTICATED)

    fun onRangingStarting() = dispatch(FindEvent.RANGING_STARTING)

    // --- Ranging samples (only fresh platform data reaches here) ---

    fun onDirection(measurement: Measurement) =
        dispatchWith(FindEvent.SAMPLE_DIRECTION) { it.copy(measurement = measurement, proximity = null) }

    fun onDistance(measurement: Measurement) =
        dispatchWith(FindEvent.SAMPLE_DISTANCE) { it.copy(measurement = measurement, proximity = null) }

    fun onProximity(band: ProximityBand) =
        dispatchWith(FindEvent.SAMPLE_PROXIMITY) { it.copy(measurement = null, proximity = band) }

    fun onRangingUnavailable() = dispatch(FindEvent.RANGING_UNAVAILABLE)

    // --- Loss / retry / termination ---

    fun onSignalLost(reason: Int = ReasonCodes.TRANSPORT_LOST) = dispatch(FindEvent.SIGNAL_LOST, reason)

    fun onRetryWait() = dispatch(FindEvent.RETRY_WAIT)

    fun onRetryElapsed() = dispatch(FindEvent.RETRY_ELAPSED)

    fun stop(reason: Int = ReasonCodes.USER_STOPPED) = dispatch(FindEvent.USER_STOP, reason)

    fun expire() = dispatch(FindEvent.DEADLINE, ReasonCodes.EXPIRED)

    fun fail(reason: Int) = dispatch(FindEvent.FATAL, reason)

    fun reset() = emit(FindUiState())

    private fun emit(next: FindUiState) {
        uiState = next
        onState(next)
    }
}
