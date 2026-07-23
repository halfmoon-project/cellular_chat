package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.Vectors
import com.cellularchat.app.core.hex
import com.cellularchat.app.core.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingProtocolTest {
    private val derivation = Vectors.json("derivation_vectors.json")
    private val app = Vectors.json("noise_app_vectors.json").getJSONObject("pairing")

    private val pairId = hex(derivation.getString("pairIdHex"))
    private val pairingPsk = hex(derivation.getString("pairingPskHex"))
    private val staticAPriv = hex(derivation.getString("staticAPrivHex"))
    private val staticBPriv = hex(derivation.getString("staticBPrivHex"))

    @Test
    fun fullPairingReplayMatchesFixtureBothRoles() {
        val b = PairingProtocol.roleB(
            pairId, pairingPsk, staticBPriv,
            fixedEphemeralPrivate = hex(app.getString("initEphemeralPrivHex")),
        )
        val a = PairingProtocol.roleA(
            pairId, pairingPsk, staticAPriv,
            fixedEphemeralPrivate = hex(app.getString("respEphemeralPrivHex")),
        )

        val msg1 = b.startHandshake()
        assertEquals("pair_hello", app.getString("msg1RecordHex"), msg1.toHex())
        a.readHello(msg1)
        val msg2 = a.writeChallenge()
        assertEquals("pair_challenge", app.getString("msg2RecordHex"), msg2.toHex())
        b.readChallenge(msg2)

        val expectedHash = app.getString("handshakeHashHex")
        assertEquals(expectedHash, b.handshakeHash.toHex())
        assertEquals(expectedHash, a.handshakeHash.toHex())

        val records = app.getJSONArray("transportRecords")
        fun expected(index: Int) = records.getJSONObject(index).getString("recordHex")

        assertEquals("B bind", expected(0), b.sendBind().toHex())
        a.receive(hex(expected(0)))
        assertEquals("A bind", expected(1), a.sendBind().toHex())
        b.receive(hex(expected(1)))
        assertEquals("B proof", expected(2), b.sendProof().toHex())
        a.receive(hex(expected(2)))
        assertEquals("A proof", expected(3), a.sendProof().toHex())
        b.receive(hex(expected(3)))
        assertEquals("B complete", expected(4), b.sendComplete().toHex())
        a.receive(hex(expected(4)))
        assertEquals("A complete", expected(5), a.sendComplete().toHex())
        b.receive(hex(expected(5)))

        for (staged in listOf(b.stagedPairData!!, a.stagedPairData!!)) {
            assertEquals(derivation.getString("pairRootHex"), staged.pairRoot.toHex())
            assertEquals(derivation.getString("fingerprintHex"), staged.fingerprint.toHex())
            assertEquals(derivation.getString("fingerprintDisplay"), staged.fingerprintDisplay)
            assertArrayEquals(hex(derivation.getString("staticAPubHex")), staged.staticA)
            assertArrayEquals(hex(derivation.getString("staticBPubHex")), staged.staticB)
            assertEquals(2L, staged.negotiatedVersion)
        }
    }
}
