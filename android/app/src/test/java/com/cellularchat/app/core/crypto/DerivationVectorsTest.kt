package com.cellularchat.app.core.crypto

import com.cellularchat.app.core.Vectors
import com.cellularchat.app.core.hex
import com.cellularchat.app.core.toHex
import org.junit.Assert.assertEquals
import org.junit.Test

class DerivationVectorsTest {
    private val v = Vectors.json("derivation_vectors.json")

    @Test
    fun derivedValuesMatchFixture() {
        val secret = hex(v.getString("secretHex"))
        val pairId = hex(v.getString("pairIdHex"))
        val staticA = hex(v.getString("staticAPubHex"))
        val staticB = hex(v.getString("staticBPubHex"))
        val handshakeHash = hex(v.getString("pairingHandshakeHashHex"))

        val pairingPsk = Derivations.pairingPsk(secret)
        assertEquals(v.getString("pairingPskHex"), pairingPsk.toHex())

        val pairRoot = Derivations.pairRoot(handshakeHash, pairingPsk, staticA, staticB)
        assertEquals(v.getString("pairRootHex"), pairRoot.toHex())

        assertEquals(v.getString("sessionPskHex"), Derivations.sessionPsk(pairRoot).toHex())
        assertEquals(v.getString("discKeyAHex"), Derivations.discoveryKey(pairRoot, Derivations.ROLE_A).toHex())
        assertEquals(v.getString("discKeyBHex"), Derivations.discoveryKey(pairRoot, Derivations.ROLE_B).toHex())
        assertEquals(v.getString("confirmAHex"), Derivations.confirmMac(pairRoot, Derivations.ROLE_A).toHex())
        assertEquals(v.getString("confirmBHex"), Derivations.confirmMac(pairRoot, Derivations.ROLE_B).toHex())

        val fingerprint = Derivations.fingerprint(pairId, staticA, staticB)
        assertEquals(v.getString("fingerprintHex"), fingerprint.toHex())
        assertEquals(v.getString("fingerprintDisplay"), Derivations.fingerprintDisplay(fingerprint))
    }

    @Test
    fun publicKeysDeriveFromPrivateKeys() {
        assertEquals(v.getString("staticAPubHex"), X25519.derivePublic(hex(v.getString("staticAPrivHex"))).toHex())
        assertEquals(v.getString("staticBPubHex"), X25519.derivePublic(hex(v.getString("staticBPrivHex"))).toHex())
    }
}
