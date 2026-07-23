package com.cellularchat.app.transport.ble

import com.cellularchat.app.core.FragmentException
import com.cellularchat.app.core.protocol.Fragmentation
import com.cellularchat.app.core.protocol.Reassembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BleFragmentChannelTest {

    /** A FIRST fragment then silence: the §9 deadline still trips via checkTimeout. */
    @Test
    fun checkTimeoutFailsAStalledReassembly() {
        var now = 0L
        val reassembler = Reassembler(clock = { now })
        val first = Fragmentation.fragment(ByteArray(50) { 1 }, mtu = 23).first()
        assertNull(reassembler.offer(first))
        assertTrue(reassembler.reassemblyInProgress)

        now = 9_999
        reassembler.checkTimeout() // still within the window: no throw.
        now = 10_001
        try {
            reassembler.checkTimeout()
            fail("expected a fragment timeout")
        } catch (e: FragmentException) {
            assertEquals("timeout", e.error)
        }
    }

    /** The channel arms the deadline on a starting reassembly and tears down on stall. */
    @Test
    fun channelArmsTimeoutAndTearsDownOnStall() {
        var now = 0L
        val pending = ArrayList<() -> Unit>()
        var tornDown = false
        val channel = BleFragmentChannel(
            reassembler = Reassembler(clock = { now }),
            scheduleTimeout = { _, action -> pending.add(action) },
            onStalled = { tornDown = true },
        )
        val first = Fragmentation.fragment(ByteArray(50) { 7 }, mtu = 23).first()
        assertNull(channel.accept(first))
        assertEquals(1, pending.size)

        now = 10_001
        pending[0]()
        assertTrue(tornDown)
    }
}
