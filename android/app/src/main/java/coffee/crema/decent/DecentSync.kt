package coffee.crema.decent

import coffee.crema.core.ShotMachine
import coffee.crema.history.StoredShot
import coffee.crema.history.BREW_LOG_UPLOAD_SKIP
import coffee.crema.history.isBrewLog
import coffee.crema.history.pulledFromVisualizer
import coffee.crema.ui.DrainResult
import coffee.crema.ui.TelemetrySample
import coffee.crema.ui.UploadDestination
import coffee.crema.ui.UploadOutcome
import coffee.crema.ui.UploadOutcomeKind
import coffee.crema.ui.UploadTargetId
import coffee.crema.visualizer.SyncLogEntry
import coffee.crema.visualizer.shotMachineOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decent account shot upload (geota/crema#84) — sign-in, the auto-upload
 * pref, and the per-shot push to decentespresso.com's shot history. A
 * self-contained controller like [coffee.crema.visualizer.VisualizerSync]:
 * its [state] is mirrored into `MainUiState.decent`, and the sharing
 * coordination drives it as an [UploadDestination].
 *
 * Gates for the automatic push, in order: an account is linked, auto-upload
 * is on, the shot is at least [MIN_SHOT_SECONDS] long (skips flushes — de1app
 * and decaid use the same floor), and a machine serial is known (stamped on
 * the shot, else the connected DE1 — never for a shot pulled from Visualizer,
 * which may have been pulled on another machine). A manual push skips the
 * auto-upload and length gates.
 *
 * One upload per shot at a time: a second request for a shot already in
 * flight returns without POSTing, and every push re-reads the shot from
 * history first. There is no retry loop here: retries (5xx / 408 / 429 and
 * transport failures, three attempts with backoff, `replace=1` on a resend
 * that may duplicate a stored shot) are the shared HTTP retry policy's job
 * (`net/HttpClients.kt`, applied by [DecentClient]), so each [DecentApi] call
 * returns the FINAL answer. An auth failure flags the account for
 * re-linking; a permanent rejection joins [DecentState.rejectedShotIds] and
 * leaves the backlog; any other failure is recorded and reported.
 */
class DecentSync(
    private val store: DecentStateStore,
    private val client: DecentApi,
    private val scope: CoroutineScope,
    /** Build the ShotRecord JSON (the core converter, [decentShotRecordJson]). */
    private val buildRecord: (shot: StoredShot, machine: ShotMachine, fullSamples: List<TelemetrySample>?) -> String,
    /** The public shot page for a serial + server id (core `decentShotViewUrl`). */
    private val shotViewUrl: (serial: String?, decentId: String?) -> String?,
    /** Surface a user-facing message (the VM's snackbar channel). */
    private val notify: (String) -> Unit,
    /**
     * Persist a successful upload: stamp `decentId` onto the local shot, and
     * [machine] too when the upload used the connected DE1's identity (so its
     * share link names the right serial later).
     */
    private val onShotUploaded: (localId: String, decentId: String, machine: ShotMachine?) -> Unit,
    /** The connected DE1's identity, or null before its serial is known. */
    private val liveMachine: () -> ShotMachine?,
    /** The shot as history holds it NOW (null = deleted) — re-read before every POST. */
    private val currentShot: (localId: String) -> StoredShot?,
    /** The account was just linked — the moment to offer a catch-up of existing shots. */
    private val onLinked: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : UploadDestination {
    companion object {
        const val MIN_SHOT_SECONDS = 5

        /** The drain gives up after this many failures in a row (rejections and 5xx alike; web parity). */
        const val FAILURE_STREAK_LIMIT = 3
        private const val LOG_CAP = 20
    }

    /** What the Settings / History UI binds to. */
    data class UiState(
        val linked: Boolean = false,
        /** The account email — also kept while [needsReauth] so "Sign in again" is prefilled. */
        val email: String? = null,
        val serials: List<String> = emptyList(),
        val autoUpload: Boolean = false,
        val needsReauth: Boolean = false,
        val lastUpload: DecentLastUpload? = null,
        /** True while a sign-in runs. */
        val busy: Boolean = false,
        /** Shot ids with an upload in flight. */
        val uploadingShotIds: Set<String> = emptySet(),
        /** `done to total` while the backlog drain runs; null otherwise. */
        val backlogProgress: Pair<Int, Int>? = null,
        /** The last sign-in failure, cleared on the next attempt. */
        val signInError: String? = null,
        /** Decent's "Recent activity" lines, newest first. */
        val log: List<SyncLogEntry> = emptyList(),
        /** Shots the server refused for good — out of the backlog until re-uploaded by hand. */
        val rejectedShotIds: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile
    private var persisted = DecentState()
    private val persistMutex = Mutex()

    /** Shot ids with an upload running — checked and claimed atomically. */
    private val inFlight = HashSet<String>()

    /** One backlog pass at a time. */
    private val drainMutex = Mutex()

    override var onUploadOutcome: ((shotId: String, outcome: UploadOutcome) -> Unit)? = null

    /** Hydrate from disk at startup (called from the VM's init coroutine). */
    suspend fun load() {
        persisted = store.load()
        fold()
    }

    private fun fold() {
        val p = persisted
        _state.update {
            it.copy(
                linked = p.linked,
                email = p.email,
                serials = p.serials,
                autoUpload = p.autoUpload,
                needsReauth = p.needsReauth,
                lastUpload = p.lastUpload,
                log = p.log,
                rejectedShotIds = p.rejectedShotIds,
            )
        }
    }

    /** Apply + store [mutate]; false when the write failed (the in-memory state still applies). */
    private suspend fun persist(mutate: (DecentState) -> DecentState): Boolean {
        val ok = persistMutex.withLock {
            persisted = mutate(persisted)
            store.save(persisted)
        }
        fold()
        return ok
    }

    private fun DecentState.logged(direction: String, shot: StoredShot, error: String? = null): DecentState {
        val entry = SyncLogEntry(
            destination = UploadTargetId.Decent,
            direction = direction,
            entity = "shot",
            id = shot.id,
            name = shot.profileName ?: "Shot",
            at = now(),
            error = error,
        )
        return copy(log = (listOf(entry) + log).take(LOG_CAP))
    }

    // ── Account ─────────────────────────────────────────────────────────────

    /**
     * Exchange email + password for the account token, then fetch the
     * account's machines — both must succeed to link. Linking is the
     * affirmative choice (de1app semantics): auto-upload turns on with it.
     */
    fun signIn(email: String, password: String) {
        val e = email.trim()
        if (e.isEmpty() || password.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, signInError = null) }
        scope.launch {
            try {
                val token = client.login(e, password)
                if (token == null) {
                    _state.update { it.copy(busy = false, signInError = "Decent didn’t accept that email + password.") }
                    return@launch
                }
                val serials = try {
                    client.fetchMachines(e, token).map { it.serial }
                } catch (err: DecentError.Auth) {
                    _state.update { it.copy(busy = false, signInError = "Decent accepted the password but not the login token — try again.") }
                    return@launch
                }
                val saved = persist {
                    DecentState(email = e, token = token, serials = serials, autoUpload = true, log = it.log)
                }
                _state.update { it.copy(busy = false) }
                notify(if (saved) "Linked Decent account $e" else "Linked Decent account $e — but couldn’t save the login on this device")
                onLinked()
            } catch (c: CancellationException) {
                _state.update { it.copy(busy = false) }
                throw c
            } catch (err: Exception) {
                _state.update { it.copy(busy = false, signInError = err.message ?: "Sign-in failed") }
            }
        }
    }

    /** Forget the linked account — the token, email, serials and last-upload note. */
    fun signOut() = scope.launch {
        persist { DecentState(log = it.log) }
        _state.update { it.copy(signInError = null) }
    }

    fun setAutoUpload(enabled: Boolean) = scope.launch { setAutoUploadNow(enabled) }

    override suspend fun setAutoUploadNow(enabled: Boolean) {
        persist { it.copy(autoUpload = enabled) }
    }

    // ── UploadDestination ───────────────────────────────────────────────────

    override val id: UploadTargetId get() = UploadTargetId.Decent
    override val enabled: Boolean get() = persisted.linked && !persisted.needsReauth
    override val autoUpload: Boolean get() = persisted.autoUpload

    override fun isUploaded(shot: StoredShot): Boolean = shot.decentId != null

    /** The public page when there is one, else the account's shot history. */
    override fun viewUrl(shot: StoredShot): String? =
        shot.decentId?.let { shareUrl(shot) ?: DECENT_HISTORY_URL }

    override fun shareUrl(shot: StoredShot): String? = shotViewUrl(shot.machineSerial, shot.decentId)

    /**
     * A shot pulled from Visualizer can go only with its own stamped serial;
     * a Brew Log row (issue #10) never goes — it is not a DE1 shot.
     */
    override fun canUpload(shot: StoredShot): Boolean =
        !shot.isBrewLog && (!shot.pulledFromVisualizer || shotMachineOf(shot) != null)

    override fun inBacklog(shot: StoredShot): Boolean =
        shot.decentId == null &&
            shot.durationMs >= MIN_SHOT_SECONDS * 1000L &&
            canUpload(shot) &&
            shot.id !in persisted.rejectedShotIds

    override fun maybeAutoUpload(shot: StoredShot, fullSamples: List<TelemetrySample>?): Boolean {
        val p = persisted
        if (!p.linked || !p.autoUpload || p.needsReauth) return false
        if (shot.isBrewLog) return false
        if (shot.durationMs < MIN_SHOT_SECONDS * 1000L) return false
        if (machineFor(shot) == null) return false
        return uploadShot(shot, manual = false, fullSamples = fullSamples)
    }

    override fun pushShot(shot: StoredShot, replace: Boolean): Boolean =
        uploadShot(shot, manual = true, replace = replace)

    // ── Upload ──────────────────────────────────────────────────────────────

    sealed class Outcome {
        data class Uploaded(val id: String?, val url: String?) : Outcome()
        data class Skipped(val reason: String, val alreadyRunning: Boolean = false) : Outcome()
        data class Failed(val error: DecentError) : Outcome()
    }

    private fun claim(shotId: String): Boolean {
        val claimed = synchronized(inFlight) { inFlight.add(shotId) }
        if (claimed) _state.update { it.copy(uploadingShotIds = it.uploadingShotIds + shotId) }
        return claimed
    }

    private fun release(shotId: String) {
        synchronized(inFlight) { inFlight.remove(shotId) }
        _state.update { it.copy(uploadingShotIds = it.uploadingShotIds - shotId) }
    }

    /** The stamped machine, else (for a shot recorded here) the connected DE1. Second = it came from the live DE1. */
    private fun resolveMachine(shot: StoredShot): Pair<ShotMachine, Boolean>? {
        shotMachineOf(shot)?.let { return it to false }
        if (shot.pulledFromVisualizer) return null
        return liveMachine()?.takeIf { it.serialNumber.isNotBlank() }?.let { it to true }
    }

    private fun machineFor(shot: StoredShot): ShotMachine? = resolveMachine(shot)?.first

    /**
     * Upload one shot in the background; `manual` skips the auto-upload +
     * length gates (an explicit tap must not be vetoed by them) and clears a
     * recorded rejection, `replace` re-uploads over the server's copy.
     * Returns false without doing anything when the shot is already in flight.
     */
    fun uploadShot(
        shot: StoredShot,
        manual: Boolean = true,
        replace: Boolean = false,
        fullSamples: List<TelemetrySample>? = null,
    ): Boolean {
        if (!persisted.linked) {
            if (manual) notify("Link your Decent account first (Settings → Sharing)")
            return false
        }
        if (!claim(shot.id)) return false
        scope.launch {
            val outcome = try {
                uploadClaimed(shot, manual, replace, fullSamples, clearRejection = manual)
            } finally {
                release(shot.id)
            }
            report(shot.id, outcome, manual)
        }
        return true
    }

    private fun report(shotId: String, outcome: Outcome, manual: Boolean) {
        val sink = onUploadOutcome
        if (sink != null) {
            sink(
                shotId,
                when (outcome) {
                    is Outcome.Uploaded -> UploadOutcome(UploadOutcomeKind.Uploaded)
                    is Outcome.Failed -> UploadOutcome(UploadOutcomeKind.Failed, outcome.error.message)
                    is Outcome.Skipped -> UploadOutcome(UploadOutcomeKind.Skipped, outcome.reason)
                },
            )
            return
        }
        when (outcome) {
            is Outcome.Uploaded -> notify("Shot uploaded to your Decent account")
            is Outcome.Failed -> notify("Decent upload failed: ${outcome.error.message}")
            // Auto-path skips (too short / no serial) are expected, not errors.
            is Outcome.Skipped -> if (manual) notify("Not uploaded: ${outcome.reason}")
        }
    }

    /** Upload one shot and await the outcome (the backlog drain; tests). */
    internal suspend fun uploadNow(
        shot: StoredShot,
        manual: Boolean,
        replace: Boolean,
        fullSamples: List<TelemetrySample>?,
        clearRejection: Boolean = false,
    ): Outcome {
        if (!claim(shot.id)) return Outcome.Skipped("Already uploading", alreadyRunning = true)
        try {
            return uploadClaimed(shot, manual, replace, fullSamples, clearRejection)
        } finally {
            release(shot.id)
        }
    }

    private suspend fun uploadClaimed(
        snapshot: StoredShot,
        manual: Boolean,
        replace: Boolean,
        fullSamples: List<TelemetrySample>?,
        clearRejection: Boolean,
    ): Outcome {
        val p = persisted
        val email = p.email
        val token = p.token
        if (!p.linked || email == null || token == null) return Outcome.Skipped("No Decent account linked")
        if (!manual && !p.autoUpload) return Outcome.Skipped("Auto-upload is off")
        if (p.needsReauth) return Outcome.Skipped("Decent login needs re-linking")
        // The caller's copy may be stale (a second tap, a drain racing a live push).
        val shot = currentShot(snapshot.id) ?: return Outcome.Skipped("Shot no longer in history")
        // Brew Log rows (issue #10): never uploaded, and never stamped with
        // the live DE1 (which the bind after a successful POST would do).
        if (shot.isBrewLog) return Outcome.Skipped(BREW_LOG_UPLOAD_SKIP)
        if (shot.decentId != null && !replace) return Outcome.Skipped("Already on Decent")
        if (!manual && shot.durationMs < MIN_SHOT_SECONDS * 1000L) return Outcome.Skipped("Shorter than $MIN_SHOT_SECONDS s")
        val (machine, fromLive) = resolveMachine(shot) ?: return Outcome.Skipped(
            if (shot.pulledFromVisualizer) "Pulled from Visualizer without a DE1 serial" else "No DE1 serial number known — connect the machine first",
        )
        if (clearRejection && shot.id in p.rejectedShotIds) persist { it.copy(rejectedShotIds = it.rejectedShotIds - shot.id) }

        val record = try {
            buildRecord(shot, machine, fullSamples)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return failed(DecentError.Invalid(e.message ?: e.javaClass.simpleName), shot)
        }

        // One call: the HTTP layer has already retried whatever was retryable.
        try {
            val result = client.uploadShot(email, token, record, replace)
            val url = shotViewUrl(machine.serialNumber, result.id)
            // No id in the answer: bound with a placeholder, so it leaves the
            // backlog — but no share link is offered for it (url stays null).
            onShotUploaded(shot.id, result.id ?: "uploaded:${now()}", if (fromLive) machine else null)
            persist {
                it.copy(
                    needsReauth = false,
                    lastUpload = DecentLastUpload(now(), ok = true, message = "Uploaded", url = url),
                    rejectedShotIds = it.rejectedShotIds - shot.id,
                ).logged("push", shot)
            }
            return Outcome.Uploaded(result.id, url)
        } catch (c: CancellationException) {
            throw c
        } catch (e: DecentError.Auth) {
            persist {
                it.copy(needsReauth = true, lastUpload = DecentLastUpload(now(), ok = false, message = e.message ?: "auth"))
                    .logged("skip", shot, e.message)
            }
            return Outcome.Failed(e)
        } catch (e: DecentError.Rejected) {
            persist { it.copy(rejectedShotIds = it.rejectedShotIds + shot.id) }
            return failed(e, shot)
        } catch (e: DecentError) {
            return failed(e, shot)
        }
    }

    private suspend fun failed(e: DecentError, shot: StoredShot): Outcome {
        persist {
            it.copy(lastUpload = DecentLastUpload(now(), ok = false, message = e.message ?: "failed")).logged("skip", shot, e.message)
        }
        return Outcome.Failed(e)
    }

    /**
     * Drain the backlog oldest-first, awaiting the whole pass. Stops at an auth
     * failure, at the first offline network error (the rest would fail the
     * same way), and after [FAILURE_STREAK_LIMIT] failures in a row of any
     * kind. A second call while a pass runs returns at once.
     */
    override suspend fun uploadUnsentNow(shots: List<StoredShot>): DrainResult {
        if (!drainMutex.tryLock()) return DrainResult(stopped = "A Decent upload pass is already running")
        try {
            if (!enabled) return DrainResult()
            val backlog = unsent(shots).sortedBy { it.completedAtMs }
            if (backlog.isEmpty()) return DrainResult()
            _state.update { it.copy(backlogProgress = 0 to backlog.size) }
            var uploaded = 0
            var failed = 0
            var skipped = 0
            var streak = 0
            var stopped: String? = null
            for ((i, shot) in backlog.withIndex()) {
                when (val o = uploadNow(shot, manual = true, replace = false, fullSamples = null)) {
                    is Outcome.Uploaded -> { uploaded++; streak = 0 }
                    is Outcome.Skipped -> skipped++
                    is Outcome.Failed -> {
                        failed++
                        val e = o.error
                        streak++
                        stopped = when {
                            e is DecentError.Auth -> "Decent login needs re-linking"
                            e.offline -> "Offline"
                            streak >= FAILURE_STREAK_LIMIT -> "$streak uploads failed in a row"
                            else -> null
                        }
                    }
                }
                _state.update { it.copy(backlogProgress = (i + 1) to backlog.size) }
                if (stopped != null) break
            }
            return DrainResult(uploaded, failed, skipped, stopped)
        } finally {
            _state.update { it.copy(backlogProgress = null) }
            drainMutex.unlock()
        }
    }
}
