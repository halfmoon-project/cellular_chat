import Foundation
import CellularChatCore

/// The three transports, ordered by preference in §4. `rawValue` is the §8
/// prologue `transportTag`; `upgradeCode` is the §8 `transport_upgrade` value.
enum TransportKind: String, CaseIterable, Equatable {
    case wifiAware = "aware"
    case nearby = "nearby"
    case ble = "ble"

    var transportTag: [UInt8] { Array(rawValue.utf8) }

    var upgradeCode: UInt64 {
        switch self {
        case .wifiAware: return 1
        case .nearby: return 2
        case .ble: return 3
        }
    }

    init?(upgradeCode: UInt64) {
        switch upgradeCode {
        case 1: self = .wifiAware
        case 2: self = .nearby
        case 3: self = .ble
        default: return nil
        }
    }
}

enum TransportFailure: Error, Equatable {
    case unsupported          // SDK/hardware absent → fall through
    case radioUnavailable
    case timeout
    case failed
}

/// A transport delivers whole, ordered protocol records (PROTOCOL_V2.md §5).
/// Reassembly (BLE fragmentation, stream framing) happens inside the transport;
/// callers see one complete record per `onRecord`.
protocol PeerTransport: AnyObject {
    var kind: TransportKind { get }
    /// Runtime capability gate — false means "skip me and fall through" (§4).
    var isAvailable: Bool { get }

    func connect() async -> Result<Void, TransportFailure>
    func send(record: [UInt8]) throws
    func disconnect(reason: ReasonCode)

    var onRecord: (([UInt8]) -> Void)? { get set }
    var onClosed: ((ReasonCode) -> Void)? { get set }
}
