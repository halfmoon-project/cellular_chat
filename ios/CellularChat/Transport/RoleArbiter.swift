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

    /// Two authenticated connections for one pair: keep the one whose Noise
    /// initiator has the bytewise smaller static key, close the other with
    /// `duplicate`. Returns true when the LOCAL-initiated connection wins.
    static func keepLocalInitiatedDuplicate(localStatic: [UInt8], peerStatic: [UInt8]) -> Bool {
        bytewiseLess(localStatic, peerStatic)
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
