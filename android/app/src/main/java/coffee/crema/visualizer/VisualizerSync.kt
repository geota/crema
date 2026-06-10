package coffee.crema.visualizer

import coffee.crema.core.exportV2JsonShot
import coffee.crema.core.signatureForShot
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
) {

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
        /** True while a sign-in exchange or bulk upload runs. */
        val busy: Boolean = false,
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
                autoSync = p.autoSync,
                privacy = p.privacy,
                includeProfile = p.includeProfile,
                includeNotes = p.includeNotes,
                lastShotSyncAt = p.lastShotSyncAt,
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

    fun setAutoSync(enabled: Boolean) = scope.launch { persist { it.copy(autoSync = enabled) } }
    fun setPrivacy(privacy: String) = scope.launch { persist { it.copy(privacy = privacy) } }
    fun setIncludeProfile(enabled: Boolean) = scope.launch { persist { it.copy(includeProfile = enabled) } }
    fun setIncludeNotes(enabled: Boolean) = scope.launch { persist { it.copy(includeNotes = enabled) } }

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
        val wire = wireShotJson(shot)
        val v2 = exportV2JsonShot(json.encodeToString(JsonObject.serializer(), wire))
        val doc = json.parseToJsonElement(v2).jsonObject.toMutableMap()
        if (!persisted.includeProfile) doc.remove("profile")
        val metadata = (doc["metadata"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (!persisted.includeNotes && metadata.containsKey("notes")) {
            metadata["notes"] = JsonNull
        }
        doc["privacy"] = JsonPrimitive(persisted.privacy)
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

    /** The suspend upload itself — payload → POST → lastSyncAt. */
    private suspend fun uploadShotNow(shot: StoredShot): String {
        val payload = buildShotPayload(shot)
        val id = withFreshToken { client.uploadShot(it, payload) }
        persist { it.copy(lastShotSyncAt = System.currentTimeMillis()) }
        return id
    }

    /** Called on `ShotCompleted` — uploads when auto-sync is armed + signed in. */
    fun maybeAutoUpload(shot: StoredShot) {
        if (persisted.autoSync && persisted.tokens != null) uploadShot(shot, silent = true)
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
