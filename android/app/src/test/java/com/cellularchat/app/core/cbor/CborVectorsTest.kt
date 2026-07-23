package com.cellularchat.app.core.cbor

import com.cellularchat.app.core.CborException
import com.cellularchat.app.core.Vectors
import com.cellularchat.app.core.hex
import com.cellularchat.app.core.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CborVectorsTest {
    private val vectors = Vectors.json("cbor_vectors.json")

    @Test
    fun acceptCasesRoundTripToExactHex() {
        val accept = vectors.getJSONArray("accept")
        for (i in 0 until accept.length()) {
            val entry = accept.getJSONObject(i)
            val expectedHex = entry.getString("hex")
            val decoded = Cbor.decode(hex(expectedHex))
            val reencoded = Cbor.encode(decoded).toHex()
            assertEquals("re-encode of ${entry.getString("diag")}", expectedHex, reencoded)
        }
    }

    @Test
    fun rejectCasesThrow() {
        val reject = vectors.getJSONArray("reject")
        for (i in 0 until reject.length()) {
            val entry = reject.getJSONObject(i)
            val bytes = hex(entry.getString("hex"))
            try {
                val value = Cbor.decode(bytes)
                fail("expected rejection for ${entry.getString("reason")}, got $value")
            } catch (expected: CborException) {
                // Every §3 violation must be rejected.
            }
        }
    }
}
