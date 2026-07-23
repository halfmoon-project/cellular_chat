package com.cellularchat.app.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolTest {
    @Test
    fun canonicalVectorMatchesSharedContract() {
        val normalized = Protocol.normalizeConnectionId("Abc-1234 ")
        assertEquals("ABC-1234", normalized)
        assertEquals(
            "db0f3cc6d7f064c0472ee745c6afdce3c097959263e75784f9f8df5fe2e07ecf",
            Protocol.roomHash(normalized),
        )
        assertEquals(
            "5fa6ca86646d18558c862501efafaad023dfc83ec5d05cbc64c987f493a54d14",
            with(Protocol) { authKey(normalized).toHex() },
        )
        val key = Protocol.authKey(normalized)
        assertEquals(
            "tfw99T2v/29w4q5gtDSPjlJaUtizR/FZnQHnQXRymfc=",
            Protocol.proof(
                "client",
                key,
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                "AAECAwQFBgcICQoLDA0ODw==",
                "EBESExQVFhcYGRobHB0eHw==",
            ),
        )
        assertEquals(
            "nlpXIjJFKUZDi5B6Ox8ukcFv59oACLZYBzoe/hTMfUI=",
            Protocol.proof(
                "server",
                key,
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                "AAECAwQFBgcICQoLDA0ODw==",
                "EBESExQVFhcYGRobHB0eHw==",
            ),
        )
    }

    @Test
    fun normalizationUsesAsciiRules() {
        assertEquals("ABC-123", Protocol.normalizeConnectionId(" abc-123 "))
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.normalizeConnectionId("\nabc-123\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.normalizeConnectionId("too short")
        }
    }

    @Test
    fun frameIsBigEndianAndRoundTripsUnicode() {
        val output = ByteArrayOutputStream()
        val message = "{\"v\":1,\"type\":\"chat\",\"text\":\"안녕\"}"
        FrameCodec.write(output, message)
        val bytes = output.toByteArray()
        val declaredLength = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        assertEquals(bytes.size - 4, declaredLength)
        assertEquals(message, FrameCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun frameRejectsZeroAndOversizeLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)))
        }
        val oversized = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(Protocol.MAX_FRAME_BYTES + 1).array()
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.read(ByteArrayInputStream(oversized))
        }
    }

    @Test
    fun frameWritesExpectedPrefix() {
        val output = ByteArrayOutputStream()
        FrameCodec.write(output, "{}")
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 123, 125), output.toByteArray())
    }
}
