import SwiftUI

@main
struct CellularChatApp: App {
    @StateObject private var network = PeerNetworkManager()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(network)
        }
    }
}
