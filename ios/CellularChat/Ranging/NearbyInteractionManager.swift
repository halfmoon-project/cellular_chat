import Foundation
import NearbyInteraction

enum RangingDisplayState: Equatable {
    case unsupported(String)
    case searching(String)
    case distanceOnly
    case directionAvailable
    case failed(String)

    var title: String {
        switch self {
        case .unsupported:
            return "지원되지 않음"
        case .searching:
            return "찾는 중"
        case .distanceOnly:
            return "거리만 측정 가능"
        case .directionAvailable:
            return "방향 측정 중"
        case .failed:
            return "측정 실패"
        }
    }

    var detail: String? {
        switch self {
        case .unsupported(let detail), .searching(let detail), .failed(let detail):
            return detail
        case .distanceOnly:
            return "상대 기기가 방향 값을 제공하지 않습니다."
        case .directionAvailable:
            return nil
        }
    }
}

@MainActor
final class NearbyInteractionManager: NSObject, ObservableObject {
    private enum Phase {
        case idle
        case awaitingPeerToken
        case awaitingAccessoryConfiguration
        case runningPeer
        case runningAccessory
    }

    struct LocalCapabilities {
        let applePeerNI: Bool
        let appleAccessoryNI: Bool
        let distance: Bool
        let direction: Bool
    }

