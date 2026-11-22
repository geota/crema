package coffee.crema.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import coffee.crema.beans.BagEmptyPrompt
import coffee.crema.beans.BeanImageStore
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.LibraryStore
import coffee.crema.beans.beanLibraryJsonl
import coffee.crema.beans.isCremaLibraryJsonl
import coffee.crema.beans.mergeBeanImport
import coffee.crema.beans.newBean
import coffee.crema.beans.newRoaster
import coffee.crema.core.BackupCounts
import java.io.File
import coffee.crema.drive.BackupZip
import coffee.crema.core.Bean
import coffee.crema.core.BrewTempRange
import coffee.crema.core.brewTempRange
import coffee.crema.core.CommonSettings
import coffee.crema.core.CremaBridge
import coffee.crema.core.MaintenanceState
import coffee.crema.core.Roaster
import coffee.crema.core.ShotBean
import coffee.crema.core.ShotPatch
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
import coffee.crema.core.creditShotDebit
import coffee.crema.core.debitRemaining
import coffee.crema.core.debitTaken
import coffee.crema.core.resettleRemaining
import coffee.crema.core.resettledBagDebit
import coffee.crema.core.ShotCaptureContext
import coffee.crema.core.ShotSelections
import coffee.crema.core.ShotEffects
import coffee.crema.core.ShotEffectsInput
import coffee.crema.core.shotEffectsJson
import coffee.crema.core.shotBeanSnapshotJson
import coffee.crema.core.BrewLogGrind
import coffee.crema.core.BrewLogGrindInput
import coffee.crema.core.brewLogGrindJson
import coffee.crema.core.exportProfileFile
import coffee.crema.core.importProfileFile
import coffee.crema.core.exportBeanconquerorMainJson
import coffee.crema.core.importBeanconquerorJson
import coffee.crema.core.importCremaJsonl
import coffee.crema.core.exportV2JsonShot
import coffee.crema.core.newShotId
import coffee.crema.history.HistoryStore
import coffee.crema.machine.CommandOutcome
import coffee.crema.beans.savedGrind
import coffee.crema.beans.activeGrinder
import coffee.crema.history.HistoryHealth
import coffee.crema.history.ShotRow
import coffee.crema.history.StoredShot
import coffee.crema.history.joined
import coffee.crema.history.withHeavy
import coffee.crema.history.coreShotJson
import coffee.crema.history.isBrewLog
import coffee.crema.history.brewDateError
import coffee.crema.history.HistoryMerge
import coffee.crema.history.historyCutNotice
import coffee.crema.history.historyDroppedNotice
import coffee.crema.history.historyWith
import coffee.crema.beans.applyBeanDraft
import coffee.crema.history.mergePulled
import coffee.crema.history.mergeHistory
import coffee.crema.history.isManualLog
import coffee.crema.history.newestFirst
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
import coffee.crema.visualizer.PulledParts
import coffee.crema.visualizer.VisualizerSync
import coffee.crema.visualizer.shotHistoryJsonl
import coffee.crema.visualizer.shotsFromCremaJsonl
import coffee.crema.visualizer.storedShotFromBackupJson
import coffee.crema.visualizer.wireShotJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
    private val autoUploadShot: (shot: ShotRow) -> Unit,
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
    /** Whether Google Drive is busy now: its own state, not the UI's mirror, which can lag (REVIEW-440 R1). */
    private val driveBusy: () -> Boolean = { uiState().drive.busy },
    /** Synchronously update the UI snapshot (the VM's `_ui.update`). Keeps the rows. */
    private val updateUi: ((MainUiState) -> MainUiState) -> Unit,
    /** Change the history or the bean library: [UiStore.edit]. */
    private val editRows: EditRows,
    /** Surface a user-facing message (the VM's snackbar channel). */
    private val notify: (String) -> Unit,
    /** Append to the session event log. */
    private val appendLog: (String) -> Unit,
    /** The current prefs snapshot (the backup's portable-settings source). */
    private val currentPrefs: () -> AppPrefs,
    /** Persist the learned SAW drip model (bridge + SawModelStore; VM). */
    private val persistSawModel: suspend () -> Unit,
    /** The VM's setActiveProfile — relay guard + persistPrefs + stop targets. */
    private val setActiveProfile: (String) -> Unit,
    /** The VM's resetBrewParams — relay-aware; clears the QC override. */
    private val resetBrewParams: () -> Unit,
    /** The VM's quickAdjustBrew: put a Quick-Controls override back (a Save's Undo). */
    private val restoreBrewParams: (BrewParams) -> Unit = {},
    /** The active profile was cleared (its profile deleted or hidden): persist
     *  the selection and push the stop targets and the desired profile, as a
     *  pick would. */
    private val onProfileCleared: () -> Unit = {},
    /** Persist this device's own selection (the prefs), with nothing else. */
    private val persistSelection: () -> Unit = {},
    /**
     * A stored shot's record-carried annotations (rating, notes, grind, bean)
     * were edited — the Decent destination queues its debounced
     * `replace=1` re-upload ([coffee.crema.decent.DecentSync.scheduleReplaceAfterEdit]).
     */
    private val onShotAnnotationsEdited: (shotId: String) -> Unit = {},
    /** Set Brew's grind dial (#488: a bag or grinder pick resets it, review F9):
     *  stored with the prefs and pushed to remotes; null = no dial. */
    private val setGrindDial: (String?) -> Unit = {},
    /** Push the hub's config snapshot (a grinder record edit changes what a remote shows). */
    private val pushConfig: () -> Unit = {},
) {

    // ── Persistence (controller-owned stores) ────────────────────────────────

    /** Bean-library persistence — a JSON file in filesDir. */
    private val library = LibraryStore(app, json)

    /** Shot-history persistence — a JSON file in filesDir. */
    private val historyStore = HistoryStore(app, json)

    init {
        // What the screens show of the whole history (#310 A3b): worked out off the
        // main thread whenever the store's rows change, the newest change winning.
        scope.launch {
            historyStore.rowsVersion.collectLatest { v ->
                val state = withContext(Dispatchers.Default) { HistoryState.of(historyStore.rows(), v, historyStore::row) }
                updateUi { it.copy(history = state) }
            }
        }
    }

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

    /** The profile the editor opened on (REVIEW-FINAL-2 N2): a Save merges what the
     *  user changed against it onto the stored row, so a pin or a Quick Save written
     *  meanwhile stays. Set when the editor opens, and to what a Save wrote. */
    private var editorBaseJson: String? = null

    /** The aborted shot held for the undo window (not yet in history), and
     *  what it does when the Undo keeps it. */
    private var discardedShot: Pair<StoredShot, ShotEffects>? = null

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
        refreshProfiles(seedEmpty = true)
    }

    /** The core's pre-infusion seconds for a full profile JSON (cards, brew
     *  header); null when the core can't read it. */
    private fun corePreinfusion(raw: String): Float? = runCatching { preinfusionSeconds(raw) }.getOrNull()

    /** Test seam: the window where a shot assembles, off the state it read. */
    @androidx.annotation.VisibleForTesting
    internal var assembling: () -> Unit = {}

    /** Test seam: a kept shot offered to the upload destinations. */
    @androidx.annotation.VisibleForTesting
    internal var offeredForUpload: (ShotRow) -> Unit = {}

    /**
     * An edit of the whole list (a merge, the legacy backend's cap) on the
     * history store's rows as they stand (#310 A3b): the rows it adds or
     * changes are in memory at once, and the rows it leaves out are let go
     * ([HistoryStore.drop]: gone on the legacy backend, kept on `shots.db`,
     * where only a named delete removes a row). Returns the list it made.
     * Not saved: the caller saves ([saveHistory]).
     */
    private fun editHistory(edit: (HistoryRows) -> HistoryRows): List<ShotRow> =
        historyStore.edit { rows -> edit(rowsForEdit(rows)) }

    /** The cap the view has now: none from P6a, but on the legacy backend. */
    private val viewCap: Int? get() = historyStore.viewCap

    /** [viewCap], for a Visualizer pull's plan. */
    val historyCap: Int? get() = viewCap

    private fun coreHealth(h: coffee.crema.persist.StoreHealth) = when (h) {
        coffee.crema.persist.StoreHealth.LOADING -> coffee.crema.core.LocalHealth.Loading
        coffee.crema.persist.StoreHealth.OK -> coffee.crema.core.LocalHealth.Ok
        coffee.crema.persist.StoreHealth.ABSENT -> coffee.crema.core.LocalHealth.Absent
        coffee.crema.persist.StoreHealth.DAMAGED -> coffee.crema.core.LocalHealth.Damaged
        coffee.crema.persist.StoreHealth.UNAVAILABLE -> coffee.crema.core.LocalHealth.Unavailable
    }

    private fun editBeans(edit: (BeanRows) -> BeanRows) = editRows({ it.copy(beans = edit(it.beans)) }, { it })

    private fun editRoasters(edit: (RoasterRows) -> RoasterRows) = editRows({ it.copy(roasters = edit(it.roasters)) }, { it })

    /** How many older rows the cap dropped for a row made here, from the edit's
     *  last run (an update can run its lambda again). */
    private inner class CapCut {
        var dropped = 0

        /** A save that drops a row says so (#310 P0). Pinned rows never go (#311). */
        fun say() {
            // From P3a a row past the cap stays in the store, hidden: nothing was dropped.
            if (!historyStore.usesSql) historyCutNotice(dropped, newKept = true)?.let(notify)
        }
    }

    /** These rows with [shot], made here, added by date and capped; [cut]
     *  counts what the cap dropped. */
    private fun HistoryRows.withMade(shot: ShotRow, cut: CapCut): HistoryRows {
        val next = historyWith(listOf(shot), this, keepId = shot.id, cap = viewCap)
        val kept = next.mapTo(HashSet()) { it.id }
        cut.dropped = count { it.id !in kept }
        return next
    }

    /**
     * [row], made here, into the history: on `shots.db` straight into the
     * store's memory, with no pass over the other rows (REVIEW-467 N4); on the
     * legacy backend in by date and capped ([withMade]), [cut] counting what
     * the cap dropped. Not saved: the caller saves.
     */
    private fun addMade(row: ShotRow, cut: CapCut) {
        if (viewCap == null) {
            cut.dropped = 0
            historyStore.put(listOf(row))
        } else {
            editHistory { rows -> rows.withMade(row, cut) }
        }
    }

    /** What to say of rows the view's cap let go: dropped on the legacy backend, kept (hidden) on `shots.db`. */
    private fun droppedNotice(dropped: Int): String = if (historyStore.usesSql) "" else historyDroppedNotice(dropped)

    /**
     * Write what the history store holds in memory and hasn't written
     * ([HistoryStore.flush]: upserts only, from P3a), then show what couldn't
     * be saved.
     */
    private suspend fun persistHistory(): Boolean {
        val ok = historyStore.flush()
        noteUnsaved()
        return ok
    }

    /**
     * Write [rows]' ids as one batch: a restore's merge step, past any cap.
     * Memory has held every restored row since the settle, so each is written
     * as memory holds it now ([HistoryStore.flush] of these ids, read under
     * the lock): an edit or an upload stamp made since isn't undone, and a row
     * deleted since stays gone (REVIEW-470 B1).
     */
    private suspend fun persistHistoryAll(rows: List<ShotRow>): Boolean {
        val ok = historyStore.flush(rows.map { it.id })
        noteUnsaved()
        return ok
    }

    private fun noteUnsaved() {
        val unsaved = historyUnsaved
        if (uiState().historyUnsaved != unsaved) updateUi { it.copy(historyUnsaved = unsaved) }
        checkStorage()
    }

    /**
     * Read the free space where the history lives and set the storage notices
     * (#310 P6a, SPEC §1): after each save, at load and on resume. A statfs,
     * cheap enough for every save. The getting-full notice, once dismissed,
     * comes back only after the space recovers and runs low again.
     */
    fun checkStorage() {
        val level = coffee.crema.history.StorageSpace.levelOf(historyStore.freeBytes())
        val s = uiState()
        if (s.storageLevel == level) return
        updateUi { it.copy(storageLevel = level, storageNoticeDismissed = it.storageNoticeDismissed && level != coffee.crema.history.StorageLevel.OK) }
    }

    /** The user dismissed the getting-full notice. */
    fun dismissStorageNotice() = updateUi { it.copy(storageNoticeDismissed = true) }

    /**
     * Write the history's edits in the background. Every edit is in the
     * store's memory already, so every reader has it now (#310 A3a, A3b);
     * the write takes what memory holds as it runs.
     */
    private fun saveHistory() {
        scope.launch { persistHistory() }
    }

    /** Load the persisted bean library into the UI snapshot at startup. */
    suspend fun loadLibrary() {
        val lib = library.load()
        editRows(
            { it.copy(beans = it.beans.with(lib.beans), roasters = it.roasters.with(lib.roasters), grinders = it.grinders.with(lib.grinders)) },
            // No bean is picked for the user: "none", once chosen or left by a
            // restore or an archive, lasts (restore-safety §3, #312). The
            // grinder pointer is the device's own too (#488).
            { it.copy(activeBeanId = lib.activeBeanId, activeGrinderId = lib.activeGrinderId) },
        )
        library.openSaves()
    }

    /**
     * Load the shot log, off the startup chain (#310 P3a): the rows only, no
     * series (P4a), so the startup sequence (prefs, auto-connect) never waits
     * on it. The first launch moves the rows into `shots.db` here. The rows
     * **merge** with what memory holds by then, so a shot captured before they
     * landed survives. A shot's series loads when something needs it
     * ([loadShot]).
     */
    suspend fun loadHistory() {
        val loaded = landed
        historySettled = false
        val load = historyStore.loadAll()
        land(load, loaded, merge = true)
    }

    /** [load] into the UI state, merged with the rows made meanwhile.
     *  Returns how many rows the view's cap let go (dropped on the legacy backend). */
    private suspend fun land(
        load: coffee.crema.history.HistoryLoad,
        loaded: CompletableDeferred<Unit>,
        merge: Boolean,
        session: List<ShotRow> = emptyList(),
    ): Int {
        var madeHere: List<ShotRow> = emptyList()
        var newer: List<ShotRow> = emptyList()
        var dropped = 0
        try {
            // The store's memory holds the rows it loaded and the rows made here since
            // it last opened, which it hasn't written (#310 A3b). [session] is what memory
            // held before a reload: a row of it the load didn't bring stays too, as it did
            // when the view held it. A row can be in both under another id, if it came
            // from Visualizer: the loaded one stays.
            val now = historyStore.rows()
            val unwritten = historyStore.unwritten().mapTo(HashSet()) { it.id }
            val present = now.mapTo(HashSet()) { it.id }
            val made = if (merge) (now.filter { it.id in unwritten } + session.filter { it.id !in present }).distinctBy { it.id } else emptyList()
            val loadedRows = now.filter { it.id !in unwritten }
            madeHere = madeWhileRefused(made, loadedRows)
            val keep = madeHere.mapTo(HashSet()) { it.id }
            historyStore.put(madeHere.filter { it.id !in present })
            // A row memory held that the load brought back as another version (a snapshot or
            // the old copy after a mid-session loss): memory's is the newer, as the view's was
            // (REVIEW-470 B2). The summary isn't compared, and the write keeps the stored one.
            val loadedById = now.associateBy { it.id }
            newer = if (merge) session.filter { m ->
                m.id in present && m.id !in unwritten && loadedById[m.id]?.let { it != m.copy(summary = it.summary) } == true
            } else emptyList()
            historyStore.put(newer)
            val drop = made.map { it.id }.filterNot { it in keep }
            historyStore.drop(drop)
            historyStore.unstage(drop)
            if (madeHere.isNotEmpty()) {
                // In by date, and on the legacy backend capped, as when they were made.
                val ids = madeHere.mapTo(HashSet()) { it.id }
                editHistory { rows ->
                    val mine = rows.filter { it.id in ids }
                    val merged = mergeHistory(mine, rows.filter { it.id !in ids }, cap = viewCap)
                    dropped = merged.dropped
                    rows.with(merged.rows)
                }
            }
            updateUi { it.copy(historyLoaded = true, historyHealth = load.health, historyLost = historyStore.lostChoices, historyError = historyStore.lastError, historyFlipError = historyStore.flipError) }
            historySettled = when (load.health) {
                // A damaged shots.json moved as nothing: its rows, pinned ones too, weren't read.
                HistoryHealth.OK -> !historyStore.sourceDamaged
                HistoryHealth.LEGACY -> historyStore.storeHealth.let { it == coffee.crema.persist.StoreHealth.OK || it == coffee.crema.persist.StoreHealth.ABSENT }
                else -> false
            }
            historyStore.openSaves()
            checkStorage()
            rowsLoaded.complete(Unit)
            // A shot made before the rows landed, and memory's newer versions, go into the store now.
            if (madeHere.isNotEmpty() || newer.isNotEmpty()) persistHistory()
            if (load.health == HistoryHealth.LEGACY && !legacyNoticed) {
                legacyNoticed = true
                notify("Shot history stays in its old format for now, keeping the newest ${coffee.crema.history.HistoryStore.LEGACY_MAX_SHOTS}. It will try again at the next launch.")
            }
        } finally {
            loaded.complete(Unit)
        }
        return dropped
    }

    /**
     * The stage-1 history index (#310 A1): its boot check, and a full diff on
     * a miss ([force]: one anyway). Nothing reads the index yet.
     */
    suspend fun syncHistoryIndex(force: Boolean = false): coffee.crema.history.IndexDiffReport? = historyStore.syncIndex(force)

    /** The index's health and a row-by-row check against `shots.db`, for the debug screen. */
    suspend fun historyIndexHealth(): coffee.crema.history.IndexHealth = historyStore.indexHealth()

    /** History's reads of the index while it can answer, else null: the in-memory rows (#310 A2). */
    val historyIndexReads: kotlinx.coroutines.flow.StateFlow<coffee.crema.history.HistoryIndexReads?> get() = historyStore.indexReads

    /** Why History reads the in-memory rows now, or null (the debug screen). */
    val historyIndexFallback: String? get() = historyStore.fallbackReason

    /** The live bean and method libraries into the index (#310 A2): derived data its coffee, tag and method answers read. */
    suspend fun mirrorHistoryLive(live: coffee.crema.core.HistoryLive) = historyStore.setIndexLive(live)

    suspend fun compareHistoryIndex(): coffee.crema.history.IndexComparison? = historyStore.compareIndex()

    /** The legacy-backend notice is said once per session. */
    private var legacyNoticed = false

    /** Load the shot log again, once it can be read (the legacy backend's refused
     *  file). Shots made while it couldn't be stay, and go into it now. Everything
     *  that reads a series waits for this load too. */
    suspend fun reloadHistory() {
        landed = CompletableDeferred()
        val session = historyStore.rows()
        val load = historyStore.loadAll()
        val dropped = land(load, landed, merge = true, session = session)
        if (dropped > 0) notify("Shot history reloaded${droppedNotice(dropped)}")
    }

    /**
     * Try the store again: a failed migration (Settings' Try again), or a store
     * that wouldn't open (the banner, and each resume). The rows in memory
     * merge in, as at launch.
     */
    suspend fun retryHistory() {
        val before = historyStore.health
        if (before == HistoryHealth.OK || before == HistoryHealth.LOST) return
        val session = historyStore.rows()
        val load = historyStore.retry()
        if (load.health == before && load.health != HistoryHealth.LEGACY) {
            updateUi { it.copy(historyError = historyStore.lastError) }
            return
        }
        landed = CompletableDeferred()
        land(load, landed, merge = true, session = session)
    }

    /** What the user chose for a lost store: each is explicit (SPEC §6). */
    enum class LostChoice { SNAPSHOT, OLD_COPY, START_EMPTY }

    /** Rebuild a lost store the way the user chose, then load it. */
    suspend fun resolveLostHistory(choice: LostChoice) {
        if (historyStore.health != HistoryHealth.LOST) return
        val session = historyStore.rows()
        val load = when (choice) {
            LostChoice.SNAPSHOT -> historyStore.restoreSnapshot()
            LostChoice.OLD_COPY -> historyStore.recoverOldCopy()
            LostChoice.START_EMPTY -> historyStore.startEmpty()
        }
        landed = CompletableDeferred()
        land(load, landed, merge = true, session = session)
        if (load.health == HistoryHealth.OK) notify("Shot history is back: ${historyStore.rows().size} shots")
    }

    /**
     * Whether a cleanup that spares pinned shots may run (#311): the rows are in
     * the history, from a load that read them or found no file. Not before, as
     * an early BLE connect would see no pinned shots, and not after a damaged or
     * unavailable load, as an unread row may be a pinned one. Set as the rows
     * land, so a load in progress never counts.
     */
    @Volatile var historySettled: Boolean = false
        private set

    /** Whether a Visualizer pull may run: not while the rows couldn't be read,
     *  as it would match against a list that doesn't hold them. */
    val historyRefused: Boolean get() = historyStore.savesRefused

    /** Completes once the rows are in the UI state (SPEC §3 `rowsLoaded`): the
     *  gate for code that reads the history as a whole. */
    private val rowsLoaded = CompletableDeferred<Unit>()

    /** Completes once the latest load (the first, or a reload, a retry, a lost
     *  store's rebuild) has landed in the UI state. */
    @Volatile private var landed = CompletableDeferred<Unit>()

    /** Wait for the rows ([rowsLoaded]) and for any load in progress to land:
     *  everything that reads the history as a whole (backup, export, uploads,
     *  a Visualizer pull, a restore) awaits this first. No series is loaded:
     *  what needs one calls [loadShot] (#310 P4a). */
    suspend fun awaitRows() {
        rowsLoaded.await()
        landed.await()
    }

    /** Whether the rows have landed ([awaitRows]). */
    val rowsReady: Boolean get() = rowsLoaded.isCompleted && landed.isCompleted

    // ── The history's rows: the store's, never the UI state's (#310 A3a, A3b) ──

    /**
     * Every row, newest first: the history store's, in memory, with every
     * edit made so far ([HistoryStore.rows]), read in one go (SPEC S3). What
     * every reader of the whole history reads: sync's planners and backlogs,
     * backups and exports, imports' id checks, the screens' counts, and
     * History's list while the index can't answer.
     */
    fun historyRows(): List<ShotRow> = historyStore.rows()

    /** [historyRows], under the names its callers knew it by. */
    fun allShots(): List<ShotRow> = historyRows()
    fun syncRows(): List<ShotRow> = historyRows()

    /**
     * The row [id] as the store holds it now ([HistoryStore.row]): every
     * lookup by id, what an upload re-reads before it goes, and what "is it
     * gone, is it bound" reads after (P3 spec, #354 review). Null = deleted.
     */
    fun findShot(id: String): ShotRow? = historyStore.row(id)
    fun syncShot(id: String): ShotRow? = findShot(id)

    /** Test seam: the store's health now, and a mid-session loss as SQLite's error handler makes one ([HistoryStore.quarantineForTest]). */
    @androidx.annotation.VisibleForTesting
    internal val historyHealthForTest: HistoryHealth get() = historyStore.health

    @androidx.annotation.VisibleForTesting
    internal fun quarantineHistoryForTest(aside: File) = historyStore.quarantineForTest(aside)

    /** Bumped whenever the rows change: what derives from them follows it. */
    val syncRowsVersion: kotlinx.coroutines.flow.StateFlow<Long> get() = historyStore.rowsVersion

    /** Whether [shot] has a curve: staged, or (its summary says so) on disk. */
    fun hasCurve(shot: ShotRow): Boolean = historyStore.hasCurve(shot)

    /** Whether shot [id] has its profile, staged or stored. */
    fun shotHasProfile(id: String): Boolean = historyStore.hasProfile(id)

    /**
     * The shot [id] whole, its series, profile and brew series under its row as
     * it stands now (SPEC §4 `loadShot`). Waits for the rows. Null when no row
     * has it. Throws [coffee.crema.history.HistoryReadException] when it can't
     * be read just now: that says nothing about the shot, so a caller never
     * reads it as having no curve.
     */
    suspend fun loadShot(id: String): StoredShot? {
        awaitRows()
        val full = historyStore.loadShot(id) ?: return null
        // The row as the store holds it (#310 A3a): an upload builds its payload from this.
        val row = findShot(id) ?: return null
        return row.joined(full)
    }

    /** [loadShot], or null (and a line in the log) when it can't be read: for what can only show it. */
    suspend fun loadShotOrNull(id: String): StoredShot? =
        runCatching { loadShot(id) }.onFailure { appendLog("Couldn't read shot $id: ${it.message}") }.getOrNull()

    /** The row for [shot], made or brought here whole: its heavy parts wait for the next save ([HistoryStore.stage]). */
    private fun staged(shot: StoredShot): ShotRow = historyStore.stage(shot)

    /**
     * The row [id] changed by [transform], on the row as the store holds it
     * now, in memory at once (every reader has it from here) and then
     * written. Not saved when the store holds no such row. The changed row.
     */
    private fun editShot(id: String, transform: (ShotRow) -> ShotRow): ShotRow? {
        val next = historyStore.update(id, transform) ?: return null
        saveHistory()
        return next
    }

    /** Rows whose curve or row couldn't be saved: the lasting banner's. */
    val historyUnsaved: Set<String> get() = historyStore.unsavedCurves + historyStore.journaled

    /** Load the pinned/hidden built-in overlays at startup (before the customs). */
    suspend fun loadProfileMeta() {
        pinnedIds = pinnedProfileStore.load()
        pinnedProfileStore.openSaves()
        hiddenIds = hiddenProfileStore.load()
        hiddenProfileStore.openSaves()
    }

    /** Load the persisted custom profiles into the merged list at startup. */
    suspend fun loadCustomProfiles() {
        // The top-level brew temp follows the first step (the dial's anchor):
        // re-derived for customs saved before that rule.
        customProfilesJson = customProfileStore.load().map(::derivedBrewTemp)
        customProfileStore.openSaves()
        refreshProfiles(seedEmpty = true)
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
    private fun refreshProfiles(seedEmpty: Boolean = false) {
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
        // Self-heal a stale selection (a hidden built-in restored from prefs,
        // a custom a restore replaced) by reseeding to the first visible
        // profile, and seed an empty one at the startup loads ([seedEmpty]).
        // No selection is kept otherwise: deleting or hiding the active
        // profile clears it (review 2026-11-21 F5), and a later refresh (a
        // pin, an import) must not pick one behind the user's back.
        val own = uiState().activeProfileId
        val current = own?.takeIf { id -> all.any { it.id == id } }
        updateUi { st ->
            // A selection the user cleared stays clear at the startup loads too (follow-up 5).
            val reseed = if (own != null || (seedEmpty && !st.profileCleared)) all.firstOrNull { it.id !in hiddenIds }?.id else null
            val active = current ?: reseed
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

    /** What an in-place Quick Controls Save replaced, until its Undo goes. */
    private data class QuickSaveUndo(
        val seq: Long,
        /** What the Save wrote, for the core's guarded Undo (`quick_save_undo`, REVIEW-CORE M2). */
        val wrote: coffee.crema.core.QuickSaveUndo,
        /** The profile's body before the Save. */
        val previousJson: String,
        /** The Quick-Controls override the Save folded into the profile. */
        val previousBrew: BrewParams?,
        /** The bag had no grind rows at all before the Save (null, not []): Undo leaves it so (#13). */
        val bagHadNoGrinds: Boolean = false,
    )

    private var quickSaveUndo: QuickSaveUndo? = null
    private var quickSaveSeq = 0L

    /**
     * Quick Controls' **Save** (review 2026-11-21 F1): write each changed part
     * of the core's plan ([quickSavePlan], `quick_save_plan`) to its owner, in
     * one message. The grind goes to the active bag's row for the active
     * grinder (#488, the core's `save_bag_grind`: adds the row, never moves
     * the star). The recipe dials go to the profile: a custom one is updated
     * in place (Undo in the message) unless [asNew]; a built-in, or [asNew],
     * becomes a copy named [copyName], made active. A copy without a name
     * writes nothing (the prompt asks first). A Save that writes retires the
     * older Save's Undo message (follow-up 3). Runs on the device that owns
     * the session: a remote sends it here (review F8).
     */
    fun quickSave(copyName: String? = null, asNew: Boolean = false, now: Long = System.currentTimeMillis()): CommandOutcome {
        // The selections and dials on screen: on the hub (where a remote's Save
        // lands too, review F8) they are its own; a remote sends Save there.
        val s = effectiveState()
        val plan = s.quickSavePlan()
        if (!plan.enabled) return CommandOutcome.Refused("Nothing to save")
        val copying = plan.writesRecipe && (asNew || plan.profileWrite == coffee.crema.core.QuickSaveProfileWrite.Copy)
        val name = copyName?.trim()?.takeIf { it.isNotEmpty() }
        if (copying && name == null) return CommandOutcome.Refused("A copy needs a name")
        // Everything that can fail first: nothing is written unless all of it can be.
        var base: String? = null
        var written: String? = null
        if (plan.writesRecipe) {
            val id = plan.profileId ?: return CommandOutcome.Refused("No profile")
            base = profileJsonById[id] ?: return CommandOutcome.Refused("That profile isn’t here")
            // The dial baked in by the core exactly as a shot runs it: brew temp
            // shifts every segment, pre-infusion rescales the leading segments,
            // dose / yield replace (id / name / everything else preserved).
            val effective = runCatching { effectiveCremaProfile(base, s.brewParams.overridesJson(json)) }.getOrElse {
                appendLog("Save profile failed: ${it.message}")
                return CommandOutcome.Refused("Save failed")
            }
            written = if (copying) {
                runCatching { quickPresetJson(effective, name!!, json) }.getOrElse {
                    appendLog("Save profile failed: ${it.message}")
                    return CommandOutcome.Refused("Save failed")
                }
            } else {
                effective
            }
        }
        val parts = mutableListOf<String>()
        var undo: QuickSaveUndo? = null
        if (written != null && base != null) {
            val id = plan.profileId!!
            if (copying) {
                customProfilesJson = listOf(written) + customProfilesJson
                refreshProfiles()
                persistCustomProfiles()
                profileIdOf(written, json)?.let { setActiveProfile(it) }
                parts += "Saved “$name”"
            } else {
                customProfilesJson = customProfilesJson.map { if (profileIdOf(it, json) == id) written else it }
                refreshProfiles()
                persistCustomProfiles()
                // The values live in the profile now.
                resetBrewParams()
                parts += "“${plan.profileName}” updated"
                val wrote = coffee.crema.core.QuickSaveUndo(
                    profileId = id,
                    profileName = plan.profileName.orEmpty(),
                    writtenProfile = json.parseToJsonElement(written),
                )
                undo = QuickSaveUndo(++quickSaveSeq, wrote, base, s.brewParams)
            }
        }
        val grind = plan.grind
        val beanId = plan.beanId
        if (grind != null && beanId != null) {
            val grinder = s.activeGrinder()
            val slot = grinder?.id ?: coffee.crema.beans.NO_GRINDER_ID
            var saved: coffee.crema.core.GrindSave? = null
            var before: coffee.crema.core.Bean? = null
            editBeans { rows ->
                rows.mapped { b ->
                    if (b.id != beanId) return@mapped b
                    before = b
                    val out = runCatching { coffee.crema.beans.saveBagGrind(b, grinder, grind, now) }
                        .onFailure { appendLog("Save grind failed: ${it.message}") }.getOrNull()
                    saved = out
                    out?.bean ?: b
                }
            }
            val out = saved
            val was = before
            if (out != null && out.changed && was != null) {
                persistLibrary()
                val label = coffee.crema.beans.formatGrind(grind, grinder?.scale)
                val where = listOfNotNull(plan.beanName, grinder?.name).joinToString(" · ")
                parts += if (parts.isEmpty()) "Grind $label saved to $where" else "grind $label saved to $where"
                undo = undo?.let { u ->
                    u.copy(
                        wrote = u.wrote.copy(
                            grind = coffee.crema.core.QuickSaveGrindUndo(
                                beanId = beanId, grinderId = slot, before = was.savedGrind(slot), written = out.bean.savedGrind(slot),
                            ),
                        ),
                        bagHadNoGrinds = was.grinds == null,
                    )
                }
            }
        }
        if (parts.isEmpty()) return CommandOutcome.Accepted
        // This Save is the newest: the older Save's Undo message goes (follow-up 3).
        retireQuickSaveUndo()
        val message = parts.joinToString(" · ")
        if (undo != null) {
            quickSaveUndo = undo
            appendLog(message)
            updateUi { it.copy(quickSaveUndo = UndoNotice(message, undo.seq)) }
        } else {
            notify(message)
        }
        return CommandOutcome.Accepted
    }

    /** The older Save's Undo goes: its message is dismissed (the screen drops a notice that's gone). */
    private fun retireQuickSaveUndo() {
        if (quickSaveUndo == null && uiState().quickSaveUndo == null) return
        quickSaveUndo = null
        updateUi { it.copy(quickSaveUndo = null) }
    }

    /** Undo the in-place Save [seq] by the core's guard (`quick_save_undo`):
     *  each half comes back only while it is still what the Save wrote, so an
     *  edit made since (a star, a rename, a new grind) stands. The profile's
     *  body brings its dial back, unless the user dialled again since. */
    fun undoQuickSave(seq: Long, now: Long = System.currentTimeMillis()) {
        val u = quickSaveUndo?.takeIf { it.seq == seq } ?: return
        quickSaveUndo = null
        updateUi { st -> if (st.quickSaveUndo?.seq == seq) st.copy(quickSaveUndo = null) else st }
        val profileId = u.wrote.profileId
        val nowProfile = customProfilesJson.firstOrNull { profileIdOf(it, json) == profileId }
            ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull
        val nowBag = u.wrote.grind?.let { g -> uiState().beans.firstOrNull { it.id == g.beanId } }
        val plan = runCatching {
            coffee.crema.ui.quickSaveUndo(coffee.crema.core.QuickSaveUndoInput(undo = u.wrote, nowProfile = nowProfile, nowBag = nowBag), now)
        }.getOrElse {
            appendLog("Save undo failed: ${it.message}")
            return
        }
        if (plan.restoreProfile) {
            customProfilesJson = customProfilesJson.map { if (profileIdOf(it, json) == profileId) u.previousJson else it }
            refreshProfiles()
            persistCustomProfiles()
            val st = uiState()
            if (st.activeProfileId == profileId && st.brewParams == null) u.previousBrew?.let(restoreBrewParams)
        }
        plan.restoreBean?.let { restored ->
            // A bag that had no rows before the Save goes back to none, not [] (#13).
            val back = if (u.bagHadNoGrinds && restored.grinds.isNullOrEmpty()) restored.copy(grinds = null) else restored
            var put = false
            editBeans { rows ->
                rows.mapped { b ->
                    // Only over the row the core checked: anything since stands.
                    if (b.id != back.id || b != nowBag) return@mapped b
                    put = true
                    back
                }
            }
            if (put) persistLibrary()
        }
        val name = uiState().profiles.firstOrNull { it.id == profileId }?.name ?: u.wrote.profileName.takeIf { it.isNotBlank() }
        val quoted = name?.let { "“$it”" }
        notify(
            when (plan.outcome) {
                coffee.crema.core.QuickSaveUndoOutcome.Undone -> "Save undone"
                coffee.crema.core.QuickSaveUndoOutcome.ProfileOnly -> "${quoted ?: "The profile"} put back; the grind changed since, not undone"
                coffee.crema.core.QuickSaveUndoOutcome.GrindOnly -> "Grind put back; ${quoted ?: "the profile"} changed since, not undone"
                coffee.crema.core.QuickSaveUndoOutcome.NotUndone -> "${quoted ?: "The profile"} changed since; not undone"
            },
        )
    }

    /** The Save [seq]'s message went without Undo: the save stands. */
    fun consumeQuickSaveUndo(seq: Long) {
        if (quickSaveUndo?.seq == seq) quickSaveUndo = null
        updateUi { st -> if (st.quickSaveUndo?.seq == seq) st.copy(quickSaveUndo = null) else st }
    }

    // ── Profile editor (the `profile-edit` route) ─────────────────────────────

    /** Open an existing saved (custom) profile in the editor. */
    fun startEditProfile(id: String) {
        draftProfileJson = null
        editorBaseJson = customProfilesJson.firstOrNull { profileIdOf(it, json) == id }
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
        editorBaseJson = fullJson
        updateUi { it.copy(editingProfileId = thin.id, draftProfile = thin) }
    }

    /**
     * Save the editor's non-curve edits. Patches only the edited fields into the
     * profile's complete JSON base (the draft for a new / duplicated profile, else
     * the custom the editor opened on), so every wire-relevant field — per-segment
     * temp / mode / ramp, notes, beverage type — round-trips untouched to the DE1.
     * An edit of a stored custom then goes through the core's `merge_profile_edit`
     * (REVIEW-FINAL-2 N2): only the fields the user changed land on the row as it
     * is stored now, so a pin or an in-place Quick Save made meanwhile stays.
     * Upserts into the custom store (newest first for a new profile; in place for
     * an edit), refreshes the merged list, and persists.
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
        val current = customProfilesJson.firstOrNull { profileIdOf(it, json) == id }
        val isExisting = current != null
        val base = when {
            draftProfileJson != null && uiState().editingProfileId == id -> draftProfileJson
            isExisting -> editorBaseJson?.takeIf { uiState().editingProfileId == id && profileIdOf(it, json) == id } ?: current
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
        val edited = derivedBrewTemp(patched)
        // A stored custom: what the user changed, onto the row as it is now.
        val saved = if (current == null) edited else runCatching {
            val opened = derivedBrewTemp(coffee.crema.profiles.profileEditorSeedJson(base, json))
            coffee.crema.profiles.mergeProfileSave(opened, edited, current)
        }.getOrElse {
            appendLog("Save profile failed: ${it.message}")
            return
        }
        editorBaseJson = saved
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
        editorBaseJson = null
        updateUi { it.copy(editingProfileId = null, draftProfile = null) }
    }

    /**
     * Delete a custom profile (built-ins are not deletable — they have no store
     * entry, so this is a no-op for them). Deleting the active one clears the
     * selection: Brew shows "No profile" rather than a profile the user didn't
     * pick (review 2026-11-21 F5).
     */
    fun deleteProfile(id: String) {
        if (customProfilesJson.none { profileIdOf(it, json) == id }) return
        customProfilesJson = customProfilesJson.filterNot { profileIdOf(it, json) == id }
        val wasActive = uiState().activeProfileId == id
        if (wasActive) updateUi { it.withActiveProfileId(null) }
        // Cascade: a deleted custom is gone for good — clear bean links so
        // the auto-load never chases a tombstone, and stamp the bags so a
        // sync sees the change (web onDeleted parity, review F9).
        if (uiState().beans.any { it.linkedProfileId == id }) {
            val now = System.currentTimeMillis()
            editBeans { rows -> rows.mapped { if (it.linkedProfileId == id) it.copy(linkedProfileId = null, updatedAt = now) else it } }
            persistLibrary()
        }
        refreshProfiles()
        persistCustomProfiles()
        if (wasActive) clearedActive()
    }

    /** The active profile was deleted or hidden and is now none: the stop
     *  targets, the desired profile and the mirrors follow, as on a pick (on
     *  this device's own selection; a remote's screen shows its hub's). */
    private fun clearedActive() {
        // A remote keeps its own cleared selection across a restart too
        // (follow-up 5); its stop targets are the hub's.
        if (uiState().mirror != null) return persistSelection()
        onProfileCleared()
    }

    /** Archive (hide) a built-in profile — built-ins can't be deleted, so this
     *  hides it from the active grid. Restored from the "Hidden" filter. Persisted. */
    fun archiveBuiltinProfile(id: String) {
        hiddenIds = hiddenIds + id
        // Hiding the active profile clears the selection (F5), as a delete does.
        val wasActive = uiState().activeProfileId == id
        if (wasActive) updateUi { it.withActiveProfileId(null) }
        refreshProfiles()
        if (wasActive) clearedActive()
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
        scope.launch {
            // The shot whole, just before it goes (#310 P4a): a row holds no curve.
            val shot = runCatching { loadShot(id) }
                .getOrElse { notify("Couldn't read that shot just now. Try again."); return@launch } ?: return@launch
            val text = runCatching { exportV2JsonShot(wireShotJson(shot).toString()) }
                .getOrElse { appendLog("Export shot failed: ${it.message}"); return@launch }
            shareText(text, "Share shot")
        }
    }

    /** Export the whole shot history as Crema `crema/v1` JSONL — Settings →
     *  Sharing. The core's format (F10): both shells import it back. */
    fun exportAllShots() {
        scope.launch {
            val text = shotsJson(null) ?: return@launch
            shareText(text, "Export shots")
        }
    }

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
            beanLibraryJsonl(s.beans, s.roasters, System.currentTimeMillis(), coffee.crema.BuildConfig.VERSION_NAME, grinders = s.grinders)
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
    suspend fun shotsJson(ids: List<String>?): String? {
        // Until the rows have loaded, say so rather than export none.
        if (!rowsReady) { notify("Shot data is still loading — try again in a moment"); return null }
        // The store's rows, not the view's (#310 A3a): every shot it holds.
        val history = syncRows()
        val rows = if (ids == null) history else { val w = ids.toHashSet(); history.filter { it.id in w } }
        if (rows.isEmpty()) { notify("Export — no shots"); return null }
        // Each shot whole, read one at a time (#310 P4a). A read that fails
        // stops the export: a file without that shot's curve would look whole.
        // A read that fails just now is tried twice more, as a backup's is.
        val missing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val shots = try {
            withContext(Dispatchers.IO) {
                rows.map { row ->
                    var last: coffee.crema.history.HistoryReadException? = null
                    repeat(3) {
                        try {
                            return@map historyStore.loadFull(row, missing)
                        } catch (e: coffee.crema.history.HistoryReadException) {
                            last = e
                        }
                    }
                    throw last!!
                }
            }
        } catch (e: coffee.crema.history.HistoryReadException) {
            notify("Couldn't read every shot just now, so nothing was exported. Try again.")
            appendLog("Export shots: ${e.message}")
            return null
        }
        // A damaged or missing curve goes without it, and says so, as a backup does (#419 N2).
        if (missing.isNotEmpty()) notify("${missing.size} shot(s) have damaged telemetry and are exported without their curves")
        return withContext(Dispatchers.Default) {
            runCatching {
                shotHistoryJsonl(shots, System.currentTimeMillis(), coffee.crema.BuildConfig.VERSION_NAME)
            }.getOrElse { appendLog("Export shots failed: ${it.message}"); null }
        }
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

    /**
     * The pour's averages (pressure, flow, head temperature), first flow to
     * last, from the core (`de1_domain::pour_averages`, #299) — derived on
     * view like the quality analysis, nothing stored. Null when the shot's
     * samples hold no pour (no telemetry, an import without it), so the detail
     * shows nothing rather than zeros.
     */
    fun pourAverages(shot: StoredShot): coffee.crema.core.PourAverages? {
        return runCatching {
            when (val averages = bridge.pourAveragesForShot(shot.coreShotJson().toString())) {
                "null" -> null
                else -> json.decodeFromString(coffee.crema.core.PourAverages.serializer(), averages)
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
    /** Copy a built backup [file] to the picked [uri], then remove the temporary file. */
    fun writeFileToUri(uri: Uri, file: File) {
        scope.launch {
            notify(if (saveBackupFile(uri, file)) "Backed up to file" else "Backup failed — couldn't write")
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
            var merged = HistoryMerge<ShotRow>(emptyList(), emptyList(), 0)
            var added: List<ShotRow> = emptyList()
            // Every row the store holds (#310 A3b): a shot already here isn't imported again.
            val present = historyRows().mapTo(HashSet()) { it.id }
            // Each new shot's curve, profile and brew series wait for the save.
            val incoming = shots.filterNot { it.id in present }.distinctBy { it.id }.map(::staged)
            editHistory { rows ->
                val existing = rows.mapTo(HashSet()) { it.id }
                added = incoming.filterNot { it.id in existing }
                merged = mergeHistory(added, rows, cap = viewCap)
                rows.with(merged.rows)
            }
            // What the legacy backend's cap dropped isn't written: its staged parts go too.
            val landing = merged.kept.mapTo(HashSet()) { it.id }
            historyStore.unstage(incoming.map { it.id }.filterNot { it in landing })
            visualizer.forgetTombstones(merged.kept.map { it.id })
            notify("Imported ${merged.kept.size} shot(s)${droppedNotice(merged.dropped)}")
            persistHistory()
        }
    }

    // ── Full-app backup & restore (crema-backup/v1) ──────────────────────────
    // One portable bundle: custom profiles + beans/roasters + shot history + the
    // portable settings subset, (de)serialised by the core (exportBackupBundle /
    // the line-tagged JSONL). OAuth tokens + per-device BLE/proxy state are
    // stripped on export and preserved on import — a backup moves between devices
    // without dragging this device's bindings along.

    /** Snapshot the backup inputs, or null + a notice when there's nothing to
     *  back up. Custom profiles only — built-ins ship with the app, so backing
     *  them up would only create duplicates. Cheap; runs on the caller. */
    private fun backupSnapshot(quiet: Boolean = false): BackupSnapshot? {
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
        // Every row the store holds (#310 A3b), each read whole one at a time by the walk.
        val shots = historyRows()
        val noData = customs.isEmpty() && s.beans.isEmpty() && s.roasters.isEmpty() && shots.isEmpty() &&
            userRecipes.isEmpty() && s.customMethods.isEmpty()
        val noProfileOrg = pinnedIds.isEmpty() && hiddenIds.isEmpty() &&
            s.lastRecipeByMethod.isEmpty() && s.hiddenBuiltinRecipes.isEmpty()
        // As a fresh install leaves them, by the core's rule: a first launch fills
        // the hot-water fields, so they never equal the raw defaults.
        val settingsDefault = coffee.crema.core.commonIsPristine(
            json.encodeToString(coffee.crema.core.CommonSettings.serializer(), currentPrefs().toCommonSettings()),
        ) && currentPrefs().qcGrind == null
        if (noData && noProfileOrg && settingsDefault) {
            if (!quiet) notify("Nothing to back up yet")
            return null
        }
        // Hidden rows the walk finds without the curve their summary names: counted for the report (#408 S5).
        val missingCurves = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val full: (ShotRow) -> coffee.crema.history.StoredShot = { historyStore.loadFull(it, missingCurves) }
        return BackupSnapshot(
            customs, s, pinnedIds, hiddenIds, currentPrefs(), visualizer.backupPrefs(), userRecipes,
            shots = shots,
            full = full,
            missingCurves = missingCurves,
            // On history.db (#310 A5) the backup walks the store's export walk, which holds a
            // delete made during it (S6), each row read whole here, its missing curves counted.
            source = historyStore.backupSource(full),
        )
    }


    /**
     * Wrap [backupBundle]'s JSONL into a `.crema.zip`: `backup.jsonl` plus, for
     * every bean with a stored photo, `images/<RAW bean id>` (the cross-shell key
     * web + Android restore by). Photos resolve by file existence (not by parsing
     * `imageRef`), so a web-restored bag's photo rides along too. Null + a notice
     * when there's nothing to back up. Shared by the local SAF save + Drive upload.
     * Snapshots on the caller's thread, then serialises, exports and zips on
     * [Dispatchers.Default] — never on Main.
     */
    suspend fun buildBackupZipFile(): File? = buildBackupZip(BackupKind.FILE)?.file

    /** The backup as a temporary file ([buildBackupZipFile]), with what's in it. */
    suspend fun buildBackupZip(kind: BackupKind): BackupZip? {
        // The cancel exists from here, so a Cancel tapped while the series
        // still load isn't lost (#391 review).
        val cancel = backupRuns.start(kind)
        try {
            // Never back up before the rows are in (F10 validation P1-2: the
            // startup auto-backup ran during the load). Each shot's curve is
            // read as the walk reaches it (#310 P4a).
            awaitRows()
            if (cancel.get()) {
                notify("Backup cancelled: nothing was saved.")
                return null
            }
            reportUnreadableSeries()
            reportUnhealthyStores()
            return buildBackupZipNow(kind, cancel)
        } finally {
            backupRuns.end(kind, cancel)
        }
    }

    /** A store that couldn't be read this session isn't in a backup made now:
     *  a manual one goes ahead, and says what it's missing. */
    private fun reportUnhealthyStores() {
        val missing = coffee.crema.persist.Stores.unhealthy()
        if (missing.isEmpty()) return
        val list = if (missing.size == 1) missing[0] else missing.dropLast(1).joinToString(", ") + " and " + missing.last()
        notify("This backup doesn't include your $list: ${if (missing.size == 1) "it" else "they"} couldn't be read.")
    }

    /** Shots whose telemetry file couldn't be read go into a backup without a
     *  curve: say so once per backup. */
    private fun reportUnreadableSeries() {
        historyStore.unreadableRows.size.takeIf { it > 0 }?.let { n ->
            notify("$n shot${if (n == 1) "" else "s"} couldn't be read and ${if (n == 1) "isn't" else "aren't"} in this backup")
        }
        val unreadable = historyStore.unreadableSeries
        if (unreadable.isEmpty()) return
        val n = allShots().count { it.id in unreadable }
        if (n > 0) notify("$n shot(s) have damaged telemetry and are backed up without their curves")
    }

    /** Each running build's cancel, by kind: a cancel stops only its own build (#391 review). */
    private val backupRuns = BackupRuns()

    /** Stop the [kind] backup being built, keeping nothing. */
    fun cancelBackup(kind: BackupKind) = backupRuns.cancel(kind)

    /**
     * Build the backup one line at a time into a temporary file in the cache
     * directory ([streamBackupZip], #310 P2a), reporting its progress in the UI
     * state under its [kind]. A cancel, a failure or a backup past the limits
     * keeps nothing.
     */
    private suspend fun buildBackupZipNow(kind: BackupKind, cancel: java.util.concurrent.atomic.AtomicBoolean): BackupZip? {
        // A build that finished as its caller was cancelled never reaches anyone
        // who'd release it: release it here, so the sweep can take it (#391 review).
        var built: BackupZip? = null
        return try {
            snapshotThenBuild(::backupSnapshot, Dispatchers.Default) { snap -> streamBackupNow(snap, kind, cancel).also { built = it } }
        } catch (c: kotlinx.coroutines.CancellationException) {
            built?.release()
            throw c
        } finally {
            updateUi { it.copy(backupProgress = it.backupProgress - kind) }
        }
    }

    private fun streamBackupNow(snap: BackupSnapshot, kind: BackupKind, cancel: java.util.concurrent.atomic.AtomicBoolean): BackupZip? {
        backupWalkStarts()
        val cache = app.cacheDir
        sweepBackupTemp(cache)
        val photos = photosOf(snap)
        return runCatching {
            streamBackupZip(
                snap, json, System.currentTimeMillis(), coffee.crema.BuildConfig.VERSION_NAME, deviceLabel(),
                photos, backupTempFile(cache),
                stop = { if (cancel.get()) throw BackupCancelled() },
                onProgress = { p -> updateUi { it.copy(backupProgress = it.backupProgress + (kind to p)) } },
                shotJson = backupShotJson,
            )
        }.fold(
            onSuccess = { built ->
                val gaps = BuiltBackup(jsonl = "", degraded = built.degraded, omitted = built.omitted)
                // A shot by its place in the walk (#310 R1a): the snapshot's rows needn't be the walk's.
                fun idOf(row: OmittedBackupRow) = if (row.kind == "shot") row.id ?: built.shotIds.getOrNull(row.index) else omittedRowId(row, snap)
                built.omitted.forEach { appendLog("Backup left out ${it.kind} ${idOf(it) ?: "at ${it.index}"}") }
                built.degraded.forEach { appendLog("Backup left out the profile of shot $it") }
                built.photosSkipped.forEach { appendLog("Backup left out the photo of bean $it") }
                backupGapsNotice(gaps)?.let { notify(it) }
                // Rows past the view's cap the walk read without their curve (the view's are reported before it).
                snap.missingCurves.size.takeIf { it > 0 }?.let { n ->
                    snap.missingCurves.forEach { appendLog("Backup left out the curve of shot $it: its telemetry is damaged or missing") }
                    notify("$n older shot${if (n == 1) " has" else "s have"} damaged telemetry and ${if (n == 1) "is" else "are"} backed up without ${if (n == 1) "its curve" else "their curves"}")
                }
                // What this backup doesn't hold whole: Free up space keeps these (REVIEW-440 B1).
                // An omitted shot it can't name makes the whole set unknown.
                val omittedShots = built.omitted.filter { it.kind == "shot" }.map(::idOf)
                val inBackup = built.shotIds.toHashSet()
                val curveless = snap.missingCurves + historyStore.unreadableSeries.filter { it in inBackup }
                val whole = if (omittedShots.any { it == null }) null else inBackup - (omittedShots.filterNotNull() + curveless + built.degraded).toSet()
                BackupZip(built.file, built.counts, built.contentHash, release = { releaseBackupFile(built.file) }, whole = whole, mark = built.mark)
            },
            onFailure = {
                when (it) {
                    is BackupCancelled -> notify("Backup cancelled: nothing was saved.")
                    is BackupTooLarge -> notify("Backup failed: ${it.message}")
                    else -> { appendLog("Backup failed: ${it.message}"); notify(BACKUP_FAILED) }
                }
                null
            },
        )
    }

    /** The bean photos a backup of [snap] holds: bean id → file. */
    private fun photosOf(snap: BackupSnapshot): List<Pair<String, File>> =
        snap.state.beans
            .filter { BeanImageStore.exists(app, it.id) }
            .map { it.id to BeanImageStore.beanImageFile(app, it.id) }

    /**
     * Auto-backup's quick "changed?" mark of a backup made now (#310 R1a):
     * the history's write mark and a hash of the rest ([backupMark]), with
     * no walk. Null when the history keeps no write counter (P3a's store),
     * when there's nothing to back up, or before the rows are in.
     */
    suspend fun backupMark(): String? {
        if (!rowsReady) return null
        val snap = backupSnapshot(quiet = true) ?: return null
        return withContext(Dispatchers.Default) {
            snap.history.writeMark()?.let { backupMark(it, backupEnvelope(snap, json, withShots = false), photosOf(snap)) }
        }
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

    // ── Restore through the core's plan (restore-safety PR7a) ─────────────────

    /** What a restore's sink needs from outside the library. */
    class RestoreDeps(
        val machineBusy: () -> Boolean,
        val totalLitres: () -> Double,
        val restoreRecipes: suspend (
            recipes: List<coffee.crema.core.BrewRecipe>,
            methods: List<coffee.crema.core.CustomBrewMethod>,
            defaults: Map<String, String>?,
            hidden: Set<String>?,
        ) -> Boolean,
        val restoreSettings: suspend (AppPrefs) -> Boolean,
        val restoreMaintenance: suspend (MaintenanceState) -> Boolean,
    )

    /** Test seam: runs between step 7's series writes and its rows. */
    @Volatile internal var afterRestoreSeries: (suspend () -> Unit)? = null

    /** Test seam: runs between step 7's change to the view (and to what sync reads) and its row saves. */
    @Volatile internal var beforeRestoreRows: (suspend () -> Unit)? = null

    /** Test seam: runs between step 7's merge into `shots.db` and its removes (a kill there leaves the union). */
    @Volatile internal var beforeRestoreRemove: (suspend () -> Unit)? = null

    /** Sharing a restored Visualizer line wanted and didn't get: for the report. */
    @Volatile var restoreHeldSharing: List<String> = emptyList()
        private set

    /** Test seam: the history's store (#310 A5). */
    @get:androidx.annotation.VisibleForTesting
    internal val historyStoreForTest: HistoryStore get() = historyStore

    /** `history.db`'s `write_seq` when this restore's step 7 began (M3), or null on `shots.db`. */
    @Volatile private var restoreSeq: ULong? = null

    /** Step 7 on P3a's `shots.db` (#310 R1a), or on `history.db` (A5): the store's memory, and the store under it. */
    private val shotsDbRestore = object : coffee.crema.history.ShotsRestore {
        override suspend fun rows(): List<ShotRow> = allShots()

        override fun hasCurve(row: ShotRow): Boolean = this@LibraryController.hasCurve(row)

        override fun rowFor(shot: coffee.crema.history.StoredShot): ShotRow = historyStore.rowFor(shot)

        override suspend fun writeSeries(shots: List<coffee.crema.history.StoredShot>): Int = historyStore.writeSeriesOf(shots)

        override suspend fun settle(next: (List<ShotRow>) -> coffee.crema.restore.NextShots): coffee.crema.restore.NextShots {
            var out: coffee.crema.restore.NextShots? = null
            // On the rows as they stand, every restored row in memory at once: what sync
            // reads from now, not batch by batch as the merges land. Uploads aren't paused
            // by a restore, and a Replace's bound row must never read as unbound
            // (REVIEW-467 N3). A row the plan leaves out stays in the store: only the
            // named remove takes one (rule 1).
            editHistory { rows -> rows.with(next(rows).also { out = it }.next) }
            val settled = checkNotNull(out)
            beforeRestoreRows?.invoke()
            return settled
        }

        override fun stage(shot: coffee.crema.history.StoredShot) {
            historyStore.stage(shot)
        }

        override suspend fun merge(rows: List<ShotRow>): Boolean = persistHistoryAll(rows)

        // The plan named them, less the rows still listed: the backup's ids are already out. On
        // history.db only rows made before the run began go (M3, #310 A5).
        override suspend fun remove(named: List<String>, backupIds: Set<String>) = historyStore.restoreRemove(named, backupIds, restoreSeq)
    }

    /** The stores a restore writes, through the library's state. */
    fun restoreSink(deps: RestoreDeps): coffee.crema.restore.RestoreSink = object : coffee.crema.restore.RestoreSink {
        override suspend fun localState(): coffee.crema.core.LocalState {
            awaitRows()
            val s = uiState()
            fun health(store: String, file: String) = coffee.crema.core.LocalStoreHealth(
                store,
                coreHealth(coffee.crema.persist.Stores.health(java.io.File(app.filesDir, file))),
            )
            val builtinRecipeIds = s.builtinRecipes.map { it.id }.toHashSet()
            // Every row, past the view's cap too: the plan must know them (#310 P3a).
            val shots = allShots()
            val deleted = visualizer.deletedShotIds() - shots.mapTo(HashSet()) { it.id }
            return coffee.crema.core.LocalState(
                shell = "android",
                // From P3a the store keeps every row; only the legacy backend has a cap.
                historyCapacity = historyStore.capacity?.toUInt(),
                machineBusy = deps.machineBusy(),
                health = listOf(
                    // A lost store takes no restore until the user chooses its way back (the banner):
                    // a merge would only go to the journal (#408 N3).
                    coffee.crema.core.LocalStoreHealth(
                        "shots",
                        if (historyStore.health == HistoryHealth.LOST) coffee.crema.core.LocalHealth.Unavailable else coreHealth(historyStore.storeHealth),
                    ),
                    health("beans", LibraryStore.FILE_NAME),
                    health("profiles", CustomProfileStore.FILE_NAME),
                    health("recipes", coffee.crema.brew.RecipeFileStore.FILE_NAME),
                    health("settings", coffee.crema.settings.SettingsStore.FILE_NAME),
                    health("maintenance", coffee.crema.maintenance.MaintenanceStore.FILE_NAME),
                    health("profileMeta", PinnedProfileStore.FILE_NAME),
                    health("profileMeta", HiddenProfileStore.FILE_NAME),
                ),
                // Bag tombstones too, named, so Replace keeps them (#488 review S5).
                beans = s.beans.withTombstones().map { it.id },
                deletedBeans = s.beans.withTombstones().filter { it.deletedAt != null }.map { it.id },
                // Roaster tombstones too, named, so Replace keeps them (#488 review SF1).
                roasters = s.roasters.withTombstones().map { it.id },
                deletedRoasters = s.roasters.withTombstones().filter { it.deletedAt != null }.map { it.id },
                // #488: grinder records, archived and tombstones included, and the device's pointer.
                grinders = s.grinders.map { it.id },
                activeGrinderId = s.activeGrinderId,
                profiles = customProfilesJson.mapNotNull { profileIdOf(it, json) },
                recipes = s.brewRecipes.map { it.id }.filterNot { it in builtinRecipeIds },
                customMethods = s.customMethods.map { it.id },
                shots = shots.map { coffee.crema.core.LocalShot(it.id, hasSamples = hasCurve(it), pinned = it.pinned) } +
                    deleted.map { coffee.crema.core.LocalShot(it, hasSamples = false, deleted = true) },
                photos = s.beans.filter { BeanImageStore.exists(app, it.id) }.map { it.id },
                beansMissingPhoto = s.beans.filter { it.imageRef != null && !BeanImageStore.exists(app, it.id) }.map { it.id },
                profileMeta = coffee.crema.core.ProfileMeta(pinnedIds.toList(), hiddenIds.toList()),
                recipeMeta = coffee.crema.core.RecipeMeta(HashMap(s.lastRecipeByMethod), s.hiddenBuiltinRecipes.toList()),
                common = currentPrefs().toCommonSettings(),
                visualizerPrefs = visualizer.backupPrefs(),
                totalLitres = deps.totalLitres(),
                activeBeanId = s.activeBeanId,
                // The engine fills this from its marker.
                addedBeans = emptyList(),
            )
        }

        override fun machineBusy(): Boolean = deps.machineBusy()

        override suspend fun cancelTombstones(ids: List<String>): Boolean = visualizer.forgetTombstonesNow(ids)

        override suspend fun putPhotos(plan: coffee.crema.core.RestorePlan, staged: Map<String, java.io.File>): Boolean =
            withContext(Dispatchers.IO) {
                plan.photos.put.all { id ->
                    val file = staged[id] ?: return@all true
                    runCatching { BeanImageStore.put(app, id, file.readBytes()) }.isSuccess
                }
            }

        override suspend fun applyRecipes(plan: coffee.crema.core.RestorePlan, records: coffee.crema.restore.RestoreRecords): Boolean {
            val s = uiState()
            val builtinIds = s.builtinRecipes.map { it.id }.toHashSet()
            val meta = plan.recipeMeta.takeIf { it.action == coffee.crema.core.PlanAction.Apply }
            return deps.restoreRecipes(
                coffee.crema.restore.nextRows(s.brewRecipes.filterNot { it.id in builtinIds }, records.parsed.recipes, plan.recipes) { it.id },
                coffee.crema.restore.nextRows(s.customMethods, records.parsed.customMethods, plan.customMethods) { it.id },
                meta?.defaults,
                meta?.hiddenBuiltins?.toSet(),
            )
        }

        override suspend fun applyLibrary(plan: coffee.crema.core.RestorePlan, records: coffee.crema.restore.RestoreRecords): Boolean {
            editRows(
                { rows ->
                    rows.copy(
                        beans = rows.beans.with(coffee.crema.restore.nextRows(rows.beans, records.parsed.beans, plan.beans) { it.id }),
                        roasters = rows.roasters.with(coffee.crema.restore.nextRows(rows.roasters, records.parsed.roasters, plan.roasters) { it.id }),
                        grinders = rows.grinders.with(coffee.crema.restore.nextRows(rows.grinders, records.parsed.grinders, plan.grinders) { it.id }),
                    ).let { next -> next.copy(beans = next.beans.with(keepingGrinds(next.beans, rows.beans, plan.beansKeepGrinds))) }
                },
                // No bean or grinder is picked for the user: an active one the backup lacks goes, and "none" lasts.
                { st ->
                    st.copy(
                        activeBeanId = if (plan.clearActiveBean) null else st.activeBeanId,
                        activeGrinderId = if (plan.clearActiveGrinder) null else st.activeGrinderId,
                    )
                },
            )
            return library.save(uiState().libraryFile())
        }

        override suspend fun applyProfiles(plan: coffee.crema.core.RestorePlan, records: coffee.crema.restore.RestoreRecords): Boolean {
            val added = plan.profiles.add.toHashSet()
            val incoming = records.parsed.profiles.map { raw -> if (profileIdOf(raw, json) in added) derivedBrewTemp(raw) else raw }
            customProfilesJson = coffee.crema.restore.nextRows(customProfilesJson, incoming, plan.profiles) { profileIdOf(it, json).orEmpty() }
            val meta = plan.profileMeta.takeIf { it.action == coffee.crema.core.PlanAction.Apply }
            if (meta != null) {
                pinnedIds = meta.pinned.toSet()
                hiddenIds = meta.hiddenBuiltins.toSet()
            }
            refreshProfiles()
            var ok = customProfileStore.save(customProfilesJson, ++customProfilesGeneration)
            if (meta != null) {
                ok = pinnedProfileStore.save(pinnedIds) && ok
                ok = hiddenProfileStore.save(hiddenIds) && ok
            }
            return ok
        }

        override suspend fun applyShots(plan: coffee.crema.core.RestorePlan, records: coffee.crema.restore.RestoreRecords): Int? {
            val replace = plan.mode == coffee.crema.core.RestoreMode.Replace
            if (historyStore.usesSql) return applyShotsToStore(plan, records, replace)
            // Series first, with no change to memory.
            val first = coffee.crema.restore.nextShots(historyRows(), records.parsed.shots, plan.shots, replace, ::hasCurve, historyStore::rowFor)
            var failed = historyStore.writeSeriesOf(first.series)
            afterRestoreSeries?.invoke()
            // Then, over the rows as they are now: the legacy list is the file.
            var second = first
            editHistory { rows ->
                second = coffee.crema.restore.nextShots(rows, records.parsed.shots, plan.shots, replace, ::hasCurve, historyStore::rowFor)
                rows.with(second.next)
            }
            // Their heavy parts wait for the save (#310 P4a), staged only now: staged
            // before, they'd read as curves here and the recompute wouldn't fill them.
            second.series.forEach(historyStore::stage)
            if (!persistHistory()) return null
            // A curve the recompute found anew went inline with the rows; its file now, before done.
            val written = first.series.mapTo(HashSet()) { it.id }
            failed += historyStore.writeSeriesOf(second.series.filterNot { it.id in written })
            return failed
        }

        /**
         * Step 7 on `shots.db` (SPEC §7, P3): merge, then remove, through the
         * history seam ([coffee.crema.restore.restoreShots], #310 R1a). Every
         * row the plan leaves is written straight to the store, past the
         * view's cap, incoming rows over local ones with the same id; then one
         * explicit delete of the rows not in the backup. A kill between leaves
         * the union: re-running the restore finishes it. Dismissing the marker
         * instead keeps the union for good (its `runDeletes` skips ids still
         * listed), which loses nothing. The view takes the newest rows.
         */
        private suspend fun applyShotsToStore(plan: coffee.crema.core.RestorePlan, records: coffee.crema.restore.RestoreRecords, replace: Boolean): Int? {
            // Step 0 (M3): a row written after this is never in the wipe.
            restoreSeq = historyStore.restoreBegin()
            return coffee.crema.restore.restoreShots(
                shotsDbRestore, plan, records, replace,
                afterSeries = { afterRestoreSeries?.invoke() },
                beforeRemove = { beforeRestoreRemove?.invoke() },
            )
        }

        override suspend fun applySingletons(plan: coffee.crema.core.RestorePlan, records: coffee.crema.restore.RestoreRecords): Boolean {
            val p = records.parsed
            var ok = true
            if (plan.settings.common == coffee.crema.core.PlanAction.Apply) {
                val extras = plan.settings.extras == coffee.crema.core.PlanAction.Apply
                val target = p.common?.let { c ->
                    val withCommon = currentPrefs().withCommonSettings(c)
                    if (extras && p.qcGrind != null) withCommon.copy(qcGrind = p.qcGrind) else withCommon
                } ?: p.prefs?.takeIf { extras }
                if (target != null) ok = deps.restoreSettings(target) && ok
            }
            if (plan.maintenance == coffee.crema.core.PlanAction.Apply) {
                p.maintenance?.let { ok = deps.restoreMaintenance(it) && ok }
            }
            if (plan.visualizerPrefs == coffee.crema.core.PlanAction.Apply) {
                p.visualizerPrefs?.let { incoming ->
                    // Never widen sharing from a backup: held fields keep this device's value.
                    val guarded = guardRestoredPrefs(visualizer.backupPrefs(), incoming, json)
                    visualizer.restorePrefs(guarded.apply)
                    restoreHeldSharing = guarded.held
                }
            }
            return ok
        }

        override suspend fun runDeletes(deletes: coffee.crema.restore.MarkerDeletes): Boolean {
            // Only over a store that loaded: a corrupt load reads as "not listed" (#325).
            fun loaded(file: String) =
                coffee.crema.persist.Stores.health(java.io.File(app.filesDir, file)) == coffee.crema.persist.StoreHealth.OK
            // Not before the rows are in: until then every id reads as "not listed".
            if (deletes.shots.isNotEmpty()) awaitRows()
            val shotsOk = deletes.shots.isEmpty() || historyStore.storeHealth == coffee.crema.persist.StoreHealth.OK
            val photosOk = deletes.photos.isEmpty() || loaded(LibraryStore.FILE_NAME)
            if (shotsOk && deletes.shots.isNotEmpty()) {
                val listed = allShots().mapTo(HashSet()) { it.id }
                historyStore.deleteSeriesOf(deletes.shots.filterNot { it in listed })
            }
            if (photosOk && deletes.photos.isNotEmpty()) {
                val listedBeans = uiState().beans.mapTo(HashSet()) { it.id }
                withContext(Dispatchers.IO) {
                    deletes.photos.filterNot { it in listedBeans }.forEach { BeanImageStore.beanImageFile(app, it).delete() }
                }
            }
            return shotsOk && photosOk
        }

        override fun pauseDeletes() = historyStore.pauseDeletes()

        override fun resumeDeletes() = historyStore.resumeDeletes()

        override fun changedDirs(): List<java.io.File> =
            listOf(app.filesDir, historyStore.seriesDirectory, BeanImageStore.beanImageFile(app, "x").parentFile!!)
    }

    // ── Free up space (#310 P6a, SPEC §8) ────────────────────────────────────

    /**
     * What Free up space would delete: every unpinned shot dated before
     * [beforeMs], with about the space they take. Null while a cleanup that
     * spares pinned shots may not run ([historySettled]): an unread row may be
     * a pinned one. Reads only: nothing is deleted until [freeUpSpace].
     */
    suspend fun planFreeUp(beforeMs: Long): coffee.crema.history.FreeUpPlan? {
        if (!rowsReady || !historySettled) return null
        // A queued upload or an unsent edit not yet read would look like none (REVIEW-440 R2).
        if (!unsentKnown()) return null
        val older = allShots().filter { it.completedAtMs < beforeMs }
        val unpinned = older.filterNot { it.pinned }
        // A shot with an upload or an edit still to reach Visualizer stays (REVIEW-440 N1, web parity).
        val (kept, doomed) = unpinned.partition(::unsent)
        val ids = doomed.map { it.id }
        return coffee.crema.history.FreeUpPlan(beforeMs, ids, older.count { it.pinned }, historyStore.bytesOf(ids), syncKept = kept.size)
    }

    /** How long Free up space waits for Visualizer's queue to load before it refuses (REVIEW-440 R2). */
    private val UNSENT_READ_WAIT_MS = 10_000L

    /** Whether Visualizer's queue and unsent edits are read, waiting a little for them at launch. */
    private suspend fun unsentKnown(): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(UNSENT_READ_WAIT_MS) { visualizer.awaitUnsentRead() } == true

    /** Whether [row] still has something to send to Visualizer: an upload pending or an edit unsent. */
    private fun unsent(row: ShotRow): Boolean = visualizer.uploadPending(row) || visualizer.hasPendingEdit(row.id)

    /**
     * Why a backup is running now, or null: a build of any kind (a file, Drive,
     * the daily one) or Drive busy. Free up space waits for it, as the walk reads
     * each shot whole as it reaches it, and a shot deleted before that would go
     * in without its curve (REVIEW-440 B1).
     */
    fun backupRunning(): String? = when {
        backupRuns.running() || uiState().backupProgress.isNotEmpty() -> "a backup is being made"
        driveBusy() -> "Google Drive is busy"
        else -> null
    }

    /**
     * Delete what [plan] named, the user's explicit choice: only those ids,
     * and of them only the ones still here, still unpinned and still dated
     * before the plan's date, checked on the rows as they stand. A shot pinned
     * since the preview stays. Bag weights don't change: no history removal
     * credits a bag (SPEC §8). The Visualizer copies stay where they are.
     * A shot the last backup left out, or wrote without its curve or profile,
     * stays too, read as the delete runs (REVIEW-437 B1): there is no
     * "delete anyway". Returns how many went, and how many of the named ones
     * the backup's gaps kept.
     */
    suspend fun freeUpSpace(
        plan: coffee.crema.history.FreeUpPlan,
        notInBackup: Set<String> = emptySet(),
    ): coffee.crema.history.FreeUpResult {
        if (!rowsReady || !historySettled) return coffee.crema.history.FreeUpResult(0, refused = "your shot history isn't fully read yet")
        // Before the backup check: nothing may suspend between it and the rows' removal.
        if (!unsentKnown()) return coffee.crema.history.FreeUpResult(0, refused = "Visualizer's sync state isn't read yet")
        // The UI isn't the only guard: a walk still running would write these shots without their curves.
        backupRunning()?.let { return coffee.crema.history.FreeUpResult(0, refused = it) }
        if (plan.ids.isEmpty()) return coffee.crema.history.FreeUpResult(0)
        val backupKept = plan.ids.count { it in notInBackup }
        val named = plan.ids.filterNot { it in notInBackup }.toHashSet()
        // Pinned and unsent are read on the rows as they stand: a shot pinned, queued or edited since the preview stays.
        fun goes(row: ShotRow) = row.id in named && !row.pinned && row.completedAtMs < plan.beforeMs && !unsent(row)
        // Every row the store holds (#310 A3b), and gone from every reader at once.
        val ids = historyRows().filter(::goes).mapTo(HashSet()) { it.id }
        val syncKept = plan.ids.count { id -> id in named && id !in ids && findShot(id)?.let(::unsent) == true }
        if (ids.isEmpty()) return coffee.crema.history.FreeUpResult(0, backupKept, syncKept)
        historyStore.forget(ids)
        persistHistory()
        historyStore.delete(ids)
        // The space is there now: a curve held in memory after a failed write goes to disk (REVIEW-440 N2).
        if (historyUnsaved.isNotEmpty()) persistHistory()
        checkStorage()
        return coffee.crema.history.FreeUpResult(ids.size, backupKept, syncKept)
    }

    /**
     * A file backup for Free up space, built in the caller's scope (the VM's,
     * so closing the dialog doesn't cancel it): the file and the shots it
     * doesn't hold whole, or null when it didn't finish (REVIEW-440 B1).
     */
    suspend fun buildFreeUpBackup(): BackupZip? = buildBackupZip(BackupKind.FILE)

    /** Write the built backup [file] to [uri] (the save picker's), then release it. True when it's written. */
    suspend fun saveBackupFile(uri: Uri, file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            copyBackupToDocument(
                file,
                open = { mode -> app.contentResolver.openOutputStream(uri, mode) },
                delete = { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) },
            )
        } finally {
            releaseBackupFile(file)
        }
    }

    /** Test seam: runs as a backup's walk starts, after its snapshot (REVIEW-440's probe holds it there). */
    @androidx.annotation.VisibleForTesting
    internal var backupWalkStarts: () -> Unit = {}

    /** Test seam: how the backup writes a shot's line ([streamBackupZip]'s default in the app). */
    @androidx.annotation.VisibleForTesting
    internal var backupShotJson: (StoredShot) -> String = { coffee.crema.visualizer.wireShotJson(it, forBackup = true).toString() }

    /** Remove a stored shot from the local history. Persisted. */
    fun deleteShot(id: String) {
        // Gone from every reader at once.
        historyStore.forget(listOf(id))
        // The one way a row leaves the store: named (#310 P3a). The legacy
        // backend's list save goes first, so its series go only after.
        scope.launch {
            persistHistory()
            historyStore.delete(listOf(id))
        }
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
        // Gone from every reader at once; the disk half erases the store ([eraseStoredData]).
        historyStore.forget(historyRows().map { it.id })
        editRows(
            { it.copy(beans = it.beans.with(emptyList()), roasters = it.roasters.with(emptyList()), grinders = it.grinders.with(emptyList())) },
            { it.copy(activeBeanId = null, activeProfileId = null, activeGrinderId = null, profileCleared = false) },
        )
        // Back to a fresh install's selection.
        refreshProfiles(seedEmpty = true)
    }

    /**
     * The disk half of [eraseAll]: delete each library store's file and every
     * copy of it (`.bak`, `.tmp`, `.corrupt-*`) rather than saving an empty
     * value, which would keep the full old file as `.bak` (final review 05
     * N-1), plus the bag photos and the last-shot diagnostic (review 05 P2-1).
     */
    suspend fun eraseStoredData(): List<String> {
        library.erase()
        val historyStayed = historyStore.erase()
        customProfileStore.erase()
        pinnedProfileStore.erase()
        hiddenProfileStore.erase()
        withContext(Dispatchers.IO) {
            BeanImageStore.deleteAll(app)
            coffee.crema.diag.ShotDiag.clear(app)
        }
        return historyStayed
    }

    // ── Visualizer sync hooks (pull/backfill/synced stamps) ──────────────────

    /**
     * Insert pulled Visualizer stubs into the history (dedup by id, newest
     * first) and persist. A pull never removes a row ([mergePulled]), so this
     * answers the ids a full history refused.
     */
    fun insertPulledShots(stubs: List<StoredShot>): Set<String> {
        if (stubs.isEmpty()) return emptySet()
        var kept = emptySet<String>()
        var present = emptySet<String>()
        // Every row the store holds, edits not yet written included (#310 A3a):
        // a pull of one of them adds no copy.
        val held = historyRows().mapTo(HashSet()) { it.id }
        // A pulled shot comes whole (its curve and recipe): they wait for the save.
        val incoming = stubs.filterNot { it.id in held }.distinctBy { it.id }.map(::staged)
        editHistory { rows ->
            val merge = mergePulled(incoming, rows, cap = viewCap)
            kept = merge.kept.mapTo(HashSet()) { it.id }
            present = rows.mapTo(HashSet()) { it.id } + held
            rows.with(merge.rows)
        }
        // A pull the full view refused isn't written: its staged parts go too.
        historyStore.unstage(incoming.map { it.id }.filterNot { it in kept })
        // On history.db the core stores a pulled duplicate once (N8, #310 A5).
        historyStore.markPulled(kept)
        saveHistory()
        return stubs.mapTo(HashSet()) { it.id } - kept - present
    }

    /**
     * Apply what a Visualizer pull may change on a bound shot (the core's
     * `merge_remote_shot`), in one write on the rows as they stand: tags it
     * adds, a barista into a blank one, the curve and recipe when the row has
     * none, and the remote version last. Persisted when anything changed.
     */
    fun applyRemoteShot(localId: String, patch: ShotPatch, pulled: PulledParts) {
        // Before the rows load, every shot looks curve-less: a pulled series
        // would replace the shot's own (the pull awaits [awaitRows]). The store
        // says whether the shot has its curve and its profile (#310 P4a).
        val backfill = patch.backfillSamples == true && rowsReady && !historyStore.hasCurve(localId) && pulled.samples.isNotEmpty()
        val fillProfile = patch.fillProfile == true && pulled.profile != null && !historyStore.hasProfile(localId)
        // The curve and recipe wait for the save; the row takes the summary they give.
        val filled = if (backfill || fillProfile) {
            findShot(localId)?.let { row ->
                historyStore.stage(
                    row.withHeavy(
                        // The whole pulled series (F10).
                        samples = if (backfill) pulled.samples else emptyList(),
                        profile = if (fillProfile) pulled.profile else null,
                        brewSeries = null,
                    ),
                )
            }
        } else {
            null
        }
        val apply = { it: ShotRow ->
            var next = it
            patch.tags?.let { tags -> next = next.copy(tags = tags) }
            patch.barista?.takeIf { next.barista.isNullOrBlank() }?.let { b -> next = next.copy(barista = b) }
            if (filled != null && backfill) {
                next = next.copy(
                    summary = filled.summary,
                    durationMs = if (next.durationMs > 0) next.durationMs else pulled.durationMs,
                )
            }
            patch.remoteVersion?.let { v -> next = next.copy(visualizerUpdatedAt = v) }
            next
        }
        val before = findShot(localId) ?: return
        val after = historyStore.update(localId, apply) ?: return
        if (after == before && filled == null) return
        saveHistory()
    }

    /** Stamp a successful Decent shot-history upload onto the local shot (#84). Persisted. */
    fun markShotDecentUploaded(localId: String, decentId: String, machine: ShotMachine? = null) {
        editShot(localId) { shot ->
            when {
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
        }
    }

    /** Stamp a successful Visualizer upload onto the local shot. Persisted. */
    fun markShotSynced(localId: String, visualizerId: String) {
        // A row past the view's cap is still here (#354 review): it takes the id too.
        editShot(localId) { it.copy(visualizerId = visualizerId) }
    }

    // ── Bean library ────────────────────────────────────────────────────────

    /** Persist the current bean library (beans + roasters + active selection). */
    fun persistLibrary() {
        val lib = uiState().libraryFile()
        scope.launch { library.save(lib) }
    }

    /** [persistLibrary], awaited: the remote → standalone commit saves the
     *  adopted bean before the role switch starts (spec §7.5, I3). */
    suspend fun persistLibraryNow() {
        library.save(uiState().libraryFile())
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
        // A tombstone (#488 review SF1) is deleted: a bag is never filed under it by name.
        val existing = roasters.firstOrNull { it.deletedAt == null && it.name.equals(trimmed, ignoreCase = true) }
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
        // Grinder records (#488): a Crema file carries them; others name grinders only.
        val wireGrinders = decodeList("grinders", coffee.crema.core.Grinder.serializer())

        val s = uiState()
        val merge = mergeBeanImport(wireBeans, wireRoasters, s.beans, s.roasters, wireGrinders = wireGrinders, libGrinders = s.grinders)
        val newBeans = merge.newBeans
        val newRoasters = merge.newRoasters
        val beanIdRemap: Map<String, String> = merge.beanIdRemap

        // Shots — the core has already mapped BC brews (espresso-only,
        // metadata, no telemetry); land them as history rows, web parity.
        // Re-import dedupe: a BC brew's unix-second timestamp is stable
        // across exports, so (completedAt, bean name) identifies a row —
        // BC ids aren't carried on the shot, and our own ids are minted.
        // Imported rows carry no telemetry; staged all the same, so the save writes them whole.
        val importedShots = buildImportedShots(plan, beanIdRemap).map(::staged)

        if (newBeans.isEmpty() && newRoasters.isEmpty() && merge.updatedBeans.isEmpty() &&
            merge.updatedRoasters.isEmpty() && importedShots.isEmpty() &&
            merge.newGrinders.isEmpty() && merge.updatedGrinders.isEmpty()
        ) {
            notify(if (merge.skipped > 0u) "Already imported — nothing new to add" else "No beans found in that file")
            return
        }
        // Apply onto the CURRENT state (not the snapshot `s`) so a concurrent
        // high-rate telemetry / scale update isn't clobbered (issue 11).
        var mergedShots = HistoryMerge<ShotRow>(emptyList(), emptyList(), 0)
        // The merge again, on the rows as they stand: the one above read them a
        // moment ago, to say whether there was anything to import.
        var landed = merge
        editRows(
            { rows ->
                landed = mergeBeanImport(wireBeans, wireRoasters, rows.beans, rows.roasters, wireGrinders = wireGrinders, libGrinders = rows.grinders)
                rows.copy(
                    beans = rows.beans.with(rows.beans.replacingById(landed.updatedBeans) { it.id } + landed.newBeans),
                    roasters = rows.roasters.with(rows.roasters.replacingById(landed.updatedRoasters) { it.id } + landed.newRoasters),
                    grinders = rows.grinders.with(rows.grinders.replacingById(landed.updatedGrinders) { it.id } + landed.newGrinders),
                )
            },
            { it.copy(activeBeanId = it.activeBeanId ?: landed.newBeans.firstOrNull()?.id) },
        )
        persistLibrary()
        // The shots into the history store's rows as they stand (#310 A3b).
        if (importedShots.isNotEmpty()) {
            editHistory { rows -> rows.with(mergeHistory(importedShots, rows, cap = viewCap).also { mergedShots = it }.rows) }
        }
        val landing = mergedShots.kept.mapTo(HashSet()) { it.id }
        historyStore.unstage(importedShots.map { it.id }.filterNot { it in landing })
        if (importedShots.isNotEmpty()) saveHistory()
        // The grinders the import names (#488): its bags' old grinder fields
        // become records and saved grinds (the library migration), and its
        // shots' grinder names get records too.
        addGrindersNamed(mergedShots.kept.mapNotNull { it.grinderModel })
        migrateGrinderLibrary()
        val bn = landed.newBeans.size; val rn = landed.newRoasters.size; val sn = mergedShots.kept.size; val un = landed.updatedBeans.size
        updateUi { it.copy(
            status = buildString {
                append("Imported $bn bean${if (bn == 1) "" else "s"} · $rn roaster${if (rn == 1) "" else "s"}")
                if (un > 0) append(" · updated $un bean${if (un == 1) "" else "s"}")
                if (sn > 0) append(" · $sn shot${if (sn == 1) "" else "s"}")
                append(droppedNotice(mergedShots.dropped))
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
    @androidx.annotation.VisibleForTesting
    internal fun buildImportedShots(plan: JsonObject, beanIdRemap: Map<String, String>): List<StoredShot> {
        val entries = plan["shots"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        val existingKeys = allShots()
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
                    grindSetting = coffee.crema.beans.grindTextOf(meta?.get("grinderSetting") as? JsonPrimitive),
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
                    // The core's 0: an imported brew never debited its bag.
                    bagDebitG = bean?.let { stored.num("bagDebit")?.toFloat() },
                ),
            )
        }
        return out
    }

    /** Open a bean in the editor (the `bean-edit` route reads this). */
    fun startEditBean(id: String) {
        editedBag = null
        updateUi { it.copy(editingBeanId = id, draftBean = null, editingBeanGone = null) }
    }

    /** The stored bag the editor is on, as last seen (PLAN follow-up 10). */
    private var editedBag: Bean? = null

    /** A bag this device deleted itself: its editor going isn't news. */
    private var deletedHere: String? = null

    /**
     * Watch the bag the editor is on (PLAN follow-up 10): when it goes from the
     * library while open, deleted elsewhere (a sync, another device), the
     * editor says its changes weren't saved instead of losing the draft
     * silently. A new bag's draft and this device's own delete are not news.
     */
    fun watchEditedBag(st: MainUiState) {
        val id = st.editingBeanId
        if (id == null || st.draftBean?.id == id) {
            editedBag = null
            return
        }
        val stored = st.beans.firstOrNull { it.id == id }
        if (stored != null) {
            editedBag = stored
            return
        }
        val held = editedBag?.takeIf { it.id == id } ?: return
        editedBag = null
        if (deletedHere == id || st.editingBeanGone != null) return
        editedBagGone(id, held.name)
    }

    /** The bag [id] the editor is on is gone: the editor and a message say its changes weren't saved (PLAN follow-up 10). */
    private fun editedBagGone(id: String, bagName: String) {
        val name = bagName.trim().ifEmpty { "This bag" }
        val told = uiState().editingBeanGone != null
        updateUi { if (it.editingBeanId == id) it.copy(editingBeanGone = name) else it }
        if (!told) notify("“$name” was deleted elsewhere; your changes weren’t saved")
    }

    /** Start a brand-new bean in the full editor — stash a blank draft and point
     *  the editor at it. Added to the library only on Save (via [updateBean]). */
    fun startNewBean() {
        val draft = newBean(name = "", roasterId = null, roastLevel = null, roastedOn = null, nowMs = System.currentTimeMillis())
        editedBag = null
        updateUi { it.copy(draftBean = draft, editingBeanId = draft.id, editingBeanGone = null) }
    }

    /**
     * Apply edits to an existing bean (the editor's Save). Resolves the roaster
     * by name (find-or-create), stamps `updatedAt`, and persists. [opened] is the
     * bag as the form opened on it: see [applyBeanDraft].
     */
    fun updateBean(
        id: String,
        roasterName: String,
        roasterPick: coffee.crema.core.CataloguePickRoaster? = null,
        opened: Bean? = null,
        transform: (Bean) -> Bean,
    ) {
        val now = System.currentTimeMillis()
        var saved = false
        var tombstoned = false
        var tombName = ""
        editRows(
            { rows ->
                // Resolve from the library, or from a new-bean draft (the "Add bean"
                // → full-editor Save path — the bean isn't in [beans] yet).
                val current = rows.beans.firstOrNull { it.id == id }
                // A tombstone (a Visualizer-bound bag deleted while the editor was
                // open, #512) stays as the delete left it: stripped, never edited
                // (REVIEW-516 follow-up 1). The edits aren't saved, and the editor says so.
                tombstoned = current?.deletedAt != null
                if (tombstoned) {
                    tombName = current?.name.orEmpty()
                    saved = false
                    return@editRows rows
                }
                val draft = uiState().draftBean?.takeIf { it.id == id }
                val existing = current ?: draft
                saved = existing != null
                if (existing == null) return@editRows rows
                // A catalogue pick's roaster (the editor only passes it while the
                // roaster input still names it): write the updated existing row, or
                // create the seeded one, and file the bag under it. Otherwise resolve
                // the typed name (find-or-create) as before.
                val (roasterId, roasters) = if (roasterPick != null) {
                    val row = coffee.crema.beans.pickedRoasterRow(roasterPick) { name -> newRoaster(name, now) }
                    val held = rows.roasters.firstOrNull { it.id == row.id && it.deletedAt == null }
                    if (held == null) {
                        // A seed is created; a matched row that vanished since → by name.
                        if (roasterPick.isNew) row.id to (rows.roasters + row) else resolveRoaster(roasterName, rows.roasters, now)
                    } else {
                        // Only the catalogue fields; anything else edited since stays.
                        val merged = held.copy(
                            name = row.name, website = row.website, country = row.country,
                            catalogueRoasterId = row.catalogueRoasterId, updatedAt = now,
                        )
                        row.id to rows.roasters.map { if (it.id == row.id) merged else it }
                    }
                } else {
                    // The name it already files under: keep that roaster, rather than
                    // whichever row of that name comes first.
                    val filed = current?.roasterId?.let { rid -> rows.roasters.firstOrNull { it.id == rid } }
                    when {
                        // A bag a "this device only" delete left under a roaster
                        // tombstone shows no roaster here; left blank, it keeps
                        // that link, so the push never clears it on Visualizer
                        // (#488 review SF1).
                        filed != null && filed.deletedAt != null && roasterName.isBlank() -> filed.id to rows.roasters.toList()
                        filed != null && filed.deletedAt == null && filed.name.equals(roasterName.trim(), ignoreCase = true) ->
                            filed.id to rows.roasters.toList()
                        else -> resolveRoaster(roasterName, rows.roasters, now)
                    }
                }
                // The form holds what the bag was when it opened: only what the user
                // changed lands, so a debit or a sync since then stands.
                val edited = when {
                    current == null -> transform(existing)
                    opened == null -> transform(current)
                    else -> {
                        val draft = transform(opened)
                        val merged = applyBeanDraft(current, draft, opened, json)
                        // The grind rows merge row by row (#488, the core's rule), so
                        // a grind auto-saved while the editor was open survives.
                        if (draft.grinds == opened.grinds) merged
                        else merged.copy(grinds = runCatching { coffee.crema.beans.mergeBagGrinds(opened, draft, current, now) }.getOrElse { draft.grinds })
                    }
                }
                val updated = edited.copy(roasterId = roasterId, updatedAt = now)
                rows.copy(
                    beans = rows.beans.with(if (current == null) rows.beans + updated else rows.beans.map { if (it.id == id) updated else it }),
                    roasters = rows.roasters.with(roasters),
                )
            },
            { st -> if (saved) st.copy(draftBean = null) else st },
        )
        if (saved) persistLibrary()
        if (tombstoned) editedBagGone(id, uiState().editingBeanGone ?: tombName)
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
            editRows(
                { rows -> rows.copy(beans = rows.beans.mapped { if (it.id == beanId) it.copy(imageRef = ref, updatedAt = now) else it }) },
                { st -> st.copy(draftBean = st.draftBean?.let { if (it.id == beanId) it.copy(imageRef = ref, updatedAt = now) else it }) },
            )
            persistLibrary()
        }
    }

    /** Remove a bean's bag photo — delete the file + clear `imageRef`. Persisted. */
    fun clearBeanImage(beanId: String) {
        scope.launch {
            withContext(Dispatchers.IO) { BeanImageStore.delete(app, beanId) }
            val now = System.currentTimeMillis()
            editRows(
                { rows -> rows.copy(beans = rows.beans.mapped { if (it.id == beanId) it.copy(imageRef = null, updatedAt = now) else it }) },
                { st -> st.copy(draftBean = st.draftBean?.let { if (it.id == beanId) it.copy(imageRef = null, updatedAt = now) else it }) },
            )
            persistLibrary()
        }
    }

    // ── Grinders and bag grinds (#488) ───────────────────────────────────────

    /**
     * The library migration (#488): grinder records from the old grinder
     * names, and each bag's old `grinderSetting` as a saved grind. Runs once
     * the library and the settings have loaded (the settings' `grinderModel`
     * is one of its inputs), and after every import and restore. Idempotent:
     * with grinder records (archived ones count) its first step doesn't run
     * again, so a removed grinder stays removed. Only `grinds` is taken from
     * the bags it returns, and only onto bags that still have none.
     */
    fun migrateGrinderLibrary(now: Long = System.currentTimeMillis()) {
        val s = uiState()
        // Only over a library and settings that read (REVIEW-CORE H5): the core runs nothing otherwise.
        val settingsFile = java.io.File(app.filesDir, coffee.crema.settings.SettingsStore.FILE_NAME)
        val gate = coffee.crema.beans.migrationGate(
            library = coffee.crema.persist.Stores.health(java.io.File(app.filesDir, LibraryStore.FILE_NAME)),
            settings = coffee.crema.persist.Stores.health(settingsFile),
            settingsKeptAside = coffee.crema.persist.SafeFile.keptAside(settingsFile).isNotEmpty(),
            grinderModel = s.grinderModel,
        )
        val out = runCatching { coffee.crema.beans.migrateGrinderLibrary(s.grinders, s.beans, s.grinderModel, gate, now) }.getOrElse {
            appendLog("Grinder migration failed (runs again next load): ${it.message}")
            return
        }
        out.skipped?.let { why ->
            appendLog("Grinder migration skipped (runs again next load): ${if (why == coffee.crema.core.MigrationSkip.LibraryUnreadable) "the bean library" else "the settings"} couldn't be read")
            return
        }
        val pointerChanges = out.grindersMigrated && s.activeGrinderId != out.activeGrinderId
        if (out.newGrinders.isEmpty() && out.updatedBeans.isEmpty() && !pointerChanges) return
        val grinds = out.updatedBeans.associate { it.id to it.grinds }
        editRows(
            { rows ->
                val have = rows.grinders.mapTo(HashSet()) { it.id }
                rows.copy(
                    grinders = rows.grinders.with(rows.grinders + out.newGrinders.filter { it.id !in have }),
                    beans = rows.beans.mapped { b -> if (b.grinds == null && b.id in grinds) b.copy(grinds = grinds[b.id]) else b },
                )
            },
            // The pointer only when the grinders step ran: there were no records for it to name.
            { st -> if (out.grindersMigrated) st.copy(activeGrinderId = out.activeGrinderId) else st },
        )
        persistLibrary()
        if (out.newGrinders.isNotEmpty()) appendLog("Grinders: ${out.newGrinders.size} added from your grinder names")
    }

    /** Grinder records for [names] (an import's shots) that no grinder has yet. Persisted. */
    private fun addGrindersNamed(names: List<String>, now: Long = System.currentTimeMillis()) {
        if (names.none { it.isNotBlank() }) return
        var added = emptyList<coffee.crema.core.Grinder>()
        editRows({ rows ->
            added = coffee.crema.beans.grindersForNames(rows.grinders, names.distinct(), now)
            rows.copy(grinders = rows.grinders.with(rows.grinders + added))
        }, { it })
        if (added.isNotEmpty()) persistLibrary()
    }

    /** [grinder] into the records, replacing the row with its id. */
    private fun upsertGrinder(grinder: coffee.crema.core.Grinder, rest: (MainUiState) -> MainUiState = { it }) {
        editRows(
            { rows ->
                val next = if (rows.grinders.any { it.id == grinder.id }) {
                    rows.grinders.map { if (it.id == grinder.id) grinder else it }
                } else {
                    rows.grinders + grinder
                }
                rows.copy(grinders = rows.grinders.with(next))
            },
            rest,
        )
        persistLibrary()
    }

    /**
     * Add a grinder from the type-or-pick box (Devices, the Brew picker, the
     * bag editor): catalogue entry [catalogueId], or the typed [name]. A name
     * or entry an archived record has brings that record back, same id, so
     * its bag grinds come back. Returns the live record, or null.
     */
    fun addGrinder(name: String, catalogueId: String?, now: Long = System.currentTimeMillis()): coffee.crema.core.Grinder? {
        val add = runCatching { coffee.crema.beans.addGrinder(uiState().grinders, name, catalogueId, now) }.getOrElse {
            appendLog("Add grinder failed: ${it.message}")
            return null
        } ?: return null
        if (add.kind == coffee.crema.core.GrinderAddKind.Existing) {
            // Already one of yours: the message offers a second one of the model (follow-up 18).
            val offer = AddAnotherOffer(name, catalogueId, add.grinder.name, ++addAnotherSeq)
            updateUi { it.copy(addAnotherOffer = offer) }
            return add.grinder
        }
        return landGrinderAdd(add)
    }

    private var addAnotherSeq = 0L

    /** A new or revived grinder lands: stored, and Brew's when none is active. */
    private fun landGrinderAdd(add: coffee.crema.core.GrinderAdd): coffee.crema.core.Grinder {
        upsertGrinder(add.grinder)
        if (add.kind == coffee.crema.core.GrinderAddKind.Revived) notify("${add.grinder.name} is back, with its saved grinds")
        // With no grinder active, the one just added becomes Brew's (review 499):
        // on the device that owns the session; a remote's pick goes to its hub.
        val s = uiState()
        if (s.mirror == null && s.grinders.activeGrinder(s.activeGrinderId) == null) {
            updateUi { it.copy(activeGrinderId = add.grinder.id) }
            onGrinderSelected(add.grinder.id)
            pushConfig()
        }
        return add.grinder
    }

    /**
     * "Add another" on the offer [seq] (#488 follow-up 18, core
     * `add_another_grinder`): an archived grinder of that model comes back,
     * else a new record "‹name› 2" with its own id and the same preset.
     * Returns the record, or null when the offer has gone.
     */
    fun addAnotherGrinder(seq: Long, now: Long = System.currentTimeMillis()): coffee.crema.core.Grinder? {
        val offer = uiState().addAnotherOffer?.takeIf { it.seq == seq } ?: return null
        updateUi { st -> if (st.addAnotherOffer?.seq == seq) st.copy(addAnotherOffer = null) else st }
        val add = runCatching { coffee.crema.beans.addAnotherGrinder(uiState().grinders, offer.name, offer.catalogueId, now) }.getOrElse {
            appendLog("Add grinder failed: ${it.message}")
            return null
        } ?: return null
        if (add.kind == coffee.crema.core.GrinderAddKind.Existing) return add.grinder
        val out = landGrinderAdd(add)
        if (add.kind == coffee.crema.core.GrinderAddKind.Created) notify("Added ${out.name}")
        return out
    }

    /** The "Add another" offer [seq] went without the action. */
    fun consumeAddAnotherOffer(seq: Long) {
        updateUi { st -> if (st.addAnotherOffer?.seq == seq) st.copy(addAnotherOffer = null) else st }
    }

    /**
     * The bag editor's "Add a grinder" pick, staged in its draft (FINAL S7,
     * core `stage_grinder_pick`): nothing is stored and the active grinder
     * stays until Save. Null when the pick names nothing.
     */
    fun stageGrinderPick(pick: GrinderPick, staged: List<coffee.crema.core.Grinder>, now: Long = System.currentTimeMillis()): coffee.crema.core.StagedGrinderPick? {
        val (kind, id, name) = when (pick) {
            is GrinderPick.Owned -> Triple(coffee.crema.core.GrinderMatchKind.Owned, pick.id, "")
            is GrinderPick.Catalogue -> Triple(coffee.crema.core.GrinderMatchKind.Catalogue, pick.id, "")
            is GrinderPick.Archived -> Triple(coffee.crema.core.GrinderMatchKind.Archived, pick.id, "")
            is GrinderPick.Create -> Triple(null, "", pick.name)
        }
        val input = coffee.crema.core.GrinderPickInput(grinders = uiState().grinders, staged = staged, kind = kind, id = id, name = name)
        return runCatching { coffee.crema.beans.stageGrinderPick(input, now) }.getOrElse {
            appendLog("Add grinder failed: ${it.message}")
            null
        }
    }

    /**
     * The bag editor's Save of its [staged] grinders, after the bag [beanId]
     * is stored (core `staged_grinders_on_save`): the grinders its rows still
     * name are stored (an archived one restored from the row as stored now),
     * and with no grinder active the bag's starred one, else the first picked,
     * becomes Brew's (review 499), on the device that owns the session.
     */
    fun saveStagedGrinders(beanId: String, staged: List<coffee.crema.core.Grinder>, now: Long = System.currentTimeMillis()) {
        if (staged.isEmpty()) return
        val s = uiState()
        val input = coffee.crema.core.StagedGrindersInput(
            grinders = s.grinders,
            staged = staged,
            bag = s.beans.firstOrNull { it.id == beanId },
            activeGrinderId = s.activeGrinderId,
        )
        val out = runCatching { coffee.crema.beans.stagedGrindersOnSave(input, now) }.getOrElse {
            appendLog("Saving the bag's grinders failed: ${it.message}")
            return
        }
        if (out.grinders.isNotEmpty()) {
            editRows(
                { rows -> rows.copy(grinders = rows.grinders.with(rows.grinders.replacingById(out.grinders) { it.id } + out.grinders.filter { g -> rows.grinders.none { it.id == g.id } })) },
                { it },
            )
            persistLibrary()
            out.grinders.filter { g -> s.grinders.any { it.id == g.id } }.forEach { notify("${it.name} is back, with its saved grinds") }
        }
        // A remote's pick goes to its hub: the pointer only on the device that owns the session.
        val active = out.activeGrinderId?.value
        if (active != null && uiState().mirror == null) {
            updateUi { it.copy(activeGrinderId = active) }
            onGrinderSelected(active)
            pushConfig()
        }
    }

    /**
     * Devices → Grinder "Remove": archive [id]. Shots and bag grinds keep it;
     * the active one is cleared (Brew shows "No grinder", review F5).
     */
    fun archiveGrinder(id: String, now: Long = System.currentTimeMillis()) {
        val g = uiState().grinders.firstOrNull { it.id == id } ?: return
        val out = runCatching { coffee.crema.beans.archiveGrinder(g, uiState().activeGrinderId, now) }.getOrElse {
            appendLog("Remove grinder failed: ${it.message}")
            return
        }
        val wasActive = uiState().activeGrinderId == id
        upsertGrinder(out.grinder) { st -> if (st.activeGrinderId == id) st.copy(activeGrinderId = out.activeGrinderId) else st }
        if (wasActive) pushConfig()
        notify("${g.name} removed. Its saved grinds stay on your bags; restore it from Archived.")
    }

    /** Devices → Grinder "Archived" → Restore: owned again, same id. */
    fun restoreGrinder(id: String, now: Long = System.currentTimeMillis()) {
        val g = uiState().grinders.firstOrNull { it.id == id } ?: return
        val back = runCatching { coffee.crema.beans.restoreGrinder(g, now) }.getOrElse {
            appendLog("Restore grinder failed: ${it.message}")
            return
        }
        upsertGrinder(back)
    }

    /**
     * Devices → Grinder edit (name, scale, preset): the fields changed
     * ([edited] against [base], the row the form opened on) onto the row as
     * stored. Refused (false) when the scale isn't usable.
     */
    fun saveGrinderEdit(base: coffee.crema.core.Grinder, edited: coffee.crema.core.Grinder, now: Long = System.currentTimeMillis()): Boolean {
        coffee.crema.beans.scaleRefusal(edited.scale.min, edited.scale.max, edited.scale.step, edited.scale.unit)?.let { notify(it); return false }
        if (edited.name.isBlank()) { notify("Enter a name"); return false }
        val current = uiState().grinders.firstOrNull { it.id == base.id } ?: return false
        val merged = runCatching { coffee.crema.beans.mergeGrinderEdit(base, edited.copy(name = edited.name.trim()), current, now) }.getOrElse {
            appendLog("Save grinder failed: ${it.message}")
            return false
        }
        upsertGrinder(merged)
        if (uiState().activeGrinderId == base.id) pushConfig()
        return true
    }

    /** The bag's newest grind per grinder, from the history this device holds. */
    fun lastGrindsForBag(beanId: String): List<coffee.crema.core.LastGrind> =
        coffee.crema.beans.lastGrindsForBag(beanId, historyStore.rows())

    /**
     * A grinder was picked (the commit of `SetActiveGrinder`, here or relayed
     * from a remote): Brew's grind takes the pick order (the bag's saved grind
     * for it, else the newest shot on the bag with it, else as it is; review F9).
     */
    fun onGrinderSelected(id: String?) {
        val s = uiState()
        val bag = s.activeBag()
        val grind = bag?.let {
            runCatching {
                coffee.crema.beans.resolveGrinderSwitch(
                    coffee.crema.core.GrinderSwitchInput(bag = it, grinderId = id, grinders = s.grinders, lastGrinds = lastGrindsForBag(it.id)),
                )
            }.onFailure { e -> appendLog("Grinder switch failed: ${e.message}") }.getOrNull()
        }
        // Nothing saved and no shot with it: the grind stays as it is (the core's pick order).
        // A text grind ("2.1.3") is Brew's grind too: a pick never clears it.
        grind?.let { setGrindDial(it) }
        persistLibrary()
    }

    /** The one Undo of a bag selection's change (review F6): the selection as applied. */
    private data class BagSelectUndo(val seq: Long, val selection: coffee.crema.core.BagSelection)

    private var bagSelectUndo: BagSelectUndo? = null
    private var bagSelectSeq = 0L

    /**
     * A bag was selected on Brew (the commit of `SetActiveBean`, here or
     * relayed): the core resolves everything else the pick changes (the
     * linked profile, the starred grinder, its grind) into one change, shown
     * in one message with one Undo (review F6). No message when nothing
     * besides the bag changed. Replaces the old linked-profile auto-load.
     * [activeBeanIdBefore] is the bag selected before this pick: picking the
     * one already selected changes nothing (FINAL N7), so an unsaved grind on
     * Brew stays.
     */
    fun onBagSelected(beanId: String, activeBeanIdBefore: String?) {
        val s = uiState()
        val bag = s.beans.firstOrNull { it.id == beanId } ?: return
        val linked = bag.linkedProfileId
        val missing = linked != null && s.profiles.none { it.id == linked }
        if (missing && beanId != activeBeanIdBefore) notify("${bag.name}’s linked profile no longer exists")
        val resolved = runCatching {
            coffee.crema.beans.resolveBagSelection(
                coffee.crema.core.BagSelectInput(
                    bag = bag,
                    activeProfileId = s.activeProfileId,
                    linkedProfileMissing = missing,
                    linkedProfileName = linked?.let { id -> s.profiles.firstOrNull { it.id == id }?.name },
                    // The mid-shot rule stays below until this shell shows the
                    // core's message and notices (batch 6 Android).
                    shotInProgress = false,
                    activeGrinderId = s.activeGrinderId,
                    grind = s.brewGrindView().value,
                    grinders = s.grinders,
                    lastGrinds = lastGrindsForBag(bag.id),
                    activeBeanId = activeBeanIdBefore,
                ),
            )
        }.getOrElse {
            appendLog("Bag selection failed: ${it.message}")
            return
        }
        var change = resolved.change
        var undo = resolved.undo
        // Not mid-extraction: loading a profile reseeds the brew dials.
        val profileName = change.profileId?.value?.let { id -> s.profiles.firstOrNull { it.id == id }?.name }
        if (change.profileId != null && s.shotInProgress) {
            notify("Shot in progress — “${profileName ?: "the linked profile"}” not loaded")
            change = change.copy(profileId = null)
            undo = undo.copy(profileId = null)
        }
        if (change.profileId == null && change.grinderId == null && change.grind == null) return
        applySelectionChange(change)
        val grinderAfter = uiState().activeGrinder()
        val parts = buildList {
            if (change.profileId != null) add("${profileName ?: "linked"} profile")
            val grindLabel = change.grind?.value?.let { coffee.crema.beans.formatGrind(it, grinderAfter?.scale) }
            when {
                change.grinderId != null -> add(listOfNotNull(resolved.grinderName ?: grinderAfter?.name ?: "No grinder", grindLabel?.let { "at $it" }).joinToString(" "))
                grindLabel != null -> add("grind $grindLabel")
            }
        }
        val message = "${bag.name}: ${parts.joinToString(", ")}"
        appendLog(message)
        val seq = ++bagSelectSeq
        bagSelectUndo = BagSelectUndo(seq, resolved.copy(change = change, undo = undo))
        updateUi { it.copy(bagSelectUndo = UndoNotice(message, seq)) }
    }

    /** Apply [change]'s parts: the profile (none clears it), the grinder pointer, Brew's grind. */
    private fun applySelectionChange(change: coffee.crema.core.SelectionChange) {
        change.profileId?.let { to ->
            val id = to.value
            if (id != null) {
                setActiveProfile(id)
            } else {
                updateUi { it.withActiveProfileId(null) }
                onProfileCleared()
            }
        }
        change.grinderId?.let { to ->
            updateUi { it.copy(activeGrinderId = to.value) }
            persistLibrary()
            pushConfig()
        }
        change.grind?.let { to -> setGrindDial(to.value) }
    }

    /** Undo the bag selection [seq] (core `bag_selection_undo`, REVIEW-CORE H4):
     *  each part goes back only while it's still what the selection set, so a
     *  change made since stands; mid-shot the profile stays (no upload). */
    fun undoBagSelection(seq: Long) {
        val u = bagSelectUndo?.takeIf { it.seq == seq } ?: return
        bagSelectUndo = null
        updateUi { st -> if (st.bagSelectUndo?.seq == seq) st.copy(bagSelectUndo = null) else st }
        val s = uiState()
        val now = coffee.crema.core.SelectionState(
            profileId = s.activeProfileId,
            grinderId = s.activeGrinderId,
            grind = s.qcGrind,
            shotInProgress = s.shotInProgress,
        )
        val still = runCatching { coffee.crema.beans.bagSelectionUndo(u.selection, now) }.getOrElse {
            appendLog("Bag selection undo failed: ${it.message}")
            return
        }
        applySelectionChange(still)
    }

    /** The bag selection [seq]'s message went without Undo. */
    fun consumeBagSelectUndo(seq: Long) {
        if (bagSelectUndo?.seq == seq) bagSelectUndo = null
        updateUi { st -> if (st.bagSelectUndo?.seq == seq) st.copy(bagSelectUndo = null) else st }
    }

    /**
     * Auto-save (#488, review F8): after a kept shot recorded on this device,
     * with "Save each shot's grind to its bag" on, its grind is saved for its
     * bag and grinder as Brew's Save would. A remote's mirror copy never
     * saves. A message only when the saved value changed.
     */
    private fun autoSaveGrind(shot: coffee.crema.history.ShotMeta, now: Long = System.currentTimeMillis()) {
        val s = uiState()
        if (s.grindAutoSave != true) return
        val beanId = shot.bean?.beanId ?: return
        var saved: coffee.crema.core.GrindSave? = null
        editBeans { rows ->
            rows.mapped { b ->
                if (b.id != beanId) return@mapped b
                val out = runCatching {
                    coffee.crema.beans.autoSaveShotGrind(
                        coffee.crema.core.AutoSaveInput(
                            enabled = true,
                            recordedHere = s.mirror == null,
                            shotBeanId = beanId,
                            shotGrinderId = shot.grinderId,
                            shotGrinderName = shot.grinderModel,
                            shotGrind = shot.grindSetting?.takeIf { it.isNotBlank() },
                            bag = b,
                        ),
                        now,
                    )
                }.onFailure { appendLog("Auto-save of the grind failed: ${it.message}") }.getOrNull()
                saved = out
                out?.bean ?: b
            }
        }
        val out = saved ?: return
        if (!out.changed) return
        persistLibrary()
        val grind = shot.grindSetting?.takeIf { it.isNotBlank() } ?: return
        val scale = s.grinders.firstOrNull { it.id == shot.grinderId }?.scale
        notify("Grind ${coffee.crema.beans.formatGrind(grind, scale)} saved to ${listOfNotNull(out.bean.name, shot.grinderModel).joinToString(" · ")}")
    }

    /**
     * Remove a bean bag; deleting the active one clears the selection (Brew
     * shows "No bag", review 2026-11-21 F5). [alsoOnVisualizer] then deletes
     * its Visualizer copy (best-effort; the id is captured before the local
     * row goes). Persisted.
     *
     * A bag that is on Visualizer leaves a tombstone, in either scope: the row
     * stays, hidden, with `deletedAt` set and no photo, so the next pull
     * doesn't add it back (#488 review S5). Any other bag is removed outright.
     */
    fun deleteBean(id: String, alsoOnVisualizer: Boolean = false) {
        val doomed = uiState().beans.firstOrNull { it.id == id } ?: return
        // The editor's own Delete (or the list's): not "deleted elsewhere".
        deletedHere = id
        val wasActive = uiState().activeBeanId == id
        val now = System.currentTimeMillis()
        editRows(
            { rows ->
                rows.copy(
                    beans = rows.beans.with(
                        rows.beans.mapNotNull { b ->
                            when {
                                b.id != id -> b
                                !b.visualizerId.isNullOrEmpty() && b.deletedAt == null -> coffee.crema.beans.beanTombstone(b, now)
                                b.deletedAt != null -> b
                                else -> null
                            }
                        },
                    ),
                )
            },
            { if (wasActive) it.copy(activeBeanId = null) else it },
        )
        // Drop the bag's photo too, so a deleted bean leaves no orphan blob.
        scope.launch(Dispatchers.IO) { BeanImageStore.delete(app, id) }
        persistLibrary()
        if (alsoOnVisualizer) doomed.visualizerId?.let { visualizer.deleteRemote(listOf(it), label = doomed.name) }
    }

    /**
     * Change the bean library from a Visualizer bean sync: the run applies each
     * row to the current list as it goes, so an edit made during it stands. The
     * run persists through [persistLibrary] when it ends.
     */
    fun editBeanLibrary(edit: (BeanLibrary) -> BeanLibrary) {
        editRows(
            { rows ->
                val next = edit(BeanLibrary(rows.beans, rows.roasters, null))
                rows.copy(beans = rows.beans.with(next.beans), roasters = rows.roasters.with(next.roasters))
            },
            { it },
        )
    }

    /**
     * Duplicate a bag into a fresh row via the core copy
     * ([coffee.crema.beans.duplicateBean]: new id, "name (copy)", no remote
     * links). Persisted.
     */
    fun duplicateBean(id: String) {
        val base = uiState().beans.firstOrNull { it.id == id } ?: return
        val copy = coffee.crema.beans.duplicateBean(base, System.currentTimeMillis())
        editBeans { rows -> rows.with(rows + copy) }
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
        editBeans { rows -> rows.mapped { if (it.id == id) transform(it).copy(updatedAt = now) else it } }
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
        editRoasters { rows -> rows.with(rows + r) }
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
        editRoasters { rows ->
            rows.mapped {
                // A tombstone stays as the delete left it (#488 review SF1).
                if (it.id == id && it.deletedAt == null) it.copy(
                    name = name.trim(),
                    website = website?.takeIf { w -> w.isNotBlank() },
                    city = city?.takeIf { c -> c.isNotBlank() },
                    country = country?.takeIf { c -> c.isNotBlank() },
                    notes = notes,
                    catalogueRoasterId = catalogueRoasterId?.takeIf { c -> c.isNotBlank() },
                    updatedAt = now,
                ) else it
            }
        }
        persistLibrary()
    }

    /**
     * Delete a roaster (web `RoasterDeleteSplit`): [cascade] = false detaches its
     * bags (roaster cleared), true deletes them too. The core's
     * `plan_roaster_delete` decides which bags go and the Visualizer ids;
     * [alsoOnVisualizer] then deletes those remote copies (bags first, then the
     * roaster). A roaster on Visualizer stays as a tombstone, and "this device
     * only" ([alsoOnVisualizer] off) leaves its bags filed under it, so the next
     * pull doesn't add it back and Visualizer's bags keep their roaster (#488
     * review SF1). Persisted.
     */
    fun deleteRoaster(id: String, alsoOnVisualizer: Boolean = false, cascade: Boolean = false) {
        val s = uiState()
        val plan = runCatching {
            coffee.crema.beans.planRoasterDelete(
                json, s.roasters.withTombstones(), s.beans.withTombstones(), id, cascade, s.activeBeanId,
                localOnly = !alsoOnVisualizer,
            )
        }.getOrNull() ?: return
        val name = s.roasters.firstOrNull { it.id == id }?.name.orEmpty()
        val now = System.currentTimeMillis()
        var after = BeanLibrary()
        editRows(
            { rows ->
                after = coffee.crema.beans.applyRoasterDelete(BeanLibrary(rows.beans, rows.roasters, s.activeBeanId), plan, now)
                rows.copy(roasters = rows.roasters.with(after.roasters), beans = rows.beans.with(after.beans))
            },
            // The pointer as the core plans it (REVIEW-CORE M3): cleared when the
            // cascade deletes the active bag (F5, Brew shows "No bag"); no other bag
            // is made active. One picked since the plan stands unless it went too.
            { st ->
                st.copy(
                    activeBeanId = if (st.activeBeanId == s.activeBeanId) plan.activeBeanIdAfter
                    else st.activeBeanId?.takeUnless { it in plan.deletedBeanIds },
                )
            },
        )
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
        editRows(
            { rows ->
                rows.copy(
                    beans = rows.beans.mapped { if (it.id in moving) it.copy(roasterId = plan.canonicalId, updatedAt = now) else it },
                    roasters = rows.roasters.mapped { if (it.id == plan.dupeId) it.copy(canonicalRoasterId = plan.canonicalId, updatedAt = now) else it },
                )
            },
            { it },
        )
        persistLibrary()
        val names = s.roasters.associate { it.id to it.name }
        notify("Merged “${names[dupeId]}” into “${names[canonicalId]}” — ${moving.size} bag(s) moved")
    }

    /** Un-merge a tagged duplicate: clear its duplicate-of pointer (its bags stay where they are). Persisted. */
    fun unmergeRoaster(id: String) {
        val now = System.currentTimeMillis()
        editRoasters { rows -> rows.mapped { if (it.id == id && it.deletedAt == null) it.copy(canonicalRoasterId = null, updatedAt = now) else it } }
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
     * What Brew has selected (REVIEW-CORE H3) in [s], the effective state (on
     * a remote, the hub's): the core freezes it on its own shot start, the
     * app's or the group head's, and the shot records that copy.
     */
    fun shotSelectionsOf(s: MainUiState): ShotSelections {
        val bean = s.beans.firstOrNull { it.id == s.activeBeanId }
        val grinder = s.activeGrinder()
        val profile = s.activeProfile()
        return ShotSelections(
            bean = bean?.let { b -> shotBeanSnapshot(b, b.roasterId?.let { rid -> s.roasters.firstOrNull { it.id == rid }?.name }) },
            profileId = profile?.id,
            profileName = profile?.name,
            grinderId = grinder?.id,
            grinderName = grinder?.name?.trim()?.ifEmpty { null },
            grind = s.brewGrindView().value,
        )
    }

    /** Hand [selections] to the core for the next shot's start. */
    fun pushShotSelections(selections: ShotSelections) {
        runCatching { bridge.setShotSelections(json.encodeToString(ShotSelections.serializer(), selections)) }
            .onFailure { appendLog("Shot selections not handed to the core: ${it.message}") }
    }

    /**
     * Capture a completed shot into the log (newest first, capped) and persist.
     * The bag, profile, grinder and grind are those the core froze at the
     * shot's start ([shotSelectionsOf]); what the completion does besides the
     * row is the core's [shotEffects].
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
        // What the completion does is the core's rule (REVIEW-CORE M4): only
        // the device that records the shot acts on it. A remote's mirror copy
        // of the hub's shot (02 F2: it still records the shot it watched) never
        // debits its own copy of the hub's bag, uploads or auto-saves; a replay
        // touches nothing real but still uploads.
        val s = effectiveState()
        val effects = shotEffects(disposition, recordedHere = s.mirror == null, replay = s.replayPrimary)
        // SkipCleaning: machine maintenance runs in the DE1's Espresso state, so a
        // cleaning/backflush profile would be recorded as a shot — skip the
        // capture entirely (no history row, no Visualizer, no bean debit;
        // nothing was ground). Decenza #1325 (maincontroller.cpp:1841-1853).
        // Descale/calibrate run in their own machine states, never here.
        if (!effects.record && !effects.offerUndo) {
            // Take the core's pending shot anyway, so it never outlives its
            // completion (web takes it whatever the disposition too).
            runCatching { bridge.takeStoredShot(json.encodeToString(ShotCaptureContext.serializer(), ShotCaptureContext(completedAt = now, replay = s.replayPrimary))) }
            appendLog("Cleaning run finished — not recorded as a shot")
            return
        }
        // The core assembles the shot (F10, the rule set web uses too): its own
        // record (the full series with frames and scale overlays), the profile
        // the DE1 held and its dose / yield / brew temp / pre-infusion, the stop
        // reason and switches. The bag, profile, grinder and grind are the ones
        // it froze at its own shot start, the app's or the group head's, from
        // [shotSelectionsOf] (REVIEW-CORE H3). The shell hands in only what the
        // core can't know.
        val ctx = ShotCaptureContext(
            completedAt = now,
            id = shotId,
            // The Settings default barista (dye2 audit #1), editable later.
            barista = s.baristaName.trim().ifEmpty { null },
            // The DE1 this ran on — frozen here so a later Decent upload (#84)
            // carries the serial the server verifies.
            machine = liveMachine(),
            // A replay's row carries the replay mark (#488 batch 6).
            replay = s.replayPrimary,
        )
        assembling()
        val assembled = runCatching {
            bridge.takeStoredShot(json.encodeToString(ShotCaptureContext.serializer(), ctx))
        }.onFailure { appendLog("Shot assembly failed: ${it.message}") }.getOrNull()
        val core = assembled?.let { json.parseToJsonElement(it) as? JsonObject }
        // No telemetry at all (or the core refused): nothing to record.
        val captured = core?.let { storedShotFromBackupJson(it, json) }?.let { c ->
            c.copy(
                // The core's running peaks from the event, as before.
                peakPressure = peakPressure,
                peakTemp = peakTemp,
                peakWeightG = peakWeightG,
                // The shot's tags are its bag's at the start: a copy, so a later
                // edit to the bag's tags doesn't rewrite history.
                tags = c.bean?.tags.orEmpty(),
            )
        }
        if (captured == null) {
            appendLog("Shot had no telemetry — not recorded")
            return
        }
        // Burn this shot's dose down from the bag it recorded (its frozen
        // `bean.beanId`), and record what it took on the row. The dose the core
        // recorded — the Quick Controls dial when the DE1 ran the dialled
        // profile (review §2.3-6) — else the recorded profile's.
        val bag = captured.bean?.beanId?.let { id -> s.beans.firstOrNull { it.id == id } }
        val dose = captured.doseG ?: captured.profileId?.let { id ->
            (s.mirroredProfile?.takeIf { it.id == id } ?: s.profiles.firstOrNull { it.id == id })?.dose
        }
        val bagDebit = bag?.takeIf { effects.debit }?.let { debitBag(it, dose) }
        val shot = captured.copy(bagDebitG = bagDebit)
        // Aborted-shot auto-discard (Decenza abortedshotclassifier.h:19-25,
        // validated over 882 shots): under 10 s of flow AND under 5 g in the
        // cup is an aborted pull, not a shot — it would only pollute history
        // and Visualizer. The rule lives in the core now; the mechanics stay
        // here: held for an undo toast instead of a modal. The bean debit above
        // still ran (the dose was physically ground), and the SAW model save
        // below still runs (its own gates reject bad samples); the uploads and
        // the auto-save wait for the Undo.
        if (effects.offerUndo) {
            discardedShot = shot to effects
            updateUi { it.copy(
                // The Last-shot card would point at a shot history doesn't
                // hold — clear it for an aborted pull.
                lastShot = null,
                discardToastMessage = "Aborted shot discarded (${fmt("%.1f", durationMs / 1000f)} s)",
            ) }
            appendLog("Shot discarded as aborted (<10 s, <5 g)")
        } else {
            val cut = CapCut()
            // The row goes on screen; its curve, profile and brew series wait for the save (#310 P4a).
            val row = staged(shot)
            addMade(row, cut)
            saveHistory()
            cut.say()
            kept(row, effects)
        }
        // Persist the learned SAW drip model — a weight-stopped shot just
        // added a training sample in the core.
        scope.launch { persistSawModel() }
    }

    /** A kept shot's [row]: offered to the armed upload destinations and its
     *  grind auto-saved to its bag (#488), as [effects] say. */
    private fun kept(row: ShotRow, effects: ShotEffects) {
        // Auto-upload to every armed destination (Visualizer, Decent), one
        // notice for the batch; each reads the shot whole, from the stage.
        if (effects.upload) {
            offeredForUpload(row)
            autoUploadShot(row)
        }
        if (effects.autoSave) autoSaveGrind(row)
    }

    /**
     * Debit [dose] from [bean] and return the grams actually taken. The core
     * `debitRemaining` owns the rule + its no-ops: it returns null when there's
     * nothing to debit (bag already empty / bad dose), so we persist — and
     * touch updatedAt — ONLY on a real debit. If it just emptied the bag,
     * surface it so the user can switch / archive / rate.
     */
    private fun debitBag(bean: Bean, dose: Float?): Float {
        val grams = dose ?: return 0f
        val now = System.currentTimeMillis()
        var taken: Float? = null
        var emptied = false
        // From what the bag holds now: [bean] can be a read from before an edit.
        editBeans { rows ->
            rows.mapped { row ->
                val remaining = row.remaining ?: 0f
                val next = if (row.id == bean.id) debitRemaining(remaining, grams) else null
                if (row.id == bean.id) {
                    taken = next?.let { debitTaken(remaining, grams) }
                    emptied = next == 0f
                }
                if (next == null) row else row.copy(remaining = next, updatedAt = now)
            }
        }
        val debit = taken ?: return 0f
        persistLibrary()
        if (emptied) onBagEmptied(bean)
        return debit
    }

    /**
     * A debit just emptied [bean]: ask what to do with it. Not on a remote: its
     * library is its own, the hub asks about the hub's bag, and dropping the
     * active bag here would change the hub's. A passing notice says so instead.
     */
    private fun onBagEmptied(bean: Bean) {
        if (uiState().mirror != null) {
            notify("That was the last of ${bean.name}; bag empty")
            return
        }
        val prompt = BagEmptyPrompt(bean.id, bean.name, unrated = (bean.rating?.toInt() ?: 0) == 0)
        updateUi { it.copy(bagEmptyPrompt = prompt) }
    }

    /**
     * The answer to the bag-empty prompt's first question. [archive] archives
     * the bag; the caller then drops it as the active bag. An unrated bag goes on
     * to the rating question.
     */
    fun answerBagEmpty(archive: Boolean) {
        val prompt = uiState().bagEmptyPrompt ?: return
        if (archive) archiveBean(prompt.beanId)
        updateUi { it.copy(bagEmptyPrompt = if (prompt.unrated) prompt.copy(askingRating = true) else null) }
    }

    /** Close the bag-empty prompt. */
    fun dismissBagEmptyPrompt() {
        updateUi { it.copy(bagEmptyPrompt = null) }
    }

    /** The Log-brew form's payload (issue #10). */
    data class ManualBrewInput(
        val method: String,
        val completedAtMs: Long,
        val beanId: String?,
        val doseG: Float?,
        val waterG: Float?,
        val yieldG: Float?,
        /** The form's grind; null = none, 0 is a grind. */
        val grind: Double?,
        val brewTempC: Float?,
        val durationMs: Long?,
        val rating: Int?,
        val notes: String?,
        val nextPlan: String?,
        val recipeName: String? = null,
        val brewSeries: coffee.crema.core.BrewSeries? = null,
        /** The custom method's label, snapshotted on the row (`custom:` methods only). */
        val methodLabel: String? = null,
        /** The active grinder the brew is stamped with; null = no grinder. */
        val grinder: coffee.crema.core.Grinder? = null,
    )

    /**
     * Record a Brew Log entry (issue #10) — a manually logged brew, or a
     * finished guided session. The one path that legitimately persists a
     * telemetry-less row. Debits the bag exactly like a live shot
     * (`debitRemaining` → bag-empty prompt) and never touches Visualizer
     * (logged brews are local-only, like `nextPlan`).
     */
    fun addManualBrew(input: ManualBrewInput): StoredShot {
        val s = uiState()
        val bean = input.beanId?.let { id -> s.beans.firstOrNull { it.id == id } }
        val roasterName = bean?.roasterId?.let { rid -> s.roasters.firstOrNull { it.id == rid }?.name }
        // The core's snapshot (REVIEW-FINAL S2, N6): the bag's notes, never
        // its legacy grind and grinder.
        val beanSnapshot = bean?.let { shotBeanSnapshot(it, roasterName) }
        // The grinder and grind, stamped as a shot's are (REVIEW-FINAL S4): the
        // brew counts for its bag's newest grind on that grinder and uploads
        // under it; the grind is text on its scale, 0 kept.
        val stamp = brewLogGrind(input.grinder, input.grind)
        // Burn the dose down from the bag — the whole point of the feature.
        val bagDebit = bean?.let { debitBag(it, input.doseG) }
        val shot = StoredShot(
            id = coffee.crema.core.newShotId(),
            completedAtMs = input.completedAtMs,
            durationMs = input.durationMs ?: 0L,
            yieldG = input.yieldG,
            doseG = input.doseG,
            bagDebitG = bagDebit,
            grindSetting = stamp.grind,
            grinderId = stamp.grinderId,
            grinderModel = stamp.grinderName,
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
        val cut = CapCut()
        val row = staged(shot)
        addMade(row, cut)
        saveHistory()
        notify("Brew logged")
        cut.say()
        return shot
    }

    /**
     * Re-tag a brew row with a (newly saved) custom method — the "Save 'X' as
     * a method?" offer. Snapshots the label beside the id. Persisted.
     */
    fun retagBrewMethod(id: String, method: String, label: String) {
        historyStore.update(id) { it.copy(brewMethod = method, brewMethodLabel = label) }
        saveHistory()
    }

    /**
     * Edit the user-entered facts of a MANUAL brew row (dose / water /
     * yield / temp / time). Measured (machine / guided) rows are left
     * alone. A dose change re-settles the bag through the same
     * credit/debit pair bean re-attribution uses. A new date moves the row
     * to its place in the list; a future one refuses the whole edit. Logged
     * brews are local-only, so nothing goes to Visualizer.
     */
    fun updateManualBrew(
        id: String,
        doseG: Float? = null,
        waterG: Float? = null,
        yieldG: Float? = null,
        brewTempC: Float? = null,
        durationMs: Long? = null,
        completedAtMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val shot = findShot(id) ?: return
        if (!shot.isManualLog) return
        if (completedAtMs != null && brewDateError(completedAtMs, nowMs) != null) return
        // Re-settle inventory with the row, using the OLD dose — the core's
        // resettleRemaining (only the net change moves, and no more comes back
        // than the row took; null = nothing to persist, including an unchanged
        // dose). Both from the rows as they stand, in one write.
        var bagMoved = false
        // The row as the store holds it now, and its bag's change worked out from it,
        // in one step: the bag's delta lands on the bag as it stands (#324).
        historyStore.update(id) { row ->
            var bagDebit = row.bagDebitG
            bagMoved = false
            if (doseG != null) {
                editBeans { beans ->
                    // The update can run its lambda again: from scratch each time.
                    bagDebit = row.bagDebitG
                    bagMoved = false
                    beans.firstOrNull { it.id == row.bean?.beanId }?.let { bean ->
                        val rem = bean.remaining ?: 0f
                        val size = bean.bagSize ?: 0f
                        bagDebit = resettledBagDebit(rem, size, row.bagDebitG, row.doseG ?: 0f, doseG)
                        resettleRemaining(rem, size, row.bagDebitG, row.doseG ?: 0f, doseG)?.let { next ->
                            bagMoved = true
                            beans.mapped { if (it.id == bean.id) it.copy(remaining = next, updatedAt = nowMs) else it }
                        }
                    } ?: beans
                }
            }
            row.copy(
                doseG = doseG ?: row.doseG,
                bagDebitG = bagDebit,
                waterG = waterG ?: row.waterG,
                yieldG = yieldG ?: row.yieldG,
                brewTempC = brewTempC ?: row.brewTempC,
                durationMs = durationMs ?: row.durationMs,
                completedAtMs = completedAtMs ?: row.completedAtMs,
            )
        } ?: return
        if (bagMoved) persistLibrary()
        saveHistory()
    }

    /** Apply a user rating / tasting-notes edit to a logged shot. Persisted.
     *  An already-uploaded shot mirrors the edit to Visualizer (soft). */
    fun updateShot(id: String, rating: Int, notes: String) {
        val marked = markForVisualizer(id, annotations = true)
        historyStore.update(id) { it.copy(rating = rating.coerceIn(0, 5).takeIf { r -> r > 0 }, notes = notes.ifBlank { null }) }
        saveMarkedEdit(marked)
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
        historyStore.update(id) { it.copy(nextPlan = nextPlan.ifBlank { null }) } ?: return
        saveHistory()
    }

    /**
     * Pin or unpin a shot (#311): a pinned shot's BLE session keeps its capture
     * past the limit, and it is exempt from any cleanup. Persisted; local only,
     * so nothing goes to Visualizer.
     */
    fun setShotPinned(id: String, pinned: Boolean) {
        historyStore.update(id) { it.copy(pinned = pinned) }
        saveHistory()
    }

    /**
     * Set (or with null / blank, clear) who made (`"barista"`) or drank
     * (`"drinker"`) a shot. Persisted. The barista mirrors to Visualizer
     * (its `barista` field); both re-send the Decent copy, whose workflow
     * context carries them.
     */
    fun setShotPerson(id: String, field: String, name: String?) {
        val value = name?.trim()?.takeIf { it.isNotEmpty() }
        // Only a barista edit sends the barista (final review 06 #1).
        val marked = field == "barista" && markForVisualizer(id, barista = true)
        historyStore.update(id) {
            when (field) {
                "barista" -> it.copy(barista = value)
                "drinker" -> it.copy(drinker = value)
                else -> it
            }
        }
        saveMarkedEdit(marked)
        if (field == "barista") schedulePatchEdited(id)
        onShotAnnotationsEdited(id)
    }

    /**
     * Set (or with null, clear) a shot's per-upload Visualizer privacy
     * override. Persisted; mirrors to the uploaded copy like [updateShot].
     */
    fun setShotPrivacy(id: String, privacy: String?) {
        val marked = markForVisualizer(id, annotations = true)
        historyStore.update(id) { it.copy(privacy = privacy) }
        saveMarkedEdit(marked)
        schedulePatchEdited(id)
    }

    /**
     * Set (or with null, clear) the grind recorded on a stored shot (issue
     * #16 follow-up: dialing-in spans shots, so the log needs fixing up after
     * the fact). Persisted; mirrors to the uploaded copy like [updateShot].
     */
    fun setShotGrind(id: String, grind: String?) {
        val marked = markForVisualizer(id, annotations = true)
        historyStore.update(id) { it.copy(grindSetting = grind) }
        saveMarkedEdit(marked)
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
        val shot = findShot(id) ?: return
        val oldBeanId = shot.bean?.beanId
        if (oldBeanId == beanId) return // same attribution — nothing to do
        val bean = beanId?.let { bid -> s.beans.firstOrNull { it.id == bid } }
        val roasterName = bean?.roasterId?.let { rid -> s.roasters.firstOrNull { it.id == rid }?.name }
        // The core's snapshot (REVIEW-FINAL S2, N6): the bag's notes, never
        // its legacy grind and grinder.
        val beanSnapshot = bean?.let { shotBeanSnapshot(it, roasterName) }
        // Move the bag debit with the attribution: the grounds physically came
        // from the bag the user now says they did, so the old bag gets back what
        // the row took (core creditShotDebit: capped at bagSize, an untracked bag
        // gains nothing, null = nothing changed) and the new bag is debited, with
        // the row recording what it took this time. Web's moveShotBag is the same.
        val dose = shot.doseG ?: 0f
        // From what the old bag holds now.
        if (oldBeanId != null) {
            val now = System.currentTimeMillis()
            var credited = false
            editBeans { rows ->
                credited = false
                rows.mapped { row ->
                    if (row.id != oldBeanId) return@mapped row
                    creditShotDebit(row.remaining ?: 0f, row.bagSize ?: 0f, shot.bagDebitG, dose)
                        ?.let { credited = true; row.copy(remaining = it, updatedAt = now) } ?: row
                }
            }
            if (credited) persistLibrary()
        }
        val bagDebit = bean?.let { debitBag(it, dose) }
        val marked = markForVisualizer(id, annotations = true)
        historyStore.update(id) { it.copy(bean = beanSnapshot, bagDebitG = bagDebit) }
        saveMarkedEdit(marked)
        schedulePatchEdited(id)
        onShotAnnotationsEdited(id)
    }

    /**
     * Mark what an edit of an uploaded shot changed, before the row changes, so
     * the PATCH sends only that and a failed one is retried by the drain. True
     * when the stored marks must change first ([saveMarkedEdit]).
     */
    private fun markForVisualizer(id: String, annotations: Boolean = false, barista: Boolean = false): Boolean {
        if (findShot(id)?.visualizerId == null) return false
        return visualizer.markShotEdited(id, annotations = annotations, barista = barista)
    }

    /**
     * Save the history after an edit, the mark ([marked]) on disk first: a kill
     * between the two then leaves at most a mark with nothing new to send, never
     * a saved barista clear without its mark, which the next pull would refill
     * (V3 #6). The rows are read as the save runs, so a later edit's save that
     * got there first is never undone by this one.
     */
    private fun saveMarkedEdit(marked: Boolean) {
        if (!marked) {
            saveHistory()
            return
        }
        // Every reader has the edit now (it's in the store's memory); its write waits for the marks.
        scope.launch {
            visualizer.storeMarks()
            persistHistory()
        }
    }

    /** Debounced edit→Visualizer mirror: the notes field fires per keystroke
     *  and star taps come in bursts — one PATCH goes out 1.5 s after the last
     *  edit, with the shot's then-current state. */
    private var shotPatchJob: Job? = null
    private fun schedulePatchEdited(id: String) {
        shotPatchJob?.cancel()
        shotPatchJob = scope.launch {
            delay(1_500)
            findShot(id)?.let { visualizer.patchEditedShot(it) }
        }
    }

    /** Undo an aborted-shot discard: persist the held shot after all. */
    fun undoDiscardShot() {
        val (shot, effects) = discardedShot ?: return
        discardedShot = null
        val cut = CapCut()
        val row = staged(shot)
        addMade(row, cut)
        editRows(
            { it },
            { it.copy(
                discardToastMessage = null,
                lastShot = LastShot(
                    durationMs = shot.durationMs,
                    yieldG = shot.yieldG,
                    peakPressure = shot.peakPressure,
                    peakTemp = shot.peakTemp,
                    completedAtMs = shot.completedAtMs,
                    id = shot.id,
                ),
            ) },
        )
        saveHistory()
        // An undone discard is a kept shot.
        kept(row, effects)
    }

    /** The discard snackbar timed out / was dismissed — drop the held shot. */
    fun consumeDiscardToast() {
        discardedShot = null
        updateUi { it.copy(discardToastMessage = null) }
    }
}

/**
 * [next] with the device's own `grinds` kept on the bags in [keepIds]: a
 * Replace that overwrote them with a backup row from an app before #488 (no
 * `grinds`) mustn't erase their saved grinds (review F7, the plan's
 * `beansKeepGrinds`). [local] is the library before the restore.
 */
internal fun keepingGrinds(next: List<Bean>, local: List<Bean>, keepIds: List<String>): List<Bean> {
    if (keepIds.isEmpty()) return next
    val keep = keepIds.toHashSet()
    val held = local.filter { it.id in keep }.associateBy { it.id }
    return next.map { b -> held[b.id]?.takeIf { b.grinds == null }?.let { b.copy(grinds = it.grinds) } ?: b }
}

/** Rows a restore writes to the store per batch. */
/** The message after a profile import: "Imported 2 profiles; 3 already in your
 *  library", with the first error when some didn't import (web words it the
 *  same, F12b). */
/**
 * The rows of [session], the list in memory while the shot log couldn't be read,
 * that aren't in [stored], the log once it could. A row can be in both under
 * another id, if it came from Visualizer, so that id counts too.
 */
internal fun <T : coffee.crema.history.ShotMeta> madeWhileRefused(session: List<T>, stored: List<coffee.crema.history.ShotMeta>): List<T> {
    val ids = stored.mapTo(HashSet()) { it.id }
    val remote = stored.mapNotNullTo(HashSet()) { it.visualizerId }
    return session.filter { it.id !in ids && it.visualizerId !in remote }
}

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

/**
 * What a backup holds, without when it was made (#311): SHA-256 over its JSONL
 * past the header line, which carries the export time, and over each photo.
 * The same data gives the same hash, so an unchanged daily backup isn't
 * uploaded again.
 */
internal fun backupContentHash(jsonl: String, images: List<Pair<String, ByteArray>>): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.update(jsonl.substringAfter('\n').toByteArray(Charsets.UTF_8))
    for ((id, bytes) in images.sortedBy { it.first }) {
        md.update(id.toByteArray(Charsets.UTF_8))
        md.update(bytes)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * What a completed shot does besides its history row: the core's one rule
 * (`shot_effects`, REVIEW-CORE M4). Should the bridge refuse, the shot is
 * still recorded (or held for its Undo) and nothing else runs.
 */
internal fun shotEffects(
    disposition: ShotDisposition,
    recordedHere: Boolean,
    replay: Boolean,
    core: (String) -> String = ::shotEffectsJson,
): ShotEffects = runCatching {
    coreJson.decodeFromString(
        ShotEffects.serializer(),
        core(coreJson.encodeToString(ShotEffectsInput.serializer(), ShotEffectsInput(disposition, recordedHere, replay))),
    )
}.getOrElse {
    ShotEffects(
        record = disposition == ShotDisposition.Record,
        offerUndo = disposition == ShotDisposition.DiscardAborted,
        debit = false,
        webhook = false,
        autoSave = false,
        upload = false,
    )
}

/**
 * [bean]'s snapshot for a shot, a logged brew or a shot moved to it: the
 * core's one builder (`ShotBean::snapshot_of`), so its notes are kept and its
 * legacy grind and grinder never are (REVIEW-FINAL S2, N6).
 */
internal fun shotBeanSnapshot(
    bean: coffee.crema.core.Bean,
    roasterName: String?,
    core: (String, String) -> String = ::shotBeanSnapshotJson,
): ShotBean = runCatching {
    coreJson.decodeFromString(ShotBean.serializer(), core(coreJson.encodeToString(coffee.crema.core.Bean.serializer(), bean), roasterName.orEmpty()))
}.getOrElse { ShotBean(beanId = bean.id, name = bean.name) }

/**
 * A logged brew's grinder and grind (core `brew_log_grind`, REVIEW-FINAL S4):
 * [grinder]'s id and name, and [grind] as text on its scale (0 kept). Should
 * the bridge refuse, the grinder is still stamped and the grind left out.
 */
internal fun brewLogGrind(
    grinder: coffee.crema.core.Grinder?,
    grind: Double?,
    core: (String) -> String = ::brewLogGrindJson,
): BrewLogGrind = runCatching {
    coreJson.decodeFromString(BrewLogGrind.serializer(), core(coreJson.encodeToString(BrewLogGrindInput.serializer(), BrewLogGrindInput(grinder, grind))))
}.getOrElse { BrewLogGrind(grinderId = grinder?.id?.ifBlank { null }, grinderName = grinder?.name?.trim()?.ifEmpty { null }) }
