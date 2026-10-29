package coffee.crema.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Defer before a post-steam auto-purge so an immediate shot wins the
 *  group (issue 15). */
const val AUTO_PURGE_DELAY_MS = 1_500L

/**
 * The post-steam auto-purge (issue 15): a short deferred group flush that
 * clears the steam plumbing once the user releases steam. Deferred so a user
 * who starts a shot straight away wins the group: the purge stands down while
 * a shot start is pending ([startPending]).
 *
 * Only the device that holds the DE1 runs it ([authoritative]). A mirror sees
 * the same `SteamSessionCompleted`, and its purge used to reach the primary
 * as a relayed Flush: a second purge, and a user request that cancels the
 * primary's pending shot start (PR #197 review #1).
 */
internal class AutoPurge(
    private val scope: CoroutineScope,
    private val authoritative: () -> Boolean,
    private val startPending: () -> Boolean,
    private val flush: () -> Unit,
    private val delayMs: Long = AUTO_PURGE_DELAY_MS,
) {
    fun schedule() {
        if (!authoritative()) return
        scope.launch {
            delay(delayMs)
            if (!authoritative() || startPending()) return@launch
            flush()
        }
    }
}
