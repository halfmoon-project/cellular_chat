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

/// Pair settings screen backing logic (Work Package C item 3): fingerprint format,
/// alias rename persistence, and creation-date preservation.
final class PairSettingsTests: XCTestCase {

    private let pairId = (0..<16).map { UInt8($0) }

    func testFingerprintMatchesPairingDerivationForBothRoles() {
        let local = [UInt8](repeating: 0xA1, count: 32)
        let peer = [UInt8](repeating: 0xB2, count: 32)

        let recA = PairRecord(pairId: pairId, roleCode: 1, peerStaticPub: peer,
                              negotiatedVersion: 2, alias: "x", createdAt: 1, revoked: false)
        XCTAssertEqual(recA.fingerprintDisplay(localStaticPub: local),
                       Derivations.fingerprintDisplay(pairId: pairId, staticA: local, staticB: peer))

        let recB = PairRecord(pairId: pairId, roleCode: 2, peerStaticPub: peer,
                              negotiatedVersion: 2, alias: "x", createdAt: 1, revoked: false)
        XCTAssertEqual(recB.fingerprintDisplay(localStaticPub: local),
                       Derivations.fingerprintDisplay(pairId: pairId, staticA: peer, staticB: local))
    }

    /// Both phones of one pair must display the same 6 digits: A sees (local=A,
    /// peer=B); B sees (local=B, peer=A) yet reconstructs the same (staticA, staticB).
    func testBothSidesShowTheSameFingerprint() {
        let a = [UInt8](repeating: 0x11, count: 32)
        let b = [UInt8](repeating: 0x22, count: 32)
        let sideA = PairRecord(pairId: pairId, roleCode: 1, peerStaticPub: b,
                               negotiatedVersion: 2, alias: "x", createdAt: 1, revoked: false)
        let sideB = PairRecord(pairId: pairId, roleCode: 2, peerStaticPub: a,
                               negotiatedVersion: 2, alias: "y", createdAt: 1, revoked: false)
        XCTAssertEqual(sideA.fingerprintDisplay(localStaticPub: a),
                       sideB.fingerprintDisplay(localStaticPub: b))
    }

    func testRenamePersistsAndPreservesCreationDateAndSecret() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("rename-\(UUID().uuidString)")
        let secrets = MemorySecretStore()
        let store = PairStore(directory: dir, secrets: secrets)
        let root = [UInt8](repeating: 7, count: 32)
        let record = PairRecord(pairId: pairId, roleCode: 1,
                                peerStaticPub: [UInt8](repeating: 2, count: 32),
                                negotiatedVersion: 2, alias: "old", createdAt: 1234, revoked: false)
        try store.commit(record, pairRoot: root)

        // Rename via the same public path the settings screen uses.
        var updated = record
        updated.alias = "new"
        try store.commit(updated, pairRoot: try store.pairRoot(record))

        // Reload from disk to prove the rename persisted.
        let reloaded = PairStore(directory: dir, secrets: secrets)
        let got = reloaded.record(forPairId: pairId)
        XCTAssertEqual(got?.alias, "new")
        XCTAssertEqual(got?.createdAt, 1234)
        XCTAssertEqual(try reloaded.pairRoot(record), root)
    }
}
