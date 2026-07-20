package coffee.crema.drive

import coffee.crema.visualizer.codeChallengeFromVerifier
import coffee.crema.visualizer.generateCodeVerifier
import coffee.crema.visualizer.randomState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException

/*
 * Google Drive backup controller — the Android twin of the web shell's
 * `$lib/drive/store.svelte.ts`, and a sibling of [coffee.crema.visualizer.VisualizerSync].
 * Deliberately NOT folded into MainViewModel: the VM instantiates one, mirrors
 * [state] into MainUiState, and forwards UI intents.
 *
 * Owns the OAuth lifecycle (PKCE Authorization-Code via a Custom Tab, reusing the
 * Visualizer PKCE helpers), token freshness, and the three backup operations
 * (upload / list+download). The backup BYTES come from the VM (it owns the
 * library/history/settings) via the [backupZip] / [applyRestore] callbacks — the
 * same `.crema.zip` (backup.jsonl + bean photos) the local SAF backup writes, so a
 * Drive backup is interchangeable with a file one.
 */
/** Minimum gap between two daily auto-backup attempts (issue #36). */
private const val AUTO_BACKUP_INTERVAL_MS = 24L * 60 * 60 * 1000

/** Filename prefix distinguishing automatic uploads from manual ones —
 *  must keep containing "crema-backup" (the Drive list query matches on
 *  that substring, and restore-latest must see auto files too). */
private const val AUTO_BACKUP_PREFIX = "crema-backup-auto-"

/** How many auto-backups retention pruning keeps (≈ a week and a half of
 *  daily coverage; Decenza's local equivalent keeps 5 days). */
private const val AUTO_BACKUP_KEEP = 10

