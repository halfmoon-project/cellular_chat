import Foundation
import CellularChatCore

/// Honest unavailable stub: there is no official Google Nearby Connections SDK
/// linked on iOS, so this transport always reports unsupported and arbitration
/// (§4) falls through to BLE. It is present so the coordinator's transport order
/// stays complete and so a future SDK integration has a defined seam.
final class NearbyConnectionsTransport: PeerTransport {
    let kind: TransportKind = .nearby
    var isAvailable: Bool { false }

    var onRecord: (([UInt8]) -> Void)?
    var onClosed: ((ReasonCode) -> Void)?

    func connect() async -> Result<Void, TransportFailure> { .failure(.unsupported) }
    func send(record: [UInt8]) throws { throw TransportFailure.unsupported }
    func disconnect(reason: ReasonCode) {}
}
