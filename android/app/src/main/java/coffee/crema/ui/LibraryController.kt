package coffee.crema.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import coffee.crema.beans.BeanImageStore
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.LibraryStore
import coffee.crema.beans.newBean
import coffee.crema.beans.newRoaster
import coffee.crema.core.Bean
import coffee.crema.core.CommonSettings
import coffee.crema.core.CremaBridge
import coffee.crema.core.MaintenanceState
import coffee.crema.core.Roaster
import coffee.crema.core.ShotBean
import coffee.crema.core.ShotDisposition
import coffee.crema.core.ShotQualityReport
import coffee.crema.core.VisualizerSyncPrefs
import coffee.crema.core.blankCremaProfile
import coffee.crema.core.builtinCremaProfiles
import coffee.crema.core.cremaProfileFromWire
import coffee.crema.core.cremaProfileToWire
import coffee.crema.core.debitRemaining
import coffee.crema.core.exportBackupJsonl
import coffee.crema.core.exportBeanconquerorMainJson
import coffee.crema.core.importBeanconquerorJson
import coffee.crema.core.newShotId
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.history.coreShotJson
import coffee.crema.history.downsampleForStorage
import coffee.crema.profiles.BrewDefaults
import coffee.crema.profiles.CremaProfile
import coffee.crema.profiles.CustomProfileStore
import coffee.crema.profiles.HiddenProfileStore
import coffee.crema.profiles.PinnedProfileStore
import coffee.crema.profiles.SegmentEdit
import coffee.crema.profiles.brewDefaultsJson
import coffee.crema.profiles.duplicatedCustomProfileJson
import coffee.crema.profiles.overrideBrewParamsJson
import coffee.crema.profiles.patchCremaProfileJson
import coffee.crema.profiles.profileIdOf
import coffee.crema.profiles.quickPresetJson
import coffee.crema.profiles.setProfilePinnedJson
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.toCommonSettings
import coffee.crema.settings.withCommonSettings
import coffee.crema.visualizer.VisualizerSync
import coffee.crema.visualizer.storedShotFromBackupJson
import coffee.crema.visualizer.wireShotJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * The library controller — profiles (built-in + custom + editor), the bean
 * library + roaster directory (+ bag photos), the shot history (capture,
 * edits, discard-undo), and import/export/backup/restore — extracted from
 * MainViewModel (review #43).
 *
 * A self-contained sibling of ProxyController / ConnectionController /
 * VisualizerSync. It OWNS the library persistence (the five stores + the raw
 * profile-JSON caches). Unlike the other controllers it does NOT keep its own
 * State flow: the library rows live in [MainUiState] (profiles, beans,
 * roasters, history, editor drafts) and this controller reads/writes them
 * SYNCHRONOUSLY through [uiState]/[updateUi] — the VM functions that stay
 * behind (pushStopTargets, configSnapshotJson, startShot) read those fields
 * immediately after library mutations (e.g. refreshProfiles → setActiveProfile
 * → pushStopTargets), so an async mirror would introduce read-after-write
 * races. Whole-app concerns cross as callbacks: the active-profile setter
 * (relay guard + stop targets), the restored-settings/maintenance apply, the
 * prefs snapshot, and the SAW-model persist.
 */
class LibraryController(
    private val app: Application,
    private val json: Json,
    private val scope: CoroutineScope,
    /** The Rust core, for the import/convert/analyze calls. */
    private val bridge: CremaBridge,
    /** Visualizer sync — auto-upload on capture, edit PATCH mirror, backup prefs. */
    private val visualizer: VisualizerSync,
    /** Read the live UI snapshot (library rows, active ids, live-shot state). */
    private val uiState: () -> MainUiState,
    /** Synchronously update the UI snapshot (the VM's `_ui.update`). */
    private val updateUi: ((MainUiState) -> MainUiState) -> Unit,
    /** Surface a user-facing message (the VM's snackbar channel). */
    private val notify: (String) -> Unit,
    /** Append to the session event log. */
    private val appendLog: (String) -> Unit,
    /** The current prefs snapshot (the backup's portable-settings source). */
    private val currentPrefs: () -> AppPrefs,
    /** Apply a restored backup's portable settings (mirrors loadPrefs; VM). */
    private val applyRestoredSettings: (AppPrefs) -> Unit,
    /** Apply a restored backup's maintenance state (reseeds the live water
     *  integrator + persists; VM — maintenance stays VM-owned). */
    private val applyRestoredMaintenance: (MaintenanceState) -> Unit,
    /** Persist the learned SAW drip model (bridge + SawModelStore; VM). */
    private val persistSawModel: suspend () -> Unit,
    /** The VM's setActiveProfile — relay guard + persistPrefs + stop targets. */
    private val setActiveProfile: (String) -> Unit,
    /** The VM's resetBrewParams — relay-aware; clears the QC override. */
    private val resetBrewParams: () -> Unit,
) {

    // ── Persistence (controller-owned stores) ────────────────────────────────

    /** Bean-library persistence — a JSON file in filesDir. */
    private val library = LibraryStore(app, json)

    /** Shot-history persistence — a JSON file in filesDir. */
    private val historyStore = HistoryStore(app, json)

    /** Custom-profile persistence — a JSON file in filesDir. */
    private val customProfileStore = CustomProfileStore(app, json)

    /** Pinned built-in ids (Favorites star) — a JSON file in filesDir. */
    private val pinnedProfileStore = PinnedProfileStore(app, json)
    private val hiddenProfileStore = HiddenProfileStore(app, json)

    // ── Raw profile caches ───────────────────────────────────────────────────

    /**
     * Raw built-in profile JSON, keyed by id — the full `CremaProfile` shape, kept
     * for upload because [MainUiState.profiles]'s thin model drops fields the wire
     * conversion ([cremaProfileToWire]) needs. Populated in [loadBuiltinProfiles].
     */
    private var builtinProfileJson: Map<String, String> = emptyMap()

    /** Built-in profiles parsed into the thin display model, cached for [refreshProfiles]. */
    private var builtinProfiles: List<CremaProfile> = emptyList()

    /**
     * Custom (user) profiles as complete `CremaProfile` JSON, newest first — the
     * editor's save target, persisted via [CustomProfileStore]. Kept as full JSON
     * (not the thin model) so every wire-relevant field round-trips on upload; see
     * [coffee.crema.profiles.patchCremaProfileJson].
     */
    private var customProfilesJson: List<String> = emptyList()

    /**
     * Pinned BUILT-IN profile ids (the Favorites star), persisted via
     * [PinnedProfileStore] and overlaid onto the built-ins in [refreshProfiles].
     * Customs carry `pinned` in their own JSON, so they aren't tracked here.
     */
    private var pinnedIds: Set<String> = emptySet()

    /** Hidden (archived) BUILT-IN profile ids, persisted via [HiddenProfileStore].
     *  Built-ins can't be deleted, so "archive" hides them from the active grid. */
    private var hiddenIds: Set<String> = emptySet()

    /**
     * Complete `CremaProfile` JSON by id — built-ins + customs — the lookup
     * the VM's startShot feeds to [cremaProfileToWire]. Rebuilt by [refreshProfiles].
     */
    private var profileJsonById: Map<String, String> = emptyMap()

    /**
     * A new / duplicated profile's complete base JSON, held between [startNewProfile]
     * / [duplicateProfile] and the editor's [saveProfile]; null otherwise.
     */
    private var draftProfileJson: String? = null

    /** The aborted shot held for the undo window (not yet in history). */
    private var discardedShot: StoredShot? = null

    // ── VM lookups (the whole-profile JSON the shot/proxy paths need) ────────

    /** The complete profile JSON for [id] (built-in or custom), or null. */
    fun profileJson(id: String): String? = profileJsonById[id]

    /** Whether [id] resolves to any known profile (built-in or custom). */
    fun hasProfile(id: String): Boolean = profileJsonById.containsKey(id)

    /** Whether [id] is a BUILT-IN profile (the config snapshot ships only customs). */
    fun isBuiltinProfile(id: String): Boolean = builtinProfileJson.containsKey(id)

    /** Adopt a complete custom-profile JSON into the library (newest first),
     *  refresh + persist — the handoff's mirrored-profile promotion (issue 05). */
    fun adoptCustomProfile(raw: String) {
        customProfilesJson = listOf(raw) + customProfilesJson
        refreshProfiles()
        persistCustomProfiles()
    }

    // ── Startup loads (called from the VM's init, same order as before) ──────

    /**
     * Load the core's built-in profiles once and seed the Brew header's active
     * selection. `builtinCremaProfiles()` is a sans-IO core call returning a
     * JSON array of `CremaProfile`; the adapter logic lives in the Rust core
     * (de1_domain::crema_profile) and every shell shares it. Failures are
     * swallowed — the header just shows "No profile selected".
     */
    fun loadBuiltinProfiles() {
        val raw = runCatching { builtinCremaProfiles() }.getOrElse {
            appendLog("Load built-in profiles failed: ${it.message}")
            return
        }
        val list = runCatching {
            json.decodeFromString(ListSerializer(CremaProfile.serializer()), raw)
        }.getOrElse {
            appendLog("Parse built-in profiles failed: ${it.message}")
            return
        }
        // Retain the full per-profile JSON (keyed by id) for upload — the thin
        // CremaProfile model decoded above is for display only and drops fields
        // the wire conversion needs.
        builtinProfileJson = runCatching {
            json.parseToJsonElement(raw).jsonArray.associate { element ->
                val id = element.jsonObject["id"]?.jsonPrimitive?.content.orEmpty()
                id to element.toString()
            }
        }.getOrElse { emptyMap() }
        builtinProfiles = list
        refreshProfiles()
    }

    /** Load the persisted bean library into the UI snapshot at startup. */
    suspend fun loadLibrary() {
        val lib = library.load()
        updateUi { it.copy(
            beans = lib.beans,
            roasters = lib.roasters,
            activeBeanId = lib.activeBeanId ?: lib.beans.firstOrNull()?.id,
        ) }
    }

    /** Load the persisted shot log at startup. */
    suspend fun loadHistory() {
        val loaded = historyStore.load()
        updateUi { it.copy(history = loaded) }
    }

    /** Load the pinned/hidden built-in overlays at startup (before the customs). */
    suspend fun loadProfileMeta() {
        pinnedIds = pinnedProfileStore.load()
        hiddenIds = hiddenProfileStore.load()
    }

    /** Load the persisted custom profiles into the merged list at startup. */
    suspend fun loadCustomProfiles() {
        customProfilesJson = customProfileStore.load()
        refreshProfiles()
    }

    // ── Profile list derivation + persistence ────────────────────────────────

    /**
     * Recompute the merged profile list (customs first, then built-ins) and the
     * id → full-JSON upload map from the two raw-JSON sources. Seeds the active
     * selection to the first profile when none is set. Called whenever the
     * built-in or custom sources change.
     */
    private fun refreshProfiles() {
        val customThin = customProfilesJson.mapNotNull { raw ->
            runCatching { json.decodeFromString(CremaProfile.serializer(), raw) }.getOrNull()
        }
        // Customs carry `pinned` in their own JSON; built-ins get the Favorites
        // star overlaid from the persisted [pinnedIds] set (they re-decode from the
        // core each launch with pinned=false).
        val all = customThin + builtinProfiles.map { it.copy(pinned = pinnedIds.contains(it.id)) }
        profileJsonById = buildMap {
            putAll(builtinProfileJson)
            customProfilesJson.forEach { raw ->
                profileIdOf(raw, json)?.takeIf { it.isNotEmpty() }?.let { put(it, raw) }
            }
        }
        // Self-heal a stale selection (deleted custom, hidden built-in restored
        // from prefs) by reseeding to the first visible profile.
        val current = uiState().activeProfileId?.takeIf { id -> all.any { it.id == id } }
        updateUi { it.copy(
            profiles = all,
            hiddenProfileIds = hiddenIds,
            activeProfileId = current ?: all.firstOrNull { it.id !in hiddenIds }?.id,
        ) }
    }

    /** Persist the current custom profiles (best-effort, off the main thread). */
    private fun persistCustomProfiles() {
        val snapshot = customProfilesJson
        scope.launch { customProfileStore.save(snapshot) }
    }

    /**
     * Toggle a profile's Favorites star (the `pinned` flag the Quick-Controls
     * favorites strip reads). Custom → patch + persist its own JSON; built-in →
     * flip membership in the persisted [pinnedIds] set. Either way [refreshProfiles]
     * re-derives the merged list so the card, header count, and favorites strip
     * stay in lock-step.
     */
    fun togglePinProfile(id: String) {
        val profile = uiState().profiles.firstOrNull { it.id == id } ?: return
        val next = !profile.pinned
        if (profile.source == "custom") {
            val base = customProfilesJson.firstOrNull { profileIdOf(it, json) == id } ?: return
            val patched = runCatching { setProfilePinnedJson(base, next, json) }.getOrElse {
                appendLog("Pin profile failed: ${it.message}"); return
            }
            customProfilesJson = customProfilesJson.map { if (profileIdOf(it, json) == id) patched else it }
            refreshProfiles()
            persistCustomProfiles()
        } else {
            pinnedIds = if (next) pinnedIds + id else pinnedIds - id
            refreshProfiles()
            val snapshot = pinnedIds
            scope.launch { pinnedProfileStore.save(snapshot) }
        }
    }

    /**
     * Persist the current Quick-Controls dial values into a profile. A user-defined
     * (custom) active profile is UPDATED in place (id / name / segments preserved);
     * a read-only built-in is COPIED to a new custom carrying the dialled dose /
     * yield / brew-temp and made active. Either way the per-shot override is cleared
     * afterwards (the values now live in the profile). Pure shell.
     */
    fun saveQuickPreset(name: String) {
        val s = uiState()
        val activeId = s.activeProfileId ?: return
        val base = profileJsonById[activeId] ?: return
        val bp = s.brewParams
        val active = s.profiles.firstOrNull { it.id == activeId }
        val d = bp?.dose?.toFloat() ?: active?.dose ?: BrewDefaults.INSTANCE.doseG
        val y = bp?.yieldOut?.toFloat() ?: active?.yieldOut
            ?: (BrewDefaults.INSTANCE.doseG * BrewDefaults.INSTANCE.ratio)
        val t = bp?.brewTemp?.toFloat() ?: active?.brewTemp ?: BrewDefaults.INSTANCE.brewTempC
        if (active?.source == "custom") {
            // User-defined: bake the dialled dose/yield/temp into its own JSON in
            // place (id / name / segments preserved), replace by id, persist — it
            // stays active. overrideBrewParamsJson is the locale-safe patcher the
            // shot-upload path uses, so a comma-decimal locale stays correct.
            val updated = runCatching { overrideBrewParamsJson(base, d, y, t, json = json) }.getOrElse {
                appendLog("Save profile failed: ${it.message}")
                return
            }
            customProfilesJson = customProfilesJson.map { if (profileIdOf(it, json) == activeId) updated else it }
            refreshProfiles()
            persistCustomProfiles()
            resetBrewParams()
            notify("Profile updated")
        } else {
            // Predefined (read-only): save a NEW custom copy carrying the dialled
            // values, then make it active.
            val preset = runCatching { quickPresetJson(base, name, d, y, t, json) }.getOrElse {
                appendLog("Save profile failed: ${it.message}")
                return
            }
            customProfilesJson = listOf(preset) + customProfilesJson
            refreshProfiles()
            persistCustomProfiles()
            profileIdOf(preset, json)?.let { setActiveProfile(it) }
            notify("Profile saved")
        }
    }

    // ── Profile editor (the `profile-edit` route) ─────────────────────────────

    /** Open an existing saved (custom) profile in the editor. */
    fun startEditProfile(id: String) {
        draftProfileJson = null
        updateUi { it.copy(editingProfileId = id, draftProfile = null) }
    }

    /**
     * Begin a brand-new custom profile: the core mints a complete blank
     * `CremaProfile` (a fresh id, `source: "custom"`, the default segment list)
     * seeded from the user's brew defaults. Held as a draft until the editor saves.
     */
    fun startNewProfile() {
        // Seed from the persisted Settings → Brew defaults (the wiring point
        // brewDefaultsJson documents).
        val s = uiState()
        val defaults = brewDefaultsJson(
            doseG = s.defaultDoseG,
            ratio = s.defaultRatio,
            brewTempC = s.defaultBrewTempC,
            preinfusionS = s.defaultPreinfuseS,
        )
        val blank = runCatching { blankCremaProfile(defaults) }.getOrElse {
            appendLog("New profile failed: ${it.message}")
            return
        }
        beginDraft(blank)
    }

    /**
     * Duplicate any profile (built-in or custom) into a fresh custom draft — the
     * full recipe (every segment, temp, limiter) is preserved verbatim so the copy
     * uploads identically; only id / source / name / pinned change. Opens the editor.
     */
    fun duplicateProfile(id: String) {
        // An unsaved new/duplicated draft isn't in profileJsonById yet — fall back
        // to the draft JSON so "Duplicate" inside the editor never silently no-ops.
        val base = profileJsonById[id]
            ?: draftProfileJson?.takeIf { uiState().editingProfileId == id }
            ?: run {
                appendLog("Duplicate: profile $id not found")
                return
            }
        val copy = runCatching { duplicatedCustomProfileJson(base, json) }.getOrElse {
            appendLog("Duplicate failed: ${it.message}")
            return
        }
        beginDraft(copy)
    }

    /** Stash a complete profile JSON as the editor's draft and route the editor to it. */
    private fun beginDraft(fullJson: String) {
        val thin = runCatching {
            json.decodeFromString(CremaProfile.serializer(), fullJson)
        }.getOrElse {
            appendLog("Draft parse failed: ${it.message}")
            return
        }
        draftProfileJson = fullJson
        updateUi { it.copy(editingProfileId = thin.id, draftProfile = thin) }
    }

    /**
     * Save the editor's non-curve edits. Patches only the edited fields into the
     * profile's complete JSON base (the draft for a new / duplicated profile, else
     * the stored custom), so every wire-relevant field — per-segment temp / mode /
     * ramp, notes, beverage type — round-trips untouched to the DE1. Upserts into
     * the custom store (newest first for a new profile; in place for an edit),
     * refreshes the merged list, and persists.
     *
     * @param segments (target, time) per existing segment, in order — the non-curve
     *   editor never adds / removes segments, so this matches the base length.
     */
    fun saveProfile(
        id: String,
        name: String,
        roast: String?,
        tags: List<String>,
        pinned: Boolean,
        notes: String,
        author: String,
        beverageType: String?,
        dose: Float,
        yieldOut: Float,
        brewTemp: Float,
        maxTotalVolumeMl: Int,
        preinfuseStepCount: Int,
        tankTemperatureC: Float,
        segments: List<SegmentEdit>,
    ) {
        val isExisting = customProfilesJson.any { profileIdOf(it, json) == id }
        val base = when {
            draftProfileJson != null && uiState().editingProfileId == id -> draftProfileJson
            isExisting -> customProfilesJson.firstOrNull { profileIdOf(it, json) == id }
            else -> null
        }
        if (base == null) {
            appendLog("Save profile: base for $id not found")
            return
        }
        val patched = runCatching {
            patchCremaProfileJson(
                baseJson = base,
                name = name,
                roast = roast,
                tags = tags,
                pinned = pinned,
                notes = notes,
                author = author,
                beverageType = beverageType,
                dose = dose,
                yieldOut = yieldOut,
                brewTemp = brewTemp,
                maxTotalVolumeMl = maxTotalVolumeMl,
                preinfuseStepCount = preinfuseStepCount,
                tankTemperatureC = tankTemperatureC,
                segments = segments,
                json = json,
            )
        }.getOrElse {
            appendLog("Save profile failed: ${it.message}")
            return
        }
        customProfilesJson = if (isExisting) {
            customProfilesJson.map { if (profileIdOf(it, json) == id) patched else it }
        } else {
            listOf(patched) + customProfilesJson
        }
        // Clear the draft but KEEP editingProfileId: the saved profile is now in
        // customProfilesJson, so the editor still resolves it (no "no profile"
        // flash before the nav pops), and a double-tap Save updates in place via
        // the isExisting branch rather than appending a duplicate. The next
        // start*Profile / cancel resets editingProfileId.
        draftProfileJson = null
        updateUi { it.copy(draftProfile = null) }
        refreshProfiles()
        // Make the just-saved profile the active one so the edit is reflected on
        // Brew. Editing a built-in saves to a fresh custom copy (built-ins are
        // read-only), so without this the original would still be loaded and the
        // change would look like it "didn't save".
        setActiveProfile(id)
        persistCustomProfiles()
    }

    /** Close the editor without saving (discards any new / duplicated draft). */
    fun cancelProfileEdit() {
        draftProfileJson = null
        updateUi { it.copy(editingProfileId = null, draftProfile = null) }
    }

    /**
     * Delete a custom profile (built-ins are not deletable — they have no store
     * entry, so this is a no-op for them). Clears the active selection if it was
     * the deleted one; [refreshProfiles] then reseeds it to the first remaining.
     */
    fun deleteProfile(id: String) {
        if (customProfilesJson.none { profileIdOf(it, json) == id }) return
        customProfilesJson = customProfilesJson.filterNot { profileIdOf(it, json) == id }
        if (uiState().activeProfileId == id) {
            updateUi { it.copy(activeProfileId = null) }
        }
        // Cascade: a deleted custom is gone for good — clear bean links so
        // the auto-load never chases a tombstone (web onDeleted parity).
        if (uiState().beans.any { it.linkedProfileId == id }) {
            updateUi { st ->
                st.copy(beans = st.beans.map { if (it.linkedProfileId == id) it.copy(linkedProfileId = null) else it })
            }
            persistLibrary()
        }
        refreshProfiles()
        persistCustomProfiles()
    }

    /** Archive (hide) a built-in profile — built-ins can't be deleted, so this
     *  hides it from the active grid. Restored from the "Hidden" filter. Persisted. */
    fun archiveBuiltinProfile(id: String) {
        hiddenIds = hiddenIds + id
        // If the hidden profile was active, drop the selection so refreshProfiles
        // re-seeds the Brew header to a still-visible profile.
        if (uiState().activeProfileId == id) {
            updateUi { it.copy(activeProfileId = null) }
        }
        refreshProfiles()
        val snapshot = hiddenIds
        scope.launch { hiddenProfileStore.save(snapshot) }
    }

    /** Restore a hidden built-in profile back to the active grid. Persisted. */
    fun unarchiveBuiltinProfile(id: String) {
        hiddenIds = hiddenIds - id
        refreshProfiles()
        val snapshot = hiddenIds
        scope.launch { hiddenProfileStore.save(snapshot) }
    }

    // ── Export / share + history actions ────────────────────────────────────

    /** Export a profile's full community-v2 JSON via the system share sheet. */
    fun exportProfile(id: String) {
        val full = profileJsonById[id]
            ?: customProfilesJson.firstOrNull { profileIdOf(it, json) == id }
            ?: run { appendLog("Export: profile $id not found"); return }
        shareText(full, "Share profile")
    }

    /** Export a stored shot as JSON via the system share sheet. */
    fun exportShot(id: String) {
        val shot = uiState().history.firstOrNull { it.id == id } ?: return
        val text = runCatching { json.encodeToString(StoredShot.serializer(), shot) }
            .getOrElse { appendLog("Export shot failed: ${it.message}"); return }
        shareText(text, "Share shot")
    }

    /** Export the whole shot history as a JSON array — Settings → Sharing. */
    fun exportAllShots() {
        val shots = uiState().history
        if (shots.isEmpty()) { appendLog("Export: no shots"); return }
        val text = runCatching { json.encodeToString(ListSerializer(StoredShot.serializer()), shots) }
            .getOrElse { appendLog("Export shots failed: ${it.message}"); return }
        shareText(text, "Export shots")
    }

    /** Export the bean library (beans + roasters) as JSON — Settings → Sharing. */
    fun exportBeansLibrary() {
        val s = uiState()
        val lib = BeanLibrary(beans = s.beans, roasters = s.roasters, activeBeanId = s.activeBeanId)
        if (lib.beans.isEmpty() && lib.roasters.isEmpty()) { appendLog("Export: empty library"); return }
        val text = runCatching { json.encodeToString(BeanLibrary.serializer(), lib) }
            .getOrElse { appendLog("Export library failed: ${it.message}"); return }
        shareText(text, "Export beans & roasters")
    }

    // ── Export-content builders (file export via SAF; share-sheet can't carry the
    //    large telemetry payloads — the Binder transaction limit). Return null +
    //    set a status when there's nothing to export. ──────────────────────────

    /** Bean library as Crema JSON (beans + roasters). */
    fun beansLibraryJson(): String? {
        val s = uiState()
        val lib = BeanLibrary(beans = s.beans, roasters = s.roasters, activeBeanId = s.activeBeanId)
        if (lib.beans.isEmpty() && lib.roasters.isEmpty()) { notify("Export — empty library"); return null }
        return runCatching { json.encodeToString(BeanLibrary.serializer(), lib) }.getOrNull()
    }

    /** Library in Beanconqueror's format (via the core). */
    fun beansBeanconquerorJson(): String? {
        val s = uiState()
        val beans = s.beans
        if (beans.isEmpty()) { notify("Export — no beans"); return null }
        val beansJson = runCatching { json.encodeToString(ListSerializer(Bean.serializer()), beans) }.getOrElse { "[]" }
        val roastersJson = runCatching { json.encodeToString(ListSerializer(Roaster.serializer()), s.roasters) }.getOrElse { "[]" }
        val envelope = "{\"beans\":$beansJson,\"roasters\":$roastersJson,\"shots\":[]}"
        return runCatching { exportBeanconquerorMainJson(envelope, System.currentTimeMillis()) }
            .getOrElse { notify("Beanconqueror export failed"); null }
    }

    /** Shots as Crema JSON — all (ids null) or only the given ids. */
    fun shotsJson(ids: List<String>?): String? {
        val history = uiState().history
        val shots = if (ids == null) history else { val w = ids.toHashSet(); history.filter { it.id in w } }
        if (shots.isEmpty()) { notify("Export — no shots"); return null }
        return runCatching { json.encodeToString(ListSerializer(StoredShot.serializer()), shots) }.getOrNull()
    }

    /**
     * Run the core's shot-quality analysis (the Decenza `ShotAnalysis` port)
     * over a stored shot. The core builds its own analysis input from the
     * stored record — real per-sample frame markers, the same path the web
     * shell uses (review #39). Null when the shot is too thin to analyze
     * (the core bails under 10 samples, returning the string `"null"`) or
     * the bridge/decode rejects the input — the detail views render nothing
     * in that case.
     */
    fun analyzeShotQuality(shot: StoredShot): ShotQualityReport? {
        return runCatching {
            when (val report = bridge.analyzeStoredShotQuality(shot.coreShotJson().toString())) {
                "null" -> null
                else -> json.decodeFromString(ShotQualityReport.serializer(), report)
            }
        }.getOrNull()
    }

    /** All profiles (built-ins + customs) as a JSON array. */
    fun allProfilesJson(): String? {
        val all = uiState().profiles.mapNotNull { profileJsonById[it.id] }
        if (all.isEmpty()) { notify("Export — no profiles"); return null }
        return "[${all.joinToString(",")}]"
    }

    /** Write export [text] to a SAF document Uri (large-payload safe). */
    fun writeTextToUri(uri: Uri, text: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    true
                }.getOrDefault(false)
            }
            notify(if (ok) "Exported to file" else "Export failed — couldn't write")
        }
    }

    /** Write raw [bytes] to a SAF document Uri — the binary backup save (a
     *  `.crema.zip`). Off the main thread; large-payload safe. */
    fun writeBytesToUri(uri: Uri, bytes: ByteArray) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    true
                }.getOrDefault(false)
            }
            notify(if (ok) "Backed up to file" else "Backup failed — couldn't write")
        }
    }

    /** Read a content Uri as UTF-8 text, off the main thread. */
    private suspend fun readUriText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            app.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
        }.getOrNull()
    }

    /** Read a SAF [uri]'s raw bytes, or null on failure. */
    private suspend fun readUriBytes(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    /** `.crema.zip` local-file magic: `PK\x03\x04`. */
    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /**
     * Unzip a `.crema.zip`: return its `backup.jsonl` text, and stash each
     * `images/<beanId>` blob via [BeanImageStore] (keyed by the RAW bean id) so a
     * backup's bean photos land exactly where the library display + the next
     * backup resolve them. Null when there's no backup.jsonl.
     */
    private suspend fun extractBackupJsonl(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        runCatching {
            var jsonl: String? = null
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        !entry.isDirectory && (name == "backup.jsonl" || name == "crema.jsonl") ->
                            jsonl = String(zis.readBytes(), Charsets.UTF_8)
                        !entry.isDirectory && name.startsWith("images/") -> {
                            val beanId = name.substringAfter("images/")
                            if (beanId.isNotEmpty()) {
                                runCatching { BeanImageStore.put(app, beanId, zis.readBytes()) }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            jsonl
        }.getOrNull()
    }

    /** Re-stamp an exported profile's JSON as a fresh custom (new id, source,
     *  Built-in tag stripped) while preserving every other wire field. */
    private fun adoptProfileJson(raw: String): String? {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject.toMutableMap() }.getOrNull() ?: return null
        if (!obj.containsKey("name") && !obj.containsKey("segments")) return null
        obj["id"] = JsonPrimitive(java.util.UUID.randomUUID().toString())
        obj["source"] = JsonPrimitive("custom")
        (obj["tags"] as? JsonArray)?.let { tags ->
            obj["tags"] = JsonArray(tags.filterNot { runCatching { it.jsonPrimitive.content }.getOrNull() == "Built-in" })
        }
        return runCatching { json.encodeToString(JsonObject.serializer(), JsonObject(obj)) }.getOrNull()
    }

    /** Import profiles — Crema's own `.json`/`.jsonl` export, plus external
     *  community-v2 `.json` and legacy `.tcl` profiles (Visualizer / de1app),
     *  routed through the core importers → CremaProfile → adopted as custom (#12). */
    fun importProfiles(uri: Uri) {
        scope.launch {
            val text = readUriText(uri)?.trim()
            if (text.isNullOrBlank()) { notify("Import failed — couldn't read that file"); return@launch }
            val elements = runCatching {
                when {
                    text.startsWith("[") -> json.parseToJsonElement(text).jsonArray.map { it.toString() }
                    text.startsWith("{") -> listOf(text)
                    else -> text.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.toList()
                }
            }.getOrElse { emptyList() }
            val adopted = elements.mapNotNull { adoptProfileJson(it) }.toMutableList()
            // Not Crema's own shape? Try the external single-file formats via the
            // core: a `{`-leading file is community-v2 JSON, otherwise legacy TCL.
            // Convert the parsed wire Profile to a CremaProfile, then adopt it.
            if (adopted.isEmpty()) {
                val ext = runCatching {
                    val wire = if (text.startsWith("{")) bridge.importV2JsonProfile(text)
                    else bridge.importLegacyTclProfile(text)
                    adoptProfileJson(cremaProfileFromWire(wire))
                }
                ext.getOrNull()?.let { adopted.add(it) }
                if (adopted.isEmpty()) {
                    // Surface the core's reason (e.g. "imported profile has no steps"
                    // for a profile-less Visualizer shot) instead of a blanket
                    // "unrecognised file".
                    val why = ext.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                    notify(why?.let { "Couldn’t import profile — $it" }
                        ?: "No profiles imported — unrecognised file")
                    return@launch
                }
            }
            customProfilesJson = adopted + customProfilesJson
            refreshProfiles()
            persistCustomProfiles()
            notify("Imported ${adopted.size} profile(s)")
        }
    }

    /** Import shots from a Crema .json export (array / single / .jsonl), skipping
     *  ids already in history. */
    fun importShots(uri: Uri) {
        scope.launch {
            val text = readUriText(uri)?.trim()
            if (text.isNullOrBlank()) { notify("Import failed — couldn't read that file"); return@launch }
            var shots = runCatching {
                if (text.startsWith("[")) json.decodeFromString(ListSerializer(StoredShot.serializer()), text)
                else listOf(json.decodeFromString(StoredShot.serializer(), text))
            }.getOrElse {
                runCatching {
                    text.lineSequence().map { it.trim() }.filter { it.startsWith("{") }
                        .map { json.decodeFromString(StoredShot.serializer(), it) }.toList()
                }.getOrElse { emptyList() }
            }
            // Not Crema's own shape? Try external shot files via the core: a de1app
            // v2 `.shot.json` (`{`-leading) or a legacy `.tcl`/`.shot`.
            if (shots.isEmpty()) {
                val ext = runCatching {
                    val wire = if (text.startsWith("{")) bridge.importV2JsonShot(text)
                    else bridge.importLegacyTclShot(text)
                    storedShotFromBackupJson(json.parseToJsonElement(wire).jsonObject, json)
                }
                ext.getOrNull()?.let { shots = listOf(it) }
                if (shots.isEmpty()) {
                    val why = ext.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                    notify(why?.let { "Couldn’t import shot — $it" }
                        ?: "No shots imported — unrecognised file")
                    return@launch
                }
            }
            val existing = uiState().history.map { it.id }.toHashSet()
            val toAdd = shots.filterNot { it.id in existing }
            val next = toAdd + uiState().history
            updateUi { it.copy(history = next) }
            notify("Imported ${toAdd.size} shot(s)")
            historyStore.save(next)
        }
    }

    // ── Full-app backup & restore (crema-backup/v1) ──────────────────────────
    // One portable bundle: custom profiles + beans/roasters + shot history + the
    // portable settings subset, (de)serialised by the core (exportBackupJsonl /
    // the line-tagged JSONL). OAuth tokens + per-device BLE/proxy state are
    // stripped on export and preserved on import — a backup moves between devices
    // without dragging this device's bindings along.

    /** Build the whole-app backup bundle as `crema-backup/v1` JSONL, or null + a
     *  notice when there's nothing to back up. Custom profiles only — built-ins
     *  ship with the app, so backing them up would only create duplicates. */
    fun backupBundleJson(): String? {
        val customs = customProfilesJson
        val s = uiState()
        // "Nothing to back up" only when EVERYTHING the bundle carries is empty /
        // default — not just the four data types. Customised settings (theme,
        // units, …) and pinned / hidden built-ins are backup-worthy on their own,
        // even with no custom profiles / beans / shots. Match web's
        // `settingsAreDefault` gate so the same app state gives the same answer on
        // both shells (issue 06 F2; web previously said "back up", Android "nothing").
        val noData = customs.isEmpty() && s.beans.isEmpty() && s.roasters.isEmpty() && s.history.isEmpty()
        val noProfileOrg = pinnedIds.isEmpty() && hiddenIds.isEmpty()
        val settingsDefault =
            currentPrefs().toCommonSettings() == AppPrefs().toCommonSettings() && currentPrefs().qcGrind == null
        if (noData && noProfileOrg && settingsDefault) {
            notify("Nothing to back up yet"); return null
        }
        // Portable settings only — drop per-device identity (BLE addresses, LAN
        // proxy, paired devices); they're meaningless on another device.
        val portable = currentPrefs().copy(
            de1Address = null,
            scaleAddress = null,
            proxyRole = "normal",
            proxyPrimaryHost = "",
            proxyPrimaryPort = 0,
            replayPrimary = false,
            pairedDevices = emptyList(),
        )
        val beansJson = runCatching { json.encodeToString(ListSerializer(Bean.serializer()), s.beans) }.getOrElse { "[]" }
        val roastersJson = runCatching { json.encodeToString(ListSerializer(Roaster.serializer()), s.roasters) }.getOrElse { "[]" }
        // Shots ride in the canonical core `StoredShot` wire shape (the SAME shape
        // the Visualizer path emits via `export_v2_json_shot`), NOT Android's flat
        // store shape: the core backup exporter parses `shots:[StoredShot]`, so the
        // flat shape fails the whole envelope's deserialization → the entire backup
        // aborts once any shot exists (issue 01). `wireShotJson` emits
        // formatVersion/completedAt/record; restore inverts via storedShotFromBackupJson.
        val shotsJson = runCatching {
            JsonArray(s.history.map { wireShotJson(it, forBackup = true) }).toString()
        }.getOrElse { "[]" }
        // Settings line: the shared cross-shell CommonSettings `common` block (both
        // shells emit it identically, so common prefs restore web<->Android) + this
        // shell's `_shell` tag + Android-only `qcGrind` (re-applied only on Android).
        val settingsJson = runCatching {
            val common = json.parseToJsonElement(json.encodeToString(CommonSettings.serializer(), portable.toCommonSettings()))
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("common", common)
                    put("_shell", "android")
                    portable.qcGrind?.let { put("qcGrind", it) }
                },
            )
        }.getOrElse { "{\"_shell\":\"android\"}" }
        // Profile organisation — pinned + hidden BUILT-IN ids (cross-shell; custom
        // pins ride inside the profile JSON). Maintenance — a shared core type,
        // fully portable. Visualizer SYNC prefs — shell-tagged, token-free.
        val profileMetaJson = json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                mapOf(
                    "pinned" to JsonArray(pinnedIds.map { JsonPrimitive(it) }),
                    "hiddenBuiltins" to JsonArray(hiddenIds.map { JsonPrimitive(it) }),
                ),
            ),
        )
        val maintenanceJson = runCatching { json.encodeToString(MaintenanceState.serializer(), s.maintenance) }.getOrElse { "{}" }
        val visualizerPrefsJson = runCatching {
            json.encodeToString(VisualizerSyncPrefs.serializer(), visualizer.backupPrefs())
        }.getOrElse { "{}" }
        // Assemble the envelope as a JsonObject rather than raw string concat so a
        // dropped brace / comma can't silently corrupt it (issue 06 F5). Each piece
        // is already a serialized JSON string; parse it back into the tree. The core
        // re-serialises the envelope when it emits the JSONL, so formatting here is
        // immaterial — this only hardens assembly.
        val envelope = buildJsonObject {
            put("profiles", JsonArray(customs.map { json.parseToJsonElement(it) }))
            put("beans", json.parseToJsonElement(beansJson))
            put("roasters", json.parseToJsonElement(roastersJson))
            put("shots", json.parseToJsonElement(shotsJson))
            put("settings", json.parseToJsonElement(settingsJson))
            put("profileMeta", json.parseToJsonElement(profileMetaJson))
            put("maintenance", json.parseToJsonElement(maintenanceJson))
            put("visualizerPrefs", json.parseToJsonElement(visualizerPrefsJson))
        }.toString()
        return runCatching {
            exportBackupJsonl(envelope, System.currentTimeMillis(), coffee.crema.BuildConfig.VERSION_NAME, deviceLabel())
        }.getOrElse { appendLog("Backup failed: ${it.message}"); notify("Backup failed"); null }
    }

    /**
     * Wrap [backupBundleJson]'s JSONL into a `.crema.zip`: `backup.jsonl` plus, for
     * every bean with a stored photo, `images/<RAW bean id>` (the cross-shell key
     * web + Android restore by). Photos resolve by file existence (not by parsing
     * `imageRef`), so a web-restored bag's photo rides along too. Null + a notice
     * when there's nothing to back up. Shared by the local SAF save + Drive upload.
     */
    fun buildBackupZipBytes(): ByteArray? {
        val jsonl = backupBundleJson() ?: return null // already notified "nothing to back up"
        return runCatching {
            val baos = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("backup.jsonl"))
                zos.write(jsonl.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                for (bean in uiState().beans) {
                    if (!BeanImageStore.exists(app, bean.id)) continue
                    val bytes = runCatching { BeanImageStore.beanImageFile(app, bean.id).readBytes() }.getOrNull() ?: continue
                    zos.putNextEntry(java.util.zip.ZipEntry("images/${bean.id}"))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
            baos.toByteArray()
        }.getOrElse { appendLog("Backup zip failed: ${it.message}"); notify("Backup failed"); null }
    }

    /** A suggested filename for the SAF create-document picker. */
    fun backupFileName(): String {
        val label = deviceLabel().replace(Regex("[^A-Za-z0-9._-]"), "_")
        val c = java.util.Calendar.getInstance()
        val stamp = fmt("%04d%02d%02d-%02d%02d",
            c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
        )
        // `.crema.zip` (backup.jsonl + images/<beanId> photos); the `crema-backup`
        // prefix keeps it recognisable + is what the Drive list query matches on.
        return "crema-backup-$label-$stamp.crema.zip"
    }

    /** Restore a `crema-backup` bundle from a SAF [uri]. MERGE adds only records
     *  whose ids aren't already present (lossless re-restore); WIPE clears the
     *  local library / history / custom profiles first (destructive — the UI
     *  gates it behind a type-to-confirm). The portable settings subset is
     *  applied in both modes; this device's BLE/proxy bindings are always kept. */
    fun restoreBackup(uri: Uri, mode: MainViewModel.RestoreMode) {
        scope.launch {
            val bytes = readUriBytes(uri)
            if (bytes == null || bytes.isEmpty()) { notify("Restore failed — couldn't read that file"); return@launch }
            restoreBackupBytes(bytes, mode)
        }
    }

    /** Apply a backup from raw bytes — a `.crema.zip` (web full backup: JSONL +
     *  bean photos) or a bare `.crema.jsonl` / `.crema`. Shared by the SAF file
     *  restore and the Google Drive restore. */
    suspend fun restoreBackupBytes(bytes: ByteArray, mode: MainViewModel.RestoreMode) {
        val text = (if (isZip(bytes)) extractBackupJsonl(bytes) else String(bytes, Charsets.UTF_8))?.trim()
        if (text.isNullOrBlank()) { notify("Restore failed — not a Crema backup"); return }
        restoreBackupFromText(text, mode)
    }

    /** Parse + apply a `crema-backup` bundle's raw text — shared by the SAF restore
     *  ([restoreBackup]) and the Google Drive restore (the downloaded `.crema`). */
    fun restoreBackupFromText(text: String, mode: MainViewModel.RestoreMode) {
        scope.launch {
            // The line-parser lives in the pure, unit-tested `parseBackupRecords`
            // (review #07); re-bind its result to the names the apply block uses.
            val parsed = parseBackupRecords(text, json, System.currentTimeMillis())
            val profiles = parsed.profiles
            val beans = parsed.beans
            val roasters = parsed.roasters
            val shots = parsed.shots
            val restoredPrefs = parsed.prefs
            val restoredCommon = parsed.common
            val restoredQcGrind = parsed.qcGrind
            val restoredProfileMeta = parsed.profileMeta
            val restoredMaintenance = parsed.maintenance
            val restoredVisualizerPrefs = parsed.visualizerPrefs
            if (!parsed.sawHeader) { notify("Restore failed — not a Crema backup"); return@launch }

            if (mode == MainViewModel.RestoreMode.WIPE) {
                customProfilesJson = emptyList()
                // Clear pin/hidden overlays too — a profileMeta line (if present)
                // re-applies the bundle's below; a bundle without one leaves them cleared.
                pinnedIds = emptySet()
                hiddenIds = emptySet()
                pinnedProfileStore.save(emptySet())
                hiddenProfileStore.save(emptySet())
                updateUi { it.copy(beans = emptyList(), roasters = emptyList(), history = emptyList(), activeBeanId = null) }
            }

            // Profiles — add customs whose id isn't already known (built-in or custom).
            val knownProfileIds = uiState().profiles.map { it.id }.toHashSet()
            val newProfiles = profiles.filter { p -> profileIdOf(p, json)?.let { it !in knownProfileIds } ?: false }
            customProfilesJson = customProfilesJson + newProfiles

            // Beans / roasters / shots — id-union (preserves ids, so a re-restore dedups).
            val s = uiState()
            val haveBeans = s.beans.map { it.id }.toHashSet()
            val haveRoasters = s.roasters.map { it.id }.toHashSet()
            val haveShots = s.history.map { it.id }.toHashSet()
            val newBeans = beans.filterNot { it.id in haveBeans }
            val newRoasters = roasters.filterNot { it.id in haveRoasters }
            val newShots = shots.filterNot { it.id in haveShots }
            updateUi { it.copy(
                beans = it.beans + newBeans,
                roasters = it.roasters + newRoasters,
                history = newShots + it.history,
                activeBeanId = it.activeBeanId ?: newBeans.firstOrNull()?.id,
            ) }

            // Profile organisation — pinned + hidden BUILT-IN ids. WIPE replaces the
            // sets; MERGE unions them. Applied BEFORE refreshProfiles so the star /
            // hidden overlay reflects the restored choices on the next list build.
            restoredProfileMeta?.let { pm ->
                val bundledPinned = (pm["pinned"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet().orEmpty()
                val bundledHidden = (pm["hiddenBuiltins"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet().orEmpty()
                pinnedIds = if (mode == MainViewModel.RestoreMode.WIPE) bundledPinned else pinnedIds + bundledPinned
                hiddenIds = if (mode == MainViewModel.RestoreMode.WIPE) bundledHidden else hiddenIds + bundledHidden
                pinnedProfileStore.save(pinnedIds)
                hiddenProfileStore.save(hiddenIds)
            }

            refreshProfiles()
            persistCustomProfiles()
            persistLibrary()
            historyStore.save(uiState().history)
            restoredPrefs?.let { applyRestoredSettings(it) }
            // Cross-shell common settings: overlay onto current prefs (keeping this
            // device's platform extras), then apply. qcGrind rides only from Android.
            restoredCommon?.let { c ->
                val target = currentPrefs().withCommonSettings(c)
                applyRestoredSettings(if (restoredQcGrind != null) target.copy(qcGrind = restoredQcGrind) else target)
            }

            // Maintenance counters + Visualizer sync prefs — both shared core types,
            // applied verbatim on either shell (no per-shell tag); never the OAuth
            // token (re-auth after restore). Reseed the live water integrator.
            restoredMaintenance?.let { applyRestoredMaintenance(it) }
            restoredVisualizerPrefs?.let { visualizer.restorePrefs(it) }

            notify(
                "Restored ${newProfiles.size} profile(s) · ${newBeans.size} bean(s) · " +
                    "${newRoasters.size} roaster(s) · ${newShots.size} shot(s)",
            )
        }
    }

    /** Remove a stored shot from the local history. Persisted. */
    fun deleteShot(id: String) {
        val next = uiState().history.filterNot { it.id == id }
        updateUi { it.copy(history = next) }
        scope.launch { historyStore.save(next) }
    }

    /** Fire an ACTION_SEND chooser with [text] (application/json). Best-effort. */
    private fun shareText(text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, title)
        }
        val chooser = Intent.createChooser(send, title).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { app.startActivity(chooser) }.onFailure { appendLog("Share failed: ${it.message}") }
    }

    // ── Erase-all (the library/history/profile share of Settings ▸ Erase) ────

    /** Clear the bean library, roaster directory, shot history, and custom
     *  profiles (disk + UI); built-ins remain and the active profile reseeds
     *  via [refreshProfiles]. The VM composes this with its settings +
     *  maintenance resets. */
    fun eraseAll() {
        customProfilesJson = emptyList()
        scope.launch {
            library.save(BeanLibrary())
            historyStore.save(emptyList())
            customProfileStore.save(emptyList())
        }
        updateUi { it.copy(
            beans = emptyList(),
            roasters = emptyList(),
            activeBeanId = null,
            history = emptyList(),
            activeProfileId = null,
        ) }
        refreshProfiles()
    }

    // ── Visualizer sync hooks (pull/backfill/synced stamps) ──────────────────

    /** Insert pulled Visualizer stubs into the history (dedup by id, newest first). Persisted. */
    fun insertPulledShots(stubs: List<StoredShot>) {
        if (stubs.isEmpty()) return
        updateUi { st ->
            val existing = st.history.map { it.id }.toHashSet()
            val fresh = stubs.filter { it.id !in existing }
            if (fresh.isEmpty()) {
                st
            } else {
                st.copy(history = (fresh + st.history).sortedByDescending { it.completedAtMs }.take(HistoryStore.MAX_SHOTS))
            }
        }
        val snapshot = uiState().history
        scope.launch { historyStore.save(snapshot) }
    }

    /** Backfill a curve-less local shot's telemetry from a Visualizer pull. Persisted. */
    fun backfillShotTelemetry(localId: String, samples: List<TelemetrySample>, durationMs: Long) {
        updateUi { st ->
            st.copy(
                history = st.history.map {
                    if (it.id == localId && it.samples.isEmpty()) {
                        it.copy(
                            samples = downsampleForStorage(samples),
                            durationMs = if (it.durationMs > 0) it.durationMs else durationMs,
                        )
                    } else {
                        it
                    }
                },
            )
        }
        val snapshot = uiState().history
        scope.launch { historyStore.save(snapshot) }
    }

    /** Stamp a successful Visualizer upload onto the local shot. Persisted. */
    fun markShotSynced(localId: String, visualizerId: String) {
        updateUi { st ->
            st.copy(history = st.history.map { if (it.id == localId) it.copy(visualizerId = visualizerId) else it })
        }
        val snapshot = uiState().history
        scope.launch { historyStore.save(snapshot) }
    }

    // ── Bean library ────────────────────────────────────────────────────────

    /** Persist the current bean library (beans + roasters + active selection). */
    fun persistLibrary() {
        val s = uiState()
        val lib = BeanLibrary(s.beans, s.roasters, s.activeBeanId)
        scope.launch { library.save(lib) }
    }

    /**
     * Find a roaster by name (case-insensitive) or mint one. Returns the roaster
     * id (null for a blank name) and the possibly-extended roaster directory.
     */
    private fun resolveRoaster(
        name: String,
        roasters: List<Roaster>,
        nowMs: Long,
    ): Pair<String?, List<Roaster>> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null to roasters
        val existing = roasters.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        return if (existing != null) {
            existing.id to roasters
        } else {
            val r = newRoaster(trimmed, nowMs)
            r.id to (roasters + r)
        }
    }

    /**
     * Import a Beanconqueror export (a system file-picker Uri) into the bean
     * library. Reads the file off the main thread — a single Beanconqueror JSON,
     * or a `.zip` archive whose chunked BEANS/BREWS files are merged back into the
     * main JSON ([mergeBcZip], the inverse of BC's chunked writer) — hands the
     * merged JSON to the core's [importBeanconquerorJson], then merges the
     * resulting plan into the library via [applyBeanImportPlan]. (Shots in the
     * plan are deferred to a later history-enrichment pass.)
     */
    fun importBeanconquerorUri(uri: Uri) {
        scope.launch {
            val merged = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = app.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() } ?: return@runCatching null
                    // PK\x03\x04 magic → a zip archive; else treat as raw JSON text.
                    if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                        mergeBcZip(bytes)
                    } else {
                        String(bytes, Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (merged.isNullOrBlank()) {
                notify("Import failed — couldn't read that file")
                return@launch
            }
            val planJson = runCatching { importBeanconquerorJson(merged, System.currentTimeMillis()) }
                .getOrElse {
                    appendLog("Beanconqueror import failed: ${it.message}")
                    notify("Import failed — not a Beanconqueror export")
                    return@launch
                }
            applyBeanImportPlan(planJson)
        }
    }

    /**
     * Merge a Beanconqueror `.zip` (a main `Beanconqueror.json` plus chunked
     * `Beanconqueror_{Beans,Brews}_N.json` overflow files) into one JSON object
     * the core can parse — the inverse of BC's chunked writer. Mirrors the web
     * `mergeBcArchive`. Returns null if no main file is present.
     */
    private fun mergeBcZip(bytes: ByteArray): String? {
        val entries = HashMap<String, String>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory) entries[e.name] = zin.readBytes().toString(Charsets.UTF_8)
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        val mainKey = entries.keys.firstOrNull { it.lowercase().endsWith("beanconqueror.json") } ?: return null
        val mainObj = runCatching { json.parseToJsonElement(entries.getValue(mainKey)).jsonObject.toMutableMap() }
            .getOrNull() ?: return null
        val re = Regex("Beanconqueror_(Brews|Beans)_(\\d+)\\.json$", RegexOption.IGNORE_CASE)
        data class Chunk(val key: String, val index: Int, val name: String)
        val chunks = entries.keys.mapNotNull { name ->
            re.find(name)?.let { m ->
                val key = if (m.groupValues[1].equals("brews", ignoreCase = true)) "BREWS" else "BEANS"
                Chunk(key, m.groupValues[2].toInt(), name)
            }
        }.sortedWith(compareBy({ it.key }, { it.index }))
        for (c in chunks) {
            val arr = runCatching { json.parseToJsonElement(entries.getValue(c.name)).jsonArray }.getOrNull() ?: continue
            val existing = (mainObj[c.key] as? JsonArray)?.toList() ?: emptyList()
            mainObj[c.key] = JsonArray(existing + arr)
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(mainObj))
    }

    /**
     * Merge a core import-plan JSON's beans + roasters into the library, applying
     * the web's dedup rules: skip a bean whose `beanconquerorId` is already present
     * (resumable re-import); repoint a new bean to an existing same-named roaster
     * (dropping the duplicate roaster); skip a bean matching an existing
     * (roaster, name, roastedOn) triple. Persisted.
     */
    private fun applyBeanImportPlan(planJson: String) {
        val plan = runCatching { json.parseToJsonElement(planJson).jsonObject }.getOrElse {
            appendLog("Import: malformed plan JSON"); return
        }
        fun <T> decodeList(key: String, ser: kotlinx.serialization.KSerializer<T>): List<T> =
            runCatching { json.decodeFromString(ListSerializer(ser), (plan[key] ?: JsonArray(emptyList())).toString()) }
                .getOrElse { emptyList() }
        val wireBeans = decodeList("beans", Bean.serializer())
        val wireRoasters = decodeList("roasters", Roaster.serializer())

        val s = uiState()
        val existingRoasterByLc = s.roasters.associateBy { it.name.trim().lowercase() }
        val existingBcIds = s.beans.mapNotNull { it.beanconquerorId }.toHashSet()
        val wireRoasterById = wireRoasters.associateBy { it.id }

        val newBeans = ArrayList<Bean>()
        val keptRoasterIds = HashSet<String>()
        var skipped = 0
        for (bean in wireBeans) {
            if (bean.beanconquerorId != null && existingBcIds.contains(bean.beanconquerorId)) { skipped++; continue }
            val wireRoaster = bean.roasterId?.let { wireRoasterById[it] }
            val libRoaster = wireRoaster?.name?.let { existingRoasterByLc[it.trim().lowercase()] }
            val resolved = when {
                libRoaster != null && wireRoaster.id != libRoaster.id -> bean.copy(roasterId = libRoaster.id)
                else -> { if (wireRoaster != null) keptRoasterIds.add(wireRoaster.id); bean }
            }
            val tripleDupe = libRoaster != null && s.beans.any {
                it.roasterId == libRoaster.id &&
                    it.name.trim().equals(bean.name.trim(), ignoreCase = true) &&
                    (it.roastedOn ?: "") == (bean.roastedOn ?: "")
            }
            if (tripleDupe) { skipped++; continue }
            newBeans.add(resolved)
        }
        val newRoasters = wireRoasters.filter { keptRoasterIds.contains(it.id) }

        if (newBeans.isEmpty() && newRoasters.isEmpty()) {
            notify(if (skipped > 0) "Already imported — nothing new to add" else "No beans found in that file")
            return
        }
        // Apply onto the CURRENT state (not the snapshot `s`) so a concurrent
        // high-rate telemetry / scale update isn't clobbered (issue 11).
        updateUi { cur ->
            cur.copy(
                beans = cur.beans + newBeans,
                roasters = cur.roasters + newRoasters,
                activeBeanId = cur.activeBeanId ?: newBeans.firstOrNull()?.id,
            )
        }
        persistLibrary()
        val bn = newBeans.size; val rn = newRoasters.size
        updateUi { it.copy(
            status = "Imported $bn bean${if (bn == 1) "" else "s"} · $rn roaster${if (rn == 1) "" else "s"}",
        ) }
    }

    /** Open a bean in the editor (the `bean-edit` route reads this). */
    fun startEditBean(id: String) {
        updateUi { it.copy(editingBeanId = id, draftBean = null) }
    }

    /** Start a brand-new bean in the full editor — stash a blank draft and point
     *  the editor at it. Added to the library only on Save (via [updateBean]). */
    fun startNewBean() {
        val draft = newBean(name = "", roasterId = null, roastLevel = null, roastedOn = null, nowMs = System.currentTimeMillis())
        updateUi { it.copy(draftBean = draft, editingBeanId = draft.id) }
    }

    /**
     * Apply edits to an existing bean (the editor's Save). Resolves the roaster
     * by name (find-or-create), stamps `updatedAt`, and persists.
     */
    fun updateBean(id: String, roasterName: String, transform: (Bean) -> Bean) {
        val s = uiState()
        // Resolve from the library, or from a new-bean draft (the "Add bean" →
        // full-editor Save path — the bean isn't in [beans] yet).
        val existing = s.beans.firstOrNull { it.id == id } ?: s.draftBean?.takeIf { it.id == id } ?: return
        val isNew = s.beans.none { it.id == id }
        val now = System.currentTimeMillis()
        val (roasterId, roasters) = resolveRoaster(roasterName, s.roasters, now)
        // The shell owns the full field mapping (the core Bean already models
        // every field); we only resolve the roaster + stamp updatedAt here.
        val updated = transform(existing).copy(roasterId = roasterId, updatedAt = now)
        updateUi { it.copy(
            beans = if (isNew) s.beans + updated else s.beans.map { if (it.id == id) updated else it },
            roasters = roasters,
            draftBean = null,
        ) }
        persistLibrary()
    }

    // ── Bean bag photos (Phase C) ────────────────────────────────────────────
    /**
     * A FileProvider content Uri for the camera (TakePicture) to write a freshly
     * captured bag photo into — a temp JPEG under `cacheDir/bean-capture/`. The
     * authority matches the manifest's `${'$'}{applicationId}.fileprovider`. Null
     * only if the Uri can't be minted (no FileProvider configured).
     */
    fun newCameraOutputUri(beanId: String): Uri? = runCatching {
        val dir = java.io.File(app.cacheDir, "bean-capture").apply { mkdirs() }
        val file = java.io.File(dir, BeanImageStore.sanitize(beanId) + ".jpg")
        FileProvider.getUriForFile(app, "${coffee.crema.BuildConfig.APPLICATION_ID}.fileprovider", file)
    }.getOrNull()

    /**
     * Store a captured / picked bag photo for [beanId]: read the [uri]'s bytes,
     * write them via [BeanImageStore], and mark the bean (`imageRef` + `updatedAt`)
     * so the library + editor show it without an app restart. Handles both a
     * library bean and a not-yet-saved new-bean [MainUiState.draftBean]. Persisted.
     */
    fun setBeanImageFromUri(beanId: String, uri: Uri) {
        scope.launch {
            val bytes = readUriBytes(uri)
            if (bytes == null || bytes.isEmpty()) { notify("Couldn't read that image"); return@launch }
            withContext(Dispatchers.IO) { BeanImageStore.put(app, beanId, bytes) }
            val ref = BeanImageStore.refForBean(beanId)
            val now = System.currentTimeMillis()
            updateUi { st ->
                st.copy(
                    beans = st.beans.map { if (it.id == beanId) it.copy(imageRef = ref, updatedAt = now) else it },
                    draftBean = st.draftBean?.let { if (it.id == beanId) it.copy(imageRef = ref, updatedAt = now) else it },
                )
            }
            persistLibrary()
        }
    }

    /** Remove a bean's bag photo — delete the file + clear `imageRef`. Persisted. */
    fun clearBeanImage(beanId: String) {
        scope.launch {
            withContext(Dispatchers.IO) { BeanImageStore.delete(app, beanId) }
            val now = System.currentTimeMillis()
            updateUi { st ->
                st.copy(
                    beans = st.beans.map { if (it.id == beanId) it.copy(imageRef = null, updatedAt = now) else it },
                    draftBean = st.draftBean?.let { if (it.id == beanId) it.copy(imageRef = null, updatedAt = now) else it },
                )
            }
            persistLibrary()
        }
    }

    /**
     * Fire the bean's linked-profile auto-load: no-op when unset or already
     * active; dangling link → notify; shot in progress → skip (profile
     * activation reseeds brew params — not mid-extraction); else activate
     * the profile with a toast naming the link.
     */
    fun maybeLoadLinkedProfile(beanId: String) {
        val s = uiState()
        val bean = s.beans.firstOrNull { it.id == beanId } ?: return
        val linkedId = bean.linkedProfileId ?: return
        if (s.activeProfileId == linkedId) return
        val profile = s.profiles.firstOrNull { it.id == linkedId }
        if (profile == null) {
            notify("${bean.name}’s linked profile no longer exists")
            return
        }
        if (s.shotInProgress) {
            notify("Shot in progress — “${profile.name}” not loaded")
            return
        }
        setActiveProfile(linkedId)
        notify("Loaded “${profile.name}” — linked to ${bean.name}")
    }

    /** Remove a bean bag; reselect the first remaining if it was active. Persisted. */
    fun deleteBean(id: String) {
        val remaining = uiState().beans.filterNot { it.id == id }
        val wasActive = uiState().activeBeanId == id
        updateUi { it.copy(
            beans = remaining,
            activeBeanId = if (wasActive) remaining.firstOrNull()?.id else it.activeBeanId,
        ) }
        // Drop the bag's photo too, so a deleted bean leaves no orphan blob.
        scope.launch(Dispatchers.IO) { BeanImageStore.delete(app, id) }
        persistLibrary()
    }

    /** Duplicate a bag into a fresh row (new id, "name (copy)", not favourite). Persisted. */
    fun duplicateBean(id: String) {
        val base = uiState().beans.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        val copy = base.copy(
            id = "bean:" + java.util.UUID.randomUUID(),
            name = "${base.name} (copy)",
            favourite = false,
            createdAt = now,
            updatedAt = now,
        )
        updateUi { it.copy(beans = it.beans + copy) }
        persistLibrary()
    }

    /** Freeze a bag — stamps `frozenOn` to today (ISO yyyy-mm-dd), pausing freshness. Persisted. */
    fun freezeBean(id: String) = mutateBean(id) { it.copy(frozenOn = isoToday(), defrostedOn = null) }

    /**
     * Defrost a bag — records `defrostedOn` and KEEPS `frozenOn` (web semantics:
     * the freeze window `frozenOn..defrostedOn` is history the freshness math
     * may use; "currently frozen" is `frozenOn != null && defrostedOn == null`).
     */
    fun defrostBean(id: String) = mutateBean(id) { it.copy(defrostedOn = isoToday()) }

    /** Archive a bag — stamps `archivedAt`. Persisted. */
    fun archiveBean(id: String) = mutateBean(id) { it.copy(archivedAt = System.currentTimeMillis()) }

    /** Unarchive a bag — clears `archivedAt`. Persisted. */
    fun unarchiveBean(id: String) = mutateBean(id) { it.copy(archivedAt = null) }

    /** Toggle the brew-page favourite star. Persisted. */
    fun toggleBeanFavourite(id: String) = mutateBean(id) { it.copy(favourite = !(it.favourite ?: false)) }

    /** Map a single bean through [transform], stamp `updatedAt`, persist. */
    internal fun mutateBean(id: String, transform: (Bean) -> Bean) {
        val now = System.currentTimeMillis()
        updateUi { s -> s.copy(beans = s.beans.map { if (it.id == id) transform(it).copy(updatedAt = now) else it }) }
        persistLibrary()
    }

    /** Today as an ISO `yyyy-mm-dd` string (matches the Bean date fields). */
    private fun isoToday(): String =
        java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()

    // ── Roaster directory ───────────────────────────────────────────────────

    /**
     * Select the roaster the pushed `roaster-edit` route edits (phone form
     * factor; the tablet edits roasters in an inline dialog). Null = a new
     * roaster draft.
     */
    fun startEditRoaster(id: String?) {
        updateUi { it.copy(editingRoasterId = id) }
    }

    /** Add a roaster to the directory. Persisted. */
    fun addRoaster(name: String, website: String?, city: String?, country: String?, notes: String) {
        if (name.isBlank()) return
        val now = System.currentTimeMillis()
        val r = newRoaster(name.trim(), now).copy(
            website = website?.takeIf { it.isNotBlank() },
            city = city?.takeIf { it.isNotBlank() },
            country = country?.takeIf { it.isNotBlank() },
            notes = notes,
        )
        updateUi { it.copy(roasters = it.roasters + r) }
        persistLibrary()
    }

    /** Update a roaster's editable fields. Persisted. */
    fun updateRoaster(id: String, name: String, website: String?, city: String?, country: String?, notes: String) {
        if (name.isBlank()) return
        val now = System.currentTimeMillis()
        updateUi { it.copy(
            roasters = it.roasters.map {
                if (it.id == id) it.copy(
                    name = name.trim(),
                    website = website?.takeIf { w -> w.isNotBlank() },
                    city = city?.takeIf { c -> c.isNotBlank() },
                    country = country?.takeIf { c -> c.isNotBlank() },
                    notes = notes,
                    updatedAt = now,
                ) else it
            },
        ) }
        persistLibrary()
    }

    /** Delete a roaster; detach its bags (clear their `roasterId`). Persisted. */
    fun deleteRoaster(id: String) {
        updateUi { s ->
            s.copy(
                roasters = s.roasters.filterNot { it.id == id },
                beans = s.beans.map { if (it.roasterId == id) it.copy(roasterId = null) else it },
            )
        }
        persistLibrary()
    }

    /** Open a roaster's website in the browser (best-effort; prepends https:// if bare). */
    fun visitRoasterWebsite(url: String?) {
        val u = url?.takeIf { it.isNotBlank() } ?: return
        val full = if (u.startsWith("http", ignoreCase = true)) u else "https://$u"
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(full)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { app.startActivity(intent) }.onFailure { appendLog("Visit failed: ${it.message}") }
    }

    // ── Shot history ──────────────────────────────────────────────────────────

    /**
     * Capture a completed shot into the log (newest first, capped) and persist.
     * Profile / bean names come from the active selections at completion. v1
     * stores a summary only — the telemetry slice for the detail chart is a
     * later increment.
     */
    fun captureCompletedShot(
        durationMs: Long,
        yieldG: Float?,
        peakPressure: Float?,
        peakTemp: Float?,
        now: Long = System.currentTimeMillis(),
        shotId: String = newShotId(),
        disposition: ShotDisposition = ShotDisposition.Record,
        peakWeightG: Float? = null,
    ) {
        val s = uiState()
        val profile = s.profiles.firstOrNull { it.id == s.activeProfileId }
        // The core classified this completion (review #40 —
        // `de1_domain::shot_disposition`, same rule on web). SkipCleaning:
        // machine maintenance runs in the DE1's Espresso state, so a
        // cleaning/backflush profile would be recorded as a shot — skip the
        // capture entirely (no history row, no Visualizer, no bean debit;
        // nothing was ground). Decenza #1325 (maincontroller.cpp:1841-1853).
        // Descale/calibrate run in their own machine states, never here.
        if (disposition == ShotDisposition.SkipCleaning) {
            appendLog("Cleaning run finished — not recorded as a shot")
            return
        }
        // Snapshot the recipe (as-run) as the core wire Profile so the upload
        // embeds real steps (#12) — not just the name. You can't switch profiles
        // mid-pour, so the active profile is the one that ran. Convert from the
        // FULL raw library JSON (the same source startShot uploads from) — NOT a
        // re-encode of the thin display model: kotlinx omits fields sitting at
        // their declared defaults (a segment target of 0, a dose of exactly
        // 18 g…), the core requires them, and the failed conversion silently
        // dropped the snapshot for any such profile.
        val profileWire: JsonObject? = s.activeProfileId?.let { profileJson(it) }?.let { raw ->
            runCatching {
                json.parseToJsonElement(cremaProfileToWire(raw)) as? JsonObject
            }.onFailure { appendLog("Shot profile snapshot failed: ${it.message}") }.getOrNull()
        }
        val bean = s.beans.firstOrNull { it.id == s.activeBeanId }
        val roasterName = bean?.roasterId?.let { rid -> s.roasters.firstOrNull { it.id == rid }?.name }
        // Freeze a full bean snapshot (the core wire shape) at shot time — the
        // single source for the Visualizer wire and the "Roaster · Name" display.
        val beanSnapshot = bean?.let { b ->
            ShotBean(
                beanId = b.id,
                name = b.name,
                roasterName = roasterName,
                roastedOn = b.roastedOn,
                roastLevel = b.roastLevel,
                tags = b.tags,
                grinderSetting = b.grinderSetting?.takeIf { it.isNotBlank() },
                grinder = b.grinder?.takeIf { it.isNotBlank() },
            )
        }
        val shot = StoredShot(
            id = shotId,
            completedAtMs = now,
            durationMs = durationMs,
            yieldG = yieldG,
            doseG = profile?.dose,
            // The QC grind dial actually used (issue 15) — null until the user sets one.
            grindSetting = s.qcGrind,
            yieldTargetG = (s.brewParams?.yieldOut ?: profile?.yieldOut?.toDouble())?.toFloat(),
            peakPressure = peakPressure,
            peakTemp = peakTemp,
            // The stats' yield fallback when the settled final weight is
            // missing (review #41) — the event has carried it all along.
            peakWeightG = peakWeightG,
            profileName = profile?.name,
            profile = profileWire,
            bean = beanSnapshot,
            // The buffer still holds the just-finished shot (cleared on the next
            // ShotStarted); downsample it for the detail chart.
            samples = downsampleForStorage(s.shotTelemetry),
        )
        // Aborted-shot auto-discard (Decenza abortedshotclassifier.h:19-25,
        // validated over 882 shots): under 10 s of flow AND under 5 g in the
        // cup is an aborted pull, not a shot — it would only pollute history
        // and Visualizer. The rule lives in the core now; the mechanics stay
        // here: held for an undo toast instead of a modal, and the bean debit
        // below still runs (the dose was physically ground) as does the SAW
        // model save (its own gates reject bad samples).
        if (disposition == ShotDisposition.DiscardAborted) {
            discardedShot = shot
            updateUi { it.copy(
                // The Last-shot card would point at a shot history doesn't
                // hold — clear it for an aborted pull.
                lastShot = null,
                discardToastMessage = "Aborted shot discarded (${fmt("%.1f", durationMs / 1000f)} s)",
            ) }
            appendLog("Shot discarded as aborted (<10 s, <5 g)")
        } else {
            val next = (listOf(shot) + s.history).take(HistoryStore.MAX_SHOTS)
            updateUi { it.copy(history = next) }
            scope.launch { historyStore.save(next) }
            // Auto-sync: push the fresh shot to Visualizer when armed + signed in.
            visualizer.maybeAutoUpload(shot)
        }
        // Persist the learned SAW drip model — a weight-stopped shot just
        // added a training sample in the core.
        scope.launch { persistSawModel() }

        // Burn this shot's dose down from its bag's remaining weight — live
        // shots only (a replay-demo must never touch real inventory). The core
        // `debitRemaining` owns the rule + its no-ops: it returns null when
        // there's nothing to debit (bag already empty / bad dose), so we persist
        // — and touch updatedAt — ONLY on a real debit. If it just emptied the
        // bag, surface it so the user can switch / archive / rate.
        val dose = profile?.dose
        if (!s.replayPrimary && bean != null && dose != null) {
            val debited = debitRemaining(bean.remaining ?: 0f, dose)
            if (debited != null) {
                mutateBean(bean.id) { it.copy(remaining = debited) }
                if (debited == 0f) {
                    val rateHint = if ((bean.rating?.toInt() ?: 0) == 0) " — rate it in Beans" else ""
                    notify("That was the last of ${bean.name}; bag empty$rateHint")
                }
            }
        }
    }

    /** Apply a user rating / tasting-notes edit to a logged shot. Persisted.
     *  An already-uploaded shot mirrors the edit to Visualizer (soft). */
    fun updateShot(id: String, rating: Int, notes: String) {
        updateUi { st ->
            st.copy(
                history = st.history.map {
                    if (it.id == id) it.copy(rating = rating.coerceIn(0, 5).takeIf { r -> r > 0 }, notes = notes.ifBlank { null }) else it
                },
            )
        }
        val snapshot = uiState().history
        scope.launch { historyStore.save(snapshot) }
        schedulePatchEdited(id)
    }

    /**
     * Set (or with null, clear) a shot's per-upload Visualizer privacy
     * override. Persisted; mirrors to the uploaded copy like [updateShot].
     */
    fun setShotPrivacy(id: String, privacy: String?) {
        updateUi { st ->
            st.copy(history = st.history.map { if (it.id == id) it.copy(privacy = privacy) else it })
        }
        val snapshot = uiState().history
        scope.launch { historyStore.save(snapshot) }
        schedulePatchEdited(id)
    }

    /**
     * Set (or with null, clear) the grind recorded on a stored shot (issue
     * #16 follow-up: dialing-in spans shots, so the log needs fixing up after
     * the fact). Persisted; mirrors to the uploaded copy like [updateShot].
     */
    fun setShotGrind(id: String, grind: Float?) {
        updateUi { st ->
            st.copy(history = st.history.map { if (it.id == id) it.copy(grindSetting = grind) else it })
        }
        val snapshot = uiState().history
        scope.launch { historyStore.save(snapshot) }
        schedulePatchEdited(id)
    }

    /**
     * Re-attribute a stored shot to another library bean — or with null, to no
     * bean (issue #16 follow-up: the wrong bean was active when the shot was
     * pulled). Freezes a fresh snapshot from the CURRENT library bean, the
     * same shape capture builds, so the display label, Visualizer wire, and
     * bean-search all follow. The recorded grind is untouched — it describes
     * the physical pull, not the attribution.
     */
    fun setShotBean(id: String, beanId: String?) {
        val s = uiState()
        val bean = beanId?.let { bid -> s.beans.firstOrNull { it.id == bid } }
        val roasterName = bean?.roasterId?.let { rid -> s.roasters.firstOrNull { it.id == rid }?.name }
        val beanSnapshot = bean?.let { b ->
            ShotBean(
                beanId = b.id,
                name = b.name,
                roasterName = roasterName,
                roastedOn = b.roastedOn,
                roastLevel = b.roastLevel,
                tags = b.tags,
                grinderSetting = b.grinderSetting?.takeIf { it.isNotBlank() },
                grinder = b.grinder?.takeIf { it.isNotBlank() },
            )
        }
        updateUi { st ->
            st.copy(history = st.history.map { if (it.id == id) it.copy(bean = beanSnapshot) else it })
        }
        val snapshot = uiState().history
        scope.launch { historyStore.save(snapshot) }
        schedulePatchEdited(id)
    }

    /** Debounced edit→Visualizer mirror: the notes field fires per keystroke
     *  and star taps come in bursts — one PATCH goes out 1.5 s after the last
     *  edit, with the shot's then-current state. */
    private var shotPatchJob: Job? = null
    private fun schedulePatchEdited(id: String) {
        shotPatchJob?.cancel()
        shotPatchJob = scope.launch {
            delay(1_500)
            uiState().history.firstOrNull { it.id == id }?.let { visualizer.patchEditedShot(it) }
        }
    }

    /** Undo an aborted-shot discard: persist the held shot after all. */
    fun undoDiscardShot() {
        val shot = discardedShot ?: return
        discardedShot = null
        val next = (listOf(shot) + uiState().history).take(HistoryStore.MAX_SHOTS)
        updateUi { it.copy(
            history = next,
            discardToastMessage = null,
            lastShot = LastShot(
                durationMs = shot.durationMs,
                yieldG = shot.yieldG,
                peakPressure = shot.peakPressure,
                peakTemp = shot.peakTemp,
                completedAtMs = shot.completedAtMs,
                id = shot.id,
            ),
        ) }
        scope.launch { historyStore.save(next) }
        visualizer.maybeAutoUpload(shot)
    }

    /** The discard snackbar timed out / was dismissed — drop the held shot. */
    fun consumeDiscardToast() {
        discardedShot = null
        updateUi { it.copy(discardToastMessage = null) }
    }
}
