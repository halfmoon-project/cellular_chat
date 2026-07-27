import Foundation
import AVFoundation
import NearbyInteraction
import simd

/// Horizontal angle extraction from an `NINearbyObject` for the `DirectionView`
/// arrow, shared by both NI rangers.
///
/// `direction` is the camera-free unit vector every U1/U2 iPhone reports
/// (`supportsDirectionMeasurement`); `horizontalAngle` (iOS 16+) only extends the
/// estimate outside the UWB field of view and needs camera assistance. Reading
/// only the latter is why two iOS 18 iPhones showed distance but never an arrow.
enum NIAngle {

    /// Device reference frame: +x right, +y up, −z out the back camera. A peer
    /// straight ahead is (0, 0, −1) → 0 rad; one to the right is (1, 0, 0) → +π/2.
    static func horizontalRadians(direction: simd_float3?, horizontalAngle: Float?) -> Double? {
        if let d = direction { return Double(atan2(d.x, -d.z)) }
        return horizontalAngle.map { Double($0) }
    }

    static func horizontalRadians(_ object: NINearbyObject) -> Double? {
        horizontalRadians(direction: object.direction, horizontalAngle: object.horizontalAngle)
    }

    /// Camera assistance auto-creates an `ARSession`; without camera access that
    /// run can invalidate the whole NI session, costing distance too. It only
    /// widens the angle estimate beyond the UWB field of view, so it is enabled
    /// solely when the camera is already authorized — never by prompting here.
    static var cameraAssistUsable: Bool {
        NISession.deviceCapabilities.supportsCameraAssistance
            && AVCaptureDevice.authorizationStatus(for: .video) == .authorized
    }

    /// Why an NI session stopped producing samples. `didInvalidateWith` used to
    /// discard the error, so a denied NI permission, a busy UWB radio and a
    /// device without UWB all rendered as the same "UWB 신호 없음" band.
    static func failureReason(_ error: Error) -> String {
        guard let ni = error as? NIError else { return error.localizedDescription }
        switch ni.code {
        case .userDidNotAllow: return "근거리 상호작용 권한 거부됨"
        case .invalidARConfiguration: return "카메라 보조 시작 실패"
        case .activeSessionsLimitExceeded, .activeExtendedDistanceSessionsLimitExceeded:
            return "다른 앱이 UWB 사용 중"
        case .resourceUsageTimeout: return "UWB 리소스 시간 초과"
        case .incompatiblePeerDevice: return "상대 기기와 UWB 비호환"
        case .unsupportedPlatform: return "이 기기가 UWB 미지원"
        case .invalidConfiguration: return "UWB 설정 오류"
        case .accessoryPeerDeviceUnavailable: return "상대 기기 응답 없음"
        default: return "UWB 세션 실패"
        }
    }
}
