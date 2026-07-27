import Foundation
import UIKit
import CellularChatCore

/// This device's self-declared display name (PROTOCOL_V2.md §11 `deviceName`),
/// shown to the peer so an unnamed pair labels itself. Defaults to the OS name;
/// on iOS that is just "iPhone" without the user-assigned-device-name
/// entitlement, so the user can override it in the people list.
enum LocalDeviceName {
    private static let key = "localDeviceName"

    static var current: String {
        get {
            let stored = UserDefaults.standard.string(forKey: key)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return stored.isEmpty ? UIDevice.current.name : stored
        }
        // A blank value is stored as blank and falls back on read, so clearing
        // the field while typing does not snap back to the OS name mid-edit.
        set {
            UserDefaults.standard.set(String(newValue.prefix(CapabilitySet.maxDeviceNameLength)),
                                      forKey: key)
        }
    }
}
