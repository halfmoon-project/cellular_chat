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

/// Revocation blocks arming a Find session (Work Package A item 4): a revoked pair
/// never arms and the §13 `revoked` reason is surfaced (PROTOCOL_V2.md §8/§13).
final class FindSessionRevocationTests: XCTestCase {

    @MainActor
    func testArmRefusesRevokedPairWithRevokedReason() {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("find-revoke-\(UUID().uuidString)")
        let store = PairStore(directory: dir, secrets: MemorySecretStore())
        let coordinator = FindSessionCoordinator(pairStore: store)

        let revoked = PairRecord(pairId: [UInt8](repeating: 5, count: 16), roleCode: 1,
                                 peerStaticPub: [UInt8](repeating: 2, count: 32),
                                 negotiatedVersion: 2, alias: "gone", createdAt: 1, revoked: true)
        coordinator.arm(pair: revoked)

        XCTAssertEqual(coordinator.state, .idle)   // never left idle
        XCTAssertEqual(coordinator.reason, .revoked)
    }
}
