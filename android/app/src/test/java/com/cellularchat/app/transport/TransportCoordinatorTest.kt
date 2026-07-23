package com.cellularchat.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake in-memory transport used as an arbitration winner. */
private class FakeTransport(override val tag: String) : PeerTransport {
    override fun setListener(listener: PeerTransport.Listener) = Unit
    override fun send(record: ByteArray) = Unit
    override fun close() = Unit
}

private class FakeCandidate(
    override val tag: String,
    private val available: Boolean,
    private val connects: Boolean,
) : TransportCandidate {
    var attempted = false
    var cancelled = false

    override fun isAvailable(): Boolean = available

    override fun attempt(timeoutMillis: Long, callback: TransportCandidate.AttemptCallback) {
        attempted = true
        if (connects) callback.onConnected(FakeTransport(tag)) else callback.onFailed()
    }

    override fun cancel() {
        cancelled = true
    }
}

class TransportCoordinatorTest {
    private fun run(candidates: List<TransportCandidate>): TransportCoordinator.Result {
        var result: TransportCoordinator.Result? = null
        TransportCoordinator(candidates).arbitrate { result = it }
        return result!!
    }

    @Test
    fun prefersAwareWhenItConnects() {
        val aware = FakeCandidate("aware", available = true, connects = true)
        val nearby = FakeCandidate("nearby", available = true, connects = true)
        val ble = FakeCandidate("ble", available = true, connects = true)
        val result = run(listOf(aware, nearby, ble))
        assertEquals("aware", (result as TransportCoordinator.Result.Won).tag)
        // Losers are never even started (no parallel high-power discovery).
        assertTrue(aware.attempted)
        assertFalse(nearby.attempted)
        assertFalse(ble.attempted)
    }

    @Test
    fun skipsUnavailableAndFallsToNearby() {
        val aware = FakeCandidate("aware", available = false, connects = true)
        val nearby = FakeCandidate("nearby", available = true, connects = true)
        val ble = FakeCandidate("ble", available = true, connects = true)
        val result = run(listOf(aware, nearby, ble))
        assertEquals("nearby", (result as TransportCoordinator.Result.Won).tag)
        assertFalse(aware.attempted)
        assertTrue(nearby.attempted)
        assertFalse(ble.attempted)
    }

    @Test
    fun fallsAllTheWayToMandatoryBle() {
        val aware = FakeCandidate("aware", available = true, connects = false)
        val nearby = FakeCandidate("nearby", available = true, connects = false)
        val ble = FakeCandidate("ble", available = true, connects = true)
        val result = run(listOf(aware, nearby, ble))
        assertEquals("ble", (result as TransportCoordinator.Result.Won).tag)
        assertTrue(aware.attempted)
        assertTrue(nearby.attempted)
        assertTrue(ble.attempted)
    }

    @Test
    fun exhaustedWhenNothingConnects() {
        val result = run(
            listOf(
                FakeCandidate("aware", available = false, connects = false),
                FakeCandidate("nearby", available = true, connects = false),
                FakeCandidate("ble", available = true, connects = false),
            ),
        )
        assertTrue(result is TransportCoordinator.Result.Exhausted)
    }

    @Test
    fun cancelStopsInFlightCandidate() {
        // A candidate that never settles, so arbitration is still in progress.
        val stalling = object : TransportCandidate {
            override val tag = "aware"
            var cancelled = false
            override fun isAvailable() = true
            override fun attempt(timeoutMillis: Long, callback: TransportCandidate.AttemptCallback) = Unit
            override fun cancel() { cancelled = true }
        }
        val coordinator = TransportCoordinator(listOf(stalling))
        var result: TransportCoordinator.Result? = null
        coordinator.arbitrate { result = it }
        assertNull(result)
        coordinator.cancel()
        assertTrue(stalling.cancelled)
    }
}