class DriveSync(
    private val store: DriveStore,
    private val json: Json,
    private val scope: CoroutineScope,
    /** Drive OAuth client_id (BuildConfig); blank = not configured in this build. */
    private val clientId: String,
    /** Surface a user-facing message (the VM's snackbar channel). */
    private val notify: (String) -> Unit,
    /** Produce the `.crema.zip` backup bytes, or null (+ its own notice) when empty. */
    private val backupZip: () -> ByteArray?,
    /** A suggested backup filename (the VM's `backupFileName()`). */
    private val backupFileName: () -> String,
    /** Apply a downloaded backup's raw bytes (a `.crema.zip` or legacy text).
     *  `wipe` = replace vs merge. */
    private val applyRestore: (bytes: ByteArray, wipe: Boolean) -> Unit,
) {
    /** What the Settings UI binds to (mirrored into MainUiState). */
    data class UiState(
        /** Epoch ms of the last successful backup upload (manual or auto),
         *  or null — the settings "Last backed up X ago" readout. */
        val lastBackupAtMs: Long? = null,
        /** False until a Drive client_id is baked into the build. */
        val configured: Boolean = false,
        val connected: Boolean = false,
        /** True while a sign-in / backup / restore runs. */
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState(configured = clientId.isNotBlank()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var persisted = DriveState()
    private val persistMutex = Mutex()

    /** Hydrate from disk at startup (called from the VM's init coroutine). */
    suspend fun load() {
        persisted = store.load()
        fold()
    }

    private fun fold() {
        _state.update { it.copy(
            configured = clientId.isNotBlank(),
            connected = persisted.tokens != null,
            lastBackupAtMs = persisted.lastBackupSuccessAtMs,
        ) }
    }

    private suspend fun persist(mutate: (DriveState) -> DriveState) {
        persistMutex.withLock {
            persisted = mutate(persisted)
            store.save(persisted)
        }
        fold()
    }

    // ── Sign-in lifecycle ────────────────────────────────────────────────────

    /** Begin the PKCE handshake: mint verifier + state, persist them (the process
     *  can die while the Custom Tab is up), and hand the authorize URL to [openUrl]. */
    fun beginSignIn(openUrl: (String) -> Unit) {
        if (clientId.isBlank()) {
            notify("Google Drive isn’t configured in this build (missing client id)")
            return
        }
        val verifier = generateCodeVerifier()
        val csrf = randomState()
        scope.launch {
            persist { it.copy(pendingVerifier = verifier, pendingState = csrf) }
            openUrl(buildGoogleAuthorizeUrl(clientId, csrf, codeChallengeFromVerifier(verifier)))
        }
    }

    /** Complete the handshake from the reversed-client-id redirect. Verifies CSRF
     *  state, exchanges the single-use code, persists the token. */
    fun handleCallback(code: String?, returnedState: String?, error: String?) {
        scope.launch {
            val verifier = persisted.pendingVerifier
            val expected = persisted.pendingState
            persist { it.copy(pendingVerifier = null, pendingState = null) }
            when {
                error != null -> notify("Google Drive sign-in was cancelled")
                code == null -> notify("Google Drive sign-in failed — no code returned")
                verifier == null -> notify("Google Drive sign-in expired — try again")
                expected == null || expected != returnedState ->
                    notify("Google Drive sign-in failed — state mismatch, try again")
                else -> {
                    _state.update { it.copy(busy = true) }
                    runCatching { exchangeCodeForDriveToken(clientId, code, verifier, json) }
                        .onSuccess { tokens ->
                            persist { it.copy(tokens = tokens) }
                            notify("Connected to Google Drive")
                        }
                        .onFailure { notify("Google Drive sign-in failed: ${it.message}") }
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    /** Forget the token (best-effort — Google has no public revoke for `drive.file`). */
    fun disconnect() {
        scope.launch {
            persist { it.copy(tokens = null) }
            notify("Disconnected from Google Drive")
        }
    }

    /** A valid access token, refreshing within 60 s of expiry; null when signed out
     *  or the refresh failed (which signs us out). Mirrors the web store's
     *  `accessToken()`. */
    private suspend fun freshToken(): String? {
        val t = persisted.tokens ?: return null
        if (System.currentTimeMillis() < t.expiresAt - 60_000) return t.accessToken
        val refresh = t.refreshToken ?: return t.accessToken
        return runCatching { refreshDriveToken(clientId, refresh, json) }
            .fold(
                onSuccess = { fresh -> persist { it.copy(tokens = fresh) }; fresh.accessToken },
                onFailure = {
                    persist { it.copy(tokens = null) }
                    notify("Google Drive session expired — reconnect")
                    null
                },
            )
    }

    // ── Backup operations ─────────────────────────────────────────────────────

    /** Upload a fresh `crema-backup/v1` file to Drive (timestamped — keeps a history). */
    fun backupNow() {
        scope.launch {
            val token = freshToken() ?: run { notify("Connect Google Drive first"); return@launch }
            val zip = backupZip() ?: return@launch // backupZip already notified "nothing to back up"
            _state.update { it.copy(busy = true) }
            runCatching { driveUploadBackup(token, backupFileName(), zip, json) }
                .onSuccess {
                    persist { it.copy(lastBackupSuccessAtMs = System.currentTimeMillis()) }
                    notify("Backed up to Google Drive")
                }
                .onFailure { notify("Google Drive backup failed: ${it.message}") }
            _state.update { it.copy(busy = false) }
        }
    }

    /**
     * Daily automatic backup (issue #36): when Drive is linked and the last
     * attempt is over 24 h old, run one quiet backup. Called on app start —
     * no scheduler; a machine tablet gets opened most days, and the refresh
     * token makes the upload unattended. Stamps the ATTEMPT time up front so
     * a failing upload retries tomorrow instead of nagging every launch.
     */
    fun autoBackupIfDue(enabled: Boolean) {
        if (!enabled) return
        scope.launch {
            if (persisted.tokens == null) return@launch
            val last = persisted.lastAutoBackupAtMs
            val now = System.currentTimeMillis()
            if (last != null && now - last < AUTO_BACKUP_INTERVAL_MS) return@launch
            persist { it.copy(lastAutoBackupAtMs = now) }
            val token = freshToken() ?: return@launch
            val zip = backupZip() ?: return@launch
            // Auto uploads carry a distinct prefix (still containing
            // "crema-backup" so the restore-latest list query sees them) —
            // retention pruning below only ever touches this prefix, so
            // manual backups are never deleted.
            val name = backupFileName().replaceFirst("crema-backup-", AUTO_BACKUP_PREFIX)
            runCatching { driveUploadBackup(token, name, zip, json) }
                .onSuccess {
                    persist { it.copy(lastBackupSuccessAtMs = System.currentTimeMillis()) }
                    notify("Daily backup saved to Google Drive")
                    pruneAutoBackups(token)
                }
                .onFailure { notify("Daily Google Drive backup failed: ${it.message}") }
        }
    }

    /** Retention pruning: keep the newest [AUTO_BACKUP_KEEP] auto-backups
     *  (daily uploads otherwise accumulate ~365 files/year in the user's
     *  Drive), never touching manual backups. Best-effort — a failed prune
     *  just retries after tomorrow's upload. */
    private suspend fun pruneAutoBackups(token: String) {
        runCatching {
            val stale = driveListBackups(token, json)
                .filter { it.name.startsWith(AUTO_BACKUP_PREFIX) }
                .sortedByDescending { it.modifiedTime }
                .drop(AUTO_BACKUP_KEEP)
            for (file in stale) driveDeleteFile(token, file.id, json)
        }
    }

    /** Download the newest Drive backup and hand its text to [applyRestore]. */
    fun restoreLatest(wipe: Boolean) {
        scope.launch {
            val token = freshToken() ?: run { notify("Connect Google Drive first"); return@launch }
            _state.update { it.copy(busy = true) }
            runCatching {
                val newest = driveListBackups(token, json).firstOrNull()
                    ?: throw IOException("No Crema backups found in Google Drive")
                driveDownloadBackup(token, newest.id, json)
            }
                .onSuccess { bytes -> applyRestore(bytes, wipe) }
                .onFailure { notify("Google Drive restore failed: ${it.message}") }
            _state.update { it.copy(busy = false) }
        }
    }
}
