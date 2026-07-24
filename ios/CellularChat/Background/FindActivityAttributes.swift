import Foundation
import ActivityKit

/// Live Activity model for an armed Find session (IMPLEMENTATION_PLAN §5
/// background controller). `peerAlias` and `deadline` are fixed for the session;
/// `statusText` and `proximityLabel` update as the session state and proximity
/// band change. Shared between the app (which drives ActivityKit) and the widget
/// extension (which renders it).
struct FindActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        var statusText: String
        var proximityLabel: String?
    }

    var peerAlias: String
    var deadline: Date
}
