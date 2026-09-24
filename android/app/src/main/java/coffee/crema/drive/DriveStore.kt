package coffee.crema.drive

import android.content.Context
import android.util.Log
import coffee.crema.security.DeviceSecretBox
import coffee.crema.security.SecretBox
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
    /** Epoch ms the in-flight handshake began — lets a return to the app with
     *  the handshake still pending be recognised as "Google never came back"
     *  (geota/crema#45). Additive → nullable. */
    val pendingStartedAtMs: Long? = null,
    /** Epoch ms of the last daily auto-backup ATTEMPT (issue #36) — stamped
     *  before the upload so a failing backup retries next day, not on every
     *  launch. Null = never attempted. Additive → nullable default. */
    val lastAutoBackupAtMs: Long? = null,
    /** Epoch ms of the last SUCCESSFUL backup upload, manual or automatic —
     *  drives the "Last backed up X ago" readout. Additive → nullable. */
    val lastBackupSuccessAtMs: Long? = null,
)

/** The at-rest form: token + PKCE verifier sealed ([SecretBox]). Idempotent. */
internal fun DriveState.sealedWith(box: SecretBox): DriveState {
    fun seal(v: String) = if (SecretBox.isWrapped(v)) v else box.wrap(v)
    return copy(
        tokens = tokens?.let { t -> t.copy(accessToken = seal(t.accessToken), refreshToken = t.refreshToken?.let(::seal)) },
        pendingVerifier = pendingVerifier?.let(::seal),
    )
}

/** The in-memory form, plus whether to re-save (legacy plaintext, or a lost key → signed out). */
internal fun DriveState.openedWith(box: SecretBox): Pair<DriveState, Boolean> {
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
    return copy(
        tokens = if (lost || access == null) null else tokens?.copy(accessToken = access, refreshToken = refresh),
        pendingVerifier = if (lost) null else verifier,
        pendingState = if (lost) null else pendingState,
        pendingStartedAtMs = if (lost) null else pendingStartedAtMs,
    ) to resave
}

/** File-backed JSON persistence for [DriveState] (`filesDir/drive.json`). */
class DriveStore(
    private val context: Context,
    private val json: Json,
    private val box: SecretBox = DeviceSecretBox.instance,
) {
    private val file get() = File(context.filesDir, FILE_NAME)

    suspend fun load(): DriveState = withContext(Dispatchers.IO) {
        val raw = runCatching {
            file.takeIf { it.exists() }?.readText()
                ?.let { json.decodeFromString(DriveState.serializer(), it) }
        }.onFailure { Log.w(TAG, "drive.json unreadable; starting signed out", it) }
            .getOrNull() ?: return@withContext DriveState()
        val (state, resave) = raw.openedWith(box)
        if (resave) write(state)
        state
    }

    /** Write [state]; false (logged) when it could not be stored. */
    suspend fun save(state: DriveState): Boolean = withContext(Dispatchers.IO) { write(state) }

    private fun write(state: DriveState): Boolean = try {
        file.writeText(json.encodeToString(DriveState.serializer(), state.sealedWith(box)))
        true
    } catch (e: Exception) {
        // Includes a keystore failure while sealing: never fall back to plaintext.
        Log.e(TAG, "couldn't save drive.json", e)
        false
    }

    private companion object {
        const val FILE_NAME = "drive.json"
        const val TAG = "DriveStore"
    }
}
