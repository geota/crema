package coffee.crema.ble

import kotlinx.coroutines.delay

/** One connect-time seed read: its log label, and whether the link depends on it. */
internal data class SeedSpec<K>(val key: K, val label: String, val required: Boolean)

/** A required DE1 subscription (core table) failed: the connect fails rather than sit READY with dead notifications. */
class RequiredSubscriptionException(name: String, cause: Throwable) :
    IllegalStateException("DE1 $name subscribe failed", cause)

/** The connect-time StateInfo read failed twice: the link is up but dead for data. */
class HollowLinkException(cause: Throwable) : IllegalStateException(HOLLOW_LINK_STATUS, cause)

/** Tries of a required seed read before the link counts as hollow. */
internal const val SEED_READ_ATTEMPTS_REQUIRED = 2

/** Pause before the required read's retry, ms. */
internal const val SEED_READ_RETRY_DELAY_MS = 500L

/**
 * The DE1's connect-time seed rule (web `de1SeedProgram` applies the same):
 * each read in order; an optional one that fails is logged and skipped; the
 * required one (StateInfo) gets one retry, and if that fails too the link is
 * hollow — [teardown] drops it and this throws [HollowLinkException], so the
 * caller's establish step fails and the reconnect supervisor backs off.
 *
 * [read] must rethrow cancellation: a session that ended mid-read never
 * reaches the hollow verdict, so a stale judgement can't tear down the next
 * link or post "reconnecting" after a Disconnect. Pure over its lambdas, for
 * the JVM tests ([De1BleManager] needs the native core for its UUIDs).
 */
internal suspend fun <K> runSeedReads(
    seeds: List<SeedSpec<K>>,
    read: suspend (SeedSpec<K>) -> Result<Unit>,
    onStatus: (String) -> Unit,
    teardown: suspend () -> Unit,
) {
    for (seed in seeds) {
        val attempts = if (seed.required) SEED_READ_ATTEMPTS_REQUIRED else 1
        var failure: Throwable? = null
        for (attempt in 1..attempts) {
            // Assigned on every try: `failure = read() ?: break` kept the first
            // try's error when the retry succeeded, so a link the retry saved
            // was still judged hollow (#167's Android seedReads did that).
            failure = read(seed).exceptionOrNull()
            if (failure == null) break
            if (attempt < attempts) delay(SEED_READ_RETRY_DELAY_MS)
        }
        if (failure == null) continue
        if (!seed.required) {
            onStatus("DE1 ${seed.label} seed read skipped: ${failure.message}")
            continue
        }
        onStatus(HOLLOW_LINK_STATUS)
        runCatching { teardown() }
        throw HollowLinkException(failure)
    }
}
