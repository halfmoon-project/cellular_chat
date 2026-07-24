package com.cellularchat.app

import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.core.protocol.FindState
import com.cellularchat.app.ranging.BackoffSchedule

/**
 * Transport-loss recovery loop (IMPLEMENTATION_PLAN.md §5 `signalLost ->
 * retryWait -> searching`, Phase 5). Mirrors iOS
 * `FindSessionCoordinator.enterSignalLostAndRetry`: a lost link (or a
 * transport-arbitration pass that found nothing) drops to `signalLost`, tears
 * down the dead transport while keeping the Find session, then backs off and
 * re-enters transport arbitration until the find deadline. Entering `signalLost`
 * already clears stale ranging UI, so nothing else is needed for that.
 *
 * Pure with respect to radios and time: every side effect is injected, so the
 * loop is unit-tested with fakes.
 */
class FindRecoveryLoop(
    private val coordinator: FindSessionCoordinator,
    private val teardownTransport: () -> Unit,
    private val beginSearch: () -> Unit,
    private val scheduleRetry: (delayMillis: Long, action: () -> Unit) -> Unit,
    private val now: () -> Long,
    private val backoff: BackoffSchedule = BackoffSchedule(),
) {
    private var deadlineMillis: Long = 0

    /** Arms the loop for a session ending at [deadlineMillis]; resets backoff. */
    fun armed(deadlineMillis: Long) {
        this.deadlineMillis = deadlineMillis
        backoff.reset()
    }

    /** A fresh link authenticated: start the backoff over (§10). */
    fun onAuthenticated() = backoff.reset()

    /** The active link closed; re-enter transport arbitration. */
    fun onLinkClosed(reason: Int) = enterSignalLostAndRetry(reason)

    /** A transport-arbitration pass found nothing: retry the whole ladder. */
    fun onArbitrationExhausted() = enterSignalLostAndRetry(ReasonCodes.TRANSPORT_LOST)

    private fun enterSignalLostAndRetry(reason: Int) {
        coordinator.onSignalLost(reason)
        // Ignore a stale/duplicate loss from an already-unlinked state.
        if (coordinator.uiState.state != FindState.SIGNAL_LOST) return
        teardownTransport()
        coordinator.onRetryWait()
        schedule()
    }

    private fun schedule() {
        if (expired()) {
            coordinator.expire()
            return
        }
        val delay = backoff.nextDelayMillis()
        scheduleRetry(delay) {
            if (coordinator.uiState.state != FindState.RETRY_WAIT) return@scheduleRetry
            if (expired()) {
                coordinator.expire()
                return@scheduleRetry
            }
            coordinator.onRetryElapsed() // retryWait -> searching
            if (coordinator.uiState.state != FindState.SEARCHING) return@scheduleRetry
            beginSearch()
        }
    }

    private fun expired(): Boolean = now() >= deadlineMillis
}
