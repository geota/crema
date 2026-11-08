package coffee.crema.visualizer

import coffee.crema.core.CallbackOutcome
import coffee.crema.core.pullCoversAttempt
import coffee.crema.core.accountChangeAction
import coffee.crema.core.CataloguePage
import coffee.crema.core.callbackOutcome
import coffee.crema.core.ShotMergeInput
import coffee.crema.core.ShotPatch
import coffee.crema.core.ShotPatchInputs
import coffee.crema.core.parseCatalogueCoffeeBags
import coffee.crema.core.parseCatalogueRoasters
import coffee.crema.core.CatalogueRoasterPage
import coffee.crema.runCatchingCancellable
import coffee.crema.core.exportV2JsonShot
import coffee.crema.core.exportV2JsonShotFull
import coffee.crema.core.signatureForShot
import coffee.crema.core.uploadRefusedNoSamples
import coffee.crema.core.visualizerShotPatchJson
import coffee.crema.core.VisualizerSyncPrefs
import coffee.crema.history.StoredShot
import coffee.crema.history.effectiveGrindSetting
import coffee.crema.history.historyFullNotice
import coffee.crema.history.isBrewLog
import coffee.crema.sync.OwnStateGate
import coffee.crema.sync.SingleFlightRefresh
import coffee.crema.ui.DrainResult
import coffee.crema.ui.TelemetrySample
import coffee.crema.ui.UploadDestination
import coffee.crema.ui.UploadOutcome
import coffee.crema.ui.UploadOutcomeKind
import coffee.crema.ui.UploadTargetId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean

/** Why a shot sync stopped: this takes the user, not a moment's wait. */
internal const val HISTORY_REFUSED_NOTICE =
    "Your shot history couldn't be read, so shots aren't syncing with Visualizer. Use Try again on the banner."

/*
 * The Visualizer sync controller — sign-in lifecycle, token freshness, and
 * shot upload. The Android home for what the web splits across
 * `token-vault` / `visualizer-call` / `shot-sync` / `sync-config`.
 *
 * Deliberately NOT part of MainViewModel (the tracked god-object debt): the
 * VM instantiates one, folds [state] into MainUiState, and forwards UI
 * intents. Everything here is self-contained over [VisualizerStore] +
 * [VisualizerClient].
 *
 * Scope: sign in (PKCE via the system browser), `/me` account, per-shot +
 * bulk upload with the same wire bytes as the web (core `export_v2_json_shot`
 * over the FFI + the `metadata.crema{localId, signature}` escape valve), shot
 * pull/reconcile, and the bean / roaster sync ([BeanSyncRunner], the web
 * `BeanSync.runSync`) run first by "Sync now", as on the web.
 */
