package com.cellularchat.app.core.crypto

import com.cellularchat.app.core.Vectors
import com.cellularchat.app.core.hex
import com.cellularchat.app.core.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryVectorsTest {
    private val derivation = Vectors.json("derivation_vectors.json")
    private val v = Vectors.json("discovery_vectors.json")

    private fun discoveryKey(role: Byte): ByteArray {
        val pairRoot = hex(derivation.getString("pairRootHex"))
        return Derivations.discoveryKey(pairRoot, role)
    }

    @Test
    fun tokensMatchFixture() {
        val tokens = v.getJSONArray("tokens")
        for (i in 0 until tokens.length()) {
            val entry = tokens.getJSONObject(i)
            val role = if (entry.getString("role") == "A") Derivations.ROLE_A else Derivations.ROLE_B
            val epoch = entry.getLong("epoch")
            assertEquals(entry.getString("inputHex"), Discovery.tokenInput(epoch, role).toHex())
            assertEquals(
                "token role=${entry.getString("role")} epoch=$epoch",
                entry.getString("tokenHex"),
                Discovery.token(discoveryKey(role), epoch, role).toHex(),
            )
        }
    }

    @Test
    fun acceptanceWindowAcceptsAdjacentEpochsAndRejectsFarther() {
        val now = v.getLong("unixSeconds")
        val role = Derivations.ROLE_A
        val key = discoveryKey(role)
        val currentEpoch = Discovery.epoch(now)

        for (delta in -1..1) {
            val token = Discovery.token(key, currentEpoch + delta, role)
            assertTrue("epoch delta $delta accepted", Discovery.isWithinAcceptanceWindow(key, now, role, token))
        }
        for (delta in intArrayOf(-2, 2)) {
            val token = Discovery.token(key, currentEpoch + delta, role)
            assertFalse("epoch delta $delta rejected", Discovery.isWithinAcceptanceWindow(key, now, role, token))
        }
    }
}
