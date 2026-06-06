package coffee.crema.maintenance

import android.content.Context
import coffee.crema.core.MaintenanceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/*
 * Maintenance store — the water-accumulation & maintenance state.
 *
 * The DE1 has NO cumulative water-volume counter — but the legacy de1app
 * derives one by integrating group flow over time (`de1_de1.tcl`:
 * `volume += GroupFlow × Δt`, with sanity clamps). Crema's web shell does the
 * same (`web/src/lib/maintenance/store.svelte.ts`): every telemetry sample's
 * flow + wall-clock Δ is integrated into a running litre total, persisted, and
 * the filter / descale / clean readouts derive from that one counter plus the
 * user-set intervals via the pure core FFI (`maintenanceReadout`).
 *
 * This is the Android counterpart: file-JSON persistence of the @Serializable
 * core [MaintenanceState] at `filesDir/maintenance.json`, the same dependency-
 * free pattern as [coffee.crema.beans.LibraryStore] /
 * [coffee.crema.history.HistoryStore]. The accumulate math + readout derivation
 * live in the VM ([coffee.crema.ui.MainViewModel]) — this layer just persists.
 */

/**
 * A single telemetry sample's accumulated water past which the value is treated
 * as a glitch and dropped — mirrors the web store's `MAX_SAMPLE_ML` (and the
 * legacy app's "excessive water volume dispensed" clamp, 1000 ml).
 */
const val MAINTENANCE_MAX_SAMPLE_ML: Double = 1000.0

/**
 * The maximum wall-clock Δ (seconds) one telemetry sample may integrate over.
 * Telemetry is ~25–40 Hz, so an in-session gap is ~25–40 ms; a larger gap means
 * a between-session pause (or a fresh session before the dt-tracker reset
 * landed) and is capped so it can't dump a huge slug of phantom water.
 */
const val MAINTENANCE_MAX_SAMPLE_DT_S: Double = 0.5

/**
 * The default maintenance state for a fresh install — matches the web store's
 * `defaultState()`. Baselines and the running total start at zero and the three
 * `*AtMs` timestamps at [nowMs], so the very first readouts are sane: a full
 * filter, no litres since descale, "0 since" for clean.
 *
 * Defaults: filterCapacityLitres=50, descaleIntervalLitres=120,
 * cleanIntervalHours=48 (verbatim from the web store).
 */
fun defaultMaintenanceState(nowMs: Long): MaintenanceState = MaintenanceState(
    totalLitres = 0.0,
    filterBaselineLitres = 0.0,
    descaleBaselineLitres = 0.0,
    cleanAtMs = nowMs,
    filterAtMs = nowMs,
    descaleAtMs = nowMs,
    filterCapacityLitres = 50.0,
    descaleIntervalLitres = 120.0,
    cleanIntervalHours = 48.0,
)

/** File-backed JSON persistence for [MaintenanceState] (`filesDir/maintenance.json`). */
class MaintenanceStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    /** Load the persisted state, or [defaultMaintenanceState] if absent / unreadable / stale. */
    suspend fun load(): MaintenanceState = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.exists() }?.readText()
                ?.let { json.decodeFromString(MaintenanceState.serializer(), it) }
        }.getOrNull() ?: defaultMaintenanceState(System.currentTimeMillis())
    }

    /** Persist the maintenance state (best-effort; failures are swallowed). */
    suspend fun save(state: MaintenanceState) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(MaintenanceState.serializer(), state)) }
        }
    }

    private companion object {
        const val FILE_NAME = "maintenance.json"
    }
}
