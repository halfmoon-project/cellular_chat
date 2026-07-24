import ActivityKit
import WidgetKit
import SwiftUI

/// Live Activity UI for an armed Find session: the pair alias, remaining time, and
/// current proximity band / status. Renders on the lock screen and in the Dynamic
/// Island. Text only — no arrow is drawn (direction is shown in-app, never here).
struct FindLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: FindActivityAttributes.self) { context in
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Label(context.attributes.peerAlias, systemImage: "location.north.fill")
                        .font(.headline)
                    Spacer()
                    remaining(context.attributes.deadline)
                        .font(.subheadline.monospacedDigit())
                }
                Text(context.state.proximityLabel ?? context.state.statusText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding()
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(context.attributes.peerAlias, systemImage: "location.north.fill")
                        .font(.headline)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    remaining(context.attributes.deadline)
                        .font(.subheadline.monospacedDigit())
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(context.state.proximityLabel ?? context.state.statusText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } compactLeading: {
                Image(systemName: "location.north.fill")
            } compactTrailing: {
                remaining(context.attributes.deadline)
                    .font(.caption2.monospacedDigit())
                    .frame(maxWidth: 44)
            } minimal: {
                Image(systemName: "location.north.fill")
            }
        }
    }

    private func remaining(_ deadline: Date) -> Text {
        Text(timerInterval: Date()...max(deadline, Date().addingTimeInterval(1)), countsDown: true)
    }
}
