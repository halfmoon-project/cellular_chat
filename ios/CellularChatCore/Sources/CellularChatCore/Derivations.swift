import Foundation

/// Pair-specific key derivations (PROTOCOL_V2.md §2). All outputs 32 bytes
/// except the fingerprint display string.
public enum Derivations {

    public static func pairingPsk(secret: [UInt8]) -> [UInt8] {
        Crypto.hmacSHA256(key: secret, data: Array("cellfind/v2 pairing psk".utf8))
    }

    /// pairRoot = HKDF(salt = h_pairing, ikm = pairingPsk,
    ///                 info = "cellfind/v2 pair root" || staticA || staticB)
    public static func pairRoot(pairingHandshakeHash: [UInt8],
                                pairingPsk: [UInt8],
                                staticA: [UInt8],
                                staticB: [UInt8]) -> [UInt8] {
        let info = Array("cellfind/v2 pair root".utf8) + staticA + staticB
        return Crypto.hkdfRFC5869(salt: pairingHandshakeHash, ikm: pairingPsk, info: info)
    }

    public static func sessionPsk(pairRoot: [UInt8]) -> [UInt8] {
        Crypto.hmacSHA256(key: pairRoot, data: Array("cellfind/v2 session psk".utf8))
    }

    public static func discoveryKey(pairRoot: [UInt8], role: PairRole) -> [UInt8] {
        let label = role == .a ? "cellfind/v2 discovery A" : "cellfind/v2 discovery B"
        return Crypto.hmacSHA256(key: pairRoot, data: Array(label.utf8))
    }

    public static func confirmMAC(pairRoot: [UInt8], role: PairRole) -> [UInt8] {
        let roleByte: UInt8 = role == .a ? 0x41 : 0x42
        return Crypto.hmacSHA256(
            key: pairRoot, data: Array("cellfind/v2 confirm".utf8) + [roleByte])
    }

    public static func fingerprint(pairId: [UInt8], staticA: [UInt8], staticB: [UInt8]) -> [UInt8] {
        Crypto.sha256(Array("cellfind/v2 fingerprint".utf8) + pairId + staticA + staticB)
    }

    /// decimal(u64BE(fpr[0..8]) mod 1_000_000), zero-padded to 6 digits.
    public static func fingerprintDisplay(pairId: [UInt8], staticA: [UInt8], staticB: [UInt8]) -> String {
        let fpr = fingerprint(pairId: pairId, staticA: staticA, staticB: staticB)
        var value: UInt64 = 0
        for i in 0..<8 { value = (value << 8) | UInt64(fpr[i]) }
        let digits = value % 1_000_000
        return String(format: "%06d", digits)
    }
}

/// Role A = inviter, Role B = joiner (PROTOCOL_V2.md §2). Permanent per pair.
public enum PairRole: Equatable {
    case a
    case b
}
