package coffee.crema.settings

import android.content.Context
import coffee.crema.core.SawModelLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File persistence for the core's learned SAW drip model
 * (`de1_domain::saw_learning`, an opaque JSON blob) — same file-JSON
 * pattern as the other stores. The core owns the shape; this store never
 * parses it. Seeded into the core at startup, saved after every shot.
 *
 * A stored blob that no longer parses is QUARANTINED rather than lost (port
 * of Decenza 95146a0c, `loadSawMap`): the core starts a fresh model and hands
 * back the raw text, and [seed] copies it verbatim to [QUARANTINE_FILE_NAME]
 * before deleting the store, so the next save cannot overwrite the only
 * copy. Newest capture wins, as in Decenza; the file's own mtime records
 * when. No UI — the bytes are kept for manual recovery and a warning is
 * logged.
 */
class SawModelStore(private val dir: File) {
    constructor(context: Context) : this(context.filesDir)

    private val file get() = File(dir, FILE_NAME)
    private val quarantine get() = File(dir, QUARANTINE_FILE_NAME)

    suspend fun load(): String? = withContext(Dispatchers.IO) {
        runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
    }

    suspend fun save(json: String) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json) }
        }
    }

    /**
     * Load the stored blob into the core via [loadIntoCore] (the bridge's
     * `loadSawModelJson`, which returns a JSON `SawModelLoad`) and quarantine
     * it if the core reports it corrupt. [log] receives the warning.
     */
    suspend fun seed(loadIntoCore: (String?) -> String, log: (String) -> Unit): SawModelLoad {
        val outcome = decode(loadIntoCore(load()))
        if (outcome is SawModelLoad.Corrupt) {
            val raw = outcome.content.raw
            val error = outcome.content.error
            val kept = withContext(Dispatchers.IO) {
                runCatching {
                    quarantine.writeText(raw)
                    file.delete()
                }.isSuccess
            }
            log(
                if (kept) {
                    "SAW model corrupt ($error) — ${raw.length} chars quarantined at " +
                        "$QUARANTINE_FILE_NAME, learning starts fresh"
                } else {
                    // Leave the original in place: it is still the only copy.
                    "SAW model corrupt ($error) — quarantine write failed"
                },
            )
        }
        return outcome
    }

    companion object {
        const val FILE_NAME = "sawModel.json"
        const val QUARANTINE_FILE_NAME = "sawModel.corrupt.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** Decode the bridge's `SawModelLoad`; anything unreadable is Absent. */
        fun decode(outcome: String): SawModelLoad =
            runCatching { json.decodeFromString<SawModelLoad>(outcome) }
                .getOrDefault(SawModelLoad.Absent)
    }
}
