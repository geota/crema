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
import coffee.crema.core.newestAutoBackup
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

/** Automatic copies kept per device for a new install, until a Settings choice exists. */
private const val AUTO_BACKUP_KEEP = 3

/** What an install that backed up before the pre-damage copy existed keeps: unchanged. */
private const val AUTO_BACKUP_KEEP_BEFORE = 10

/** How long a pre-damage pin holds against a newer damage, on Drive's clock. */
private const val PIN_KEEPS_MS = 30L * 24 * 60 * 60 * 1000

/** Counts for a name that's only read for its label. */
private const val ZERO_COUNTS = """{"shots":0,"curves":0}"""

/** A built backup, and what's in it for its automatic name. */
class BackupZip(
    /** The `.crema.zip`, a temporary file (#310 P2a): released once uploaded or skipped. */
    val file: java.io.File,
    val counts: BackupCounts,
    /** What's in it, without its export time: the same data gives the same hash (#311). */
    val contentHash: String? = null,
    /** Done with [file]: removes it. */
    val release: () -> Unit = { file.delete() },
)

/** What a new install keeps of its automatic copies. */
internal const val NEW_INSTALL_DRIVE_COPIES = 3

/**
 * The automatic copies retention keeps: the [stored] choice, else 10 for an
 * install that has backed up before (as it had before #362) and 3 for a new one.
 * The setting shows this, so it shows what retention uses.
 */
internal fun autoBackupKeepFor(stored: Int?, backedUpBefore: Boolean): Int =
    stored ?: if (backedUpBefore) AUTO_BACKUP_KEEP_BEFORE else AUTO_BACKUP_KEEP

