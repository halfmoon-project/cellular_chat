package com.cellularchat.app.transport

/**
 * An established, ordered, record-oriented link to the peer (PROTOCOL_V2.md §5).
 * Fragmentation/stream-framing is handled inside each implementation so callers
 * always see whole records. The Noise session (core `SecureSession`) runs on top.
 */
interface PeerTransport {
    /** Prologue transport tag: "ble", "aware", or "nearby" (§8). */
    val tag: String

    fun setListener(listener: Listener)

    /** Sends one whole record. Must not block the caller's thread indefinitely. */
    fun send(record: ByteArray)

    fun close()

    interface Listener {
        fun onRecord(record: ByteArray)

        /** The underlying link dropped; [reason] is a §13 code. */
        fun onLinkLost(reason: Int)
    }
}

/**
 * Arbitration-time view of one transport (IMPLEMENTATION_PLAN.md §4). A
 * candidate reports availability and attempts to reach an authenticated link
 * within a measured timeout, yielding a [PeerTransport] on success.
 */
interface TransportCandidate {
    val tag: String

    /** Runtime capability check — never an OS-version guess. */
    fun isAvailable(): Boolean

    /** [timeoutMillis] <= 0 means mandatory (no arbitration timeout, e.g. BLE). */
    fun attempt(timeoutMillis: Long, callback: AttemptCallback)

    /** Aborts an in-flight [attempt] and releases its radio resources. */
    fun cancel()

    interface AttemptCallback {
        fun onConnected(transport: PeerTransport)
        fun onFailed()
    }
}
