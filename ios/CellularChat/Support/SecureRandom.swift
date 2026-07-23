import Foundation
import Security

/// Failure of the system CSPRNG. Surfaced instead of silently proceeding with
/// zeroed key material (PROTOCOL_V2.md §14).
enum SecureRandomError: Error { case failed(OSStatus) }

/// Cryptographically secure random bytes, or throw on RNG failure. Wraps
/// `SecRandomCopyBytes` so callers never discard its `OSStatus` and never build
/// a pairId/secret/sid from an all-zero buffer.
func secureRandomBytes(count: Int) throws -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: count)
    let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
    guard status == errSecSuccess else { throw SecureRandomError.failed(status) }
    return bytes
}
