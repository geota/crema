package coffee.crema.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * One completion notice per shot across every cloud destination — the Android
 * twin of the web's `$lib/history/upload-toast`.
 *
 * Each destination's push (Visualizer, Decent) used to raise its own snackbar,
 * so a shot going to both produced two a second apart. Now the caller opens a
 * batch for the shot, fires each destination (collecting the ones that
 * actually started an upload), and seals the batch with exactly those. Each
 * push reports its outcome, and the batch collapses into one line once every
 * sealed destination has answered: "Uploaded to Visualizer + Decent", or
 * "Uploaded to Visualizer · Decent failed: …". Reports that land between open
 * and seal are held, so a push that fails synchronously still counts. A
 * report with no batch notifies on its own. Skips (too short, no serial) are
 * silent unless they are all there is. A destination that never answers
 * cannot hold the notice forever: [timeoutMs] after sealing, the batch
 * summarises what it has (the web uses the same 90 s).
 */

enum class UploadOutcomeKind { Uploaded, Queued, Failed, Skipped }

data class UploadOutcome(val kind: UploadOutcomeKind, val message: String? = null)

/** The one-line summary, or null when there is nothing worth saying. */
fun summarizeUploadOutcomes(results: Map<UploadTargetId, UploadOutcome>): Pair<Boolean, String>? {
    val uploaded = results.filterValues { it.kind == UploadOutcomeKind.Uploaded }.keys.map { it.displayName }
    val queued = results.filterValues { it.kind == UploadOutcomeKind.Queued }.keys.map { it.displayName }
    val failed = results.filterValues { it.kind == UploadOutcomeKind.Failed }
    val parts = buildList {
        if (uploaded.isNotEmpty()) add("Uploaded to ${uploaded.joinToString(" + ")}")
        if (queued.isNotEmpty()) add("${queued.joinToString(" + ")} queued to retry")
        failed.forEach { (d, r) -> add("${d.displayName} failed" + (r.message?.let { ": $it" } ?: "")) }
    }
    if (parts.isEmpty()) return null
    return (failed.isEmpty()) to parts.joinToString(" · ")
}

class UploadOutcomeCollector(
    /** Runs the batch timeout; null = no timeout (tests of the pure folding). */
    private val scope: CoroutineScope?,
    private val timeoutMs: Long = BATCH_TIMEOUT_MS,
    private val notify: (ok: Boolean, message: String) -> Unit,
) {
    companion object {
        const val BATCH_TIMEOUT_MS = 90_000L
    }

    private class Batch {
        /** Null while open (the destinations are still being fired). */
        var expected: MutableSet<UploadTargetId>? = null
        val results: MutableMap<UploadTargetId, UploadOutcome> = linkedMapOf()
        var timer: Job? = null
    }

    private val batches = HashMap<String, Batch>()

    /** Start a batch for [shotId]; reports are held until [seal]. */
    @Synchronized
    fun open(shotId: String) {
        batches.getOrPut(shotId) { Batch() }
    }

    /**
     * Name the destinations that actually started an upload. Completes at once
     * when they have all answered already; drops the batch when none fired.
     */
    fun seal(shotId: String, fired: Collection<UploadTargetId>) {
        val done: Map<UploadTargetId, UploadOutcome>?
        synchronized(this) {
            val b = batches[shotId] ?: return
            val expected = (b.expected ?: mutableSetOf()).apply { addAll(fired) }
            b.expected = expected
            if (expected.isEmpty()) {
                batches.remove(shotId)
                return
            }
            done = if (b.results.keys.containsAll(expected)) finishLocked(shotId) else null
            if (done == null && b.timer == null && scope != null) {
                b.timer = scope.launch {
                    delay(timeoutMs)
                    val partial = synchronized(this@UploadOutcomeCollector) { finishLocked(shotId) }
                    partial?.let(::emit)
                }
            }
        }
        done?.let(::emit)
    }

    /** Open + seal in one step, for callers that know the destinations up front. */
    fun register(shotId: String, destinations: Collection<UploadTargetId>) {
        if (destinations.isEmpty()) return
        open(shotId)
        seal(shotId, destinations)
    }

    /** A destination's answer; notifies once the batch is complete. */
    fun report(shotId: String, destination: UploadTargetId, outcome: UploadOutcome) {
        val done: Map<UploadTargetId, UploadOutcome>?
        synchronized(this) {
            val b = batches[shotId]
            if (b == null) {
                done = mapOf(destination to outcome)
            } else {
                b.results[destination] = outcome
                val expected = b.expected
                done = if (expected != null && b.results.keys.containsAll(expected)) finishLocked(shotId) else null
            }
        }
        done?.let(::emit)
    }

    private fun finishLocked(shotId: String): Map<UploadTargetId, UploadOutcome>? {
        val b = batches.remove(shotId) ?: return null
        b.timer?.cancel()
        return b.results
    }

    private fun emit(results: Map<UploadTargetId, UploadOutcome>) {
        summarizeUploadOutcomes(results)?.let { (ok, msg) -> notify(ok, msg) }
    }
}

/** Row pip across every destination that counts for the shot (History lists). */
enum class UploadPip { Uploaded, Partial, Local }

fun uploadPipFor(targets: List<UploadTarget>): UploadPip {
    val counted = targets.filter { it.counted }
    if (counted.isEmpty()) return UploadPip.Local
    return if (counted.all { it.uploaded }) UploadPip.Uploaded else UploadPip.Partial
}
