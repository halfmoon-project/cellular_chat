package com.cellularchat.app.transport

import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborText
import com.cellularchat.app.core.cbor.cborMapOf
import com.cellularchat.app.core.protocol.TransportCode
import com.cellularchat.app.core.protocol.TransportUpgradeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature A: the fixed code↔tag map, preference order, and control codecs. */
class TransportUpgradeCodecTest {
    @Test
    fun transportCodesAreFixed() {
        assertEquals("aware", TransportCode.tag(1))
        assertEquals("nearby", TransportCode.tag(2))
        assertEquals("ble", TransportCode.tag(3))
        assertNull(TransportCode.tag(4))
        assertEquals(1L, TransportCode.code("aware"))
        assertEquals(2L, TransportCode.code("nearby"))
        assertEquals(3L, TransportCode.code("ble"))
        assertNull(TransportCode.code("wifi"))
    }

    @Test
    fun preferenceIsStrictAndNeverTargetsBle() {
        assertTrue(TransportPreference.shouldUpgrade("ble", "aware"))
        assertTrue(TransportPreference.shouldUpgrade("ble", "nearby"))
        assertTrue(TransportPreference.shouldUpgrade("nearby", "aware"))
        assertTrue(!TransportPreference.shouldUpgrade("aware", "nearby"))
        assertTrue(!TransportPreference.shouldUpgrade("aware", "aware"))
        assertTrue(!TransportPreference.shouldUpgrade("nearby", "ble")) // ble is never a target
    }

    @Test
    fun upgradeRoundTrips() {
        val body = TransportUpgradeCodec.encodeUpgrade(TransportCode.AWARE, 7)
        val decoded = TransportUpgradeCodec.decodeUpgrade(body)
        assertEquals(TransportCode.AWARE, decoded.transport)
        assertEquals(7L, decoded.attemptId)
    }

    @Test
    fun ackRoundTrips() {
        val decoded = TransportUpgradeCodec.decodeAck(
            TransportUpgradeCodec.encodeAck(TransportCode.NEARBY, 3, accepted = true),
        )
        assertEquals(TransportCode.NEARBY, decoded.transport)
        assertEquals(3L, decoded.attemptId)
        assertTrue(decoded.accepted)
    }

    @Test(expected = ProtocolException::class)
    fun malformedUpgradeMissingFieldThrows() {
        TransportUpgradeCodec.decodeUpgrade(cborMapOf(1L to CborInt(1))) // no attemptId
    }

    @Test(expected = ProtocolException::class)
    fun malformedUpgradeWrongTypeThrows() {
        TransportUpgradeCodec.decodeUpgrade(cborMapOf(1L to CborText("aware"), 2L to CborInt(1)))
    }

    @Test(expected = ProtocolException::class)
    fun malformedAckMissingAcceptedThrows() {
        TransportUpgradeCodec.decodeAck(cborMapOf(1L to CborInt(1), 2L to CborInt(1)))
    }
}
