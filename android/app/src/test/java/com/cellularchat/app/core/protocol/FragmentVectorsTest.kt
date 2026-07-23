package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.FragmentException
import com.cellularchat.app.core.Vectors
import com.cellularchat.app.core.hex
import com.cellularchat.app.core.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FragmentVectorsTest {
    private val v = Vectors.json("fragment_vectors.json")

    @Test
    fun fragmentingAndReassemblingMatchesFixture() {
        val cases = v.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val record = hex(case.getString("recordHex"))
            val mtu = case.getInt("mtu")
            val expected = case.getJSONArray("fragments")

            val produced = Fragmentation.fragment(record, mtu)
            assertEquals("$name fragment count", expected.length(), produced.size)
            for (f in produced.indices) {
                assertEquals("$name fragment $f", expected.getString(f), produced[f].toHex())
            }

            val reassembled = Fragmentation.reassemble(produced.toList())
            assertArrayEquals("$name reassembly", record, reassembled)
        }
    }

    @Test
    fun malformedFragmentsFailWithNamedError() {
        val malformed = v.getJSONArray("malformed")
        for (i in 0 until malformed.length()) {
            val case = malformed.getJSONObject(i)
            val name = case.getString("name")
            val expectedError = case.getString("error")
            val fragments = case.getJSONArray("fragments")

            val reassembler = Reassembler()
            try {
                for (f in 0 until fragments.length()) {
                    reassembler.offer(hex(fragments.getString(f)))
                }
                fail("$name expected FragmentException($expectedError)")
            } catch (e: FragmentException) {
                assertEquals("$name error name", expectedError, e.error)
            }
        }
    }
}
