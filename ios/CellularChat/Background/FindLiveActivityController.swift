import Foundation
import ActivityKit

/// Armed-deadline lifecycle around a Find session (PROTOCOL_V2.md §10) plus a
/// Live Activity (IMPLEMENTATION_PLAN §5). The controller owns the deadline timer
/// (so all radio work stops deterministically at expiry) and, when the widget
/// extension can render it, an ActivityKit Live Activity showing the pair alias,
/// remaining time, and current proximity band. Background discovery/ranging also
/// relies on the `UIBackgroundModes` declared in Info.plist.
@MainActor
final class FindLiveActivityController: ObservableObject {

    @Published private(set) var deadline: Date?
    @Published private(set) var isActive = false

    /// Called when the armed deadline elapses so the coordinator can tear down
    /// discovery, advertising, and ranging (§10).
    var onExpired: (() -> Void)?

    private var timer: Timer?
    private var activity: Activity<FindActivityAttributes>?

    static let defaultDuration: TimeInterval = 30 * 60   // 30 minutes
    static let maxDuration: TimeInterval = 2 * 60 * 60   // 2 hours

    /// Arm Find with a bounded deadline (default 30 min, max 2 h) and start the
    /// Live Activity for `alias` when Live Activities are enabled.
    func arm(duration: TimeInterval = defaultDuration, alias: String = "") {
        let clamped = min(max(duration, 60), Self.maxDuration)
        let deadline = Date().addingTimeInterval(clamped)
        self.deadline = deadline
        isActive = true
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: clamped, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.expire() }
        }
        startActivity(alias: alias, deadline: deadline)
    }

    /// Push a fresh Live Activity content state (status + proximity band). A no-op
    /// when no activity is running.
    func update(statusText: String, proximityLabel: String?) {
        guard let activity, let deadline else { return }
        let state = FindActivityAttributes.ContentState(statusText: statusText,
                                                        proximityLabel: proximityLabel)
        Task { await activity.update(ActivityContent(state: state, staleDate: deadline)) }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        deadline = nil
        isActive = false
        endActivity()
    }

    private func expire() {
        stop()
        onExpired?()
    }

    var remaining: TimeInterval? {
        guard let deadline else { return nil }
        return max(0, deadline.timeIntervalSinceNow)
    }

    // MARK: ActivityKit

    private func startActivity(alias: String, deadline: Date) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let attributes = FindActivityAttributes(peerAlias: alias, deadline: deadline)
        let state = FindActivityAttributes.ContentState(statusText: "찾는 중", proximityLabel: nil)
        activity = try? Activity.request(attributes: attributes,
                                         content: ActivityContent(state: state, staleDate: deadline))
    }

    private func endActivity() {
        guard let activity else { return }
        self.activity = nil
        Task { await activity.end(nil, dismissalPolicy: .immediate) }
    }
}
