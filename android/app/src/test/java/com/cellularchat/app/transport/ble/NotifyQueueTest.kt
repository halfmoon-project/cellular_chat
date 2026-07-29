package com.cellularchat.app.transport.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §9 notify flow control: exactly one notification in flight at a time, nothing
 * dropped. Firing every fragment in a loop (the previous behaviour) loses the
 * ones past the stack's buffer and stalls the peer's reassembly.
 */
class NotifyQueueTest {

    @Test
    fun sendsOneFragmentAtATimeAndNeverDropsTheRest() {
        val sent = ArrayList<Int>()
        val queue = NotifyQueue { sent.add(it[0].toInt()); true }

        (1..5).forEach { queue.enqueue(byteArrayOf(it.toByte())) }
        // Only the first went out; the other four wait for onSent.
        assertEquals(listOf(1), sent)

        repeat(4) { queue.onSent() }
        assertEquals(listOf(1, 2, 3, 4, 5), sent)
    }

    @Test
    fun enqueueingWhileInFlightDoesNotJumpTheQueue() {
        val sent = ArrayList<Int>()
        val queue = NotifyQueue { sent.add(it[0].toInt()); true }

        queue.enqueue(byteArrayOf(1))
        queue.enqueue(byteArrayOf(2))
        queue.onSent()          // 2 goes out
        queue.enqueue(byteArrayOf(3))
        assertEquals(listOf(1, 2), sent)
        queue.onSent()          // 3 goes out
        assertEquals(listOf(1, 2, 3), sent)
    }

    @Test
    fun aFailedSendReleasesTheSlotSoTheQueueDoesNotWedge() {
        val sent = ArrayList<Int>()
        var linkUp = false
        val queue = NotifyQueue { if (linkUp) { sent.add(it[0].toInt()); true } else false }

        queue.enqueue(byteArrayOf(1))   // link down: refused, slot released
        assertEquals(emptyList<Int>(), sent)

        linkUp = true
        queue.enqueue(byteArrayOf(2))
        assertEquals(listOf(2), sent)   // not wedged in flight forever
    }

    @Test
    fun resetDropsPendingFragmentsAndClearsTheSlot() {
        val sent = ArrayList<Int>()
        val queue = NotifyQueue { sent.add(it[0].toInt()); true }

        queue.enqueue(byteArrayOf(1))
        queue.enqueue(byteArrayOf(2))
        queue.reset()
        queue.enqueue(byteArrayOf(9))
        // 2 was dropped with the link; 9 goes out immediately on the free slot.
        assertArrayEquals(intArrayOf(1, 9), sent.toIntArray())
    }
}