    static var localCapabilities: LocalCapabilities {
        let capabilities = NISession.deviceCapabilities
        let precise = capabilities.supportsPreciseDistanceMeasurement
        let accessoryInterop: Bool
        if #available(iOS 26.1, *) {
            accessoryInterop = precise
        } else {
            accessoryInterop = false
        }
        return LocalCapabilities(
            applePeerNI: precise,
            appleAccessoryNI: accessoryInterop,
            distance: precise,
            direction: capabilities.supportsDirectionMeasurement || capabilities.supportsCameraAssistance
        )
    }

    @Published private(set) var activePeerID: String?
    @Published private(set) var state: RangingDisplayState = .unsupported("찾을 기기를 선택하세요.")
    @Published private(set) var distanceMeters: Float?
    @Published private(set) var horizontalAngleRadians: Float?

    var onSendMessage: ((String, WireMessage) -> Void)?

    private var session: NISession?
    private var lastConfiguration: NIConfiguration?
    private var activePlatform: String?
    private var phase: Phase = .idle

    @discardableResult
    func startFinding(
        peerID: String,
        peerPlatform: String,
        capabilities: RangingCapabilities?,
        onWillStart: () -> Void
    ) -> Bool {
        guard Self.localCapabilities.distance else {
            showUnavailable(for: peerID, platform: peerPlatform, reason: "이 iPhone은 UWB 정밀 거리 측정을 지원하지 않습니다.")
            return false
        }
        guard !hasActiveAttempt(for: peerID, platform: peerPlatform) else { return true }

        switch peerPlatform {
        case "ios":
            guard capabilities?.applePeerNI == true else {
                showUnavailable(for: peerID, platform: peerPlatform, reason: "상대 iPhone이 Nearby Interaction을 지원하지 않습니다.")
                return false
            }
            onWillStart()
            preparePeerSession(for: peerID, sendToken: true)
            return true
        case "android":
            guard Self.localCapabilities.appleAccessoryNI else {
                showUnavailable(for: peerID, platform: peerPlatform, reason: "Android UWB 연동에는 iOS 26.1 이상이 필요합니다.")
                return false
            }
            guard capabilities?.androidRawUwb == true else {
                showUnavailable(for: peerID, platform: peerPlatform, reason: "상대 Android 기기가 호환 UWB ranging을 지원하지 않습니다.")
                return false
            }
            onWillStart()
            waitForAccessoryConfiguration(from: peerID)
            return true
        default:
            showUnavailable(for: peerID, platform: peerPlatform, reason: "상대 플랫폼의 UWB 연결 방식을 알 수 없습니다.")
            return false
        }
    }

    func handleStartRequest(from peerID: String, platform: String, capabilities: RangingCapabilities?) {
        guard Self.localCapabilities.distance else {
            showUnavailable(
                for: peerID,
                platform: platform,
                reason: "이 iPhone은 UWB 정밀 거리 측정을 지원하지 않습니다.",
                notifyPeer: true
            )
            return
        }

        if hasActiveAttempt(for: peerID, platform: platform) {
            return
        } else if platform == "ios", capabilities?.applePeerNI == true {
            preparePeerSession(for: peerID, sendToken: true)
        } else if platform == "android", Self.localCapabilities.appleAccessoryNI,
                  capabilities?.androidRawUwb == true {
            waitForAccessoryConfiguration(from: peerID)
        } else {
            showUnavailable(
                for: peerID,
                platform: platform,
                reason: "상대 기기와 호환되는 UWB 모드가 없습니다.",
                notifyPeer: true
            )
        }
    }

    func receive(_ message: WireMessage, from peerID: String, platform: String) {
        switch message.type {
        case .niDiscoveryToken:
            guard expects(.awaitingPeerToken, from: peerID, platform: "ios"), platform == "ios" else { return }
            guard let encoded = message.data, let data = Data(base64Encoded: encoded) else {
                failCurrent("Nearby Interaction 토큰이 올바르지 않습니다.")
                return
            }
            receivePeerToken(data, from: peerID)
        case .niAccessoryConfig:
            guard expects(.awaitingAccessoryConfiguration, from: peerID, platform: "android"),
                  platform == "android",
                  Self.localCapabilities.appleAccessoryNI else { return }
            guard let encoded = message.data, let data = Data(base64Encoded: encoded) else {
                failCurrent("Android UWB 설정 데이터가 올바르지 않습니다.")
                return
            }
            receiveAccessoryConfiguration(data, from: peerID)
        case .niShareableConfig:
            // iOS is the controller in accessory mode and does not consume this payload.
            break
        default:
            break
        }
    }

    func stop() {
        let currentSession = session
        session = nil
        lastConfiguration = nil
        activePeerID = nil
        activePlatform = nil
        phase = .idle
        distanceMeters = nil
        horizontalAngleRadians = nil
        state = .unsupported("찾을 기기를 선택하세요.")
        currentSession?.invalidate()
    }

    private func preparePeerSession(for peerID: String, sendToken: Bool) {
        resetSession(for: peerID, platform: "ios")
        phase = .awaitingPeerToken
        state = .searching("상대 기기의 UWB 신호를 찾는 중입니다.")
        if sendToken { sendLocalDiscoveryToken(to: peerID) }
    }

    private func hasActiveAttempt(for peerID: String, platform: String) -> Bool {
        activePeerID == peerID && activePlatform == platform && phase != .idle
    }

    private func expects(_ expectedPhase: Phase, from peerID: String, platform: String) -> Bool {
        activePeerID == peerID && activePlatform == platform && phase == expectedPhase
    }

    private func showUnavailable(
        for peerID: String,
        platform: String,
        reason: String,
        notifyPeer: Bool = false
    ) {
        let currentSession = session
        session = nil
        lastConfiguration = nil
        activePeerID = peerID
        activePlatform = platform
        phase = .idle
        distanceMeters = nil
        horizontalAngleRadians = nil
        state = .unsupported(reason)
        currentSession?.invalidate()
        if notifyPeer { sendStop(to: peerID) }
    }

    private func waitForAccessoryConfiguration(from peerID: String) {
        let currentSession = session
        session = nil
        lastConfiguration = nil
        activePeerID = peerID
        activePlatform = "android"
        phase = .awaitingAccessoryConfiguration
        distanceMeters = nil
        horizontalAngleRadians = nil
        state = .searching("Android 기기의 UWB 설정 데이터를 기다리는 중입니다.")
        currentSession?.invalidate()
    }

    private func resetSession(for peerID: String, platform: String) {
        let currentSession = session
        session = nil
        lastConfiguration = nil
        currentSession?.invalidate()
        let newSession = NISession()
        newSession.delegate = self
        newSession.delegateQueue = .main
        session = newSession
        activePeerID = peerID
        activePlatform = platform
        distanceMeters = nil
        horizontalAngleRadians = nil
    }

    private func sendLocalDiscoveryToken(to peerID: String) {
        guard let token = session?.discoveryToken else {
            failCurrent("이 기기에서 Nearby Interaction 토큰을 만들 수 없습니다.")
            return
        }
        do {
            let data = try NSKeyedArchiver.archivedData(withRootObject: token, requiringSecureCoding: true)
            var message = WireMessage(type: .niDiscoveryToken)
            message.data = data.base64EncodedString()
            onSendMessage?(peerID, message)
        } catch {
            failCurrent("Nearby Interaction 토큰 직렬화 실패: \(error.localizedDescription)")
        }
    }

    private func receivePeerToken(_ data: Data, from peerID: String) {
        do {
            guard let token = try NSKeyedUnarchiver.unarchivedObject(
                ofClass: NIDiscoveryToken.self,
                from: data
            ) else {
                throw RangingError.invalidPeerToken
            }

            guard expects(.awaitingPeerToken, from: peerID, platform: "ios"), session != nil else { return }
            let configuration = NINearbyPeerConfiguration(peerToken: token)
            let local = NISession.deviceCapabilities
            if local.supportsCameraAssistance {
                configuration.isCameraAssistanceEnabled = true
            }
            if #available(iOS 17.0, *),
               local.supportsExtendedDistanceMeasurement,
               token.deviceCapabilities.supportsExtendedDistanceMeasurement {
                configuration.isExtendedDistanceMeasurementEnabled = true
            }
            lastConfiguration = configuration
            session?.run(configuration)
            phase = .runningPeer
            state = .searching("상대 기기의 UWB 신호를 찾는 중입니다.")
        } catch {
            failCurrent("Nearby Interaction 토큰 해석 실패: \(error.localizedDescription)")
        }
    }

    private func receiveAccessoryConfiguration(_ data: Data, from peerID: String) {
        do {
            let configuration = try NINearbyAccessoryConfiguration(data: data)
            if NISession.deviceCapabilities.supportsCameraAssistance {
                configuration.isCameraAssistanceEnabled = true
            }
            guard expects(.awaitingAccessoryConfiguration, from: peerID, platform: "android") else { return }
            resetSession(for: peerID, platform: "android")
            lastConfiguration = configuration
            session?.run(configuration)
            phase = .runningAccessory
            state = .searching("Android 기기와 UWB 세션을 설정하는 중입니다.")
        } catch {
            failCurrent("Android UWB 설정을 시작할 수 없습니다: \(error.localizedDescription)")
        }
    }

    private func failCurrent(_ reason: String) {
        let peerID = activePeerID
        let currentSession = session
        session = nil
        lastConfiguration = nil
        phase = .idle
        distanceMeters = nil
        horizontalAngleRadians = nil
        state = .failed(reason)
        currentSession?.invalidate()
        if let peerID { sendStop(to: peerID) }
    }

    private func sendStop(to peerID: String) {
        onSendMessage?(peerID, WireMessage(type: .rangingStop))
    }
}

