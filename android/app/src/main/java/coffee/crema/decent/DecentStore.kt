package coffee.crema.decent

import android.content.Context
import android.util.Log
import coffee.crema.security.DeviceSecretBox
import coffee.crema.security.SecretBox
import coffee.crema.visualizer.SyncLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * The linked Decent Espresso account + the "upload shots to Decent" prefs
 * (geota/crema#84) — the Android twin of the web's `$lib/decent/account`.
 *
 * Decent's shot history (decentespresso.com → My account → shot history +
 * charts) accepts shots uploaded by the owner's apps: de1app's `shot_upload`
 * plugin and decaid's `shot-upload.reaplugin` both POST a decaid-format
 * ShotRecord to `/support/api/shot_upload`. Crema does the same, so a Crema
 * shot lands in the account next to the ones the tablet uploaded.
 *
 * `token` is the server-issued credential `login_test` returns for a valid
 * email + password — the password itself is never persisted (de1app / decaid
 * discipline). On disk it is sealed with the AndroidKeyStore key
 * ([SecretBox]); a legacy plaintext token is re-saved sealed on first read,
 * and one the key can no longer open reads as "sign in again". Kept in its own
 * file, like visualizer.json, so a prefs reset doesn't sign the user out and a
 * backup never carries it (data_extraction_rules.xml excludes it).
 */

/** The outcome of the most recent upload attempt, for the Settings card. */
@Serializable
data class DecentLastUpload(
    val at: Long,
    val ok: Boolean,
    /** Short human-readable note ("Uploaded", "Decent rejected the shot (HTTP 422)", …). */
    val message: String,
    /** The uploaded shot's public page when the upload succeeded with an id. */
    val url: String? = null,
)

@Serializable
data class DecentState(
    /** Account email once linked; kept through a "sign in again" so the form is prefilled. */
    val email: String? = null,
    /** Server-issued credential (from `login_test`); null = not linked. */
    val token: String? = null,
    /** DE1 serials registered on the account (`/support/api/sn`); refreshed on link. */
    val serials: List<String> = emptyList(),
    /**
     * Upload each finished shot as it completes. Linking turns this on (de1app:
     * enabling the plugin is the affirmative choice); the toggle opts out.
     */
    val autoUpload: Boolean = false,
    /** True once a request came back 401 / the stored token became unreadable — sign in again. */
    val needsReauth: Boolean = false,
    val lastUpload: DecentLastUpload? = null,
    /**
     * Shots the server refused for good (a permanent 4xx). Kept out of the
     * backlog so a drain doesn't hit them every time; a manual re-upload
     * clears the entry.
     */
    val rejectedShotIds: Set<String> = emptySet(),
    /** Decent's own "Recent activity" lines (capped 20, newest first); the UI merges it with Visualizer's. */
    val log: List<SyncLogEntry> = emptyList(),
) {
    val linked: Boolean get() = !email.isNullOrBlank() && !token.isNullOrBlank()
}

/** Persistence for [DecentState] — an interface so [DecentSync] is testable in memory. */
interface DecentStateStore {
    suspend fun load(): DecentState

    /** Write [state]; false (logged) when it could not be stored. */
    suspend fun save(state: DecentState): Boolean
}

/** The at-rest form: the token sealed. Idempotent. */
internal fun DecentState.sealedWith(box: SecretBox): DecentState =
    copy(token = token?.let { if (SecretBox.isWrapped(it)) it else box.wrap(it) })

/** The in-memory form, plus whether the file should be re-saved (a plaintext token, or a lost one). */
internal fun DecentState.openedWith(box: SecretBox): Pair<DecentState, Boolean> {
    val stored = token ?: return this to false
    return when (val o = box.open(stored)) {
        is SecretBox.Opened.Value -> copy(token = o.value) to o.migrate
        // The key is gone: keep the email for a prefilled "sign in again".
        SecretBox.Opened.Lost -> copy(token = null, needsReauth = !email.isNullOrBlank()) to true
    }
}

class DecentStore(
    private val context: Context,
    private val json: Json,
    private val box: SecretBox = DeviceSecretBox.instance,
) : DecentStateStore {
    private val file get() = File(context.filesDir, FILE_NAME)

    override suspend fun load(): DecentState = withContext(Dispatchers.IO) {
        val raw = try {
            file.takeIf { it.exists() }?.readText()?.let { json.decodeFromString(DecentState.serializer(), it) }
        } catch (e: Exception) {
            Log.w(TAG, "decent.json unreadable; starting signed out", e)
            null
        } ?: return@withContext DecentState()
        val (state, resave) = raw.openedWith(box)
        if (resave) write(state)
        state
    }

    override suspend fun save(state: DecentState): Boolean = withContext(Dispatchers.IO) { write(state) }

    private fun write(state: DecentState): Boolean = try {
        file.writeText(json.encodeToString(DecentState.serializer(), state.sealedWith(box)))
        true
    } catch (e: Exception) {
        // Includes a keystore failure while sealing: never fall back to plaintext.
        Log.e(TAG, "couldn't save decent.json", e)
        false
    }

    private companion object {
        const val FILE_NAME = "decent.json"
        const val TAG = "DecentStore"
    }
}
