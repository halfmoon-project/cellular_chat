import Foundation
import WiFiAware
import CellularChatCore

/// Observes the OS Wi-Fi Aware paired-device list (plan §8, PROTOCOL_V2.md §8) and
/// clears a pair's `pairingHandle` when its system pairing is removed. The handle
/// is only an install-scoped routing hint: dropping it never revokes the pair —
/// the Noise identity is unaffected and the next Find simply falls back to BLE
/// rendezvous instead of the Wi-Fi Aware paired-device shortcut.
final class SystemPairObserver {
    private let pairStore: PairStore
    private var task: Task<Void, Never>?

    init(pairStore: PairStore) { self.pairStore = pairStore }

    /// Begin observing. Gated on Wi-Fi Aware support so unsupported devices do
    /// nothing. Idempotent: a second call while already running is ignored.
    func start() {
        guard task == nil, LocalCapabilities.wifiAwareAvailable() else { return }
        task = Task { [weak self] in
            guard let self else { return }
            do {
                for try await devices in WAPairedDevice.allDevices {
                    let present = Set(devices.keys)
                    await MainActor.run { self.reconcile(present: present) }
                }
            } catch {
                // Stream ended/failed: leave stored handles as-is (BLE still
                // works); a later `start()` re-observes.
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    /// Clear any stored `pairingHandle` whose device ID is no longer in the OS
    /// paired-device set (§8). Pure over its input so it is unit-testable without
    /// Wi-Fi Aware hardware.
    @MainActor
    func reconcile(present: Set<UInt64>) {
        for record in pairStore.active {
            guard let handle = record.pairingHandle, let id = UInt64(handle) else { continue }
            if !present.contains(id) {
                pairStore.setPairingHandle(nil, pairId: record.pairId)
            }
        }
    }
}