extension NearbyInteractionManager: NISessionDelegate {
    nonisolated func session(_ session: NISession, didUpdate nearbyObjects: [NINearbyObject]) {
        guard let object = nearbyObjects.first else { return }
        let distance = object.distance
        let angle = object.horizontalAngle
        Task { @MainActor [weak self] in
            guard let self, session === self.session else { return }
            self.distanceMeters = distance
            self.horizontalAngleRadians = angle
            if angle != nil {
                self.state = .directionAvailable
            } else if distance != nil {
                self.state = .distanceOnly
            } else {
                self.state = .searching("기기를 들고 주변을 천천히 둘러보세요.")
            }
        }
    }

    nonisolated func session(
        _ session: NISession,
        didGenerateShareableConfigurationData shareableConfigurationData: Data,
        for object: NINearbyObject
    ) {
        Task { @MainActor [weak self] in
            guard let self,
                  session === self.session,
                  let peerID = self.activePeerID,
                  self.activePlatform == "android",
                  self.phase == .runningAccessory else { return }
            var message = WireMessage(type: .niShareableConfig)
            message.data = shareableConfigurationData.base64EncodedString()
            self.onSendMessage?(peerID, message)
            self.state = .searching("Android 기기의 UWB 시작 신호를 기다리는 중입니다.")
        }
    }

    nonisolated func session(_ session: NISession, didRemove nearbyObjects: [NINearbyObject], reason: NINearbyObject.RemovalReason) {
        Task { @MainActor [weak self] in
            guard let self, session === self.session else { return }
            self.distanceMeters = nil
            self.horizontalAngleRadians = nil
            self.state = .searching("UWB 신호가 끊겼습니다. 두 기기를 들어 다시 찾아보세요.")
        }
    }

    nonisolated func sessionWasSuspended(_ session: NISession) {
        Task { @MainActor [weak self] in
            guard let self, session === self.session else { return }
            self.state = .searching("앱이 다시 활성화되면 측정을 재개합니다.")
        }
    }

    nonisolated func sessionSuspensionEnded(_ session: NISession) {
        Task { @MainActor [weak self] in
            guard let self, session === self.session, let configuration = self.lastConfiguration else { return }
            session.run(configuration)
            self.state = .searching("UWB 측정을 다시 시작하는 중입니다.")
        }
    }

    nonisolated func session(_ session: NISession, didInvalidateWith error: Error) {
        Task { @MainActor [weak self] in
            guard let self, session === self.session else { return }
            let peerID = self.activePeerID
            self.session = nil
            self.lastConfiguration = nil
            self.phase = .idle
            self.distanceMeters = nil
            self.horizontalAngleRadians = nil
            self.state = .failed(error.localizedDescription)
            if let peerID { self.sendStop(to: peerID) }
        }
    }
}

private enum RangingError: LocalizedError {
    case invalidPeerToken

    var errorDescription: String? {
        "Nearby Interaction 토큰 형식이 올바르지 않습니다."
    }
}
