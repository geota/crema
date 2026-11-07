package coffee.crema.drive

import coffee.crema.core.AutoBackupRetention
import coffee.crema.core.BackupCounts
import coffee.crema.core.BackupFile
import coffee.crema.core.BackupFileInfo
import coffee.crema.core.BackupShrink
import coffee.crema.core.BackupShrinkKind
import coffee.crema.core.CallbackOutcome
import coffee.crema.core.RunOutcome
import coffee.crema.core.RunOutcomeCompleteInner
import coffee.crema.core.RunOutcomeFailedInner
import coffee.crema.core.SyncFailure
import coffee.crema.core.SyncFailureRejectedInner
import coffee.crema.core.autoBackupFileName
import coffee.crema.core.autoBackupRetention
import coffee.crema.core.describeBackupFile
import coffee.crema.core.callbackOutcome
import coffee.crema.core.reconnectNoticeDue
import coffee.crema.runCatchingCancellable
import coffee.crema.sync.OwnStateGate
import coffee.crema.sync.SingleFlightRefresh
import coffee.crema.visualizer.codeChallengeFromVerifier
import coffee.crema.visualizer.generateCodeVerifier
import coffee.crema.visualizer.randomState
import kotlinx.coroutines.CancellationException
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

/** One backup in the picker. [modifiedMs] is null when Drive's time can't be read. */
data class DriveBackupRow(val id: String, val modifiedMs: Long?, val info: BackupFileInfo)

internal fun DriveFile.toBackupRow(json: Json) = DriveBackupRow(
    id = id,
    modifiedMs = toBackupFile()?.modifiedMs,
    info = json.decodeFromString(BackupFileInfo.serializer(), describeBackupFile(name)),
)

