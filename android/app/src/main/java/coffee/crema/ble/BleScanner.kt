package coffee.crema.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The app's single shared BLE scanner, on top of [BleTransport].
 *
 * The DE1 and the Bookoo scale are both discovered by an unfiltered scan plus
 * an advertised-name match — neither device reliably advertises its 128-bit
 * GATT service UUID, so a service-UUID scan filter never matches (this mirrors
 * the legacy de1app discovery rule in `bluetooth.tcl`). [BleTransport.scan]
 * already runs the unfiltered scan with a name predicate; this class owns the
 * "wants" bookkeeping on top of it.
 *
 * A "want" is registered with [scanFor] and identified by a [String] label.
 * Each want collects its own [BleTransport.scan] flow; on the first match the
 * want's `onFound` fires once and the want is dropped (its scan job cancelled);
 * [cancel] drops a want without a match (e.g. on disconnect).
 *
 * The underlying [BleTransport] runs at most one physical scan even with
 * several wants outstanding — each want's `scan()` flow is a thin filter over
 * the one shared scan inside the transport.
 *
 * Threading: scan results arrive on the [scope] (a background dispatcher), the
 * same off-main-thread contract the managers' callbacks had. `onFound` and
 * `onStatus` dispatch happen there; UI consumers hop to the main thread
 * themselves. The wants map is synchronised.
 */
class BleScanner(
    private val transport: BleTransport,
    /** Called with human-readable status transitions for the UI log. */
    private val onStatus: (String) -> Unit,
) {
    /** A single registered discovery request and the job collecting its scan. */
    private class Want(
        val label: String,
        var job: Job? = null,
    )

    /** The scanner's own coroutine scope; cancelled per-want on match/cancel. */
    private val scope = CoroutineScope(SupervisorJob())

    /** Outstanding wants, keyed by label. Guarded by `this`. */
    private val wants = LinkedHashMap<String, Want>()

    /**
     * Register a discovery request. The unfiltered scan runs (inside
     * [BleTransport]) while at least one want is outstanding. When a scanned
     * advertisement's name satisfies [matches], [onFound] is invoked once with
     * the matched device and its advertised name and the want is dropped.
     * Re-registering the same [label] replaces the previous request.
     */
    fun scanFor(
        label: String,
        matches: (name: String) -> Boolean,
        onFound: (device: BleTransport.DeviceHandle, name: String) -> Unit,
    ) {
        val want = Want(label)
        synchronized(this) {
            wants.remove(label)?.job?.cancel()
            wants[label] = want
        }
        // Collect this want's slice of the shared scan. The transport emits
        // only name-matching advertisements; act on the first one.
        want.job = transport.scan(matches)
            .onEach { match ->
                val fire: Boolean
                synchronized(this) {
                    // Drop the want before firing, so a re-entrant
                    // scanFor/cancel from onFound is safe and the scan job
                    // does not deliver a second match.
                    fire = wants.remove(label) === want
                }
                if (fire) {
                    want.job?.cancel()
                    onFound(match.device, match.name)
                }
            }
            .catch { t ->
                synchronized(this) { wants.remove(label) }
                onStatus("Scan failed: ${t.message}")
                Log.w(TAG, "Scan for '$label' failed", t)
            }
            .launchIn(scope)
    }

    /**
     * Drop the want registered under [label] (e.g. on disconnect), cancelling
     * its scan collection. A no-op if the label is not registered.
     */
    fun cancel(label: String) {
        synchronized(this) {
            wants.remove(label)?.job?.cancel()
        }
    }

    private companion object {
        const val TAG = "BleScanner"
    }
}
