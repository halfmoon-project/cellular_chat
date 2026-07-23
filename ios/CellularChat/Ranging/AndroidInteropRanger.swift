import Foundation
import NearbyInteraction
import CellularChatCore

/// iOS-side of the Android↔iOS UWB interop (PROTOCOL_V2.md §12 `uwb_apple_interop`,
/// UWB_INTEROP.md). iOS is the responder/controlee: it receives the 48-byte
/// `apple_config`, builds `NINearbyAccessoryConfiguration`, and forwards the
/// generated 35-byte shareable data unchanged as `apple_shareable`. Requires
/// iOS 26.1+ (the `appleInteropUwb` capability).
@MainActor
final class AndroidInteropRanger: NSObject {

    /// Forward the platform's shareable configuration data as `apple_shareable`.
    var onSendShareable: ((Data) -> Void)?
    /// A fresh measurement, or nil to clear the last sample (§10/§12).
    var onMeasurement: ((Measurement?) -> Void)?

    private var session: NISession?
    private var configuration: NINearbyAccessoryConfiguration?

    static var isSupported: Bool {
        if #available(iOS 26.1, *) {
            return NISession.deviceCapabilities.supportsPreciseDistanceMeasurement
        }
        return false
    }

    /// Consume the 48-byte accessory configuration from `apple_config` and start.
    /// A retry MUST pass fresh bytes; stale shareable config is never reused
    /// (UWB_INTEROP.md).
    func start(appleConfig data: Data) {
        guard Self.isSupported else { onMeasurement?(nil); return }
        guard let config = try? NINearbyAccessoryConfiguration(data: data) else {
            onMeasurement?(nil); return
        }
        if NISession.deviceCapabilities.supportsCameraAssistance {
            config.isCameraAssistanceEnabled = true
        }
        let session = NISession()
        session.delegate = self
        session.delegateQueue = .main
        self.session = session
        self.configuration = config
        session.run(config)
    }

    func stop() {
        let session = self.session
        self.session = nil
        configuration = nil
        session?.invalidate()
        onMeasurement?(nil)
    }
}

extension AndroidInteropRanger: NISessionDelegate {
    nonisolated func session(_ session: NISession,
                             didGenerateShareableConfigurationData data: Data,
                             for object: NINearbyObject) {
        Task { @MainActor [weak self] in
            guard let self, session === self.session else { return }
            self.onSendShareable?(data)   // 35 bytes, forwarded unchanged
        }
    }

    nonisolated func session(_ session: NISession, didUpdate nearbyObjects: [NINearbyObject]) {
        guard let object = nearbyObjects.first else { return }
        let distance = object.distance.map { Double($0) }
        let angle = object.horizontalAngle.map { Double($0) }
        Task { @MainActor [weak self] in
            guard let self, session === self.session else { return }
            self.onMeasurement?(Measurement(timestamp: Date(), method: .uwbAppleInterop,
                                            distanceMeters: distance,
                                            horizontalAngleRadians: angle,
                                            proximity: nil))
        }
    }

    nonisolated func session(_ session: NISession, didRemove nearbyObjects: [NINearbyObject],
                             reason: NINearbyObject.RemovalReason) {
        Task { @MainActor [weak self] in
            guard let self, session === self.session else { return }
            self.onMeasurement?(nil)
        }
    }

    nonisolated func session(_ session: NISession, didInvalidateWith error: Error) {
        Task { @MainActor [weak self] in
            guard let self, session === self.session else { return }
            self.session = nil
            self.onMeasurement?(nil)
        }
    }
}
