import CellularChatCore

/// Per-pair feature availability for the Find screen: which ranging technology
/// is in use and, when it is the coarse fallback, why the better one is not.
/// Derived from capability booleans ONLY (PROTOCOL_V2.md §11: "never
/// OS-version guesses" — `osVersion` is a free-form string, never parsed).
struct PairFeatureStatus: Equatable {
    /// Technology label, always shown while a session is armed.
    let rangingLabel: String
    /// Why the precise method is unavailable; nil when nothing to explain.
    let rangingReason: String?
    /// Transport note for pairs that can never leave BLE; nil otherwise.
    let transportNote: String?

    static func derive(local: CapabilitySet,
                       peer: CapabilitySet?,
                       selection: RangingSelection?) -> PairFeatureStatus {
        let uwbActive: Bool
        switch selection?.method {
        case .niPeer, .uwbAppleInterop, .uwbAndroidOob: uwbActive = true
        case .bleRssi, nil: uwbActive = false
        }

        let reason: String?
        if uwbActive {
            reason = nil
        } else if let peer {
            let crossPlatform = local.os != peer.os
            if !peer.uwbPresent {
                reason = "상대 기기에 UWB 없음"
            } else if crossPlatform && !peer.appleInteropUwb {
                reason = "상대 기기가 iPhone-Android 간 UWB 미지원"
            } else if !local.uwbPresent {
                reason = "이 기기에 UWB 없음"
            } else if crossPlatform && !local.appleInteropUwb {
                reason = "이 기기가 iPhone-Android 간 UWB 미지원"
            } else {
                reason = nil
            }
        } else {
            reason = "상대 기기 기능 확인 전"
        }

        // Same predicate as the §10 upgrade eligibility: a pair without mutual
        // Wi-Fi Aware stays on BLE for good — say so instead of looking stuck.
        let transportNote: String?
        if let peer, !(local.wifiAware && peer.wifiAware) {
            transportNote = "기본 연결(BLE)로 동작 중"
        } else {
            transportNote = nil
        }

        return PairFeatureStatus(
            rangingLabel: uwbActive ? "UWB 정밀 거리" : "신호세기 기반(대략적)",
            rangingReason: reason,
            transportNote: transportNote)
    }

    /// Single secondary-style line for the Find screen.
    var line: String {
        ([rangingLabel] + [rangingReason, transportNote].compactMap { $0 })
            .joined(separator: " · ")
    }
}
