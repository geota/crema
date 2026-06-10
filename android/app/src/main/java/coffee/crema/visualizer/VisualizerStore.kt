package coffee.crema.visualizer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    // ── Sync preferences (web sync-config + settings defaults) ──────────────
    /** Master auto-sync gate — new shots upload as they complete. Web default: true. */
    val autoSync: Boolean = true,
    /** Upload privacy: `"public" | "unlisted" | "private"`. Web default: unlisted. */
    val privacy: String = "unlisted",
    /** Include the profile block in uploads. Web default: true. */
    val includeProfile: Boolean = true,
    /** Include tasting notes in uploads. Web default: false. */
    val includeNotes: Boolean = false,
    /** Unix ms of the last successful shot push, or null. */
    val lastShotSyncAt: Long? = null,
)

/** File-backed JSON persistence for [VisualizerState] (`filesDir/visualizer.json`). */
class VisualizerStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    suspend fun load(): VisualizerState = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.exists() }?.readText()
                ?.let { json.decodeFromString(VisualizerState.serializer(), it) }
        }.getOrNull() ?: VisualizerState()
    }

    suspend fun save(state: VisualizerState) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(VisualizerState.serializer(), state)) }
        }
    }

    private companion object {
        const val FILE_NAME = "visualizer.json"
    }
}
