import Foundation
import CryptoKit

/// Keychain-backed storage of secret bytes, pinned to this device only and
/// excluded from iCloud/backup (PROTOCOL_V2.md §2 iOS storage requirement).
enum Keychain {

    static func set(_ data: Data, service: String, account: String) throws {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        add[kSecAttrSynchronizable as String] = false
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.status(status) }
    }

    static func get(service: String, account: String) throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainError.status(status) }
        return out as? Data
    }

    static func delete(service: String, account: String) {
        SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ] as CFDictionary)
    }

    enum KeychainError: Error { case status(OSStatus) }
}

/// Pair-specific Curve25519 static keys (PROTOCOL_V2.md §2). A fresh key pair is
/// generated per pairing so two pairings cannot be correlated. The private key
/// never leaves the Keychain.
struct DeviceKeyStore {
    private static let service = "com.cellularchat.pair.static"

    private func account(_ pairId: [UInt8]) -> String { Data(pairId).base64EncodedString() }

    /// Generate and persist a fresh static key pair for a new pairing.
    @discardableResult
    func createStaticKey(pairId: [UInt8]) throws -> Curve25519.KeyAgreement.PrivateKey {
        let key = Curve25519.KeyAgreement.PrivateKey()
        try Keychain.set(key.rawRepresentation, service: Self.service, account: account(pairId))
        return key
    }

    func staticPrivateKey(pairId: [UInt8]) throws -> Curve25519.KeyAgreement.PrivateKey? {
        guard let raw = try Keychain.get(service: Self.service, account: account(pairId)) else { return nil }
        return try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw)
    }

    func staticPublicKey(pairId: [UInt8]) throws -> [UInt8]? {
        try staticPrivateKey(pairId: pairId).map { Array($0.publicKey.rawRepresentation) }
    }

    func deleteStaticKey(pairId: [UInt8]) {
        Keychain.delete(service: Self.service, account: account(pairId))
    }
}
