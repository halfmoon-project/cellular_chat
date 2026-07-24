import Foundation

public enum OSKind: UInt64, Equatable {
    case android = 1
    case ios = 2
}

/// CapabilitySet (PROTOCOL_V2.md §11). Unknown keys are ignored; missing keys
/// default to false/empty (forward compatibility).
public struct CapabilitySet: Equatable {
    public var os: OSKind
    public var osVersion: String
    public var appVersion: String
    public var wifiAware: Bool
    public var nearbyConnections: Bool
    public var bleCentral: Bool
    public var blePeripheral: Bool
    public var uwbPresent: Bool
    public var uwbAzimuth: Bool
    public var uwbElevation: Bool
    public var appleInteropUwb: Bool
    public var niEdm: Bool
    public var wifiRtt: Bool
    public var backgroundRanging: Bool

    public init(os: OSKind,
                osVersion: String = "",
                appVersion: String = "",
                wifiAware: Bool = false,
                nearbyConnections: Bool = false,
                bleCentral: Bool = false,
                blePeripheral: Bool = false,
                uwbPresent: Bool = false,
                uwbAzimuth: Bool = false,
                uwbElevation: Bool = false,
                appleInteropUwb: Bool = false,
                niEdm: Bool = false,
                wifiRtt: Bool = false,
                backgroundRanging: Bool = false) {
        self.os = os
        self.osVersion = osVersion
        self.appVersion = appVersion
        self.wifiAware = wifiAware
        self.nearbyConnections = nearbyConnections
        self.bleCentral = bleCentral
        self.blePeripheral = blePeripheral
        self.uwbPresent = uwbPresent
        self.uwbAzimuth = uwbAzimuth
        self.uwbElevation = uwbElevation
        self.appleInteropUwb = appleInteropUwb
        self.niEdm = niEdm
        self.wifiRtt = wifiRtt
        self.backgroundRanging = backgroundRanging
    }

    public func encoded() -> CBOR {
        .map([
            CBORPair(.uint(1), .uint(os.rawValue)),
            CBORPair(.uint(2), .text(osVersion)),
            CBORPair(.uint(3), .text(appVersion)),
            CBORPair(.uint(4), .bool(wifiAware)),
            CBORPair(.uint(5), .bool(nearbyConnections)),
            CBORPair(.uint(6), .bool(bleCentral)),
            CBORPair(.uint(7), .bool(blePeripheral)),
            CBORPair(.uint(8), .bool(uwbPresent)),
            CBORPair(.uint(9), .bool(uwbAzimuth)),
            CBORPair(.uint(10), .bool(uwbElevation)),
            CBORPair(.uint(11), .bool(appleInteropUwb)),
            CBORPair(.uint(12), .bool(niEdm)),
            CBORPair(.uint(13), .bool(wifiRtt)),
            CBORPair(.uint(14), .bool(backgroundRanging)),
        ])
    }

    /// Decode, ignoring unknown keys and defaulting missing keys (§11).
    public static func decode(_ cbor: CBOR) throws -> CapabilitySet {
        guard cbor.isMap else { throw ProtocolError.schemaViolation }
        func boolAt(_ k: UInt64) -> Bool {
            if case let .bool(b) = cbor.value(forKey: k) { return b }
            return false
        }
        func textAt(_ k: UInt64) -> String { cbor.value(forKey: k)?.asText ?? "" }
        guard let osRaw = cbor.value(forKey: 1)?.asUInt, let os = OSKind(rawValue: osRaw) else {
            throw ProtocolError.schemaViolation
        }
        return CapabilitySet(
            os: os,
            osVersion: textAt(2),
            appVersion: textAt(3),
            wifiAware: boolAt(4),
            nearbyConnections: boolAt(5),
            bleCentral: boolAt(6),
            blePeripheral: boolAt(7),
            uwbPresent: boolAt(8),
            uwbAzimuth: boolAt(9),
            uwbElevation: boolAt(10),
            appleInteropUwb: boolAt(11),
            niEdm: boolAt(12),
            wifiRtt: boolAt(13),
            backgroundRanging: boolAt(14))
    }
}

/// Ranging method selection (PROTOCOL_V2.md §12).
public enum RangingMethod: UInt64, Equatable {
    case uwbAppleInterop = 1
    case uwbAndroidOob = 2
    case niPeer = 3
    case bleRssi = 4
}

public struct RangingSelection: Equatable {
    public let method: RangingMethod
    public let edm: Bool
}

public enum RangingSelector {

    /// Deterministic function over both CapabilitySets, computed identically on
    /// both sides. Mutually exclusive conditions; ble_rssi is the fallback.
    public static func select(local: CapabilitySet, peer: CapabilitySet) -> RangingSelection {
        // 3. ni_peer: both ios, both uwbPresent. EDM only if both niEdm.
        if local.os == .ios && peer.os == .ios && local.uwbPresent && peer.uwbPresent {
            return RangingSelection(method: .niPeer, edm: local.niEdm && peer.niEdm)
        }
        // 2. uwb_android_oob: both android, both uwbPresent.
        if local.os == .android && peer.os == .android && local.uwbPresent && peer.uwbPresent {
            return RangingSelection(method: .uwbAndroidOob, edm: false)
        }
        // 1. uwb_apple_interop: one ios + one android, both uwbPresent, both appleInteropUwb.
        if local.os != peer.os && local.uwbPresent && peer.uwbPresent
            && local.appleInteropUwb && peer.appleInteropUwb {
            return RangingSelection(method: .uwbAppleInterop, edm: false)
        }
        // 4. ble_rssi: always-available fallback.
        return RangingSelection(method: .bleRssi, edm: false)
    }

    /// Whether `method` lies in the mutually-supported set of both CapabilitySets
    /// (PROTOCOL_V2.md §14). This is the weaker receiver-side predicate that
    /// `capabilityMismatch` enforcement uses; it is looser than `select`, which
    /// picks exactly one method. `ble_rssi` is always supported.
    public static func supports(method: RangingMethod,
                                local: CapabilitySet, peer: CapabilitySet) -> Bool {
        switch method {
        case .niPeer:
            return local.os == .ios && peer.os == .ios && local.uwbPresent && peer.uwbPresent
        case .uwbAndroidOob:
            return local.os == .android && peer.os == .android && local.uwbPresent && peer.uwbPresent
        case .uwbAppleInterop:
            return local.os != peer.os && local.uwbPresent && peer.uwbPresent
                && local.appleInteropUwb && peer.appleInteropUwb
        case .bleRssi:
            return true
        }
    }
}
