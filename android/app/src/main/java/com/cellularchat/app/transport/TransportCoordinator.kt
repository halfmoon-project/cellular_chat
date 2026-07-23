package com.cellularchat.app.transport

/**
 * Sequential transport arbitration (IMPLEMENTATION_PLAN.md §4, PROTOCOL_V2.md
 * §10). Candidates are attempted strictly in preference order — Wi-Fi Aware,
 * then Nearby Connections, then the mandatory BLE GATT — each with its own
 * measured timeout. The first candidate that reaches an authenticated link
 * wins; the losers are never started in parallel, so two high-power discovery
 * mechanisms never run at once.
 *
 * Wholly synchronous-friendly: a candidate may invoke its callback inline
 * (tests) or later from a radio callback (production). Re-entrancy is guarded so
 * an inline callback still advances the ladder exactly once per candidate.
 */
class TransportCoordinator(
    private val candidates: List<TransportCandidate>,
    private val timeoutForTag: (String) -> Long = { tag ->
        when (tag) {
            "aware" -> 4_000L
            "nearby" -> 4_000L
            else -> 0L // BLE is mandatory: no arbitration timeout.
        }
    },
) {
    sealed interface Result {
        data class Won(val tag: String, val transport: PeerTransport) : Result
        data object Exhausted : Result
    }

    private var index = 0
    private var active: TransportCandidate? = null
    private var finished = false
    private var onResult: ((Result) -> Unit)? = null

    fun arbitrate(onResult: (Result) -> Unit) {
        check(this.onResult == null) { "arbitration already in progress" }
        this.onResult = onResult
        index = 0
        finished = false
        advance()
    }

    /** Aborts arbitration and cancels any in-flight attempt. */
    fun cancel() {
        if (finished) return
        finished = true
        active?.cancel()
        active = null
        onResult = null
    }

    private fun advance() {
        while (index < candidates.size && !finished) {
            val candidate = candidates[index]
            index += 1
            if (!candidate.isAvailable()) continue
            attempt(candidate)
            return
        }
        if (!finished) finish(Result.Exhausted)
    }

    private fun attempt(candidate: TransportCandidate) {
        active = candidate
        var settled = false
        candidate.attempt(
            timeoutForTag(candidate.tag),
            object : TransportCandidate.AttemptCallback {
                override fun onConnected(transport: PeerTransport) {
                    if (settled || finished) return
                    settled = true
                    active = null
                    finish(Result.Won(candidate.tag, transport))
                }

                override fun onFailed() {
                    if (settled || finished) return
                    settled = true
                    active = null
                    advance()
                }
            },
        )
    }

    private fun finish(result: Result) {
        finished = true
        val callback = onResult
        onResult = null
        callback?.invoke(result)
    }
}
