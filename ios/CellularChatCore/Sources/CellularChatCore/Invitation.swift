import Foundation

/// Invitation (PROTOCOL_V2.md §4).
/// invite = [2, pairId(16), secret(32), createdAt]; text = "CF2:" || base64url_nopad.
public struct Invitation: Equatable {
    public let pairId: [UInt8]
    public let secret: [UInt8]
    public let createdAt: UInt64

    public init(pairId: [UInt8], secret: [UInt8], createdAt: UInt64) {
        self.pairId = pairId
        self.secret = secret
        self.createdAt = createdAt
    }

    public static let version: UInt64 = 2
    public static let maxAgeSeconds: UInt64 = 15 * 60   // 900
    public static let maxFutureSkewSeconds: UInt64 = 2 * 60  // 120
    private static let prefix = "CF2:"

    public func encodedCBOR() -> [UInt8] {
        CBORCoder.encode(.array([
            .uint(Invitation.version),
            .bytes(pairId),
            .bytes(secret),
            .uint(createdAt),
        ]))
    }

    public func text() -> String {
        Invitation.prefix + Invitation.base64urlNoPad(encodedCBOR())
    }

    /// Parse and validate against the acceptance window (§4).
    public static func parse(text: String, nowUnixSeconds: UInt64) throws -> Invitation {
        guard text.hasPrefix(prefix) else { throw ProtocolError.invalidInvitation }
        let body = String(text.dropFirst(prefix.count))
        guard let raw = base64urlDecode(body) else { throw ProtocolError.invalidInvitation }

        let cbor: CBOR
        do {
            cbor = try CBORCoder.decode(raw)
        } catch {
            throw ProtocolError.invalidInvitation
        }
        guard case let .array(items) = cbor, items.count == 4 else {
            throw ProtocolError.invalidInvitation
        }
        guard case let .uint(ver) = items[0], ver == version else {
            throw ProtocolError.invalidInvitation
        }
        guard case let .bytes(pairId) = items[1], pairId.count == 16 else {
            throw ProtocolError.invalidInvitation
        }
        guard case let .bytes(secret) = items[2], secret.count == 32 else {
            throw ProtocolError.invalidInvitation
        }
        guard case let .uint(createdAt) = items[3] else {
            throw ProtocolError.invalidInvitation
        }
        // Time window: not more than 15 min in the past, not more than 2 min in the future.
        if createdAt <= nowUnixSeconds {
            if nowUnixSeconds - createdAt > maxAgeSeconds { throw ProtocolError.invalidInvitation }
        } else {
            if createdAt - nowUnixSeconds > maxFutureSkewSeconds { throw ProtocolError.invalidInvitation }
        }
        return Invitation(pairId: pairId, secret: secret, createdAt: createdAt)
    }

    // MARK: base64url without padding

    static func base64urlNoPad(_ data: [UInt8]) -> String {
        let b64 = Data(data).base64EncodedString()
        return b64.replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func base64urlDecode(_ s: String) -> [UInt8]? {
        var str = s.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        // Reject non-base64url characters explicitly (e.g. "!!!!").
        let allowed = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")
        guard str.allSatisfy({ allowed.contains($0) }) else { return nil }
        while str.count % 4 != 0 { str += "=" }
        guard let data = Data(base64Encoded: str) else { return nil }
        return Array(data)
    }
}
