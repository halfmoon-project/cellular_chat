import Foundation

/// Every fatal condition in PROTOCOL_V2.md §14 surfaces as one of these.
/// A fatal error tears down the transport and maps to a §13 reason code; it
/// never crashes the caller.
public enum ProtocolError: Error, Equatable {
    // CBOR (§3)
    case cborTruncated
    case cborTrailingBytes
    case cborNonminimalInt
    case cborIndefiniteLength
    case cborUnsupportedMajor        // tag / bignum
    case cborUnsupportedSimple       // float / undefined / reserved
    case cborInvalidUTF8
    case cborUnsortedOrDuplicateKey

    // Records / framing (§5)
    case zeroLengthRecord
    case oversizeRecord
    case unknownRecordType
    case streamLengthInvalid

    // Schema (§6/§8/§14)
    case schemaViolation
    case wrongMessagePhase           // handshake after complete / transport before complete
    case unsupportedVersion          // any version != 2 / downgrade

    // Crypto / session (§8/§14)
    case aeadFailure
    case counterMismatch             // seq != CipherState counter
    case sidMismatch
    case counterOverflow             // must re-handshake before n = 2^32
    case pinnedKeyMismatch           // session initiator static != pinned peer
    case macMismatch                 // pairing confirm MAC failed

    // Invitation (§4)
    case invalidInvitation

    // Fragmentation (§9) — names mirror fragment_vectors.json
    case fragNoRecordInProgress      // "noRecordInProgress"
    case fragUnexpectedFirst         // "unexpectedFirst"
    case fragBadCounter              // "badCounter"
    case fragDeclaredLengthInvalid   // "declaredLengthInvalid"
    case fragLengthMismatch          // "lengthMismatch"
    case fragEmptyChunk              // "emptyChunk"
    case fragTimeout                 // reassembly exceeded 10 s
}
