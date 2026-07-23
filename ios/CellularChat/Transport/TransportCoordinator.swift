import Foundation
import CellularChatCore

/// Sequential transport arbitration and upgrade (PROTOCOL_V2.md §4/§10).
///
/// The two high-power discovery mechanisms (Wi-Fi Aware, Nearby) are never run
/// in parallel: each is attempted in order with a bounded timeout, and BLE is
/// the always-available baseline. Once an authenticated high-throughput path
/// wins, losing transports stop.
@MainActor
final class TransportCoordinator {

    /// Preferred order from §4: Wi-Fi Aware → Nearby Connections → BLE.
    static let defaultOrder: [TransportKind] = [.wifiAware, .nearby, .ble]

    private(set) var active: PeerTransport?

    /// Try transports in `order`, returning the first that connects. Only one
    /// connect attempt is in flight at a time (no parallel discovery). Testable
    /// with fake transports.
    static func arbitrate(transports: [PeerTransport],
                          order: [TransportKind] = defaultOrder) async -> PeerTransport? {
        for kind in order {
            guard let transport = transports.first(where: { $0.kind == kind && $0.isAvailable })
            else { continue }
            if case .success = await transport.connect() {
                return transport
            }
        }
        return nil
    }

    func select(transports: [PeerTransport],
                order: [TransportKind] = defaultOrder) async -> PeerTransport? {
        let winner = await Self.arbitrate(transports: transports, order: order)
        if let winner {
            active = winner
        }
        return winner
    }

    /// Whether an available transport ranks strictly above the active one and is
    /// therefore worth a same-`sid` upgrade (§10). Order index: lower is better.
    func shouldUpgrade(to candidate: TransportKind,
                       order: [TransportKind] = defaultOrder) -> Bool {
        guard let active, candidate != active.kind,
              let ai = order.firstIndex(of: active.kind),
              let ci = order.firstIndex(of: candidate) else { return false }
        return ci < ai
    }

    func teardown(reason: ReasonCode) {
        active?.disconnect(reason: reason)
        active = nil
    }
}
