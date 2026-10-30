package coffee.crema.visualizer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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
    /** Core `classify_token_refresh_failure(status, body)`: `"Definitive"`, `"Retryable"` or `"Transient"`. */
    val refreshFailure: (status: Int?, body: String?) -> String = { _, _ -> "Transient" },
    /** Core `visualizer_queue_decision(op, tag, status, body, attemptsMade)` as JSON. */
    val queueDecisionJson: (op: String, tag: String, status: Int?, body: String, attemptsMade: Int) -> String =
        { _, _, _, _, _ -> "{\"kind\":\"Drop\",\"details\":{\"stop_pass\":false}}" },
) {
    /** Whether a failed refresh-token grant ([e] from the refresh) ends the session. */
    fun refreshEndsSession(e: Throwable): Boolean {
        val reply = e as? TokenEndpointError
        return refreshFailure(reply?.status, reply?.body) == "Definitive"
    }

    /** The retry queue's rule for a queued [op] that failed with [e], [attemptsMade] attempts in. */
    fun queueDecision(op: String, e: Throwable, attemptsMade: Int): RetryDecision {
        val http = e as? VisualizerError.Http
        return RetryDecision.parse(queueDecisionJson(op, errorTag(e), http?.status, http?.body.orEmpty(), attemptsMade))
    }

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
            refreshFailure = { status, body ->
                coffee.crema.core.classifyTokenRefreshFailure(status?.takeIf { it in 0..0xFFFF }?.toUShort(), body)
            },
            queueDecisionJson = { op, tag, status, body, attempts ->
                coffee.crema.core.visualizerQueueDecision(
                    op,
                    tag,
                    status?.takeIf { it in 0..0xFFFF }?.toUShort(),
                    body,
                    attempts.coerceAtLeast(0).toUInt(),
                )
            },
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

/** The core's error taxonomy tag for [e] (`VisualizerCallError::from_tag`). */
fun errorTag(e: Throwable): String = when (e) {
    is VisualizerError.Auth -> "NotAuthenticatedError"
    is VisualizerError.PremiumGated -> "VisualizerPremiumGatedError"
    is VisualizerError.NotFound -> "VisualizerNotFoundError"
    is VisualizerError.Http -> "HttpStatusError"
    is VisualizerError.Network, is java.io.IOException -> "NetworkError"
    else -> "ResponseDecodeError"
}

/** What the retry queue does with a failed operation (core `QueueDecision`). */
sealed class RetryDecision {
    /** Remove it; [stopPass]: stop this drain (the daily quota). */
    data class Drop(val stopPass: Boolean) : RetryDecision()

    /** Keep it without spending an attempt; look again after [delayMs]. */
    data class Park(val delayMs: Long) : RetryDecision()

    /** Retry after [delayMs]; [stopPass]: leave the rest for a later drain (a 429). */
    data class Retry(val delayMs: Long, val stopPass: Boolean) : RetryDecision()

    companion object {
        /** Read the core's `QueueDecision` JSON (`{"kind":…,"details":{…}}`). */
        fun parse(json: String): RetryDecision {
            val obj = Json.parseToJsonElement(json).jsonObject
            val details = obj["details"]?.jsonObject
            fun long(k: String) = details?.get(k)?.jsonPrimitive?.longOrNull ?: 0L
            fun bool(k: String) = details?.get(k)?.jsonPrimitive?.booleanOrNull ?: false
            return when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
                "Park" -> Park(long("delay_ms"))
                "Retry" -> Retry(long("delay_ms"), bool("stop_pass"))
                else -> Drop(bool("stop_pass"))
            }
        }
    }
}
