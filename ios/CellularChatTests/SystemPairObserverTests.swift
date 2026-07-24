import XCTest
import CellularChatCore
@testable import CellularChat

/// In-memory pair-root storage so store logic runs without the Keychain.
private final class MemorySecretStore: PairSecretStore {
    private var items: [String: Data] = [:]
    func set(_ data: Data, account: String) throws { items[account] = data }
    func get(account: String) throws -> Data? { items[account] }
    func delete(account: String) { items[account] = nil }
}

/// System-pair removal reconciliation (Work Package B item 5): a stored Wi-Fi
/// Aware `pairingHandle` is dropped when its device ID leaves the OS paired set,
/// while the pair record itself (the Noise identity) stays intact.
final class SystemPairObserverTests: XCTestCase {

    private func makeStore() throws -> (PairStore, [UInt8]) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("sysobs-\(UUID().uuidString)")
        let store = PairStore(directory: dir, secrets: MemorySecretStore())
        let pid = [UInt8](repeating: 1, count: 16)
        try store.commit(
            PairRecord(pairId: pid, roleCode: 1, peerStaticPub: [UInt8](repeating: 2, count: 32),
                       negotiatedVersion: 2, alias: "P", createdAt: 1, revoked: false),
            pairRoot: [UInt8](repeating: 3, count: 32))
        store.setPairingHandle("7", pairId: pid)
        return (store, pid)
    }

    @MainActor
    func testClearsHandleWhenPairingRemoved() throws {
        let (store, pid) = try makeStore()
        let observer = SystemPairObserver(pairStore: store)
        observer.reconcile(present: [])   // device 7 no longer paired
        XCTAssertNil(store.record(forPairId: pid)?.pairingHandle)
        // The pair itself is untouched (identity is not affected by the routing hint).
        XCTAssertEqual(store.record(forPairId: pid)?.revoked, false)
        XCTAssertEqual(store.active.count, 1)
    }

    @MainActor
    func testKeepsHandleWhileStillPaired() throws {
        let (store, pid) = try makeStore()
        let observer = SystemPairObserver(pairStore: store)
        observer.reconcile(present: [7])   // device 7 still paired
        XCTAssertEqual(store.record(forPairId: pid)?.pairingHandle, "7")
    }
}
