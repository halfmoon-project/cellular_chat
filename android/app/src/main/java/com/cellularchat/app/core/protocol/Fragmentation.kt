package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.FragmentException
import java.io.ByteArrayOutputStream

/**
 * BLE GATT fragmentation and reassembly (PROTOCOL_V2.md §9).
 *
 * fragment = flags(1) || [u32BE totalLen if FIRST] || chunk
 * flags.bit7 FIRST, flags.bit6 FINAL, flags.bit0..5 counter mod 64.
 */
object Fragmentation {
    private const val FIRST = 0x80
    private const val FINAL = 0x40
    private const val COUNTER_MASK = 0x3f
    private const val COUNTER_MODULUS = 64
    private const val MAX_RECORD = 65536

    /** Splits a record into ATT payloads for the given ATT_MTU (usable = MTU − 3). */
    fun fragment(record: ByteArray, mtu: Int): List<ByteArray> {
        val usable = mtu - 3
        val firstCapacity = usable - 5
        val restCapacity = usable - 1
        require(firstCapacity >= 1 && restCapacity >= 1) { "MTU $mtu is too small to fragment" }

        val fragments = ArrayList<ByteArray>()
        val total = record.size
        var offset = 0
        var counter = 0
        var first = true
        while (offset < total || first) {
            val capacity = if (first) firstCapacity else restCapacity
            val take = minOf(capacity, total - offset)
            val isFinal = offset + take >= total
            var flags = counter and COUNTER_MASK
            if (first) flags = flags or FIRST
            if (isFinal) flags = flags or FINAL

            val out = ByteArrayOutputStream()
            out.write(flags)
            if (first) {
                out.write((total ushr 24) and 0xff)
                out.write((total ushr 16) and 0xff)
                out.write((total ushr 8) and 0xff)
                out.write(total and 0xff)
            }
            out.write(record, offset, take)
            fragments.add(out.toByteArray())

            offset += take
            counter = (counter + 1) % COUNTER_MODULUS
            first = false
        }
        return fragments
    }

    fun reassemble(fragments: List<ByteArray>): ByteArray {
        val reassembler = Reassembler()
        var record: ByteArray? = null
        for (fragment in fragments) {
            val completed = reassembler.offer(fragment)
            if (completed != null) record = completed
        }
        return record ?: throw FragmentException("lengthMismatch")
    }
}

/** Stateful reassembler; [offer] returns the completed record on the FINAL fragment. */
class Reassembler(
    private val timeoutMillis: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var buffer: ByteArrayOutputStream? = null
    private var totalLen = 0
    private var received = 0
    private var expectedCounter = 0
    private var startMillis = 0L

    fun offer(fragment: ByteArray): ByteArray? {
        if (fragment.isEmpty()) throw FragmentException("emptyChunk")
        val flags = fragment[0].toInt() and 0xff
        val isFirst = flags and 0x80 != 0
        val isFinal = flags and 0x40 != 0
        val counter = flags and 0x3f

        if (isFirst) {
            if (buffer != null) throw FragmentException("unexpectedFirst")
            if (fragment.size < 5) throw FragmentException("declaredLengthInvalid")
            if (counter != 0) throw FragmentException("badCounter")
            totalLen = ((fragment[1].toInt() and 0xff) shl 24) or
                ((fragment[2].toInt() and 0xff) shl 16) or
                ((fragment[3].toInt() and 0xff) shl 8) or
                (fragment[4].toInt() and 0xff)
            if (totalLen <= 0 || totalLen > 65536) throw FragmentException("declaredLengthInvalid")
            val chunk = fragment.copyOfRange(5, fragment.size)
            if (chunk.isEmpty()) throw FragmentException("emptyChunk")
            if (chunk.size > totalLen) throw FragmentException("lengthMismatch")
            buffer = ByteArrayOutputStream().apply { write(chunk) }
            received = chunk.size
            expectedCounter = 1
            startMillis = clock()
            return complete(isFinal)
        }

        val current = buffer ?: throw FragmentException("noRecordInProgress")
        if (clock() - startMillis > timeoutMillis) throw FragmentException("timeout")
        if (counter != expectedCounter) throw FragmentException("badCounter")
        val chunk = fragment.copyOfRange(1, fragment.size)
        if (chunk.isEmpty()) throw FragmentException("emptyChunk")
        received += chunk.size
        if (received > totalLen) throw FragmentException("lengthMismatch")
        current.write(chunk)
        expectedCounter = (expectedCounter + 1) % 64
        return complete(isFinal)
    }

    private fun complete(isFinal: Boolean): ByteArray? {
        if (!isFinal) return null
        if (received != totalLen) throw FragmentException("lengthMismatch")
        val record = buffer!!.toByteArray()
        reset()
        return record
    }

    private fun reset() {
        buffer = null
        totalLen = 0
        received = 0
        expectedCounter = 0
    }
}
