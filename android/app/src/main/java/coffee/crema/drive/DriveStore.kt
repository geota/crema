package coffee.crema.drive

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * Google Drive auth state — the token + the in-flight PKCE handshake. One file
 * (`filesDir/drive.json`), kept OUT of `prefs.json` (like [VisualizerStore]) so a
 * reset-preferences / erase-all doesn't silently sign the user out of Drive. The
 * token is also EXCLUDED from the backup bundle — a restore re-auths.
 *
 * Same file-JSON pattern as the rest of the shell (suspend load/save on IO,
 * defaults on parse failure).
 */

@Serializable
data class DriveState(
    /** The current token set, or null when signed out. */
    val tokens: DriveTokenSet? = null,
    /** PKCE verifier for the in-flight Custom-Tab handshake (single-use). */
    val pendingVerifier: String? = null,
    /** CSRF state for the in-flight handshake (single-use). */
    val pendingState: String? = null,
)

/** File-backed JSON persistence for [DriveState] (`filesDir/drive.json`). */
class DriveStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    suspend fun load(): DriveState = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.exists() }?.readText()
                ?.let { json.decodeFromString(DriveState.serializer(), it) }
        }.getOrNull() ?: DriveState()
    }

    suspend fun save(state: DriveState) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(DriveState.serializer(), state)) }
        }
    }

    private companion object {
        const val FILE_NAME = "drive.json"
    }
}
