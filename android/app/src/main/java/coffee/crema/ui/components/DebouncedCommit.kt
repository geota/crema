package coffee.crema.ui.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Collapses a burst of edits into one commit: [update] records the newest
 * value and (re)starts a [delayMs] timer; the value is committed when the
 * timer runs out, or at once on [flush] (a chip tap, leaving the shot).
 * A value equal to the last committed one is not committed again.
 *
 * For text fields whose every commit is expensive — a person field's commit
 * rewrites the whole shot history, schedules a Visualizer PATCH and re-sends
 * the Decent copy (cohort review 2026-10-25, 06 #15).
 */
class DebouncedCommit<T>(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    initial: T,
    private val commit: (T) -> Unit,
) {
    private var job: Job? = null
    private var pending: T = initial
    private var hasPending = false
    private var committed: T = initial

    /** Record [value]; it is committed after [delayMs] without a newer one. */
    fun update(value: T) {
        pending = value
        hasPending = true
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            flush()
        }
    }

    /** Commit a pending value now (no-op when nothing changed). */
    fun flush() {
        job?.cancel()
        job = null
        if (!hasPending) return
        hasPending = false
        if (pending == committed) return
        committed = pending
        commit(pending)
    }
}
