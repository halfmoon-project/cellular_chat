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

    init() {
        // A new process cannot adopt the previous one's session, so an Activity left
        // behind by a crash/kill is orphaned: it would keep counting down with a
        // status nothing can ever update. End it instead.
        // ponytail: best-effort — `activities` is hydrated from the ActivityKit
        // daemon asynchronously, so a sweep one hop after launch catches the normal
        // case; observing `activityUpdates` instead would also see the activity this
        // controller starts itself. Never touches the live one.
        Task { [weak self] in
            for orphan in Activity<FindActivityAttributes>.activities
            where orphan.id != self?.activity?.id {
                await orphan.end(nil, dismissalPolicy: .immediate)
            }
        }
    }

    /// Arm Find with a bounded deadline (default 30 min, max 2 h) and start the
    /// Live Activity for `alias` when Live Activities are enabled.
    func arm(duration: TimeInterval = defaultDuration, alias: String = "", statusText: String) {
        let clamped = min(max(duration, 60), Self.maxDuration)
        let deadline = Date().addingTimeInterval(clamped)
        self.deadline = deadline
        isActive = true
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: clamped, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.expire() }
        }
        endActivity(finalText: nil)   // never stack two activities for one session
        startActivity(alias: alias, deadline: deadline, statusText: statusText)
    }

    /// Push a fresh Live Activity content state (status + proximity band). A no-op
    /// when no activity is running.
    func update(statusText: String, proximityLabel: String?) {
        guard let activity, let deadline else { return }
        let state = FindActivityAttributes.ContentState(statusText: statusText,
                                                        proximityLabel: proximityLabel)
        Task { await activity.update(ActivityContent(state: state, staleDate: deadline)) }
    }

    /// End the session. `finalText` is shown on the Live Activity for a minute so an
    /// expiry or failure is not just a card silently vanishing; pass nil (a user stop,
    /// which the user already knows about) to dismiss immediately.
    func stop(finalText: String? = nil) {
        timer?.invalidate()
        timer = nil
        deadline = nil
        isActive = false
        endActivity(finalText: finalText)
    }

    private func expire() {
        timer?.invalidate()
        timer = nil
        // The coordinator drives teardown (and the closing Live Activity text)
        // through the reducer's DEADLINE event; with no handler, end it here.
        if let onExpired { onExpired() } else { stop() }
    }

    var remaining: TimeInterval? {
        guard let deadline else { return nil }
        return max(0, deadline.timeIntervalSinceNow)
    }

    // MARK: ActivityKit

    private func startActivity(alias: String, deadline: Date, statusText: String) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let attributes = FindActivityAttributes(peerAlias: alias, deadline: deadline)
        let state = FindActivityAttributes.ContentState(statusText: statusText, proximityLabel: nil)
        activity = try? Activity.request(attributes: attributes,
                                         content: ActivityContent(state: state, staleDate: deadline))
    }

    private func endActivity(finalText: String?) {
        guard let activity else { return }
        self.activity = nil
        let final = finalText.map {
            ActivityContent(state: FindActivityAttributes.ContentState(statusText: $0, proximityLabel: nil),
                            staleDate: nil)
        }
        let policy: ActivityUIDismissalPolicy =
            final == nil ? .immediate : .after(Date().addingTimeInterval(60))
        Task { await activity.end(final, dismissalPolicy: policy) }
    }
}
