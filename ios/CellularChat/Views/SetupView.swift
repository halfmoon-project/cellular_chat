import SwiftUI
import HalfmoonTokens

struct SetupView: View {
    @EnvironmentObject private var network: PeerNetworkManager
    @AppStorage("cellchat.display-name") private var displayName = ""
    @State private var connectionID = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("표시 이름", text: $displayName)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                    SecureField("6~32자 연결 ID", text: $connectionID)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                } header: {
                    Text("오프라인 방")
                } footer: {
                    Text("두 기기에 같은 연결 ID를 입력하세요. ID 자체는 네트워크로 전송되지 않습니다.")
                }

                Section {
                    Button("안전한 ID 만들기") {
                        connectionID = Self.generatedConnectionID()
                    }
                    Button("주변 기기 찾기") {
                        network.start(displayName: displayName, connectionID: connectionID)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || connectionID.isEmpty)
                }

                Section("필요 조건") {
                    Label("인터넷이나 셀룰러 데이터는 필요하지 않음", systemImage: "network.slash")
                    Label("iOS·Android는 같은 로컬 Wi-Fi 또는 핫스팟 권장", systemImage: "wifi")
                    Label("방향 찾기는 양쪽 UWB 지원 기기에서만 가능", systemImage: "location.north.circle")
                }
            }
            .scrollContentBackground(.hidden)
            .background(HM.color.bgSubtle)
            .navigationTitle("Cellular Chat")
        }
    }

    private static func generatedConnectionID() -> String {
        let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        return "CC-" + String((0..<13).compactMap { _ in alphabet.randomElement() })
    }
}
