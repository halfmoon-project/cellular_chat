package com.cellularchat.app

import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.core.protocol.FindState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FindRecoveryLoopTest {

    /** Drives a real (pure) FindSessionCoordinator with injected side-effects. */
    private class Harness(startNow: Long = 0L, deadline: Long = 1_000L) {
        var last = FindUiState()
        val coordinator = FindSessionCoordinator { last = it }
        var teardowns = 0
        var searches = 0
        var scheduledDelay: Long? = null
        private var scheduledAction: (() -> Unit)? = null
        var now = startNow

        val loop = FindRecoveryLoop(
            coordinator = coordinator,
            teardownTransport = { teardowns++ },
            beginSearch = { searches++ },
            scheduleRetry = { d, a -> scheduledDelay = d; scheduledAction = a },
            now = { now },
        ).also { it.armed(deadline) }

        val state get() = last.state
        fun runScheduled() = scheduledAction?.invoke()

        /** Drives the coordinator to CONNECTED. */
        fun connect() {
            coordinator.arm(1_000)
            coordinator.onPeerFound(); coordinator.onTransportConnected(); coordinator.onAuthenticated()
        }
    }

    @Test
    fun lostLinkTearsDownAndReEntersArbitration() {
        val h = Harness()
        h.connect()
        assertEquals(FindState.CONNECTED, h.state)

        h.loop.onLinkClosed(ReasonCodes.TRANSPORT_LOST)
        assertEquals(FindState.RETRY_WAIT, h.state)
        assertEquals(1, h.teardowns)
        assertEquals(5_000L, h.scheduledDelay) // first backoff step

        h.runScheduled() // retryWait -> searching -> beginSearch
        assertEquals(FindState.SEARCHING, h.state)
        assertEquals(1, h.searches)
    }

    @Test
    fun backoffGrowsAcrossRepeatedLossesUntilReauthentication() {
        val h = Harness()
        h.connect()
        h.loop.onLinkClosed(ReasonCodes.TRANSPORT_LOST)
        assertEquals(5_000L, h.scheduledDelay)
        h.runScheduled() // -> searching
        h.coordinator.onPeerFound() // searching -> p2pConnecting (loseable again)

        h.loop.onLinkClosed(ReasonCodes.TRANSPORT_LOST)
        assertEquals("backoff doubles without a fresh authentication", 10_000L, h.scheduledDelay)

        // A fresh link resets the backoff.
        h.runScheduled() // -> searching
        h.coordinator.onPeerFound()
        h.loop.onAuthenticated()
        h.loop.onLinkClosed(ReasonCodes.TRANSPORT_LOST)
        assertEquals(5_000L, h.scheduledDelay)
    }

    @Test
    fun exhaustedArbitrationRetriesTheLadder() {
        val h = Harness()
        h.coordinator.arm(1_000)
        h.coordinator.onPeerFound() // p2pConnecting
        h.loop.onArbitrationExhausted()
        assertEquals(FindState.RETRY_WAIT, h.state)
        assertEquals(1, h.teardowns)
        assertEquals(5_000L, h.scheduledDelay)
    }

    @Test
    fun retryAfterDeadlineExpiresInsteadOfSearching() {
        val h = Harness(startNow = 2_000L, deadline = 1_000L) // already past the deadline
        h.connect()
        h.loop.onLinkClosed(ReasonCodes.TRANSPORT_LOST)
        assertEquals(FindState.EXPIRED, h.state)
        assertEquals(0, h.searches)
        assertNull(h.scheduledDelay)
    }

    @Test
    fun staleLossFromUnlinkedStateIsIgnored() {
        val h = Harness()
        h.coordinator.arm(1_000) // searching (not a linked state)
        h.loop.onLinkClosed(ReasonCodes.TRANSPORT_LOST)
        assertEquals(FindState.SEARCHING, h.state)
        assertEquals(0, h.teardowns)
        assertNull(h.scheduledDelay)
    }
}
