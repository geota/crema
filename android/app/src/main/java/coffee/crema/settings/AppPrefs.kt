package coffee.crema.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * App preferences — the user's display/behaviour settings, persisted via the
 * same file-JSON pattern as the bean library and shot history.
 *
 * v1 holds just the theme mode; units (weight/temp/pressure) and other prefs are
 * additive later (older records deserialise cleanly via defaults).
 */
@Serializable
data class AppPrefs(
    /** `"system" | "light" | "dark"` — drives CremaTheme. Defaults to dark
     *  (the machine app is dark-skinned). */
    val themeMode: String = "dark",
    /** Max shot duration cap, seconds. Pushed to the core on load + shown as a
     *  Time stop-condition on Brew. */
    val maxShotDurationS: Float = 45f,
    // ── Shot behaviour (re-applied to the core on launch) ────────────────────
    val autoTare: Boolean = false,
    val stopOnWeight: Boolean = false,
    val steamEco: Boolean = false,
    /** Pre-shot group flush / post-steam purge requests. Persisted preference;
     *  the shot sequence does not consume them yet (Settings rows carry the
     *  "not implemented" pill until it does). */
    val preFlush: Boolean = false,
    val steamPurge: Boolean = false,
    // ── Display ──────────────────────────────────────────────────────────────
    /** Live-chart channel keys (Quick Controls chart strip). */
    val chartChannels: Set<String> = setOf("pressure", "flow", "weight"),
    /** Hold FLAG_KEEP_SCREEN_ON while a shot is pulling. */
    val keepScreenOnBrew: Boolean = false,
    /** Show the inline debug / event-log panel in Settings → Advanced
     *  (web `showDebugPanel`). */
    val showDebugPanel: Boolean = false,
    // ── Brew defaults (seed new profiles + the Quick-Controls fallbacks) ─────
    val defaultDoseG: Float = 18f,
    val defaultRatio: Float = 2f,
    val defaultBrewTempC: Float = 93f,
    val defaultPreinfuseS: Float = 8f,
    /** Equipment-level grinder model (web `grinderModel`) — free text shown on
     *  Brew + sent with Visualizer uploads/patches. Blank = unset. */
    val grinderModel: String = "",
    /** Keep the DE1 awake while Crema is open (web `suppressDe1Sleep`) — a
     *  60 s UserPresent (MMR 0x803858) heartbeat resets the sleep timer. */
    val suppressDe1Sleep: Boolean = true,
    // ── Quick-Controls steam / hot-water / flush (issue 14) ──────────────────
    /** Persisted QC overrides for the machine's steam / hot-water / flush params.
     *  These stick (no throwaway state) and take priority; on change they're
     *  applied to the machine via read-modify-write (the machine's reported
     *  timeouts / espresso volume / group temp are preserved). Defaults mirror
     *  the former QuickControls seed values. */
    val qcSteamTimeS: Float = 12f,
    val qcSteamFlowMlS: Float = 1.2f,
    val qcSteamTempC: Float = 148f,
    val qcHotWaterTempC: Float = 80f,
    val qcHotWaterVolumeMl: Float = 150f,
    val qcFlushTimeS: Float = 4f,
    val qcFlushTempC: Float = 95f,
    // ── Session restore ──────────────────────────────────────────────────────
    /** The last active profile id, restored on launch (web `crema.profiles.activeId`). */
    val activeProfileId: String? = null,
)

/** File-backed JSON persistence for [AppPrefs] (`filesDir/prefs.json`). */
class SettingsStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    suspend fun load(): AppPrefs = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.exists() }?.readText()
                ?.let { json.decodeFromString(AppPrefs.serializer(), it) }
        }.getOrNull() ?: AppPrefs()
    }

    suspend fun save(prefs: AppPrefs) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(AppPrefs.serializer(), prefs)) }
        }
    }

    private companion object {
        const val FILE_NAME = "prefs.json"
    }
}
