package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.ProtocolException
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Record types and size limits (PROTOCOL_V2.md §5). */
object Records {
    const val PAIRING_HANDSHAKE: Int = 0x01
    const val SESSION_HANDSHAKE: Int = 0x02
    const val SESSION_TRANSPORT: Int = 0x03
    const val PAIRING_TRANSPORT: Int = 0x04

    const val MAX_NOISE_MESSAGE: Int = 65535
    const val MAX_PLAINTEXT_ENVELOPE: Int = 65519
    const val MAX_RECORD: Int = 65536

    private val KNOWN_TYPES = setOf(
        PAIRING_HANDSHAKE, SESSION_HANDSHAKE, SESSION_TRANSPORT, PAIRING_TRANSPORT,
    )

    fun build(recordType: Int, payload: ByteArray): ByteArray {
        val record = ByteArray(payload.size + 1)
        record[0] = recordType.toByte()
        System.arraycopy(payload, 0, record, 1, payload.size)
        validate(record)
        return record
    }

    /** Enforces §5/§14 record limits before any other processing. */
    fun validate(record: ByteArray) {
        if (record.isEmpty()) throw ProtocolException("zero-length record")
        if (record.size > MAX_RECORD) throw ProtocolException("record exceeds ${MAX_RECORD} bytes")
        val type = record[0].toInt() and 0xff
        if (type !in KNOWN_TYPES) throw ProtocolException("unknown record type $type")
    }

    fun recordType(record: ByteArray): Int {
        validate(record)
        return record[0].toInt() and 0xff
    }

    fun payload(record: ByteArray): ByteArray {
        validate(record)
        return record.copyOfRange(1, record.size)
    }
}

/** Stream framing for Wi-Fi Aware sockets: u32BE(recordLength) || record (§5). */
object StreamFraming {
    fun frame(record: ByteArray): ByteArray {
        Records.validate(record)
        val length = record.size
        val framed = ByteArray(length + 4)
        framed[0] = (length ushr 24).toByte()
        framed[1] = (length ushr 16).toByte()
        framed[2] = (length ushr 8).toByte()
        framed[3] = length.toByte()
        System.arraycopy(record, 0, framed, 4, length)
        return framed
    }

    fun write(output: OutputStream, record: ByteArray) {
        output.write(frame(record))
        output.flush()
    }

    fun read(input: InputStream): ByteArray {
        val header = readFully(input, 4)
        val length = ((header[0].toInt() and 0xff) shl 24) or
            ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or
            (header[3].toInt() and 0xff)
        if (length <= 0 || length > Records.MAX_RECORD) {
            throw ProtocolException("framed record length $length is invalid")
        }
        val record = readFully(input, length)
        Records.validate(record)
        return record
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) throw EOFException("stream ended after $read of $count bytes")
            read += n
        }
        return buffer
    }
}
