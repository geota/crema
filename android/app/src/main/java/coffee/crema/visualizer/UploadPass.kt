package coffee.crema.visualizer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/*
 * The Visualizer backlog loop's stop / retry policy — the Android twin of the
 * web `ShotSync.uploadUnsyncedShots` + `shouldAbortLoop`. Every Visualizer
 * upload pass (the cross-destination catch-up, "Upload all", Sync now's push)
 * runs through [runUploadPass], so they agree on when to stop:
 *
 *  - the free-plan daily cap (a 422 whose body names the "daily limit",
 *    visualizer 3e9ba33c — detected by the core's `visualizer_quota_limit`):
 *    stop, leave the rest unsynced for tomorrow, and say so;
 *  - the rate limit (429): wait out the window (core `retry_backoff_ms`: 60 s,
 *    then 120 s) and retry the same shot; still limited → stop, the rest stay
 *    in the backlog;
 *  - an expired session: stop.
 *
 * The native core calls are injected ([UploadPolicy]) so the loop is
 * JVM-testable.
 */

/** Why a pass stopped before the end of the backlog. */
sealed class PassStop {
    /** The free plan's daily cap of [limit] new shots. */
    data class Quota(val limit: Int) : PassStop()

    /** Still rate-limited after the retries. */
    data object RateLimited : PassStop()

    /** The Visualizer session is gone. */
    data object Auth : PassStop()

    /** The user-facing line. */
    val notice: String
        get() = when (this) {
            is Quota -> quotaNotice(limit)
            RateLimited -> RATE_LIMIT_NOTICE
            Auth -> "Visualizer session expired"
        }
}

/** The notice when the free-plan daily cap stops uploads (web `quotaNotice`). */
fun quotaNotice(limit: Int): String =
    "Visualizer's free plan uploads up to $limit shots a day — the rest will upload tomorrow."

/** The notice when the rate limit stops a pass (web `RATE_LIMIT_NOTICE`). */
const val RATE_LIMIT_NOTICE = "Visualizer is limiting uploads right now — the rest will retry in a few minutes."

/** One pass's tally. */
data class PassResult(
    val uploaded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val stop: PassStop? = null,
)

/** The core's Visualizer error policy, injected (the native library isn't loaded in JVM tests). */
class UploadPolicy(
    /** Core `visualizer_quota_limit(status, body)`: the cap when this is the quota 422, else null. */
    val quotaLimit: (status: Int, body: String) -> Int?,
    /** Core `retry_backoff_ms(status, attempt)`. */
    val backoffMs: (status: Int?, attempt: Int) -> Long,
    /** How many times a 429 is retried before the pass stops. */
    val maxRateLimitRetries: Int = 2,
) {
    fun quotaLimitOf(e: Throwable): Int? = (e as? VisualizerError.Http)?.let { quotaLimit(it.status, it.body) }

    fun isRateLimited(e: Throwable): Boolean = e is VisualizerError.Http && e.status == 429

    /** The stop a failure forces (null = log it and carry on with the next shot). */
    fun stopFor(e: Throwable): PassStop? = when {
        e is VisualizerError.Auth -> PassStop.Auth
        isRateLimited(e) -> PassStop.RateLimited
        else -> quotaLimitOf(e)?.let { PassStop.Quota(it) }
    }

    /** The user-facing failure line for one shot. */
    fun describe(e: Throwable): String = quotaLimitOf(e)?.let(::quotaNotice) ?: (e.message ?: "failed")

    companion object {
        /** The production policy over the native core. */
        fun core(): UploadPolicy = UploadPolicy(
            quotaLimit = { status, body ->
                status.takeIf { it in 0..0xFFFF }
                    ?.let { coffee.crema.core.visualizerQuotaLimit(it.toUShort(), body)?.toInt() }
            },
            backoffMs = { status, attempt ->
                coffee.crema.core.retryBackoffMs(
                    status?.takeIf { it in 0..0xFFFF }?.toUShort(),
                    attempt.coerceAtLeast(0).toUInt(),
                ).toLong()
            },
        )
    }
}

/**
 * Run [step] over [items] in order. [step] returns true when it uploaded,
 * false when it skipped the item, and throws on failure. A 429 waits
 * ([sleep]) and retries the same item; [onFailure] sees every failure that
 * isn't retried; a failure [UploadPolicy.stopFor] names ends the pass.
 */
suspend fun <T> runUploadPass(
    items: List<T>,
    policy: UploadPolicy,
    sleep: suspend (Long) -> Unit = { delay(it) },
    onFailure: suspend (T, Exception) -> Unit = { _, _ -> },
    step: suspend (T) -> Boolean,
): PassResult {
    var uploaded = 0
    var failed = 0
    var skipped = 0
    for (item in items) {
        var attempt = 0
        while (true) {
            try {
                if (step(item)) uploaded++ else skipped++
                break
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (policy.isRateLimited(e) && attempt < policy.maxRateLimitRetries) {
                    sleep(policy.backoffMs(429, attempt))
                    attempt++
                    continue
                }
                failed++
                onFailure(item, e)
                val stop = policy.stopFor(e)
                if (stop != null) return PassResult(uploaded, failed, skipped, stop)
                break
            }
        }
    }
    return PassResult(uploaded, failed, skipped)
}
