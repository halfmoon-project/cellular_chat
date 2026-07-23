import Foundation
import NearbyInteraction
import CellularChatCore

/// iOS-to-iOS Nearby Interaction peer ranging (PROTOCOL_V2.md §12 `ni_peer`).
/// Discovery tokens are exchanged as `ni_token` messages (§8 msgType 23). EDM is
/// enabled only when both peers report support via `NISession.deviceCapabilities`.
@MainActor
final class ApplePeerRanger: NSObject {

    /// Send this device's archived `NIDiscoveryToken` as an `ni_token` message.
    var onSendToken: ((Data) -> Void)?
    /// A fresh measurement, or nil to signal the last sample is cleared (§10/§12).
    var onMeasurement: ((Measurement?) -> Void)?

    private var session: NISession?
    private var configuration: NINearbyPeerConfiguration?
    private var enableEDM = false

    /// This iPhone supports precise distance (the `uwbPresent` gate for §12).
    static var isSupported: Bool { NISession.deviceCapabilities.supportsPreciseDistanceMeasurement }

    /// Begin a peer session and publish this device's discovery token.
    func start(enableEDM: Bool) {
        guard Self.isSupported else { onMeasurement?(nil); return }
        self.enableEDM = enableEDM
        let session = NISession()
        session.delegate = self
        session.delegateQueue = .main
        self.session = session
        guard let token = session.discoveryToken,
              let data = try? NSKeyedArchiver.archivedData(withRootObject: token, requiringSecureCoding: true)
        else { onMeasurement?(nil); return }
        onSendToken?(data)
    }

    /// Consume the peer's `ni_token` and run the configured peer session.
    func receivePeerToken(_ data: Data) {
        guard let session,
              let token = try? NSKeyedUnarchiver.unarchivedObject(ofClass: NIDiscoveryToken.self, from: data)
        else { onMeasurement?(nil); return }

        let config = NINearbyPeerConfiguration(peerToken: token)
        let caps = NISession.deviceCapabilities
        if caps.supportsCameraAssistance {
            config.isCameraAssistanceEnabled = true
        }
        // EDM only if selected AND both radios report support (§12).
        if enableEDM,
           caps.supportsExtendedDistanceMeasurement,
           token.deviceCapabilities.supportsExtendedDistanceMeasurement {
            config.isExtendedDistanceMeasurementEnabled = true
        }
        configuration = config
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

extension ApplePeerRanger: NISessionDelegate {
    nonisolated func session(_ session: NISession, didUpdate nearbyObjects: [NINearbyObject]) {
        guard let object = nearbyObjects.first else { return }
        let distance = object.distance.map { Double($0) }
        let angle = object.horizontalAngle.map { Double($0) }
        Task { @MainActor [weak self] in
            guard let self, session === self.session else { return }
            // A fresh angle enables direction UI; otherwise distance-only (§12).
            self.onMeasurement?(Measurement(timestamp: Date(), method: .niPeer,
                                            distanceMeters: distance,
                                            horizontalAngleRadians: angle,
                                            proximity: nil))
        }
    }

    nonisolated func session(_ session: NISession, didRemove nearbyObjects: [NINearbyObject],
                             reason: NINearbyObject.RemovalReason) {
        Task { @MainActor [weak self] in
            guard let self, session === self.session else { return }
            self.onMeasurement?(nil)   // clear stale measurement on signal loss
        }
    }

    nonisolated func sessionWasSuspended(_ session: NISession) {
        Task { @MainActor [weak self] in
            guard let self, session === self.session else { return }
            self.onMeasurement?(nil)
        }
    }

    nonisolated func sessionSuspensionEnded(_ session: NISession) {
        Task { @MainActor [weak self] in
            guard let self, session === self.session, let config = self.configuration else { return }
            session.run(config)
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
