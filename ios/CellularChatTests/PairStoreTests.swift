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

/// PairStore persistence + revocation (Work Package A item 3).
final class PairStoreTests: XCTestCase {

    private func tempDir() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("pairstore-\(UUID().uuidString)")
    }

    private func record(_ id: UInt8) -> PairRecord {
        PairRecord(pairId: [UInt8](repeating: id, count: 16), roleCode: 1,
                   peerStaticPub: [UInt8](repeating: 2, count: 32), negotiatedVersion: 2,
                   alias: "P\(id)", createdAt: 1, revoked: false)
    }

    func testSaveReloadPreservesRecords() throws {
        let dir = tempDir()
        let store = PairStore(directory: dir, secrets: MemorySecretStore())
        try store.commit(record(1), pairRoot: [UInt8](repeating: 3, count: 32))
        // A fresh instance (simulating an app restart) reloads from disk.
        let reopened = PairStore(directory: dir, secrets: MemorySecretStore())
        XCTAssertEqual(reopened.all.count, 1)
        XCTAssertNotNil(reopened.record(forPairId: [UInt8](repeating: 1, count: 16)))
    }

    func testRevokeExcludesFromActiveButKeepsRecord() throws {
        let dir = tempDir()
        let store = PairStore(directory: dir, secrets: MemorySecretStore())
        try store.commit(record(1), pairRoot: [UInt8](repeating: 3, count: 32))
        store.revoke(pairId: [UInt8](repeating: 1, count: 16))
        XCTAssertTrue(store.active.isEmpty)
        XCTAssertEqual(store.all.count, 1)
        XCTAssertEqual(store.record(forPairId: [UInt8](repeating: 1, count: 16))?.revoked, true)
    }

    /// Peer platform learned via the capability exchange persists across restart
    /// so a later Find arm can derive same-platform roles (Work Package B item 2).
    func testSetPeerPlatformPersistsAcrossReload() throws {
        let dir = tempDir()
        let store = PairStore(directory: dir, secrets: MemorySecretStore())
        try store.commit(record(1), pairRoot: [UInt8](repeating: 3, count: 32))
        let pid = [UInt8](repeating: 1, count: 16)
        XCTAssertNil(store.record(forPairId: pid)?.peerPlatform)
        store.setPeerPlatform(.ios, pairId: pid)
        XCTAssertEqual(store.record(forPairId: pid)?.peerPlatform, .ios)
        let reopened = PairStore(directory: dir, secrets: MemorySecretStore())
        XCTAssertEqual(reopened.record(forPairId: pid)?.peerPlatform, .ios)
    }

    /// The Wi-Fi Aware pairing handle is a routing hint that can be stored and
    /// cleared without touching identity (Work Package B items 4/5).
    func testSetPairingHandleStoreThenClear() throws {
        let dir = tempDir()
        let store = PairStore(directory: dir, secrets: MemorySecretStore())
        try store.commit(record(1), pairRoot: [UInt8](repeating: 3, count: 32))
        let pid = [UInt8](repeating: 1, count: 16)
        store.setPairingHandle("42", pairId: pid)
        XCTAssertEqual(store.record(forPairId: pid)?.pairingHandle, "42")
        store.setPairingHandle(nil, pairId: pid)
        XCTAssertNil(store.record(forPairId: pid)?.pairingHandle)
    }
}
