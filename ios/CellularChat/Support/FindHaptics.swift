import UIKit
import CellularChatCore

/// Proximity-driven haptic pulses (IMPLEMENTATION_PLAN Phase 8). The pulse
/// interval scales with the freshest distance/proximity band — closer is faster —
/// and is driven strictly by live measurements. Any stale-clear, signal loss,
/// stop, or expiry calls `stop()`, so pulses never outlive a real measurement.
@MainActor
final class FindHaptics {
    private let generator = UIImpactFeedbackGenerator(style: .medium)
    private var timer: Timer?
    private var currentInterval: TimeInterval?

    /// Retune the cadence from a fresh sample. A sample carrying neither a
    /// distance nor a known proximity band stops pulsing — there is nothing
    /// truthful to convey.
    func update(for measurement: Measurement) {
        guard let interval = Self.interval(for: measurement) else { stop(); return }
        guard interval != currentInterval else { return }
        currentInterval = interval
        generator.prepare()
        timer?.invalidate()
        let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.pulse() }
        }
        timer.tolerance = interval * 0.1
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
        pulse()   // fire immediately so a new/closer reading is felt at once
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        currentInterval = nil
    }

    private func pulse() {
        generator.impactOccurred()
        generator.prepare()
    }

    /// Closer = shorter interval. A precise distance maps 0–10 m onto 0.25–1.5 s;
    /// proximity bands map to fixed cadences; `unknown`/no-signal returns nil so
    /// the caller stays silent.
    static func interval(for m: Measurement) -> TimeInterval? {
        if let distance = m.distanceMeters {
            let clamped = min(max(distance, 0), 10)
            return 0.25 + (clamped / 10) * 1.25
        }
        switch m.proximity {
        case .veryNear: return 0.3
        case .near: return 0.8
        case .far: return 1.5
        case .unknown, nil: return nil
        }
    }
}
