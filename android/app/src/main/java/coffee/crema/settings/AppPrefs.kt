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
    /** Auto-tare the scale on shot start. Defaults ON to match the core's own
     *  default ([CremaCore] `auto_tare = true`) and the web shell (which
     *  defaults `autoTareOnShotStart` true). Android historically defaulted
     *  false, so a fresh install silently shipped auto-tare DISABLED — the scale
     *  never zeroed at shot start, unlike web. Existing users keep their saved
     *  value (this only changes the no-prefs / absent-key default). */
    val autoTare: Boolean = true,
    /** Stop the shot at the yield target (stop-on-weight). Defaults ON for the
     *  same web/core parity reason as [autoTare] (web defaults `stopOnWeight`
     *  true); a no-op when no scale or yield target is present. */
    val stopOnWeight: Boolean = true,
    val steamEco: Boolean = false,
    /** Pre-shot group flush / post-steam purge requests. Persisted preference;
     *  the shot sequence does not consume them yet (Settings rows carry the
     *  "not implemented" pill until it does). */
    val preFlush: Boolean = false,
    val steamPurge: Boolean = false,
    // ── Units (issue 44) ──────────────────────────────────────────────────────
    /** Weight unit for dose/yield/scale readouts — `"g" | "oz"`. */
    val weightUnit: String = "g",
    /** Temperature unit for every temp readout — `"C" | "F"`. */
    val tempUnit: String = "C",
    /** Pressure unit for the pressure channel — `"bar" | "psi"`. */
    val pressureUnit: String = "bar",
    /** Volume unit for water/dispensed readouts — `"ml" | "floz"`. */
    val volumeUnit: String = "ml",
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
    /** Persisted Quick-Controls grinder click (issue 15). Null = never dialed —
     *  the stepper shows a seed value and shots record no grind until the user
     *  sets it; once set it sticks and is recorded onto each shot. */
    val qcGrind: Float? = null,
    // ── Session restore ──────────────────────────────────────────────────────
    /** The last active profile id, restored on launch (web `crema.profiles.activeId`). */
    val activeProfileId: String? = null,
    // ── Connection (auto-connect is per-device = a remembered address) ───────
    /** Remembered DE1 Bluetooth address — the auto-connect target (reconnect on
     *  an unexpected drop + connect on launch). Non-null ⟺ the DE1's
     *  "Auto-connect" is ON; turning the toggle OFF clears it (forget). */
    val de1Address: String? = null,
    /** Remembered scale Bluetooth address (same per-device auto-connect rule). */
    val scaleAddress: String? = null,
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