/** The picker's second line: what's known of a backup, of device, counts, kind. */
internal fun DriveBackupRow.subtitle(): String = listOfNotNull(
    info.device,
    info.counts?.let { "${it.shots} shots, ${it.curves} with curves" },
    if (info.automatic) "Daily" else "Manual",
).joinToString(" · ")

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
    /** A line for the event log and diagnostics, not the user. */
    private val log: (String) -> Unit = {},
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
    /** Wall clock (unix ms); tests pin it. */
    private val now: () -> Long = System::currentTimeMillis,
    /** The Drive calls (injected so JVM tests can drive a backup). */
    private val uploadBackup: suspend (token: String, name: String, bytes: ByteArray) -> DriveFile =
        { token, name, bytes -> driveUploadBackup(token, name, bytes, json) },
    private val listBackups: suspend (token: String) -> List<DriveFile> = { token -> driveListBackups(token, json) },
    private val trashFile: suspend (token: String, fileId: String) -> Unit =
        { token, id -> driveTrashFile(token, id, json) },
    private val downloadBackup: suspend (token: String, fileId: String) -> ByteArray =
        { token, id -> driveDownloadBackup(token, id, json) },
    /** Revoke a grant at Google, best-effort (injected so JVM tests make no call). */
    private val revokeGrant: suspend (DriveTokenSet) -> Unit = { revokeDriveGrant(it) },
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
        /** Google ended the sign-in: signed out, and Settings asks to reconnect. */
        val needsReconnect: Boolean = false,
        /** The last run's lasting status, or null when there's nothing to say. */
        val healthLine: String? = null,
        /** The backups to pick from, newest first, while the picker is open. */
        val backups: List<DriveBackupRow>? = null,
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
            needsReconnect = persisted.needsReconnect,
            healthLine = driveHealthLine(persisted.health),
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
                    runCatchingCancellable { exchangeCode(code, verifier) }
                        .onSuccess { tokens ->
                            persist { it.copy(tokens = tokens, needsReconnect = false, health = null) }
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
            persist { it.copy(tokens = null, unopened = null, needsReconnect = false, health = null) }
            if (!quiet) notify("Disconnected from Google Drive")
            tokens?.let { revokeGrant(it) }
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
        tokens?.let { scope.launch { revokeGrant(it) } }
    }

    /** What [freshToken] found. */
    private sealed interface FreshToken {
        /** A set to call with. */
        data class Token(val set: DriveTokenSet) : FreshToken

        /** The session stands, but no token for now. */
        data class Waiting(val failure: SyncFailure) : FreshToken

        /** Signed out, or Google ended the sign-in. */
        data object NeedsReconnect : FreshToken
    }

    /** A Drive call that couldn't get a token, or whose retry couldn't. */
    private class NoDriveToken(val failure: SyncFailure, message: String? = null) : Exception(message)

    private val refresher = SingleFlightRefresh<DriveTokenSet>(
        stored = { persisted.tokens },
        refreshTokenOf = { it.refreshToken },
        expiring = ::expiring,
        grant = refreshToken,
        endsSession = { driveFailure(it, "refresh", now()) == SyncFailure.SessionEnded },
        store = { spent, fresh ->
            persist { st -> if (st.tokens?.refreshToken == spent) st.copy(tokens = fresh) else st }
        },
        end = { rejected ->
            // Keeps everything else, so a reconnect picks up where this left off.
            var ended = false
            persist { st ->
                ended = st.tokens != null && st.tokens.refreshToken == rejected
                if (ended) st.copy(tokens = null, needsReconnect = true) else st
            }
            if (ended) finishRun(failed(SyncFailure.SessionEnded))
        },
    )

    private fun expiring(tokens: DriveTokenSet) = now() >= tokens.expiresAt - 60_000

    /** A token to call with, refreshing within 60 s of expiry. Only Google saying
     *  the refresh token is dead ends the session (geota/crema#323). */
    private suspend fun freshToken(): FreshToken {
        val tokens = persisted.tokens ?: return FreshToken.NeedsReconnect
        return if (expiring(tokens)) refreshed(tokens, rejected = false) else FreshToken.Token(tokens)
    }

    private suspend fun refreshed(observed: DriveTokenSet, rejected: Boolean): FreshToken = try {
        refresher.refresh(observed, rejected)?.let { FreshToken.Token(it) } ?: FreshToken.NeedsReconnect
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        FreshToken.Waiting(driveFailure(e, "refresh", now()))
    }

    /** Run [call] with [tokens], refreshing and retrying once on a 401. */
    private suspend fun <T> withToken(tokens: DriveTokenSet, call: suspend (String) -> T): T {
        try {
            return call(tokens.accessToken)
        } catch (e: DriveHttpError) {
            if (driveFailure(e, "call", now()) != SyncFailure.AccessRefused) throw e
            when (val again = refreshed(tokens, rejected = true)) {
                is FreshToken.Token -> try {
                    return call(again.set.accessToken)
                } catch (second: DriveHttpError) {
                    // A fresh token and still a 401: Drive is refusing this
                    // sign-in, and Google hasn't ended it, so it isn't one to
                    // word as ended.
                    if (driveFailure(second, "call", now()) != SyncFailure.AccessRefused) throw second
                    throw NoDriveToken(SyncFailure.Rejected(SyncFailureRejectedInner(second.status.toUShort())), second.message)
                }
                is FreshToken.Waiting -> throw NoDriveToken(again.failure)
                FreshToken.NeedsReconnect -> throw NoDriveToken(SyncFailure.SessionEnded)
            }
        }
    }

    private fun failureOf(e: Throwable): SyncFailure = (e as? NoDriveToken)?.failure ?: driveFailure(e, "call", now())

    private fun failed(failure: SyncFailure) = RunOutcome.Failed(RunOutcomeFailedInner(failure))

    private fun backedUp() = RunOutcome.Complete(
        RunOutcomeCompleteInner(next_cursor_ms = 0, applied = 1u, unchanged = 0u, skipped_permanent = 0u),
    )

    /** Keep the run's lasting status, and raise the one reconnect notice on the way
     *  in. True when the status changed. */
    private suspend fun finishRun(outcome: RunOutcome): Boolean {
        val previous = persisted.health
        val next = driveHealthAfter(outcome, now())
        persist { it.copy(health = next) }
        if (reconnectNoticeDue(previous, next)) notify(DRIVE_RECONNECT_LINE)
        return previous != next
    }

    /**
     * A token for a user-started [action], or null once it has said why it can't
     * start. A session that ended in this very call has said so already.
     */
    private suspend fun tokenForUser(action: String): DriveTokenSet? {
        val alreadyEnded = persisted.needsReconnect
        return when (val held = freshToken()) {
            is FreshToken.Token -> held.set
            FreshToken.NeedsReconnect -> {
                if (!persisted.needsReconnect) notify("Connect Google Drive first")
                else if (alreadyEnded) notify(DRIVE_RECONNECT_LINE)
                null
            }
            is FreshToken.Waiting -> {
                finishRun(failed(held.failure))
                notify("Google Drive $action failed: ${driveFailureLine(held.failure, null)}")
                null
            }
        }
    }

    // ── Backup operations ─────────────────────────────────────────────────────

    /** Upload a fresh `crema-backup/v1` file to Drive (timestamped — keeps a history). */
    fun backupNow() {
        scope.launch {
            if (!ownState.await()) return@launch
            val tokens = tokenForUser("backup") ?: return@launch
            // Busy from the start: building the zip takes a while on a big
            // history (it runs off Main, so the UI stays live meanwhile).
            _state.update { it.copy(busy = true) }
            val built = backupZip() ?: run {
                _state.update { it.copy(busy = false) } // backupZip already notified "nothing to back up"
                return@launch
            }
            try {
                withToken(tokens) { uploadBackup(it, backupFileName(), built.bytes) }
                persist { it.copy(lastBackupSuccessAtMs = now()) }
                finishRun(backedUp())
                notify("Backed up to Google Drive")
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                val failure = failureOf(e)
                finishRun(failed(failure))
                // A session that just ended has said so once already.
                if (failure != SyncFailure.SessionEnded) {
                    notify("Google Drive backup failed: ${driveFailureLine(failure, e.message)}")
                }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    /**
     * Daily automatic backup (issue #36): when Drive is linked and the last
     * attempt is over 24 h old, run one quiet backup. Called on app start — no
     * scheduler; a machine tablet gets opened most days, and the refresh token
     * makes the upload unattended. The attempt is stamped once a token is held,
     * so a launch with no network costs nothing. An upload that fails for a
     * reason time can fix tries again next launch; any other waits a day, with
     * its reason in Settings rather than a notice on every launch.
     */
    fun autoBackupIfDue(enabled: Boolean) {
        if (!enabled) return
        scope.launch {
            if (!ownState.await()) return@launch
            if (persisted.tokens == null) return@launch
            val last = persisted.lastAutoBackupAtMs
            if (last != null && now() - last < AUTO_BACKUP_INTERVAL_MS) return@launch
            val tokens = when (val held = freshToken()) {
                is FreshToken.Token -> held.set
                is FreshToken.Waiting -> {
                    finishRun(failed(held.failure))
                    return@launch
                }
                FreshToken.NeedsReconnect -> {
                    finishRun(failed(SyncFailure.SessionEnded))
                    return@launch
                }
            }
            persist { it.copy(lastAutoBackupAtMs = now()) }
            val built = backupZip() ?: return@launch
            // The name carries the counts the retention below compares, and this
            // install's tag. Its prefix still holds "crema-backup", so the Drive
            // list query finds it, and only this prefix is ever pruned, so a
            // manual backup never is.
            val tag = persisted.autoBackupTag
                ?: newAutoBackupTag().also { fresh -> persist { it.copy(autoBackupTag = fresh) } }
            val name = autoBackupFileName(backupFileName(), tag, json.encodeToString(BackupCounts.serializer(), built.counts))
            try {
                withToken(tokens) { uploadBackup(it, name, built.bytes) }
                persist { it.copy(lastBackupSuccessAtMs = now()) }
                finishRun(backedUp())
                notify("Daily backup saved to Google Drive")
                settleAutoBackups(tokens, name)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                val failure = failureOf(e)
                if (isTransient(failure)) persist { it.copy(lastAutoBackupAtMs = last) }
                val changed = finishRun(failed(failure))
                // Everything else stays a Settings line. This one a user can act
                // on, and it won't clear by itself: once, on the way in.
                if (changed && e is NoDriveToken && failure is SyncFailure.Rejected) notify(DRIVE_REFUSED_LINE)
            }
        }
    }

    /**
     * After an automatic upload: the core's retention over the whole listing
     * ([autoBackupRetention], daily uploads otherwise accumulate ~365 files a
     * year), and a notice if the copy just made is far smaller than the last.
     * Pruned copies go to the trash, so a wrong prune can be undone for 30 days.
     * Best-effort: a failed list or trash just retries after tomorrow's upload.
     */
    private suspend fun settleAutoBackups(tokens: DriveTokenSet, uploaded: String) {
        runCatchingCancellable {
            val files = withToken(tokens) { listBackups(it) }.mapNotNull { it.toBackupFile() }
            val filesJson = json.encodeToString(ListSerializer(BackupFile.serializer()), files)
            val keep = persisted.autoBackupKeep
                ?: AUTO_BACKUP_KEEP.also { chosen -> persist { it.copy(autoBackupKeep = chosen) } }
            val plan = json.decodeFromString(
                AutoBackupRetention.serializer(),
                autoBackupRetention(filesJson, uploaded, keep.toUInt()),
            )
            plan.shrink?.let { notify(shrinkNotice(it)) }
            withToken(tokens) { token -> plan.delete.forEach { trashFile(token, it) } }
        }.onFailure { log("Drive backup prune failed: ${it.message}") }
    }

    /** List what's in Drive and open the picker: every backup, manual or daily,
     *  from any device, as the newest isn't always the one that's wanted. */
    fun openBackupPicker() {
        scope.launch {
            if (!ownState.await()) return@launch
            val tokens = tokenForUser("restore") ?: return@launch
            _state.update { it.copy(busy = true) }
            runCatchingCancellable { withToken(tokens) { listBackups(it) }.map { it.toBackupRow(json) } }
                .onSuccess { rows ->
                    if (rows.isEmpty()) notify("No Crema backups found in Google Drive")
                    else _state.update { it.copy(backups = rows) }
                }
                .onFailure { e ->
                    val failure = (e as? NoDriveToken)?.failure
                    if (failure != SyncFailure.SessionEnded) {
                        notify("Couldn't list your Google Drive backups: ${failure?.let { driveFailureLine(it, e.message) } ?: e.message}")
                    }
                }
            _state.update { it.copy(busy = false) }
        }
    }

    /** The picker was dismissed. */
    fun closeBackupPicker() = _state.update { it.copy(backups = null) }

    /** Download the backup [fileId] and hand its bytes to [applyRestore]. */
    fun restoreBackup(fileId: String, wipe: Boolean) {
        scope.launch {
            if (!ownState.await()) return@launch
            val tokens = tokenForUser("restore") ?: return@launch
            _state.update { it.copy(busy = true, backups = null) }
            runCatchingCancellable { withToken(tokens) { downloadBackup(it, fileId) } }
                .onSuccess { bytes -> applyRestore(bytes, wipe) }
                .onFailure { e ->
                    val failure = (e as? NoDriveToken)?.failure
                    if (failure != SyncFailure.SessionEnded) {
                        notify("Google Drive restore failed: ${failure?.let { driveFailureLine(it, e.message) } ?: e.message}")
                    }
                }
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
