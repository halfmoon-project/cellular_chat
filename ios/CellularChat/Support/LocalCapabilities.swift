import Foundation
import CellularChatCore
import CoreBluetooth
import NearbyInteraction
import WiFiAware

/// Builds the authenticated `CapabilitySet` (PROTOCOL_V2.md §11) from real
/// runtime capability checks — never from OS-version guesses. Every framework
/// touch is gated by that framework's own capability API.
enum LocalCapabilities {

    static func current() -> CapabilitySet {
        let ni = NISession.deviceCapabilities
        let precise = ni.supportsPreciseDistanceMeasurement

        // appleInteropUwb is true on iOS only for 26.1+ AND only when the radio
        // actually reports precise distance support (§11).
        var interop = false
        if #available(iOS 26.1, *) { interop = precise }

        return CapabilitySet(
            os: .ios,
            osVersion: ProcessInfo.processInfo.operatingSystemVersionString,
            appVersion: appVersion(),
            wifiAware: wifiAwareAvailable(),
            nearbyConnections: false,          // no official iOS Nearby Connections SDK
            bleCentral: bleCentralAvailable(),
            blePeripheral: true,               // iOS can act as GATT peripheral
            uwbPresent: precise,
            uwbAzimuth: ni.supportsDirectionMeasurement,
            uwbElevation: false,               // NI has no elevation capability flag
            appleInteropUwb: interop,
            niEdm: ni.supportsExtendedDistanceMeasurement,
            wifiRtt: false,                    // no public iOS Wi-Fi RTT API
            backgroundRanging: false)          // no runtime probe; kept conservative
    }

    static func wifiAwareAvailable() -> Bool {
        WACapabilities.supportedFeatures.contains(.wifiAware)
    }

    static func bleCentralAvailable() -> Bool {
        switch CBCentralManager.authorization {
        case .denied, .restricted: return false
        default: return true
        }
    }

    private static func appVersion() -> String {
        (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "0"
    }
}
