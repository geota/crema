package coffee.crema.drive

import coffee.crema.core.AutoBackupRetention
import coffee.crema.core.BackupCounts
import coffee.crema.core.BackupFile
import coffee.crema.core.BackupShrink
import coffee.crema.core.BackupShrinkKind
import coffee.crema.core.CallbackOutcome
import coffee.crema.core.autoBackupFileName
import coffee.crema.core.autoBackupRetention
import coffee.crema.core.callbackOutcome
import coffee.crema.sync.OwnStateGate
import coffee.crema.visualizer.codeChallengeFromVerifier
import coffee.crema.visualizer.generateCodeVerifier
import coffee.crema.visualizer.randomState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import java.util.UUID

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

/** Automatic copies kept per device, until a Settings choice exists. */
private const val AUTO_BACKUP_KEEP = 10

/** A built backup, and what's in it for its automatic name. */
class BackupZip(val bytes: ByteArray, val counts: BackupCounts)

/** A new install's tag: short, as it's in every file name. */
internal fun newAutoBackupTag(): String = UUID.randomUUID().toString().take(6)

/** A Drive listing's row as the core reads it; null when its time can't be read,
 *  which leaves that file alone. */
internal fun DriveFile.toBackupFile(): BackupFile? =
    runCatching { BackupFile(id, name, Instant.parse(modifiedTime).toEpochMilli()) }.getOrNull()

/** What to tell the user when a backup came out far smaller than the last. */
internal fun shrinkNotice(shrink: BackupShrink): String {
    val what = when (shrink.kind) {
        BackupShrinkKind.Shots -> "shots"
        BackupShrinkKind.Curves -> "curves"
    }
    return "Today's backup has far fewer $what than your last backup (${shrink.now}, was ${shrink.was}). " +
        "Older copies are being kept."
}

