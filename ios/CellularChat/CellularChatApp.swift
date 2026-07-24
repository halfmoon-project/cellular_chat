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
    private let systemPairObserver: SystemPairObserver

    init() {
        find = FindSessionCoordinator(pairStore: pairStore)
        systemPairObserver = SystemPairObserver(pairStore: pairStore)
        // Watch OS Wi-Fi Aware pair removals to drop stale routing hints (§8).
        systemPairObserver.start()
    }

    func makePairingCoordinator() -> PairingCoordinator {
        PairingCoordinator(pairStore: pairStore)
    }
}
