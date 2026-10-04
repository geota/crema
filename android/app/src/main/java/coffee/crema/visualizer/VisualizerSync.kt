package coffee.crema.visualizer

import coffee.crema.core.CataloguePage
import coffee.crema.core.ShotPatchInputs
import coffee.crema.core.parseCatalogueCoffeeBags
import coffee.crema.core.parseCatalogueRoasters
import coffee.crema.core.CatalogueRoasterPage
import coffee.crema.runCatchingCancellable
import coffee.crema.core.exportV2JsonShot
import coffee.crema.core.exportV2JsonShotFull
import coffee.crema.core.signatureForShot
import coffee.crema.core.visualizerShotPatchJson
import coffee.crema.core.VisualizerSyncPrefs
import coffee.crema.history.StoredShot
import coffee.crema.history.effectiveGrindSetting
import coffee.crema.history.isBrewLog
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

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
    /** Insert pulled remote stubs into the local history (dedup by id). */
    private val onPulledShots: (List<StoredShot>) -> Unit = {},
    /** Backfill a bound local's telemetry from a pull (no-op when it has a curve). */
    private val onBackfillTelemetry: (localId: String, samples: List<coffee.crema.ui.TelemetrySample>, durationMs: Long) -> Unit = { _, _, _ -> },
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
    /** The bean library as it stands now — the bean sync's starting snapshot. */
    private val beanLibrary: () -> coffee.crema.beans.BeanLibrary = { coffee.crema.beans.BeanLibrary() },
    /**
     * Merge a finished bean sync back into the library: the rows it touched
     * ([BeanSyncResult.beans] / [BeanSyncResult.roasters]) and the ids the run
     * started from (see [mergeSyncedRows]).
     */
    private val onBeansSynced: (result: BeanSyncResult, snapshotBeanIds: Set<String>, snapshotRoasterIds: Set<String>) -> Unit = { _, _, _ -> },
    /** The core's bean-sync surface (injected so JVM tests needn't load the native core). */
    private val beanSyncCore: BeanSyncCore = BeanSyncCore.Native,
) : UploadDestination {

    internal companion object {
        /** Backstop on the pull walk — mirrors the web's maxPages default. */
        const val MAX_PULL_PAGES = 200

        /** The app-start Premium re-probe runs at most this often (web `PREMIUM_REFRESH_INTERVAL_MS`). */
        const val PREMIUM_REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000
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
        /** True while a sign-in exchange or bulk upload runs. */
        val busy: Boolean = false,
        /** True while a Sync-now pull/push pass runs. */
        val syncing: Boolean = false,
        /** Shot ids with an upload in flight (History pip spinners). */
        val uploadingShotIds: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(UiState(configured = clientId.isNotBlank()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile
    private var persisted = VisualizerState()
    private val persistMutex = Mutex()

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
    override val enabled: Boolean get() = persisted.tokens != null && directionPushes(persisted.prefs.shotsDirection)
    override val autoUpload: Boolean get() = persisted.prefs.autoUpload
    override fun isUploaded(shot: StoredShot): Boolean = shot.visualizerId != null
    override fun viewUrl(shot: StoredShot): String? = shot.visualizerId?.let { "https://visualizer.coffee/shots/$it" }
    /** Brew Log rows (issue #10) are local-only — never in the Visualizer backlog. */
    override fun inBacklog(shot: StoredShot): Boolean = shot.visualizerId == null && !shot.isBrewLog
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

    /** Hydrate from disk at startup (called from the VM's init coroutine). */
    suspend fun load() {
        persisted = store.load()
        fold()
    }

    private fun fold() {
        val p = persisted
        _state.update {
            it.copy(
                configured = clientId.isNotBlank(),
                signedIn = p.tokens != null,
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
        val ok = persistMutex.withLock {
            persisted = mutate(persisted)
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
            val verifier = persisted.pendingVerifier
            val expected = persisted.pendingState
            // Single-use — clear the handshake whatever happens next.
            persist { it.copy(pendingVerifier = null, pendingState = null) }
            when {
                error != null -> notify("Visualizer sign-in was cancelled")
                code == null -> notify("Visualizer sign-in failed — no code returned")
                verifier == null -> notify("Visualizer sign-in expired — try again")
                expected == null || expected != returnedState ->
                    notify("Visualizer sign-in failed — state mismatch, try again")
                else -> {
                    _state.update { it.copy(busy = true) }
                    runCatchingCancellable { exchangeCode(code, verifier) }
                        .onSuccess { tokens ->
                            if (!persist { it.copy(tokens = tokens) }) notify("Couldn’t save the Visualizer login on this device")
                            val account = runCatchingCancellable { client.fetchAccount(tokens.accessToken) }.getOrNull()
                            persist { it.copy(account = account) }
                            refreshPremium()
                            notify("Signed in to Visualizer${account?.let { a -> " as ${a.name}" }.orEmpty()}")
                            onSignedIn()
                        }
                        .onFailure { notify("Visualizer sign-in failed: ${it.message}") }
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    /** Revoke (best-effort) + forget tokens and the cached account. */
    fun signOut() {
        scope.launch {
            persisted.tokens?.accessToken?.let { revokeToken(clientId, it) }
            // The tier is per-account: a later sign-in re-probes it.
            persist { it.copy(tokens = null, account = null, premium = null, premiumCheckedAt = null) }
            notify("Signed out of Visualizer")
        }
    }

    /** Re-fetch `/me` (Settings open). Silent on failure — the cache stands. */
    fun refreshAccount() {
        if (persisted.tokens == null) return
        scope.launch {
            runCatchingCancellable { withFreshToken { client.fetchAccount(it) } }
                .onSuccess { account -> persist { it.copy(account = account) } }
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
        val p = persisted
        if (p.tokens == null) return
        val checked = p.premiumCheckedAt
        if (checked != null && now() - checked < PREMIUM_REFRESH_INTERVAL_MS) return
        scope.launch { refreshPremium() }
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
     * [VisualizerError.Auth] when there is no session or the refresh fails.
     */
    private suspend fun <T> withFreshToken(block: suspend (String) -> T): T {
        var tokens = persisted.tokens ?: throw VisualizerError.Auth()
        if (tokens.expiresAt - 60_000 < System.currentTimeMillis()) {
            tokens = refreshOrSignOut(tokens) ?: throw VisualizerError.Auth()
        }
        return try {
            block(tokens.accessToken)
        } catch (e: VisualizerError.Auth) {
            val fresh = refreshOrSignOut(tokens) ?: throw e
            block(fresh.accessToken)
        }
    }

    /** Refresh + persist, or clear the session when the grant is gone. */
    private suspend fun refreshOrSignOut(current: TokenSet): TokenSet? {
        val refresh = current.refreshToken ?: run {
            persist { it.copy(tokens = null) }
            return null
        }
        return runCatchingCancellable { refreshTokens(refresh) }
            .onSuccess { fresh -> persist { it.copy(tokens = fresh) } }
            .getOrElse {
                persist { it.copy(tokens = null) }
                notify("Visualizer session expired — sign in again")
                null
            }
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
            result.onSuccess { id -> onShotSynced(shot.id, id) }
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
                    val id = uploadShotNow(shot)
                    onShotSynced(shot.id, id)
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

    /** One backlog upload with the History spinner on; stamps the id. Throws on failure. */
    private suspend fun pushTracked(shot: StoredShot): Boolean {
        _state.update { it.copy(uploadingShotIds = it.uploadingShotIds + shot.id) }
        try {
            onShotSynced(shot.id, uploadShotNow(shot))
            return true
        } finally {
            _state.update { it.copy(uploadingShotIds = it.uploadingShotIds - shot.id) }
        }
    }

    /** The suspend upload itself — payload → POST → lastSyncAt + log line. */
    private suspend fun uploadShotNow(shot: StoredShot): String {
        // Backstop: every caller filters brews out first (issue #10).
        require(!shot.isBrewLog) { "Brew Log rows are never uploaded to Visualizer" }
        val payload = buildShotPayload(shot)
        val id = withFreshToken { client.uploadShot(it, payload) }
        persist { it.copy(lastShotSyncAt = System.currentTimeMillis()) }
        logSync("push", shot.id, shot.profileName ?: "Shot")
        return id
    }

    /**
     * Mirror a History-panel edit onto the shot's already-uploaded Visualizer
     * copy (web ShotSync.patchEditedShot). The body assembly — rating →
     * `flavor` with a cleared rating OMITTED, the full inline-bean block
     * (brand/type/roast date/roast level/grinder setting — this builder
     * previously sent only brand+type), key names, blank-skipping — lives in
     * the core (`de1_domain::visualizer_shot_patch`, review #42) so both
     * shells emit an identical wire body. What stays here is the config:
     * notes gated on include-notes (an opt-out never leaks via an edit) and
     * the effective privacy, and the cached premium tier (Premium-only fields
     * are dropped for a free / unknown account; nothing left = no request).
     * No-op for never-uploaded shots; soft — a failure notifies but never
     * blocks the local edit.
     */
    fun patchEditedShot(shot: StoredShot) {
        val vid = shot.visualizerId ?: return
        if (persisted.tokens == null) return
        if (!directionPushes(persisted.prefs.shotsDirection)) return
        val inputs = ShotPatchInputs(
            rating = shot.rating?.toUByte(),
            notes = if (persisted.prefs.includeNotes) (shot.notes ?: "") else null,
            privacy = shot.privacy ?: persisted.prefs.privacy,
            grinderModel = grinderModel(),
            // Android has no shot tags; empty = the core omits `tag_list`
            // (the web sends `patch.tagList ?? []` the same way).
            tagList = emptyList(),
            // Inline bean from the structured snapshot frozen at shot time (issue 06).
            beanBrand = shot.bean?.roasterName,
            beanType = shot.bean?.name,
            roastDate = shot.bean?.roastedOn,
            roastLevel = shot.bean?.roastLevel?.toDouble(),
            // The shot's own recorded/edited grind wins over the bean's
            // reference setting — the same precedence History displays
            // (issue #16: a grind edit must reach the uploaded copy).
            grinderSetting = shot.effectiveGrindSetting,
        )
        // Fitted to the account tier in core: a free account — or one whose tier
        // isn't probed yet — silently loses the Premium-only fields
        // (private_notes, the tasting scores incl. flavor, tag_list,
        // coffee_bag_id …); null = nothing left the server would apply, so no
        // request and no notice.
        val body = runCatchingCancellable {
            shotPatchBody(json.encodeToString(ShotPatchInputs.serializer(), inputs), persisted.premium)
                ?.let { json.decodeFromString(JsonObject.serializer(), it) }
        }.getOrElse { notify("Visualizer update failed: ${it.message}"); return } ?: return
        scope.launch {
            runCatchingCancellable { withFreshToken { client.patchShot(it, vid, body) } }
                .onFailure { notify("Visualizer update failed: ${it.message}") }
        }
    }

    /** Called on `ShotCompleted` — uploads when auto-sync is armed, signed in,
     *  and the shots direction pushes. Verbose: the upload snackbar confirms
     *  the push (and surfaces a failure — a silent miss left users believing
     *  the shot was synced), per the issue #44 follow-up ask. */
    override fun maybeAutoUpload(shot: StoredShot, fullSamples: List<TelemetrySample>?): Boolean {
        if (persisted.prefs.autoUpload && persisted.tokens != null && directionPushes(persisted.prefs.shotsDirection)) {
            return uploadShot(shot)
        }
        return false
    }

    // ── Pull / reconcile (web pullAllShotsSince + applyShotReconciliation) ──

    /**
     * Walk `GET /shots` newest-updated-first, collecting every shot updated at
     * or after [sinceMs] as core `WireShot` JSON (+ reconstructed telemetry).
     * Stops at the cursor, page exhaustion, or the 200-page cap. One failed
     * detail fetch skips that row (auth failures abort — no point continuing).
     */
    private suspend fun pullAllShotsSince(
        sinceMs: Long,
    ): Triple<List<JsonObject>, Map<String, String>, Map<String, JsonObject>> {
        val cursorSec = sinceMs / 1000
        val wires = mutableListOf<JsonObject>()
        val samplesById = mutableMapOf<String, String>()
        val profilesById = mutableMapOf<String, JsonObject>()
        var page = 1
        var totalPages = 1
        var reachedOlder = false
        while (!reachedOlder && page <= totalPages.coerceAtMost(MAX_PULL_PAGES)) {
            val (summaries, pages) = withFreshToken { client.listShots(it, page, 50) }
            totalPages = pages
            if (summaries.isEmpty()) break
            for (summary in summaries) {
                if (summary.updatedAtSec < cursorSec) {
                    // Sorted by updated_at desc — crossing the cursor ends the walk.
                    reachedOlder = true
                    continue
                }
                val detail = runCatchingCancellable { withFreshToken { client.fetchShotDetail(it, summary.id) } }
                    .getOrElse { e ->
                        if (e is VisualizerError.Auth) throw e
                        continue
                    }
                val detailStr = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), detail)
                val payload = buildJsonObject {
                    put(
                        "summary",
                        buildJsonObject {
                            put("id", JsonPrimitive(summary.id))
                            put("clock", JsonPrimitive(summary.clockSec))
                            put("updated_at", JsonPrimitive(summary.updatedAtSec))
                        },
                    )
                    put("detail", detail)
                }
                val wire = runCatchingCancellable {
                    json.parseToJsonElement(
                        coffee.crema.core.wireShotFromDetail(json.encodeToString(JsonObject.serializer(), payload)),
                    ).jsonObject
                }.getOrNull() ?: continue
                wires += wire
                runCatchingCancellable { coffee.crema.core.samplesFromVisualizerDetail(detailStr) }
                    .getOrNull()?.let { samplesById[summary.id] = it }
                // Pull the recipe too (the detail carries no step list) so the shot
                // is self-contained (#12); a profile-less stub / error → name-only.
                runCatchingCancellable {
                    val v2 = withFreshToken {
                        client.request("GET", "/shots/${summary.id}/profile?format=json", it)
                    } ?: return@runCatchingCancellable null
                    json.parseToJsonElement(coffee.crema.core.parseV2Profile(v2.toString())).jsonObject
                }.getOrNull()?.let { profilesById[summary.id] = it }
            }
            page++
        }
        return Triple(wires, samplesById, profilesById)
    }

    /**
     * Reconcile pulled wires against the local history via the CORE planner
     * (`reconcile_shots` over the FFI — the identical algorithm the web runs)
     * and apply the actions through the VM callbacks. Returns pulled-count.
     */
    private suspend fun reconcileAndApply(localShots: List<StoredShot>, wires: List<JsonObject>, samplesById: Map<String, String>, profilesById: Map<String, JsonObject>): Int {
        if (wires.isEmpty()) return 0
        val payload = buildJsonObject {
            put(
                "local",
                kotlinx.serialization.json.buildJsonArray {
                    localShots.forEach { shot ->
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
                },
            )
            put("remote", kotlinx.serialization.json.JsonArray(wires))
        }
        val actions = runCatchingCancellable {
            json.parseToJsonElement(
                coffee.crema.core.reconcileShots(json.encodeToString(JsonObject.serializer(), payload)),
            ) as kotlinx.serialization.json.JsonArray
        }.getOrElse { return 0 }
        var pulled = 0
        val stubs = mutableListOf<StoredShot>()
        for (el in actions) {
            val action = el as? JsonObject ?: continue
            fun fld(k: String) = (action[k] as? JsonPrimitive)?.contentOrNull
            when (fld("kind")) {
                "add" -> {
                    val remote = action["remote"] as? JsonObject ?: continue
                    val vid = (remote["id"] as? JsonPrimitive)?.contentOrNull
                    storedShotFromWire(remote, vid?.let { samplesById[it] }, json)?.let { stub ->
                        // Attach the recipe fetched at pull time (#12) so the shot is
                        // self-contained; absent for profile-less remotes.
                        val withProfile = vid?.let { profilesById[it] }?.let { stub.copy(profile = it) } ?: stub
                        stubs += withProfile
                        pulled++
                        logSync("pull", stub.id, stub.profileName ?: "Shot")
                    }
                }
                "bind" -> {
                    val localId = fld("localId") ?: continue
                    val vid = fld("visualizerId") ?: continue
                    onShotSynced(localId, vid)
                    logSync("pull", localId, "Shot (bound)")
                }
                "update" -> {
                    val localId = fld("localId") ?: continue
                    val remote = action["remote"] as? JsonObject ?: continue
                    val vid = (remote["id"] as? JsonPrimitive)?.contentOrNull
                    val durationMs = (remote["duration_ms"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
                    vid?.let { samplesById[it] }?.let { samplesJson ->
                        val samples = parseTimedSamples(samplesJson, json)
                        if (samples.isNotEmpty()) onBackfillTelemetry(localId, samples, durationMs)
                    }
                }
            }
        }
        if (stubs.isNotEmpty()) onPulledShots(stubs)
        return pulled
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
            val snapshot = beanLibrary()
            val runner = BeanSyncRunner(
                core = beanSyncCore,
                json = json,
                call = { method, path, body -> withFreshToken { client.request(method, path, it, body) } },
                now = now,
            )
            val result = runner.run(
                snapshot.beans,
                snapshot.roasters,
                BeanSyncSettings(
                    lastSyncAt = persisted.beanLastSyncAt,
                    premium = persisted.premium,
                    beansDirection = persisted.prefs.beansDirection,
                    roastersDirection = persisted.prefs.roastersDirection,
                ),
            )
            onBeansSynced(result, snapshot.beans.mapTo(HashSet()) { it.id }, snapshot.roasters.mapTo(HashSet()) { it.id })
            val failure = result.error?.takeIf { !result.ok }?.let {
                SyncLogEntry(direction = "pull", entity = "bean", id = "", name = "Bean sync", at = now(), error = it)
            }
            persist { st ->
                st.copy(
                    premium = result.premium,
                    beanLastSyncAt = result.lastSyncAt ?: st.beanLastSyncAt,
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
        if (persisted.premium == false || persisted.tokens == null) return
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
    fun syncNow(shots: List<StoredShot>, includeBeans: Boolean = true) {
        if (persisted.tokens == null) {
            notify("Sign in to Visualizer first (Settings → Sharing)")
            return
        }
        val direction = persisted.prefs.shotsDirection
        val beansOn = includeBeans &&
            (persisted.prefs.beansDirection != "off" || persisted.prefs.roastersDirection != "off")
        if (direction == "off" && !beansOn) {
            notify("Sync is off — pick a direction first")
            return
        }
        _state.update { it.copy(syncing = true) }
        scope.launch {
            val parts = mutableListOf<String>()
            var failed = false
            if (beansOn) {
                runCatchingCancellable { runBeanSync() }
                    .onSuccess { r ->
                        if (r != null) {
                            parts += "beans ${r.pulled} pulled · ${r.pushed} pushed" + if (r.premiumLocked) " (read-only, free tier)" else ""
                            if (!r.ok) failed = true
                        }
                    }
                    .onFailure { e ->
                        failed = true
                        logBeanFailure(e.message)
                    }
            }
            var pulled = 0
            var pushed = 0
            if (directionPulls(direction)) {
                runCatchingCancellable {
                    val since = persisted.shotPullCursor ?: 0L
                    val (wires, samples, profiles) = pullAllShotsSince(since)
                    pulled = reconcileAndApply(shots, wires, samples, profiles)
                    persist { it.copy(shotPullCursor = System.currentTimeMillis()) }
                }.onFailure { e ->
                    failed = true
                    logSync("pull", "", "Pull failed", e.message)
                }
            }
            var stop: PassStop? = null
            if (directionPushes(direction)) {
                val unsynced = shots.filter { it.visualizerId == null && !it.isBrewLog }
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
                    if (stop != null) append(" · ${stop.notice}")
                    else if (failed) append(" · some steps failed (see log)")
                },
            )
        }
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
        scope.launch {
            persist { it.copy(shotPullCursor = null) }
            // Shots only — the web's "Re-sync shots" never touches beans.
            syncNow(shots, includeBeans = false)
        }
    }

    /** Upload every shot without a `visualizerId`, sequentially, with a summary. */
    fun uploadAllUnsynced(shots: List<StoredShot>) {
        if (persisted.tokens == null) {
            notify("Sign in to Visualizer first (Settings → Sharing)")
            return
        }
        val unsynced = shots.filter { it.visualizerId == null && !it.isBrewLog }
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
