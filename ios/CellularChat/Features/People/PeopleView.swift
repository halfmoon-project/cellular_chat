import SwiftUI
import HalfmoonTokens

/// Paired-people list: pick a pair to find, add a new pairing, or revoke one
/// (PROTOCOL_V2.md §2 local revocation).
struct PeopleView: View {
    @EnvironmentObject private var appModel: AppModel
    @State private var pairs: [PairRecord] = []
    @State private var showingPairing = false

    var body: some View {
        List {
            if pairs.isEmpty {
                ContentUnavailableView("등록된 상대가 없습니다",
                                       systemImage: "person.crop.circle.badge.plus",
                                       description: Text("QR로 상대 기기를 한 번 등록하면 이후 인터넷 없이 서로를 다시 찾을 수 있습니다."))
            }
            ForEach(pairs) { pair in
                NavigationLink {
                    FindView(pair: pair)
                } label: {
                    row(pair)
                }
                .swipeActions {
                    Button("해제", role: .destructive) { revoke(pair) }
                }
            }
        }
        .navigationTitle("사람")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showingPairing = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("상대 추가")
            }
        }
        .sheet(isPresented: $showingPairing, onDismiss: refresh) {
            PairingView(pairStore: appModel.pairStore)
        }
        .onAppear(perform: refresh)
    }

    private func row(_ pair: PairRecord) -> some View {
        VStack(alignment: .leading, spacing: HM.space._1) {
            Text(pair.alias).font(HM.typography.headingSm.font)
            Text(pair.pairRole == .a ? "내가 초대함 (A)" : "내가 참여함 (B)")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private func refresh() { pairs = appModel.pairStore.active }

    private func revoke(_ pair: PairRecord) {
        appModel.pairStore.revoke(pairId: pair.pairId)
        refresh()
    }
}