class VisualizerSync(
    private val store: VisualizerStore,
    private val client: VisualizerClient,
    private val json: Json,
    private val scope: CoroutineScope,
    /** Doorkeeper application client_id (BuildConfig); blank = not configured. */
    private val clientId: String,
    /** Crema app version, stamped into `metadata.crema.appVersion`. */
    private val appVersion: String,
    /** Surface a user-facing message (the VM's snackbar channel). */
    private val notify: (String) -> Unit,
    /** Persist a successful upload: stamp `visualizerId` onto the local shot. */
    private val onShotSynced: (localId: String, visualizerId: String) -> Unit,
    /** The user's equipment-level grinder model (Settings → Machine); null = unset. */
    private val grinderModel: () -> String? = { null },
    /** Insert pulled remote stubs into the local history (dedup by id); answers the ids a full history refused. */
    private val onPulledShots: (List<StoredShot>) -> Set<String> = { emptySet() },
    /**
     * Apply what a pull may change on a bound local (the core's
     * `merge_remote_shot`), in one write: tags it adds, a barista into a blank
     * one, the curve or recipe when the row has none, then the remote version.
     */
    private val onApplyRemote: (localId: String, patch: ShotPatch, pulled: PulledParts) -> Unit = { _, _, _ -> },
    /** Core `merge_remote_shot_json` (injected so a JVM test can watch it). */
    private val mergeRemote: (payloadJson: String) -> String = { coffee.crema.core.mergeRemoteShot(it) },
    /** Core `pull_run_call`: the pull run's rules by name, JSON in and out. */
    private val pullRunCall: (op: String, payload: String) -> String = { op, p -> coffee.crema.core.pullRunCall(op, p) },
    /** How a pacing or rate-limit wait is slept (a JVM test records it instead). */
    private val paceDelay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    /** Sign-in just completed — the moment to offer a catch-up of existing shots. */
    private val onSignedIn: () -> Unit = {},
    /** The shot as history holds it now (null = deleted) — re-read before a backlog POST. */
    private val currentShot: (localId: String) -> StoredShot? = { null },
    /** The core's quota / backoff policy (injected so JVM tests needn't load the native core). */
    private val uploadPolicy: UploadPolicy = UploadPolicy.core(),
    /**
     * Core `visualizer_shot_patch_json(inputs, premium)`: the edit-sync PATCH
     * body fitted to the account tier, or null = skip (injected so JVM tests
     * needn't load the native core).
     */
    private val shotPatchBody: (inputsJson: String, premium: Boolean?) -> String? =
        { inputs, premium -> visualizerShotPatchJson(inputs, premium) },
    /** Wall clock (unix ms) — the daily Premium re-probe gate; tests pin it. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Core `parse_catalogue_coffee_bags`: raw catalogue body → [CataloguePage] (injected so JVM tests needn't load the native core). */
    private val parseCatalogue: (bodyJson: String) -> CataloguePage =
        { body -> json.decodeFromString(CataloguePage.serializer(), parseCatalogueCoffeeBags(body)) },
    /** Core `parse_catalogue_roasters`: raw body → [CatalogueRoasterPage] (injected for JVM tests). */
    private val parseCatalogueRoasterPage: (bodyJson: String) -> CatalogueRoasterPage =
        { body -> json.decodeFromString(CatalogueRoasterPage.serializer(), parseCatalogueRoasters(body)) },
    /** The OAuth code → token exchange (injected so JVM tests can drive a sign-in). */
    private val exchangeCode: suspend (code: String, verifier: String) -> TokenSet =
        { code, verifier -> exchangeCodeForToken(clientId, code, verifier, json) },
    /** The refresh-token grant (injected so JVM tests can drive the 401 → refresh → retry). */
    private val refreshTokens: suspend (refreshToken: String) -> TokenSet =
        { refresh -> refreshAccessToken(clientId, refresh, json) },
    /** The bean library as it stands now. */
    private val beanLibrary: () -> coffee.crema.beans.BeanLibrary = { coffee.crema.beans.BeanLibrary() },
    /** Apply a bean sync's change to the current rows. */
    private val editBeanLibrary: ((coffee.crema.beans.BeanLibrary) -> coffee.crema.beans.BeanLibrary) -> Unit = {},
    /** A bean sync ended: its changes are in the library, to be stored. */
    private val onBeansSynced: () -> Unit = {},
    /** The core's bean-sync surface (injected so JVM tests needn't load the native core). */
    private val beanSyncCore: BeanSyncCore = BeanSyncCore.Native,
    /** When a network comes back (F17's retry trigger); null = no trigger (JVM tests). */
    private val networkWatcher: NetworkWatcher? = null,
    /** Run the retry queue's timers (the scheduled retry and the foreground
     *  tick); JVM tests turn them off and drain by hand. */
    private val retryTimers: Boolean = true,
    /** Waits for the stored shots' telemetry to load (F10 validation P1-2):
     *  an upload or a pull before it would see shots without their series. */
    private val seriesReady: suspend () -> Unit = {},
    /** How the retry timers wait (injected so a JVM test can see each wait). */
    private val retryDelay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    /** Core `upload_refused_no_samples`: a machine shot with no telemetry doesn't go. */
    private val uploadRefused: (isBrewLog: Boolean, sampleCount: UInt?) -> Boolean = ::uploadRefusedNoSamples,
    /** Whether the stored shots are in the history; a list taken before then is empty. */
    private val shotsLoaded: () -> Boolean = { true },
    /** The shot log couldn't be read: shots wait for the user, not for a load. */
    private val historyRefused: () -> Boolean = { false },
    /** A restore is applying: a pull waits for it, as its rows aren't settled (restore-safety §8). */
    private val restoreRunning: () -> Boolean = { false },
    /** Core `callback_outcome`: what a redirect means for the stored handshake. */
    private val callbackRule: (expectedState: String?, verifier: String?, code: String?, returnedState: String?, error: String?) -> CallbackOutcome =
        ::callbackOutcome,
    /** How long a run waits for the rows before it skips (tests shrink it). */
    private val rowsWaitMs: Long = ROWS_WAIT_MS,
    /** Every shot as history holds it now: the backlog a different account is asked about. */
    private val allShots: () -> List<StoredShot> = { emptyList() },
    /** Revoke an access token at the server, best-effort (injected for JVM tests). */
    private val revoke: suspend (accessToken: String) -> Unit = { revokeToken(clientId, it) },
) : UploadDestination {

    internal companion object {
        /** Backstop on the pull walk — mirrors the web's maxPages default. */
        const val MAX_PULL_PAGES = 500

        /** Rows a run's list page asks for: the most Visualizer gives. */
        const val RUN_PAGE_ITEMS = 100

        /** How long a run waits for the rows before it skips. */
        const val ROWS_WAIT_MS = 30_000L

        /** The app-start Premium re-probe runs at most this often (web `PREMIUM_REFRESH_INTERVAL_MS`). */
        const val PREMIUM_REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** Why nothing goes up while the account is unknown or a change of it unanswered. */
        const val ACCOUNT_HELD_NOTICE = "Visualizer is waiting on your account. See Settings → Sharing."

        /** The retry queue's foreground tick (web `UploadQueue`'s 5 minutes). */
        const val RETRY_TICK_MS = 5L * 60 * 1000

        /** A network coming back drains at most this often, so a flapping
         *  link can't drain in a loop. */
        const val NETWORK_DRAIN_SPACING_MS = 30_000L

        /** A queued shot another upload holds is retried this much later. */
        const val CLAIMED_RETRY_MS = 5_000L

        /** The scheduled drain never runs sooner than this, so an entry left
         *  due (a stopped pass) can't make the drain spin. */
        const val MIN_DRAIN_DELAY_MS = 5_000L
    }

    /** What the Settings/History UI binds to. */
    data class UiState(
        /** False until a Doorkeeper client_id is baked into the build. */
        val configured: Boolean = false,
        val signedIn: Boolean = false,
        val account: VisualizerAccount? = null,
        val autoSync: Boolean = true,
        val privacy: String = "unlisted",
        val includeProfile: Boolean = true,
        val includeNotes: Boolean = false,
        val lastShotSyncAt: Long? = null,
        /** Shots sync direction: `"off" | "backup" | "pull" | "two-way"`. */
        val shotsDirection: String = "backup",
        /** Beans / roasters sync directions (same four values; the push side needs Premium). */
        val beansDirection: String = "two-way",
        val roastersDirection: String = "two-way",
        /** Cached Premium tier: false = free (bag / roaster writes locked), null = not probed. */
        val premium: Boolean? = null,
        /** Unix ms of the last completed bean / roaster sync, or null. */
        val beanLastSyncAt: Long? = null,
        /** Recent sync activity (capped 20, newest first). */
        val log: List<SyncLogEntry> = emptyList(),
        /** The server ended the session: signed out, and Settings asks to sign in again. */
        val needsReconnect: Boolean = false,
        /** Signed in, but `/me` hasn't said as whom yet, so nothing is sent. */
        val accountUnconfirmed: Boolean = false,
        /** Signed in as a different account: uploads wait for [answerAccountChange]. */
        val accountChangeAsk: Boolean = false,
        /** True while a sign-in exchange or bulk upload runs. */
        val busy: Boolean = false,
        /** True while a Sync-now pull/push pass runs. */
        val syncing: Boolean = false,
        /** Set while a pull sits out Visualizer's rate limit: when it goes on (unix ms). */
        val pullWaitingUntil: Long? = null,
        /** Shot ids with an upload in flight (History pip spinners). */
        val uploadingShotIds: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(UiState(configured = clientId.isNotBlank()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile
    private var persisted = VisualizerState()
    private val persistMutex = Mutex()

    /** Holds everything that touches [persisted] until [load] has read the file. */
    private val ownState = OwnStateGate("Visualizer", notify)

    /** The rows' late arrival has been reported (see [rowsReady]). */
    private val rowsLateTold = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Shots deleted here this session with no id, so an upload holding one stops short. */
    private val deletedHere: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Shot ids with an upload running — checked and claimed atomically. */
    private val inFlight = HashSet<String>()

    /** One backlog pass at a time. */
    private val drainMutex = Mutex()

    /**
     * Per-shot upload outcome for the cross-destination notice (one snackbar
     * for Visualizer + Decent together). When set, [uploadShot] reports here
     * instead of raising its own snackbar.
     */
    override var onUploadOutcome: ((shotId: String, outcome: UploadOutcome) -> Unit)? = null

    // ── UploadDestination ────────────────────────────────────────────────────

    override val id: UploadTargetId get() = UploadTargetId.Visualizer
    override val enabled: Boolean get() = persisted.tokens != null && !uploadsHeld && directionPushes(persisted.prefs.shotsDirection)

    /** `/me` still has to say whose this session is. */
    private val accountToAsk: Boolean
        get() = persisted.accountUnconfirmed || (persisted.syncAccountId == null && persisted.tokens != null)

    /** Nothing goes up until the account is known and any change of it is answered. */
    private val uploadsHeld: Boolean get() = persisted.accountUnconfirmed || persisted.accountChangeAsk
    override val autoUpload: Boolean get() = persisted.prefs.autoUpload
    override fun isUploaded(shot: StoredShot): Boolean = shot.visualizerId != null
    override fun viewUrl(shot: StoredShot): String? = shot.visualizerId?.let { "https://visualizer.coffee/shots/$it" }
    /** Brew Log rows (issue #10) are local-only — never in the Visualizer backlog. */
    override fun inBacklog(shot: StoredShot): Boolean =
        shot.visualizerId == null && !shot.isBrewLog && !noTelemetry(shot) && !excluded(shot)

    /** The user kept this shot off the account signed in now. */
    private fun excluded(shot: StoredShot): Boolean =
        persisted.uploadExcludedBefore?.let { shot.completedAtMs < it } ?: false

    /** A machine shot no upload can take: no telemetry, now the series have settled. */
    fun noTelemetry(shot: StoredShot): Boolean =
        shot.visualizerId == null && shot.samples.isEmpty() && shotsSettled && uploadRefused(shot.isBrewLog, 0u)

    /** The series load has ended; until then a row's curve may be on its way. */
    val shotsSettled: Boolean get() = shotsLoaded() && !historyRefused()
    override fun canUpload(shot: StoredShot): Boolean = !shot.isBrewLog
    override fun pushShot(shot: StoredShot, replace: Boolean): Boolean = uploadShot(shot, silent = false)
    override suspend fun setAutoUploadNow(enabled: Boolean) {
        persist { it.copy(prefs = it.prefs.copy(autoUpload = enabled)) }
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

    /**
     * Hydrate from disk at startup (its own coroutine, so nothing in the row
     * load can strand a sign-in). An edit marked before this finished keeps its
     * mark, merged with the stored ones and stored here (round-3 clean check
     * C3 #9). The old `baristaPending` set migrates in.
     */
    suspend fun load() {
        val unstored = try {
            persistMutex.withLock {
                // Seeded, so a reconnect as someone else is noticed even though
                // this device never stored the id before.
                persisted = store.load().let { it.copy(syncAccountId = it.syncAccountId ?: it.account?.id?.ifBlank { null }) }
                pacer.restore(persisted.paceBucket)
                ownState.opened()
                val stored = persisted.pendingEdits.toMutableMap()
                for (id in persisted.baristaPending) stored[id] = (stored[id] ?: PendingEdit()).with(PendingEdit(barista = true))
                val early = pendingEdits.toMap()
                pendingEdits.clear()
                pendingEdits.putAll(stored)
                for ((id, edit) in early) pendingEdits.merge(id, edit) { a, b -> a.with(b) }
                persisted.baristaPending.isNotEmpty() || pendingEdits != persisted.pendingEdits
            }
        } finally {
            ownState.failed()
        }
        fold()
        if (unstored) storePendingEdits()
    }

    private fun fold() {
        val p = persisted
        _state.update {
            it.copy(
                configured = clientId.isNotBlank(),
                signedIn = p.tokens != null,
                needsReconnect = p.needsReconnect,
                accountUnconfirmed = p.accountUnconfirmed,
                accountChangeAsk = p.accountChangeAsk,
                account = p.account,
                // Android's single auto gate is the shot auto-upload (`autoUpload`).
                autoSync = p.prefs.autoUpload,
                privacy = p.prefs.privacy,
                includeProfile = p.prefs.includeProfile,
                includeNotes = p.prefs.includeNotes,
                lastShotSyncAt = p.lastShotSyncAt,
                shotsDirection = p.prefs.shotsDirection,
                beansDirection = p.prefs.beansDirection,
                roastersDirection = p.prefs.roastersDirection,
                premium = p.premium,
                beanLastSyncAt = p.beanLastSyncAt,
                log = p.log,
            )
        }
    }

    /** Apply + store [mutate]; false when the write failed (the in-memory state still applies). */
    private suspend fun persist(mutate: (VisualizerState) -> VisualizerState): Boolean {
        if (!ownState.await()) return false
        val ok = persistMutex.withLock {
            // New tokens replace a sign-in the keystore couldn't open at start.
            persisted = mutate(persisted).let { if (it.tokens != null && it.unopened != null) it.copy(unopened = null) else it }
            store.save(persisted)
        }
        fold()
        return ok
    }

    // ── Whole-app backup (sync PREFERENCES only — never the token) ─────────────

    /**
     * The user's Visualizer sync preferences for a whole-app backup — the shared
     * core [VisualizerSyncPrefs] shape, serialised byte-identically with the web
     * shell so a backup's `visualizerPrefs` line moves between devices/shells.
     * NEVER the OAuth token, account, in-flight handshake, cursors, or log —
     * those are per-device runtime state (re-auth after a restore).
     */
    fun backupPrefs(): VisualizerSyncPrefs = persisted.prefs

    /**
     * Apply restored Visualizer sync preferences from a backup's `visualizerPrefs`
     * line — overwrites [VisualizerState.prefs], preserving the current tokens /
     * account / in-flight handshake / cursors / log.
     */
    suspend fun restorePrefs(prefs: VisualizerSyncPrefs) {
        persist { it.copy(prefs = prefs) }
    }

    // ── Sign-in lifecycle ────────────────────────────────────────────────────

    /**
     * Begin the PKCE handshake: mint verifier + state, persist them (the
     * process can die while the browser is up), and hand the authorize URL to
     * [openUrl] (an ACTION_VIEW launcher).
     */
    fun beginSignIn(openUrl: (String) -> Unit) {
        if (clientId.isBlank()) {
            notify("Visualizer isn’t configured in this build (missing client id)")
            return
        }
        val verifier = generateCodeVerifier()
        val csrf = randomState()
        scope.launch {
            if (!ownState.await()) return@launch
            persist { it.copy(pendingVerifier = verifier, pendingState = csrf) }
            openUrl(buildAuthorizeUrl(clientId, csrf, codeChallengeFromVerifier(verifier)))
        }
    }

    /**
     * Complete the handshake from the `crema://visualizer/callback` redirect.
     * Verifies CSRF state, exchanges the code (single-use verifier), fetches
     * `/me`, persists the lot.
     */
    fun handleCallback(code: String?, returnedState: String?, error: String?) {
        scope.launch {
            if (!ownState.await()) return@launch
            val verifier = persisted.pendingVerifier
            val outcome = callbackRule(persisted.pendingState, verifier, code, returnedState, error)
            // Any app or page can fire `crema://visualizer/...`, and a cold
            // start or a recreated activity can replay one: with no handshake
            // of ours it writes nothing, and one in progress survives it.
            if (outcome == CallbackOutcome.NotOurs) return@launch
            // Single-use — clear the handshake whatever happens next.
            persist { it.copy(pendingVerifier = null, pendingState = null) }
            when (outcome) {
                CallbackOutcome.NotOurs -> Unit
                CallbackOutcome.Cancelled -> notify("Visualizer sign-in was cancelled")
                is CallbackOutcome.Refused -> notify("Visualizer sign-in failed: ${outcome.error}")
                CallbackOutcome.NoCode -> notify("Visualizer sign-in failed — no code returned")
                CallbackOutcome.Exchange -> {
                    code ?: return@launch
                    verifier ?: return@launch
                    _state.update { it.copy(busy = true) }
                    runCatchingCancellable { exchangeCode(code, verifier) }
                        .onSuccess { tokens ->
                            // The hold lands with the tokens, so nothing can upload
                            // before `/me` has said whose they are.
                            val saved = persist { it.copy(tokens = tokens, needsReconnect = false, accountUnconfirmed = true) }
                            if (!saved) notify("Couldn’t save the Visualizer login on this device")
                            val known = confirmAccount()
                            refreshPremium()
                            notify(
                                when {
                                    known -> "Signed in to Visualizer${persisted.account?.let { a -> " as ${a.name}" }.orEmpty()}"
                                    else -> "Signed in to Visualizer. Nothing syncs until Crema can check the account."
                                },
                            )
                        }
                        .onFailure { notify("Visualizer sign-in failed: ${it.message}") }
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    /** Revoke (best-effort) + forget tokens and the cached account. [quiet]
     *  skips the snackbar (Erase all data reports once for everything). */
    fun signOut(quiet: Boolean = false) {
        scope.launch {
            if (!ownState.await()) return@launch
            // Forget first, then revoke from the in-memory copy: the revoke can
            // take up to 20 s, and a kill in that window must not leave the
            // token on disk (final review 04 P3-3).
            val token = persisted.tokens?.accessToken
            // The tier is per-account: a later sign-in re-probes it.
            // Also forgets a stored sign-in the keystore couldn't open this start.
            persist {
                it.copy(
                    tokens = null, account = null, premium = null, premiumCheckedAt = null, unopened = null,
                    needsReconnect = false, accountUnconfirmed = false,
                )
            }
            if (!quiet) notify("Signed out of Visualizer")
            token?.let { revoke(it) }
        }
    }

    /**
     * Erase all data: reset EVERYTHING this store keeps (sign-in, sharing
     * settings, sync cursors, the activity log with its shot and bean names)
     * and delete its file, then revoke the old token from the in-memory copy
     * (final review 04 P3-3). The revoke runs on [scope] after the wipe.
     */
    suspend fun eraseAll() {
        // The token to revoke is in the file. The wait is bounded, but a load
        // that never ends still holds the lock below, so the erase waits for it.
        ownState.settle()
        val token = persistMutex.withLock {
            val t = persisted.tokens?.accessToken
            persisted = VisualizerState()
            pendingEdits.clear()
            store.erase()
            ownState.opened()
            t
        }
        fold()
        token?.let { scope.launch { revoke(it) } }
    }

    /** Re-fetch `/me` (Settings open). Silent on failure — the cache stands. */
    fun refreshAccount() {
        if (persisted.tokens == null) return
        scope.launch { confirmAccount() }
    }

    /**
     * Ask `/me` who this session is, and settle what that means for what this
     * device holds (core `account_change_action`). False when it didn't answer,
     * or named no one: the hold stays, and the drain asks again.
     */
    private suspend fun confirmAccount(): Boolean {
        val account = runCatchingCancellable { withFreshToken { client.fetchAccount(it) } }.getOrNull()
            ?.takeIf { it.id.isNotBlank() } ?: return false
        val wasHeld = persisted.accountUnconfirmed
        var changed = false
        persist { st ->
            val settled = st.copy(account = account, syncAccountId = account.id, accountUnconfirmed = false)
            changed = accountChangeAction(st.syncAccountId, account.id) == "Changed"
            if (changed) settled.forNewAccount(oldId = st.syncAccountId, newId = account.id) else settled
        }
        if (changed) {
            pendingEdits.clear()
            notify("This is a different Visualizer account. Nothing uploads until you choose in Settings → Sharing.")
        }
        if (wasHeld || changed) {
            // On a cold start the rows can still be loading, and the backlog
            // counted next needs them.
            scope.launch {
                // Rows that never loaded say nothing about the backlog: the ask stands.
                if (rowsReady() && persisted.accountChangeAsk && unsent(allShots()).isEmpty()) {
                    persist { it.copy(accountChangeAsk = false) }
                }
                onSignedIn()
            }
        }
        return true
    }

    /**
     * Everything here belonged to the old account: start the cursor over, drop
     * what was waiting to reach it and keep its remote deletes for if it comes
     * back. Rows bound to it stay as they are, and a PATCH to one is refused and
     * counted. The tombstones of [newId] itself come back into play.
     */
    private fun VisualizerState.forNewAccount(oldId: String?, newId: String) = copy(
        accountChangeAsk = true,
        uploadExcludedBefore = null,
        shotPullCursor = null,
        baristaPending = emptySet(),
        pendingEdits = emptyMap(),
        rowFailures = emptyList(),
        pullCursorEpoch = 1,
        retryQueue = emptyList(),
        uploadAttempted = emptyMap(),
        shotTombstones = shotTombstones.map {
            when {
                it.parkedFor == newId -> it.copy(parkedFor = null)
                it.parkedFor == null -> it.copy(parkedFor = oldId)
                else -> it
            }
        },
        unboundTombstones = unboundTombstones.map {
            when {
                it.parkedFor == newId -> it.copy(parkedFor = null)
                it.parkedFor == null -> it.copy(parkedFor = oldId)
                else -> it
            }
        },
    )

    /**
     * The answer to "upload your shots to this different account?". Yes uploads
     * the backlog. No, or dismissing it, keeps the shots that exist now off the
     * account, and new ones flow, so uploads can't stay held for good.
     */
    fun answerAccountChange(upload: Boolean) {
        scope.launch {
            persist { it.copy(accountChangeAsk = false, uploadExcludedBefore = if (upload) null else now()) }
            if (upload) {
                rowsReady()
                uploadAllUnsynced(allShots())
            }
        }
    }

    /**
     * Re-run the Premium probe ([VisualizerClient.probePremium], the web's
     * sentinel roaster write) and cache a conclusive result with its time.
     * Inconclusive / failed probes leave the cached value alone. Returns the
     * probe result.
     */
    private suspend fun refreshPremium(): Boolean? {
        val premium = runCatchingCancellable { withFreshToken { client.probePremium(it, now()) } }.getOrNull()
            ?: return null
        persist { it.copy(premium = premium, premiumCheckedAt = now()) }
        return premium
    }

    /**
     * App start: re-probe the Premium tier when signed in and the last
     * conclusive probe is over 24 h old (or never happened); otherwise nothing.
     */
    fun refreshPremiumIfStale() {
        scope.launch {
            if (!ownState.await()) return@launch
            val p = persisted
            if (p.tokens == null) return@launch
            val checked = p.premiumCheckedAt
            if (checked != null && now() - checked < PREMIUM_REFRESH_INTERVAL_MS) return@launch
            refreshPremium()
        }
    }

    /** The Sharing card's "Test" — a `/me` round-trip plus the Premium probe, with a visible verdict. */
    fun testConnection() {
        if (persisted.tokens == null) {
            notify("Not signed in to Visualizer")
            return
        }
        _state.update { it.copy(busy = true) }
        scope.launch {
            runCatchingCancellable { withFreshToken { client.fetchAccount(it) } }
                .onSuccess { account ->
                    persist { it.copy(account = account) }
                    val tier = when (refreshPremium()) {
                        true -> " (Premium)"
                        false -> " (free tier)"
                        null -> ""
                    }
                    notify("Visualizer connection OK — signed in as ${account.name}$tier")
                }
                .onFailure { notify("Visualizer connection failed: ${it.message}") }
            _state.update { it.copy(busy = false) }
        }
    }

    // ── Preferences ──────────────────────────────────────────────────────────

    fun setAutoSync(enabled: Boolean) = scope.launch { setAutoUploadNow(enabled) }
    fun setShotsDirection(direction: String) = scope.launch { persist { it.copy(prefs = it.prefs.copy(shotsDirection = direction)) } }

    /**
     * Beans / roasters direction. On a free account (cached `premium == false`)
     * the pushing modes are refused — the web greys them out the same way;
     * Off / Pull stay pickable.
     */
    fun setBeansDirection(direction: String) {
        if (persisted.premium == false && directionPushes(direction)) return
        scope.launch { persist { it.copy(prefs = it.prefs.copy(beansDirection = direction)) } }
    }

    fun setRoastersDirection(direction: String) {
        if (persisted.premium == false && directionPushes(direction)) return
        scope.launch { persist { it.copy(prefs = it.prefs.copy(roastersDirection = direction)) } }
    }
    fun setPrivacy(privacy: String) = scope.launch { persist { it.copy(prefs = it.prefs.copy(privacy = privacy)) } }
    fun setIncludeProfile(enabled: Boolean) = scope.launch { persist { it.copy(prefs = it.prefs.copy(includeProfile = enabled)) } }
    fun setIncludeNotes(enabled: Boolean) = scope.launch { persist { it.copy(prefs = it.prefs.copy(includeNotes = enabled)) } }

    private fun directionPushes(d: String) = d == "backup" || d == "two-way"
    private fun directionPulls(d: String) = d == "pull" || d == "two-way"

    /** [d] with its push leg taken off. */
    private fun pullsOnly(d: String) = if (directionPulls(d)) "pull" else "off"

    /** Append a sync-activity line (capped at 20, newest first). Persisted. */
    private suspend fun logSync(direction: String, id: String, name: String, error: String? = null) {
        val entry = SyncLogEntry(direction = direction, entity = "shot", id = id, name = name, at = System.currentTimeMillis(), error = error)
        persist { it.copy(log = (listOf(entry) + it.log).take(20)) }
    }

    // ── Catalogue search ─────────────────────────────────────────────────────

    /**
     * Search the Visualizer canonical catalogue (`GET /canonical_coffee_bags`)
     * for the bean form — open to free accounts, so only a session is needed.
     * Throws [VisualizerError] (incl. [VisualizerError.Auth] when signed out).
     */
    suspend fun searchCatalogue(query: String): CataloguePage {
        val body = withFreshToken { client.searchCanonicalCoffeeBags(it, query) }
        return parseCatalogue(json.encodeToString(JsonElement.serializer(), body ?: JsonNull))
    }

    /**
     * Search the catalogue's roasters (`GET /canonical_roasters`) — the roaster
     * form's search and a bag pick's roaster lookup (website / country). Open to
     * free accounts. Throws [VisualizerError] (incl. [VisualizerError.Auth]).
     */
    suspend fun searchCatalogueRoasters(query: String): CatalogueRoasterPage {
        val body = withFreshToken { client.searchCanonicalRoasters(it, query) }
        return parseCatalogueRoasterPage(json.encodeToString(JsonElement.serializer(), body ?: JsonNull))
    }

    /**
     * The catalogue record of a bag's roaster — one roaster search by [name],
     * matched on [catalogueRoasterId]; null when it isn't found or the lookup
     * fails (a bag pick never blocks on it: the roaster gets name + link only).
     */
    suspend fun lookupCatalogueRoaster(catalogueRoasterId: String, name: String): coffee.crema.core.CatalogueRoaster? {
        if (catalogueRoasterId.isBlank() || name.isBlank()) return null
        return try {
            searchCatalogueRoasters(name).entries.firstOrNull { it.id == catalogueRoasterId }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // ── Token freshness (web TokenVault.withFreshToken semantics) ───────────

    /**
     * Run [block] with a valid access token: proactive refresh inside a 60 s
     * expiry window, plus a one-shot refresh-and-retry on a 401. Throws
     * [VisualizerError.Auth] when there is no session or it has ended, and a
     * recoverable [VisualizerError.Network] when the refresh failed and left the
     * session standing, so the retry queue tries again (F17).
     */
    private suspend fun <T> withFreshToken(block: suspend (String) -> T): T {
        var tokens = persisted.tokens ?: throw VisualizerError.Auth()
        if (expiring(tokens)) tokens = refreshed(tokens, rejected = false) ?: throw VisualizerError.Auth()
        return try {
            block(tokens.accessToken)
        } catch (e: VisualizerError.Auth) {
            val fresh = refreshed(tokens, rejected = true) ?: throw e
            block(fresh.accessToken)
        }
    }

    private fun expiring(tokens: TokenSet) = tokens.expiresAt - 60_000 < System.currentTimeMillis()

    /**
     * One refresh at a time, and only ever onto the set it started from (web
     * `TokenVault`). Only the token endpoint definitively rejecting the refresh
     * token ends the session (core `classify_token_refresh_failure`: 400/401
     * `invalid_grant`). Doorkeeper rotates refresh tokens; one the server leaves
     * out stays the one in use.
     */
    private val refresher = SingleFlightRefresh<TokenSet>(
        stored = { persisted.tokens },
        refreshTokenOf = { it.refreshToken },
        expiring = ::expiring,
        grant = { spent -> refreshTokens(spent).let { it.copy(refreshToken = it.refreshToken ?: spent) } },
        endsSession = { uploadPolicy.refreshEndsSession(it) },
        store = { spent, fresh ->
            persist { st -> if (st.tokens?.refreshToken == spent) st.copy(tokens = fresh) else st }
        },
        end = { rejected ->
            var ended = false
            persist { st ->
                ended = st.tokens != null && st.tokens.refreshToken == rejected
                if (ended) st.copy(tokens = null, needsReconnect = true) else st
            }
            if (ended) notify(VISUALIZER_RECONNECT_LINE)
        },
    )

    private suspend fun refreshed(observed: TokenSet, rejected: Boolean): TokenSet? = try {
        refresher.refresh(observed, rejected)
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        throw VisualizerError.Network("Couldn’t refresh the Visualizer session: ${e.message}")
    }

    // ── Shot upload ──────────────────────────────────────────────────────────

    /**
     * Build the `POST /shots/upload` payload: the core's community-v2 export
     * over the FFI (identical wire bytes to the web), minus the profile /
     * notes blocks per the user's sharing prefs, plus `privacy` and the
     * `metadata.crema{localId, signature, appVersion}` escape valve.
     */
    internal fun buildShotPayload(shot: StoredShot): JsonObject {
        val wire = wireShotJson(shot, grinderModel())
        // A RE-upload (already bound) keeps the post-flow tail: Visualizer
        // de-dupes by a SHA over the telemetry columns, so reproducing the
        // original series updates the SAME remote row in place (fixing the
        // date + journal fields) instead of minting a duplicate.
        val wireJson = json.encodeToString(JsonObject.serializer(), wire)
        val v2 = if (shot.visualizerId != null) exportV2JsonShotFull(wireJson) else exportV2JsonShot(wireJson)
        val doc = json.parseToJsonElement(v2).jsonObject.toMutableMap()
        if (!persisted.prefs.includeProfile) doc.remove("profile")
        val metadata = (doc["metadata"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (!persisted.prefs.includeNotes) {
            if (metadata.containsKey("notes")) metadata["notes"] = JsonNull
            // The v2 document carries the notes text in two more slots —
            // `meta.shot.notes` (rides the raw file Visualizer keeps
            // downloadable) and the journal block its parser captures
            // (`app.data.settings.espresso_notes`). An opt-out must strip both.
            (doc["meta"] as? JsonObject)?.let { meta ->
                (meta["shot"] as? JsonObject)?.let { s ->
                    val shotMap = s.toMutableMap().apply { put("notes", JsonPrimitive("")) }
                    doc["meta"] = JsonObject(meta.toMutableMap().apply { put("shot", JsonObject(shotMap)) })
                }
            }
            (doc["app"] as? JsonObject)?.let { app ->
                (app["data"] as? JsonObject)?.let { data ->
                    (data["settings"] as? JsonObject)?.let { st ->
                        val stripped = st.toMutableMap().apply { remove("espresso_notes") }
                        val newData = data.toMutableMap().apply { put("settings", JsonObject(stripped)) }
                        doc["app"] = JsonObject(app.toMutableMap().apply { put("data", JsonObject(newData)) })
                    }
                }
            }
        }
        // Per-shot override wins; null inherits the Sharing default (web parity).
        doc["privacy"] = JsonPrimitive(shot.privacy ?: persisted.prefs.privacy)
        metadata["crema"] = buildJsonObject {
            put("localId", JsonPrimitive(shot.id))
            put(
                "signature",
                JsonPrimitive(
                    signatureForShot(
                        shot.completedAtMs,
                        shot.durationMs,
                        shot.profileName,
                        shotFinalWeight(shot),
                    ),
                ),
            )
            put("appVersion", JsonPrimitive(appVersion))
        }
        doc["metadata"] = JsonObject(metadata)
        return JsonObject(doc)
    }

    /**
     * Upload one shot in the background; stamps `visualizerId` on success.
     * Returns false without POSTing when signed out or the shot is already in flight.
     */
    fun uploadShot(shot: StoredShot, silent: Boolean = false): Boolean {
        // Brew Log rows (issue #10) never reach Visualizer — auto or manual.
        if (shot.isBrewLog) return false
        if (persisted.tokens == null) {
            if (!silent) notify("Sign in to Visualizer first (Settings → Sharing)")
            return false
        }
        if (uploadsHeld) {
            if (!silent) notify(ACCOUNT_HELD_NOTICE)
            return false
        }
        if (!claim(shot.id)) return false
        scope.launch {
            val result = try {
                Result.success(uploadShotNow(shot))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                release(shot.id)
            }
            result.onSuccess { id -> bindUploaded(shot.id, id) }
            // F17: a recoverable failure is queued for a retry; a success is a
            // sync tail that drains what is waiting.
            result.onSuccess { drainSoon() }
            result.exceptionOrNull()?.let { enqueueRetry(shot.id, it) }
            if (silent) return@launch
            val sink = onUploadOutcome
            result
                .onSuccess {
                    if (sink != null) sink(shot.id, UploadOutcome(UploadOutcomeKind.Uploaded)) else notify("Shot uploaded to Visualizer")
                }
                .onFailure { e ->
                    val why = uploadPolicy.describe(e)
                    if (sink != null) sink(shot.id, UploadOutcome(UploadOutcomeKind.Failed, why))
                    else notify("Visualizer upload failed: $why")
                }
        }
        return true
    }

    /**
     * Push every unsynced shot, awaiting the whole pass. Used by the
     * cross-destination catch-up. Skips shots already in flight (a live push
     * or a manual tap) and re-reads each shot first, so nothing is POSTed
     * twice; stops when the session is gone. A second call while a pass runs
     * returns at once.
     */
    override suspend fun uploadUnsentNow(shots: List<StoredShot>): DrainResult {
        if (!drainMutex.tryLock()) return DrainResult(stopped = "A Visualizer upload pass is already running")
        try {
            if (!enabled) return DrainResult()
            logNoTelemetry(shots)
            val unsynced = unsent(shots)
            if (unsynced.isEmpty()) return DrainResult()
            _state.update { it.copy(busy = true) }
            // Stops on the free-plan daily cap / a lasting 429 / a dead
            // session (web `shouldAbortLoop`); the rest stay in the backlog.
            val r = runUploadPass(
                unsynced,
                uploadPolicy,
                onFailure = { snapshot, e ->
                    if (e !is VisualizerError.Auth) logSync("skip", snapshot.id, snapshot.profileName ?: "Shot", uploadPolicy.describe(e))
                },
            ) { snapshot ->
                if (!claim(snapshot.id)) return@runUploadPass false
                try {
                    val shot = currentShot(snapshot.id) ?: snapshot
                    if (shot.visualizerId != null) return@runUploadPass false
                    bindUploaded(shot.id, uploadShotNow(shot))
                    true
                } finally {
                    release(snapshot.id)
                }
            }
            return DrainResult(r.uploaded, r.failed, r.skipped, r.stop?.notice)
        } finally {
            _state.update { it.copy(busy = false) }
            drainMutex.unlock()
        }
    }

    // ── Retry queue (F17; web `UploadQueue`) ─────────────────────────────────

    /** One drain at a time. */
    private val retryMutex = Mutex()

    /** The next scheduled drain (a queued entry's retry time). */
    private var retryJob: kotlinx.coroutines.Job? = null

    /** The foreground tick and its network trigger. */
    private var tickJob: kotlinx.coroutines.Job? = null

    /** When a network-triggered drain last ran (for [NETWORK_DRAIN_SPACING_MS]). */
    @Volatile private var lastNetworkDrainAt = 0L

    /** Queue a shot whose upload failed with [e], if the core says retry. */
    private suspend fun enqueueRetry(shotId: String, e: Throwable) {
        when (val d = uploadPolicy.queueDecision("create", e, attemptsMade = 1)) {
            is RetryDecision.Retry -> {
                val at = now() + d.delayMs
                persist { st ->
                    st.copy(retryQueue = st.retryQueue.filterNot { it.shotId == shotId } + QueuedUpload(shotId, 1, at))
                }
                scheduleDrain(at)
            }
            // The quota, a terminal error or a signed-out create: not queued (the
            // shot stays unsynced; the next pass or the post-sign-in catch-up
            // offers it again).
            else -> Unit
        }
    }

    /** Drain soon (a trigger: start, network back, the tick, a sync tail). */
    fun drainSoon() {
        scope.launch {
            if (!ownState.await()) return@launch
            val idle = persisted.retryQueue.isEmpty() && persisted.shotTombstones.none { it.owed }
            if (idle && !accountToAsk) return@launch
            drainRetryQueue()
        }
    }

    /** Run the drain when the earliest entry is due, and never sooner than
     *  [MIN_DRAIN_DELAY_MS] from now (an entry already due waits that long). */
    private fun scheduleDrain(at: Long) {
        if (!retryTimers) return
        retryJob?.cancel()
        retryJob = scope.launch {
            retryDelay((at - now()).coerceAtLeast(MIN_DRAIN_DELAY_MS))
            drainRetryQueue()
        }
    }

    /** When the next drain is owed: the earliest queued upload, or remote
     *  delete waiting out a backoff (signed in only); null = nothing waits. */
    private fun nextDrainAt(): Long? {
        val deletes = if (persisted.tokens == null) emptyList()
        else persisted.shotTombstones.filter { it.owed && it.nextAttemptAt > 0 }.map { it.nextAttemptAt }
        val edits = if (persisted.tokens == null) emptyList()
        else pendingEdits.values.filter { it.nextAttemptAt > 0 }.map { it.nextAttemptAt }
        return (persisted.retryQueue.map { it.nextAttemptAt } + deletes + edits).minOrNull()
    }

    /**
     * Retry every due queued upload once, on the core's rule
     * (`visualizer_queue_decision`): success binds the id; the daily quota or a
     * 429 stops the drain; a terminal or third failure drops it. A shot since
     * deleted, or already synced, leaves the queue. Then the remote deletes the
     * tombstones still owe (signed out: parked until a sign-in). Single-flight;
     * returns the number uploaded.
     */
    internal suspend fun drainRetryQueue(): Int {
        // Nothing moves until `/me` says whose session this is, so each drain
        // asks. A session from before this was recorded gets its id here too.
        if (accountToAsk) {
            val held = persisted.accountUnconfirmed
            if (!confirmAccount() && held) return 0
        }
        if (uploadsHeld) return 0
        // A 429's cooldown: nothing goes before it ends.
        if (pacer.coolingDown()) {
            nextDrainAt()?.let(::scheduleDrain)
            return 0
        }
        // Own state loads apart from the rows, and a shot that isn't in the
        // history leaves the queue: not before the rows are there. A later
        // trigger tries again.
        if (!rowsReady()) return 0
        if (!retryMutex.tryLock()) return 0
        var uploaded = 0
        var limited = false
        try {
            val due = persisted.retryQueue.filter { it.nextAttemptAt <= now() }
            for (entry in due) {
                val shot = currentShot(entry.shotId)
                if (shot == null || shot.visualizerId != null || shot.isBrewLog) {
                    dequeueRetry(entry.shotId)
                    continue
                }
                // Another upload holds it: try again a little later, not at
                // once (its entry stays due otherwise, and the drain would spin).
                if (!claim(shot.id)) {
                    updateRetry(entry.copy(nextAttemptAt = now() + CLAIMED_RETRY_MS))
                    continue
                }
                val failure = try {
                    bindUploaded(shot.id, uploadShotNow(shot))
                    null
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    e
                } finally {
                    release(shot.id)
                }
                if (failure == null) {
                    dequeueRetry(entry.shotId)
                    uploaded += 1
                    continue
                }
                val made = entry.attempts + 1
                when (val d = uploadPolicy.queueDecision("create", failure, made)) {
                    is RetryDecision.Drop -> {
                        dequeueRetry(entry.shotId)
                        logSync("skip", shot.id, shot.profileName ?: "Shot", uploadPolicy.describe(failure))
                        if (d.stopPass) {
                            uploadPolicy.quotaLimitOf(failure)?.let { notify(quotaNotice(it)) }
                            break
                        }
                    }
                    is RetryDecision.Park -> updateRetry(entry.copy(nextAttemptAt = now() + d.delayMs))
                    is RetryDecision.Retry -> {
                        updateRetry(QueuedUpload(entry.shotId, made, now() + d.delayMs))
                        if (d.stopPass) {
                            limited = true
                            break
                        }
                    }
                }
            }
            if (!limited) drainPendingEdits()
            deleteRemoteShots(dueOnly = true)
        } finally {
            retryMutex.unlock()
        }
        nextDrainAt()?.let(::scheduleDrain)
        return uploaded
    }

    private suspend fun dequeueRetry(shotId: String) =
        persist { st -> st.copy(retryQueue = st.retryQueue.filterNot { it.shotId == shotId }) }

    private suspend fun updateRetry(entry: QueuedUpload) =
        persist { st -> st.copy(retryQueue = st.retryQueue.map { if (it.shotId == entry.shotId) entry else it }) }

    /**
     * The app came to the foreground ([foreground]) or left it: while in the
     * foreground the queue drains every [RETRY_TICK_MS] and whenever a network
     * becomes available (at most once per [NETWORK_DRAIN_SPACING_MS]).
     */
    fun onForegroundChanged(foreground: Boolean) {
        tickJob?.cancel()
        tickJob = null
        if (!foreground) {
            networkWatcher?.stop()
            return
        }
        networkWatcher?.start {
            val t = now()
            if (t - lastNetworkDrainAt >= NETWORK_DRAIN_SPACING_MS) {
                lastNetworkDrainAt = t
                drainSoon()
            }
        }
        if (!retryTimers) return
        tickJob = scope.launch {
            while (true) {
                retryDelay(RETRY_TICK_MS)
                drainSoon()
            }
        }
    }

    /** One backlog upload with the History spinner on; stamps the id. Throws on failure. */
    private suspend fun pushTracked(shot: StoredShot): Boolean {
        _state.update { it.copy(uploadingShotIds = it.uploadingShotIds + shot.id) }
        try {
            bindUploaded(shot.id, uploadShotNow(shot))
            return true
        } finally {
            _state.update { it.copy(uploadingShotIds = it.uploadingShotIds - shot.id) }
        }
    }

    /** The suspend upload itself — payload → POST → lastSyncAt + log line. */
    private suspend fun uploadShotNow(snapshot: StoredShot): String {
        // Backstop: every caller filters brews out first (issue #10).
        require(!snapshot.isBrewLog) { "Brew Log rows are never uploaded to Visualizer" }
        // A copy taken before the series loaded has none: wait, then re-read.
        seriesReady()
        val shot = if (snapshot.samples.isEmpty()) currentShot(snapshot.id) ?: snapshot else snapshot
        if (uploadRefused(shot.isBrewLog, shot.samples.size.toUInt())) throw VisualizerError.NoSeries()
        val payload = buildShotPayload(shot)
        // Deleted while this held its claim: a POST would only make a copy to delete.
        if (shot.id in deletedHere) throw VisualizerError.RowDeleted()
        // Stored first: a kill mid-POST must leave a delete able to see it.
        persist { it.copy(uploadAttempted = it.uploadAttempted + (shot.id to now())) }
        val id = try {
            withFreshToken { client.uploadShot(it, payload) }
        } catch (e: Exception) {
            if (uploadPolicy.refusedUpload(e)) persist { it.copy(uploadAttempted = it.uploadAttempted - shot.id) }
            throw e
        }
        persist { it.copy(lastShotSyncAt = System.currentTimeMillis()) }
        logSync("push", shot.id, shot.profileName ?: "Shot")
        return id
    }

    /**
     * The upload of [shotId] came back as [visualizerId]. A row deleted meanwhile
     * can't take the id, so a tombstone does, and the remote delete runs if the
     * user asked for one. The shot stays deleted either way.
     */
    private suspend fun bindUploaded(shotId: String, visualizerId: String) {
        onShotSynced(shotId, visualizerId)
        val gone = currentShot(shotId)?.visualizerId != visualizerId
        persist { st ->
            val settled = st.copy(uploadAttempted = st.uploadAttempted - shotId)
            if (gone) settled.withTombstoneBound(shotId, visualizerId, now()) else settled
        }
        if (gone) drainSoon()
    }

    /** Whether an upload of [shotId] is running. */
    private fun claimed(shotId: String): Boolean = synchronized(inFlight) { shotId in inFlight }

    /** Whether an upload of [shotId] may have reached Visualizer, or is about to. */
    private fun uploadPending(st: VisualizerState, shotId: String): Boolean =
        shotId in st.uploadAttempted || st.retryQueue.any { it.shotId == shotId } || claimed(shotId)

    /** Whether a delete of [shot] should offer Visualizer: an upload may land its copy. */
    fun uploadPending(shot: StoredShot): Boolean = uploadPending(persisted, shot.id)

    /**
     * Send a History edit to the shot's Visualizer copy (web
     * `ShotSync.patchEditedShot`): what the edit marked ([markShotEdited]) and
     * nothing else. The body assembly lives in the core
     * (`de1_domain::visualizer_shot_patch`, review #42), fitted to the cached
     * tier: Premium-only fields are dropped for a free or unknown account, and
     * nothing left means no request. No-op for never-uploaded shots; soft — a
     * failure notifies, the mark stays and the drain tries again.
     */
    fun patchEditedShot(shot: StoredShot) {
        if (shot.visualizerId == null) return
        scope.launch { pushPendingEdit(shot.id, fallback = shot, fromEdit = true) }
    }

    /**
     * Send what is pending for [localId], built from the row as it stands
     * ([fallback] when history has no copy to hand). A mark clears once what was
     * sent is still the row's; one made since stays. A failure for good (the row
     * is gone or refused) clears and reports; any other keeps the mark, backed
     * off on the core's schedule. Returns the failure, if any.
     */
    internal suspend fun pushPendingEdit(localId: String, fallback: StoredShot? = null, fromEdit: Boolean = false): Throwable? {
        val edit = pendingEdits[localId] ?: return null
        val shot = currentShot(localId) ?: fallback ?: return null
        val vid = shot.visualizerId ?: return null
        if (persisted.tokens == null || persisted.accountUnconfirmed) return null
        if (!directionPushes(persisted.prefs.shotsDirection)) return null
        val inputs = editInputs(shot, edit)
        // Fitted to the account tier in core: a free account — or one whose tier
        // isn't probed yet — silently loses the Premium-only fields
        // (private_notes, the tasting scores incl. flavor, coffee_bag_id …);
        // null = nothing left the server would apply, so no request.
        val body = runCatchingCancellable {
            shotPatchBody(json.encodeToString(ShotPatchInputs.serializer(), inputs), persisted.premium)
                ?.let { json.decodeFromString(JsonObject.serializer(), it) }
        }.getOrElse {
            if (fromEdit) notify("Visualizer update failed: ${it.message}")
            return it
        }
        val failure = body?.let {
            runCatchingCancellable { withFreshToken { t -> client.patchShot(t, vid, it) } }.exceptionOrNull()
        }
        if (failure == null) {
            settleSentEdit(localId, edit, inputs)
            return null
        }
        val tier = if (failure is VisualizerError.PremiumGated) checkTierFor(localId) else null
        if (uploadPolicy.editFailureIsTerminal(failure) || tier == true) {
            pendingEdits.remove(localId)
            storePendingEdits()
            logSync("skip", localId, shot.profileName ?: "Shot", "This edit can't reach Visualizer: ${failure.message}")
            if (fromEdit) notify("Visualizer won't take this shot's edit: ${failure.message}")
        } else if (tier == false) {
            // Free after all: the next try sends only what a free account takes.
            pendingEdits.computeIfPresent(localId) { _, cur -> cur.copy(nextAttemptAt = 0) }
            storePendingEdits()
            nextDrainAt()?.let(::scheduleDrain)
        } else {
            pendingEdits.computeIfPresent(localId) { _, cur ->
                val made = cur.attempts + 1
                cur.copy(attempts = made, nextAttemptAt = now() + uploadPolicy.backoffMs((failure as? VisualizerError.Http)?.status, made - 1))
            }
            storePendingEdits()
            if (fromEdit) notify("Visualizer update failed: ${failure.message}")
            nextDrainAt()?.let(::scheduleDrain)
        }
        return failure
    }

    /**
     * A Premium refusal on [localId]'s edit: the cached tier was wrong. Probe it
     * for real (a sentinel write), at most once a day per mark, stamped before
     * it goes so an unanswered probe waits too. True: Premium confirmed and
     * still refused, so the edit is for good; false: free, now cached; null: no
     * probe or no answer.
     */
    private suspend fun checkTierFor(localId: String): Boolean? {
        val cur = pendingEdits[localId] ?: return null
        if (cur.tierProbedAt > 0 && now() - cur.tierProbedAt < PREMIUM_REFRESH_INTERVAL_MS) return null
        // A probe now would be refused unsent (the 429 cooldown): it waits, and
        // keeps the day's try for when it can go.
        if (pacer.coolingDown()) return null
        pendingEdits.computeIfPresent(localId) { _, c -> c.copy(tierProbedAt = now()) }
        storePendingEdits()
        return refreshPremium()
    }

    /** The PATCH inputs for [edit] on [shot]: only the marked fields. */
    private fun editInputs(shot: StoredShot, edit: PendingEdit) = shotPatchInputs(
        shot,
        includeNotes = persisted.prefs.includeNotes,
        defaultPrivacy = persisted.prefs.privacy,
        settingsGrinderModel = grinderModel(),
        sendJournal = edit.annotations,
        sendBarista = edit.barista,
    )

    /**
     * A PATCH built from [sent] landed (or had nothing the tier applies): clear
     * each mark whose field is still what was sent. One edited again meanwhile
     * stays, for the drain.
     */
    private suspend fun settleSentEdit(localId: String, sent: PendingEdit, sentInputs: ShotPatchInputs) {
        val now = currentShot(localId)
        pendingEdits.computeIfPresent(localId) { _, cur ->
            val current = now?.let { editInputs(it, sent) }
            val journalSame = current == null || current.copy(barista = null) == sentInputs.copy(barista = null)
            val baristaSame = current == null || current.barista == sentInputs.barista
            PendingEdit(
                annotations = cur.annotations && !(sent.annotations && journalSame),
                barista = cur.barista && !(sent.barista && baristaSame),
            ).takeIf { it.owes }
        }
        storePendingEdits()
    }

    /**
     * Edits of uploaded shots that haven't reached Visualizer yet: only these
     * fields ride a PATCH (the barista only after a barista edit, as the name or
     * "" once cleared, so a clear still reaches Visualizer), and a pull doesn't
     * fill a barista into a shot whose edit is pending (V3 #6: a failed clear
     * was refilled by the next pull, and the next edit sent the old name back).
     * Mirrored into [VisualizerState.pendingEdits] so it survives a restart
     * (V2 P3-4).
     */
    private val pendingEdits = java.util.concurrent.ConcurrentHashMap<String, PendingEdit>()

    /** Every Visualizer request's pacer; its bucket is stored in [VisualizerState.paceBucket]. */
    private val pacer = Pacer(
        json = json,
        rules = { op, payload -> pullRunCall(op, payload) },
        now = { now() },
        sleep = { paceDelay(it) },
        store = { bucket -> if (ownState.settled) persist { it.copy(paceBucket = bucket) } },
    ).also { client.pacer = it }

    /**
     * Mark what a History edit of [localId] changed: the journal fields
     * ([annotations]) or the barista. In memory at once, so a pull from now on
     * sees it. True when the stored set must change: the caller then runs
     * [storeMarks] before it queues the row's save, so a kill can't leave the
     * row saved without its mark (a barista clear would be refilled by the next
     * pull, V3 #6). False when the mark was already there, due: a notes
     * keystroke then rewrites nothing.
     */
    fun markShotEdited(localId: String, annotations: Boolean = false, barista: Boolean = false): Boolean {
        val edit = PendingEdit(annotations = annotations, barista = barista)
        if (!edit.owes) return false
        var changed = false
        pendingEdits.compute(localId) { _, cur ->
            val next = cur?.with(edit) ?: edit
            changed = next != cur
            next
        }
        return changed
    }

    /**
     * Store the marks made so far, waiting for own state to load first (a
     * store before it is a no-op, and [load] keeps marks made early). Run it
     * before the save of a row [markShotEdited] marked.
     */
    suspend fun storeMarks() {
        if (ownState.await()) storePendingEdits()
    }

    /** The History barista edit — call before [patchEditedShot] for that shot. */
    fun markBaristaEdited(localId: String) {
        if (markShotEdited(localId, barista = true)) scope.launch { storeMarks() }
    }

    /**
     * Store the live pending set (read under the persist lock, so the last write
     * wins with the latest set). Before [load], a no-op: [load] stores the marks
     * made before it.
     */
    private suspend fun storePendingEdits() {
        if (!ownState.settled) return
        persist { it.copy(pendingEdits = pendingEdits.toMap(), baristaPending = emptySet()) }
    }

    /**
     * The PATCH retry list: every due edit, sent from the row as it stands. A
     * shot no longer in the history owes nothing; a 429 leaves the rest.
     */
    private suspend fun drainPendingEdits() {
        if (!directionPushes(persisted.prefs.shotsDirection)) return
        for ((id, edit) in pendingEdits.toMap()) {
            if (edit.nextAttemptAt > now()) continue
            if (currentShot(id) == null) {
                pendingEdits.remove(id)
                storePendingEdits()
                continue
            }
            val failure = pushPendingEdit(id)
            if (failure != null && uploadPolicy.isRateLimited(failure)) break
        }
    }

    /** Called on `ShotCompleted` — uploads when auto-sync is armed, signed in,
     *  and the shots direction pushes. Verbose: the upload snackbar confirms
     *  the push (and surfaces a failure — a silent miss left users believing
     *  the shot was synced), per the issue #44 follow-up ask. */
    override fun maybeAutoUpload(shot: StoredShot, fullSamples: List<TelemetrySample>?): Boolean {
        if (persisted.prefs.autoUpload && enabled) {
            return uploadShot(shot)
        }
        return false
    }

    // ── Pull / reconcile (web pullAllShotsSince + applyShotReconciliation) ──

    // ── The pull run (web `ShotSync.pullAndReconcileShots`) ───────────────────

    /** One of the core's pull rules: [op] with [args], its JSON answer. */
    private fun rule(op: String, args: JsonObject): JsonElement =
        json.parseToJsonElement(pullRunCall(op, json.encodeToString(JsonObject.serializer(), args)))

    /** What a run request's failure means to the core (`RowFailure`). */
    private fun rowFailureOf(e: Throwable): JsonObject {
        fun call(failure: JsonElement) = buildJsonObject {
            put("kind", JsonPrimitive("Call"))
            put("details", buildJsonObject { put("failure", failure) })
        }
        fun kind(k: String) = buildJsonObject { put("kind", JsonPrimitive(k)) }
        return when (e) {
            is VisualizerError.NotFound -> call(kind("NotFound"))
            is VisualizerError.Http -> call(json.parseToJsonElement(uploadPolicy.classifyJson(e.status, e.body)))
            is VisualizerError.Network -> if (e.message == MALFORMED_BODY) kind("NotJson") else call(kind("Offline"))
            is VisualizerError.PremiumGated -> call(kind("NeedsPremium"))
            is VisualizerError.Auth -> call(kind("SessionEnded"))
            else -> call(kind("Offline"))
        }
    }

    /** A run request's answer: the value, or the failure and what the core says to do. */
    private sealed interface RunReply<out A> {
        data class Ok<A>(val value: A) : RunReply<A>
        data class Fail(val failure: JsonObject, val action: JsonObject) : RunReply<Nothing>
    }

    /**
     * One request of a pull run, paced patiently, with the core's rule for a
     * failure: a 5xx retries once in place, a 429 sits its wait out and tries
     * the same request again (three times), anything else comes back.
     */
    private suspend fun <A> runRequest(block: suspend () -> A): RunReply<A> {
        var attempt = 0
        while (true) {
            val failure = try {
                // A 429's wait is the pacer's cooldown: the next slot sits it out.
                return RunReply.Ok(withContext(PatientRun) { block() })
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                e
            } finally {
                if (_state.value.pullWaitingUntil != null) _state.update { it.copy(pullWaitingUntil = null) }
            }
            val f = rowFailureOf(failure)
            val action = rule(
                "rowFailureAction",
                buildJsonObject {
                    put("failure", f)
                    put("attempt", JsonPrimitive(attempt))
                    put("jitter", JsonPrimitive(kotlin.random.Random.nextDouble()))
                },
            ).jsonObject
            when ((action["kind"] as? JsonPrimitive)?.contentOrNull) {
                "RetryNow" -> attempt += 1
                "Wait" -> {
                    val wait = action["details"]!!.jsonObject["wait_ms"]!!.jsonPrimitive.content.toLong()
                    pacer.noteRateLimited(wait)
                    _state.update { it.copy(pullWaitingUntil = now() + wait) }
                    attempt += 1
                }
                else -> return RunReply.Fail(f, action)
            }
        }
    }

    /** The `SyncFailure` a run ends on, from a failed request's `RowFailure`. */
    private fun endFailure(f: JsonObject): JsonElement =
        if ((f["kind"] as? JsonPrimitive)?.contentOrNull == "Call") {
            f["details"]!!.jsonObject["failure"]!!
        } else {
            buildJsonObject {
                put("kind", JsonPrimitive("Unavailable"))
                put("details", buildJsonObject { put("status", JsonPrimitive(200)) })
            }
        }

    /** A detail's own `updated_at` (unix s), in either format, or null. */
    private fun detailUpdatedAt(detail: JsonElement): Long? {
        val d = detail as? JsonObject ?: return null
        val v = (d["updated_at"] as? JsonPrimitive)
            ?: ((d["meta"] as? JsonObject)?.get("visualizer") as? JsonObject)?.get("updated_at") as? JsonPrimitive
        return v?.contentOrNull?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
    }

    /** A list row as the core reads it. */
    private fun listRow(s: VisualizerClient.ShotSummary) = buildJsonObject {
        put("id", JsonPrimitive(s.id))
        put("clock", JsonPrimitive(s.clockSec))
        put("updated_at", JsonPrimitive(s.updatedAtSec))
    }

    /**
     * Fetch one row's detail and apply it, as the run does for each row: the
     * recipe only when offered and the row has none, the row re-read just
     * before the request so an edit made meanwhile wins. The core outcome.
     */
    private suspend fun pullRow(summary: VisualizerClient.ShotSummary, startShots: List<StoredShot>): RunReply<JsonObject> {
        // The rows as they stand now, not as the page began: a row bound by an
        // earlier row of this page can't bind a second remote copy.
        val shots = allShots().ifEmpty { startShots }
        val bound = shots.firstOrNull { it.visualizerId == summary.id }?.let { currentShot(it.id) ?: it }
        val before = bound?.let { mapOf(it.id to mergeInputOf(it, pendingEdits[it.id]?.barista == true)) }.orEmpty()
        val detail = when (val r = runRequest { withFreshToken { client.fetchShotDetail(it, summary.id) } }) {
            is RunReply.Ok -> r.value
            is RunReply.Fail -> return r
        }
        // A poison retry has no list row: its version comes from the detail.
        val updatedAt = summary.updatedAtSec.takeIf { it > 0 } ?: detailUpdatedAt(detail)
        val payload = buildJsonObject {
            put("summary", listRow(summary.copy(updatedAtSec = updatedAt ?: 0L)))
            put("detail", detail)
        }
        val wire = runCatching {
            json.parseToJsonElement(coffee.crema.core.wireShotFromDetail(json.encodeToString(JsonObject.serializer(), payload))).jsonObject
                // No version known: none is written, and the walk fetches it when listed.
                .let { if (updatedAt == null) JsonObject(it + ("updated_at_ms" to JsonNull)) else it }
        }.getOrNull()
        if (wire == null) {
            val f = buildJsonObject { put("kind", JsonPrimitive("Unmappable")) }
            val action = rule("rowFailureAction", buildJsonObject { put("failure", f); put("attempt", JsonPrimitive(0)); put("jitter", JsonPrimitive(0.5)) })
            return RunReply.Fail(f, action.jsonObject)
        }
        val detailStr = json.encodeToString(JsonElement.serializer(), detail)
        val samples = runCatching { coffee.crema.core.samplesFromVisualizerDetail(detailStr) }.getOrNull()
        val offered = ((detail as? JsonObject)?.get("profile_url") as? JsonPrimitive)?.isString == true
        val profile = if (offered && bound?.profile == null) {
            // A recipe that won't come leaves the row name-only.
            (runRequest { withFreshToken { client.request("GET", "/shots/${summary.id}/profile?format=json", it) } } as? RunReply.Ok)
                ?.value?.let { v2 -> runCatching { json.parseToJsonElement(coffee.crema.core.parseV2Profile(v2.toString())).jsonObject }.getOrNull() }
        } else {
            null
        }
        val applied = reconcileAndApply(
            shots,
            listOf(wire),
            samples?.let { mapOf(summary.id to it) }.orEmpty(),
            profile?.let { mapOf(summary.id to it) }.orEmpty(),
            before,
        )
        val outcome = when {
            applied.refused > 0 -> buildJsonObject { put("kind", JsonPrimitive("HistoryFull")) }
            applied.held > 0 -> buildJsonObject {
                put("kind", JsonPrimitive("EditedDuringFetch"))
                put("details", buildJsonObject { put("has_series", JsonPrimitive(false)) })
            }
            else -> buildJsonObject { put("kind", JsonPrimitive("Applied")) }
        }
        return RunReply.Ok(outcome)
    }

    /** What a run did: the core's `RunOutcome`, rows applied, rows a full history refused. */
    internal data class RunResult(val outcome: JsonObject, val pulled: Int, val refused: Int)

    /**
     * Pull as the core's run rules say: paced, walking `GET /shots` newest
     * first from the cursor (`updated_after`). Each row applies as it goes,
     * its version written last, so a run that stops keeps what it did and the
     * next one skips it. The cursor moves only on a complete run, on the
     * server's clock. A row that keeps failing becomes poison after three
     * counted days and is retried alone once a day. The first run after this
     * changed (`pullCursorEpoch` unset) walks the whole account once.
     */
    internal suspend fun pullRun(shots: List<StoredShot>, fromStart: Boolean = false): RunResult {
        // One run at a time: a second Sync while one walks does nothing.
        if (!runMutex.tryLock()) return RunResult(buildJsonObject { put("kind", JsonPrimitive("Busy")) }, 0, 0)
        try {
            if (restoreRunning()) return RunResult(restoreBusy(), 0, 0)
            return walkRun(shots, fromStart)
        } finally {
            runMutex.unlock()
        }
    }

    /** A run that waits for a restore, or stopped for one mid-walk. */
    private fun restoreBusy() = buildJsonObject {
        put("kind", JsonPrimitive("Busy"))
        put("restore", JsonPrimitive(true))
    }

    /** Held by the pull run that is walking. */
    private val runMutex = Mutex()

    private suspend fun walkRun(shots: List<StoredShot>, fromStart: Boolean): RunResult {
        val st0 = persisted
        val repairing = st0.pullCursorEpoch == null
        val legacyCursor = if (repairing) st0.shotPullCursor?.div(1000) else null
        val since = if (fromStart || repairing) 0L else (st0.shotPullCursor ?: 0L)
        if (repairing || fromStart) {
            notify("Crema is checking your whole Visualizer account once. On a large account this takes a while, and shots it was missing come back.")
        }
        val run = st0.pullRun + 1
        persist { it.copy(pullRun = run) }
        var poison = if (fromStart) emptyList() else st0.rowFailures
        fun poisonJson() = json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(coffee.crema.core.PoisonEntry.serializer()), poison)
        fun poisonFrom(el: JsonElement) =
            json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(coffee.crema.core.PoisonEntry.serializer()), el)
        val resolved = mutableListOf<String>()
        var tally: JsonElement = buildJsonObject { put("sinceMs", JsonPrimitive(since)) }
        var pulled = 0
        var refused = 0
        /** Versions this run wrote, for the plan of a later page. */
        val seen = mutableMapOf<String, Long>()

        // Poison rows due their daily try, alone, before the walk.
        val due = rule("poisonDue", buildJsonObject { put("entries", poisonJson()); put("nowMs", JsonPrimitive(now())) })
        for (id in due.jsonArray.map { it.jsonPrimitive.content }) {
            val clock = poison.firstOrNull { it.id == id }?.clock ?: 0L
            val r = pullRow(VisualizerClient.ShotSummary(id, clock, 0L), shots)
            val gone = r is RunReply.Fail && failureKind(r.failure) == "NotFound"
            if (r is RunReply.Ok || gone) {
                resolved += id
                if (r is RunReply.Ok && (r.value["kind"] as? JsonPrimitive)?.contentOrNull == "Applied") pulled += 1
            } else {
                poison = poisonFrom(rule("notePoisonRetry", buildJsonObject { put("entries", poisonJson()); put("id", JsonPrimitive(id)); put("nowMs", JsonPrimitive(now())) }))
            }
        }

        val tombstoned = persisted.shotTombstones.mapTo(HashSet()) { it.visualizerId }
        var page = 1
        var ownDeletes = 0
        walk@ while (true) {
            // A restore started mid-walk: what this run applied stays; the cursor,
            // the poison list and the rest wait for the next run, after it.
            if (restoreRunning()) {
                logSync("pull", "", "Pulled $pulled shot${if (pulled == 1) "" else "s"}, then paused", "A restore started, so the pull stopped. It goes on with the next sync.")
                return RunResult(restoreBusy(), pulled, refused)
            }
            if (page > MAX_PULL_PAGES) {
                tally = JsonObject(tally.jsonObject + ("ended" to partial("PageCap")))
                break
            }
            val after = if (since > 0) since / 1000 - 1 else null
            val list = when (val r = runRequest { withFreshToken { client.listShotsPage(it, page, RUN_PAGE_ITEMS, after) } }) {
                is RunReply.Ok -> r.value
                is RunReply.Fail -> {
                    tally = rule("endRun", buildJsonObject { put("tally", tally); put("failure", endFailure(r.failure)) })
                    break@walk
                }
            }
            val shift = rule("afterListPage", buildJsonObject { put("tally", tally); put("count", JsonPrimitive(list.count)); put("ownDeletes", JsonPrimitive(ownDeletes)) }).jsonObject
            tally = shift["tally"]!!
            ownDeletes = 0
            when (shift["step"]!!.jsonPrimitive.content) {
                "Restart" -> { page = 1; continue@walk }
                "Stop" -> break@walk
            }
            val current = allShots().ifEmpty { shots }
            val known = kotlinx.serialization.json.buildJsonArray {
                for (row in list.rows) {
                    val local = current.firstOrNull { it.visualizerId == row.id }
                    when {
                        row.id in tombstoned -> add(buildJsonObject { put("remoteId", JsonPrimitive(row.id)); put("tombstoned", JsonPrimitive(true)) })
                        local != null || row.id in seen -> add(
                            buildJsonObject {
                                put("remoteId", JsonPrimitive(row.id))
                                (seen[row.id] ?: local?.visualizerUpdatedAt)?.let { put("remoteVersion", JsonPrimitive(it)) }
                                put("hasSeries", JsonPrimitive(local == null || local.samples.isNotEmpty()))
                                put("isBrew", JsonPrimitive(local?.isBrewLog == true))
                            },
                        )
                    }
                }
            }
            val candidates = kotlinx.serialization.json.buildJsonArray {
                current.filter { it.visualizerId == null && !it.isBrewLog }.forEach { s ->
                    add(buildJsonObject { put("localId", JsonPrimitive(s.id)); put("completedAtMs", JsonPrimitive(s.completedAtMs)); put("durationMs", JsonPrimitive(s.durationMs)) })
                }
                persisted.unboundTombstones.filter { it.parkedFor == null }.forEach { t ->
                    add(buildJsonObject { put("localId", JsonPrimitive(t.id)); put("completedAtMs", JsonPrimitive(t.completedAtMs)); put("durationMs", JsonPrimitive(t.durationMs)); put("tombstone", JsonPrimitive(true)) })
                }
            }
            val plan = rule(
                "planPage",
                buildJsonObject {
                    put("rows", kotlinx.serialization.json.JsonArray(list.rows.map(::listRow)))
                    put("known", known)
                    put("candidates", candidates)
                    put("freeSlots", JsonPrimitive((coffee.crema.history.HistoryStore.MAX_SHOTS - current.size).coerceAtLeast(0)))
                    legacyCursor?.let { put("legacyCursor", JsonPrimitive(it)) }
                },
            ).jsonObject["rows"]!!.jsonArray
            for ((i, planned) in plan.withIndex()) {
                val summary = list.rows[i]
                val row = listRow(summary)
                val p = planned.jsonObject
                if (p["kind"]!!.jsonPrimitive.content == "Skip") {
                    val full = p["details"]!!.jsonObject["why"]!!.jsonPrimitive.content == "HistoryFull"
                    if (full) refused += 1
                    val outcome = buildJsonObject { put("kind", JsonPrimitive(if (full) "HistoryFull" else "Unchanged")) }
                    tally = rule("recordRow", buildJsonObject { put("tally", tally); put("row", row); put("outcome", outcome) })
                    continue
                }
                if (restoreRunning()) {
                    logSync("pull", "", "Pulled $pulled shot${if (pulled == 1) "" else "s"}, then paused", "A restore started, so the pull stopped. It goes on with the next sync.")
                    return RunResult(restoreBusy(), pulled, refused)
                }
                when (val r = pullRow(summary, current)) {
                    is RunReply.Ok -> {
                        when ((r.value["kind"] as? JsonPrimitive)?.contentOrNull) {
                            "Applied" -> {
                                pulled += 1
                                resolved += summary.id
                                seen[summary.id] = summary.updatedAtSec
                            }
                            "HistoryFull" -> refused += 1
                        }
                        tally = rule("recordRow", buildJsonObject { put("tally", tally); put("row", row); put("outcome", r.value) })
                    }
                    is RunReply.Fail -> {
                        tally = if ((r.action["kind"] as? JsonPrimitive)?.contentOrNull == "Skip") {
                            val outcome = buildJsonObject { put("kind", JsonPrimitive("Failed")); put("details", r.action["details"]!!) }
                            rule("recordRow", buildJsonObject { put("tally", tally); put("row", row); put("outcome", outcome) })
                        } else {
                            rule("endRun", buildJsonObject { put("tally", tally); put("failure", endFailure(r.failure)) })
                        }
                    }
                }
                if (tally.jsonObject["ended"] != null && tally.jsonObject["ended"] !is JsonNull) break@walk
            }
            if (page >= list.pages || list.rows.isEmpty()) break
            page += 1
        }

        // The only failing row may be the only one changed: one known-good
        // detail says whether the server works, for the poison count.
        if (rule("healthProbeDue", buildJsonObject { put("tally", tally) }).jsonPrimitive.boolean) {
            val good = allShots().ifEmpty { shots }.firstOrNull { it.visualizerId != null && it.visualizerUpdatedAt != null }
            if (good?.visualizerId != null) {
                val ok = runCatchingCancellable { withContext(PatientRun) { withFreshToken { client.fetchShotDetail(it, good.visualizerId) } } }.isSuccess
                tally = rule("recordHealthProbe", buildJsonObject { put("tally", tally); put("succeeded", JsonPrimitive(ok)) })
            }
        }

        poison = poisonFrom(
            rule("settlePoison", buildJsonObject {
                put("entries", poisonJson()); put("tally", tally)
                put("resolved", kotlinx.serialization.json.JsonArray(resolved.map(::JsonPrimitive))); put("nowMs", JsonPrimitive(now()))
            }),
        )
        val outcome = rule("finishRun", buildJsonObject { put("tally", tally); put("poison", poisonJson()) }).jsonObject
        val complete = outcome["kind"]!!.jsonPrimitive.content == "Complete"
        persist { st ->
            st.copy(
                rowFailures = poison,
                shotPullCursor = if (complete) outcome["details"]!!.jsonObject["next_cursor_ms"]!!.jsonPrimitive.content.toLong() else st.shotPullCursor,
                pullCursorEpoch = if (complete) 1 else st.pullCursorEpoch,
                // Matches left the list above, so what's here wasn't found.
                unboundTombstones = if (complete) st.unboundTombstones.filterNot { it.expiredBy(run, since) } else st.unboundTombstones,
            )
        }
        pacer.flush()
        return RunResult(outcome, pulled, refused)
    }

    /** The run's one line in the sync log: a large run would push out every other line. */
    private suspend fun logRun(result: RunResult) {
        val kind = result.outcome["kind"]?.jsonPrimitive?.contentOrNull
        if (kind == "Busy") {
            notify("A Visualizer sync is already running.")
            return
        }
        val details = result.outcome["details"] as? JsonObject
        val shotsN = "${result.pulled} shot${if (result.pulled == 1) "" else "s"}"
        if (kind == "Complete") {
            val skipped = details?.get("skipped_permanent")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            logSync("pull", "", if (result.pulled == 0) "No new shots from Visualizer" else "Pulled $shotsN", if (skipped > 0) "$skipped couldn't be read and were skipped." else null)
            return
        }
        val why = when (details?.get("reason")?.jsonPrimitive?.contentOrNull) {
            "RateLimited" -> "Visualizer is limiting requests. The rest come with the next sync."
            "Offline" -> "The connection dropped. The rest come with the next sync."
            "ServerTrouble" -> "Visualizer isn't answering properly. The rest come with the next sync."
            "PageCap" -> "The pull reached its page limit. Run Sync again to continue."
            "RowsFailed" -> "Some shots couldn't be read. They're tried again with the next sync."
            "ListShifted" -> "Shots changed on Visualizer while pulling. The rest come with the next sync."
            "HistoryFull" -> historyFullNotice(result.refused)
            else -> "Visualizer couldn't be reached."
        }
        logSync("pull", "", "Pulled $shotsN, then stopped", why)
    }

    private fun partial(reason: String) = buildJsonObject {
        put("kind", JsonPrimitive("Partial"))
        put("details", buildJsonObject { put("reason", JsonPrimitive(reason)) })
    }

    private fun failureKind(f: JsonObject): String? =
        f["details"]?.jsonObject?.get("failure")?.jsonObject?.get("kind")?.jsonPrimitive?.contentOrNull

    /**
     * Reconcile pulled wires against the local history via the CORE planner
     * (`reconcile_shots` over the FFI — the identical algorithm the web runs)
     * and apply the actions through the VM callbacks. A row a full history
     * refuses is counted and the rest of the plan still applies.
     */
    internal suspend fun reconcileAndApply(
        localShots: List<StoredShot>,
        wires: List<JsonObject>,
        samplesById: Map<String, String>,
        profilesById: Map<String, JsonObject>,
        before: Map<String, ShotMergeInput> = emptyMap(),
    ): PullApplied {
        if (wires.isEmpty()) return PullApplied()
        // Tombstones ride along (web F18a): a shot deleted here whose remote copy
        // is still on Visualizer must match that copy (an `update`, skipped
        // below), not look new and be pulled back in. One deleted during its
        // upload has no id yet, and matches by upload key instead.
        val tombstones = persisted.shotTombstones
        val tombstoned = tombstones.mapTo(HashSet()) { it.id }
        val unbound = persisted.unboundTombstones.filter { it.parkedFor == null }
        val unboundIds = unbound.mapTo(HashSet()) { it.id }
        val payload = buildJsonObject {
            put(
                "local",
                kotlinx.serialization.json.buildJsonArray {
                    localShots.filterNot { it.isBrewLog }.forEach { shot ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(shot.id))
                                put("completedAt", JsonPrimitive(shot.completedAtMs))
                                put("duration", JsonPrimitive(shot.durationMs))
                                put("profileName", shot.profileName?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("finalWeight", shotFinalWeight(shot)?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("visualizerId", shot.visualizerId?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("deletedAt", JsonNull)
                            },
                        )
                    }
                    tombstones.forEach { t ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(t.id))
                                put("completedAt", JsonPrimitive(0))
                                put("duration", JsonPrimitive(0))
                                put("profileName", JsonNull)
                                put("finalWeight", JsonNull)
                                put("visualizerId", JsonPrimitive(t.visualizerId))
                                put("deletedAt", JsonPrimitive(t.deletedAt))
                            },
                        )
                    }
                    unbound.forEach { t ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(t.id))
                                put("completedAt", JsonPrimitive(t.completedAtMs))
                                put("duration", JsonPrimitive(t.durationMs))
                                put("profileName", JsonNull)
                                put("finalWeight", JsonNull)
                                put("visualizerId", JsonNull)
                                put("deletedAt", JsonPrimitive(t.deletedAt))
                            },
                        )
                    }
                },
            )
            put("remote", kotlinx.serialization.json.JsonArray(wires))
        }
        val actions = runCatchingCancellable {
            json.parseToJsonElement(
                coffee.crema.core.reconcileShots(json.encodeToString(JsonObject.serializer(), payload)),
            ) as kotlinx.serialization.json.JsonArray
        }.getOrElse { return PullApplied(planned = false) }
        val stubs = mutableListOf<StoredShot>()
        var held = 0
        for (el in actions) {
            val action = el as? JsonObject ?: continue
            fun fld(k: String) = (action[k] as? JsonPrimitive)?.contentOrNull
            when (fld("kind")) {
                "add" -> {
                    val remote = action["remote"] as? JsonObject ?: continue
                    val vid = (remote["id"] as? JsonPrimitive)?.contentOrNull
                    storedShotFromWire(remote, vid?.let { samplesById[it] }, json)?.let { found ->
                        // Its version too, or every run would fetch it again.
                        val version = (remote["updated_at_ms"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()?.div(1000)
                        val stub = found.copy(visualizerUpdatedAt = version)
                        // Attach the recipe fetched at pull time (#12) so the shot is
                        // self-contained; absent for profile-less remotes.
                        stubs += vid?.let { profilesById[it] }?.let { stub.copy(profile = it) } ?: stub
                    }
                }
                "bind" -> {
                    val localId = fld("localId") ?: continue
                    val vid = fld("visualizerId") ?: continue
                    // Deleted during its upload: the id goes on the tombstone,
                    // and the remote copy stays out of the history.
                    if (localId in unboundIds) {
                        persist { it.withTombstoneBound(localId, vid, now()) }
                        continue
                    }
                    bindUploaded(localId, vid)
                    if (mergeAndApply(localId, action["remote"] as? JsonObject, samplesById[vid], profilesById[vid], before[localId])) held += 1
                    logSync("pull", localId, "Shot (bound)")
                }
                "update" -> {
                    val localId = fld("localId") ?: continue
                    // Deleted here: nothing to update, and the remote copy stays
                    // out of the history.
                    if (localId in tombstoned) continue
                    val remote = action["remote"] as? JsonObject ?: continue
                    val vid = (remote["id"] as? JsonPrimitive)?.contentOrNull
                    if (mergeAndApply(localId, remote, vid?.let { samplesById[it] }, vid?.let { profilesById[it] }, before[localId])) held += 1
                }
            }
        }
        val refused = if (stubs.isEmpty()) emptySet() else onPulledShots(stubs)
        val added = stubs.filter { it.id !in refused }
        added.forEach { logSync("pull", it.id, it.profileName ?: "Shot") }
        // One line for the run: a large pull would push out the whole log.
        if (refused.isNotEmpty()) logSync("skip", "", "Pull", historyFullNotice(refused.size))
        return PullApplied(added.size, refused.size, held = held)
    }

    // ── Shot deletion (web history tombstones + upload-queue delete) ───────

    /**
     * Whether pull [run] from [since], which walked everything, ends this
     * tombstone: it started later, it covered the window the upload would have
     * landed in, and no POST is still out, whose id would need somewhere to land.
     */
    private fun UnboundTombstone.expiredBy(run: Long, since: Long): Boolean =
        parkedFor == null && writtenRun < run && !claimed(id) && pullCoversAttempt(since, attemptedAt)

    /** The shots deleted here whose Visualizer copy still exists. */
    internal val shotTombstones: List<ShotTombstone> get() = persisted.shotTombstones

    /**
     * The shot [shot] was deleted here, with [deleteRemote] saying whether its
     * Visualizer copy goes too. A delete records intent, and a binding can arrive
     * later. A shot bound or with an upload under way leaves a tombstone. One never
     * offered to Visualizer just goes.
     */
    fun tombstoneDeleted(shot: StoredShot, deleteRemote: Boolean) {
        val visualizerId = shot.visualizerId
        if (visualizerId != null) return tombstoneShot(shot.id, visualizerId, deleteRemote)
        deletedHere += shot.id
        scope.launch {
            if (!ownState.await()) return@launch
            persist { st ->
                if (!uploadPending(st, shot.id)) return@persist st
                val tombstone = UnboundTombstone(
                    id = shot.id,
                    completedAtMs = shot.completedAtMs,
                    durationMs = shot.durationMs,
                    deletedAt = now(),
                    deleteRemote = deleteRemote,
                    writtenRun = st.pullRun,
                    attemptedAt = st.uploadAttempted[shot.id] ?: now().takeIf { claimed(shot.id) },
                )
                st.copy(
                    unboundTombstones = st.unboundTombstones.filterNot { it.id == shot.id } + tombstone,
                    uploadAttempted = st.uploadAttempted - shot.id,
                )
            }
        }
    }

    /**
     * Record that the shot [id], uploaded as [visualizerId], was deleted here.
     * The tombstone keeps the next pull from bringing the remote copy back.
     * With [deleteRemote] the remote copy is deleted too, now and on every
     * later sync until the `DELETE` lands; then the tombstone goes. A shot
     * that was never uploaded needs no tombstone (the caller just drops it).
     */
    fun tombstoneShot(id: String, visualizerId: String, deleteRemote: Boolean) {
        scope.launch {
            persist { st ->
                st.copy(
                    shotTombstones = st.shotTombstones.filterNot { it.id == id } +
                        ShotTombstone(id, visualizerId, deletedAt = now(), deleteRemote = deleteRemote),
                )
            }
            if (deleteRemote) deleteRemoteShots()
        }
    }

    /**
     * A restore or an import brought back shots with these [ids]: drop their
     * tombstones, so a pending remote delete doesn't remove the copy a
     * restored shot now points at, and the pull treats them as live again.
     */
    /** Shots deleted here whose tombstone is still owed: a restore's plan counts them as deleted. */
    fun deletedShotIds(): Set<String> =
        deletedHere.toSet() + persisted.shotTombstones.map { it.id } + persisted.unboundTombstones.map { it.id }

    /** [forgetTombstones], awaited: a restore's step 2. Whether it landed. */
    suspend fun forgetTombstonesNow(ids: Collection<String>): Boolean {
        val gone = ids.toHashSet()
        deletedHere -= gone
        if (persisted.shotTombstones.none { it.id in gone } && persisted.unboundTombstones.none { it.id in gone }) return true
        return persist { st ->
            st.copy(
                shotTombstones = st.shotTombstones.filterNot { it.id in gone },
                unboundTombstones = st.unboundTombstones.filterNot { it.id in gone },
            )
        }
    }

    fun forgetTombstones(ids: Collection<String>) {
        val gone = ids.toHashSet()
        deletedHere -= gone
        if (persisted.shotTombstones.none { it.id in gone } && persisted.unboundTombstones.none { it.id in gone }) return
        scope.launch {
            persist { st ->
                st.copy(
                    shotTombstones = st.shotTombstones.filterNot { it.id in gone },
                    unboundTombstones = st.unboundTombstones.filterNot { it.id in gone },
                )
            }
        }
    }

    /**
     * Send `DELETE /shots/{id}` for every tombstone the user asked to delete
     * remotely (web `ShotSync.deleteShot`: open to free accounts, a 404 counts
     * as already gone). A success drops the tombstone. A failure goes by the
     * core's queue rule for a delete (`visualizer_queue_decision`, as web's
     * queue): retried on its backoff, then parked an hour at a time after the
     * third attempt, since nothing else re-offers it; a terminal failure (a 403,
     * …) stops the remote delete but keeps the tombstone, so the copy is still
     * never pulled back. [dueOnly] (the drain) skips the
     * ones still in their backoff; a sync the user runs tries them all. A no-op
     * when signed out (parked until a sign-in).
     */
    internal suspend fun deleteRemoteShots(dueOnly: Boolean = false) {
        if (persisted.tokens == null || persisted.accountUnconfirmed) return
        for (t in persisted.shotTombstones.filter { it.owed && (!dueOnly || it.nextAttemptAt <= now()) }) {
            val failure = runCatchingCancellable { withFreshToken { client.request("DELETE", "/shots/${t.visualizerId}", it) } }
                .exceptionOrNull()?.takeUnless { it is VisualizerError.NotFound }
            val decision = failure?.let { uploadPolicy.queueDecision("delete", it, t.attempts + 1) }
            val updated = when (decision) {
                null -> null
                is RetryDecision.Drop -> {
                    notify("Visualizer refused to delete a shot, so its copy stays there: ${failure.message}")
                    t.copy(deleteRemote = false)
                }
                is RetryDecision.Park -> t.copy(nextAttemptAt = now() + decision.delayMs)
                is RetryDecision.Retry -> t.copy(attempts = t.attempts + 1, nextAttemptAt = now() + decision.delayMs)
            }
            persist { st ->
                st.copy(
                    shotTombstones = if (updated == null) st.shotTombstones.filterNot { it.id == t.id }
                    else st.shotTombstones.map { if (it.id == t.id) updated else it },
                )
            }
            val entry = SyncLogEntry(
                direction = "delete", entity = "shot", id = t.visualizerId, name = "Shot", at = now(),
                error = failure?.let { it.message ?: "delete failed" },
            )
            persist { it.copy(log = (listOf(entry) + it.log).take(20)) }
            // The quota or a 429 stops the deletes too.
            if ((decision as? RetryDecision.Retry)?.stopPass == true || (decision as? RetryDecision.Drop)?.stopPass == true) break
        }
    }

    /**
     * What the merge reads of a local row (core `ShotMergeInput`), with its
     * pending barista edit. Android marks no tags.
     */
    private fun mergeInputOf(shot: StoredShot, baristaPending: Boolean) = ShotMergeInput(
        tags = shot.tags,
        tagsPending = false,
        barista = shot.barista?.trim()?.ifEmpty { null },
        baristaPending = baristaPending,
        hasSeries = shot.samples.isNotEmpty(),
        hasProfile = shot.profile != null,
        isBrew = shot.isBrewLog,
    )

    /**
     * Merge a pulled copy onto the bound local [localId] through the core
     * (`merge_remote_shot`): tags only add, a pending barista edit wins, a curve
     * or recipe fills only a row without one, and nothing else changes. A row
     * edited since [before] (as the pull began) is newer here and is left
     * alone; true when such a row still lacks its curve, so the cursor holds.
     * Internal for the JVM tests.
     */
    internal fun mergeAndApply(
        localId: String,
        remote: JsonObject?,
        samplesJson: String? = null,
        profile: JsonObject? = null,
        before: ShotMergeInput? = null,
    ): Boolean {
        remote ?: return false
        val local = currentShot(localId) ?: return false
        val input = mergeInputOf(local, pendingEdits[localId]?.barista == true)
        if (before != null && editedDuringFetch(before, input)) return local.samples.isEmpty() && !local.isBrewLog
        val payload = buildJsonObject {
            put("local", json.encodeToJsonElement(ShotMergeInput.serializer(), input))
            put("remote", remote)
            put("profileOffered", JsonPrimitive(profile != null))
        }
        val patch = runCatching {
            json.decodeFromString(ShotPatch.serializer(), mergeRemote(json.encodeToString(JsonObject.serializer(), payload)))
        }.getOrElse { return false }
        val durationMs = (remote["duration_ms"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
        val samples = if (patch.backfillSamples == true) samplesJson?.let { parseTimedSamples(it, json) }.orEmpty() else emptyList()
        onApplyRemote(localId, patch, PulledParts(samples, durationMs, profile.takeIf { patch.fillProfile == true }))
        return false
    }

    // ── Bean / roaster sync (web BeanSync.runSync) ────────────────────────────

    /** True while a bean / roaster run is in flight (one at a time). */
    private val beanSyncMutex = Mutex()

    /**
     * One bean / roaster sync over the current library: [BeanSyncRunner] with
     * every request on the token-refreshing client, then the touched rows merged
     * back ([onBeansSynced]), the Premium flag + sync time cached, and the run's
     * first ten activity lines mirrored into the shared sync log (the web
     * `BeanSyncSection` does the same). Returns null when another run holds the lock.
     */
    internal suspend fun runBeanSync(): BeanSyncResult? {
        if (!beanSyncMutex.tryLock()) return null
        try {
            if (persisted.tokens == null) return BeanSyncResult(error = "Sign in to Visualizer first.")
            if (persisted.accountUnconfirmed) return BeanSyncResult(error = ACCOUNT_HELD_NOTICE)
            // A pull is fine, but nothing goes up to an account the user hasn't
            // said yes to, and the baseline waits for the push that's held.
            val asking = persisted.accountChangeAsk
            val runner = BeanSyncRunner(
                core = beanSyncCore,
                json = json,
                call = { method, path, body -> withFreshToken { client.request(method, path, it, body) } },
                now = now,
            )
            val result = runner.run(
                beanLibrary,
                editBeanLibrary,
                BeanSyncSettings(
                    lastSyncAt = persisted.beanLastSyncAt,
                    premium = persisted.premium,
                    beansDirection = if (asking) pullsOnly(persisted.prefs.beansDirection) else persisted.prefs.beansDirection,
                    roastersDirection = if (asking) pullsOnly(persisted.prefs.roastersDirection) else persisted.prefs.roastersDirection,
                    createsFrom = persisted.uploadExcludedBefore,
                ),
            )
            onBeansSynced()
            val failure = result.error?.takeIf { !result.ok }?.let {
                SyncLogEntry(direction = "pull", entity = "bean", id = "", name = "Bean sync", at = now(), error = it)
            }
            persist { st ->
                st.copy(
                    premium = result.premium,
                    beanLastSyncAt = result.lastSyncAt?.takeUnless { asking } ?: st.beanLastSyncAt,
                    log = (listOfNotNull(failure) + result.log.take(10) + st.log).take(20),
                )
            }
            return result
        } finally {
            beanSyncMutex.unlock()
        }
    }

    /**
     * Best-effort Visualizer DELETEs after a local delete the user asked to
     * mirror ("Also delete on Visualizer"), bags before their roaster (web
     * `bestEffortRemoteDelete`). Skipped on a free account (the endpoints are
     * Premium-only) or when signed out; a 404 counts as already gone; failures
     * only log — the local delete already happened.
     */
    fun deleteRemote(beanVisualizerIds: List<String>, roasterVisualizerId: String? = null, label: String = "") {
        if (persisted.premium == false || persisted.tokens == null || persisted.accountUnconfirmed) return
        if (beanVisualizerIds.isEmpty() && roasterVisualizerId == null) return
        scope.launch {
            val targets = beanVisualizerIds.map { "bean" to "/coffee_bags/$it" } +
                listOfNotNull(roasterVisualizerId?.let { "roaster" to "/roasters/$it" })
            for ((entity, path) in targets) {
                val id = path.substringAfterLast('/')
                val err = runCatchingCancellable { withFreshToken { client.request("DELETE", path, it) } }
                    .fold(onSuccess = { null }, onFailure = { e -> if (e is VisualizerError.NotFound) null else (e.message ?: "delete failed") })
                val entry = SyncLogEntry(direction = "delete", entity = entity, id = id, name = label.ifBlank { entity }, at = now(), error = err)
                persist { it.copy(log = (listOf(entry) + it.log).take(20)) }
            }
        }
    }

    /**
     * "Sync now" (web BeanSyncSection order): beans + roasters first when
     * either direction is on (one bidirectional run, as on the web), then the
     * shot pull and push per the shots direction. The shot pull cursor
     * advances ONLY on a successful pull — never on a push. Soft per-step: a
     * failure logs + notifies, the other steps still run.
     */
    fun syncNow(shots: List<StoredShot>, includeBeans: Boolean = true, fromStart: Boolean = false) {
        if (persisted.tokens == null) {
            notify("Sign in to Visualizer first (Settings → Sharing)")
            return
        }
        if (persisted.accountUnconfirmed) {
            notify(ACCOUNT_HELD_NOTICE)
            refreshAccount()
            return
        }
        // Beans don't touch the shot log, so they run without it.
        val shotsOn = shotsReady()
        val direction = if (shotsOn) persisted.prefs.shotsDirection else "off"
        val beansOn = includeBeans &&
            (persisted.prefs.beansDirection != "off" || persisted.prefs.roastersDirection != "off")
        if (!shotsOn && !(beansOn && historyRefused())) return
        if (direction == "off" && !beansOn) {
            notify("Sync is off — pick a direction first")
            return
        }
        _state.update { it.copy(syncing = true) }
        scope.launch {
            // The pull backfills curve-less shots: not before the stored
            // series have loaded.
            seriesReady()
            val parts = mutableListOf<String>()
            var failed = false
            // Remote deletes the user asked for that haven't landed yet.
            if (shotsOn) runCatchingCancellable { deleteRemoteShots() }
            if (beansOn) {
                runCatchingCancellable { runBeanSync() }
                    .onSuccess { r ->
                        if (r != null) {
                            parts += "beans ${r.pulled} pulled · ${r.pushed} pushed" + if (r.premiumLocked) " (read-only, free tier)" else ""
                            if (!r.ok || r.writesFailed > 0) failed = true
                        }
                    }
                    .onFailure { e ->
                        failed = true
                        logBeanFailure(e.message)
                    }
            }
            var pulled = 0
            var refused = 0
            var pushed = 0
            if (directionPulls(direction)) {
                runCatchingCancellable {
                    val result = pullRun(shots, fromStart = fromStart)
                    pulled = result.pulled
                    refused = result.refused
                    logRun(result)
                    if (result.outcome["kind"]?.jsonPrimitive?.contentOrNull !in setOf("Complete", "Busy")) failed = true
                }.onFailure { e ->
                    failed = true
                    logSync("pull", "", "Pull failed", e.message)
                }
            }
            var stop: PassStop? = null
            if (directionPushes(direction) && !uploadsHeld) {
                logNoTelemetry(shots)
                val unsynced = unsent(shots)
                val r = runUploadPass(
                    unsynced,
                    uploadPolicy,
                    onFailure = { shot, e -> logSync("skip", shot.id, shot.profileName ?: "Shot", uploadPolicy.describe(e)) },
                ) { shot -> pushTracked(shot) }
                pushed = r.uploaded
                if (r.failed > 0) failed = true
                stop = r.stop
            }
            if (direction != "off") {
                parts += buildString {
                    append("shots ")
                    if (directionPulls(direction)) append("$pulled pulled")
                    if (directionPulls(direction) && directionPushes(direction)) append(" · ")
                    if (directionPushes(direction)) append("$pushed pushed")
                }
            }
            _state.update { it.copy(syncing = false) }
            notify(
                buildString {
                    append("Visualizer sync: ")
                    append(parts.joinToString(" · "))
                    if (refused > 0) append(" · ${historyFullNotice(refused)}")
                    if (stop != null) append(" · ${stop.notice}")
                    else if (failed) append(" · some steps failed (see log)")
                },
            )
        }
    }

    /**
     * Wait for the rows and their series, up to [ROWS_WAIT_MS]. False when they
     * didn't arrive: the caller skips this run, spending nothing, and the user
     * is told once, not once per trigger.
     */
    private suspend fun rowsReady(): Boolean {
        if (withTimeoutOrNull(rowsWaitMs) { seriesReady() } != null) return true
        if (rowsLateTold.compareAndSet(false, true)) {
            notify("Shot data hasn’t loaded, so Visualizer uploads are waiting. They’ll retry on their own.")
        }
        return false
    }

    /** One line for the pass: these would otherwise fill the log on every sync. */
    private suspend fun logNoTelemetry(shots: List<StoredShot>) {
        val n = shots.count(::noTelemetry)
        if (n > 0) logSync("skip", "", "Upload", "$n shot${if (n == 1) " has" else "s have"} no telemetry to upload")
    }

    /**
     * The sign-in can load before the rows do, and these actions work from the
     * list the UI passes in: an empty one would pull every remote shot in as new.
     */
    private fun shotsReady(): Boolean {
        if (historyRefused()) {
            notify(HISTORY_REFUSED_NOTICE)
            return false
        }
        if (shotsLoaded()) return true
        notify("Shot data is still loading — try again in a moment")
        return false
    }

    private suspend fun logBeanFailure(error: String?) {
        val entry = SyncLogEntry(direction = "pull", entity = "bean", id = "", name = "Bean sync", at = now(), error = error ?: "failed")
        persist { it.copy(log = (listOf(entry) + it.log).take(20)) }
    }

    /**
     * Re-pull the entire Visualizer history from the beginning (web "Re-sync
     * shots"): clear the incremental cursor, then run a normal sync pass. The
     * reconcile planner de-duplicates against existing locals.
     */
    fun resyncAllShots(shots: List<StoredShot>) {
        if (!shotsReady()) return
        // Shots only — the web's "Re-sync shots" never touches beans. From the
        // start, forgetting the rows marked as failing.
        syncNow(shots, includeBeans = false, fromStart = true)
    }

    /** Upload every shot without a `visualizerId`, sequentially, with a summary. */
    fun uploadAllUnsynced(shots: List<StoredShot>) {
        if (persisted.tokens == null) {
            notify("Sign in to Visualizer first (Settings → Sharing)")
            return
        }
        if (uploadsHeld) {
            notify(ACCOUNT_HELD_NOTICE)
            return
        }
        if (!shotsReady()) return
        scope.launch { logNoTelemetry(shots) }
        val unsynced = unsent(shots)
        if (unsynced.isEmpty()) {
            notify("Everything is already on Visualizer")
            return
        }
        _state.update { it.copy(busy = true) }
        scope.launch {
            val r = runUploadPass(unsynced, uploadPolicy) { shot -> pushTracked(shot) }
            _state.update { it.copy(busy = false) }
            val summary = if (r.failed == 0) "Uploaded ${r.uploaded} shot(s) to Visualizer"
            else "Uploaded ${r.uploaded} shot(s); ${r.failed} failed"
            notify(r.stop?.let { "$summary — ${it.notice}" } ?: summary)
        }
    }
}

/** The lasting line when the server ends the session. */
const val VISUALIZER_RECONNECT_LINE = "Visualizer ended this sign-in. Sign in again to keep syncing. Nothing on this device is lost."

/**
 * The id arrived for a shot deleted here: from its upload finishing, or from a
 * pull finding its row. It goes on a tombstone, always, so an upload landing
 * after a delete is still caught, carrying the intent an unbound one recorded.
 */
internal fun VisualizerState.withTombstoneBound(shotId: String, visualizerId: String, now: Long): VisualizerState {
    val unbound = unboundTombstones.firstOrNull { it.id == shotId }
    val tombstone = ShotTombstone(
        id = shotId,
        visualizerId = visualizerId,
        deletedAt = unbound?.deletedAt ?: now,
        deleteRemote = unbound?.deleteRemote ?: false,
        parkedFor = unbound?.parkedFor,
    )
    return copy(
        shotTombstones = shotTombstones.filterNot { it.id == shotId } + tombstone,
        unboundTombstones = unboundTombstones.filterNot { it.id == shotId },
    )
}

/**
 * The equipment-level grinder model for [shot]'s upload / PATCH: the shot's
 * own (an imported or restored shot carries the grinder it was pulled on),
 * else the Settings default. Blank counts as absent. Mirrors the web
 * `resolveGrinderModel`.
 */
internal fun resolveGrinderModel(shot: StoredShot, settingsDefault: String?): String? =
    shot.grinderModel?.trim()?.takeIf { it.isNotEmpty() }
        ?: settingsDefault?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The shell-side inputs of an edit-sync PATCH (web `annotationsPatch`); the
 * body itself is assembled in the core. [sendJournal] off leaves out every
 * journal field, for a PATCH that carries only the barista. Pure, for the JVM
 * tests.
 */
internal fun shotPatchInputs(
    shot: StoredShot,
    includeNotes: Boolean,
    defaultPrivacy: String?,
    settingsGrinderModel: String?,
    sendBarista: Boolean = false,
    sendJournal: Boolean = true,
): ShotPatchInputs = ShotPatchInputs(
    rating = shot.rating?.toUByte()?.takeIf { sendJournal },
    notes = if (includeNotes && sendJournal) (shot.notes ?: "") else null,
    privacy = (shot.privacy ?: defaultPrivacy)?.takeIf { sendJournal },
    grinderModel = resolveGrinderModel(shot, settingsGrinderModel)?.takeIf { sendJournal },
    // Android has no tag editor, so it never sends tags: null omits
    // `tag_list`, which is an absolute list, and `[]` would clear the remote tags.
    tagList = null,
    // Inline bean from the structured snapshot frozen at shot time (issue 06).
    beanBrand = shot.bean?.roasterName?.takeIf { sendJournal },
    beanType = shot.bean?.name?.takeIf { sendJournal },
    roastDate = shot.bean?.roastedOn?.takeIf { sendJournal },
    roastLevel = shot.bean?.roastLevel?.toDouble()?.takeIf { sendJournal },
    // The shot's own recorded/edited grind wins over the bean's
    // reference setting — the same precedence History displays
    // (issue #16: a grind edit must reach the uploaded copy).
    grinderSetting = shot.effectiveGrindSetting?.takeIf { sendJournal },
    // Who made it → `barista` (a free-account field; no drinker field).
    // Only after a barista edit ([sendBarista]); null = the core leaves the
    // remote value alone. Empty once cleared, so a clear reaches Visualizer.
    barista = if (sendBarista) baristaOf(shot) else null,
)

/**
 * Whether a row changed while its copy was fetched (core `edited_during_fetch`):
 * its tags, its barista, or what is pending on them.
 */
internal fun editedDuringFetch(was: ShotMergeInput, now: ShotMergeInput): Boolean =
    was.tags.orEmpty() != now.tags.orEmpty() ||
        (was.tagsPending ?: false) != (now.tagsPending ?: false) ||
        was.barista.orEmpty() != now.barista.orEmpty() ||
        (was.baristaPending ?: false) != (now.baristaPending ?: false)

/**
 * What a pull left in the history: rows added, new rows a full one refused,
 * whether the core planned it, and rows edited mid-pull that still lack their
 * curve, so the cursor holds.
 */
internal data class PullApplied(val pulled: Int = 0, val refused: Int = 0, val planned: Boolean = true, val held: Int = 0)

/** What a pull fetched besides the detail, for [VisualizerSync]'s `onApplyRemote`. */
data class PulledParts(
    /** The pulled telemetry, when the merge takes it. */
    val samples: List<coffee.crema.ui.TelemetrySample> = emptyList(),
    /** The remote duration, ms, kept with a backfilled curve. */
    val durationMs: Long = 0,
    /** The pulled recipe, when the merge takes it. */
    val profile: JsonObject? = null,
)

/** A shot's barista as the PATCH sends it: trimmed, "" when unset or cleared. */
internal fun baristaOf(shot: StoredShot): String = shot.barista?.trim().orEmpty()

