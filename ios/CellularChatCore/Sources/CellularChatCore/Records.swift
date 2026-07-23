import Foundation

/// Records and framing (PROTOCOL_V2.md §5).
public enum RecordType: UInt8 {
    case pairingHandshake = 0x01   // raw Noise NNpsk0 message
    case sessionHandshake = 0x02   // raw Noise IKpsk2 message
    case sessionTransport = 0x03   // Noise transport ciphertext
    case pairingTransport = 0x04   // Noise transport ciphertext
}

public enum RecordLimits {
    public static let maxNoiseMessage = 65535
    public static let maxPlaintextEnvelope = 65519   // 65535 - 16 tag
    public static let maxRecord = 65536
}

public enum Records {

    public static func make(_ type: RecordType, payload: [UInt8]) -> [UInt8] {
        [type.rawValue] + payload
    }

    /// Split a record into (type, payload) enforcing §5 size rules first.
    public static func parse(_ record: [UInt8]) throws -> (type: RecordType, payload: [UInt8]) {
        guard !record.isEmpty else { throw ProtocolError.zeroLengthRecord }
        guard record.count <= RecordLimits.maxRecord else { throw ProtocolError.oversizeRecord }
        guard let type = RecordType(rawValue: record[0]) else { throw ProtocolError.unknownRecordType }
        return (type, Array(record[1...]))
    }

    // MARK: Stream framing (Wi-Fi Aware) — u32BE(recordLength) || record

    public static func frameForStream(_ record: [UInt8]) -> [UInt8] {
        let len = UInt32(record.count)
        return [
            UInt8((len >> 24) & 0xFF),
            UInt8((len >> 16) & 0xFF),
            UInt8((len >> 8) & 0xFF),
            UInt8(len & 0xFF),
        ] + record
    }

    /// Consumes as many complete framed records as are present in `buffer`,
    /// returning the records and the number of bytes consumed. A declared
    /// length of 0 or > 65536 is a fatal protocol error (§5).
    public static func readStream(_ buffer: [UInt8]) throws -> (records: [[UInt8]], consumed: Int) {
        var records: [[UInt8]] = []
        var pos = 0
        while pos + 4 <= buffer.count {
            let len = (UInt32(buffer[pos]) << 24) | (UInt32(buffer[pos + 1]) << 16)
                | (UInt32(buffer[pos + 2]) << 8) | UInt32(buffer[pos + 3])
            if len == 0 || len > UInt32(RecordLimits.maxRecord) {
                throw ProtocolError.streamLengthInvalid
            }
            let total = 4 + Int(len)
            if pos + total > buffer.count { break }   // incomplete frame; wait for more
            records.append(Array(buffer[pos + 4..<pos + total]))
            pos += total
        }
        return (records, pos)
    }
}
