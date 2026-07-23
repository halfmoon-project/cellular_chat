package com.cellularchat.app.core.crypto

import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.Vectors
import com.cellularchat.app.core.hex
import com.cellularchat.app.core.toHex
import com.cellularchat.app.core.cbor.Cbor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class InviteVectorsTest {
    private val v = Vectors.json("invite_vectors.json")
    private val now = v.getLong("nowUnixSeconds")

    @Test
    fun validInvitationParsesAndReencodes() {
        val valid = v.getJSONObject("valid")
        val parsed = Invitation.parse(valid.getString("text"), now)
        assertArrayEquals(hex(valid.getString("pairIdHex")), parsed.pairId)
        assertArrayEquals(hex(valid.getString("secretHex")), parsed.secret)
        assertEquals(valid.getLong("createdAt"), parsed.createdAt)

        assertEquals(valid.getString("text"), Invitation.encode(parsed))
        assertEquals(valid.getString("cborHex"), Cbor.encode(Cbor.decode(hex(valid.getString("cborHex")))).toHex())
    }

    @Test
    fun rejectCasesFail() {
        val reject = v.getJSONArray("reject")
        for (i in 0 until reject.length()) {
            val entry = reject.getJSONObject(i)
            try {
                val parsed = Invitation.parse(entry.getString("text"), now)
                fail("expected rejection for ${entry.getString("reason")}, got $parsed")
            } catch (expected: ProtocolException) {
                // Every §4 rule violation must be rejected.
            }
        }
    }
}
