import SwiftUI
import HalfmoonTokens

struct DirectionView: View {
    @ObservedObject var ranging: NearbyInteractionManager
    let peerName: String?
    let onStop: () -> Void

    var body: some View {
        VStack(spacing: HM.space._3) {
            HStack {
                VStack(alignment: .leading, spacing: HM.space._1) {
                    Text(peerName.map { "\($0) 찾기" } ?? "기기 찾기")
                        .font(HM.typography.headingSm.font)
                    Text(ranging.state.title)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button("중지", action: onStop)
                    .buttonStyle(.bordered)
            }

            if case .directionAvailable = ranging.state,
               let angle = ranging.horizontalAngleRadians {
                Image(systemName: "location.north.fill")
                    .font(.system(size: 62, weight: .bold))
                    .foregroundStyle(HM.color.actionPrimaryBg)
                    .rotationEffect(.radians(Double(angle)))
                    .accessibilityLabel("상대 기기 방향")
                    .accessibilityValue("정면 기준 \(Int(Double(angle) * 180 / .pi))도")
            } else {
                Image(systemName: iconName)
                    .font(.system(size: 48))
                    .foregroundStyle(iconColor)
            }

            if let distance = ranging.distanceMeters {
                Text(distance.formatted(.number.precision(.fractionLength(1))) + " m")
                    .font(.system(.title, design: .rounded, weight: .bold))
            }

            if let detail = ranging.state.detail {
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(HM.space._4)
        .frame(maxWidth: .infinity)
        .background(HM.color.bgSubtle, in: RoundedRectangle(cornerRadius: HM.radius.xl))
        .overlay {
            RoundedRectangle(cornerRadius: HM.radius.xl)
                .stroke(HM.color.borderDefault)
        }
        .padding(.horizontal, HM.space._4)
    }

    private var iconName: String {
        switch ranging.state {
        case .searching:
            return "wave.3.right.circle"
        case .distanceOnly:
            return "ruler"
        case .unsupported:
            return "location.slash"
        case .failed:
            return "exclamationmark.triangle"
        case .directionAvailable:
            return "location.north.fill"
        }
    }

    private var iconColor: Color {
        switch ranging.state {
        case .failed:
            return HM.color.statusDanger
        case .unsupported:
            return .secondary
        default:
            return HM.color.actionPrimaryBg
        }
    }
}
