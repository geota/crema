package coffee.crema.ui

/**
 * Run [write] for each of [items] strictly one after another — the next starts
 * only when the previous returned (its retry included). The DE1 write lane of
 * one `CoreOutput` (cohort review 02 P2-2). Top-level for JUnit.
 */
internal suspend fun <T> executeInOrder(items: List<T>, write: suspend (T) -> Unit) {
    for (item in items) write(item)
}

/**
 * One write of a DE1 batch: [write]'s failure — including a timeout or a
 * preemption, which `De1BleManager` raises as `De1WriteFailedException` — is
 * [log]ged and reported as `false`, so the batch goes on; a cancellation of
 * the batch itself propagates. The catch structure of
 * `MainViewModel.deliverDe1Write`, top-level for JUnit (cohort review
 * 2026-10-28, Android P2-1).
 */
internal suspend fun deliverOrLog(write: suspend () -> Boolean, log: (Exception) -> Unit): Boolean =
    try {
        write()
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (e: Exception) {
        log(e)
        false
    }
