package coffee.crema.visualizer

import coffee.crema.core.PaceBucket
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marks a coroutine as part of a pull run: its requests wait for the pacer's
 * slot however long it takes. Any other request fails as rate limited, unsent,
 * rather than wait past [Pacer.INLINE_WAIT_MAX_MS] (a cooldown after a 429).
 */
val PatientRun: CoroutineContext = PatientRunElement()

/** The key of [PatientRun]. */
private object PatientRunKey : CoroutineContext.Key<PatientRunElement>

private class PatientRunElement : AbstractCoroutineContextElement(PatientRunKey)

/**
 * The request pacer every Visualizer call goes through (core `pace`): 30
 * requests of burst, then one every 4 s, which stays inside Visualizer's 50 a
 * minute and 200 in ten for one device. A 429 sets a cooldown that holds every
 * call, a relaunch included. The web keeps the same bucket
 * (`#lib/visualizer/pace.ts`).
 *
 * The bucket is stored ([store]) every tenth request, on a 429 and when a run
 * stops ([flush]), so a relaunch doesn't start with a full burst.
 */
class Pacer(
    private val json: Json,
    /** Core `pull_run_call` (injected so a JVM test can stand in for it). */
    private val rules: (op: String, payload: String) -> String,
    private val now: () -> Long,
    /** How a wait is slept (a JVM test records it instead). */
    private val sleep: suspend (Long) -> Unit,
    /** Persist the bucket. */
    private val store: suspend (PaceBucket) -> Unit,
) {
    @Volatile private var bucket: PaceBucket = PaceBucket(tatMs = 0, lastNowMs = 0)
    private var sinceStore = 0

    /** Start from a stored bucket (own state loaded). */
    fun restore(stored: PaceBucket?) {
        if (stored != null) bucket = stored
    }

    /**
     * Take the next request's slot and sit its wait out. A call outside a run
     * ([PatientRun]) whose wait is past [INLINE_WAIT_MAX_MS] throws a 429
     * instead, unsent, and takes no slot.
     */
    suspend fun take() {
        val patient = currentCoroutineContext()[PatientRunKey] != null
        val wait: Long
        synchronized(this) {
            val answer = json.parseToJsonElement(
                rules("pace", buildJsonObject { put("bucket", bucketJson()); put("nowMs", now()) }.toString()),
            ).jsonObject
            wait = answer["waitMs"]!!.jsonPrimitive.long
            if (!patient && wait > INLINE_WAIT_MAX_MS) {
                throw VisualizerError.Http(429, "Visualizer is limiting requests", PACED_BODY)
            }
            bucket = json.decodeFromJsonElement(PaceBucket.serializer(), answer["bucket"]!!)
            sinceStore += 1
        }
        if (sinceStore >= STORE_EVERY) flush()
        if (wait > 0) sleep(wait)
    }

    /** A 429 arrived: nothing goes before `now + waitMs`. Stored at once. */
    suspend fun noteRateLimited(waitMs: Long) {
        synchronized(this) {
            bucket = json.decodeFromString(
                PaceBucket.serializer(),
                rules(
                    "noteRateLimit",
                    buildJsonObject { put("bucket", bucketJson()); put("nowMs", now()); put("waitMs", waitMs) }.toString(),
                ),
            )
        }
        flush()
    }

    /** Whether a 429's cooldown is still running: drains wait it out. */
    fun coolingDown(): Boolean = (bucket.cooldownUntilMs ?: 0L) > now()

    /** Store the bucket now. */
    suspend fun flush() {
        sinceStore = 0
        store(bucket)
    }

    private fun bucketJson() = json.encodeToJsonElement(PaceBucket.serializer(), bucket)

    companion object {
        /** Requests between stores of the bucket. */
        const val STORE_EVERY = 10

        /** The longest a call outside a run waits for its slot. */
        const val INLINE_WAIT_MAX_MS = 10_000L

        /** The body of a rate-limited failure the pacer made, with nothing sent. */
        const val PACED_BODY = "crema:paced"
    }
}

/** The Sync row's line while a pull sits out Visualizer's rate limit until [untilMs]. */
fun pullWaitingLine(untilMs: Long): String {
    val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(untilMs))
    return "Visualizer asked Crema to slow down. The pull goes on at $at."
}
