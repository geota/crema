package coffee.crema.ble

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * The stop-write preemption lane (issue #15).
 *
 * Nordic serialises every GATT operation under `object OperationMutex` — a
 * **process-global singleton**, not per-peripheral — so ANY in-flight
 * operation, on the DE1 *or the scale*, makes the one write that must not
 * wait (the shot stop) queue behind it: up to the 5 s write timeout behind a
 * single wedged op, and scale heartbeats/tares contend too. The mutex is
 * fair FIFO with no priority concept, so the queue cannot be jumped — but it
 * can be *emptied*: every non-critical write registers its coroutine here
 * for the duration of the transport call, and an urgent write first cancels
 * them all. Cancellation is safe by construction — each write already runs
 * under `withTimeout`, so its transport op is cancellable and Nordic's
 * `withLock` releases the mutex on cancellation — which frees the radio for
 * the stop within milliseconds instead of seconds.
 *
 * Urgent writes do NOT register, so a stop can never preempt another stop
 * (the enforcer's 1 s re-commands flow through the same lane).
 *
 * The cost of a preemption is one failed non-critical write (a keep-awake
 * poke, a scale LCD tweak) — logged by its own caller, retried by its own
 * cadence, and worth strictly less than a pour running past its target.
 */
object UrgentWriteGate {
    private const val TAG = "UrgentWriteGate"

    private val inFlight = ConcurrentHashMap<Job, String>()

    /** Register [job] (a non-critical write's coroutine) as preemptible while
     *  [block] runs. No-op registration when the context has no job. */
    suspend fun <T> preemptible(job: Job?, label: String, block: suspend () -> T): T {
        if (job == null) return block()
        inFlight[job] = label
        return try {
            block()
        } finally {
            inFlight.remove(job)
        }
    }

    /** Cancel every registered non-critical write so the global GATT mutex
     *  frees for the caller's urgent write. */
    fun preempt(reason: String) {
        if (inFlight.isEmpty()) return
        inFlight.forEach { (job, label) ->
            Log.i(TAG, "Preempting in-flight $label for $reason")
            job.cancel(CancellationException("preempted by $reason"))
        }
        inFlight.clear()
    }
}
