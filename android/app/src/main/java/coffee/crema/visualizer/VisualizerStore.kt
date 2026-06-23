package coffee.crema.visualizer

import android.content.Context
import coffee.crema.core.VisualizerSyncPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/*
 * Visualizer state — tokens, the in-flight PKCE handshake, the cached account,
 * and the sync preferences. One file (`filesDir/visualizer.json`), mirroring
 * the web shell's `crema.visualizer.{tokens,sync}.v1` localStorage keys, and
 * keeping tokens out of `prefs.json` (which `eraseAll` / reset-preferences
 * rewrite with defaults — signing out should be an explicit act).
 *
 * Same file-JSON store pattern as the rest of the shell (suspend load/save on
 * Dispatchers.IO, defaults on parse failure).
 */

/** Crema-side projection of the Visualizer `/me` response (web `VisualizerAccount`). */
@Serializable
data class VisualizerAccount(
    val id: String,
    val name: String,
    val public: Boolean = false,
    val avatarUrl: String = "",
)

/** One sync activity-log line (web `SyncLogEntry`) — capped, newest first. */
@Serializable
data class SyncLogEntry(
    /** `"push" | "pull" | "skip" | "delete"`. */
    val direction: String,
    /** `"shot" | "bean" | "roaster"`. */
    val entity: String,
    val id: String,
    val name: String,
    /** Unix ms. */
    val at: Long,
    val error: String? = null,
)

/** Everything Visualizer-related the shell persists. */
@Serializable
data class VisualizerState(
    /** The current token set, or null when signed out. */
    val tokens: TokenSet? = null,
    /** PKCE verifier for the in-flight browser handshake (single-use). */
    val pendingVerifier: String? = null,
    /** CSRF state for the in-flight browser handshake (single-use). */
    val pendingState: String? = null,
    /** Cached `/me` projection; refreshed on sign-in and on Settings open. */
    val account: VisualizerAccount? = null,
    /**
     * Unified sync PREFERENCES — the shared core [VisualizerSyncPrefs] shape both
     * shells serialise identically, so a backup's `visualizerPrefs` line moves
     * between web and Android with no per-shell tag. Pre-unification builds stored
     * these as flat fields (`autoSync`/`privacy`/…); [VisualizerStore.load]
     * migrates such a file forward.
     */
    val prefs: VisualizerSyncPrefs = DEFAULT_VISUALIZER_SYNC_PREFS,
    /** Unix ms of the last successful shot push, or null. */
    val lastShotSyncAt: Long? = null,
    /**
     * Incremental pull cursor (unix ms); null pulls everything. Advanced ONLY
     * by a successful pull — never by a push (the web learned this the hard
     * way: a shared cursor starved the pull).
     */
    val shotPullCursor: Long? = null,
    /** Recent sync activity — capped at 20 entries, newest first. */
    val log: List<SyncLogEntry> = emptyList(),
)

/** The canonical default sync prefs — mirrors the core `VisualizerSyncPrefs::default`. */
val DEFAULT_VISUALIZER_SYNC_PREFS: VisualizerSyncPrefs = VisualizerSyncPrefs(
    autoUpload = true,
    autoSync = true,
    privacy = "unlisted",
    includeProfile = true,
    includeNotes = false,
    shotsDirection = "backup",
    beansDirection = "two-way",
    roastersDirection = "two-way",
)

/** File-backed JSON persistence for [VisualizerState] (`filesDir/visualizer.json`). */
class VisualizerStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    suspend fun load(): VisualizerState = withContext(Dispatchers.IO) {
        runCatching {
            val text = file.takeIf { it.exists() }?.readText() ?: return@runCatching null
            val state = json.decodeFromString(VisualizerState.serializer(), text)
            // Migrate a pre-unification file (flat `autoSync`/`privacy`/… fields and
            // no `prefs` object) forward into the shared [VisualizerSyncPrefs] shape.
            val obj = json.parseToJsonElement(text).jsonObject
            if (obj["prefs"] == null && obj.keys.any { it in LEGACY_PREF_KEYS }) {
                state.copy(prefs = migrateLegacyPrefs(obj))
            } else {
                state
            }
        }.getOrNull() ?: VisualizerState()
    }

    /** Build [VisualizerSyncPrefs] from a pre-unification file's flat fields. The
     *  legacy `autoSync` WAS the shot auto-upload gate (Android's single auto flag). */
    private fun migrateLegacyPrefs(obj: JsonObject): VisualizerSyncPrefs {
        fun bool(k: String, d: Boolean) = (obj[k] as? JsonPrimitive)?.booleanOrNull ?: d
        fun str(k: String, d: String) = (obj[k] as? JsonPrimitive)?.contentOrNull ?: d
        return VisualizerSyncPrefs(
            autoUpload = bool("autoSync", true),
            autoSync = true,
            privacy = str("privacy", "unlisted"),
            includeProfile = bool("includeProfile", true),
            includeNotes = bool("includeNotes", false),
            shotsDirection = str("shotsDirection", "backup"),
            beansDirection = "two-way",
            roastersDirection = "two-way",
        )
    }

    suspend fun save(state: VisualizerState) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(VisualizerState.serializer(), state)) }
        }
    }

    private companion object {
        const val FILE_NAME = "visualizer.json"
        val LEGACY_PREF_KEYS = setOf(
            "autoSync", "privacy", "includeProfile", "includeNotes", "shotsDirection",
        )
    }
}
