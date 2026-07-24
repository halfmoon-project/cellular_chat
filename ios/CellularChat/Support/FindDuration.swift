import Foundation

/// User-selectable Find durations (IMPLEMENTATION_PLAN §10). All values stay
/// within `FindLiveActivityController`'s [1 min, 2 h] clamp, so the picked value
/// is armed verbatim. Default is 30 minutes.
enum FindDuration: TimeInterval, CaseIterable, Identifiable {
    case min15 = 900
    case min30 = 1800
    case min60 = 3600
    case hr2 = 7200

    static let `default`: FindDuration = .min30

    var id: TimeInterval { rawValue }
    var seconds: TimeInterval { rawValue }

    var label: String {
        switch self {
        case .min15: return "15분"
        case .min30: return "30분"
        case .min60: return "1시간"
        case .hr2: return "2시간"
        }
    }
}
