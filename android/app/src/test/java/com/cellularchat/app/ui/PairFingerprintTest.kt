package com.cellularchat.app.ui

import com.cellularchat.app.core.crypto.Derivations
import com.cellularchat.app.core.crypto.X25519
import com.cellularchat.app.identity.PairRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairFingerprintTest {
    private val pairId = ByteArray(16) { 7 }
    private val privA = ByteArray(32) { (it + 1).toByte() }
    private val privB = ByteArray(32) { (it + 100).toByte() }
    private val pubA = X25519.derivePublic(privA)
    private val pubB = X25519.derivePublic(privB)

    private fun recordA() = PairRecord(
        pairId = pairId,
        role = 1,
        localStaticPrivate = privA,
        peerStaticPublic = pubB,
        pairRoot = ByteArray(32),
        negotiatedVersion = 2,
        alias = "A쪽",
        createdAt = 1_700_000_000,
    )

    private fun recordB() = PairRecord(
        pairId = pairId,
        role = 2,
        localStaticPrivate = privB,
        peerStaticPublic = pubA,
        pairRoot = ByteArray(32),
        negotiatedVersion = 2,
        alias = "B쪽",
        createdAt = 1_700_000_000,
    )

    @Test
    fun bothSidesComputeTheSameSixDigitDisplay() {
        val a = PairFingerprint.display(recordA())
        val b = PairFingerprint.display(recordB())
        assertEquals(a, b)
        assertEquals(6, a.length)
        assertTrue(a.all { it.isDigit() })
    }

    @Test
    fun matchesTheProtocolFingerprintDerivation() {
        // Same value the pairing confirmation showed: fpr over (pairId, staticA, staticB).
        val expected = Derivations.fingerprintDisplay(Derivations.fingerprint(pairId, pubA, pubB))
        assertEquals(expected, PairFingerprint.display(recordA()))
    }
}
