import SwiftUI

@main
struct CellularChatApp: App {
    @StateObject private var appModel = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appModel)
        }
    }
}

/// Owns the process-wide singletons: the pair database and the Find session
/// coordinator. Pairing coordinators are created per pairing flow.
@MainActor
final class AppModel: ObservableObject {
    let pairStore = PairStore()
    let find: FindSessionCoordinator

    init() {
        find = FindSessionCoordinator(pairStore: pairStore)
    }

    func makePairingCoordinator() -> PairingCoordinator {
        PairingCoordinator(pairStore: pairStore)
    }
}
