import SwiftUI
import CellularChatCore
import HalfmoonTokens

/// Per-pair settings (PROTOCOL_V2.md §2): rename the alias, show the pinned peer
/// key fingerprint and creation date, and revoke the pairing with confirmation.
struct PairSettingsView: View {
    @EnvironmentObject private var appModel: AppModel
    @Environment(\.dismiss) private var dismiss
    let pair: PairRecord

    @State private var alias: String
    @State private var showRevokeConfirm = false

    init(pair: PairRecord) {
        self.pair = pair
        _alias = State(initialValue: pair.alias)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("이름") {
                    TextField("상대 이름", text: $alias)
                        .onSubmit(saveAlias)
                }
                Section("확인 코드") {
                    Text(fingerprint)
                        .font(.system(.title2, design: .monospaced, weight: .bold))
                        .tracking(4)
                    Text("등록할 때 두 기기에 표시된 6자리 숫자입니다.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section("등록 정보") {
                    LabeledContent("등록한 날짜", value: createdText)
                    LabeledContent("내 역할", value: pair.pairRole == .a ? "초대함 (A)" : "참여함 (B)")
                }
                Section {
                    Button("페어 해제", role: .destructive) { showRevokeConfirm = true }
                }
            }
            .navigationTitle(pair.alias)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("완료") { saveAlias(); dismiss() }
                }
            }
            .confirmationDialog("이 페어를 해제하면 다시 등록해야 서로를 찾을 수 있습니다.",
                                isPresented: $showRevokeConfirm, titleVisibility: .visible) {
                Button("해제", role: .destructive) { revoke() }
                Button("취소", role: .cancel) {}
            }
        }
    }

    private var fingerprint: String {
        guard let localStatic = try? DeviceKeyStore().staticPublicKey(pairId: pair.pairId) else {
            return "------"
        }
        return pair.fingerprintDisplay(localStaticPub: localStatic)
    }

    private var createdText: String {
        let date = Date(timeIntervalSince1970: TimeInterval(pair.createdAt))
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    private func saveAlias() {
        let trimmed = alias.trimmingCharacters(in: .whitespacesAndNewlines)
        // Persist through the existing PairStore API: re-commit the record with
        // the unchanged pairRoot and the new alias.
        guard !trimmed.isEmpty, trimmed != pair.alias,
              let root = try? appModel.pairStore.pairRoot(pair) else { return }
        var updated = pair
        updated.alias = trimmed
        try? appModel.pairStore.commit(updated, pairRoot: root)
    }

    private func revoke() {
        appModel.pairStore.revoke(pairId: pair.pairId)
        dismiss()
    }
}
