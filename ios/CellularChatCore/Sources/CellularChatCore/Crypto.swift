import Foundation
import CryptoKit

/// Cryptographic primitives for the suite in PROTOCOL_V2.md §1:
/// X25519 / ChaCha20-Poly1305 (IETF) / SHA-256 / HKDF-SHA256, all via CryptoKit.
enum Crypto {

    static func sha256(_ data: [UInt8]) -> [UInt8] {
        Array(SHA256.hash(data: data))
    }

    static func hmacSHA256(key: [UInt8], data: [UInt8]) -> [UInt8] {
        let mac = HMAC<SHA256>.authenticationCode(
            for: data, using: SymmetricKey(data: key))
        return Array(mac)
    }

    /// Noise-style HKDF (rev 34 §4.3): 2 or 3 chained outputs from a chaining key.
    static func hkdfNoise(chainingKey: [UInt8], ikm: [UInt8], outputs: Int) -> [[UInt8]] {
        let temp = hmacSHA256(key: chainingKey, data: ikm)
        let out1 = hmacSHA256(key: temp, data: [0x01])
        let out2 = hmacSHA256(key: temp, data: out1 + [0x02])
        if outputs == 2 { return [out1, out2] }
        let out3 = hmacSHA256(key: temp, data: out2 + [0x03])
        return [out1, out2, out3]
    }

    /// RFC 5869 HKDF-SHA256 (extract-then-expand). Used for `pairRoot` (§2).
    static func hkdfRFC5869(salt: [UInt8], ikm: [UInt8], info: [UInt8], length: Int = 32) -> [UInt8] {
        let derived = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm),
            salt: salt,
            info: info,
            outputByteCount: length)
        return derived.withUnsafeBytes { Array($0) }
    }

    // MARK: X25519

    static func x25519PublicKey(fromPrivate priv: [UInt8]) throws -> [UInt8] {
        let key = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: priv)
        return Array(key.publicKey.rawRepresentation)
    }

    static func x25519(privateKey: [UInt8], publicKey: [UInt8]) throws -> [UInt8] {
        let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
        let pub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicKey)
        let shared = try priv.sharedSecretFromKeyAgreement(with: pub)
        return shared.withUnsafeBytes { Array($0) }
    }

    // MARK: ChaCha20-Poly1305

    /// Noise nonce: 4 zero bytes || u64LE(counter).
    static func nonce(counter: UInt64) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: 12)
        for i in 0..<8 {
            out[4 + i] = UInt8((counter >> (8 * UInt64(i))) & 0xFF)
        }
        return out
    }

    static func aeadSeal(key: [UInt8], counter: UInt64, ad: [UInt8], plaintext: [UInt8]) throws -> [UInt8] {
        let n = try ChaChaPoly.Nonce(data: nonce(counter: counter))
        let box = try ChaChaPoly.seal(
            plaintext, using: SymmetricKey(data: key), nonce: n, authenticating: ad)
        return Array(box.ciphertext) + Array(box.tag)
    }

    static func aeadOpen(key: [UInt8], counter: UInt64, ad: [UInt8], ciphertext: [UInt8]) throws -> [UInt8] {
        guard ciphertext.count >= 16 else { throw ProtocolError.aeadFailure }
        let n = try ChaChaPoly.Nonce(data: nonce(counter: counter))
        let ct = Array(ciphertext[0..<ciphertext.count - 16])
        let tag = Array(ciphertext[ciphertext.count - 16..<ciphertext.count])
        do {
            let box = try ChaChaPoly.SealedBox(nonce: n, ciphertext: ct, tag: tag)
            return try Array(ChaChaPoly.open(box, using: SymmetricKey(data: key), authenticating: ad))
        } catch {
            throw ProtocolError.aeadFailure
        }
    }

    /// Constant-time equality for MAC / key-confirmation comparison (§6).
    static func constantTimeEqual(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count { diff |= a[i] ^ b[i] }
        return diff == 0
    }
}
