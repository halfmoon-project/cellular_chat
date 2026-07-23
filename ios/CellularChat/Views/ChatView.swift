import SwiftUI
import UniformTypeIdentifiers
import HalfmoonTokens

struct ChatView: View {
    @EnvironmentObject private var network: PeerNetworkManager
    @State private var draft = ""
    @State private var showFileImporter = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                statusHeader

                if let activeID = network.ranging.activePeerID {
                    DirectionView(
                        ranging: network.ranging,
                        peerName: network.peers.first(where: { $0.id == activeID })?.displayName,
                        onStop: network.stopFinding
                    )
                    .padding(.vertical, HM.space._2)
                }

                content
                composer
            }
            .navigationTitle("오프라인 대화")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("나가기", role: .destructive) { network.stop() }
                }
            }
            .fileImporter(
                isPresented: $showFileImporter,
                allowedContentTypes: [.data],
                allowsMultipleSelection: false
            ) { result in
                if case .success(let urls) = result, let url = urls.first {
                    network.offerFile(at: url)
                } else if case .failure(let error) = result {
                    network.errorMessage = error.localizedDescription
                }
            }
        }
    }

    private var statusHeader: some View {
        VStack(alignment: .leading, spacing: HM.space._2) {
            Label(network.statusText, systemImage: network.peers.isEmpty ? "antenna.radiowaves.left.and.right.slash" : "antenna.radiowaves.left.and.right")
                .font(.subheadline)

            if !network.peers.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(network.peers) { peer in
                            Button {
                                network.startFinding(peerID: peer.id)
                            } label: {
                            VStack(alignment: .leading, spacing: HM.space._1) {
                                    Text(peer.displayName).fontWeight(.semibold)
                                    Text(peer.platform == "ios" ? "iPhone · 방향 찾기" : "Android · 방향 찾기")
                                        .font(.caption2)
                                }
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
            }
        }
        .padding(HM.space._4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(HM.color.bgSubtle)
    }

    private var content: some View {
        ScrollViewReader { proxy in
            List {
                if !network.pendingFileOffers.isEmpty {
                    Section("받을 파일") {
                        ForEach(network.pendingFileOffers) { offer in
                            VStack(alignment: .leading, spacing: HM.space._2) {
                                Text(offer.name).fontWeight(.semibold)
                                Text("\(offer.peerName) · \(ByteCountFormatter.string(fromByteCount: Int64(offer.size), countStyle: .file))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                HStack {
                                    Button("받기") { network.respond(to: offer, accepted: true) }
                                        .buttonStyle(.borderedProminent)
                                    Button("거절") { network.respond(to: offer, accepted: false) }
                                        .buttonStyle(.bordered)
                                }
                            }
                        }
                    }
                }

                if !network.receivedFiles.isEmpty {
                    Section("받은 파일") {
                        ForEach(network.receivedFiles) { file in
                            ShareLink(item: file.localURL) {
                                Label(file.name, systemImage: "square.and.arrow.up")
                            }
                        }
                    }
                }

                Section("메시지") {
                    if network.messages.isEmpty {
                        Text("연결된 기기에 첫 메시지를 보내보세요.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(network.messages) { message in
                        VStack(alignment: message.isLocal ? .trailing : .leading, spacing: HM.space._1) {
                            Text(message.isLocal ? "나" : message.senderName)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(message.text)
                                .foregroundStyle(message.isLocal ? HM.color.actionPrimaryFg : HM.color.fgDefault)
                                .padding(.horizontal, HM.space._3)
                                .padding(.vertical, HM.space._2)
                                .background(
                                    message.isLocal ? HM.color.actionPrimaryBg : HM.color.bgMuted,
                                    in: RoundedRectangle(cornerRadius: HM.radius.lg)
                                )
                        }
                        .frame(maxWidth: .infinity, alignment: message.isLocal ? .trailing : .leading)
                        .id(message.id)
                    }
                }
            }
            .listStyle(.plain)
            .onChange(of: network.messages.count) { _ in
                if let id = network.messages.last?.id {
                    withAnimation { proxy.scrollTo(id, anchor: .bottom) }
                }
            }
        }
    }

    private var composer: some View {
        HStack(spacing: HM.space._3) {
            Button {
                showFileImporter = true
            } label: {
                Image(systemName: "paperclip")
                    .font(.title3)
            }
            .disabled(network.peers.isEmpty)

            TextField("메시지", text: $draft, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(1...4)
                .onSubmit(send)

            Button(action: send) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.title2)
            }
            .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || network.peers.isEmpty)
        }
        .padding(HM.space._4)
        .background(HM.color.bgDefault)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(HM.color.borderDefault)
                .frame(height: 1)
        }
    }

    private func send() {
        network.sendChat(draft)
        draft = ""
    }
}
