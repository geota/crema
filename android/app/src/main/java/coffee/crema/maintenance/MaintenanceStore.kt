package coffee.crema.maintenance

import android.content.Context
import coffee.crema.core.MaintenanceState
import coffee.crema.persist.eraseStore
import coffee.crema.persist.loadStore
import coffee.crema.persist.saveStore
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
 * [coffee.crema.history.HistoryStore]. The core counts the water
 * (`Event.Telemetry.counted_ml`) and derives the readout; the VM adds the
 * counts up ([coffee.crema.ui.MainViewModel]) — this layer just persists.
 */

/**
 * The default maintenance state for a fresh install — the core's
 * (`MaintenanceState::default_at`, shared with web): no water counted, the
 * three `*AtMs` stamps at [nowMs] so the first readouts read "0 since", a
 * 50 L filter, a 120 L descale interval and a 48 h clean cycle.
 */
fun defaultMaintenanceState(nowMs: Long): MaintenanceState =
    defaultsJson.decodeFromString(MaintenanceState.serializer(), coffee.crema.core.defaultMaintenanceState(nowMs))

private val defaultsJson = Json { ignoreUnknownKeys = true }

/** File-backed JSON persistence for [MaintenanceState] (`filesDir/maintenance.json`). */
class MaintenanceStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    /** Load the persisted state, or [defaultMaintenanceState] if absent. An
     *  unreadable file is kept aside and reported (see [loadStore]). */
    suspend fun load(): MaintenanceState = withContext(Dispatchers.IO) {
        loadStore(file, "maintenance log") { json.decodeFromString(MaintenanceState.serializer(), it) }
            .valueOrNull() ?: defaultMaintenanceState(System.currentTimeMillis())
    }

    /** Persist the maintenance state atomically (best-effort; a failure is logged). */
    suspend fun save(state: MaintenanceState) {
        withContext(Dispatchers.IO) {
            saveStore(file, json.encodeToString(MaintenanceState.serializer(), state))
        }
    }

    /** Delete the store file and every copy of it (Erase all data; final review 05 N-1). */
    suspend fun erase() {
        withContext(Dispatchers.IO) { eraseStore(file) }
    }

    private companion object {
        const val FILE_NAME = "maintenance.json"
    }
}
