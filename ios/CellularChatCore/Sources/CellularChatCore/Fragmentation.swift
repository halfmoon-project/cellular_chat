import Foundation

/// BLE GATT fragmentation and reassembly (PROTOCOL_V2.md §9).
///
/// fragment   = flags(1) || [u32BE totalLen if FIRST] || chunk
/// flags.bit7 = FIRST, flags.bit6 = FINAL, flags.bit0..5 = counter mod 64.
public enum Fragmentation {

    /// Split one record into ATT payloads for the given ATT_MTU
    /// (usable payload = ATT_MTU − 3).
    public static func fragment(record: [UInt8], mtu: Int) -> [[UInt8]] {
        let usable = mtu - 3
        var frags: [[UInt8]] = []
        let total = record.count
        var pos = 0
        var counter = 0
        while true {
            let first = pos == 0
            let room = usable - 1 - (first ? 4 : 0)
            let end = min(pos + max(room, 0), total)
            let chunk = Array(record[pos..<end])
            pos = end
            let final = pos == total
            var header: [UInt8] = [
                (first ? 0x80 : 0) | (final ? 0x40 : 0) | UInt8(counter & 0x3F)
            ]
            if first {
                let t = UInt32(total)
                header += [UInt8((t >> 24) & 0xFF), UInt8((t >> 16) & 0xFF),
                           UInt8((t >> 8) & 0xFF), UInt8(t & 0xFF)]
            }
            frags.append(header + chunk)
            counter += 1
            if final { break }
        }
        return frags
    }
}

/// Stateful reassembler. `push` returns the completed record or nil while a
/// record is still in progress; it throws on any §9 violation.
public final class FragmentReassembler {

    public static let timeoutSeconds: TimeInterval = 10

    private var inProgress = false
    private var buffer: [UInt8] = []
    private var expectedTotal = 0
    private var lastCounter = 0
    private var startTime: TimeInterval = 0
    private let clock: () -> TimeInterval

    public init(clock: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 }) {
        self.clock = clock
    }

    public func push(_ fragment: [UInt8]) throws -> [UInt8]? {
        guard let flags = fragment.first else { throw ProtocolError.fragEmptyChunk }
        let first = (flags & 0x80) != 0
        let final = (flags & 0x40) != 0
        let counter = Int(flags & 0x3F)

        if first {
            if inProgress { throw ProtocolError.fragUnexpectedFirst }
            guard fragment.count >= 5 else { throw ProtocolError.fragDeclaredLengthInvalid }
            let total = (Int(fragment[1]) << 24) | (Int(fragment[2]) << 16)
                | (Int(fragment[3]) << 8) | Int(fragment[4])
            if total == 0 || total > RecordLimits.maxRecord {
                throw ProtocolError.fragDeclaredLengthInvalid
            }
            let chunk = Array(fragment[5...])
            if chunk.isEmpty { throw ProtocolError.fragEmptyChunk }

            inProgress = true
            buffer = chunk
            expectedTotal = total
            lastCounter = counter
            startTime = clock()

            if buffer.count > expectedTotal { return try fail(.fragLengthMismatch) }
            if final { return try finish() }
            return nil
        } else {
            guard inProgress else { throw ProtocolError.fragNoRecordInProgress }
            if clock() - startTime > FragmentReassembler.timeoutSeconds {
                return try fail(.fragTimeout)
            }
            if counter != (lastCounter + 1) % 64 { return try fail(.fragBadCounter) }
            let chunk = Array(fragment[1...])
            if chunk.isEmpty { return try fail(.fragEmptyChunk) }

            buffer += chunk
            lastCounter = counter
            if buffer.count > expectedTotal { return try fail(.fragLengthMismatch) }
            if final { return try finish() }
            return nil
        }
    }

    /// Rejects a FIRST-only stalled reassembly (§9 10-second budget) even when no
    /// further fragment ever arrives. The transport arms a timer to poll this; it
    /// throws `fragTimeout` and clears state once the in-progress record is too
    /// old, and is a no-op while idle. Complements the in-line check in `push`,
    /// which can only fire when a subsequent fragment arrives.
    public func checkStallTimeout() throws {
        guard inProgress else { return }
        if clock() - startTime > FragmentReassembler.timeoutSeconds {
            reset()
            throw ProtocolError.fragTimeout
        }
    }

    private func finish() throws -> [UInt8] {
        if buffer.count != expectedTotal { return try fail(.fragLengthMismatch) }
        let record = buffer
        reset()
        return record
    }

    private func fail(_ error: ProtocolError) throws -> [UInt8] {
        reset()
        throw error
    }

    private func reset() {
        inProgress = false
        buffer = []
        expectedTotal = 0
        lastCounter = 0
        startTime = 0
    }
}
