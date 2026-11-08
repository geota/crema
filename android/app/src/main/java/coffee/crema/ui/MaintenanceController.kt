package coffee.crema.ui

import coffee.crema.core.Event
import coffee.crema.core.MaintenanceReadout
import coffee.crema.core.MaintenanceState
import coffee.crema.core.maintenanceReadout
import coffee.crema.maintenance.MaintenanceStore
import coffee.crema.maintenance.defaultMaintenanceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Maintenance: the water accumulation and the filter / descale / clean
 * readouts (MainViewModel breakup step 2).
 *
 * The DE1 has no cumulative water counter; the core counts each telemetry
 * frame's water (`Telemetry.counted_ml`, espresso and hot water only) and this
 * adds it up into a running litre total (the de1app / web-shell approach),
 * deriving the readouts from it via the pure core FFI. The live total lives in
 * [totalLitres] (not the ui-state) so the ~25–40 Hz accumulate doesn't thrash
 * recomposition; it's flushed into the persisted [MaintenanceState] (and the
 * readout recomputed) only at load, on shot / water-session completion, and
 * after a mark action.
 *
 * Registers its core-event handlers on [lane] as it is built; they run after
 * the VM's own for the same event.
 */
internal class MaintenanceController(
    private val lane: CoreLane,
    private val json: Json,
    private val store: MaintenanceStore,
    private val scope: CoroutineScope,
    private val uiState: () -> MainUiState,
    private val updateUi: ((MainUiState) -> MainUiState) -> Unit,
    private val appendLog: (String) -> Unit,
) {
    /**
     * The live integrated water total, litres — seeded from the loaded
     * [MaintenanceState.totalLitres] in [load] and advanced on every
     * `Event.Telemetry`. Lane-owned: written only on the core lane (any other
     * thread goes through [CoreLane.post]).
     */
    private var totalLitres: Double = 0.0

    /** Test seam: the live total. */
    @androidx.annotation.VisibleForTesting
    internal val countedLitres: Double get() = totalLitres

    init {
        lane.on<Event.Telemetry> { event ->
            // Add the core's counted water to the running total (the DE1 has no
            // cumulative counter): `counted_ml` is set only for espresso and hot
            // water, gap-capped — the core's rule, shared with web (Android used
            // to count descale, clean, steam rinse and air purge too). In-memory
            // only — flushed on shot / water-session completion.
            event.content.counted_ml?.let { totalLitres += it / 1000.0 }
        }
        // A pour just finished: flush the integrated water into the persisted
        // maintenance state and recompute the filter / descale / clean readout.
        lane.on<Event.ShotCompleted> { flush() }
        // Flush the integrated water (same boundary as a completed shot).
        lane.on<Event.WaterSessionCompleted> { flush() }
    }

    /** Load the persisted maintenance state, seed the live total, compute the readout. */
    suspend fun load() {
        val state = store.load()
        lane.post { totalLitres = state.totalLitres }
        updateUi { it.copy(
            maintenance = state,
            maintenanceReadout = computeReadout(state),
        ) }
    }

    /** A backup's maintenance state, a restore's step 8: reseed the live water
     *  integrator and persist. Whether it landed. */
    suspend fun restoreNow(state: MaintenanceState): Boolean {
        lane.post { totalLitres = state.totalLitres }
        updateUi { it.copy(maintenance = state, maintenanceReadout = computeReadout(state)) }
        return store.save(state)
    }

    /**
     * Erase all data, the memory half: a fresh-install state (baselines and
     * total zeroed, the three *AtMs = now so the readouts read "0 since").
     * Returns it for [eraseStored].
     */
    fun erase(): MaintenanceState {
        val fresh = defaultMaintenanceState(System.currentTimeMillis())
        lane.post { totalLitres = 0.0 }
        updateUi { it.copy(
            maintenance = fresh,
            maintenanceReadout = computeReadout(fresh),
        ) }
        return fresh
    }

    /** Erase all data, the disk half: delete the store, then save [fresh]. */
    suspend fun eraseStored(fresh: MaintenanceState) {
        store.erase()
        store.save(fresh)
    }

    /**
     * Mark the water filter cleaned — rebaseline its litre counter to the current
     * total and stamp `filterAtMs` (the web store's `markFilterCleaned`). Persists
     * + recomputes the readout.
     */
    fun markFilterCleaned() = lane.post {
        val next = uiState().maintenance.copy(
            totalLitres = totalLitres,
            filterBaselineLitres = totalLitres,
            filterAtMs = System.currentTimeMillis(),
        )
        save(next)
    }

    /**
     * Mark a descale done — rebaseline the descale litre counter to the current
     * total and stamp `descaleAtMs` (the web store's `markDescaled`). Persists +
     * recomputes the readout.
     */
    fun markDescaled() = lane.post {
        val next = uiState().maintenance.copy(
            totalLitres = totalLitres,
            descaleBaselineLitres = totalLitres,
            descaleAtMs = System.currentTimeMillis(),
        )
        save(next)
    }

    /**
     * Mark a clean cycle done — reset its hour counter by stamping `cleanAtMs`
     * (the web store's `markCleaned`). Persists + recomputes the readout.
     */
    fun markCleaned() = lane.post {
        val next = uiState().maintenance.copy(
            totalLitres = totalLitres,
            cleanAtMs = System.currentTimeMillis(),
        )
        save(next)
    }

    // ── Maintenance intervals (web WaterSection "Maintenance intervals") ─────

    /** Set the water-filter capacity (litres). Persisted + readout recomputed. */
    fun setFilterCapacity(litres: Double) =
        save(uiState().maintenance.copy(filterCapacityLitres = litres.coerceIn(5.0, 500.0)))

    /** Set the descale interval (litres). Persisted + readout recomputed. */
    fun setDescaleInterval(litres: Double) =
        save(uiState().maintenance.copy(descaleIntervalLitres = litres.coerceIn(10.0, 1000.0)))

    /** Set the clean-cycle interval (hours). Persisted + readout recomputed. */
    fun setCleanInterval(hours: Double) =
        save(uiState().maintenance.copy(cleanIntervalHours = hours.coerceIn(1.0, 500.0)))

    // Per-reminder arming (additive Option field: null = enabled). A disabled
    // reminder is never "due" — the core readout forces its *_ok true, which
    // silences the Brew banner on every shell with no special-casing.
    fun setFilterReminderEnabled(on: Boolean) =
        save(uiState().maintenance.copy(filterEnabled = on))

    fun setDescaleReminderEnabled(on: Boolean) =
        save(uiState().maintenance.copy(descaleEnabled = on))

    fun setCleanReminderEnabled(on: Boolean) =
        save(uiState().maintenance.copy(cleanEnabled = on))

    /**
     * Derive the filter / descale / clean readout from [state] via the pure core
     * FFI (`maintenanceReadout` — a MaintenanceState JSON in, a MaintenanceReadout
     * JSON out; @Throws on a bad input). The same derivation the web shell uses,
     * so both produce byte-identical readouts. Failures fall back to null.
     */
    private fun computeReadout(state: MaintenanceState): MaintenanceReadout? = runCatching {
        val stateJson = json.encodeToString(MaintenanceState.serializer(), state)
        val resultJson = maintenanceReadout(stateJson, System.currentTimeMillis())
        json.decodeFromString(MaintenanceReadout.serializer(), resultJson)
    }.getOrElse {
        appendLog("Maintenance readout failed: ${it.message}")
        null
    }

    /**
     * Flush the live integrated [totalLitres] into the persisted
     * [MaintenanceState], save, and recompute the readout. Called on shot /
     * water-session completion (the natural "a pour just finished" boundaries) —
     * NOT per telemetry sample.
     */
    private fun flush() {
        val next = uiState().maintenance.copy(totalLitres = totalLitres)
        updateUi { it.copy(
            maintenance = next,
            maintenanceReadout = computeReadout(next),
        ) }
        scope.launch { store.save(next) }
    }

    /** Fold a mark action's new state into the ui-state, recompute, and persist. */
    private fun save(next: MaintenanceState) {
        updateUi { it.copy(
            maintenance = next,
            maintenanceReadout = computeReadout(next),
        ) }
        scope.launch { store.save(next) }
    }
}
