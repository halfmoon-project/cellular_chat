import SwiftUI
import Network
import WiFiAware
import DeviceDiscoveryUI

/// Presents the system Wi-Fi Aware app-to-app pairing UI (PROTOCOL_V2.md §6 step
/// 3, plan §8) by wrapping `DDDevicePairingViewController`. The pairing flow only
/// presents this when `WiFiAwareTransport.systemPairingSupported()` is true, so
/// BLE-only pairing still completes when it is unsupported or the user declines.
///
/// `DDDevicePairingViewController` reports no completion of its own, so the newly
/// paired device ID is recovered by diffing `WAPairedDevice.allDevices` around the
/// presentation. That ID is a §8 routing hint only — never the security identity.
struct WiFiAwarePairingSheet: UIViewControllerRepresentable {
    /// Fires once when the sheet is torn down, carrying the newly paired device ID
    /// if the system added one (nil when the user declined or none was added).
    let onComplete: (WAPairedDevice.ID?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onComplete: onComplete) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.captureBefore()
        guard let svc = WAPublishableService.allServices[WiFiAwareTransport.serviceName] else {
            return UIViewController()   // safety net; the caller gates on support
        }
        let provider = WAPublisherListener.wifiAware(.connecting(to: svc, from: .allPairedDevices))
        return DDDevicePairingViewController(listenerProvider: provider, access: .default)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    static func dismantleUIViewController(_ uiViewController: UIViewController, coordinator: Coordinator) {
        coordinator.captureAfterAndComplete()
    }

    final class Coordinator {
        private let onComplete: (WAPairedDevice.ID?) -> Void
        private var before: Set<WAPairedDevice.ID> = []

        init(onComplete: @escaping (WAPairedDevice.ID?) -> Void) { self.onComplete = onComplete }

        func captureBefore() {
            Task { [weak self] in self?.before = (try? await Self.pairedIDs()) ?? [] }
        }

        func captureAfterAndComplete() {
            let before = self.before
            let complete = self.onComplete
            Task { @MainActor in
                let after = (try? await Self.pairedIDs()) ?? []
                complete(after.subtracting(before).first)
            }
        }

        private static func pairedIDs() async throws -> Set<WAPairedDevice.ID> {
            let devices = try await WAPairedDevice.allDevices.current() ?? [:]
            return Set(devices.keys)
        }
    }
}
