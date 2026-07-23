package com.cellularchat.app.core.crypto

/** Rotating rediscovery tokens (PROTOCOL_V2.md §7). */
object Discovery {
    const val EPOCH_SECONDS = 120L

    fun epoch(unixSeconds: Long): Long = Math.floorDiv(unixSeconds, EPOCH_SECONDS)

    fun tokenInput(epoch: Long, role: Byte): ByteArray {
        val input = ByteArray(10)
        input[0] = 0x02
        var value = epoch
        for (i in 7 downTo 0) {
            input[1 + i] = (value and 0xff).toByte()
            value = value ushr 8
        }
        input[9] = role
        return input
    }

    fun token(discoveryKey: ByteArray, epoch: Long, role: Byte): ByteArray =
        Hashes.hmacSha256(discoveryKey, tokenInput(epoch, role)).copyOf(16)

    /** Candidate tokens for the current epoch and the two adjacent epochs. */
    fun acceptanceTokens(discoveryKey: ByteArray, unixSeconds: Long, role: Byte): List<ByteArray> {
        val current = epoch(unixSeconds)
        return listOf(current - 1, current, current + 1).map { token(discoveryKey, it, role) }
    }

    /** True when [candidate] matches a token within the ±1 epoch acceptance window. */
    fun isWithinAcceptanceWindow(discoveryKey: ByteArray, unixSeconds: Long, role: Byte, candidate: ByteArray): Boolean =
        acceptanceTokens(discoveryKey, unixSeconds, role).any { it.contentEquals(candidate) }
}
