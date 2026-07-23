package com.cellularchat.app.transport

import android.os.Handler
import android.os.Looper

/**
 * Adapts a radio [PeerTransport] to the arbitration [TransportCandidate]
 * contract (IMPLEMENTATION_PLAN.md §4): availability gating, a measured timeout,
 * and a single-settle guarantee so an inline or late radio callback advances the
 * ladder exactly once.
 */
class RadioTransportCandidate(
    override val tag: String,
    private val available: () -> Boolean,
    private val make: (onReady: () -> Unit) -> PeerTransport?,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : TransportCandidate {

    private var transport: PeerTransport? = null
    private var settled = false
    private var timeoutRunnable: Runnable? = null

    override fun isAvailable(): Boolean = available()

    override fun attempt(timeoutMillis: Long, callback: TransportCandidate.AttemptCallback) {
        val built = make {
            if (settled) return@make
            settled = true
            clearTimeout()
            val t = transport
            if (t != null) callback.onConnected(t) else callback.onFailed()
        }
        if (built == null) {
            if (!settled) {
                settled = true
                callback.onFailed()
            }
            return
        }
        transport = built
        if (timeoutMillis > 0) {
            val runnable = Runnable {
                if (settled) return@Runnable
                settled = true
                runCatching { built.close() }
                callback.onFailed()
            }
            timeoutRunnable = runnable
            handler.postDelayed(runnable, timeoutMillis)
        }
    }

    override fun cancel() {
        clearTimeout()
        runCatching { transport?.close() }
    }

    private fun clearTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }
}
