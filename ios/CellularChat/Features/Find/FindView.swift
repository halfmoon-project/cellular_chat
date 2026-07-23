import SwiftUI
import UIKit
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

    private var isArmed: Bool {
        switch find.state {
        case .idle, .stopped, .expired, .failed: return false
        default: return find.selectedPair?.id == pair.id
        }
    }

    var body: some View {
        VStack(spacing: HM.space._4) {
            DirectionView(state: find.state,
                          measurement: ranging.measurement,
                          statusText: find.statusText,
                          peerName: pair.alias)

            if find.state == .failed, find.reason == .permissionRequired {
                Button("설정 열기") { openSettings() }
                    .buttonStyle(.bordered)
            }

            if isArmed {
                Button("찾기 중지", role: .destructive) { find.stop() }
                    .buttonStyle(.borderedProminent)
            } else {
                Button("찾기 시작") { find.arm(pair: pair) }
                    .buttonStyle(.borderedProminent)
                    .disabled(pair.revoked)
            }
            Spacer()
        }
        .padding(HM.space._4)
        .navigationTitle(pair.alias)
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { if isArmed { find.stop() } }
    }

    private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}
