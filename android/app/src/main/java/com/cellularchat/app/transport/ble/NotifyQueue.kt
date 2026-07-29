package com.cellularchat.app.transport.ble

import java.util.ArrayDeque

/**
 * Serializes `outbox` notifications for one GATT link (PROTOCOL_V2.md §9).
 *
 * Android accepts one `notifyCharacteristicChanged` at a time and reports
 * completion through `onNotificationSent`. Firing the next fragment before that
 * callback drops it once the stack's buffer fills, and a dropped fragment stalls
 * the peer's reassembly into the 10-second §9 budget — which reaches the user as
 * "went out of range" rather than as a send failure.
 *
 * [send] returns false when the link is gone; the queue then stops rather than
 * spinning.
 *
 * Synchronized because the three entry points genuinely run on different
 * threads: `enqueue` from the sending thread via `PeerTransport.send`, and both
 * `onSent` and `reset` from GATT server callbacks on a binder thread. An
 * unguarded `ArrayDeque` corrupts under concurrent add/poll, and a torn read of
 * `inFlight` breaks it in both directions — stale false puts two notifications
 * in flight (the very bug this class exists to prevent), stale true wedges the
 * queue forever. The monitor is reentrant, so [pump] may call back in.
 */
internal class NotifyQueue(private val send: (ByteArray) -> Boolean) {

    private val queue = ArrayDeque<ByteArray>()
    private var inFlight = false

    @Synchronized
    fun enqueue(fragment: ByteArray) {
        queue.addLast(fragment)
        pump()
    }

    /** One notification completed; release the slot and send the next. */
    @Synchronized
    fun onSent() {
        inFlight = false
        pump()
    }

    @Synchronized
    fun reset() {
        queue.clear()
        inFlight = false
    }

    private fun pump() {
        if (inFlight) return
        val next = queue.pollFirst() ?: return
        inFlight = true
        if (!send(next)) inFlight = false
    }
}
