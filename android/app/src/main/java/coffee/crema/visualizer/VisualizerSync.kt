package coffee.crema.visualizer

import coffee.crema.core.ShotPatchInputs
import coffee.crema.core.exportV2JsonShot
import coffee.crema.core.signatureForShot
import coffee.crema.core.visualizerShotPatchJson
import coffee.crema.core.VisualizerSyncPrefs
import coffee.crema.history.StoredShot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
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
 * v1 scope = push ("backup" direction): sign in (PKCE via the system
 * browser), `/me` account, per-shot + bulk upload with the same wire bytes
 * as the web (core `export_v2_json_shot` over the FFI + the
 * `metadata.crema{localId, signature}` escape valve). Pull/reconcile and
 * bean/roaster sync stay web-only for now (documented backlog).
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
) {

    private companion object {
        /** Backstop on the pull walk — mirrors the web's maxPages default. */
        const val MAX_PULL_PAGES = 200
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

    private var persisted = VisualizerState()
    private val persistMutex = Mutex()

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
                log = p.log,
            )
        }
    }

    private suspend fun persist(mutate: (VisualizerState) -> VisualizerState) {
        persistMutex.withLock {
            persisted = mutate(persisted)
            store.save(persisted)
        }
        fold()
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
                    runCatching { exchangeCodeForToken(clientId, code, verifier, json) }
                        .onSuccess { tokens ->
                            persist { it.copy(tokens = tokens) }
                            val account = runCatching { client.fetchAccount(tokens.accessToken) }.getOrNull()
                            persist { it.copy(account = account) }
                            notify("Signed in to Visualizer${account?.let { a -> " as ${a.name}" }.orEmpty()}")
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
            persist { it.copy(tokens = null, account = null) }
            notify("Signed out of Visualizer")
        }
    }

    /** Re-fetch `/me` (Settings open). Silent on failure — the cache stands. */
    fun refreshAccount() {
        if (persisted.tokens == null) return
        scope.launch {
            runCatching { withFreshToken { client.fetchAccount(it) } }
                .onSuccess { account -> persist { it.copy(account = account) } }
        }
    }

    /** The Sharing card's "Test" — a `/me` round-trip with a visible verdict. */
    fun testConnection() {
        if (persisted.tokens == null) {
            notify("Not signed in to Visualizer")
            return
        }
        _state.update { it.copy(busy = true) }
        scope.launch {
            runCatching { withFreshToken { client.fetchAccount(it) } }
                .onSuccess { account ->
                    persist { it.copy(account = account) }
                    notify("Visualizer connection OK — signed in as ${account.name}")
                }
                .onFailure { notify("Visualizer connection failed: ${it.message}") }
            _state.update { it.copy(busy = false) }
        }
    }

    // ── Preferences ──────────────────────────────────────────────────────────

    fun setAutoSync(enabled: Boolean) = scope.launch { persist { it.copy(prefs = it.prefs.copy(autoUpload = enabled)) } }
    fun setShotsDirection(direction: String) = scope.launch { persist { it.copy(prefs = it.prefs.copy(shotsDirection = direction)) } }
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
        return runCatching { refreshAccessToken(clientId, refresh, json) }
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
        val v2 = exportV2JsonShot(json.encodeToString(JsonObject.serializer(), wire))
        val doc = json.parseToJsonElement(v2).jsonObject.toMutableMap()
        if (!persisted.prefs.includeProfile) doc.remove("profile")
        val metadata = (doc["metadata"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (!persisted.prefs.includeNotes && metadata.containsKey("notes")) {
            metadata["notes"] = JsonNull
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

    /** Upload one shot in the background; stamps `visualizerId` on success. */
    fun uploadShot(shot: StoredShot, silent: Boolean = false) {
        if (persisted.tokens == null) {
            if (!silent) notify("Sign in to Visualizer first (Settings → Sharing)")
            return
        }
        _state.update { it.copy(uploadingShotIds = it.uploadingShotIds + shot.id) }
        scope.launch {
            runCatching { uploadShotNow(shot) }
                .onSuccess { id ->
                    if (!silent) notify("Shot uploaded to Visualizer")
                    onShotSynced(shot.id, id)
                }
                .onFailure { if (!silent) notify("Visualizer upload failed: ${it.message}") }
            _state.update { it.copy(uploadingShotIds = it.uploadingShotIds - shot.id) }
        }
    }

    /** The suspend upload itself — payload → POST → lastSyncAt + log line. */
    private suspend fun uploadShotNow(shot: StoredShot): String {
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
     * the effective privacy. No-op for never-uploaded shots; soft — a
     * failure notifies but never blocks the local edit.
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
            // Inline bean from the structured snapshot frozen at shot time (issue 06).
            beanBrand = shot.bean?.roasterName,
            beanType = shot.bean?.name,
            roastDate = shot.bean?.roastedOn,
            roastLevel = shot.bean?.roastLevel?.toDouble(),
            grinderSetting = shot.bean?.grinderSetting,
        )
        val body = runCatching {
            json.decodeFromString(
                JsonObject.serializer(),
                visualizerShotPatchJson(json.encodeToString(ShotPatchInputs.serializer(), inputs)),
            )
        }.getOrElse { notify("Visualizer update failed: ${it.message}"); return }
        scope.launch {
            runCatching { withFreshToken { client.patchShot(it, vid, body) } }
                .onFailure { notify("Visualizer update failed: ${it.message}") }
        }
    }

    /** Called on `ShotCompleted` — uploads when auto-sync is armed, signed in,
     *  and the shots direction pushes. */
    fun maybeAutoUpload(shot: StoredShot) {
        if (persisted.prefs.autoUpload && persisted.tokens != null && directionPushes(persisted.prefs.shotsDirection)) {
            uploadShot(shot, silent = true)
        }
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
                val detail = runCatching { withFreshToken { client.fetchShotDetail(it, summary.id) } }
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
                val wire = runCatching {
                    json.parseToJsonElement(
                        coffee.crema.core.wireShotFromDetail(json.encodeToString(JsonObject.serializer(), payload)),
                    ).jsonObject
                }.getOrNull() ?: continue
                wires += wire
                runCatching { coffee.crema.core.samplesFromVisualizerDetail(detailStr) }
                    .getOrNull()?.let { samplesById[summary.id] = it }
                // Pull the recipe too (the detail carries no step list) so the shot
                // is self-contained (#12); a profile-less stub / error → name-only.
                runCatching {
                    val v2 = withFreshToken {
                        client.request("GET", "/shots/${summary.id}/profile?format=json", it)
                    } ?: return@runCatching null
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
        val actions = runCatching {
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

    /**
     * "Sync now": pull-then-push per the shots direction (web BeanSyncSection
     * order). The pull cursor advances ONLY on a successful pull — never on a
     * push. Soft per-step: a failure logs + notifies, the other step still runs.
     */
    fun syncNow(shots: List<StoredShot>) {
        if (persisted.tokens == null) {
            notify("Sign in to Visualizer first (Settings → Sharing)")
            return
        }
        val direction = persisted.prefs.shotsDirection
        if (direction == "off") {
            notify("Shot sync is off — pick a direction first")
            return
        }
        _state.update { it.copy(syncing = true) }
        scope.launch {
            var pulled = 0
            var pushed = 0
            var failed = false
            if (directionPulls(direction)) {
                runCatching {
                    val since = persisted.shotPullCursor ?: 0L
                    val (wires, samples, profiles) = pullAllShotsSince(since)
                    pulled = reconcileAndApply(shots, wires, samples, profiles)
                    persist { it.copy(shotPullCursor = System.currentTimeMillis()) }
                }.onFailure { e ->
                    failed = true
                    logSync("pull", "", "Pull failed", e.message)
                }
            }
            if (directionPushes(direction)) {
                val unsynced = shots.filter { it.visualizerId == null }
                for (shot in unsynced) {
                    _state.update { it.copy(uploadingShotIds = it.uploadingShotIds + shot.id) }
                    runCatching { uploadShotNow(shot) }
                        .onSuccess { id ->
                            pushed++
                            onShotSynced(shot.id, id)
                        }
                        .onFailure { e ->
                            failed = true
                            logSync("skip", shot.id, shot.profileName ?: "Shot", e.message)
                        }
                    _state.update { it.copy(uploadingShotIds = it.uploadingShotIds - shot.id) }
                }
            }
            _state.update { it.copy(syncing = false) }
            notify(
                buildString {
                    append("Visualizer sync: ")
                    if (directionPulls(direction)) append("$pulled pulled")
                    if (directionPulls(direction) && directionPushes(direction)) append(" · ")
                    if (directionPushes(direction)) append("$pushed pushed")
                    if (failed) append(" · some steps failed (see log)")
                },
            )
        }
    }

    /**
     * Re-pull the entire Visualizer history from the beginning (web "Re-sync
     * shots"): clear the incremental cursor, then run a normal sync pass. The
     * reconcile planner de-duplicates against existing locals.
     */
    fun resyncAllShots(shots: List<StoredShot>) {
        scope.launch {
            persist { it.copy(shotPullCursor = null) }
            syncNow(shots)
        }
    }

    /** Upload every shot without a `visualizerId`, sequentially, with a summary. */
    fun uploadAllUnsynced(shots: List<StoredShot>) {
        if (persisted.tokens == null) {
            notify("Sign in to Visualizer first (Settings → Sharing)")
            return
        }
        val unsynced = shots.filter { it.visualizerId == null }
        if (unsynced.isEmpty()) {
            notify("Everything is already on Visualizer")
            return
        }
        _state.update { it.copy(busy = true) }
        scope.launch {
            var ok = 0
            var failed = 0
            for (shot in unsynced) {
                _state.update { it.copy(uploadingShotIds = it.uploadingShotIds + shot.id) }
                runCatching { uploadShotNow(shot) }
                    .onSuccess { id ->
                        ok++
                        onShotSynced(shot.id, id)
                    }
                    .onFailure { failed++ }
                _state.update { it.copy(uploadingShotIds = it.uploadingShotIds - shot.id) }
            }
            _state.update { it.copy(busy = false) }
            notify(
                if (failed == 0) "Uploaded $ok shot(s) to Visualizer"
                else "Uploaded $ok shot(s); $failed failed",
            )
        }
    }
}
