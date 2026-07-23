import Foundation

/// Disconnect / transition reason codes (PROTOCOL_V2.md §13).
public enum ReasonCode: UInt64, Equatable {
    case normal = 1
    case expired = 2
    case revoked = 3
    case duplicate = 4
    case protocolError = 5
    case authFailed = 6
    case timeout = 7
    case transportLost = 8
    case upgraded = 9
    case capabilityMismatch = 10
    case userStopped = 11
    case permissionRequired = 12
    case radioUnavailable = 13
    case backgroundSuspended = 14
    case identityMismatch = 15
}

/// Ranging error codes (PROTOCOL_V2.md §13).
public enum RangingErrorCode: UInt64, Equatable {
    case unsupported = 1
    case configRejected = 2
    case platformError = 3
    case lostSignal = 4
    case timeout = 5
}
