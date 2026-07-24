import CellularChatCore

/// Short Korean camera-assist coaching (IMPLEMENTATION_PLAN §12, item 7). When a
/// UWB method is active and the platform supports camera assistance, the NISession
/// runs its convergence/coaching algorithm to resolve direction — it needs the
/// user to sweep the phone. This surfaces that as text guidance only (never AR UI)
/// while a direction is not yet resolved, and stays silent once it is.
enum FindCoaching {

    static func cameraAssistText(method: RangingMethod?,
                                 state: FindState,
                                 cameraAssistSupported: Bool) -> String? {
        guard cameraAssistSupported,
              method == .niPeer || method == .uwbAppleInterop else { return nil }
        switch state {
        case .rangingStarting, .distanceOnly, .connectedOnly:
            return "기기를 세운 채 좌우로 천천히 움직이면 UWB 방향 정확도가 올라갑니다."
        default:
            return nil   // directionAvailable = converged; other states = not ranging yet
        }
    }
}
