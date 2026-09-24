package coffee.crema.visualizer

import android.content.Context
import android.util.Log
import coffee.crema.security.DeviceSecretBox
import coffee.crema.security.SecretBox
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
    /** Where it went (serialised `"visualizer"` / `"decent"`; entries predating the field are Visualizer's). */
    val destination: coffee.crema.ui.UploadTargetId = coffee.crema.ui.UploadTargetId.Visualizer,
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

/** The at-rest form: token + PKCE verifier sealed ([SecretBox]). Idempotent. */
internal fun VisualizerState.sealedWith(box: SecretBox): VisualizerState {
    fun seal(v: String) = if (SecretBox.isWrapped(v)) v else box.wrap(v)
    return copy(
        tokens = tokens?.let { t -> t.copy(accessToken = seal(t.accessToken), refreshToken = t.refreshToken?.let(::seal)) },
        pendingVerifier = pendingVerifier?.let(::seal),
    )
}

/**
 * The in-memory form, plus whether to re-save (a legacy plaintext secret, or
 * one the key can no longer open — then the session is dropped: signed out).
 */
internal fun VisualizerState.openedWith(box: SecretBox): Pair<VisualizerState, Boolean> {
    var resave = false
    var lost = false
    fun open(v: String?): String? = when (val o = box.openOrNull(v)) {
        null -> null
        is SecretBox.Opened.Value -> { if (o.migrate) resave = true; o.value }
        SecretBox.Opened.Lost -> { lost = true; resave = true; null }
    }
    val access = tokens?.let { open(it.accessToken) }
    val refresh = tokens?.refreshToken?.let { open(it) }
    val verifier = open(pendingVerifier)
    val opened = copy(
        tokens = if (lost || access == null) null else tokens.copy(accessToken = access, refreshToken = refresh),
        account = if (lost) null else account,
        pendingVerifier = if (lost) null else verifier,
        pendingState = if (lost) null else pendingState,
    )
    return opened to resave
}

/** File-backed JSON persistence for [VisualizerState] (`filesDir/visualizer.json`). */
class VisualizerStore(
    private val context: Context,
    private val json: Json,
    private val box: SecretBox = DeviceSecretBox.instance,
) {
    private val file get() = File(context.filesDir, FILE_NAME)

    suspend fun load(): VisualizerState = withContext(Dispatchers.IO) {
        val raw = runCatching {
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
        }.onFailure { Log.w(TAG, "visualizer.json unreadable; starting signed out", it) }
            .getOrNull() ?: return@withContext VisualizerState()
        val (state, resave) = raw.openedWith(box)
        if (resave) write(state)
        state
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

    /** Write [state]; false (logged) when it could not be stored. */
    suspend fun save(state: VisualizerState): Boolean = withContext(Dispatchers.IO) { write(state) }

    private fun write(state: VisualizerState): Boolean = try {
        file.writeText(json.encodeToString(VisualizerState.serializer(), state.sealedWith(box)))
        true
    } catch (e: Exception) {
        // Includes a keystore failure while sealing: never fall back to plaintext.
        Log.e(TAG, "couldn't save visualizer.json", e)
        false
    }

    private companion object {
        const val FILE_NAME = "visualizer.json"
        const val TAG = "VisualizerStore"
        val LEGACY_PREF_KEYS = setOf(
            "autoSync", "privacy", "includeProfile", "includeNotes", "shotsDirection",
        )
    }
}
