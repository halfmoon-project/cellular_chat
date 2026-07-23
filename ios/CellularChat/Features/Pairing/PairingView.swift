import SwiftUI
import UIKit
import HalfmoonTokens

/// The pairing flow (PROTOCOL_V2.md §6): invite (show QR + copyable string) or
/// join (scan/paste), then confirm the shared fingerprint before commit.
struct PairingView: View {
    @EnvironmentObject private var appModel: AppModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var coordinator: PairingCoordinator
    @State private var mode: Mode = .choose
    @State private var pasteText = ""
    @State private var alias = ""

    enum Mode { case choose, invite, joinScan, joinPaste }

    init(pairStore: PairStore) {
        _coordinator = StateObject(wrappedValue: PairingCoordinator(pairStore: pairStore))
    }

    var body: some View {
        NavigationStack {
            content
                .padding(HM.space._4)
                .navigationTitle("상대 등록")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("닫기") { coordinator.cancel(); dismiss() }
                    }
                }
                .onChange(of: coordinator.step) { _, step in
                    if step == .done { dismiss() }
                }
        }
    }

    @ViewBuilder private var content: some View {
        if case .awaitingFingerprint = coordinator.step {
            fingerprintConfirm
        } else if case let .failed(message) = coordinator.step {
            failure(message)
        } else {
            switch mode {
            case .choose: chooser
            case .invite: inviteScreen
            case .joinScan: scanScreen
            case .joinPaste: pasteScreen
            }
        }
    }

    private var chooser: some View {
        VStack(spacing: HM.space._4) {
            TextField("상대 이름 (선택)", text: $alias)
                .textFieldStyle(.roundedBorder)
            Button("QR 코드로 초대") { startInvite() }
                .buttonStyle(.borderedProminent)
            Button("QR 코드 스캔으로 참여") { mode = .joinScan }
                .buttonStyle(.bordered)
            Button("코드 붙여넣기로 참여") { mode = .joinPaste }
                .buttonStyle(.bordered)
            Spacer()
        }
    }

    private var inviteScreen: some View {
        VStack(spacing: HM.space._4) {
            if let image = coordinator.qrImage {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 240)
                    .accessibilityLabel("초대 QR 코드")
            }
            if let text = coordinator.invitationText {
                Text(text)
                    .font(.footnote.monospaced())
                    .lineLimit(3)
                    .textSelection(.enabled)
                Button("코드 복사") { UIPasteboard.general.string = text }
                    .buttonStyle(.bordered)
            }
            Text(stepText).font(.caption).foregroundStyle(.secondary)
            Spacer()
        }
    }

    private var scanScreen: some View {
        VStack {
            QRScannerView { code in join(with: code) }
                .clipShape(RoundedRectangle(cornerRadius: HM.radius.lg))
            Text("상대의 초대 QR을 비추세요").font(.caption).foregroundStyle(.secondary)
        }
    }

    private var pasteScreen: some View {
        VStack(spacing: HM.space._3) {
            TextField("CF2:...", text: $pasteText, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(3...6)
            Button("참여") { join(with: pasteText) }
                .buttonStyle(.borderedProminent)
                .disabled(pasteText.isEmpty)
            Spacer()
        }
    }

    private var fingerprintConfirm: some View {
        VStack(spacing: HM.space._4) {
            Text("확인 코드").font(HM.typography.headingSm.font)
            Text(coordinator.fingerprint ?? "------")
                .font(.system(.largeTitle, design: .monospaced, weight: .bold))
                .tracking(4)
            Text("두 기기에 같은 6자리 숫자가 보이면 확인을 누르세요.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("확인하고 등록") { coordinator.confirmFingerprint() }
                .buttonStyle(.borderedProminent)
            Button("취소", role: .destructive) { coordinator.cancel(); dismiss() }
            Spacer()
        }
    }

    private func failure(_ message: String) -> some View {
        VStack(spacing: HM.space._3) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 44))
                .foregroundStyle(HM.color.statusDanger)
            Text(message).multilineTextAlignment(.center)
            Button("닫기") { dismiss() }.buttonStyle(.bordered)
            Spacer()
        }
    }

    private var stepText: String {
        switch coordinator.step {
        case .connecting: return "상대 기기를 기다리는 중…"
        case .handshaking, .binding, .proving: return "보안 채널 설정 중…"
        default: return "상대가 이 코드를 스캔하기를 기다립니다."
        }
    }

    // MARK: actions

    private func startInvite() {
        guard coordinator.makeInvitation() != nil else { return }
        mode = .invite
        // Inviter is the BLE advertiser/peripheral; NNpsk0 with the invitation
        // secret is the real authenticator, so the rendezvous token is unused here.
        let transport = BLETransport(role: .peripheral,
                                     localToken: { [UInt8](repeating: 0, count: 16) },
                                     acceptsPeerToken: { _ in true })
        coordinator.startAsInviter(over: transport, alias: aliasOrDefault)
    }

    private func join(with code: String) {
        let transport = BLETransport(role: .central,
                                     localToken: { [UInt8](repeating: 0, count: 16) },
                                     acceptsPeerToken: { _ in true })
        coordinator.startAsJoiner(inviteText: code.trimmingCharacters(in: .whitespacesAndNewlines),
                                  over: transport, alias: aliasOrDefault)
    }

    private var aliasOrDefault: String {
        alias.isEmpty ? "상대 기기" : alias
    }
}