class DriveSync(
    private val store: DriveStore,
    private val json: Json,
    private val scope: CoroutineScope,
    /** Drive OAuth client_id (BuildConfig); blank = not configured in this build. */
    private val clientId: String,
    /** Surface a user-facing message (the VM's snackbar channel). */
    private val notify: (String) -> Unit,
    /** Produce the `.crema.zip` backup bytes, or null (+ its own notice) when
     *  empty. Suspends: the build runs off Main (LibraryController). */
    private val backupZip: suspend () -> BackupZip?,
    /** A suggested backup filename (the VM's `backupFileName()`). */
    private val backupFileName: () -> String,
    /** Apply a downloaded backup's raw bytes (a `.crema.zip` or legacy text).
     *  `wipe` = replace vs merge. */
    private val applyRestore: (bytes: ByteArray, wipe: Boolean) -> Unit,
    /** The OAuth code → token exchange (injected so JVM tests can drive a sign-in). */
    private val exchangeCode: suspend (code: String, verifier: String) -> DriveTokenSet =
        { code, verifier -> exchangeCodeForDriveToken(clientId, code, verifier, json) },
    /** The refresh-token grant (injected so JVM tests can drive a refresh). */
    private val refreshToken: suspend (refresh: String) -> DriveTokenSet = { refresh -> refreshDriveToken(clientId, refresh, json) },
    /** Core `callback_outcome`: what a redirect means for the stored handshake. */
    private val callbackRule: (expectedState: String?, verifier: String?, code: String?, returnedState: String?, error: String?) -> CallbackOutcome =
        ::callbackOutcome,
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

    @Volatile
    private var persisted = DriveState()
    private val persistMutex = Mutex()

    /** Holds everything that touches [persisted] until [load] has read the file. */
    private val ownState = OwnStateGate("Google Drive", notify)

    /** A sign-in began in this process. One from before a restart is for its redirect to finish. */
    @Volatile
    private var signInStartedHere = false

    /** Hydrate from disk at startup (its own coroutine, so nothing in the row
     *  load can strand a sign-in or the daily backup). */
    suspend fun load() {
        try {
            persisted = store.load()
            ownState.opened()
        } finally {
            ownState.failed()
        }
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
        if (!ownState.await()) return
        persistMutex.withLock {
            // New tokens replace a sign-in the keystore couldn't open at start.
            persisted = mutate(persisted).let { if (it.tokens != null && it.unopened != null) it.copy(unopened = null) else it }
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
            if (!ownState.await()) return@launch
            signInStartedHere = true
            persist {
                it.copy(
                    pendingVerifier = verifier,
                    pendingState = csrf,
                    pendingStartedAtMs = System.currentTimeMillis(),
                )
            }
            openUrl(buildGoogleAuthorizeUrl(clientId, csrf, codeChallengeFromVerifier(verifier)))
        }
    }

    /**
     * Called when the app comes back to the foreground: if the handshake is
     * STILL pending a moment later, Google never redirected us — it rendered an
     * error page in the browser and the app would otherwise sit silent forever
     * with a stale verifier (geota/crema#45, where Google answered
     * `invalid_request: Custom URI scheme is not enabled for your Android
     * client` and the user only saw a browser error).
     *
     * The delay lets a real redirect win: it arrives via `onNewIntent` →
     * [handleCallback], whose clear is a coroutine, so checking synchronously
     * on resume would race it. [GRACE_MS] also keeps a fast tab-open →
     * tab-close round trip from tripping this.
     *
     * A handshake from before a restart isn't judged here: the consent page can
     * still be open, and its redirect can still finish the sign-in.
     */
    fun notePossibleAbandon() {
        scope.launch {
            if (!signInStartedHere || persisted.pendingVerifier == null) return@launch
            delay(GRACE_MS)
            val started = persisted.pendingStartedAtMs ?: 0L
            if (persisted.pendingVerifier == null) return@launch
            if (System.currentTimeMillis() - started < GRACE_MS) return@launch
            persist { it.copy(pendingVerifier = null, pendingState = null, pendingStartedAtMs = null) }
            notify("Google Drive sign-in didn’t complete — Google never returned to Crema. If the browser showed an error, the Drive OAuth client needs “Custom URI scheme” enabled.")
        }
    }

    /** Complete the handshake from the reversed-client-id redirect. Verifies CSRF
     *  state, exchanges the single-use code, persists the token. */
    fun handleCallback(code: String?, returnedState: String?, error: String?) {
        scope.launch {
            if (!ownState.await()) return@launch
            val verifier = persisted.pendingVerifier
            val outcome = callbackRule(persisted.pendingState, verifier, code, returnedState, error)
            // With no handshake of ours (a stray redirect, a cold start or a
            // recreated activity replaying one) it writes nothing, and one in
            // progress survives it.
            if (outcome == CallbackOutcome.NotOurs) return@launch
            // Single-use — clear the handshake whatever happens next.
            persist { it.copy(pendingVerifier = null, pendingState = null, pendingStartedAtMs = null) }
            when (outcome) {
                CallbackOutcome.NotOurs -> Unit
                CallbackOutcome.Cancelled -> notify("Google Drive sign-in was cancelled")
                // Google refusing the request: saying "cancelled" for that
                // hides a real configuration fault (geota/crema#45).
                is CallbackOutcome.Refused -> notify("Google Drive sign-in failed: ${outcome.error}")
                CallbackOutcome.NoCode -> notify("Google Drive sign-in failed — no code returned")
                CallbackOutcome.Exchange -> {
                    code ?: return@launch
                    verifier ?: return@launch
                    _state.update { it.copy(busy = true) }
                    runCatching { exchangeCode(code, verifier) }
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

    /**
     * Forget the token, then revoke the grant at Google (best-effort, from the
     * in-memory copy so a slow or failed revoke never leaves it on disk).
     * Revoking the refresh token ends the whole grant (final review 05 N-5).
     */
    fun disconnect(quiet: Boolean = false) {
        scope.launch {
            if (!ownState.await()) return@launch
            val tokens = persisted.tokens
            // Also forgets a stored sign-in the keystore couldn't open this start.
            persist { it.copy(tokens = null, unopened = null) }
            if (!quiet) notify("Disconnected from Google Drive")
            tokens?.let { revokeDriveGrant(it) }
        }
    }

    /**
     * Erase all data: reset everything this store keeps (the token, a pending
     * handshake, the backup timestamps), delete its file, then revoke the old
     * grant from the in-memory copy.
     */
    suspend fun eraseAll() {
        // The grant to revoke is in the file; a load that never ended only
        // costs the revoke, not the erase.
        ownState.settle()
        val tokens = persistMutex.withLock {
            val t = persisted.tokens
            persisted = DriveState()
            store.erase()
            ownState.opened()
            t
        }
        fold()
        tokens?.let { scope.launch { revokeDriveGrant(it) } }
    }

    /** A valid access token, refreshing within 60 s of expiry; null when signed out
     *  or the refresh failed (which signs us out). Mirrors the web store's
     *  `accessToken()`. */
    private suspend fun freshToken(): String? {
        val t = persisted.tokens ?: return null
        if (System.currentTimeMillis() < t.expiresAt - 60_000) return t.accessToken
        val refresh = t.refreshToken ?: return t.accessToken
        return runCatching { refreshToken(refresh) }
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
            // Busy from the start: building the zip takes a while on a big
            // history (it runs off Main, so the UI stays live meanwhile).
            _state.update { it.copy(busy = true) }
            val built = backupZip() ?: run {
                _state.update { it.copy(busy = false) } // backupZip already notified "nothing to back up"
                return@launch
            }
            runCatching { driveUploadBackup(token, backupFileName(), built.bytes, json) }
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
            if (!ownState.await()) return@launch
            if (persisted.tokens == null) return@launch
            val last = persisted.lastAutoBackupAtMs
            val now = System.currentTimeMillis()
            if (last != null && now - last < AUTO_BACKUP_INTERVAL_MS) return@launch
            persist { it.copy(lastAutoBackupAtMs = now) }
            val token = freshToken() ?: return@launch
            val built = backupZip() ?: return@launch
            // The name carries the counts the retention below compares, and this
            // install's tag. Its prefix still holds "crema-backup", so the Drive
            // list query finds it, and only this prefix is ever pruned, so a
            // manual backup never is.
            val tag = persisted.autoBackupTag
                ?: newAutoBackupTag().also { fresh -> persist { it.copy(autoBackupTag = fresh) } }
            val name = autoBackupFileName(backupFileName(), tag, json.encodeToString(BackupCounts.serializer(), built.counts))
            runCatching { driveUploadBackup(token, name, built.bytes, json) }
                .onSuccess {
                    persist { it.copy(lastBackupSuccessAtMs = System.currentTimeMillis()) }
                    notify("Daily backup saved to Google Drive")
                    settleAutoBackups(token, name)
                }
                .onFailure { notify("Daily Google Drive backup failed: ${it.message}") }
        }
    }

    /**
     * After an automatic upload: the core's retention over the whole listing
     * ([autoBackupRetention], daily uploads otherwise accumulate ~365 files a
     * year), and a notice if the copy just made is far smaller than the last.
     * Pruned copies go to the trash, so a wrong prune can be undone for 30 days.
     * Best-effort: a failed list or trash just retries after tomorrow's upload.
     */
    private suspend fun settleAutoBackups(token: String, uploaded: String) {
        runCatching {
            val files = driveListBackups(token, json).mapNotNull { it.toBackupFile() }
            val filesJson = json.encodeToString(ListSerializer(BackupFile.serializer()), files)
            val keep = persisted.autoBackupKeep
                ?: AUTO_BACKUP_KEEP.also { chosen -> persist { it.copy(autoBackupKeep = chosen) } }
            val plan = json.decodeFromString(
                AutoBackupRetention.serializer(),
                autoBackupRetention(filesJson, uploaded, keep.toUInt()),
            )
            plan.shrink?.let { notify(shrinkNotice(it)) }
            for (id in plan.delete) driveTrashFile(token, id, json)
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

    private companion object {
        /** How long to let a real redirect land before calling the handshake
         *  abandoned. Long enough for `onNewIntent` → [handleCallback]'s
         *  coroutine to clear the pending state, short enough that the user
         *  still connects the message to the tap. */
        const val GRACE_MS = 1_500L
    }
}
