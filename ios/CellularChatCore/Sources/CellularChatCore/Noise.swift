import Foundation

/// Noise Protocol Framework revision 34, restricted to X25519 / ChaChaPoly /
/// SHA-256 and the NNpsk0 / IKpsk2 patterns (PROTOCOL_V2.md §1). The psk-mode
/// rule applies: the `e` token also calls MixKey(e.public).

enum NoisePattern {
    case nnpsk0
    case ikpsk2

    var name: String {
        switch self {
        case .nnpsk0: return "Noise_NNpsk0_25519_ChaChaPoly_SHA256"
        case .ikpsk2: return "Noise_IKpsk2_25519_ChaChaPoly_SHA256"
        }
    }

    /// Pre-message static tokens for (initiator, responder).
    var preResponder: [String] {
        switch self {
        case .nnpsk0: return []
        case .ikpsk2: return ["s"]
        }
    }

    var messages: [[String]] {
        switch self {
        case .nnpsk0: return [["psk", "e"], ["e", "ee"]]
        case .ikpsk2: return [["e", "es", "s", "ss"], ["e", "ee", "se", "psk"]]
        }
    }
}

final class CipherState {
    var k: [UInt8]?
    var n: UInt64 = 0

    init(_ k: [UInt8]? = nil) { self.k = k }

    func encryptWithAd(_ ad: [UInt8], _ plaintext: [UInt8]) throws -> [UInt8] {
        guard let k else { return plaintext }
        guard n < 0x1_0000_0000 else { throw ProtocolError.counterOverflow }
        let out = try Crypto.aeadSeal(key: k, counter: n, ad: ad, plaintext: plaintext)
        n += 1
        return out
    }

    func decryptWithAd(_ ad: [UInt8], _ ciphertext: [UInt8]) throws -> [UInt8] {
        guard let k else { return ciphertext }
        guard n < 0x1_0000_0000 else { throw ProtocolError.counterOverflow }
        let out = try Crypto.aeadOpen(key: k, counter: n, ad: ad, ciphertext: ciphertext)
        n += 1
        return out
    }
}

final class SymmetricState {
    var h: [UInt8]
    var ck: [UInt8]
    var cipher = CipherState()

    init(protocolName: String) {
        let name = Array(protocolName.utf8)
        if name.count <= 32 {
            h = name + [UInt8](repeating: 0, count: 32 - name.count)
        } else {
            h = Crypto.sha256(name)
        }
        ck = h
    }

    func mixKey(_ ikm: [UInt8]) {
        let out = Crypto.hkdfNoise(chainingKey: ck, ikm: ikm, outputs: 2)
        ck = out[0]
        cipher = CipherState(out[1])
    }

    func mixHash(_ data: [UInt8]) {
        h = Crypto.sha256(h + data)
    }

    func mixKeyAndHash(_ ikm: [UInt8]) {
        let out = Crypto.hkdfNoise(chainingKey: ck, ikm: ikm, outputs: 3)
        ck = out[0]
        mixHash(out[1])
        cipher = CipherState(out[2])
    }

    func encryptAndHash(_ plaintext: [UInt8]) throws -> [UInt8] {
        let c = try cipher.encryptWithAd(h, plaintext)
        mixHash(c)
        return c
    }

    func decryptAndHash(_ ciphertext: [UInt8]) throws -> [UInt8] {
        let p = try cipher.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return p
    }

    func split() -> (CipherState, CipherState) {
        let out = Crypto.hkdfNoise(chainingKey: ck, ikm: [], outputs: 2)
        return (CipherState(out[0]), CipherState(out[1]))
    }
}

final class HandshakeState {
    private let pattern: NoisePattern
    private let sym: SymmetricState
    let initiator: Bool

    private var s: [UInt8]?      // local static private
    private var e: [UInt8]?      // local ephemeral private
    private(set) var rs: [UInt8]?  // remote static public
    private var re: [UInt8]?     // remote ephemeral public
    private let psk: [UInt8]?
    private var msgIndex = 0

