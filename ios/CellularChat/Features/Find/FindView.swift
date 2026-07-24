import SwiftUI
import UIKit
import NearbyInteraction
import CellularChatCore
import HalfmoonTokens

/// The Find screen: arm/stop a time-limited session and show the transport-
/// independent state with a reworked `DirectionView`. "검색 중" explicitly means
/// "not yet in direct radio range," never "the other person is not here."
struct FindView: View {
    @EnvironmentObject private var appModel: AppModel
    let pair: PairRecord

    var body: some View {
        FindContent(find: appModel.find, ranging: appModel.find.ranging, pair: pair)
    }
}

private struct FindContent: View {
    @ObservedObject var find: FindSessionCoordinator
    @ObservedObject var ranging: RangingCoordinator
    let pair: PairRecord
    @State private var duration: FindDuration = .default

    private var isArmed: Bool {
        switch find.state {
        case .idle, .stopped, .expired, .failed: return false
        default: return find.selectedPair?.id == pair.id
        }
    }

    /// Short camera-assist coaching, only while a UWB method is active and the
    /// platform supports camera assistance (capability-gated; text only).
    private var coachingText: String? {
        FindCoaching.cameraAssistText(method: ranging.selection?.method,
                                      state: find.state,
                                      cameraAssistSupported: NISession.deviceCapabilities.supportsCameraAssistance)
    }

    var body: some View {
        VStack(spacing: HM.space._4) {
            DirectionView(state: find.state,
                          measurement: ranging.measurement,
                          statusText: find.statusText,
                          peerName: pair.alias)

            if let coachingText {
                Text(coachingText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            if find.state == .failed, find.reason == .permissionRequired {
                Button("설정 열기") { openSettings() }
                    .buttonStyle(.bordered)
            }

            if isArmed {
                Button("찾기 중지", role: .destructive) { find.stop() }
                    .buttonStyle(.borderedProminent)
            } else {
                Picker("찾기 시간", selection: $duration) {
                    ForEach(FindDuration.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                Button("찾기 시작") { find.arm(pair: pair, duration: duration.seconds) }
                    .buttonStyle(.borderedProminent)
                    .disabled(pair.revoked)
            }

            Text("앱을 강제 종료하거나 기기가 절전 상태가 되면 상대 기기에서 나를 찾지 못할 수 있습니다.")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Spacer()
        }
        .padding(HM.space._4)
        .navigationTitle(pair.alias)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}
