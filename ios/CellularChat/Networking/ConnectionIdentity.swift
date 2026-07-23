import CryptoKit
import Foundation

enum ConnectionIDError: LocalizedError, Equatable {
    case invalidLength
    case invalidCharacters

    var errorDescription: String? {
        switch self {
        case .invalidLength:
            return "연결 ID는 6~32자여야 합니다."
        case .invalidCharacters:
            return "연결 ID에는 영문자, 숫자, 하이픈(-)만 사용할 수 있습니다."
        }
    }
}

struct ConnectionIdentity {
    static let authenticationPrefix = Data("cellchat-v1\0".utf8)

    let normalizedID: String
    let roomHash: String
    let authenticationKey: Data

    init(connectionID: String) throws {
        let normalized = try Self.normalize(connectionID)
        normalizedID = normalized
        roomHash = Self.hex(Data(SHA256.hash(data: Data(normalized.utf8))))

        var keyMaterial = Self.authenticationPrefix
        keyMaterial.append(contentsOf: normalized.utf8)
        authenticationKey = Data(SHA256.hash(data: keyMaterial))
    }

    static func normalize(_ value: String) throws -> String {
        let trimmed = value.trimmingCharacters(in: CharacterSet(charactersIn: " "))
        guard (6...32).contains(trimmed.utf8.count) else {
            throw ConnectionIDError.invalidLength
        }

        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-")
        guard trimmed.unicodeScalars.allSatisfy(allowed.contains), trimmed.unicodeScalars.count == trimmed.utf8.count else {
            throw ConnectionIDError.invalidCharacters
        }

        return trimmed.uppercased(with: Locale(identifier: "en_US_POSIX"))
    }

    func proof(
        role: AuthenticationRole,
        clientDeviceID: String,
        serverDeviceID: String,
        clientNonce: String,
        serverNonce: String
    ) -> String {
        let transcript = [
            role.rawValue,
            "v1",
            clientDeviceID,
            serverDeviceID,
            clientNonce,
            serverNonce
        ].joined(separator: "\0")
        let key = SymmetricKey(data: authenticationKey)
        let code = HMAC<SHA256>.authenticationCode(for: Data(transcript.utf8), using: key)
        return Data(code).base64EncodedString()
    }

    func validates(
        proof: String,
        role: AuthenticationRole,
        clientDeviceID: String,
        serverDeviceID: String,
        clientNonce: String,
        serverNonce: String
    ) -> Bool {
        guard let code = Data(base64Encoded: proof) else { return false }
        let transcript = [
            role.rawValue,
            "v1",
            clientDeviceID,
            serverDeviceID,
            clientNonce,
            serverNonce
        ].joined(separator: "\0")
        return HMAC<SHA256>.isValidAuthenticationCode(
            code,
            authenticating: Data(transcript.utf8),
            using: SymmetricKey(data: authenticationKey)
        )
    }

    static func randomNonce() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(status == errSecSuccess, "Secure random generation failed")
        return Data(bytes).base64EncodedString()
    }

    private static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}

enum AuthenticationRole: String {
    case client
    case server
}

enum DeviceIdentity {
    private static let defaultsKey = "cellchat.device-id"

    static func persistentID(defaults: UserDefaults = .standard) -> String {
        if let stored = defaults.string(forKey: defaultsKey),
           let uuid = UUID(uuidString: stored) {
            return uuid.uuidString.lowercased()
        }

        let value = UUID().uuidString.lowercased()
        defaults.set(value, forKey: defaultsKey)
        return value
    }
}
