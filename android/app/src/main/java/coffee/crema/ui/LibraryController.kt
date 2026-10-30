package coffee.crema.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import coffee.crema.beans.BeanImageStore
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.LibraryStore
import coffee.crema.beans.beanLibraryJsonl
import coffee.crema.beans.isCremaLibraryJsonl
import coffee.crema.beans.mergeBeanImport
import coffee.crema.beans.newBean
import coffee.crema.beans.newRoaster
import coffee.crema.core.Bean
import coffee.crema.core.BrewTempRange
import coffee.crema.core.brewTempRange
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
import coffee.crema.core.effectiveCremaProfile
import coffee.crema.core.withDerivedBrewTemp
import coffee.crema.core.preinfusionSeconds
import coffee.crema.core.creditRemaining
import coffee.crema.core.debitRemaining
import coffee.crema.core.resettleRemaining
import coffee.crema.core.ShotCaptureContext
import coffee.crema.core.exportBackupJsonl
import coffee.crema.core.exportProfileFile
import coffee.crema.core.importProfileFile
import coffee.crema.core.exportBeanconquerorMainJson
import coffee.crema.core.importBeanconquerorJson
import coffee.crema.core.importCremaJsonl
import coffee.crema.core.exportV2JsonShot
import coffee.crema.core.newShotId
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.history.coreShotJson
import coffee.crema.history.isBrewLog
import coffee.crema.history.isManualLog
import coffee.crema.persist.ImportLimits
import coffee.crema.persist.ImportTooLargeException
import coffee.crema.persist.readBytesCapped
import coffee.crema.persist.readZipCapped
import coffee.crema.profiles.BrewDefaults
import coffee.crema.profiles.CremaProfile
import coffee.crema.profiles.CustomProfileStore
import coffee.crema.profiles.HiddenProfileStore
import coffee.crema.profiles.PinnedProfileStore
import coffee.crema.profiles.SegmentEdit
import coffee.crema.profiles.brewDefaultsJson
import coffee.crema.profiles.duplicatedCustomProfileJson
import coffee.crema.profiles.patchCremaProfileJson
import coffee.crema.profiles.withEditedSegmentsJson
import coffee.crema.profiles.profileIdOf
import coffee.crema.profiles.quickPresetJson
import coffee.crema.profiles.setProfilePinnedJson
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.toCommonSettings
import coffee.crema.settings.withCommonSettings
import coffee.crema.visualizer.VisualizerSync
import coffee.crema.visualizer.shotHistoryJsonl
import coffee.crema.visualizer.shotsFromCremaJsonl
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
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import coffee.crema.core.ShotMachine

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
    /** The connected DE1's identity, stamped onto each recorded shot (#84). */
    private val liveMachine: () -> ShotMachine?,
    /** Push a just-recorded shot to every armed destination, one notice for the batch ([SharingController.autoUpload]). */
    private val autoUploadShot: (shot: StoredShot, fullSamples: List<TelemetrySample>?) -> Unit,
    /** Merge restored brew recipes + their organisation into the VM-owned
     *  recipe store (WIPE replaces); returns how many recipes were added. */
    private val applyRestoredRecipes: (recipes: List<coffee.crema.core.BrewRecipe>, meta: JsonObject?, wipe: Boolean) -> Int = { _, _, _ -> 0 },
    /** Apply a backup's custom brewing methods (merge by id / wipe); returns how many were added. */
    private val applyRestoredCustomMethods: (methods: List<coffee.crema.core.CustomBrewMethod>, wipe: Boolean) -> Int = { _, _ -> 0 },
    /** Read this device's own UI snapshot (library rows, active ids,
     *  live-shot state): what the library persists. */
    private val uiState: () -> MainUiState,
    /** The UI snapshot as the screens show it: on a remote, the hub's machine
     *  and session settings over this device's own (spec §7.2). Library
     *  actions that act on what is on screen read this. */
    private val effectiveState: () -> MainUiState = uiState,
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
    /**
     * A stored shot's record-carried annotations (rating, notes, grind, bean)
     * were edited — the Decent destination queues its debounced
     * `replace=1` re-upload ([coffee.crema.decent.DecentSync.scheduleReplaceAfterEdit]).
     */
    private val onShotAnnotationsEdited: (shotId: String) -> Unit = {},
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
     *  refresh + persist — a hub's custom profile, which a remote adopts when
     *  it becomes standalone (issue 05). Returns once it is on disk, so the
     *  T6 commit never saves an `activeProfileId` that isn't (§7.5 step 5). */
    suspend fun adoptCustomProfile(raw: String) {
        customProfilesJson = listOf(raw) + customProfilesJson
        refreshProfiles()
        customProfileStore.save(customProfilesJson, ++customProfilesGeneration)
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
        builtinProfiles = list.map { p -> p.copy(preinfusionS = builtinProfileJson[p.id]?.let(::corePreinfusion)) }
        refreshProfiles()
    }

    /** The core's pre-infusion seconds for a full profile JSON (cards, brew
     *  header); null when the core can't read it. */
    private fun corePreinfusion(raw: String): Float? = runCatching { preinfusionSeconds(raw) }.getOrNull()

    /** Load the persisted bean library into the UI snapshot at startup. */
    suspend fun loadLibrary() {
        val lib = library.load()
        updateUi { it.copy(
            beans = lib.beans,
            roasters = lib.roasters,
            activeBeanId = lib.activeBeanId ?: lib.beans.firstOrNull()?.id,
        ) }
    }

    /** Load the persisted shot log at startup: the rows now, the telemetry
     *  series in the background (F10 — ~0.1 s for 300 shots on a desktop JVM,
     *  more on a phone), so the startup sequence (prefs, auto-connect) never
     *  waits on it. Until a series lands its shot shows no curve. */
    suspend fun loadHistory() {
        val rows = historyStore.loadRows()
        updateUi { it.copy(history = rows) }
        val missing = rows.filter { it.samples.isEmpty() }.map { it.id }
        if (missing.isEmpty()) return
        scope.launch {
            val series = historyStore.loadSeries(missing)
            if (series.isEmpty()) return@launch
            updateUi { st ->
                st.copy(
                    history = st.history.map { shot ->
                        if (shot.samples.isEmpty()) series[shot.id]?.let { shot.copy(samples = it) } ?: shot else shot
                    },
                )
            }
        }
    }

    /** Load the pinned/hidden built-in overlays at startup (before the customs). */
    suspend fun loadProfileMeta() {
        pinnedIds = pinnedProfileStore.load()
        hiddenIds = hiddenProfileStore.load()
    }

    /** Load the persisted custom profiles into the merged list at startup. */
    suspend fun loadCustomProfiles() {
        // The top-level brew temp follows the first step (the dial's anchor):
        // re-derived for customs saved before that rule.
        customProfilesJson = customProfileStore.load().map(::derivedBrewTemp)
        refreshProfiles()
    }

    /** [raw] with its top-level brew temp derived from its first step by the
     *  core, so the cards, the dial and the editor show the same number; a
     *  profile the core can't read is kept as it is. */
    private fun derivedBrewTemp(raw: String): String = runCatching { withDerivedBrewTemp(raw) }.getOrDefault(raw)

    /**
     * The editor's segments after its Brew temp field moved to [target]: the
     * core shifts every step by the difference from the first step's
     * temperature, keeping the shape and the bounds (Decenza
     * `applyTemperatureToProfile`). Unchanged when the profile can't be found
     * or read.
     */
    fun editorBrewTemp(id: String, segments: List<SegmentEdit>, target: Double): List<SegmentEdit> {
        val base = draftProfileJson?.takeIf { uiState().editingProfileId == id } ?: profileJsonById[id] ?: return segments
        return runCatching {
            val edited = withEditedSegmentsJson(base, segments, json)
            val shifted = effectiveCremaProfile(edited, """{"brewTemp":$target}""")
            val temps = json.parseToJsonElement(shifted).jsonObject["segments"]!!.jsonArray
                .map { it.jsonObject["temp"]?.jsonPrimitive?.floatOrNull }
            segments.mapIndexed { i, seg -> temps.getOrNull(i)?.let { seg.copy(temp = it) } ?: seg }
        }.getOrDefault(segments)
    }

    /** The editor's Brew temp field range for these [segments]: the core's
     *  `brew_temp_range` (F6), so no step leaves the core's bounds after a
     *  shift. Null when the draft can't be built (the caller falls back). */
    fun editorBrewTempRange(id: String, segments: List<SegmentEdit>): Pair<Double, Double>? {
        val base = draftProfileJson?.takeIf { uiState().editingProfileId == id } ?: profileJsonById[id] ?: return null
        return runCatching {
            val r = json.decodeFromString(BrewTempRange.serializer(), brewTempRange(withEditedSegmentsJson(base, segments, json)))
            r.min.toDouble() to r.max.toDouble()
        }.getOrNull()
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
                ?.copy(preinfusionS = corePreinfusion(raw))
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
        updateUi { st ->
            val active = current ?: all.firstOrNull { it.id !in hiddenIds }?.id
            // A reseeded selection is a profile change: the override goes too.
            val reseeded = if (active == st.activeProfileId) st else st.withActiveProfileId(active)
            reseeded.copy(profiles = all, hiddenProfileIds = hiddenIds)
        }
    }

    /** Persist the current custom profiles (best-effort, off the main thread). */
    private fun persistCustomProfiles() {
        val snapshot = customProfilesJson
        // Numbered as the snapshot is taken: the store skips one that lands
        // after a newer one (the awaited T6 adoption save, say).
        val generation = ++customProfilesGeneration
        scope.launch { customProfileStore.save(snapshot, generation) }
    }

    /** The last custom-profile snapshot's number. */
    private var customProfilesGeneration = 0L

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
        // The profile and dial on screen: on a remote, the hub's.
        val s = effectiveState()
        val activeId = s.activeProfileId ?: return
        val base = profileJsonById[activeId] ?: run {
            // A hub's custom profile this library doesn't have.
            if (s.mirror != null) notify("That profile is only on ${s.remoteHubLabel()}")
            return
        }
        val active = s.profiles.firstOrNull { it.id == activeId }
        // The dial baked in by the core exactly as a shot runs it: brew temp
        // shifts every segment, pre-infusion rescales the leading segments,
        // dose / yield replace (id / name / everything else preserved).
        val effective = runCatching { effectiveCremaProfile(base, s.brewParams.overridesJson(json)) }.getOrElse {
            appendLog("Save profile failed: ${it.message}")
            return
        }
        if (active?.source == "custom") {
            // User-defined: replace it by id in place, persist — it stays active.
            val updated = effective
            customProfilesJson = customProfilesJson.map { if (profileIdOf(it, json) == activeId) updated else it }
            refreshProfiles()
            persistCustomProfiles()
            resetBrewParams()
            notify("Profile updated")
        } else {
            // Predefined (read-only): save a NEW custom copy carrying the dialled
            // values, then make it active.
            val preset = runCatching { quickPresetJson(effective, name, json) }.getOrElse {
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
        // The saved brew temp is the first step's (the editor's field shifted
        // every step as it moved; a direct segment 0 edit moves it too).
        val saved = derivedBrewTemp(patched)
        customProfilesJson = if (isExisting) {
            customProfilesJson.map { if (profileIdOf(it, json) == id) saved else it }
        } else {
            listOf(saved) + customProfilesJson
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
        val wasActive = uiState().activeProfileId == id
        if (wasActive) updateUi { it.withActiveProfileId(null) }
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
        if (wasActive) activateReseeded()
    }

    /** The active profile was deleted or hidden and [refreshProfiles] picked the
     *  next visible one: select it like any other pick, so its stop targets,
     *  the desired profile and the mirrors follow (on this device's own
     *  selection; a remote's screen shows its hub's). */
    private fun activateReseeded() {
        if (uiState().mirror != null) return
        uiState().activeProfileId?.let(setActiveProfile)
    }

    /** Archive (hide) a built-in profile — built-ins can't be deleted, so this
     *  hides it from the active grid. Restored from the "Hidden" filter. Persisted. */
    fun archiveBuiltinProfile(id: String) {
        hiddenIds = hiddenIds + id
        // If the hidden profile was active, drop the selection so refreshProfiles
        // re-seeds the Brew header to a still-visible profile.
        val wasActive = uiState().activeProfileId == id
        if (wasActive) updateUi { it.withActiveProfileId(null) }
        refreshProfiles()
        if (wasActive) activateReseeded()
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

    /** Share one profile as the core's community-v2 `.json` profile file (the
     *  file web exports, readable by de1app, reaprime and Decenza). */
    fun exportProfile(id: String) {
        val text = profilesFile(listOf(id)) { appendLog(it) } ?: return
        shareText(text, "Share profile")
    }

    /** The core's profile file for the profiles [ids], in order: pretty v2 for
     *  one, JSON lines for several. Null (after telling [report] why) when none
     *  is found or the core refuses. */
    fun profilesFile(ids: List<String>, report: (String) -> Unit): String? {
        val profiles = ids.mapNotNull { id ->
            profileJsonById[id] ?: customProfilesJson.firstOrNull { profileIdOf(it, json) == id }
        }
        if (profiles.isEmpty()) { report("Export — no profiles"); return null }
        return runCatching { exportProfileFile("[${profiles.joinToString(",")}]") }
            .getOrElse { report("Export profiles failed: ${it.message}"); null }
    }

    /** Export a stored shot as a community-v2 `.shot.json` via the system
     *  share sheet — the core's exporter (F10), which web and every DE1 app
     *  read; it used to be Android's flat model, which nothing could import. */
    fun exportShot(id: String) {
        val shot = uiState().history.firstOrNull { it.id == id } ?: return
        val text = runCatching { exportV2JsonShot(wireShotJson(shot, settingsGrinder()).toString()) }
            .getOrElse { appendLog("Export shot failed: ${it.message}"); return }
        shareText(text, "Share shot")
    }

    /** Export the whole shot history as Crema `crema/v1` JSONL — Settings →
     *  Sharing. The core's format (F10): both shells import it back. */
    fun exportAllShots() {
        val text = shotsJson(null) ?: return
        shareText(text, "Export shots")
    }

    /** The Settings grinder, for an export of a shot captured before F10
     *  froze the grinder on it. */
    private fun settingsGrinder(): String? = effectiveState().grinderModel.trim().ifEmpty { null }

    /** Export the bean library (beans + roasters) as Crema JSONL — Settings → Sharing. */
    fun exportBeansLibrary() {
        val text = beansLibraryJsonl { appendLog(it) } ?: return
        shareText(text, "Export beans & roasters")
    }

    // ── Export-content builders (file export via SAF; share-sheet can't carry the
    //    large telemetry payloads — the Binder transaction limit). Return null +
    //    set a status when there's nothing to export. ──────────────────────────

    /** Bean library as a Crema `crema/v1` JSONL export (beans + roasters) — the
     *  core's format, re-importable on both shells. */
    fun beansLibraryJson(): String? = beansLibraryJsonl { notify(it) }

    /** [beanLibraryJsonl] over the current library, or null after telling [report] why. */
    private fun beansLibraryJsonl(report: (String) -> Unit): String? {
        val s = uiState()
        if (s.beans.isEmpty() && s.roasters.isEmpty()) { report("Export — empty library"); return null }
        return runCatching {
            beanLibraryJsonl(s.beans, s.roasters, System.currentTimeMillis(), coffee.crema.BuildConfig.VERSION_NAME)
        }.getOrElse { report("Export library failed: ${it.message}"); null }
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

    /** Shots as the core's Crema `crema/v1` JSONL ([shotHistoryJsonl]) — all
     *  (ids null) or only the given ids. Both shells import it (F10). */
    fun shotsJson(ids: List<String>?): String? {
        val history = uiState().history
        val shots = if (ids == null) history else { val w = ids.toHashSet(); history.filter { it.id in w } }
        if (shots.isEmpty()) { notify("Export — no shots"); return null }
        return runCatching {
            shotHistoryJsonl(shots, settingsGrinder(), System.currentTimeMillis(), coffee.crema.BuildConfig.VERSION_NAME)
        }.getOrElse { appendLog("Export shots failed: ${it.message}"); null }
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

    /** All profiles (built-ins + customs) as the core's profile file: one
     *  community-v2 profile per line (`.jsonl`), the file web exports. */
    fun allProfilesFile(): String? = profilesFile(uiState().profiles.map { it.id }) { notify(it) }

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

    /**
     * The user-facing reason the last import read was refused for size
     * ([ImportTooLargeException], review 05 P3-2), consumed by [readFailure] so
     * the snackbar names the limit instead of a generic "couldn't read".
     */
    @Volatile private var lastTooLarge: String? = null

    /** Note a size refusal from [block]; any other failure stays a plain null. */
    private inline fun <T> capped(block: () -> T?): T? =
        runCatching(block).onFailure { if (it is ImportTooLargeException) lastTooLarge = it.message }.getOrNull()

    /** The message for a failed import read: the size refusal if there was one, else [fallback]. */
    private fun readFailure(fallback: String): String = lastTooLarge?.also { lastTooLarge = null } ?: fallback

    /** Read a content Uri as UTF-8 text (at most [ImportLimits.MAX_FILE_BYTES]), off the main thread. */
    private suspend fun readUriText(uri: Uri): String? = withContext(Dispatchers.IO) {
        lastTooLarge = null
        capped {
            app.contentResolver.openInputStream(uri)?.use { String(it.readBytesCapped(ImportLimits.MAX_FILE_BYTES), Charsets.UTF_8) }
        }
    }

    /** Read a SAF [uri]'s raw bytes (at most [ImportLimits.MAX_FILE_BYTES]), or null on failure. */
    private suspend fun readUriBytes(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        lastTooLarge = null
        capped { app.contentResolver.openInputStream(uri)?.use { it.readBytesCapped(ImportLimits.MAX_FILE_BYTES) } }
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
        capped {
            var jsonl: String? = null
            // Only the backup members are inflated, each within the size caps
            // (review 05 P3-2): a zip bomb stops with a clear error, not an OOM.
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
                readZipCapped(
                    zis,
                    accept = { it == "backup.jsonl" || it == "crema.jsonl" || it.startsWith("images/") },
                ) { name, entryBytes ->
                    if (name.startsWith("images/")) {
                        val beanId = name.substringAfter("images/")
                        if (beanId.isNotEmpty()) runCatching { BeanImageStore.put(app, beanId, entryBytes) }
                    } else {
                        jsonl = String(entryBytes, Charsets.UTF_8)
                    }
                }
            }
            jsonl
        }
    }

    /** Import profiles from any profile file — community-v2 `.json`, a `.jsonl`
     *  bundle, a de1app `.tcl`, a Crema backup, an old Android export — read by
     *  the core, which returns fresh custom profiles (core id, brew temp from the
     *  first step) and what didn't import (#12, F12). The core skips, and counts,
     *  the profiles already in the library — custom and built-in, hidden ones
     *  included — by title and steps (F12b). */
    fun importProfiles(uri: Uri) {
        scope.launch {
            val text = readUriText(uri)
            if (text.isNullOrBlank()) { notify(readFailure("Import failed — couldn't read that file")); return@launch }
            val library = "[" + (customProfilesJson + builtinProfileJson.values).joinToString(",") + "]"
            val result = runCatching { json.parseToJsonElement(importProfileFile(text, library)).jsonObject }
                .getOrElse { notify("Couldn’t import profile — ${it.message}"); return@launch }
            val imported = result["profiles"]?.jsonArray.orEmpty().map { it.toString() }
            val skipped = result["skipped_duplicates"]?.jsonPrimitive?.intOrNull ?: 0
            val errors = result["errors"]?.jsonArray.orEmpty().map { e ->
                val obj = e.jsonObject
                val message = obj["message"]?.jsonPrimitive?.content.orEmpty()
                obj["item"]?.jsonPrimitive?.intOrNull?.let { "item $it: $message" } ?: message
            }
            if (imported.isNotEmpty()) {
                customProfilesJson = imported + customProfilesJson
                refreshProfiles()
                persistCustomProfiles()
            }
            notify(profileImportSummary(imported.size, skipped, errors))
        }
    }

    /** Import shots from a Crema export — the `crema/v1` JSONL, or an older
     *  Android flat array / single / .jsonl — skipping ids already in history. */
    fun importShots(uri: Uri) {
        scope.launch {
            val text = readUriText(uri)?.trim()
            if (text.isNullOrBlank()) { notify(readFailure("Import failed — couldn't read that file")); return@launch }
            // Crema `crema/v1` JSONL (the all-shots export, F10) through the core.
            var shots = shotsFromCremaJsonl(text, json)
            if (shots.isEmpty()) shots = runCatching {
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

    /** Snapshot the backup inputs, or null + a notice when there's nothing to
     *  back up. Custom profiles only — built-ins ship with the app, so backing
     *  them up would only create duplicates. Cheap; runs on the caller. */
    private fun backupSnapshot(): BackupSnapshot? {
        val customs = customProfilesJson
        val s = uiState()
        // "Nothing to back up" only when EVERYTHING the bundle carries is empty /
        // default — not just the four data types. Customised settings (theme,
        // units, …) and pinned / hidden built-ins are backup-worthy on their own,
        // even with no custom profiles / beans / shots. Match web's
        // `settingsAreDefault` gate so the same app state gives the same answer on
        // both shells (issue 06 F2; web previously said "back up", Android "nothing").
        // Brew recipes: the user's own + copies only — built-ins are bundled.
        val userRecipes = s.brewRecipes.filterNot { r -> s.builtinRecipes.any { it.id == r.id } }
        val noData = customs.isEmpty() && s.beans.isEmpty() && s.roasters.isEmpty() && s.history.isEmpty() &&
            userRecipes.isEmpty() && s.customMethods.isEmpty()
        val noProfileOrg = pinnedIds.isEmpty() && hiddenIds.isEmpty() &&
            s.lastRecipeByMethod.isEmpty() && s.hiddenBuiltinRecipes.isEmpty()
        val settingsDefault =
            currentPrefs().toCommonSettings() == AppPrefs().toCommonSettings() && currentPrefs().qcGrind == null
        if (noData && noProfileOrg && settingsDefault) {
            notify("Nothing to back up yet"); return null
        }
        return BackupSnapshot(customs, s, pinnedIds, hiddenIds, currentPrefs(), visualizer.backupPrefs(), userRecipes)
    }

    /** Build the whole-app backup bundle ([backupBundleJsonl]) from [snap], or
     *  null + a notice on failure. Reads only [snap], so it is safe off the main
     *  thread. */
    private fun backupBundleJson(snap: BackupSnapshot): String? = runCatching {
        backupBundleJsonl(snap, json, System.currentTimeMillis(), coffee.crema.BuildConfig.VERSION_NAME, deviceLabel())
    }.getOrElse { appendLog("Backup failed: ${it.message}"); notify("Backup failed"); null }

    /**
     * Wrap [backupBundleJson]'s JSONL into a `.crema.zip`: `backup.jsonl` plus, for
     * every bean with a stored photo, `images/<RAW bean id>` (the cross-shell key
     * web + Android restore by). Photos resolve by file existence (not by parsing
     * `imageRef`), so a web-restored bag's photo rides along too. Null + a notice
     * when there's nothing to back up. Shared by the local SAF save + Drive upload.
     * Snapshots on the caller's thread, then serialises, exports and zips on
     * [Dispatchers.Default] — never on Main.
     */
    suspend fun buildBackupZipBytes(): ByteArray? =
        snapshotThenBuild(::backupSnapshot, Dispatchers.Default) { snap ->
            val jsonl = backupBundleJson(snap) ?: return@snapshotThenBuild null // already notified
            runCatching {
                zipBackupBytes(
                    jsonl,
                    snap.state.beans.asSequence()
                        .filter { BeanImageStore.exists(app, it.id) }
                        .mapNotNull { bean ->
                            runCatching { BeanImageStore.beanImageFile(app, bean.id).readBytes() }
                                .getOrNull()?.let { bean.id to it }
                        },
                )
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
            if (bytes == null || bytes.isEmpty()) { notify(readFailure("Restore failed — couldn't read that file")); return@launch }
            restoreBackupBytes(bytes, mode)
        }
    }

    /** Apply a backup from raw bytes — a `.crema.zip` (web full backup: JSONL +
     *  bean photos) or a bare `.crema.jsonl` / `.crema`. Shared by the SAF file
     *  restore and the Google Drive restore. */
    suspend fun restoreBackupBytes(bytes: ByteArray, mode: MainViewModel.RestoreMode) {
        val text = (if (isZip(bytes)) extractBackupJsonl(bytes) else String(bytes, Charsets.UTF_8))?.trim()
        if (text.isNullOrBlank()) { notify(readFailure("Restore failed — not a Crema backup")); return }
        restoreBackupFromText(text, mode)
    }

    /** Parse + apply a `crema-backup` bundle's raw text — shared by the SAF restore
     *  ([restoreBackup]) and the Google Drive restore (the downloaded `.crema`). */
    fun restoreBackupFromText(text: String, mode: MainViewModel.RestoreMode) {
        scope.launch {
            // The core parses the bundle (`parseBackup` → importBackupJsonl, the
            // parser web uses); re-bind its result to the names the apply block uses.
            val parsed = parseBackup(text, json, System.currentTimeMillis())
                ?: run { notify("Restore failed — not a Crema backup"); return@launch }
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
            customProfilesJson = customProfilesJson + newProfiles.map(::derivedBrewTemp)

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
            // Never widen sharing from a backup (review 05 P3-1): held fields keep
            // this device's value and the notice below says so.
            val heldSharing = restoredVisualizerPrefs?.let { incoming ->
                val guarded = guardRestoredPrefs(visualizer.backupPrefs(), incoming, json)
                visualizer.restorePrefs(guarded.apply)
                guarded.held
            }.orEmpty()
            // Methods before recipes, so a restored recipe's `custom:` method resolves.
            val newMethods = applyRestoredCustomMethods(parsed.customMethods, mode == MainViewModel.RestoreMode.WIPE)
            val newRecipes = applyRestoredRecipes(parsed.recipes, parsed.recipeMeta, mode == MainViewModel.RestoreMode.WIPE)

            notify(
                "Restored ${newProfiles.size} profile(s) · ${newBeans.size} bean(s) · " +
                    "${newRoasters.size} roaster(s) · ${newShots.size} shot(s)" +
                    (if (newRecipes > 0) " · $newRecipes recipe(s)" else "") +
                    (if (newMethods > 0) " · $newMethods method(s)" else "") +
                    (heldSharingNotice(heldSharing)?.let { ". $it" } ?: ""),
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

    /** Clear the bean library, roaster directory, shot history, custom
     *  profiles, pins / hidden built-ins and recipes + custom methods in
     *  memory and in the UI; built-ins remain and the active profile reseeds
     *  via [refreshProfiles]. The disk half is [eraseStoredData]. The VM
     *  composes both with its settings + maintenance resets. */
    fun eraseAll() {
        customProfilesJson = emptyList()
        pinnedIds = emptySet()
        hiddenIds = emptySet()
        // Brew recipes + custom methods: an empty wipe-restore clears them.
        applyRestoredCustomMethods(emptyList(), true)
        applyRestoredRecipes(emptyList(), null, true)
        updateUi { it.copy(
            beans = emptyList(),
            roasters = emptyList(),
            activeBeanId = null,
            history = emptyList(),
            activeProfileId = null,
        ) }
        refreshProfiles()
    }

    /**
     * The disk half of [eraseAll]: delete each library store's file and every
     * copy of it (`.bak`, `.tmp`, `.corrupt-*`) rather than saving an empty
     * value, which would keep the full old file as `.bak` (final review 05
     * N-1), plus the bag photos and the last-shot diagnostic (review 05 P2-1).
     */
    suspend fun eraseStoredData() {
        library.erase()
        historyStore.erase()
        customProfileStore.erase()
        pinnedProfileStore.erase()
        hiddenProfileStore.erase()
        withContext(Dispatchers.IO) {
            BeanImageStore.deleteAll(app)
            coffee.crema.diag.ShotDiag.clear(app)
        }
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
                            // The whole pulled series (F10).
                            samples = samples,
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

    /**
     * Fill a pulled shot's blank barista from Visualizer (final review 06 #1).
     * A barista already set here is kept. Persisted.
     */
    fun fillShotBarista(localId: String, barista: String) {
        var changed = false
        updateUi { st ->
            st.copy(
                history = st.history.map {
                    if (it.id == localId && it.barista.isNullOrBlank()) {
                        changed = true
                        it.copy(barista = barista)
                    } else {
                        it
                    }
                },
            )
        }
        if (!changed) return
        val snapshot = uiState().history
        scope.launch { historyStore.save(snapshot) }
    }

    /** Stamp a successful Decent shot-history upload onto the local shot (#84). Persisted. */
    fun markShotDecentUploaded(localId: String, decentId: String, machine: ShotMachine? = null) {
        updateUi { st ->
            st.copy(
                history = st.history.map { shot ->
                    when {
                        shot.id != localId -> shot
                        // Brew Log rows (issue #10) never carry a machine stamp.
                        shot.isBrewLog -> shot
                        // The upload used the connected DE1 (DecentSync passes it only
                        // when the shot had no usable serial — none, or an unknown `0`):
                        // stamp its identity too, so the share link names the serial the
                        // server filed it under.
                        machine != null -> shot.copy(
                            decentId = decentId,
                            machineSerial = machine.serialNumber,
                            machineFirmware = machine.firmwareVersion,
                            machineModel = machine.model,
                        )
                        else -> shot.copy(decentId = decentId)
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

    /** [persistLibrary], awaited: the remote → standalone commit saves the
     *  adopted bean before the role switch starts (spec §7.5, I3). */
    suspend fun persistLibraryNow() {
        val s = uiState()
        library.save(BeanLibrary(s.beans, s.roasters, s.activeBeanId))
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
     * Import a bean library file (a system file-picker Uri). Reads the file off
     * the main thread, then:
     *
     * - a Crema `crema/v1` JSONL export (Android's or web's — bare, or the
     *   `crema.jsonl` inside web's `.crema.zip`; its photos are not imported) goes
     *   to the core's [importCremaJsonl];
     * - otherwise it's a Beanconqueror export: a single JSON, or a `.zip` whose
     *   chunked BEANS/BREWS files are merged back into the main JSON
     *   ([mergeBcZip], the inverse of BC's chunked writer), for the core's
     *   [importBeanconquerorJson].
     *
     * The plan merges into the library via [applyBeanImportPlan] — beans,
     * roasters, AND (Beanconqueror) the espresso brews as history rows (metadata
     * only, no telemetry; web parity).
     */
    fun importBeanconquerorUri(uri: Uri) {
        scope.launch {
            lastTooLarge = null
            val merged = withContext(Dispatchers.IO) {
                capped {
                    val bytes = app.contentResolver.openInputStream(uri)
                        ?.use { it.readBytesCapped(ImportLimits.MAX_FILE_BYTES) } ?: return@capped null
                    // PK\x03\x04 magic → a zip archive; else treat as raw text.
                    if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                        cremaJsonlFromZip(bytes) ?: mergeBcZip(bytes)
                    } else {
                        String(bytes, Charsets.UTF_8)
                    }
                }
            }
            if (merged.isNullOrBlank()) {
                notify(readFailure("Import failed — couldn't read that file"))
                return@launch
            }
            if (isCremaLibraryJsonl(merged)) {
                val planJson = runCatching { importCremaJsonl(merged) }.getOrElse {
                    appendLog("Crema import failed: ${it.message}")
                    notify("Import failed — not a Crema export")
                    return@launch
                }
                applyBeanImportPlan(planJson)
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

    /** The `crema.jsonl` inside a web `.crema.zip` bean export, or null when the zip has none. */
    private fun cremaJsonlFromZip(bytes: ByteArray): String? {
        var jsonl: String? = null
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
            readZipCapped(zin, accept = { it.lowercase().endsWith("crema.jsonl") }) { _, entryBytes ->
                if (jsonl == null) jsonl = entryBytes.toString(Charsets.UTF_8)
            }
        }
        return jsonl
    }

    /**
     * Merge a Beanconqueror `.zip` (a main `Beanconqueror.json` plus chunked
     * `Beanconqueror_{Beans,Brews}_N.json` overflow files) into one JSON object
     * the core can parse — the inverse of BC's chunked writer. Mirrors the web
     * `mergeBcArchive`. Returns null if no main file is present.
     */
    private fun mergeBcZip(bytes: ByteArray): String? {
        val entries = HashMap<String, String>()
        // Only the JSON members, within the size caps (review 05 P3-2).
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
            readZipCapped(zin, accept = { it.lowercase().endsWith(".json") }) { name, entryBytes ->
                entries[name] = entryBytes.toString(Charsets.UTF_8)
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
     * Merge a core import-plan JSON's beans + roasters into the library with the
     * core's import rule ([mergeBeanImport], the one web uses): new rows are
     * added, newer same-id rows replace their library ones. Persisted.
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
        val merge = mergeBeanImport(wireBeans, wireRoasters, s.beans, s.roasters)
        val newBeans = merge.newBeans
        val newRoasters = merge.newRoasters
        val beanIdRemap: Map<String, String> = merge.beanIdRemap

        // Shots — the core has already mapped BC brews (espresso-only,
        // metadata, no telemetry); land them as history rows, web parity.
        // Re-import dedupe: a BC brew's unix-second timestamp is stable
        // across exports, so (completedAt, bean name) identifies a row —
        // BC ids aren't carried on the shot, and our own ids are minted.
        val importedShots = buildImportedShots(plan, beanIdRemap)

        if (newBeans.isEmpty() && newRoasters.isEmpty() && merge.updatedBeans.isEmpty() &&
            merge.updatedRoasters.isEmpty() && importedShots.isEmpty()
        ) {
            notify(if (merge.skipped > 0u) "Already imported — nothing new to add" else "No beans found in that file")
            return
        }
        // Apply onto the CURRENT state (not the snapshot `s`) so a concurrent
        // high-rate telemetry / scale update isn't clobbered (issue 11).
        updateUi { cur ->
            cur.copy(
                beans = cur.beans.replacingById(merge.updatedBeans) { it.id } + newBeans,
                roasters = cur.roasters.replacingById(merge.updatedRoasters) { it.id } + newRoasters,
                activeBeanId = cur.activeBeanId ?: newBeans.firstOrNull()?.id,
                history = (importedShots + cur.history)
                    .sortedByDescending { it.completedAtMs }
                    .take(HistoryStore.MAX_SHOTS),
            )
        }
        persistLibrary()
        if (importedShots.isNotEmpty()) {
            val snapshot = uiState().history
            scope.launch { historyStore.save(snapshot) }
        }
        val bn = newBeans.size; val rn = newRoasters.size; val sn = importedShots.size; val un = merge.updatedBeans.size
        updateUi { it.copy(
            status = buildString {
                append("Imported $bn bean${if (bn == 1) "" else "s"} · $rn roaster${if (rn == 1) "" else "s"}")
                if (un > 0) append(" · updated $un bean${if (un == 1) "" else "s"}")
                if (sn > 0) append(" · $sn shot${if (sn == 1) "" else "s"}")
            },
        ) }
    }

    /** This list with each row that shares an id with one of [rows] swapped for it, order kept. */
    private fun <T> List<T>.replacingById(rows: List<T>, id: (T) -> String): List<T> {
        if (rows.isEmpty()) return this
        val byId = rows.associateBy(id)
        return map { byId[id(it)] ?: it }
    }

    /**
     * Decode the import plan's `shots` (core `ImportedShot`: the sans-IO
     * `StoredShot` + resolved bean/roaster/grinder strings) into this shell's
     * flattened [StoredShot] rows, dropping any already in history (same
     * completed-at ms + same bean name = the same BC brew on a re-import).
     * Telemetry stays empty — BC's `flow_profile` sidecar parse is deferred,
     * exactly as on web.
     */
    private fun buildImportedShots(plan: JsonObject, beanIdRemap: Map<String, String>): List<StoredShot> {
        val entries = plan["shots"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        val existingKeys = uiState().history
            .map { it.completedAtMs to it.bean?.name?.trim()?.lowercase().orEmpty() }
            .toHashSet()
        val out = ArrayList<StoredShot>(entries.size)
        for (el in entries) {
            val imp = runCatching { el.jsonObject }.getOrNull() ?: continue
            val stored = imp["storedShot"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: continue
            val meta = stored["metadata"]?.let { runCatching { it.jsonObject }.getOrNull() }
            fun JsonObject.num(key: String): Double? =
                runCatching { (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull() }.getOrNull()
            fun JsonObject.str(key: String): String? =
                runCatching { (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()
            val completedAt = stored.num("completedAt")?.toLong() ?: continue
            val beanName = imp.str("beanName")?.trim().orEmpty()
            if (!existingKeys.add(completedAt to beanName.lowercase())) continue // re-import dupe
            // Core `record.duration` is a serde `Duration` — `{secs, nanos}`.
            val durationMs = stored["record"]?.let { runCatching { it.jsonObject }.getOrNull() }
                ?.get("duration")?.let { d ->
                    runCatching { d.jsonObject }.getOrNull()?.let { obj ->
                        ((obj.num("secs") ?: 0.0) * 1000.0 + (obj.num("nanos") ?: 0.0) / 1_000_000.0).toLong()
                    } ?: (d as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
                } ?: 0L
            val bean = if (beanName.isNotEmpty()) {
                ShotBean(
                    beanId = imp.str("beanId")?.let { beanIdRemap[it] ?: it },
                    name = beanName,
                    roasterName = imp.str("roasterName"),
                    roastedOn = imp.str("roastedOn"),
                    roastLevel = imp.num("roastLevel")?.toInt()?.toUByte(),
                )
            } else {
                null
            }
            out.add(
                StoredShot(
                    id = "shot:bc:" + java.util.UUID.randomUUID(),
                    completedAtMs = completedAt,
                    durationMs = durationMs,
                    yieldG = meta?.num("yieldOut")?.toFloat(),
                    doseG = meta?.num("dose")?.toFloat(),
                    grindSetting = meta?.str("grinderSetting")?.replace(',', '.')?.toFloatOrNull(),
                    profileName = null,
                    bean = bean,
                    rating = meta?.num("rating")?.toInt()?.takeIf { it > 0 },
                    notes = meta?.str("notes")?.takeIf { it.isNotBlank() },
                    // Brew Log rows (issue #10): the core now imports BC's
                    // non-espresso brews with a method + water-in instead of
                    // skipping them.
                    brewMethod = stored.str("brewMethod"),
                    waterG = meta?.num("waterG")?.toFloat(),
                    brewTempC = stored.num("brewTempTarget")?.toFloat(),
                ),
            )
        }
        return out
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
    fun updateBean(
        id: String,
        roasterName: String,
        roasterPick: coffee.crema.core.CataloguePickRoaster? = null,
        transform: (Bean) -> Bean,
    ) {
        val s = uiState()
        // Resolve from the library, or from a new-bean draft (the "Add bean" →
        // full-editor Save path — the bean isn't in [beans] yet).
        val existing = s.beans.firstOrNull { it.id == id } ?: s.draftBean?.takeIf { it.id == id } ?: return
        val isNew = s.beans.none { it.id == id }
        val now = System.currentTimeMillis()
        // A catalogue pick's roaster (the editor only passes it while the
        // roaster input still names it): write the updated existing row, or
        // create the seeded one, and file the bag under it. Otherwise resolve
        // the typed name (find-or-create) as before.
        val (roasterId, roasters) = if (roasterPick != null) {
            val row = coffee.crema.beans.pickedRoasterRow(roasterPick) { name -> newRoaster(name, now) }
            val current = s.roasters.firstOrNull { it.id == row.id }
            if (current == null) {
                // A seed is created; a matched row that vanished since → by name.
                if (roasterPick.isNew) row.id to (s.roasters + row) else resolveRoaster(roasterName, s.roasters, now)
            } else {
                // Only the catalogue fields; anything else edited since stays.
                val merged = current.copy(
                    name = row.name, website = row.website, country = row.country,
                    catalogueRoasterId = row.catalogueRoasterId, updatedAt = now,
                )
                row.id to s.roasters.map { if (it.id == row.id) merged else it }
            }
        } else {
            resolveRoaster(roasterName, s.roasters, now)
        }
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
            if (bytes == null || bytes.isEmpty()) { notify(readFailure("Couldn't read that image")); return@launch }
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

    /**
     * Remove a bean bag; reselect the first remaining if it was active.
     * [alsoOnVisualizer] then deletes its Visualizer copy (best-effort; the id
     * is captured before the local row goes). Persisted.
     */
    fun deleteBean(id: String, alsoOnVisualizer: Boolean = false) {
        val doomed = uiState().beans.firstOrNull { it.id == id }
        val remaining = uiState().beans.filterNot { it.id == id }
        val wasActive = uiState().activeBeanId == id
        updateUi { it.copy(
            beans = remaining,
            activeBeanId = if (wasActive) remaining.firstOrNull()?.id else it.activeBeanId,
        ) }
        // Drop the bag's photo too, so a deleted bean leaves no orphan blob.
        scope.launch(Dispatchers.IO) { BeanImageStore.delete(app, id) }
        persistLibrary()
        if (alsoOnVisualizer) doomed?.visualizerId?.let { visualizer.deleteRemote(listOf(it), label = doomed.name) }
    }

    /**
     * Merge a finished Visualizer bean sync back into the library
     * ([coffee.crema.visualizer.mergeSyncedRows]): touched rows replace their
     * current versions, pulled additions are prepended, rows deleted mid-run
     * stay deleted. Persisted.
     */
    fun applyBeanSync(result: coffee.crema.visualizer.BeanSyncResult, snapshotBeanIds: Set<String>, snapshotRoasterIds: Set<String>) {
        if (result.beans.isEmpty() && result.roasters.isEmpty()) return
        updateUi { st ->
            st.copy(
                beans = coffee.crema.visualizer.mergeSyncedRows(st.beans, result.beans, snapshotBeanIds) { it.id },
                roasters = coffee.crema.visualizer.mergeSyncedRows(st.roasters, result.roasters, snapshotRoasterIds) { it.id },
            )
        }
        persistLibrary()
    }

    /**
     * Duplicate a bag into a fresh row via the core copy
     * ([coffee.crema.beans.duplicateBean]: new id, "name (copy)", no remote
     * links). Persisted.
     */
    fun duplicateBean(id: String) {
        val base = uiState().beans.firstOrNull { it.id == id } ?: return
        val copy = coffee.crema.beans.duplicateBean(base, System.currentTimeMillis())
        updateUi { it.copy(beans = it.beans + copy) }
        persistLibrary()
    }

    /**
     * Freeze a bag through the core rule ([coffee.crema.beans.setBeanFrozen]):
     * stamps `frozenOn` today, or carries an earlier freeze. Persisted.
     */
    fun freezeBean(id: String) = mutateBean(id) { coffee.crema.beans.setBeanFrozen(it, true, System.currentTimeMillis()) }

    /**
     * Defrost a bag through the core rule ([coffee.crema.beans.setBeanFrozen]):
     * records `defrostedOn` and KEEPS `frozenOn`, so the frozen span stays off
     * the off-roast count ("currently frozen" is `frozenOn != null && defrostedOn == null`).
     */
    fun defrostBean(id: String) = mutateBean(id) { coffee.crema.beans.setBeanFrozen(it, false, System.currentTimeMillis()) }

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
    fun addRoaster(name: String, website: String?, city: String?, country: String?, notes: String, catalogueRoasterId: String? = null) {
        if (name.isBlank()) return
        val now = System.currentTimeMillis()
        val r = newRoaster(name.trim(), now).copy(
            website = website?.takeIf { it.isNotBlank() },
            city = city?.takeIf { it.isNotBlank() },
            country = country?.takeIf { it.isNotBlank() },
            notes = notes,
            catalogueRoasterId = catalogueRoasterId?.takeIf { it.isNotBlank() },
        )
        updateUi { it.copy(roasters = it.roasters + r) }
        persistLibrary()
    }

    /**
     * Update a roaster's editable fields. Persisted. [catalogueRoasterId] is the
     * Visualizer catalogue link the editor holds (set by a catalogue pick,
     * cleared by unlink); the duplicate-of pointer is never touched.
     */
    fun updateRoaster(
        id: String,
        name: String,
        website: String?,
        city: String?,
        country: String?,
        notes: String,
        catalogueRoasterId: String?,
    ) {
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
                    catalogueRoasterId = catalogueRoasterId?.takeIf { c -> c.isNotBlank() },
                    updatedAt = now,
                ) else it
            },
        ) }
        persistLibrary()
    }

    /**
     * Delete a roaster (web `RoasterDeleteSplit`): [cascade] = false detaches its
     * bags (roaster cleared), true deletes them too. The core's
     * `plan_roaster_delete` decides which bags go and the Visualizer ids;
     * [alsoOnVisualizer] then deletes those remote copies (bags first, then the
     * roaster). Persisted.
     */
    fun deleteRoaster(id: String, alsoOnVisualizer: Boolean = false, cascade: Boolean = false) {
        val s = uiState()
        val plan = runCatching {
            coffee.crema.beans.planRoasterDelete(json, s.roasters, s.beans, id, cascade)
        }.getOrNull() ?: return
        val name = s.roasters.firstOrNull { it.id == id }?.name.orEmpty()
        val after = coffee.crema.beans.applyRoasterDelete(
            BeanLibrary(s.beans, s.roasters, s.activeBeanId), plan, System.currentTimeMillis(),
        )
        val activeGone = s.activeBeanId != null && after.activeBeanId == null
        updateUi { st ->
            st.copy(
                roasters = after.roasters,
                beans = after.beans,
                activeBeanId = if (activeGone) after.beans.firstOrNull()?.id else st.activeBeanId,
            )
        }
        // Cascaded bags leave no orphan photos.
        if (plan.deletedBeanIds.isNotEmpty()) {
            scope.launch(Dispatchers.IO) { plan.deletedBeanIds.forEach { BeanImageStore.delete(app, it) } }
        }
        persistLibrary()
        if (alsoOnVisualizer) visualizer.deleteRemote(plan.remoteBeanIds, plan.remoteRoasterId, label = name)
    }

    /**
     * Merge roaster [dupeId] into [canonicalId] (web Roasters tab): the core's
     * `plan_roaster_merge` lists the dupe's bags, which move to the canonical
     * roaster; the dupe is kept but tagged (`canonicalRoasterId`), so it hides
     * from the directory and can be un-merged. The moved bags are stamped, so
     * the next Visualizer sync PATCHes their new roaster. Persisted.
     */
    fun mergeRoaster(canonicalId: String, dupeId: String) {
        val s = uiState()
        val plan = runCatching {
            coffee.crema.beans.planRoasterMerge(json, s.roasters, s.beans, canonicalId, dupeId)
        }.getOrNull() ?: return
        val moving = plan.beanIds.toSet()
        val now = System.currentTimeMillis()
        updateUi { st ->
            st.copy(
                beans = st.beans.map { if (it.id in moving) it.copy(roasterId = plan.canonicalId, updatedAt = now) else it },
                roasters = st.roasters.map { if (it.id == plan.dupeId) it.copy(canonicalRoasterId = plan.canonicalId, updatedAt = now) else it },
            )
        }
        persistLibrary()
        val names = s.roasters.associate { it.id to it.name }
        notify("Merged “${names[dupeId]}” into “${names[canonicalId]}” — ${moving.size} bag(s) moved")
    }

    /** Un-merge a tagged duplicate: clear its duplicate-of pointer (its bags stay where they are). Persisted. */
    fun unmergeRoaster(id: String) {
        val now = System.currentTimeMillis()
        updateUi { st ->
            st.copy(roasters = st.roasters.map { if (it.id == id) it.copy(canonicalRoasterId = null, updatedAt = now) else it })
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
        // The selections on screen: on a remote, the hub's (02 F2 — a remote
        // still records the shot it watched).
        val s = effectiveState()
        val profile = s.activeProfile()
        // The core classified this completion (review #40 —
        // `de1_domain::shot_disposition`, same rule on web). SkipCleaning:
        // machine maintenance runs in the DE1's Espresso state, so a
        // cleaning/backflush profile would be recorded as a shot — skip the
        // capture entirely (no history row, no Visualizer, no bean debit;
        // nothing was ground). Decenza #1325 (maincontroller.cpp:1841-1853).
        // Descale/calibrate run in their own machine states, never here.
        if (disposition == ShotDisposition.SkipCleaning) {
            // Take the core's pending shot anyway, so it never outlives its
            // completion (web takes it whatever the disposition too).
            runCatching { bridge.takeStoredShot(json.encodeToString(ShotCaptureContext.serializer(), ShotCaptureContext(completedAt = now))) }
            appendLog("Cleaning run finished — not recorded as a shot")
            return
        }
        val bean = s.beans.firstOrNull { it.id == s.activeBeanId }
        val roasterName = bean?.roasterId?.let { rid -> s.roasters.firstOrNull { it.id == rid }?.name }
        // The core assembles the shot (F10, the rule set web uses too): its own
        // record (the full series with frames and scale overlays), the profile
        // the DE1 held and its dose / yield / brew temp / pre-infusion, the
        // grinder (bean, else Settings) frozen now, the stop reason and
        // switches. The shell hands in only what the core can't know.
        val ctx = ShotCaptureContext(
            completedAt = now,
            id = shotId,
            bean = bean?.let { b ->
                ShotBean(
                    beanId = b.id,
                    name = b.name,
                    roasterName = roasterName,
                    roastedOn = b.roastedOn,
                    roastLevel = b.roastLevel,
                    tags = b.tags,
                    grinderSetting = b.grinderSetting?.takeIf { it.isNotBlank() },
                    grinder = b.grinder?.takeIf { it.isNotBlank() },
                    notes = b.notes?.takeIf { it.isNotBlank() },
                )
            },
            settingsGrinder = s.grinderModel.trim().ifEmpty { null },
            // The QC grind dial actually used (issue 15) — null until the user sets one.
            grindDial = s.qcGrind,
            // The Settings default barista (dye2 audit #1), editable later.
            barista = s.baristaName.trim().ifEmpty { null },
            tags = bean?.tags?.takeIf { it.isNotEmpty() },
            // The DE1 this ran on — frozen here so a later Decent upload (#84)
            // carries the serial the server verifies.
            machine = liveMachine(),
            // The profile on screen, the core's last fallback name: a remote's
            // mirror core never uploads, so it knows no profile title (on a
            // remote the screen shows the hub's selection).
            profileName = profile?.name,
        )
        val assembled = runCatching {
            bridge.takeStoredShot(json.encodeToString(ShotCaptureContext.serializer(), ctx))
        }.onFailure { appendLog("Shot assembly failed: ${it.message}") }.getOrNull()
        val core = assembled?.let { json.parseToJsonElement(it) as? JsonObject }
        // No telemetry at all (or the core refused): nothing to record.
        val shot = core?.let { storedShotFromBackupJson(it, json) }?.copy(
            // The core's running peaks from the event, as before.
            peakPressure = peakPressure,
            peakTemp = peakTemp,
            peakWeightG = peakWeightG,
        )
        if (shot == null) {
            appendLog("Shot had no telemetry — not recorded")
            return
        }
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
            // Auto-upload to every armed destination (Visualizer, Decent), one
            // notice for the batch, with the core's full series.
            autoUploadShot(shot, shot.samples)
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
        // The dose the core recorded — the Quick Controls dial when the DE1 ran
        // the dialled profile (review §2.3-6) — else the profile's.
        val dose = shot.doseG ?: profile?.dose
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

    /** The Log-brew form's payload (issue #10). */
    data class ManualBrewInput(
        val method: String,
        val completedAtMs: Long,
        val beanId: String?,
        val doseG: Float?,
        val waterG: Float?,
        val yieldG: Float?,
        val grindSetting: Float?,
        val brewTempC: Float?,
        val durationMs: Long?,
        val rating: Int?,
        val notes: String?,
        val nextPlan: String?,
        val recipeName: String? = null,
        val brewSeries: coffee.crema.core.BrewSeries? = null,
        /** The custom method's label, snapshotted on the row (`custom:` methods only). */
        val methodLabel: String? = null,
    )

    /**
     * Record a Brew Log entry (issue #10) — a manually logged brew, or a
     * finished guided session. The one path that legitimately persists a
     * telemetry-less row. Debits the bag exactly like a live shot
     * (`debitRemaining` → bag-empty notice) and never touches Visualizer
     * (logged brews are local-only, like `nextPlan`).
     */
    fun addManualBrew(input: ManualBrewInput): StoredShot {
        val s = uiState()
        val bean = input.beanId?.let { id -> s.beans.firstOrNull { it.id == id } }
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
        val shot = StoredShot(
            id = coffee.crema.core.newShotId(),
            completedAtMs = input.completedAtMs,
            durationMs = input.durationMs ?: 0L,
            yieldG = input.yieldG,
            doseG = input.doseG,
            grindSetting = input.grindSetting,
            profileName = null,
            bean = beanSnapshot,
            rating = input.rating?.coerceIn(1, 5),
            notes = input.notes?.ifBlank { null },
            nextPlan = input.nextPlan?.ifBlank { null },
            brewMethod = input.method,
            brewMethodLabel = input.methodLabel?.ifBlank { null },
            recipeName = input.recipeName?.ifBlank { null },
            waterG = input.waterG,
            brewTempC = input.brewTempC,
            brewSeries = input.brewSeries?.let { coffee.crema.history.downsampleBrewSeries(it) },
            barista = uiState().baristaName.trim().ifEmpty { null },
            // No machine stamp (issue #10): a logged brew involves no DE1, so
            // machineSerial / Firmware / Model stay null — the #84 record-time
            // stamp belongs to the live-shot path only.
        )
        val next = (listOf(shot) + s.history).take(HistoryStore.MAX_SHOTS)
        updateUi { it.copy(history = next) }
        scope.launch { historyStore.save(next) }
        // Burn the dose down from the bag — the whole point of the feature.
        val dose = input.doseG
        if (bean != null && dose != null && dose > 0f) {
            val debited = debitRemaining(bean.remaining ?: 0f, dose)
            if (debited != null) {
                mutateBean(bean.id) { it.copy(remaining = debited) }
                if (debited == 0f) {
                    val rateHint = if ((bean.rating?.toInt() ?: 0) == 0) " — rate it in Beans" else ""
                    notify("That was the last of ${bean.name}; bag empty$rateHint")
                }
            }
        }
        notify("Brew logged")
        return shot
    }

    /**
     * Re-tag a brew row with a (newly saved) custom method — the "Save 'X' as
     * a method?" offer. Snapshots the label beside the id. Persisted.
     */
    fun retagBrewMethod(id: String, method: String, label: String) {
        val next = uiState().history.map { if (it.id == id) it.copy(brewMethod = method, brewMethodLabel = label) else it }
        updateUi { it.copy(history = next) }
        scope.launch { historyStore.save(next) }
    }

    /**
     * Edit the user-entered facts of a MANUAL brew row (dose / water /
     * yield / temp / time). Measured (machine / guided) rows are left
     * alone. A dose change re-settles the bag through the same
     * credit/debit pair bean re-attribution uses.
     */
    fun updateManualBrew(
        id: String,
        doseG: Float? = null,
        waterG: Float? = null,
        yieldG: Float? = null,
        brewTempC: Float? = null,
        durationMs: Long? = null,
    ) {
        val s = uiState()
        val shot = s.history.firstOrNull { it.id == id } ?: return
        if (!shot.isManualLog) return
        // Re-settle inventory before the row mutates, using the OLD dose —
        // the core's resettleRemaining (credit old, debit new; null = nothing
        // to persist, including an unchanged dose).
        if (doseG != null) {
            shot.bean?.beanId?.let { beanId ->
                s.beans.firstOrNull { it.id == beanId }?.let { bean ->
                    val rem = bean.remaining ?: return@let
                    resettleRemaining(rem, bean.bagSize ?: 0f, shot.doseG ?: 0f, doseG)?.let { next ->
                        mutateBean(bean.id) { it.copy(remaining = next) }
                    }
                }
            }
        }
        updateUi { st ->
            st.copy(
                history = st.history.map {
                    if (it.id != id) it
                    else it.copy(
                        doseG = doseG ?: it.doseG,
                        waterG = waterG ?: it.waterG,
                        yieldG = yieldG ?: it.yieldG,
                        brewTempC = brewTempC ?: it.brewTempC,
                        durationMs = durationMs ?: it.durationMs,
                    )
                },
            )
        }
        val snapshot = uiState().history
        scope.launch { historyStore.save(snapshot) }
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
        onShotAnnotationsEdited(id)
    }

    /**
     * Set a shot's forward-looking "next time" plan. Persisted. Local-only
     * workflow state — deliberately NOT mirrored to Visualizer (no
     * [schedulePatchEdited]): the exporter never carries it, so a PATCH
     * would be a no-op round trip.
     */
    fun setShotNextPlan(id: String, nextPlan: String) {
        updateUi { st ->
            st.copy(
                history = st.history.map {
                    if (it.id == id) it.copy(nextPlan = nextPlan.ifBlank { null }) else it
                },
            )
        }
        val snapshot = uiState().history
        scope.launch { historyStore.save(snapshot) }
    }

    /**
     * Set (or with null / blank, clear) who made (`"barista"`) or drank
     * (`"drinker"`) a shot. Persisted. The barista mirrors to Visualizer
     * (its `barista` field); both re-send the Decent copy, whose workflow
     * context carries them.
     */
    fun setShotPerson(id: String, field: String, name: String?) {
        val value = name?.trim()?.takeIf { it.isNotEmpty() }
        updateUi { st ->
            st.copy(
                history = st.history.map {
                    when {
                        it.id != id -> it
                        field == "barista" -> it.copy(barista = value)
                        field == "drinker" -> it.copy(drinker = value)
                        else -> it
                    }
                },
            )
        }
        val snapshot = uiState().history
        scope.launch { historyStore.save(snapshot) }
        if (field == "barista") {
            // Only a barista edit sends the barista (final review 06 #1).
            visualizer.markBaristaEdited(id)
            schedulePatchEdited(id)
        }
        onShotAnnotationsEdited(id)
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
        onShotAnnotationsEdited(id)
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
        val shot = s.history.firstOrNull { it.id == id } ?: return
        val oldBeanId = shot.bean?.beanId
        if (oldBeanId == beanId) return // same attribution — nothing to do
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
        // Move the bag debit with the attribution: the grounds physically came
        // from the bag the user now says they did, so the wrongly-debited old
        // bag gets the dose BACK and the new bag loses it (capture's
        // debitRemaining rule). Best-effort by design: the credit is capped at
        // bagSize (the bag may have been refilled since), null-remaining bags
        // are untracked and left alone, and a capture-time debit that clamped
        // at 0 can't be reconstructed. Uses the dose the shot recorded.
        val dose = shot.doseG
        if (dose != null && dose > 0f) {
            s.beans.firstOrNull { it.id == oldBeanId }?.let { old ->
                old.remaining?.let { rem ->
                    // Core rule (the inverse of debitRemaining): cap at
                    // bagSize, null = nothing changed, don't persist.
                    creditRemaining(rem, dose, old.bagSize ?: 0f)?.let { credited ->
                        mutateBean(old.id) { it.copy(remaining = credited) }
                    }
                }
            }
            if (bean != null) {
                val debited = debitRemaining(bean.remaining ?: 0f, dose)
                if (debited != null) {
                    mutateBean(bean.id) { it.copy(remaining = debited) }
                    if (debited == 0f) notify("That was the last of ${bean.name}; bag empty")
                }
            }
        }
        val snapshot = uiState().history
        scope.launch { historyStore.save(snapshot) }
        schedulePatchEdited(id)
        onShotAnnotationsEdited(id)
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
        autoUploadShot(shot, null)
    }

    /** The discard snackbar timed out / was dismissed — drop the held shot. */
    fun consumeDiscardToast() {
        discardedShot = null
        updateUi { it.copy(discardToastMessage = null) }
    }
}

/** The message after a profile import: "Imported 2 profiles; 3 already in your
 *  library", with the first error when some didn't import (web words it the
 *  same, F12b). */
internal fun profileImportSummary(imported: Int, skipped: Int, errors: List<String>): String {
    if (imported == 0 && skipped == 0) {
        return errors.firstOrNull()?.let { "Couldn’t import profile — $it" } ?: "No profiles imported"
    }
    val head = if (errors.isEmpty()) "Imported $imported profile${if (imported == 1) "" else "s"}"
    else "Imported $imported of ${imported + errors.size}"
    val already = if (skipped > 0) "; $skipped already in your library" else ""
    val tail = errors.firstOrNull()?.let { " — $it" }.orEmpty()
    return head + already + tail
}
