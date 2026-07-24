import SwiftUI
import CellularChatCore
import HalfmoonTokens

/// Distance/direction presentation. An arrow is shown ONLY when the platform
/// reported a fresh horizontal angle (state `directionAvailable`); it is never
/// synthesized from RSSI, a last-known angle, or the peer's self-reported angle
/// (PROTOCOL_V2.md §12, UWB_INTEROP.md). Distance-only and proximity-only paths
/// never draw an arrow.
struct DirectionView: View {
    let state: FindState
    let measurement: Measurement?
    let statusText: String
    let peerName: String

    var body: some View {
        VStack(spacing: HM.space._3) {
            Text("\(peerName) 찾기").font(HM.typography.headingSm.font)

            symbol

            if let distance = measurement?.distanceMeters, state != .signalLost {
                Text(distance.formatted(.number.precision(.fractionLength(1))) + " m")
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                    .accessibilityLabel("거리 \(distance.formatted(.number.precision(.fractionLength(1)))) 미터")
            } else if let band = measurement?.proximity, state == .proximityOnly {
                Text(band.label)
                    .font(.system(.title, design: .rounded, weight: .bold))
            }

            // RSSI approaching/receding trend as ADVISORY TEXT ONLY (Feature C):
            // shown only at high confidence and never as an arrow/glyph.
            if let trendText {
                Text(trendText)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Text(statusText)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(HM.space._4)
        .frame(maxWidth: .infinity)
        .background(HM.color.bgSubtle, in: RoundedRectangle(cornerRadius: HM.radius.xl))
        .overlay {
            RoundedRectangle(cornerRadius: HM.radius.xl).stroke(HM.color.borderDefault)
        }
    }

    /// Advisory RSSI trend text (Feature C): only for an RSSI-derived proximity
    /// value at high confidence. RSSI never yields a direction, so this is text
    /// only — no arrow. Low confidence shows nothing.
    private var trendText: String? {
        guard let m = measurement, m.method == .bleRssi, m.proximity != nil,
              m.trendConfidence == .high, state != .signalLost else { return nil }
        switch m.trend {
        case .approaching: return "가까워지는 중"
        case .receding: return "멀어지는 중"
        case .steady: return "유지"
        }
    }

    @ViewBuilder private var symbol: some View {
        if state == .directionAvailable, let angle = measurement?.horizontalAngleRadians {
            Image(systemName: "location.north.fill")
                .font(.system(size: 64, weight: .bold))
                .foregroundStyle(HM.color.actionPrimaryBg)
                .rotationEffect(.radians(angle))
                .accessibilityLabel("상대 기기 방향")
                .accessibilityValue("정면 기준 \(Int(angle * 180 / .pi))도")
        } else {
            Image(systemName: fallbackIcon)
                .font(.system(size: 48))
                .foregroundStyle(fallbackColor)
        }
    }

    private var fallbackIcon: String {
        switch state {
        case .distanceOnly: return "ruler"
        case .proximityOnly: return "dot.radiowaves.left.and.right"
        case .signalLost, .searching, .retryWait: return "wave.3.right.circle"
        case .failed: return "exclamationmark.triangle"
        default: return "point.3.connected.trianglepath.dotted"
        }
    }

    private var fallbackColor: Color {
        state == .failed ? HM.color.statusDanger : HM.color.actionPrimaryBg
    }
}
