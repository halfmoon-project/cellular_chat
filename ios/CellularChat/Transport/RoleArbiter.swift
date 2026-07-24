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
    /// Noise initiator, AND Wi-Fi Aware subscriber, all tied to the same pinned
    /// keys so two same-platform peers pick opposite roles (§4/§9/§10)? A
    /// cross-platform or not-yet-known peer keeps the default iOS-initiator
    /// direction; a known same-platform (iOS) peer derives it from the keys.
    static func localIsInitiatorSide(peerPlatform: OSKind?,
                                     localStatic: [UInt8], peerStatic: [UInt8]) -> Bool {
        guard peerPlatform == .ios else { return true }
        return localIsBLECentral(localOS: .ios, peerOS: .ios,
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
