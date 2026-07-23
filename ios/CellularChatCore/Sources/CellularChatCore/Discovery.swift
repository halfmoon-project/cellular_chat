import Foundation

/// Rediscovery tokens (PROTOCOL_V2.md §7). BLE advertisements carry only a
/// 16-byte rotating token; it is a rendezvous filter, never proof of identity.
public enum Discovery {

    public static let epochSeconds: UInt64 = 120

    public static func epoch(unixSeconds: UInt64) -> UInt64 {
        unixSeconds / epochSeconds
    }

    /// input = 0x02 || u64BE(epoch) || roleByte ; token = HMAC(discKey, input)[0..16]
    public static func token(discoveryKey: [UInt8], epoch: UInt64, role: PairRole) -> [UInt8] {
        var input: [UInt8] = [0x02]
        for i in stride(from: 7, through: 0, by: -1) {
            input.append(UInt8((epoch >> (8 * UInt64(i))) & 0xFF))
        }
        input.append(role == .a ? 0x41 : 0x42)
        return Array(Crypto.hmacSHA256(key: discoveryKey, data: input)[0..<16])
    }

    /// Acceptance set: current epoch and the two adjacent epochs (§7).
    public static func acceptanceTokens(discoveryKey: [UInt8], unixSeconds: UInt64, role: PairRole) -> [[UInt8]] {
        let e = epoch(unixSeconds: unixSeconds)
        return [e - 1, e, e + 1].map { token(discoveryKey: discoveryKey, epoch: $0, role: role) }
    }

    /// True if `candidate` matches any accepted (current ±1 epoch) token.
    public static func accepts(candidate: [UInt8], discoveryKey: [UInt8], unixSeconds: UInt64, role: PairRole) -> Bool {
        acceptanceTokens(discoveryKey: discoveryKey, unixSeconds: unixSeconds, role: role)
            .contains { Crypto.constantTimeEqual($0, candidate) }
    }
}
