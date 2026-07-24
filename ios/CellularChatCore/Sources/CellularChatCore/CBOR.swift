import Foundation

/// Canonical CBOR value model (PROTOCOL_V2.md §3): the deterministic core
/// profile only — ints, byte/text strings, arrays, maps, and false/true/null.
public indirect enum CBOR: Equatable {
    case uint(UInt64)
    case nint(UInt64)          // encodes the value -1 - n (n is the stored payload)
    case bytes([UInt8])
    case text(String)
    case array([CBOR])
    case map([CBORPair])       // preserved in canonical (encoded-key) order
    case bool(Bool)
    case null
}

public struct CBORPair: Equatable {
    public let key: CBOR
    public let value: CBOR
    public init(_ key: CBOR, _ value: CBOR) {
        self.key = key
        self.value = value
    }
}

public extension CBOR {
    /// Value for an unsigned-integer map key, or nil if absent / not a map.
    func value(forKey key: UInt64) -> CBOR? {
        guard case let .map(pairs) = self else { return nil }
        for p in pairs where p.key == .uint(key) { return p.value }
        return nil
    }

    var asUInt: UInt64? {
        if case let .uint(v) = self { return v }
        return nil
    }
    var asBytes: [UInt8]? {
        if case let .bytes(b) = self { return b }
        return nil
    }
    var asText: String? {
        if case let .text(t) = self { return t }
        return nil
    }
    var isMap: Bool {
        if case .map = self { return true }
        return false
    }
}

// MARK: - Encoder

public enum CBORCoder {

    private static func head(_ major: UInt8, _ value: UInt64) -> [UInt8] {
        let m = major << 5
        if value < 24 {
            return [m | UInt8(value)]
        } else if value < 0x100 {
            return [m | 24, UInt8(value)]
        } else if value < 0x10000 {
            return [m | 25, UInt8(value >> 8), UInt8(value & 0xFF)]
        } else if value < 0x1_0000_0000 {
            return [m | 26] + beBytes(value, 4)
        } else {
            return [m | 27] + beBytes(value, 8)
        }
    }

    private static func beBytes(_ value: UInt64, _ count: Int) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: count)
        for i in 0..<count {
            out[count - 1 - i] = UInt8((value >> (8 * i)) & 0xFF)
        }
        return out
    }

    public static func encode(_ value: CBOR) -> [UInt8] {
        switch value {
        case let .uint(v):
            return head(0, v)
        case let .nint(n):
            return head(1, n)
        case let .bytes(b):
            return head(2, UInt64(b.count)) + b
        case let .text(s):
            let raw = Array(s.utf8)
            return head(3, UInt64(raw.count)) + raw
        case let .array(items):
            var out = head(4, UInt64(items.count))
            for item in items { out += encode(item) }
            return out
        case let .map(pairs):
            let encoded = pairs.map { (encode($0.key), encode($0.value)) }
                .sorted { lexLess($0.0, $1.0) }
            var out = head(5, UInt64(pairs.count))
            for (k, v) in encoded { out += k; out += v }
            return out
        case let .bool(b):
            return [b ? 0xF5 : 0xF4]
        case .null:
            return [0xF6]
        }
    }

    /// Bytewise lexicographic order used for canonical map-key sorting.
    private static func lexLess(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        let n = min(a.count, b.count)
        var i = 0
        while i < n {
            if a[i] != b[i] { return a[i] < b[i] }
            i += 1
        }
        return a.count < b.count
    }

    // MARK: - Decoder

    public static func decode(_ data: [UInt8]) throws -> CBOR {
        var dec = Decoder(data: data)
        let value = try dec.decodeItem().value
        if dec.pos != data.count { throw ProtocolError.cborTrailingBytes }
        return value
    }

    private struct Decoder {
        let data: [UInt8]
        var pos: Int = 0

        mutating func take(_ n: Int) throws -> ArraySlice<UInt8> {
            guard pos + n <= data.count else { throw ProtocolError.cborTruncated }
            let out = data[pos..<pos + n]
            pos += n
            return out
        }

        mutating func readHead() throws -> (major: UInt8, value: UInt64) {
            let b = try take(1).first!
            let major = b >> 5
            let info = b & 0x1F
            if info < 24 { return (major, UInt64(info)) }
            switch info {
            case 24:
                let v = try take(1).first!
                if v < 24 { throw ProtocolError.cborNonminimalInt }
                return (major, UInt64(v))
            case 25:
                let raw = Array(try take(2))
                let v = (UInt64(raw[0]) << 8) | UInt64(raw[1])
                if v < 0x100 { throw ProtocolError.cborNonminimalInt }
                return (major, v)
            case 26:
                let raw = Array(try take(4))
                var v: UInt64 = 0
                for byte in raw { v = (v << 8) | UInt64(byte) }
                if v < 0x10000 { throw ProtocolError.cborNonminimalInt }
                return (major, v)
            case 27:
                let raw = Array(try take(8))
                var v: UInt64 = 0
                for byte in raw { v = (v << 8) | UInt64(byte) }
                if v < 0x1_0000_0000 { throw ProtocolError.cborNonminimalInt }
                return (major, v)
            default:
                // 28..30 reserved, 31 indefinite
                throw ProtocolError.cborIndefiniteLength
            }
        }

        /// A byte/text length or array/map count can never exceed the bytes
        /// remaining in the input (each element needs >= 1 byte), so reject it
        /// before allocating — an attacker-controlled count must throw, not abort.
        mutating func boundedCount(_ value: UInt64) throws -> Int {
            guard value <= UInt64(data.count - pos) else { throw ProtocolError.cborTruncated }
            return Int(value)
        }

        mutating func decodeItem() throws -> (value: CBOR, encoded: [UInt8]) {
            let start = pos
            let (major, value) = try readHead()
            switch major {
            case 0:
                return (.uint(value), Array(data[start..<pos]))
            case 1:
                return (.nint(value), Array(data[start..<pos]))
            case 2:
                let bytes = Array(try take(boundedCount(value)))
                return (.bytes(bytes), Array(data[start..<pos]))
            case 3:
                let raw = Array(try take(boundedCount(value)))
                guard let s = String(bytes: raw, encoding: .utf8) else {
                    throw ProtocolError.cborInvalidUTF8
                }
                return (.text(s), Array(data[start..<pos]))
            case 4:
                var items: [CBOR] = []
                items.reserveCapacity(try boundedCount(value))
                for _ in 0..<value {
                    items.append(try decodeItem().value)
                }
                return (.array(items), Array(data[start..<pos]))
            case 5:
                _ = try boundedCount(value)
                var pairs: [CBORPair] = []
                var prevKeyEnc: [UInt8]? = nil
                for _ in 0..<value {
                    let (key, keyEnc) = try decodeItem()
                    if let prev = prevKeyEnc, !lexLess(prev, keyEnc) {
                        // <= previous means unsorted or duplicate
                        throw ProtocolError.cborUnsortedOrDuplicateKey
                    }
                    prevKeyEnc = keyEnc
                    let val = try decodeItem().value
                    pairs.append(CBORPair(key, val))
                }
                return (.map(pairs), Array(data[start..<pos]))
            case 7:
                switch value {
                case 20: return (.bool(false), Array(data[start..<pos]))
                case 21: return (.bool(true), Array(data[start..<pos]))
                case 22: return (.null, Array(data[start..<pos]))
                default: throw ProtocolError.cborUnsupportedSimple
                }
            default:
                // major 6 = tag
                throw ProtocolError.cborUnsupportedMajor
            }
        }
    }
}
