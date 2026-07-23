import Foundation

/// Logical Find session state machine (PROTOCOL_V2.md §10). Both platforms
/// implement the identical reducer; conformance is state_transitions.json.
public enum FindState: String, CaseIterable, Equatable, Sendable {
    case idle, arming, searching, p2pConnecting, authenticating, connected
    case rangingStarting, directionAvailable, distanceOnly, proximityOnly, connectedOnly
    case signalLost, retryWait, stopped, expired, failed
}

public enum FindEvent: String, CaseIterable, Equatable, Sendable {
    case arm = "ARM"
    case armed = "ARMED"
    case peerFound = "PEER_FOUND"
    case transportConnected = "TRANSPORT_CONNECTED"
    case authenticated = "AUTHENTICATED"
    case rangingStarting = "RANGING_STARTING"
    case sampleDirection = "SAMPLE_DIRECTION"
    case sampleDistance = "SAMPLE_DISTANCE"
    case sampleProximity = "SAMPLE_PROXIMITY"
    case rangingUnavailable = "RANGING_UNAVAILABLE"
    case signalLost = "SIGNAL_LOST"
    case retryWait = "RETRY_WAIT"
    case retryElapsed = "RETRY_ELAPSED"
    case userStop = "USER_STOP"
    case deadline = "DEADLINE"
    case fatal = "FATAL"
}

public enum FindStateMachine {

    private static let terminal: Set<FindState> = [.stopped, .expired, .failed]
    private static let rangingStates: Set<FindState> = [
        .rangingStarting, .directionAvailable, .distanceOnly, .proximityOnly, .connectedOnly,
    ]

    /// Returns the next state, or nil if the (state, event) pair is invalid.
    public static func reduce(_ state: FindState, _ event: FindEvent) -> FindState? {
        // Terminal and idle states allow only their explicit transitions.
        switch (state, event) {
        case (.idle, .arm): return .arming
        case (.arming, .armed): return .searching
        case (.searching, .peerFound): return .p2pConnecting
        case (.p2pConnecting, .transportConnected): return .authenticating
        case (.authenticating, .authenticated): return .connected
        case (.connected, .rangingStarting): return .rangingStarting
        case (.connectedOnly, .rangingStarting): return .rangingStarting
        case (.signalLost, .retryWait): return .retryWait
        case (.retryWait, .retryElapsed): return .searching
        default:
            break
        }

        // RANGING_UNAVAILABLE from connected / ranging (non-connectedOnly) -> connectedOnly.
        if event == .rangingUnavailable,
           state == .connected || (rangingStates.contains(state) && state != .connectedOnly) {
            return .connectedOnly
        }

        // Sample events from ranging states (excluding connectedOnly).
        let sampleSources: Set<FindState> = [
            .rangingStarting, .directionAvailable, .distanceOnly, .proximityOnly,
        ]
        if sampleSources.contains(state) {
            switch event {
            case .sampleDirection: return .directionAvailable
            case .sampleDistance: return .distanceOnly
            case .sampleProximity: return .proximityOnly
            default: break
            }
        }

        // SIGNAL_LOST from connected | ranging | p2pConnecting | authenticating.
        if event == .signalLost {
            let linked: Set<FindState> = rangingStates.union([.connected, .p2pConnecting, .authenticating])
            if linked.contains(state) { return .signalLost }
        }

        // USER_STOP / DEADLINE / FATAL from any non-terminal, non-idle state.
        if state != .idle && !terminal.contains(state) {
            switch event {
            case .userStop: return .stopped
            case .deadline: return .expired
            case .fatal: return .failed
            default: break
            }
        }

        return nil
    }
}
