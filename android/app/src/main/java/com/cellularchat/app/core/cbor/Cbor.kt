package com.cellularchat.app.core.cbor

import com.cellularchat.app.core.CborException
import java.io.ByteArrayOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Canonical CBOR (PROTOCOL_V2.md §3) codec restricted to the deterministic core
 * profile. The decoder rejects every §3 violation.
 */
object Cbor {

    fun encode(value: CborValue): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): CborValue {
        val decoder = Decoder(bytes)
        val value = decoder.decodeItem()
        if (decoder.pos != bytes.size) throw CborException("trailing bytes")
        return value
    }

    // --- encoder ---

    private fun write(out: ByteArrayOutputStream, value: CborValue) {
        when (value) {
            is CborInt -> {
                val v = value.value
                if (v >= 0) writeArgument(out, 0, v) else writeArgument(out, 1, -1L - v)
            }
            is CborBytes -> {
                writeArgument(out, 2, value.value.size.toLong())
                out.write(value.value)
            }
            is CborText -> {
                val utf8 = value.value.toByteArray(StandardCharsets.UTF_8)
                writeArgument(out, 3, utf8.size.toLong())
                out.write(utf8)
            }
            is CborArray -> {
                writeArgument(out, 4, value.items.size.toLong())
                value.items.forEach { write(out, it) }
            }
            is CborMap -> {
                // Canonical order: sort entries by the bytewise order of the encoded key.
                val sorted = value.entries.sortedWith { a, b ->
                    compareLex(encode(a.first), encode(b.first))
                }
                writeArgument(out, 5, sorted.size.toLong())
                sorted.forEach { (k, v) -> write(out, k); write(out, v) }
            }
            is CborBool -> out.write(if (value.value) 0xf5 else 0xf4)
            CborNull -> out.write(0xf6)
        }
    }

    /** Writes major type [major] with unsigned argument [arg] in shortest form. */
    private fun writeArgument(out: ByteArrayOutputStream, major: Int, arg: Long) {
        val head = major shl 5
        when {
            ucmp(arg, 24) < 0 -> out.write(head or arg.toInt())
            ucmp(arg, 256) < 0 -> { out.write(head or 24); out.write(arg.toInt() and 0xff) }
            ucmp(arg, 65536) < 0 -> {
                out.write(head or 25)
                out.write((arg ushr 8).toInt() and 0xff)
                out.write(arg.toInt() and 0xff)
            }
            ucmp(arg, 4294967296L) < 0 -> {
                out.write(head or 26)
                for (shift in intArrayOf(24, 16, 8, 0)) out.write((arg ushr shift).toInt() and 0xff)
            }
            else -> {
                out.write(head or 27)
                for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
                    out.write((arg ushr shift).toInt() and 0xff)
                }
            }
        }
    }

    // --- decoder ---

    private class Decoder(val data: ByteArray) {
        var pos = 0

        private fun readByte(): Int {
            if (pos >= data.size) throw CborException("truncated")
            return data[pos++].toInt() and 0xff
        }

        private fun remaining() = data.size - pos

        /** Reads the argument for major types 0-5, enforcing shortest-form. */
        private fun readArgument(ai: Int): Long = when {
            ai < 24 -> ai.toLong()
            ai == 24 -> {
                val v = readByte().toLong()
                if (v < 24) throw CborException("nonminimal int")
                v
            }
            ai == 25 -> {
                val v = (readByte().toLong() shl 8) or readByte().toLong()
                if (v < 256) throw CborException("nonminimal int")
                v
            }
            ai == 26 -> {
                var v = 0L
                repeat(4) { v = (v shl 8) or readByte().toLong() }
                if (v < 65536) throw CborException("nonminimal int")
                v
            }
            ai == 27 -> {
                var v = 0L
                repeat(8) { v = (v shl 8) or readByte().toLong() }
                if (ucmp(v, 4294967296L) < 0) throw CborException("nonminimal int")
                v
            }
            else -> throw CborException("reserved or indefinite length")
        }

        fun decodeItem(): CborValue {
            val initial = readByte()
            val major = initial ushr 5
            val ai = initial and 0x1f
            return when (major) {
                0 -> CborInt(toSigned(readArgument(ai)))
                1 -> CborInt(-1L - toSigned(readArgument(ai)))
                2 -> {
                    val len = readLength(readArgument(ai))
                    val bytes = data.copyOfRange(pos, pos + len)
                    pos += len
                    CborBytes(bytes)
                }
                3 -> {
                    val len = readLength(readArgument(ai))
                    val bytes = data.copyOfRange(pos, pos + len)
                    pos += len
                    CborText(strictUtf8(bytes))
                }
                4 -> {
                    val count = readLength(readArgument(ai))
                    val items = ArrayList<CborValue>(count)
                    repeat(count) { items.add(decodeItem()) }
                    CborArray(items)
                }
                5 -> decodeMap(readLength(readArgument(ai)))
                6 -> throw CborException("tags are forbidden")
                7 -> decodeSimple(ai)
                else -> throw CborException("unsupported major type")
            }
        }

        private fun decodeMap(count: Int): CborMap {
            val entries = ArrayList<Pair<CborValue, CborValue>>(count)
            var prevKey: ByteArray? = null
            repeat(count) {
                val keyStart = pos
                val key = decodeItem()
                val keyBytes = data.copyOfRange(keyStart, pos)
                prevKey?.let {
                    val cmp = compareLex(it, keyBytes)
                    if (cmp == 0) throw CborException("duplicate map key")
                    if (cmp > 0) throw CborException("unsorted map keys")
                }
                prevKey = keyBytes
                val value = decodeItem()
                entries.add(key to value)
            }
            return CborMap(entries)
        }

        private fun decodeSimple(ai: Int): CborValue = when (ai) {
            20 -> CborBool(false)
            21 -> CborBool(true)
            22 -> CborNull
            23 -> throw CborException("undefined is forbidden")
            24 -> throw CborException("unsupported simple value")
            25, 26, 27 -> throw CborException("floating point is forbidden")
            else -> throw CborException("unsupported simple value")
        }

        /** Validates a length/count fits the remaining buffer (also guards huge values). */
        private fun readLength(arg: Long): Int {
            if (arg < 0 || arg > remaining()) throw CborException("truncated")
            return arg.toInt()
        }
    }

    // --- helpers ---

    private fun toSigned(arg: Long): Long {
        // Values above Long range are not produced by this protocol; reject them.
        if (arg < 0) throw CborException("integer out of range")
        return arg
    }

    private fun strictUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            throw CborException("invalid utf-8 text")
        }
    }

    private fun ucmp(a: Long, b: Long): Int = java.lang.Long.compareUnsigned(a, b)

    private fun compareLex(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