    init(pattern: NoisePattern,
         initiator: Bool,
         prologue: [UInt8] = [],
         s: [UInt8]? = nil,
         e: [UInt8]? = nil,
         rs: [UInt8]? = nil,
         psk: [UInt8]? = nil) throws {
        self.pattern = pattern
        self.initiator = initiator
        self.sym = SymmetricState(protocolName: pattern.name)
        self.s = s
        self.e = e
        self.rs = rs
        self.psk = psk
        sym.mixHash(prologue)
        // No pre-message initiator statics in either supported pattern.
        for token in pattern.preResponder {
            precondition(token == "s")
            let pub = initiator ? rs! : try Crypto.x25519PublicKey(fromPrivate: s!)
            sym.mixHash(pub)
        }
    }

    var handshakeHash: [UInt8] { sym.h }

    var isFinished: Bool { msgIndex == pattern.messages.count }

    func split() -> (CipherState, CipherState) { sym.split() }

    func writeMessage(_ payload: [UInt8] = []) throws -> [UInt8] {
        let tokens = pattern.messages[msgIndex]
        msgIndex += 1
        var buf: [UInt8] = []
        for token in tokens {
            switch token {
            case "e":
                let epub = try Crypto.x25519PublicKey(fromPrivate: e!)
                buf += epub
                sym.mixHash(epub)
                if psk != nil { sym.mixKey(epub) }
            case "s":
                buf += try sym.encryptAndHash(try Crypto.x25519PublicKey(fromPrivate: s!))
            case "ee":
                sym.mixKey(try Crypto.x25519(privateKey: e!, publicKey: re!))
            case "es":
                if initiator {
                    sym.mixKey(try Crypto.x25519(privateKey: e!, publicKey: rs!))
                } else {
                    sym.mixKey(try Crypto.x25519(privateKey: s!, publicKey: re!))
                }
            case "se":
                if initiator {
                    sym.mixKey(try Crypto.x25519(privateKey: s!, publicKey: re!))
                } else {
                    sym.mixKey(try Crypto.x25519(privateKey: e!, publicKey: rs!))
                }
            case "ss":
                sym.mixKey(try Crypto.x25519(privateKey: s!, publicKey: rs!))
            case "psk":
                sym.mixKeyAndHash(psk!)
            default:
                fatalError("unknown token \(token)")
            }
        }
        buf += try sym.encryptAndHash(payload)
        return buf
    }

    func readMessage(_ message: [UInt8]) throws -> [UInt8] {
        let tokens = pattern.messages[msgIndex]
        msgIndex += 1
        var rest = message
        for token in tokens {
            switch token {
            case "e":
                guard rest.count >= 32 else { throw ProtocolError.aeadFailure }
                re = Array(rest[0..<32])
                rest = Array(rest[32...])
                sym.mixHash(re!)
                if psk != nil { sym.mixKey(re!) }
            case "s":
                let take = 32 + (sym.cipher.k != nil ? 16 : 0)
                guard rest.count >= take else { throw ProtocolError.aeadFailure }
                let chunk = Array(rest[0..<take])
                rest = Array(rest[take...])
                rs = try sym.decryptAndHash(chunk)
            case "ee":
                sym.mixKey(try Crypto.x25519(privateKey: e!, publicKey: re!))
            case "es":
                if initiator {
                    sym.mixKey(try Crypto.x25519(privateKey: e!, publicKey: rs!))
                } else {
                    sym.mixKey(try Crypto.x25519(privateKey: s!, publicKey: re!))
                }
            case "se":
                if initiator {
                    sym.mixKey(try Crypto.x25519(privateKey: s!, publicKey: re!))
                } else {
                    sym.mixKey(try Crypto.x25519(privateKey: e!, publicKey: rs!))
                }
            case "ss":
                sym.mixKey(try Crypto.x25519(privateKey: s!, publicKey: rs!))
            case "psk":
                sym.mixKeyAndHash(psk!)
            default:
                fatalError("unknown token \(token)")
            }
        }
        return try sym.decryptAndHash(rest)
    }
}