/** The copies-to-keep choices Settings offers (#311). */
val DRIVE_COPIES_CHOICES = listOf(1, 3, 5, 10)

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
    /** Build a backup; [daily] for the automatic one, which has its own cancel and progress. */
    private val backupZip: suspend (daily: Boolean) -> BackupZip?,
    /** A suggested backup filename (the VM's `backupFileName()`). */
    private val backupFileName: () -> String,
    /** Apply a downloaded backup's raw bytes (a `.crema.zip` or legacy text).
     *  `wipe` = replace vs merge. */
    private val applyRestore: (bytes: ByteArray, wipe: Boolean) -> Unit,
    /** Whether the active network is unmetered (Wi-Fi): a Wi-Fi only backup waits for it. */
    private val unmetered: () -> Boolean = { true },
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
    private val uploadBackup: suspend (token: String, name: String, file: java.io.File) -> DriveFile =
        { token, name, file -> driveUploadBackup(token, name, file, json) },
    private val listBackups: suspend (token: String) -> List<DriveFile> = { token -> driveListBackups(token, json) },
    private val trashFile: suspend (token: String, fileId: String) -> Unit =
        { token, id -> driveTrashFile(token, id, json) },
    private val downloadBackup: suspend (token: String, fileId: String) -> ByteArray =
        { token, id -> driveDownloadBackup(token, id, json) },
    /** Revoke a grant at Google, best-effort (injected so JVM tests make no call). */
    private val revokeGrant: suspend (DriveTokenSet) -> Unit = { revokeDriveGrant(it) },
    /** Why a daily backup mustn't run now (a store that couldn't be read, a
     *  restore running), or null. A copy made then would hold less than the
     *  last, and could push a good one out. */
    private val autoBackupBlocked: () -> String? = { null },
    /** Per store the counts can't see (beans, profiles, recipes, settings), by
     *  file name: the newest time it was kept aside as damaged, on this
     *  device's clock. A store's time newer than the one last seen for that
     *  store is a new damage; one store's clock never hides another's. */
    private val damageByStore: () -> Map<String, Long> = { emptyMap() },
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
        /** Why today's daily backup didn't run, or null. */
        val autoBackupPaused: String? = null,
        /** Automatic copies kept (#311): the stored choice, else what the next
         *  retention would choose ([autoBackupKeepFor]). Null until loaded. */
        val autoBackupKeep: Int? = null,
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
            autoBackupKeep = autoBackupKeepFor(
                persisted.autoBackupKeep,
                persisted.lastAutoBackupAtMs != null || persisted.lastBackupSuccessAtMs != null,
            ),
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
                            // A new sign-in may be another account: the next
                            // backup uploads whatever it finds (#311).
                            persist { it.copy(tokens = tokens, needsReconnect = false, health = null, lastAutoBackupHash = null) }
                            notify("Connected to Google Drive")
                        }
                        .onFailure { notify("Google Drive sign-in failed: ${it.message}") }
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    /**
     * A restore finished: whatever damage the pre-damage pin guarded is dealt
     * with, so its 30-day hold ends. The pin stays in retention's slot until a
     * new damage pins yesterday's copy (REVIEW-366).
     */
    fun restoreCompleted() {
        scope.launch {
            if (!ownState.await()) return@launch
            if (persisted.preDamageCopy != null && !persisted.pinReleased) persist { it.copy(pinReleased = true) }
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
            // The hash goes too: the next account's Drive holds none of it (#311).
            persist { it.copy(tokens = null, unopened = null, needsReconnect = false, health = null, lastAutoBackupHash = null) }
            _state.update { it.copy(backups = null) }
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
            val built = backupZip(false) ?: run {
                _state.update { it.copy(busy = false) } // backupZip already notified "nothing to back up"
                return@launch
            }
            try {
                withToken(tokens) { uploadBackup(it, backupFileName(), built.file) }
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
            } finally {
                built.release()
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
    fun autoBackupIfDue(enabled: Boolean, weekly: Boolean = false, wifiOnly: Boolean = false) {
        if (!enabled) return
        scope.launch {
            if (!ownState.await()) return@launch
            if (persisted.tokens == null) return@launch
            val last = persisted.lastAutoBackupAtMs
            val interval = if (weekly) 7 * AUTO_BACKUP_INTERVAL_MS else AUTO_BACKUP_INTERVAL_MS
            if (last != null && now() - last < interval) return@launch
            // Not stamped: the next launch checks again (#311).
            if (wifiOnly && !unmetered()) {
                log("Automatic Drive backup waits for Wi-Fi")
                return@launch
            }
            // Not stamped: the next launch tries again, once the store is back.
            autoBackupBlocked()?.let { why ->
                log("Daily Drive backup skipped: $why")
                _state.update { it.copy(autoBackupPaused = "Daily backup paused: $why.") }
                return@launch
            }
            _state.update { it.copy(autoBackupPaused = null) }
            // An install that has backed up before keeps the 10 copies it had.
            val backedUpBefore = last != null || persisted.lastBackupSuccessAtMs != null
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
            // The name carries the counts the retention below compares, and this
            // install's tag. Its prefix still holds "crema-backup", so the Drive
            // list query finds it, and only this prefix is ever pruned, so a
            // manual backup never is.
            val tag = persisted.autoBackupTag
                ?: newAutoBackupTag().also { fresh -> persist { it.copy(autoBackupTag = fresh) } }
            // Not stamped when it can't look: the next launch tries again, as an
            // upload now would push the copy it should keep out of reach.
            if (!pinPreDamageCopy(tokens, autoBackupFileName(backupFileName(), tag, ZERO_COUNTS))) return@launch
            persist { it.copy(lastAutoBackupAtMs = now()) }
            val built = backupZip(true) ?: return@launch
            // Nothing changed since the last upload that landed: no new copy
            // (#311), as long as Drive still holds one of this device's.
            if (built.contentHash != null && built.contentHash == persisted.lastAutoBackupHash && holdsOwnCopy(tokens, tag)) {
                built.release()
                log("Automatic Drive backup skipped: nothing changed since the last one")
                return@launch
            }
            val name = autoBackupFileName(backupFileName(), tag, json.encodeToString(BackupCounts.serializer(), built.counts))
            try {
                withToken(tokens) { uploadBackup(it, name, built.file) }
                // The hash only after it landed, from the data as the build read it.
                persist { it.copy(lastBackupSuccessAtMs = now(), lastAutoBackupHash = built.contentHash) }
                finishRun(backedUp())
                notify("Daily backup saved to Google Drive")
                settleAutoBackups(tokens, name, backedUpBefore)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                val failure = failureOf(e)
                if (isTransient(failure)) persist { it.copy(lastAutoBackupAtMs = last) }
                val changed = finishRun(failed(failure))
                // Everything else stays a Settings line. This one a user can act
                // on, and it won't clear by itself: once, on the way in.
                if (changed && e is NoDriveToken && failure is SyncFailure.Rejected) notify(DRIVE_REFUSED_LINE)
            } finally {
                built.release()
            }
        }
    }

    /** Whether Drive lists one of this device's automatic copies. A list that
     *  fails counts as none, so the backup goes ahead. */
    private suspend fun holdsOwnCopy(tokens: DriveTokenSet, tag: String): Boolean =
        runCatchingCancellable {
            withToken(tokens) { listBackups(it) }.mapNotNull { it.toBackupFile() }.any { it.name.contains("~$tag") }
        }.getOrDefault(false)

    /**
     * Set how many automatic copies this device keeps (#311). The next
     * automatic backup prunes to it; manual backups are never touched.
     */
    fun setAutoBackupKeep(keep: Int) {
        scope.launch { persist { it.copy(autoBackupKeep = keep) } }
    }

    /**
     * How many of this device's automatic copies on Drive [keep] would delete at
     * the next backup, for the preview before a lower limit is saved: the
     * core's retention over the listing, as the next prune runs it. Null when
     * it can't look (signed out, or the list failed).
     */
    suspend fun copiesOverLimit(keep: Int): Int? {
        if (!ownState.await()) return null
        val tag = persisted.autoBackupTag ?: return 0
        val tokens = (freshToken() as? FreshToken.Token)?.set ?: return null
        return runCatchingCancellable {
            val files = withToken(tokens) { listBackups(it) }.mapNotNull { it.toBackupFile() }
            val newestOwn = files.filter { it.name.contains("~$tag") }.maxByOrNull { it.modifiedMs } ?: return@runCatchingCancellable 0
            val filesJson = json.encodeToString(ListSerializer(BackupFile.serializer()), files)
            json.decodeFromString(
                AutoBackupRetention.serializer(),
                autoBackupRetention(filesJson, newestOwn.name, keep.toUInt(), persisted.preDamageCopy),
            ).delete.size
        }.getOrNull()
    }

    /**
     * After an automatic upload: the core's retention over the whole listing
     * ([autoBackupRetention], daily uploads otherwise accumulate ~365 files a
     * year), and a notice if the copy just made is far smaller than the last.
     * Pruned copies go to the trash, so a wrong prune can be undone for 30 days.
     * Best-effort: a failed list or trash just retries after tomorrow's upload.
     */
    private suspend fun settleAutoBackups(tokens: DriveTokenSet, uploaded: String, backedUpBefore: Boolean) {
        runCatchingCancellable {
            val files = withToken(tokens) { listBackups(it) }.mapNotNull { it.toBackupFile() }
            val filesJson = json.encodeToString(ListSerializer(BackupFile.serializer()), files)
            // Chosen once, by the first retention to run: 3 for a new install,
            // now that a copy from before any damage stays too; 10, as it had,
            // for one that backed up before this.
            val keep = persisted.autoBackupKeep
                ?: autoBackupKeepFor(null, backedUpBefore).also { chosen -> persist { it.copy(autoBackupKeep = chosen) } }
            val plan = json.decodeFromString(
                AutoBackupRetention.serializer(),
                autoBackupRetention(filesJson, uploaded, keep.toUInt(), persisted.preDamageCopy),
            )
            plan.shrink?.let { notify(shrinkNotice(it)) }
            withToken(tokens) { token -> plan.delete.forEach { trashFile(token, it) } }
        }.onFailure { log("Drive backup prune failed: ${it.message}") }
    }

    /**
     * A damage the counts can't see, new since the last look: pin this
     * install's newest copy on Drive, by id, for retention to keep. It's from
     * before the damage, as the launch that found it didn't back up, and no
     * clock is compared: the device's and Drive's can disagree, and a damage
     * long since fixed isn't new. False when Drive couldn't be listed.
     */
    private suspend fun pinPreDamageCopy(tokens: DriveTokenSet, nameLike: String): Boolean {
        val damage = damageByStore()
        val seen = persisted.damageSeen
        if (damage.none { (store, at) -> at > (seen[store] ?: Long.MIN_VALUE) }) return true
        return runCatchingCancellable {
            val files = withToken(tokens) { listBackups(it) }.mapNotNull { it.toBackupFile() }
            // A recent pin stays: the copies made since it may all be thin, and
            // a second damage then would move the slot onto one of them. Its
            // age is read on Drive's clock, against the newest copy listed.
            val current = persisted.preDamageCopy?.let { id -> files.find { it.id == id } }
            val newest = files.maxOfOrNull { it.modifiedMs }
            val held = current != null && newest != null && !persisted.pinReleased &&
                newest - current.modifiedMs < PIN_KEEPS_MS
            if (held) {
                current!!.id
            } else {
                newestAutoBackup(json.encodeToString(ListSerializer(BackupFile.serializer()), files), nameLike)
            }
        }.fold(
            onSuccess = { pin ->
                persist {
                    it.copy(damageSeen = seen + damage, preDamageCopy = pin, pinReleased = it.pinReleased && pin == it.preDamageCopy)
                }
                _state.update { it.copy(autoBackupPaused = null) }
                true
            },
            onFailure = {
                log("Daily Drive backup waits: couldn't list Drive to keep the copy from before a damage (${it.message})")
                _state.update { it.copy(autoBackupPaused = "Daily backup paused: couldn't check Google Drive.") }
                false
            },
        )
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
