package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.Vectors
import org.junit.Assert.assertEquals
import org.junit.Test

class StateTransitionsTest {
    private val v = Vectors.json("state_transitions.json")

    @Test
    fun reducerAgreesWithEveryRow() {
        val transitions = v.getJSONArray("transitions")
        assertEquals(256, transitions.length())
        for (i in 0 until transitions.length()) {
            val row = transitions.getJSONObject(i)
            val state = FindState.fromWire(row.getString("state"))
            val event = FindEvent.fromWire(row.getString("event"))
            val expectedNext = FindState.fromWire(row.getString("next"))
            val expectedValid = row.getBoolean("valid")

            val result = FindStateMachine.reduce(state, event)
            assertEquals("${row.getString("state")}/${row.getString("event")} valid", expectedValid, result.valid)
            assertEquals("${row.getString("state")}/${row.getString("event")} next", expectedNext, result.next)
        }
    }
}
