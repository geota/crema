package coffee.crema.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The wait a sync controller's own state (`visualizer.json`, `drive.json`,
 * `decent.json`) puts in front of everything that touches it. Until the load
 * finishes the controller holds defaults, and a write from there replaces the
 * file with them: tokens, the pull cursor and tombstones go (geota/crema#322).
 *
 * It waits rather than refuses, because a mutation runs on the loaded state.
 * A load that failed or never finishes doesn't proceed on defaults:
 * [await] answers false, the caller does nothing, and the user is told once.
 *
 * Not the floor: refusing a write that gets past this belongs to `SafeFile`.
 */
internal class OwnStateGate(
    /** The service, as a notice names it. */
    private val service: String,
    private val notify: (String) -> Unit,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private enum class Load { Waiting, Loaded, Failed }

    private val load = MutableStateFlow(Load.Waiting)
    private val told = AtomicBoolean(false)

    /** The load has ended, either way. */
    val settled: Boolean get() = load.value != Load.Waiting

    /** Let the waiters through: the load read the file. */
    fun opened() {
        load.value = Load.Loaded
    }

    /** The load ended without reading the file. No effect after [opened]. */
    fun failed() {
        load.compareAndSet(Load.Waiting, Load.Failed)
    }

    /**
     * Wait for the load, up to [timeoutMs]. True when the state may be used.
     * Spends nothing: a caller that gets false just stops.
     */
    suspend fun await(): Boolean {
        val result = waitForLoad()
        if (result == Load.Loaded) return true
        if (told.compareAndSet(false, true)) {
            notify(
                if (result == Load.Failed) {
                    "Couldn’t read $service’s saved sign-in. Restart Crema to try again."
                } else {
                    "$service’s saved sign-in hasn’t loaded yet, so that didn’t go through. Try again in a moment."
                },
            )
        }
        return false
    }

    /** The wait alone, with no notice, for a caller that goes ahead either way. */
    suspend fun settle() {
        waitForLoad()
    }

    private suspend fun waitForLoad(): Load? = withTimeoutOrNull(timeoutMs) { load.first { it != Load.Waiting } }

    companion object {
        /** A callback never hangs silently past this. */
        const val TIMEOUT_MS = 10_000L
    }
}
