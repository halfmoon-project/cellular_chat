import Foundation

/// Minimal armed-deadline lifecycle around a Find session (PROTOCOL_V2.md §10).
///
/// Follow-up: a real Live Activity needs a WidgetKit extension target, which is
/// out of scope for this change. Background discovery/ranging relies on the
/// `UIBackgroundModes` (bluetooth-central, nearby-interaction) declared in
/// Info.plist. This controller only owns the deadline timer and surfaces expiry
/// so all radio work stops deterministically when the session ends.
@MainActor
final class FindLiveActivityController: ObservableObject {

    @Published private(set) var deadline: Date?
    @Published private(set) var isActive = false

    /// Called when the armed deadline elapses so the coordinator can tear down
    /// discovery, advertising, and ranging (§10).
    var onExpired: (() -> Void)?

    private var timer: Timer?

    static let defaultDuration: TimeInterval = 30 * 60   // 30 minutes
    static let maxDuration: TimeInterval = 2 * 60 * 60   // 2 hours

    /// Arm Find with a bounded deadline (default 30 min, max 2 h).
    func arm(duration: TimeInterval = defaultDuration) {
        let clamped = min(max(duration, 60), Self.maxDuration)
        let deadline = Date().addingTimeInterval(clamped)
        self.deadline = deadline
        isActive = true
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: clamped, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.expire() }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        deadline = nil
        isActive = false
    }

    private func expire() {
        stop()
        onExpired?()
    }

    var remaining: TimeInterval? {
        guard let deadline else { return nil }
        return max(0, deadline.timeIntervalSinceNow)
    }
}
