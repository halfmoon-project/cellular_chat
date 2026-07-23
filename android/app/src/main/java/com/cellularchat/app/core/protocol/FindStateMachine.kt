package com.cellularchat.app.core.protocol

/** Logical Find session states (PROTOCOL_V2.md §10). [wire] matches the fixtures. */
enum class FindState(val wire: String) {
    IDLE("idle"),
    ARMING("arming"),
    SEARCHING("searching"),
    P2P_CONNECTING("p2pConnecting"),
    AUTHENTICATING("authenticating"),
    CONNECTED("connected"),
    RANGING_STARTING("rangingStarting"),
    DIRECTION_AVAILABLE("directionAvailable"),
    DISTANCE_ONLY("distanceOnly"),
    PROXIMITY_ONLY("proximityOnly"),
    CONNECTED_ONLY("connectedOnly"),
    SIGNAL_LOST("signalLost"),
    RETRY_WAIT("retryWait"),
    STOPPED("stopped"),
    EXPIRED("expired"),
    FAILED("failed");

    companion object {
        fun fromWire(wire: String): FindState =
            entries.first { it.wire == wire }
    }
}

enum class FindEvent {
    ARM, ARMED, PEER_FOUND, TRANSPORT_CONNECTED, AUTHENTICATED, RANGING_STARTING,
    SAMPLE_DIRECTION, SAMPLE_DISTANCE, SAMPLE_PROXIMITY, RANGING_UNAVAILABLE,
    SIGNAL_LOST, RETRY_WAIT, RETRY_ELAPSED, USER_STOP, DEADLINE, FATAL;

    companion object {
        fun fromWire(wire: String): FindEvent = valueOf(wire)
    }
}

data class FindTransition(val next: FindState, val valid: Boolean)

/** Deterministic Find reducer; conformance fixtures in state_transitions.json. */
object FindStateMachine {
    fun reduce(state: FindState, event: FindEvent): FindTransition {
        val next = when (state) {
            FindState.IDLE -> when (event) {
                FindEvent.ARM -> FindState.ARMING
                else -> null
            }
            FindState.ARMING -> when (event) {
                FindEvent.ARMED -> FindState.SEARCHING
                else -> terminal(event)
            }
            FindState.SEARCHING -> when (event) {
                FindEvent.PEER_FOUND -> FindState.P2P_CONNECTING
                else -> terminal(event)
            }
            FindState.P2P_CONNECTING -> when (event) {
                FindEvent.TRANSPORT_CONNECTED -> FindState.AUTHENTICATING
                FindEvent.SIGNAL_LOST -> FindState.SIGNAL_LOST
                else -> terminal(event)
            }
            FindState.AUTHENTICATING -> when (event) {
                FindEvent.AUTHENTICATED -> FindState.CONNECTED
                FindEvent.SIGNAL_LOST -> FindState.SIGNAL_LOST
                else -> terminal(event)
            }
            FindState.CONNECTED -> when (event) {
                FindEvent.RANGING_STARTING -> FindState.RANGING_STARTING
                FindEvent.RANGING_UNAVAILABLE -> FindState.CONNECTED_ONLY
                FindEvent.SIGNAL_LOST -> FindState.SIGNAL_LOST
                else -> terminal(event)
            }
            FindState.RANGING_STARTING,
            FindState.DIRECTION_AVAILABLE,
            FindState.DISTANCE_ONLY,
            FindState.PROXIMITY_ONLY -> when (event) {
                FindEvent.SAMPLE_DIRECTION -> FindState.DIRECTION_AVAILABLE
                FindEvent.SAMPLE_DISTANCE -> FindState.DISTANCE_ONLY
                FindEvent.SAMPLE_PROXIMITY -> FindState.PROXIMITY_ONLY
                FindEvent.RANGING_UNAVAILABLE -> FindState.CONNECTED_ONLY
                FindEvent.SIGNAL_LOST -> FindState.SIGNAL_LOST
                else -> terminal(event)
            }
            FindState.CONNECTED_ONLY -> when (event) {
                FindEvent.RANGING_STARTING -> FindState.RANGING_STARTING
                FindEvent.SIGNAL_LOST -> FindState.SIGNAL_LOST
                else -> terminal(event)
            }
            FindState.SIGNAL_LOST -> when (event) {
                FindEvent.RETRY_WAIT -> FindState.RETRY_WAIT
                else -> terminal(event)
            }
            FindState.RETRY_WAIT -> when (event) {
                FindEvent.RETRY_ELAPSED -> FindState.SEARCHING
                else -> terminal(event)
            }
            FindState.STOPPED, FindState.EXPIRED, FindState.FAILED -> null
        }
        return if (next == null) FindTransition(state, false) else FindTransition(next, true)
    }

    /** USER_STOP/DEADLINE/FATAL are accepted from every non-terminal active state. */
    private fun terminal(event: FindEvent): FindState? = when (event) {
        FindEvent.USER_STOP -> FindState.STOPPED
        FindEvent.DEADLINE -> FindState.EXPIRED
        FindEvent.FATAL -> FindState.FAILED
        else -> null
    }
}
