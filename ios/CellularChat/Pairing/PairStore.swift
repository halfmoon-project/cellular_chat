import Foundation
import CellularChatCore

/// Backing store for the per-pair `pairRoot` secret. The production store is the
/// device Keychain; the init seam lets tests inject an in-memory implementation
/// so persistence/revocation logic runs without Keychain entitlements.
protocol PairSecretStore {
    func set(_ data: Data, account: String) throws
    func get(account: String) throws -> Data?
    func delete(account: String)
}

/// Default Keychain-backed secret storage (PROTOCOL_V2.md §2).
struct KeychainSecretStore: PairSecretStore {
    let service: String
    func set(_ data: Data, account: String) throws { try Keychain.set(data, service: service, account: account) }
    func get(account: String) throws -> Data? { try Keychain.get(service: service, account: account) }
    func delete(account: String) { Keychain.delete(service: service, account: account) }
}

/// Persistent pairing database. Secret key material (`pairRoot`) lives in the
/// Keychain; the metadata record lives in an app-private file excluded from
/// backup (PROTOCOL_V2.md §2). Supports local revocation.
final class PairStore {
    private static let keychainService = "com.cellularchat.pair.root"

    private let fileURL: URL
    private let deviceKeys = DeviceKeyStore()
    private let secrets: PairSecretStore
    private var records: [String: PairRecord] = [:]   // keyed by PairRecord.id

    init(directory: URL? = nil, secrets: PairSecretStore? = nil) {
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory,
                                                         in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        self.fileURL = base.appendingPathComponent("pairs.json")
        self.secrets = secrets ?? KeychainSecretStore(service: Self.keychainService)
        excludeFromBackup(base)
        load()
    }

    // MARK: Query

    var all: [PairRecord] { Array(records.values).sorted { $0.createdAt < $1.createdAt } }
    var active: [PairRecord] { all.filter { !$0.revoked } }

    func record(forPairId pairId: [UInt8]) -> PairRecord? {
        records[Data(pairId).base64EncodedString()]
    }

    /// The non-revoked record whose peer discovery key matches `candidateToken`
    /// for the current ±1 epoch (PROTOCOL_V2.md §7 rendezvous resolution).
    func resolve(token candidateToken: [UInt8], nowUnixSeconds: UInt64) -> PairRecord? {
        for record in active {
            guard let root = try? pairRoot(record) else { continue }
            let peerDiscKey = Derivations.discoveryKey(pairRoot: root, role: record.peerRole)
            if Discovery.accepts(candidate: candidateToken, discoveryKey: peerDiscKey,
                                 unixSeconds: nowUnixSeconds, role: record.peerRole) {
                return record
            }
        }
        return nil
    }

    // MARK: Mutation

    /// Persist a committed pairing plus its `pairRoot` secret.
    func commit(_ record: PairRecord, pairRoot root: [UInt8]) throws {
        try secrets.set(Data(root), account: record.id)
        records[record.id] = record
        save()
    }

    func pairRoot(_ record: PairRecord) throws -> [UInt8] {
        guard let data = try secrets.get(account: record.id) else {
            throw PairStoreError.missingKeyMaterial
        }
        return Array(data)
    }

    /// Record the peer's platform once learned from the capability exchange
    /// (§8/§11) so a later Find arm can derive same-platform transport roles
    /// (§9/§10). A no-op when unchanged, so it does not rewrite on every session.
    func setPeerPlatform(_ platform: OSKind, pairId: [UInt8]) {
        let key = Data(pairId).base64EncodedString()
        guard var record = records[key], record.peerPlatform != platform else { return }
        record.peerPlatform = platform
        records[key] = record
        save()
    }

    /// Store or clear the Wi-Fi Aware system-pairing association (§8): an
    /// install-scoped routing hint, never the security identity. Cleared when the
    /// system pairing is removed (§8 observation); a no-op when unchanged.
    func setPairingHandle(_ handle: String?, pairId: [UInt8]) {
        let key = Data(pairId).base64EncodedString()
        guard var record = records[key], record.pairingHandle != handle else { return }
        record.pairingHandle = handle
        records[key] = record
        save()
    }

    /// Local revocation: a revoked pair never answers a session handshake (§8).
    func revoke(pairId: [UInt8]) {
        let key = Data(pairId).base64EncodedString()
        guard var record = records[key] else { return }
        record.revoked = true
        records[key] = record
        save()
    }

    func delete(pairId: [UInt8]) {
        let key = Data(pairId).base64EncodedString()
        secrets.delete(account: key)
        deviceKeys.deleteStaticKey(pairId: pairId)
        records[key] = nil
        save()
    }

    // MARK: Persistence

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let list = try? JSONDecoder().decode([PairRecord].self, from: data) else { return }
        records = Dictionary(uniqueKeysWithValues: list.map { ($0.id, $0) })
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(all) else { return }
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private func excludeFromBackup(_ url: URL) {
        var url = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }

    enum PairStoreError: Error { case missingKeyMaterial }
}
