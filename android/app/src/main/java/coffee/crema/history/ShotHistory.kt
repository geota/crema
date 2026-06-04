package coffee.crema.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/*
 * Shot history — the log of completed shots.
 *
 * v1 stores a per-shot SUMMARY only (no telemetry), so the persisted file stays
 * small. The detail view's static chart (reusing ShotChart) comes next and will
 * add a downsampled telemetry slice to [StoredShot] — additive, so older records
 * deserialise cleanly. Same file-JSON persistence pattern as the bean library.
 */

/** One completed shot — a row in the History list. */
@Serializable
data class StoredShot(
    val id: String,
    /** Unix epoch ms when the shot completed (drives "N min ago"). */
    val completedAtMs: Long,
    /** Total shot duration, ms. */
    val durationMs: Long,
    /** Final scale weight (yield), g, or null when no scale was paired. */
    val yieldG: Float? = null,
    /** Dose at capture (from the active profile), g — for the ratio. */
    val doseG: Float? = null,
    /** Peak group pressure across the shot, bar, or null. */
    val peakPressure: Float? = null,
    /** Peak group-head temperature across the shot, °C, or null. */
    val peakTemp: Float? = null,
    /** Active profile name at capture, or null. */
    val profileName: String? = null,
    /** Active bean ("roaster · name") at capture, or null. */
    val beanName: String? = null,
)

/** File-backed JSON persistence for the shot log (`filesDir/shots.json`). */
class HistoryStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    suspend fun load(): List<StoredShot> = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.exists() }?.readText()
                ?.let { json.decodeFromString(ListSerializer(StoredShot.serializer()), it) }
        }.getOrNull() ?: emptyList()
    }

    suspend fun save(shots: List<StoredShot>) {
        withContext(Dispatchers.IO) {
            runCatching {
                file.writeText(json.encodeToString(ListSerializer(StoredShot.serializer()), shots))
            }
        }
    }

    companion object {
        /** Keep the most recent N shots (matches the web cap). */
        const val MAX_SHOTS = 300
        private const val FILE_NAME = "shots.json"
    }
}
