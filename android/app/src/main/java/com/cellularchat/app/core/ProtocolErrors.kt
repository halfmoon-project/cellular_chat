package com.cellularchat.app.core

/**
 * Fatal protocol error. Carries a §13 reason so the caller can tear down the
 * transport and surface a user-visible state.
 */
open class ProtocolException(
    message: String,
    val reason: Int = ReasonCodes.PROTOCOL_ERROR,
) : Exception(message)

/** Raised when an AEAD tag verification fails (§14 AEAD failure). */
class AeadException(message: String) : ProtocolException(message, ReasonCodes.PROTOCOL_ERROR)

/** Canonical CBOR (§3) violation. */
class CborException(message: String) : ProtocolException(message, ReasonCodes.PROTOCOL_ERROR)

/** BLE reassembly (§9) violation. [error] is the taxonomy name from fragment_vectors.json. */
class FragmentException(val error: String) : ProtocolException(error, ReasonCodes.PROTOCOL_ERROR)

/** §13 disconnect / transition reason codes. */
object ReasonCodes {
    const val NORMAL = 1
    const val EXPIRED = 2
    const val REVOKED = 3
    const val DUPLICATE = 4
    const val PROTOCOL_ERROR = 5
    const val AUTH_FAILED = 6
    const val TIMEOUT = 7
    const val TRANSPORT_LOST = 8
    const val UPGRADED = 9
    const val CAPABILITY_MISMATCH = 10
    const val USER_STOPPED = 11
    const val PERMISSION_REQUIRED = 12
    const val RADIO_UNAVAILABLE = 13
    const val BACKGROUND_SUSPENDED = 14
    const val IDENTITY_MISMATCH = 15
}

/** §13 ranging error codes. */
object RangingErrorCodes {
    const val UNSUPPORTED = 1
    const val CONFIG_REJECTED = 2
    const val PLATFORM_ERROR = 3
    const val LOST_SIGNAL = 4
    const val TIMEOUT = 5
}
