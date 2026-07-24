package com.cellularchat.app.transport

/** Bytewise unsigned lexicographic comparison of two equal-purpose keys. */
internal fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return a.size - b.size
}

/**
 * Deterministic BLE central/peripheral ownership (PROTOCOL_V2.md §9,
 * IMPLEMENTATION_PLAN.md §4). The central is the Noise/IKpsk2 initiator.
 *
 * - Cross-platform: iOS is the central (safer iOS background path), so Android
 *   is always the peripheral.
 * - Same-platform: the side with the bytewise-smaller pinned static public key
 *   is the central.
 */
object BleRoleSelector {
    fun localIsCentral(
        localStatic: ByteArray,
        peerStatic: ByteArray,
        localIsIos: Boolean,
        peerIsIos: Boolean,
    ): Boolean {
        if (localIsIos != peerIsIos) return localIsIos
        return compareUnsigned(localStatic, peerStatic) < 0
    }
}

/**
 * Transport preference order for the BLE→higher upgrade (PROTOCOL_V2.md §4/§10,
 * Feature A): aware < nearby < ble. A candidate is upgrade-eligible only when its
 * preference index is STRICTLY better (smaller) than the active transport's. `ble`
 * is never an upgrade target.
 */
object TransportPreference {
    private val ORDER = listOf("aware", "nearby", "ble")

    /** Lower is more preferred; an unknown tag ranks after everything. */
    fun index(tag: String): Int = ORDER.indexOf(tag).let { if (it < 0) ORDER.size else it }

    /** True iff moving the active transport [from] to candidate [to] is a strict upgrade. */
    fun shouldUpgrade(from: String, to: String): Boolean =
        to != "ble" && index(to) < index(from)
}

/**
 * Duplicate-connection tie-break (PROTOCOL_V2.md §10). When two simultaneous
 * authenticated connections exist for one pair, both sides keep the one whose
 * Noise initiator has the bytewise-smaller static public key and close the
 * other with `disconnect {reason: duplicate}`.
 */
object DuplicateConnectionResolver {
    /**
     * @return true if the connection whose initiator static is
     * [candidateInitiatorStatic] should be kept over the one whose initiator
     * static is [otherInitiatorStatic]. Ties keep the candidate.
     */
    fun shouldKeep(
        candidateInitiatorStatic: ByteArray,
        otherInitiatorStatic: ByteArray,
    ): Boolean = compareUnsigned(candidateInitiatorStatic, otherInitiatorStatic) <= 0
}
