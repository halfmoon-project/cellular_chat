import Foundation
import CellularChatCore

/// Persistent pairing database. Secret key material (`pairRoot`) lives in the
/// Keychain; the metadata record lives in an app-private file excluded from
/// backup (PROTOCOL_V2.md §2). Supports local revocation.
final class PairStore {
    private static let keychainService = "com.cellularchat.pair.root"

    private let fileURL: URL
    private let deviceKeys = DeviceKeyStore()
    private var records: [String: PairRecord] = [:]   // keyed by PairRecord.id

    init(directory: URL? = nil) {
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory,
                                                         in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        self.fileURL = base.appendingPathComponent("pairs.json")
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
        try Keychain.set(Data(root), service: Self.keychainService, account: record.id)
        records[record.id] = record
        save()
    }

    func pairRoot(_ record: PairRecord) throws -> [UInt8] {
        guard let data = try Keychain.get(service: Self.keychainService, account: record.id) else {
            throw PairStoreError.missingKeyMaterial
        }
        return Array(data)
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
        Keychain.delete(service: Self.keychainService, account: key)
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
