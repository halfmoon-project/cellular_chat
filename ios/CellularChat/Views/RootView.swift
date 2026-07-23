import SwiftUI
import HalfmoonTokens

struct RootView: View {
    @EnvironmentObject private var network: PeerNetworkManager

    var body: some View {
        Group {
            if network.isRunning {
                ChatView()
            } else {
                SetupView()
            }
        }
        .tint(HM.color.actionPrimaryBg)
        .foregroundStyle(HM.color.fgDefault)
        .background(HM.color.bgDefault)
        .alert("오류", isPresented: Binding(
            get: { network.errorMessage != nil },
            set: { if !$0 { network.errorMessage = nil } }
        )) {
            Button("확인", role: .cancel) { network.errorMessage = nil }
        } message: {
            Text(network.errorMessage ?? "알 수 없는 오류")
        }
    }
}
