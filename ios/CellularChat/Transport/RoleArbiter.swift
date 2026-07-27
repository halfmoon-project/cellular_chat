import Foundation
import CellularChatCore

/// Deterministic connection-ownership tie-breaks (PROTOCOL_V2.md §9/§10).
/// Pure functions so both sides compute the identical result and so they can be
/// unit-tested without radios.
enum RoleArbiter {

    /// Is the local device the BLE central (Noise initiator)? Cross-platform
    /// pairs put iOS as central; same-platform pairs give it to the bytewise
    /// smaller pinned static public key.
    static func localIsBLECentral(localOS: OSKind, peerOS: OSKind,
                                  localStatic: [UInt8], peerStatic: [UInt8]) -> Bool {
        if localOS != peerOS { return localOS == .ios }
        return bytewiseLess(localStatic, peerStatic)
    }

    /// Is the local device the initiator side of the Find session — BLE central,
    /// Noise initiator, AND Wi-Fi Aware subscriber, all from the same pinned keys
    /// so the two sides always pick opposite roles (§4/§9/§10)?
    ///
    /// The peer's platform is deliberately NOT consulted. It is only ever learned
    /// from a completed session, so the old "unknown peer ⇒ I am the initiator"
    /// default made two iPhones both pick BLE central: neither advertised, and
    /// Find sat in `searching` until the deadline — with no way out, since the
    /// platform is only learned by connecting. The key tie-break needs no prior
    /// knowledge and is exactly what Android computes today, so both platforms
    /// agree on the first attempt.
    ///
    /// ponytail: this drops §9's cross-platform "iOS is the central" preference,
    /// so ~half of iPhone↔Android pairs put the iPhone in the peripheral role,
    /// where background advertising moves to the overflow area that an Android
    /// central cannot see (foreground still works, and that half is fully
    /// deadlocked today). Restore the preference by persisting the peer OS on
    /// BOTH platforms — iOS already does via `PairStore.setPeerPlatform`; Android
    /// must do the same from the session_ready CapabilitySet and pass
    /// `peerIsIos` into `TransportCandidateFactory.candidates` — and only then
    /// re-introduce the platform branch here. Flipping one side alone re-creates
    /// the same both-central deadlock.
    static func localIsInitiatorSide(localStatic: [UInt8], peerStatic: [UInt8]) -> Bool {
        localIsBLECentral(localOS: .ios, peerOS: .ios,
                          localStatic: localStatic, peerStatic: peerStatic)
    }

    /// Two authenticated connections for one pair: keep the one whose Noise
    /// initiator has the bytewise smaller static key, close the other with
    /// `duplicate`. Returns true when the LOCAL-initiated connection wins.
    static func keepLocalInitiatedDuplicate(localStatic: [UInt8], peerStatic: [UInt8]) -> Bool {
        bytewiseLess(localStatic, peerStatic)
    }

    /// Resolve a §10 duplicate from this device's view: given the local device is
    /// the Noise initiator of the NEW connection iff `localInitiatedNew`, does the
    /// new connection win over a standing one for the same pair? The winner is the
    /// connection whose Noise initiator holds the bytewise-smaller static key, so a
    /// peer-initiated new connection wins exactly when the local one would not.
    static func keepNewDuplicate(localInitiatedNew: Bool,
                                 localStatic: [UInt8], peerStatic: [UInt8]) -> Bool {
        localInitiatedNew == keepLocalInitiatedDuplicate(localStatic: localStatic, peerStatic: peerStatic)
    }

    static func bytewiseLess(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        let n = min(a.count, b.count)
        var i = 0
        while i < n {
            if a[i] != b[i] { return a[i] < b[i] }
            i += 1
        }
        return a.count < b.count
    }
}
