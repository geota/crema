package coffee.crema.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import coffee.crema.ProcessScope
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.os.BatteryManager
import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.MachineRequest
import coffee.crema.core.WaterSessionKind
import coffee.crema.core.MmrReg
import coffee.crema.core.Bean
import coffee.crema.drive.DriveStore
import coffee.crema.drive.DriveSync
import coffee.crema.core.builtinCremaProfiles
import coffee.crema.core.cremaProfileToWire
import coffee.crema.core.exportBackupJsonl
import coffee.crema.core.CommonSettings
import coffee.crema.core.defaultIdleSleepMin
import coffee.crema.settings.resetCommon
import coffee.crema.settings.toCommonSettings
import androidx.core.content.FileProvider
import coffee.crema.history.StoredShot
import coffee.crema.maintenance.MaintenanceStore
import coffee.crema.persist.KeptAside
import coffee.crema.persist.KeptAsideNotices
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.Setting
import coffee.crema.settings.setting
import coffee.crema.visualizer.VisualizerClient
import coffee.crema.visualizer.VisualizerStore
import coffee.crema.visualizer.VisualizerSync
import coffee.crema.settings.SettingsStore
import coffee.crema.settings.SawModelStore
import coffee.crema.ble.BleScanner
import coffee.crema.ble.BleSessionRecorder
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.ble.De1Uuids
import coffee.crema.ble.proxy.DeviceInfo
import coffee.crema.machine.CommandOutcome
import coffee.crema.machine.MachineActions
import coffee.crema.machine.MachineCommand
import coffee.crema.machine.MachineCommands
import coffee.crema.machine.QcField
import coffee.crema.core.ShotQualityReport
import coffee.crema.core.brewTempRange
import coffee.crema.core.preinfusionDial
import coffee.crema.profiles.CremaProfile
import coffee.crema.profiles.brewDefaultsJson
import coffee.crema.profiles.SegmentEdit
import java.io.File
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import android.net.Uri
import kotlinx.serialization.json.put
import coffee.crema.decent.DecentClient
import coffee.crema.decent.DecentStore
import coffee.crema.decent.DecentSync
import coffee.crema.decent.UniffiDecentCore
import coffee.crema.decent.decentShotRecordJson

/**
 * Owns the [CremaBridge] and the [De1BleManager], parses the JSON `CoreOutput`
 * the bridge returns, and projects decoded [Event]s into [MainUiState].
 *
 * Carried over from the Phase-0 proof-of-concept: connection, core ownership,
 * and per-screen state are still folded into one class so the end-to-end path
 * is readable top to bottom. Splitting these into proper layers is future work.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    // See [coreJson]: lenient enums, and the core enums tagged `kind`.
    private val json = coreJson

    /** The Rust core, behind the UniFFI bridge. */
    private val bridge: CremaBridge = CremaBridge()


    /**
     * The core lane ([CoreLane], review #28): every `CoreOutput` applies there,
     * and the heavy FFI calls run there. What each core event does is
     * registered on it by the controller that owns its state, while that
     * controller is built and before any output can apply. The writers reach
     * later fields ([ble], [scale]) only when invoked.
     */
    private val lane: CoreLane = CoreLane(
        scope = viewModelScope,
        json = json,
        log = { appendLog(it) },
        writeDe1 = { target, bytes, urgent, ackUploadId ->
            ble.writeCharacteristic(target, bytes, urgent = urgent, ackUploadId = ackUploadId)
        },
        writeScale = { bytes -> scale.writeCommand(bytes) },
        shotRunning = { _ui.value.shotInProgress },
        profileWriteFailed = { id -> bridge.profileWriteFailed(SystemClock.elapsedRealtime().toULong(), id) },
    )

    /** Where the dial's debounce wait runs ([MachineSessionController.dialDebounceContext]).
     *  Test seam: a test dispatcher makes the 0.5 s virtual time. */
    @androidx.annotation.VisibleForTesting
    internal var dialDebounceContext: kotlin.coroutines.CoroutineContext
        get() = session.dialDebounceContext
        set(value) { session.dialDebounceContext = value }

    private val _ui = MutableStateFlow(MainUiState())

    /** The high-rate readouts as they arrive: the store every reader and
     *  writer uses, on its own thread and in order ([live] is the screens'
     *  view of it). In memory only: nothing persisted reads it. */
    private val _live = MutableStateFlow(LiveTelemetry())

    /**
     * The machine's configuration ([MachineConfigController]): the config
     * commands' bodies, the core inputs that follow settings, the steam /
     * hot-water packet and the setters that reach the core. Built before
     * everything it or [session] reach: construction only stores these, and
     * the callbacks reach later members only when invoked.
     */
    private val machineConfig: MachineConfigController = MachineConfigController(
        lane = lane,
        bridge = bridge,
        json = json,
        uiState = { _ui.value },
        updateUi = { transform -> _ui.update(transform) },
        effectiveState = { effectiveState() },
        appendLog = { appendLog(it) },
        refusesOwnEdit = { refusesOwnEdit(it) },
        persistPrefs = { persistPrefs() },
        pushConfig = { proxy.pushConfig() },
        profileJson = { library.profileJson(it) },
        persistLibrary = { library.persistLibrary() },
        maybeLoadLinkedProfile = { library.maybeLoadLinkedProfile(it) },
        pushStopTargets = { state, desiredDelayMs -> session.pushStopTargets(state, desiredDelayMs) },
        refreshStopTargetProjection = { session.refreshStopTargetProjection() },
        pushStartSettings = { session.pushStartSettings() },
        configureIdleSleep = { presence.configureIdleSleep(it) },
    )

    /**
     * The machine session ([MachineSessionController]): its event handlers,
     * the service-mode clock, the shot record's markers, the shot start, the
     * desired profile, the stop targets and the connect setup. Built right after the lane, so its handlers come first for the
     * events another owner also handles (as when they were the VM's own); its
     * callbacks reach later members only when invoked.
     */
    private val session: MachineSessionController = MachineSessionController(
        lane = lane,
        app = app,
        scope = viewModelScope,
        uiState = { _ui.value },
        updateUi = { transform -> _ui.update(transform) },
        updateLive = { transform -> _live.update(transform) },
        effectiveState = { effectiveState() },
        appendLog = { appendLog(it) },
        notify = { notifyUser(it) },
        onMachineState = { prev, state, mode -> presence.onMachineState(prev, state, mode) },
        setLinkPriority = { high -> ble.setConnectionPriority(high = high) },
        captureCompletedShot = { durationMs, yieldG, peakPressure, peakTemp, completedAtMs, id, disposition, peakWeightG ->
            library.captureCompletedShot(durationMs, yieldG, peakPressure, peakTemp, completedAtMs, id, disposition, peakWeightG = peakWeightG)
        },
        setHotWaterTarget = { machineConfig.setHotWaterTarget(it) },
        pushConfig = { proxy.pushConfig() },
        armWake = { wake.arm(it) },
        scaleRemembered = { connection.state.value.rememberedScaleAddress != null },
        kickScaleReconnect = { connection.kickScaleReconnect() },
        bridge = bridge,
        json = json,
        profileJson = { library.profileJson(it) },
        refreshHotWaterTarget = { machineConfig.refreshHotWaterTarget() },
        markAppSleepRequest = { presence.markAppSleepRequest() },
        firmwareHolds = { firmwareHolds() },
        markSetupDone = { firmwareCtl.setupDone = true },
        de1Ready = { ble.state.value == De1BleManager.State.READY },
        de1Address = { ble.connectedAddress },
        writeDe1 = { target, bytes -> ble.writeCharacteristic(target, bytes) },
    )

    /**
     * The UI state the screens collect: the effective projection of [_ui]
     * ([effective]: on a remote, the hub's mirrored values laid over this
     * device's own; elsewhere [_ui] itself). A telemetry sample does not
     * change it: the high-rate readouts are [live]'s (cohort review
     * 2026-10-25, P2-4).
     *
     * [_ui] holds this device's own values plus the mirror slice, and is what
     * the VM persists. VM code reads it directly for its own values and
     * [effectiveState] for what is on screen (spec §7.2).
     */
    val ui: StateFlow<MainUiState> = _ui
        .map { it.effective() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _ui.value.effective())

    /**
     * The readouts the screens collect, as low in the tree as they can
     * ([LiveReadouts]). While the screensaver covers everything nothing is
     * published, so the tree behind it stays still for hours on a wall tablet;
     * the saver going away publishes the latest at once. Logic reads the
     * store, never this: [_live]'s value is current on the caller's thread.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val live: StateFlow<LiveTelemetry> = _ui
        .map { it.saverVisible }
        .distinctUntilChanged()
        .flatMapLatest { saverUp -> if (saverUp) emptyFlow() else _live }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _live.value)

    /** This device's own state, for tests (AC-16): what [persistPrefs] saves. */
    @androidx.annotation.VisibleForTesting
    internal val ownState: StateFlow<MainUiState> = _ui

    /** The readouts as they arrive, for tests: [live] goes quiet behind the saver. */
    @androidx.annotation.VisibleForTesting
    internal val liveStore: StateFlow<LiveTelemetry> = _live

    /** The state the screens show ([effective]). */
    private fun effectiveState(): MainUiState = _ui.value.effective()

    /**
     * Run [edit], the body of a read-only machine or session setter (§7.3),
     * unless this device is a remote: there it changes nothing — no own value,
     * no machine write — and says where to change it.
     */
    private inline fun editOwnSetting(setting: Setting, edit: () -> Unit) {
        if (refusesOwnEdit(setting)) return
        edit()
    }

    /** Whether [editOwnSetting] refuses [setting] here (a remote), saying so. */
    private fun refusesOwnEdit(setting: Setting): Boolean {
        if (_ui.value.mirror == null) return false
        appendLog("Not changing ${setting.name} on a remote")
        notifyUser("Change on ${_ui.value.remoteHubLabel()}")
        return true
    }



    /** App preferences: load, persist, restore, and the personal setters ([SettingsController]). */
    private val settings: SettingsController = SettingsController(
        store = SettingsStore(app, json),
        scope = viewModelScope,
        uiState = { _ui.value },
        updateUi = { transform -> _ui.update(transform) },
        isTablet = app.resources.configuration.smallestScreenWidthDp >= 600,
        connectionState = { connection.state.value },
        companionIdFor = { presence.companionIdFor(it) },
        pushConfig = { proxy.pushConfig() },
    )

    /** Learned SAW drip-model persistence — an opaque core-owned JSON blob. */
    private val sawModelStore = SawModelStore(app)
    /** Water accumulation and the maintenance readouts ([MaintenanceController]). */
    private val maintenance: MaintenanceController = MaintenanceController(
        lane = lane,
        json = json,
        store = MaintenanceStore(app, json),
        scope = viewModelScope,
        uiState = { _ui.value },
        updateUi = { transform -> _ui.update(transform) },
        appendLog = { appendLog(it) },
    )

    /** Closed in [onCleared]; lives exactly as long as this ViewModel. */
    private val decentClient = DecentClient(json)

    /**
     * Decent account shot upload (#84) — sign-in + the per-shot push to
     * decentespresso.com's shot history. Same self-contained-controller shape
     * as [visualizer]; its [state] is mirrored into [MainUiState.decent] and
     * [sharing] drives it as an upload destination.
     */
    val decent: DecentSync = DecentSync(
        store = DecentStore(app, json),
        client = decentClient,
        scope = viewModelScope,
        buildRecord = { shot, machine, fullSamples ->
            decentShotRecordJson(
                core = UniffiDecentCore,
                json = json,
                shot = shot,
                machine = machine,
                appVersion = coffee.crema.BuildConfig.VERSION_NAME,
                grinderModel = effectiveState().grinderModel.trim().takeIf { it.isNotEmpty() },
                fullSamples = fullSamples,
            )
        },
        shotViewUrl = { serial, id -> UniffiDecentCore.shotViewUrl(serial, id) },
        notify = { msg -> notifyUser(msg) },
        onShotUploaded = { localId, decentId, machine -> library.markShotDecentUploaded(localId, decentId, machine) },
        liveMachine = { sharing.liveMachine() },
        knownSerial = UniffiDecentCore::knownSerial,
        liveSerialUnreadable = { sharing.liveSerialUnreadable() },
        currentShot = { id -> _ui.value.history.firstOrNull { it.id == id } },
        seriesReady = { library.awaitSeries() },
        onLinked = { sharing.offerCatchUp(UploadTargetId.Decent) },
    )

    /** Closed in [onCleared]; lives exactly as long as this ViewModel. */
    private val visualizerClient = VisualizerClient(json)

    /**
     * Visualizer sync — sign-in, account, shot upload. A self-contained
     * controller (deliberately NOT folded into this ViewModel); its [state]
     * is mirrored into [MainUiState.visualizer] below and the UI calls its
     * methods through this public handle.
     */
    val visualizer: VisualizerSync = VisualizerSync(
        store = VisualizerStore(app, json),
        client = visualizerClient,
        json = json,
        scope = viewModelScope,
        clientId = coffee.crema.BuildConfig.VISUALIZER_CLIENT_ID,
        appVersion = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "dev",
        notify = { msg -> notifyUser(msg) },
        onShotSynced = { localId, visualizerId -> library.markShotSynced(localId, visualizerId) },
        grinderModel = { effectiveState().grinderModel.trim().takeIf { it.isNotEmpty() } },
        onPulledShots = { stubs -> library.insertPulledShots(stubs) },
        onBackfillTelemetry = { localId, samples, durationMs -> library.backfillShotTelemetry(localId, samples, durationMs) },
        onFillBarista = { localId, barista -> library.fillShotBarista(localId, barista) },
        onSignedIn = { sharing.offerCatchUp(UploadTargetId.Visualizer) },
        currentShot = { id -> _ui.value.history.firstOrNull { it.id == id } },
        seriesReady = { library.awaitSeries() },
        beanLibrary = { effectiveState().let { coffee.crema.beans.BeanLibrary(it.beans, it.roasters, it.activeBeanId) } },
        onBeansSynced = { result, beanIds, roasterIds -> library.applyBeanSync(result, beanIds, roasterIds) },
        networkWatcher = coffee.crema.visualizer.DefaultNetworkWatcher(app),
    )

    /**
     * Sharing coordination across the cloud destinations ([visualizer],
     * [decent]) — the capture-time fan-out + one notice per shot, the History
     * menu push, the "Upload N" backlog + catch-up offer, and the derived
     * [MainUiState.sharing]. See [SharingController].
     */
    val sharing: SharingController = SharingController(
        scope = viewModelScope,
        visualizer = visualizer,
        decent = decent,
        uiFlow = _ui,
        updateUi = { transform -> _ui.update(transform) },
        notify = { notifyUser(it) },
    )

    /** Visualizer's auto-upload toggle; turning it on offers the backlog once. */
    fun setVisualizerAutoSync(enabled: Boolean) = sharing.setAutoUpload(UploadTargetId.Visualizer, enabled)

    /** Decent's auto-upload toggle; turning it on offers the backlog once. */
    fun setDecentAutoUpload(enabled: Boolean) = sharing.setAutoUpload(UploadTargetId.Decent, enabled)

    fun runCatchUp() = sharing.runCatchUp()

    fun dismissCatchUp() = sharing.dismissCatchUp()

    /** Push the whole backlog to every enabled destination ("Upload N"). */
    fun uploadMissing() = sharing.uploadMissing()

    /** Push one shot to the chosen destinations (the menu picked them) — one notice for all. */
    fun uploadShotTo(shot: StoredShot, targets: List<UploadTarget>) = sharing.uploadShotTo(shot, targets)

    /**
     * Google Drive backup controller — a self-contained sibling of [visualizer].
     * Owns the Drive OAuth + token freshness + the upload/list/download calls; the
     * backup BYTES come from this VM via the callbacks (the same `crema-backup/v1`
     * JSONL the local SAF backup writes). [state] is mirrored into [MainUiState.drive].
     */
    val drive: DriveSync = DriveSync(
        store = DriveStore(app, json),
        json = json,
        scope = viewModelScope,
        clientId = coffee.crema.BuildConfig.GOOGLE_DRIVE_CLIENT_ID,
        notify = { msg -> notifyUser(msg) },
        backupZip = { library.buildBackupZipBytes() },
        backupFileName = { library.backupFileName() },
        applyRestore = { bytes, wipe ->
            viewModelScope.launch { library.restoreBackupBytes(bytes, if (wipe) RestoreMode.WIPE else RestoreMode.MERGE) }
        },
    )

    /**
     * The library controller (review #43) — profiles (built-in + custom +
     * editor), the bean library + roaster directory (+ bag photos), the shot
     * history (capture, edits, discard-undo), and import/export/backup/
     * restore. A self-contained sibling of [visualizer] / [drive] /
     * [ProxyController] / [ConnectionController]; it OWNS the library
     * persistence (five stores + the raw profile-JSON caches). The library
     * rows stay in [MainUiState] and the controller reads/writes them
     * SYNCHRONOUSLY through the uiState/updateUi callbacks — the functions
     * that read them back (the stop targets, the config snapshot, the shot start) read
     * those fields immediately after library mutations, so an async mirror
     * would introduce read-after-write races. Whole-app concerns cross as
     * callbacks below.
     */
    private val library: LibraryController = LibraryController(
        app = app,
        json = json,
        scope = viewModelScope,
        bridge = bridge,
        visualizer = visualizer,
        liveMachine = { sharing.liveMachine() },
        autoUploadShot = { shot, fullSamples -> sharing.autoUpload(shot, fullSamples) },
        applyRestoredRecipes = { recipes, meta, wipe -> guided.applyRestoredRecipes(recipes, meta, wipe) },
        applyRestoredCustomMethods = { methods, wipe -> guided.applyRestoredCustomMethods(methods, wipe) },
        uiState = { _ui.value },
        effectiveState = { effectiveState() },
        updateUi = { transform -> _ui.update(transform) },
        notify = { notifyUser(it) },
        appendLog = { appendLog(it) },
        currentPrefs = { currentPrefs() },
        applyRestoredSettings = { applyRestoredSettings(it) },
        applyRestoredMaintenance = { m -> maintenance.restore(m) },
        persistSawModel = {
            runCatching { sawModelStore.save(bridge.sawModelJson()) }
                .onFailure { appendLog("SAW model save failed: ${it.message}") }
        },
        setActiveProfile = { setActiveProfile(it) },
        resetBrewParams = { resetBrewParams() },
        onShotAnnotationsEdited = { id -> decent.scheduleReplaceAfterEdit(id) },
    )

    // ── Guided brew sessions, recipes, custom methods and the Brew Log ────
    // (issue #10) — see [GuidedBrewController]. It registers the session
    // handlers on [lane] as it is built; its drafts and selections are
    // re-exposed below so the screens keep reading the VM.

    private val guided: GuidedBrewController = GuidedBrewController(
        app = app,
        json = json,
        scope = viewModelScope,
        bridge = bridge,
        lane = lane,
        uiFlow = _ui,
        liveState = { _live.value },
        effectiveState = { effectiveState() },
        updateUi = { transform -> _ui.update(transform) },
        setBrewSessionActive = { bgConnection.setBrewSessionActive(it) },
        persistPrefs = { persistPrefs() },
        addManualBrew = { library.addManualBrew(it) },
        retagBrewMethod = { id, method, label -> library.retagBrewMethod(id, method, label) },
        notify = { notifyUser(it) },
        log = { appendLog(it) },
    )

    /** The running session's weight curve + stage marks, for the live chart. */
    val liveBrewSeries: StateFlow<coffee.crema.core.BrewSeries> get() = guided.liveBrewSeries

    /** The open Log-brew form's draft, or null (see [NavRestore.restoreRoute]). */
    val logBrew: StateFlow<coffee.crema.ui.brewlog.BrewLogDraft?> get() = guided.logBrew

    /** The Scale screen's Weigh | Brew mode and the Brew setup's picks. */
    val guidedSetup: StateFlow<coffee.crema.ui.brewlog.GuidedBrewSetup> get() = guided.guidedSetup

    /** The open recipe editor's working copy, or null. */
    val recipeEdit: StateFlow<coffee.crema.ui.brewlog.RecipeEditDraft?> get() = guided.recipeEdit

    /** The add / edit method dialog's draft, or null. */
    val methodEdit: StateFlow<coffee.crema.ui.brewlog.MethodEditDraft?> get() = guided.methodEdit

    // ── Declared above every `init` block (cohort review 2026-10-25, P3-2) ──
    // Kotlin initialises properties and runs `init` blocks in source order, so
    // a property declared below an init is still null/0 while that init runs
    // (the July 2026 launch crash).

    /**
     * The 1 s core clock while the DE1 link is up or coming up (cohort review
     * 2026-10-28, #2 N1): the upload ack watchdog, the machine-model wait, the
     * re-upload repeat, the lost-scale watchdog and steam eco. The bridge call
     * and its output both run on the core lane, so a tick's events are never
     * stale against a start queued on the lane.
     */
    private val coreTicker by lazy {
        CoreTicker(viewModelScope) {
            kotlinx.coroutines.withContext(lane.dispatcher) {
                lane.applyJson(bridge.onTick(SystemClock.elapsedRealtime().toULong()))
            }
        }
    }

    /** The core's wake hint (`WakeAt`, F16): one one-shot tick on the core
     *  lane, cancelled when the link goes or the app is backgrounded. */
    private val wake by lazy {
        WakeTimer(viewModelScope, { SystemClock.elapsedRealtime() }, { at ->
            kotlinx.coroutines.withContext(lane.dispatcher) {
                lane.applyJson(bridge.onTick(at.toULong()))
            }
        })
    }

    /** Test seam: a wake-up tick is pending. */
    @androidx.annotation.VisibleForTesting
    internal val wakePending: Boolean get() = wake.pending

    /** Arm a session for [recipe]; [startOnPour] starts it at the first pour. */
    fun brewSessionArm(recipe: coffee.crema.core.BrewRecipe, startOnPour: Boolean) = guided.brewSessionArm(recipe, startOnPour)

    /** Start an armed session's clock (the Start tap). */
    fun brewSessionBegin() = guided.brewSessionBegin()
    fun brewSessionPause() = guided.brewSessionPause()
    fun brewSessionResume() = guided.brewSessionResume()
    fun brewSessionSkip() = guided.brewSessionSkip()
    fun brewSessionFinish() = guided.brewSessionFinish()

    /** Drop the session without a summary (backing out of setup / discard). */
    fun brewSessionCancel() = guided.brewSessionCancel()

    /** Clear a finished session's summary card (after save or discard). */
    fun brewSessionClearSummary() = guided.brewSessionClearSummary()

    // Recipe library.
    fun upsertBrewRecipe(recipe: coffee.crema.core.BrewRecipe) = guided.upsertBrewRecipe(recipe)
    fun duplicateBrewRecipe(id: String): coffee.crema.core.BrewRecipe? = guided.duplicateBrewRecipe(id)
    fun isBuiltinRecipe(id: String): Boolean = guided.isBuiltinRecipe(id)
    fun deleteBrewRecipe(id: String) = guided.deleteBrewRecipe(id)
    fun setBuiltinRecipeHidden(id: String, hidden: Boolean) = guided.setBuiltinRecipeHidden(id, hidden)
    fun setDefaultBrewRecipe(recipe: coffee.crema.core.BrewRecipe) = guided.setDefaultBrewRecipe(recipe)
    fun defaultBrewRecipeFor(method: String): coffee.crema.core.BrewRecipe? = guided.defaultBrewRecipeFor(method)

    // Setup selections + the recipe editor.
    fun setScaleMode(mode: String) = guided.setScaleMode(mode)
    fun selectGuidedMethod(method: String) = guided.selectGuidedMethod(method)
    fun selectGuidedRecipe(recipe: coffee.crema.core.BrewRecipe) = guided.selectGuidedRecipe(recipe)
    fun setGuidedStartOnPour(on: Boolean) = guided.setGuidedStartOnPour(on)
    fun selectGuidedBean(key: String) = guided.selectGuidedBean(key)
    fun guidedBeanId(st: MainUiState = effectiveState()): String? = guided.guidedBeanId(st)
    fun openRecipeEdit(owner: String, recipe: coffee.crema.core.BrewRecipe, isNew: Boolean = false) =
        guided.openRecipeEdit(owner, recipe, isNew)
    fun updateRecipeEdit(transform: (coffee.crema.ui.brewlog.RecipeEditDraft) -> coffee.crema.ui.brewlog.RecipeEditDraft) =
        guided.updateRecipeEdit(transform)
    fun setRecipeEditMethod(method: String) = guided.setRecipeEditMethod(method)
    fun closeRecipeEdit() = guided.closeRecipeEdit()
    fun saveRecipeEdit() = guided.saveRecipeEdit()

    // Your own brewing methods.
    fun openNewMethod(target: String, label: String = "") = guided.openNewMethod(target, label)
    fun openEditMethod(id: String) = guided.openEditMethod(id)
    fun updateMethodEdit(transform: (coffee.crema.ui.brewlog.MethodEditDraft) -> coffee.crema.ui.brewlog.MethodEditDraft) =
        guided.updateMethodEdit(transform)
    fun setMethodEditStyle(style: coffee.crema.core.BrewMethodStyle) = guided.setMethodEditStyle(style)
    fun closeMethodEdit() = guided.closeMethodEdit()
    fun saveMethodEdit(): Boolean = guided.saveMethodEdit()
    fun deleteCustomMethod(id: String) = guided.deleteCustomMethod(id)
    fun acceptSaveMethodPrompt() = guided.acceptSaveMethodPrompt()
    fun dismissSaveMethodPrompt() = guided.dismissSaveMethodPrompt()

    /** The finished session's "Save brew…": the Log-brew form, pre-filled with measured truth. */
    fun openGuidedLogBrew() = guided.openGuidedLogBrew()

    /**
     * What this device does for each [MachineCommand]: the bodies behind the
     * verbs. [ProxyController]'s local machine runs them for this device's own
     * UI and for a secondary's relayed commands alike. Declared before [proxy],
     * which takes it; its members reach later fields only when invoked.
     */
    private val machineActions = object : MachineActions {
        override fun applyStartShot(): CommandOutcome = session.applyStartShot()
        override fun applyMachineState(request: MachineRequest) = session.applyMachineState(request)
        override fun applyCancelMaintenance() = session.applyCancelMaintenance()
        override fun applyTareScale() = scaleController.applyTareScale()
        override fun applyHotWaterDose(dose: coffee.crema.core.HotWaterDose) = machineConfig.applyHotWaterDose(dose)
        override fun showConfig(cmd: MachineCommand.Config) = machineConfig.showConfig(cmd)
        override fun commitConfig(cmd: MachineCommand.Config) = machineConfig.commitConfig(cmd)
        override fun hasProfile(id: String): Boolean = library.hasProfile(id)
        override fun hasBean(id: String): Boolean = _ui.value.beans.any { it.id == id }
        // Someone is using Crema on a mirror: that's presence at the machine.
        override fun notePeerActivity() = presence.idleActivity(coffee.crema.core.IdleActivity.Mirror)
        override fun configReady(): Boolean = settings.loaded
    }

    /**
     * The multi-device proxy controller (review #43) — mode switching (normal /
     * hub / remote), the LAN relay + remote-link lifecycle, NSD peer
     * discovery and TOFU pairing. A self-contained sibling of
     * [visualizer] / [drive]; its [ProxyController.State] is mirrored into
     * [MainUiState] in `init`.
     *
     * It OWNS the app-wide BLE transport — the one Nordic-backed transport in
     * NORMAL mode, the relay tap or mirror link otherwise — behind a switchable
     * facade, so it MUST be constructed before the scanner and both BLE
     * managers below (they sit on [ProxyController.transport], and it closes
     * the Nordic `Environment` in [ProxyController.close]).
     *
     * Whole-app concerns stay in this VM, reached through the callbacks:
     * the machine command bodies ([machineActions]), the session-config snapshot/apply and the
     * TOFU paired-device list (persisted with the app prefs). The lambdas referencing
     * later-declared fields ([ble], [scale], [bleRecorder]) are only INVOKED
     * post-construction: the controller starts no role until `init` calls
     * [ProxyController.start] (the field-init-order trap, review #36).
     */
    private val proxy: ProxyController = ProxyController(
        app = app,
        json = json,
        scope = viewModelScope,
        setCoreReadOnly = { bridge.setReadOnly(it) },
        setRecorderEnabled = { bleRecorder.enabled = it },
        notify = { notifyUser(it) },
        appendLog = { appendLog(it) },
        connect = { connect() },
        disconnect = { disconnect() },
        disconnectDevices = { ble.disconnect(); scale.disconnect() },
        connectScale = { connectScale() },
        scaleAttachWanted = {
            scale.state.value.let { it != ScaleBleManager.State.READY && it != ScaleBleManager.State.SCANNING }
        },
        de1Connected = { ble.connectedAddress != null },
        machineStateName = { _ui.value.machineStateName?.string },
        machineActions = machineActions,
        snapshotConfig = { remoteConfig.configSnapshotJson() },
        applyConfig = { remoteConfig.applyRemoteConfig(it) },
        // The devices this primary holds: the DE1, plus the scale when one is
        // connected (issue 04) — its advertised name lets a secondary's scale
        // manager scan-match + re-derive the codec via connectScale.
        roster = {
            buildList {
                ble.connectedAddress?.let { add(DeviceInfo(it, "DE1", "de1", "CONNECTED")) }
                scale.connectedAddress?.let { addr ->
                    add(DeviceInfo(addr, scale.connectedName ?: "Scale", "scale", "CONNECTED"))
                }
            }
        },
        // Latest-value state/identity chars snapshot on attach; the COUNTED
        // streams — the DE1 ShotSample and the scale weight — stay live-only or
        // the mirror's core double-counts (issue 04; M1-PROTOCOL §5).
        isSnapshotChar = { _, char -> char != De1Uuids.SHOT_SAMPLE && char != scale.weightNotifyChar },
        firmwareHoldsLink = { runCatching { bridge.firmwareLocksWrites() }.getOrDefault(false) },
        onRoleStarting = { role, refind -> remoteConfig.onRoleStarting(role, refind) },
        showPending = { remoteConfig.showPending(it) },
        onWelcomed = { hubName -> _ui.update { s -> s.copy(mirror = s.mirror?.welcomed(hubName)) } },
        onModeApplied = { role, host, port ->
            _ui.update {
                it.copy(
                    proxyRole = role,
                    // Keep the secondary target sticky across mode changes: only a
                    // `secondary` switch updates host/port. Otherwise stopping a mirror
                    // (→ normal) would wipe the debug/manual peer from the picker, so on
                    // a network with no NSD you couldn't re-mirror without re-seeding it.
                    proxyPrimaryHost = if (role == "secondary") host else it.proxyPrimaryHost,
                    proxyPrimaryPort = if (role == "secondary") port else it.proxyPrimaryPort,
                    // A new remote target's Welcome names its hub again.
                    mirroringPrimaryName = "",
                )
            }
            persistPrefs()
        },
        // The re-find reattached on a new port of the saved host: that is
        // the remote's target now (same role, same hub, nothing to drop).
        // Applied after the prefs load, which would otherwise put the old
        // target back (and persistPrefs does nothing before it).
        onTargetMoved = { host, port ->
            viewModelScope.launch {
                settings.ready.await()
                _ui.update { it.copy(proxyPrimaryHost = host, proxyPrimaryPort = port) }
                persistPrefs()
            }
        },
        prefsReady = { settings.ready.await() },
    )

    /**
     * The VM side of hub / remote config ([RemoteConfigController]): the hub's
     * config snapshot, a remote's mirror slice and its pending edits, and the
     * role switches with the T6 adoption. Built after [proxy], which it drives.
     */
    private val remoteConfig: RemoteConfigController = RemoteConfigController(
        proxy = proxy,
        json = json,
        scope = viewModelScope,
        uiState = { _ui.value },
        updateUi = { transform -> _ui.update(transform) },
        effectiveState = { effectiveState() },
        appendLog = { appendLog(it) },
        notify = { notifyUser(it) },
        pushCoreInputs = { machineConfig.pushCoreInputs(it) },
        persistPrefsNow = { persistPrefsNow() },
        profileJson = { library.profileJson(it) },
        isBuiltinProfile = { library.isBuiltinProfile(it) },
        hasProfile = { library.hasProfile(it) },
        adoptCustomProfile = { library.adoptCustomProfile(it) },
        persistLibraryNow = { library.persistLibraryNow() },
    )

    /**
     * The one app-wide BLE scanner, shared by both managers. A single
     * unfiltered scan dispatches each result by advertised-name match to
     * whichever device currently wants to connect — see [connect] / [connectScale].
     */
    private val bleScanner = BleScanner(
        transport = proxy.transport,
        onStatus = { line -> _ui.update { it.copy(status = line) } },
    )

    /**
     * The one session-wide BLE recorder, shared by both managers. A connection
     * counter inside it opens a single capture file when the first device (DE1
     * or scale) connects and closes it when the last disconnects, so a session
     * with both devices produces ONE interleaved file that replays through a
     * single `CremaCore` — important for scale-aware behaviour like shot-start
     * auto-tare.
     */
    private val bleRecorder = BleSessionRecorder(app)

    private val ble: De1BleManager = De1BleManager(
        transport = proxy.transport,
        bridge = bridge,
        recorder = bleRecorder,
        onCoreOutput = lane::deliver,
        onStatus = { line -> _ui.update { it.copy(status = line) } },
        timeline = coffee.crema.diag.ReconnectTimelines.recorder,
    )

    /** Keeps the process — and thus the DE1 link + its reconnect loop — alive
     *  while the screen is off (geota/crema#65). Gated on the user's pref AND
     *  charging by [BackgroundConnectionController]; fed the live DE1 state,
     *  effective setting, and foreground state below. */
    private val bgConnection = coffee.crema.ble.BackgroundConnectionController(app)

    /**
     * Firmware updates (alpha, [FirmwareController]): the flash driver's scope,
     * its link surface and the Settings flow ([firmware]). Built after [ble],
     * which the flash drives; its callbacks reach later members only when
     * invoked.
     */
    private val firmwareCtl: FirmwareController = FirmwareController(
        app = app,
        json = json,
        bridge = bridge,
        ble = ble,
        scope = viewModelScope,
        uiState = { _ui.value },
        appendLog = { appendLog(it) },
        setFlashActive = { active -> bgConnection.setFirmwareFlashActive(active) },
        afterRelease = { session.afterFirmwareRelease() },
    )

    /** Settings → Machine → "Update firmware…" (alpha). */
    val firmware: coffee.crema.firmware.FirmwareUpdateController get() = firmwareCtl.update

    /**
     * A flash (or its erase / power-cycle window) holds the DE1 link: the
     * reconnect side-effects stay off the machine ([FirmwareController.holds]).
     */
    private fun firmwareHolds(): Boolean = firmwareCtl.holds()


    /**
     * The Bookoo scale connection. Shares the one [bridge] with [ble]: the core
     * is internally `Mutex`-guarded, so both managers feeding it concurrently
     * is safe. Its `CoreOutput` goes through the same [CoreLane.deliver] path.
     * It also shares the one [bleRecorder] so its weight notifications and
     * tare/timer writes land in the same interleaved capture file as the DE1's.
     */
    private val scale: ScaleBleManager = ScaleBleManager(
        transport = proxy.transport,
        bridge = bridge,
        recorder = bleRecorder,
        onCoreOutput = lane::deliver,
        onStatus = { line -> _ui.update { it.copy(status = line) } },
        onScaleIdentified = { scaleController.refreshScaleCapabilities(it) },
        timeline = coffee.crema.diag.ReconnectTimelines.recorder,
    )

    /** The scale session's UI, settings and verbs ([ScaleController]). */
    private val scaleController: ScaleController = ScaleController(
        lane = lane,
        json = json,
        bridge = bridge,
        scope = viewModelScope,
        uiState = { _ui.value },
        updateUi = { transform -> _ui.update(transform) },
        updateLive = { transform -> _live.update(transform) },
        appendLog = { appendLog(it) },
        notify = { notifyUser(it) },
        refusesOwnEdit = { refusesOwnEdit(it) },
        refreshStopTargets = { session.refreshStopTargetProjection() },
        presumeDead = { reason -> scale.presumeDead(reason) },
    )

    /**
     * The device-connection controller (review #43) — DE1/scale connect and
     * disconnect verbs, the manager-state collectors, the Bluetooth-adapter
     * watcher, per-device auto-connect, the scale heartbeat, and the DE1
     * keep-awake tick. A self-contained sibling of [proxy] / [visualizer] /
     * [drive]; its [ConnectionController.State] is mirrored into [MainUiState]
     * in `init`. The managers themselves stay fields of this VM (their core
     * output and command routing are VM concerns); the controller owns their
     * connection lifecycle. Whole-app reactions stay here as callbacks.
     */
    /** Companion-device associations + presence (Android CDM; optional per
     *  device, user-approved via the system dialog). */
    private val companion = coffee.crema.ble.companion.CompanionCoordinator(
        coffee.crema.ble.companion.AndroidCompanionGateway(app),
        coffee.crema.ble.companion.CompanionPresence.presence,
    )

    private val connection: ConnectionController = ConnectionController(
        app = app,
        scope = viewModelScope,
        coreDispatcher = lane.dispatcher,
        ble = ble,
        scale = scale,
        bleScanner = bleScanner,
        transport = proxy.transport,
        appendLog = { appendLog(it) },
        persistPrefs = { persistPrefs() },
        onConnectStarted = { _live.update { it.copy(eventLog = emptyList()) } },
        onDe1SessionClosed = { session.clearDe1SessionUi() },
        onScaleSessionClosed = { scaleController.clearScaleSessionUi() },
        onDe1Ready = {
            // Into a DE1 still in a flash / reboot window, nothing but the
            // flash's own build read (audit H2); [afterFirmwareRelease] runs
            // these once it lets go.
            if (firmwareHolds()) {
                appendLog("Firmware: DE1 ready during a firmware update — connect setup deferred")
            } else {
                session.readMachineInfo()
                session.sweepRunner.onReady()
            }
            presence.linkIdleSleep(true)
            // Re-assert the stop targets on every (re)connect. The core now
            // carries them across `reset()` itself, so this is belt-and-braces
            // — but it is the only thing standing between a GHC-started shot
            // and an unarmed auto-stop if the core is ever rebuilt some other
            // way (geota/crema#56). Pure core state, no BLE.
            session.pushStopTargets()
            // "Keep connected in the background" sets up Android's "nearby"
            // association once — a few seconds in, once the machine state is
            // known (it only runs on an idle machine).
            presence.scheduleNearbySetup()
        },
        // The core's profile sync forgets what this connection pushed when
        // the link drops (`de1LinkLost` / `reset`) and uploads again after
        // the next connect sweep.
        onDe1Dropped = {
            firmwareCtl.setupDone = false
            session.sweepRunner.onDropped()
            presence.linkIdleSleep(false)
            // The last state the DE1 reported is history once the link is
            // gone. Until the next connect's StateInfo seed read lands, the
            // Brew hero must say "syncing", not replay "Idle" from before the
            // drop (BrewReadiness).
            _ui.update { it.copy(machineState = null, machineStateName = null, machineSubstate = null) }
        },
        pushRoster = { proxy.pushRoster() },
        refreshAdvertisement = { proxy.refreshAdvertisement() },
        heartbeatIntervalMs = { _ui.value.scaleCapabilities?.heartbeat_interval_ms?.toLong() },
        sendScaleHeartbeat = { lane.deliver(bridge.scaleHeartbeat()) },
        isAppForeground = { presence.appInForeground },
        timeline = coffee.crema.diag.ReconnectTimelines.recorder,
        companion = companion,
        presenceEvents = coffee.crema.ble.companion.CompanionPresence.events,
    )

    /**
     * Presence ([PresenceController]): the screensaver, the idle-sleep timer,
     * sleep-on-quit, app foreground and the companion "nearby" setup. Built
     * after [connection] and [companion]; its lambdas reach later members only
     * when invoked.
     */
    private val presence: PresenceController = PresenceController(
        lane = lane,
        bridge = bridge,
        scope = viewModelScope,
        uiState = { _ui.value },
        updateUi = { transform -> _ui.update(transform) },
        appendLog = { appendLog(it) },
        notify = { notifyUser(it) },
        persistPrefs = { persistPrefs() },
        firmwareHolds = { firmwareHolds() },
        de1Ready = { ble.state.value == De1BleManager.State.READY },
        wake = { wake() },
        quitSleepWrite = { session.launchQuitSleepWrite() },
        companion = companion,
        connectionState = { connection.state.value },
        connectTo = { connection.connectTo(it) },
        disconnect = { disconnect() },
        setBackgroundStartAllowed = { bgConnection.setBackgroundStartAllowed(it) },
    )

    /** Every store file reported unreadable this process (see [KeptAsideNotices]). */
    private var keptAside: List<KeptAside> = emptyList()

    /** This VM's reconnect-timeline hook. The deferred teardown in
     *  [onCleared] releases exactly this one (and [presence] its sleep-on-quit
     *  registration), so a VM built meanwhile keeps its own (final cohort
     *  review 2026-10-28, 04 P3-1). */
    private val timelineLogHook: (coffee.crema.ble.ReconnectTimeline) -> Unit = { appendLog(it.compactLine()) }

    // ── Declared above every `init` block too (P3-2) ──────────────────────
    // Moved up from their feature sections below, in their original order,
    // so that no property is declared after an `init` block.

    /** The whole startup load (library, prefs, custom profiles, …). Tests wait
     *  for it: [SettingsController.ready] completes partway through. */
    @androidx.annotation.VisibleForTesting
    internal lateinit var startupLoad: kotlinx.coroutines.Job

    // ── `init` blocks: every property is declared above this point (P3-2) ──

    init {
        // Multi-device (M2): start the persisted proxy role (a primary's relay,
        // a secondary's mirror link), arm the recorder gate, start NSD peer
        // discovery and advertise this instance. First, so the managers'
        // transport is the role's before anything can drive it, and here, not
        // in the controller's construction: it reaches [bleRecorder]/[ble].
        proxy.start()
        // Seed the recipe library from disk and keep the custom-method labels current.
        guided.load()
    }

    init {
        // Unreadable store files: the stores move them aside as they load and
        // report here; tell the user once instead of showing silently-empty data.
        viewModelScope.launch {
            KeptAsideNotices.pending.collect { pending ->
                if (pending.isEmpty()) return@collect
                keptAside = keptAside + KeptAsideNotices.drain()
                val msg = KeptAsideNotices.message(keptAside)
                appendLog(msg)
                _ui.update { it.copy(keptAsideNotice = msg) }
            }
        }
        // A hub's remotes hold no live copy of its stock: a change to the active
        // bag's weights re-sends the snapshot that carries its empty flag.
        viewModelScope.launch {
            _ui.map { s -> s.beans.firstOrNull { it.id == s.activeBeanId }?.let { it.remaining to it.bagSize } }
                .distinctUntilChanged()
                .collect { proxy.pushConfig() }
        }
        // Each finished reconnect episode → one compact line in the event log
        // (and so the diagnostics ring buffer).
        coffee.crema.diag.ReconnectTimelines.recorder.onFinished = timelineLogHook
        // A companion device appeared: the keep-alive may start now, even from
        // the background (the CDM exemption) — its own gates still apply. The
        // reconnect kick itself is the connection controller's.
        viewModelScope.launch {
            coffee.crema.ble.companion.CompanionPresence.events.collect { e ->
                if (e.present) bgConnection.onCompanionPresence()
            }
        }
    }

    /** The kept-aside snackbar was dismissed or timed out. */
    fun consumeKeptAsideNotice() {
        _ui.update { it.copy(keptAsideNotice = null) }
    }

    /** Share the kept-aside copies (the snackbar's "Share") through the system
     *  share sheet, e.g. to attach to a bug report or recover by hand. */
    fun shareKeptAside() {
        consumeKeptAsideNotice()
        val files = keptAside.mapNotNull { it.keptAt }.filter { it.isFile }
        if (files.isEmpty()) return
        runCatching {
            // FileProvider only serves cacheDir/kept-aside/; copy, never move.
            val dir = File(getApplication<Application>().cacheDir, "kept-aside").apply { mkdirs() }
            val authority = "${coffee.crema.BuildConfig.APPLICATION_ID}.fileprovider"
            val uris = ArrayList(
                files.map { f ->
                    val copy = f.copyTo(File(dir, f.name), overwrite = true)
                    FileProvider.getUriForFile(getApplication(), authority, copy)
                },
            )
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }.apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_SUBJECT, "Crema: unreadable data files")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Share damaged files")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(chooser)
        }.onFailure { notifyUser("Couldn't share the damaged files: ${it.message}") }
    }

    init {
        // Sleep-on-quit (see [PresenceController.installQuitSleep]).
        presence.installQuitSleep()
        // Mirror the proxy controller's state slice into the UI snapshot (the
        // same pattern as [visualizer]/[drive] below).
        viewModelScope.launch {
            proxy.state.collect { ps ->
                // Relay v4's one-time notices (spec §6.3, §6.4), shown once each.
                if (ps.keyResetNotice) {
                    notifyUser(KEY_RESET_COPY)
                    proxy.dismissKeyResetNotice()
                }
                _ui.update {
                    it.copy(
                        peers = ps.peers,
                        mirrorReconnecting = ps.mirrorReconnecting,
                        mirrorViewOnly = ps.mirrorViewOnly,
                        pendingPairing = ps.pendingPairing,
                        pairingSecondsLeft = ps.pairingSecondsLeft,
                        pairingLockedSecondsLeft = ps.pairingLockedSecondsLeft,
                        // Relay v4: the pins are the paired devices (never persisted with the prefs).
                        pairedDevices = ps.pairedRemotes.map { r -> PairedDevice(r.id, r.name, r.scope == "control", r.lastSeenMs) },
                        remoteConnecting = ps.remoteConnecting,
                        pairedHubs = ps.pairedHubs,
                        relayPairing = ps.pairing,
                        relayKeysUnavailable = ps.relayKeysUnavailable,
                        relayKeyResetNotice = ps.keyResetNotice,
                        relayNeedsRepair = ps.needsRepair,
                        relayUpgradeNotice = ps.upgradeNotice,
                        remotes = ps.remotes,
                        // The link to the hub dropped: pending edits become
                        // unconfirmed (never resent) until it's back.
                        mirror = it.mirror?.let { m ->
                            if (ps.mirrorReconnecting && !m.reconnecting) m.reconnectingNow() else m
                        },
                        // The Welcome names the hub before its first snapshot does.
                        mirroringPrimaryName = ps.hubName.ifBlank { it.mirroringPrimaryName },
                        remoteHubHasMachine = ps.hubHasMachine,
                        refindGaveUp = ps.refindGaveUp,
                        hubStoppedHosting = ps.hubStoppedHosting,
                        hubHoldingMachine = if (ps.role == "normal") {
                            ps.peers.firstOrNull { p -> p.isMirrorSource && p.id != proxy.instanceId }
                        } else {
                            null
                        },
                        pairedHubPinned = ps.pairedHubPinned,
                    )
                }
            }
        }
        // Start the connection controller — the Bluetooth-adapter watcher, the
        // manager-state collectors, and the DE1 keep-awake tick — and mirror
        // its state slice into the UI snapshot.
        connection.start()
        // Keep-alive service watcher: register the power receiver + evaluate.
        bgConnection.start()
        // A flash that was waiting for the DE1's power-cycle when the process
        // died comes back with its lock (audit M3).
        firmware.restore()
        // The firmware flash hears link loss from the core (de1LinkLost /
        // reset) and needs the recovery to re-read the build after the
        // power-cycle.
        viewModelScope.launch {
            var wasReady = false
            ble.state.collect { st ->
                coreTicker.onLinkState(st)
                // Nothing the core waited for applies to a link that is gone
                // (the BLE manager resets the core on a drop).
                if (st != De1BleManager.State.READY) wake.cancel()
                val ready = st == De1BleManager.State.READY
                if (ready && !wasReady) firmware.onLinkRestored(ble.connectedAddress)
                if (!ready && wasReady) firmware.onLinkLost()
                wasReady = ready
            }
        }
        // The lost-scale watchdog runs on the core clock: a scale alone keeps
        // it ticking (a scale-only session).
        viewModelScope.launch { scale.state.collect { coreTicker.onScaleState(it) } }
        viewModelScope.launch {
            connection.state.collect { cs ->
                _ui.update {
                    it.copy(
                        bleState = cs.de1,
                        scaleState = cs.scale,
                        de1BluetoothAddress = cs.de1Address,
                        bluetoothOn = cs.bluetoothOn,
                        rememberedDe1Address = cs.rememberedDe1Address,
                        rememberedScaleAddress = cs.rememberedScaleAddress,
                        rememberedScaleName = cs.rememberedScaleName,
                        de1Reconnecting = cs.de1Reconnecting,
                        scaleReconnecting = cs.scaleReconnecting,
                    )
                }
                // The background keep-alive is wanted whenever a DE1 connection
                // is live or being pursued (not idle / not user-disconnected).
                bgConnection.setDe1Active(
                    cs.de1 != De1BleManager.State.IDLE &&
                        cs.de1 != De1BleManager.State.DISCONNECTED,
                )
            }
        }
        library.loadBuiltinProfiles()
        // Load both persisted stores in one coroutine, sequentially, so their
        // _ui copies don't race (read-modify-write of the shared snapshot).
        startupLoad = viewModelScope.launch {
            holdStartupLoad?.await()
            library.loadLibrary()
            library.loadHistory()
            loadPrefs()
            // Seed the learned SAW drip model into the core (it survives
            // bridge.reset() in-core, so startup is the only seed point).
            // A corrupt blob is quarantined, not lost (SawModelStore.seed).
            runCatching { sawModelStore.seed(bridge::loadSawModelJson, ::appendLog) }
                .onFailure { appendLog("SAW model seed failed: ${it.message}") }
            library.loadProfileMeta()
            library.loadCustomProfiles()
            // Seed the core's stop targets from the profile just restored.
            // Startup restore doesn't go through setActiveProfile, so without
            // this the core holds no weight/volume/max-time target until the
            // first app-started shot — and a shot started at the machine's GHC
            // never triggers that push, so it would arm nothing at all
            // (geota/crema#56). Pure core state; safe while disconnected.
            session.pushStopTargets()
            maintenance.load()
            visualizer.load()
            // Uploads that failed last time retry now (F17).
            visualizer.drainSoon()
            // Daily Visualizer Premium check (gates the edit sync's Premium-only fields).
            visualizer.refreshPremiumIfStale()
            decent.load()
            sharing.start()
            drive.load()
            // Daily automatic Drive backup (issue #36) — after both the prefs
            // (the toggle) and the Drive tokens have hydrated.
            drive.autoBackupIfDue(_ui.value.autoDriveBackup)
            // Daily automatic release check (opt-in, default off).
            maybeAutoUpdateCheck()
        }
        startupLoad.invokeOnCompletion {
            // A load that failed before the prefs still lets a hub serve.
            settings.ready.complete(Unit)
        }
        presence.startScreensaverClock()
        // Mirror the Visualizer controller's state into the UI snapshot.
        viewModelScope.launch {
            visualizer.state.collect { vs ->
                _ui.update { it.copy(visualizer = vs) }
            }
        }
        // Mirror the Decent account controller's state (#84).
        viewModelScope.launch {
            decent.state.collect { ds ->
                _ui.update { it.copy(decent = ds) }
            }
        }
        // Mirror the Drive controller's state into the UI snapshot.
        viewModelScope.launch {
            drive.state.collect { ds ->
                _ui.update { it.copy(drive = ds) }
            }
        }
    }

    /** Select the Brew header's active profile. (M1: display only — uploading the
     *  profile to the DE1 on Coffee is the M2 gated-start sequence.) Persisted
     *  (AppPrefs.activeProfileId) so the selection survives a restart. */
    fun setActiveProfile(id: String) = machine.execute(MachineCommand.SetActiveProfile(id))

    // ── Library delegates (see [LibraryController]) ──────────────────────────
    // Thin pass-throughs so screens keep calling the same VM API.

    /** Toggle a profile's Favorites star (custom JSON patch / pinned-ids set). */
    fun togglePinProfile(id: String) = library.togglePinProfile(id)

    /** Persist the Quick-Controls dial values into the active profile (or a copy). */
    fun saveQuickPreset(name: String) = library.saveQuickPreset(name)

    /** Open an existing saved (custom) profile in the editor. */
    fun startEditProfile(id: String) = library.startEditProfile(id)

    /** Begin a brand-new custom profile draft seeded from the brew defaults. */
    fun startNewProfile() = library.startNewProfile()

    /** Duplicate any profile into a fresh custom draft and open the editor. */
    fun duplicateProfile(id: String) = library.duplicateProfile(id)

    /** Save the editor's non-curve edits (upsert into the custom store). */
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
    ) = library.saveProfile(
        id, name, roast, tags, pinned, notes, author, beverageType, dose, yieldOut,
        brewTemp, maxTotalVolumeMl, preinfuseStepCount, tankTemperatureC, segments,
    )

    /** The editor's segments after its Brew temp field moved to [target]: every
     *  step shifted by the core (Decenza `applyTemperatureToProfile`). */
    fun editorBrewTemp(id: String, segments: List<SegmentEdit>, target: Double): List<SegmentEdit> =
        library.editorBrewTemp(id, segments, target)

    /** The editor's Brew temp field range — the core's per-profile range (F6). */
    fun editorBrewTempRange(id: String, segments: List<SegmentEdit>): Pair<Double, Double>? =
        library.editorBrewTempRange(id, segments)

    /** Close the editor without saving (discards any new / duplicated draft). */
    fun cancelProfileEdit() = library.cancelProfileEdit()

    /** Delete a custom profile (no-op for built-ins). */
    fun deleteProfile(id: String) = library.deleteProfile(id)

    /** Archive (hide) a built-in profile from the active grid. */
    fun archiveBuiltinProfile(id: String) = library.archiveBuiltinProfile(id)

    /** Restore a hidden built-in profile back to the active grid. */
    fun unarchiveBuiltinProfile(id: String) = library.unarchiveBuiltinProfile(id)

    /** Share a profile as the core's community-v2 `.json` profile file. */
    fun exportProfile(id: String) = library.exportProfile(id)

    /** Export a stored shot as JSON via the system share sheet. */
    fun exportShot(id: String) = library.exportShot(id)

    /** Export the whole shot history as a JSON array — Settings → Sharing. */
    fun exportAllShots() = library.exportAllShots()

    /** Export the bean library (beans + roasters) as JSON — Settings → Sharing. */
    fun exportBeansLibrary() = library.exportBeansLibrary()

    /** Bean library as Crema JSON (beans + roasters), or null when empty. */
    fun beansLibraryJson(): String? = library.beansLibraryJson()

    /** Library in Beanconqueror's format (via the core), or null when empty. */
    fun beansBeanconquerorJson(): String? = library.beansBeanconquerorJson()

    /** Shots as Crema JSON — all (ids null) or only the given ids. */
    fun shotsJson(ids: List<String>?): String? = library.shotsJson(ids)

    /** The core's shot-quality analysis over a stored shot (review #39). */
    fun analyzeShotQuality(shot: StoredShot): ShotQualityReport? = library.analyzeShotQuality(shot)

    /** All profiles (built-ins + customs) as the core's `.jsonl` profile file, or null when empty. */
    fun allProfilesFile(): String? = library.allProfilesFile()

    /** Write export [text] to a SAF document Uri (large-payload safe). */
    fun writeTextToUri(uri: Uri, text: String) = library.writeTextToUri(uri, text)

    /** Write raw [bytes] to a SAF document Uri — the binary backup save. */
    fun writeBytesToUri(uri: Uri, bytes: ByteArray) = library.writeBytesToUri(uri, bytes)

    /** Import profiles (Crema / community-v2 / legacy .tcl, via the core). */
    fun importProfiles(uri: Uri) = library.importProfiles(uri)

    /** Import shots from a Crema / de1app / legacy export. */
    fun importShots(uri: Uri) = library.importShots(uri)

    /** Wrap the backup JSONL + bean photos into a `.crema.zip`, or null + a
     *  notice. Suspends: the build runs off the main thread. */
    suspend fun buildBackupZipBytes(): ByteArray? = library.buildBackupZipBytes()

    /** A suggested filename for the SAF create-document picker. */
    fun backupFileName(): String = library.backupFileName()

    /** Restore a `crema-backup` bundle from a SAF [uri]. */
    fun restoreBackup(uri: Uri, mode: RestoreMode) = library.restoreBackup(uri, mode)

    /** Apply a backup from raw bytes (SAF file or Google Drive). */
    suspend fun restoreBackupBytes(bytes: ByteArray, mode: RestoreMode) = library.restoreBackupBytes(bytes, mode)

    /** Parse + apply a `crema-backup` bundle's raw text. */
    fun restoreBackupFromText(text: String, mode: RestoreMode) = library.restoreBackupFromText(text, mode)

    /**
     * Remove a stored shot from the local history. Persisted. A shot already
     * on Visualizer leaves a tombstone so the next pull doesn't bring its
     * remote copy back (web parity); [alsoOnVisualizer] deletes that copy too,
     * retried on each sync until it lands. A shot never uploaded just goes.
     */
    fun deleteShot(id: String, alsoOnVisualizer: Boolean = false) {
        val shot = _ui.value.history.firstOrNull { it.id == id } ?: return
        library.deleteShot(id)
        shot.visualizerId?.let { visualizer.tombstoneShot(id, it, deleteRemote = alsoOnVisualizer) }
    }

    /** Whether a shot's delete can also remove its Visualizer copy: it was
     *  uploaded and a session is open (shot deletes aren't Premium-gated, unlike bags). */
    fun canDeleteShotOnVisualizer(visualizerId: String?): Boolean =
        visualizerId != null && _ui.value.visualizer.signedIn

    /** Import a Beanconqueror export (JSON or chunked .zip) into the library. */
    fun importBeanconquerorUri(uri: Uri) = library.importBeanconquerorUri(uri)

    /** Open a bean in the editor (the `bean-edit` route reads this). */
    fun startEditBean(id: String) = library.startEditBean(id)

    /** Start a brand-new bean draft in the full editor. */
    fun startNewBean() = library.startNewBean()

    /** Apply edits to a bean (the editor's Save; find-or-create the roaster). */
    fun updateBean(
        id: String,
        roasterName: String,
        roasterPick: coffee.crema.core.CataloguePickRoaster? = null,
        transform: (Bean) -> Bean,
    ) = library.updateBean(id, roasterName, roasterPick, transform)

    /** A FileProvider Uri for the camera to write a bag photo into. */
    fun newCameraOutputUri(beanId: String): Uri? = library.newCameraOutputUri(beanId)

    /** Store a captured / picked bag photo for [beanId]. Persisted. */
    fun setBeanImageFromUri(beanId: String, uri: Uri) = library.setBeanImageFromUri(beanId, uri)

    /** Remove a bean's bag photo. Persisted. */
    fun clearBeanImage(beanId: String) = library.clearBeanImage(beanId)

    /**
     * Remove a bean bag; reselect the first remaining if it was active.
     * [alsoOnVisualizer] mirrors the delete to its Visualizer copy (web
     * "Delete here and on Visualizer").
     */
    fun deleteBean(id: String, alsoOnVisualizer: Boolean = false) = library.deleteBean(id, alsoOnVisualizer)

    /** Whether a delete can also remove the row's Visualizer copy: signed in and the row is synced. */
    fun canDeleteOnVisualizer(visualizerId: String?): Boolean =
        visualizerId != null && _ui.value.visualizer.signedIn && _ui.value.visualizer.premium != false

    /** Duplicate a bag into a fresh row. Persisted. */
    fun duplicateBean(id: String) = library.duplicateBean(id)

    /** Freeze a bag (stamps `frozenOn`, pausing freshness). Persisted. */
    fun freezeBean(id: String) = library.freezeBean(id)

    /** Defrost a bag (records `defrostedOn`, keeps `frozenOn`). Persisted. */
    fun defrostBean(id: String) = library.defrostBean(id)

    /** Archive a bag. Persisted. */
    fun archiveBean(id: String) = library.archiveBean(id)

    /** The bag-empty prompt's Archive or Keep. Archiving drops the bag as the
     *  active one too, so the next shot isn't billed to an archived bag (web's
     *  `promptBagEmpty`). */
    fun answerBagEmpty(archive: Boolean) {
        val beanId = _ui.value.bagEmptyPrompt?.beanId
        library.answerBagEmpty(archive)
        if (archive && effectiveState().activeBeanId == beanId) setActiveBean(null)
    }

    /** Close the bag-empty prompt. */
    fun dismissBagEmptyPrompt() = library.dismissBagEmptyPrompt()

    /** Unarchive a bag. Persisted. */
    fun unarchiveBean(id: String) = library.unarchiveBean(id)

    /** Toggle the brew-page favourite star. Persisted. */
    fun toggleBeanFavourite(id: String) = library.toggleBeanFavourite(id)

    /** Select the roaster the `roaster-edit` route edits (null = new draft). */
    fun startEditRoaster(id: String?) = library.startEditRoaster(id)

    /** Add a roaster to the directory. Persisted. */
    fun addRoaster(name: String, website: String?, city: String?, country: String?, notes: String, catalogueRoasterId: String? = null) =
        library.addRoaster(name, website, city, country, notes, catalogueRoasterId)

    /** Update a roaster's editable fields + catalogue link. Persisted. */
    fun updateRoaster(id: String, name: String, website: String?, city: String?, country: String?, notes: String, catalogueRoasterId: String?) =
        library.updateRoaster(id, name, website, city, country, notes, catalogueRoasterId)

    /**
     * Delete a roaster: detach its bags, or [cascade] = delete them too.
     * [alsoOnVisualizer] also deletes the Visualizer copies. Persisted.
     */
    fun deleteRoaster(id: String, alsoOnVisualizer: Boolean = false, cascade: Boolean = false) =
        library.deleteRoaster(id, alsoOnVisualizer, cascade)

    /** Whether a roaster delete can also remove Visualizer copies (signed in, not free, something synced). */
    fun canDeleteRoasterOnVisualizer(roaster: coffee.crema.core.Roaster, cascade: Boolean): Boolean =
        _ui.value.visualizer.signedIn && _ui.value.visualizer.premium != false &&
            coffee.crema.beans.roasterRemoteDeleteAvailable(roaster, _ui.value.beans, cascade)

    /** Merge [dupeId] into [canonicalId]: move its bags, tag it as a duplicate (web Roasters tab). Persisted. */
    fun mergeRoaster(canonicalId: String, dupeId: String) = library.mergeRoaster(canonicalId, dupeId)

    /** Un-merge a tagged duplicate (clear its duplicate-of pointer). Persisted. */
    fun unmergeRoaster(id: String) = library.unmergeRoaster(id)

    /** Roaster merge suggestions for the Roasters tab (core rule); empty if the core call fails. */
    fun roasterDuplicates(roasters: List<coffee.crema.core.Roaster>): List<coffee.crema.core.RoasterDuplicate> =
        runCatching { coffee.crema.beans.detectRoasterDuplicates(json, roasters) }.getOrDefault(emptyList())

    /** Open a roaster's website in the browser (best-effort). */
    fun visitRoasterWebsite(url: String?) = library.visitRoasterWebsite(url)

    /** Apply a user rating / tasting-notes edit to a logged shot. Persisted. */
    fun updateShot(id: String, rating: Int, notes: String) = library.updateShot(id, rating, notes)

    /** Set (or clear) a shot's per-upload Visualizer privacy override. */
    fun setShotPrivacy(id: String, privacy: String?) = library.setShotPrivacy(id, privacy)

    /** Set (or clear) the grind recorded on a logged shot (issue #16). */
    fun setShotGrind(id: String, grind: Float?) = library.setShotGrind(id, grind)

    /** Re-attribute a logged shot to another bean, or none (issue #16). */
    fun setShotBean(id: String, beanId: String?) = library.setShotBean(id, beanId)

    // ── Brew Log (issue #10) ─────────────────────────────────────────
    /** Record a manual brew / guided-session result; debits the bag. */
    fun addManualBrew(input: LibraryController.ManualBrewInput) = library.addManualBrew(input)

    /** Open the form on [owner]'s tab — seeded from [prefill] ("Log again") or [beanId]'s bag. */
    fun openLogBrew(owner: String, prefill: StoredShot? = null, beanId: String? = null) = guided.openLogBrew(owner, prefill, beanId)
    fun updateLogBrew(transform: (coffee.crema.ui.brewlog.BrewLogDraft) -> coffee.crema.ui.brewlog.BrewLogDraft) =
        guided.updateLogBrew(transform)
    fun reseedLogBrew(method: String) = guided.reseedLogBrew(method)
    fun closeLogBrew() = guided.closeLogBrew()

    /** Validate + save the draft as a manual brew (bag debit included) and close the form. */
    fun saveLogBrew(nowMs: Long = System.currentTimeMillis()): Boolean = guided.saveLogBrew(nowMs)

    /** Edit a MANUAL brew row's user-entered facts (re-settles the bag on dose). */
    fun updateManualBrew(
        id: String,
        doseG: Float? = null,
        waterG: Float? = null,
        yieldG: Float? = null,
        brewTempC: Float? = null,
        durationMs: Long? = null,
    ) = library.updateManualBrew(id, doseG, waterG, yieldG, brewTempC, durationMs)

    /** Set a shot's forward-looking "next time" plan (local-only). */
    fun setShotNextPlan(id: String, nextPlan: String) = library.setShotNextPlan(id, nextPlan)
    fun setShotPerson(id: String, field: String, name: String?) = library.setShotPerson(id, field, name)

    /**
     * Prefill Brew from a logged shot — "start from this shot". Re-activates
     * the shot's bag (while it still exists un-archived), loads its profile
     * by name, sets the QC grind to what the shot was actually pulled at,
     * and lays the shot's recorded dose / yield target over the loaded
     * recipe. Load-into-brew: no history record is created. Android
     * persists fewer targets than web (no brew-temp / pre-infuse on
     * [coffee.crema.history.StoredShot]), so the profile's own recipe
     * covers whatever the shot didn't record.
     */
    fun startFromShot(id: String) {
        // What's on screen: on a remote, the hub's bean and profile.
        val st = effectiveState()
        val shot = st.history.firstOrNull { it.id == id } ?: return
        // 1. The bag.
        shot.bean?.beanId?.let { bid ->
            val bean = st.beans.firstOrNull { it.id == bid }
            if (bean != null && bean.archivedAt == null && st.activeBeanId != bid) {
                setActiveBean(bid)
            }
        }
        // 2. The profile — by name; a renamed/deleted one degrades to "the rest".
        val match = shot.profileName?.let { n ->
            st.profiles.firstOrNull { it.name.equals(n, ignoreCase = true) }
        }
        if (match != null) setActiveProfile(match.id)
        // 3. The dial: the shot's own grind (falling back to its bag snapshot's
        //    reference setting), then dose/yield over the loaded recipe.
        val grind = shot.grindSetting
            ?: shot.bean?.grinderSetting?.trim()?.replace(',', '.')?.toFloatOrNull()
        grind?.let { setQcGrind(it) }
        if (shot.doseG != null || shot.yieldTargetG != null) {
            val active = match ?: effectiveState().activeProfile()
            val dose = shot.doseG?.toDouble() ?: active?.dose?.toDouble() ?: 18.0
            // The profile's own brew temperature as the core anchors it (its
            // first step), so the dial-in moves no temperature it didn't record.
            val anchor = brewDialOf(active?.id?.let { library.profileJson(it) }, json, ::brewTempRange, ::preinfusionDial)?.anchor
            quickAdjustBrew(
                dose = dose,
                yieldOut = shot.yieldTargetG?.toDouble() ?: active?.yieldOut?.toDouble() ?: (dose * 2),
                brewTemp = shot.brewTempC?.toDouble() ?: anchor ?: active?.brewTemp?.toDouble() ?: 93.0,
            )
        }
        val stamp = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            .format(java.util.Date(shot.completedAtMs))
        val missing = shot.profileName?.takeIf { match == null }
        notifyUser(
            if (missing == null) "Dialed in from the $stamp shot"
            else "Dialed in from the $stamp shot (profile “$missing” isn’t in your library)",
        )
    }

    /** Apply a Quick-Controls brew override (dose/yield/brew-temp). Transient —
     *  not saved to the profile; baked into the next shot's upload by [startShot]. */
    fun quickAdjustBrew(dose: Double, yieldOut: Double, brewTemp: Double, preinf: Double? = null) =
        machine.execute(MachineCommand.AdjustBrew(dose, yieldOut, brewTemp, preinf))

    /** Reset the Quick-Controls override back to the active profile's recipe. */
    fun resetBrewParams() = machine.execute(MachineCommand.ResetBrew)

    // ── Full-app backup & restore (crema-backup/v1) ──────────────────────────
    // One portable bundle: custom profiles + beans/roasters + shot history + the
    // portable settings subset, (de)serialised by the core (exportBackupJsonl /
    // the line-tagged JSONL). OAuth tokens + per-device BLE/proxy state are
    // stripped on export and preserved on import — a backup moves between devices
    // without dragging this device's bindings along.

    /** Restore strategy chosen in the UI. */
    enum class RestoreMode { MERGE, WIPE }

    /** Apply a restored backup's PORTABLE settings to the live UI + persist, while
     *  preserving this device's per-device bindings (BLE addresses, LAN proxy,
     *  paired devices). Mirrors loadPrefs' hydration for the portable fields;
     *  activeProfileId is deliberately NOT restored, so a merge never hijacks the
     *  current selection. */
    private fun applyRestoredSettings(p: AppPrefs) {
        settings.restoreValues(p)
        // On a remote the core keeps following the hub's values (§7.4).
        machineConfig.pushCoreInputs(effectiveState())
        // Re-send the connect sweep's settings with the restored prefs — machine
        // registers plus the Quick-Controls steam / hot-water / flush params —
        // so a restore takes effect without waiting for the next connect or QC
        // edit (its writes are a no-op when disconnected or off-primary). Not
        // the sweep itself: only the on-ready sweep settles the link for the
        // profile sync, and a restore while the DE1 is down must not.
        session.seedMachineSettings(onReady = false)
        persistPrefs() // pushes the hub's snapshot too
    }

    /** Request that History select [id] when it next opens (the Brew "Last shot"
     *  card tap-through). */
    fun openShotInHistory(id: String?) {
        if (id != null) _ui.update { it.copy(pendingHistoryShotId = id) }
    }

    /** Clear the pending History selection once HistoryScreen has applied it. */
    fun consumePendingHistoryShot() {
        if (_ui.value.pendingHistoryShotId != null) {
            _ui.update { it.copy(pendingHistoryShotId = null) }
        }
    }

    /** Request that History filter to [beanId]'s shots when it next opens (the
     *  bean detail's "See all N shots" tap-through). */
    fun openBeanShotsInHistory(beanId: String?) {
        if (beanId != null) _ui.update { it.copy(pendingHistoryBeanId = beanId) }
    }

    /** Clear the pending History bean filter once HistoryScreen has applied it. */
    fun consumePendingHistoryBean() {
        if (_ui.value.pendingHistoryBeanId != null) {
            _ui.update { it.copy(pendingHistoryBeanId = null) }
        }
    }

    /** Load a history shot's profile onto Brew (find by name → set active). */
    fun loadProfileOnBrew(profileName: String?) {
        val name = profileName ?: return
        val match = _ui.value.profiles.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: run { notifyUser("Profile \u201c$name\u201d isn\u2019t in your library"); return }
        setActiveProfile(match.id)
        notifyUser("Loaded \u201c${match.name}\u201d on Brew")
    }

    /** Start steaming. */
    fun steam() = machine.execute(MachineCommand.StartSteam)

    /** Start hot water. */
    fun hotWater() = machine.execute(MachineCommand.StartHotWater)

    /** Start a group-head flush / rinse. */
    fun flush() = machine.execute(MachineCommand.StartFlush)

    /**
     * Start an espresso shot the gated way (web parity): upload the active
     * profile to the DE1, wait for the upload to complete, observe the firmware's
     * profile-download guard window, then request the Espresso state. The Espresso
     * request itself fires from the core lane's handlers on `ProfileUploadCompleted`.
     *
     * The active profile is converted from the editable `CremaProfile` shape to
     * the wire `Profile` the DE1 expects by the sans-IO core
     * ([cremaProfileToWire]); the upload's frame-write commands + the
     * `ProfileUploadStarted` event come back as a `CoreOutput` and run through the
     * shared command path.
     *
     * M2 v1 always uploads (no fingerprint-skip optimisation yet) and has no
     * pre-shot flush (no settings store on Android yet) — both are later refinements.
     */
    fun startShot() = machine.execute(MachineCommand.StartShot)

    /** Scan for and connect to a DE1 — delegate of [ConnectionController.connect].
     *  Called from the UI once the BLE runtime permissions have been granted. */
    fun connect() = connection.connect()

    /** Connect from the firmware power-cycle step: the DE1 the flash is for
     *  when its address is known, else the usual scan. */
    fun connectFirmwareDe1() {
        val address = firmware.machineAddress
        if (address != null) connection.connectTo(address) else connection.connect()
    }

    /** Disconnect the DE1 — delegate of [ConnectionController.disconnect]. */
    fun disconnect() {
        // Leave the DE1's USB port charging (Decenza
        // `BatteryManager::ensureChargerOn`): smart charging may have switched
        // it off, and nothing would turn it back on once Crema is gone.
        if (_ui.value.bleState == De1BleManager.State.READY && !firmwareHolds()) {
            lane.route("USB charger on") { bridge.setUsbChargerOn(true) }
        }
        session.sweepRunner.onDropped()
        presence.linkIdleSleep(false)
        connection.disconnect()
    }

    // ---- Machine control (AND5) -------------------------------------------

    /**
     * Ask the DE1 to enter [state]. The core returns a `CoreOutput` whose
     * `Command.WriteCharacteristic(De1RequestedState)` is dispatched through the
     * shared command path — the same path the scale writes use. The Brew screen
     * (M1/M2) builds its Coffee / Stop / mode controls on this.
     */
    // ── Multi-device: where commands go (see [ProxyController]) ─────────────

    /** Where the verbs send their [MachineCommand]s: this device, or the
     *  primary while mirroring. */
    private val machine: MachineCommands get() = proxy.machine

    /** The brew-temperature dial for the profile on screen; `null` with no active profile. */
    fun brewDial(): BrewDial? = machineConfig.brewDial()

    /** Test seam: feed a hub's config frame to this device, whatever its role
     *  (AC-20: a normal device or a hub drops it). */
    @androidx.annotation.VisibleForTesting
    internal fun deliverConfigForTest(json: String) = remoteConfig.applyRemoteConfig(json)

    // ── Multi-device pairing (relay v4) ───────────────────────────────────────
    // The pairing ceremony, the pins and the keys live in [ProxyController]
    // (relay-v4.json, its own credential file); nothing here is persisted.

    /** The hub's code dialog: the hub user typed [code] and chose a scope. */
    fun submitPairingCode(code: String, canControl: Boolean) = proxy.submitPairingCode(code, canControl)

    /** The hub's code dialog was cancelled. */
    fun cancelPairing() = proxy.cancelPairing()

    /** Remote: pair with the hub at [host]:[port]. */
    fun pairWith(host: String, port: Int, label: String) = proxy.pairWith(host, port, label)

    /** Remote: Pair again with the saved target (after the upgrade, or once
     *  the hub couldn't be verified; spec §6.3, §6.4). */
    fun pairAgain() = proxy.pairAgain()

    /** Remote: cancel the pairing in progress (the code sheet's Cancel). */
    fun cancelRemotePairing() = proxy.cancelRemotePairing()

    /** Remote: close the code sheet after its outcome. */
    fun dismissPairing() = proxy.dismissPairing()

    /** Remote: the code sheet's Try again (three new attempts). */
    fun retryRemotePairing() = proxy.retryRemotePairing()

    /** Reset this device's pairing key (every pairing is cleared). */
    fun resetPairingKey() = proxy.resetPairingKey()

    /** The one-time key-reset notice was shown. */
    fun dismissKeyResetNotice() = proxy.dismissKeyResetNotice()

    /** The upgrade banner / "Pair again" status was dismissed. */
    fun dismissUpgradeNotice() = proxy.dismissUpgradeNotice()

    /** Settings ▸ Advanced ▸ Paired devices ▸ Pair a device: accept new devices for a while. */
    fun openPairingWindow() = proxy.openPairingWindow()

    /** Stop accepting new devices now. */
    fun closePairingWindow() = proxy.closePairingWindow()

    /** Remote: connect through the pinned hub [hubId] (a superseded pin's explicit pick). */
    fun connectToPinnedHub(hubId: String) = proxy.connectToPinnedHub(hubId)

    /** Remote: forget the hub [hubId] (it has to pair again). */
    fun forgetHub(hubId: String) = proxy.forgetHub(hubId)

    /** Revoke a paired device (Settings ▸ Advanced ▸ Paired devices): its pin is
     *  dropped and a live session ends now; it has to pair again to reconnect. */
    fun forgetPairedDevice(id: String) = proxy.forgetRemote(id)

    /** The Paired devices rows' actions, for both shells. */
    internal fun relayPairingActions() = RelayPairingActions(
        openWindow = ::openPairingWindow,
        closeWindow = ::closePairingWindow,
        forgetRemote = ::forgetPairedDevice,
        forgetHub = ::forgetHub,
        connectHub = ::connectToPinnedHub,
        resetKey = ::resetPairingKey,
        dismissUpgradeBanner = ::dismissUpgradeNotice,
    )

    /** Wake a sleeping DE1 (the request also stops anything running, so a
     *  mirror sends it only while it shows the machine asleep). */
    fun wake() = machine.execute(MachineCommand.Wake)

    /** Put the DE1 to sleep. Marked here too, for a mirror's own screensaver. */
    fun sleep() {
        presence.markAppSleepRequest()
        machine.execute(MachineCommand.Sleep)
    }

    /** The debug panel's raw Espresso request, skipping the start sequence.
     *  Not available while mirroring. */
    fun startEspresso() = machine.execute(MachineCommand.RawEspresso)

    /** Stop a running shot (return to idle). */
    fun stopShot() = machine.execute(MachineCommand.Stop)

    /** Smart-charging mode for the DE1's USB port. Persisted; the next minute's check applies it. */
    fun setUsbChargingMode(mode: String) {
        _ui.update { it.copy(usbChargingMode = mode) }
        persistPrefs()
        session.usbChargerTick()
    }

    // ── Settings: machine setters (Settings → Machine / Advanced / Service) ────
    // Thin VM wrappers so the Settings screen calls the VM (matching the
    // theme / scale-config pattern) rather than the bridge directly. Reads /
    // setters are safe while disconnected — the bridge returns a command JSON
    // and with no BLE link nothing is written.

    /** Set the mains heater voltage (120 or 230; anything else is a no-op). */
    fun setHeaterVoltage(volts: Int) = machineConfig.setHeaterVoltage(volts)

    /** Override the AC mains frequency (0 = auto-detect, 50 or 60; anything else is a no-op). */
    fun setLineFrequency(hz: Float) = machineConfig.setLineFrequency(hz)

    /** On-demand GitHub release check (Settings → About, issue #36). */
    fun checkForUpdates() {
        if (_ui.value.updateCheckBusy) return
        _ui.update { it.copy(updateCheckBusy = true) }
        viewModelScope.launch {
            val info = coffee.crema.update.checkForUpdates(json)
            _ui.update { it.copy(updateInfo = info, updateCheckBusy = false) }
        }
    }

    /** Daily automatic release check toggle. Turning it on runs a check right
     *  away if one is due. */
    fun setAutoUpdateCheck(on: Boolean) {
        _ui.update { it.copy(autoUpdateCheck = on) }
        persistPrefs()
        if (on) maybeAutoUpdateCheck()
    }

    /** One opt-in daily release check (issue #36 follow-up): at most one
     *  attempt per 24 h, stamped up front so a failed check retries tomorrow
     *  rather than on every launch; notifies (snackbar) exactly once per new
     *  version. Never runs unless [MainUiState.autoUpdateCheck] is on. */
    private fun maybeAutoUpdateCheck() {
        val s = _ui.value
        if (!s.autoUpdateCheck) return
        val now = System.currentTimeMillis()
        val last = s.lastUpdateCheckAtMs
        if (last != null && now - last < AUTO_UPDATE_CHECK_INTERVAL_MS) return
        _ui.update { it.copy(lastUpdateCheckAtMs = now) }
        persistPrefs()
        viewModelScope.launch {
            val info = coffee.crema.update.checkForUpdates(json)
            if (info.error != null) return@launch // quiet — retries tomorrow
            _ui.update { it.copy(updateInfo = info) }
            // Notify about the channel this build rides: nightlies track the
            // rolling nightly tag, everything else tracks stable.
            val current = coffee.crema.BuildConfig.VERSION_NAME
            val candidate = if (current.contains("nightly")) info.latestNightly else info.latestStable
            // Strictly newer, never merely different: being ON the newest build
            // (or ahead of it with a local build) must stay quiet. `!=` here
            // notified users on the latest nightly about the very build they
            // were running, on every check.
            if (coffee.crema.update.isNewerBuild(current, candidate) &&
                candidate != _ui.value.lastSeenLatestVersion
            ) {
                notifyUser("Update available — Crema $candidate (you’re on $current)")
                _ui.update { it.copy(lastSeenLatestVersion = candidate) }
                persistPrefs()
            }
        }
    }

    /** Re-read the auto-detector's resolved line frequency. */
    fun refreshLineFrequency() = machineConfig.refreshLineFrequency()

    /** Set the maximum shot duration, seconds (null = clear the cap). */
    fun setMaxShotDuration(seconds: Float?) = machineConfig.setMaxShotDuration(seconds)

    // ── Live mode switches (M2 — no restart) — see [ProxyController] ────────

    /** Become a standalone NORMAL device. On a remote this is Stop, which
     *  leaves the remote role like Connect directly. */
    fun switchToNormal() = remoteConfig.switchToNormal()

    /** Connect through the hub at [host]:[port]: the picker's "Connect through". */
    fun switchToSecondary(host: String, port: Int) = remoteConfig.switchToSecondary(host, port)

    /** A remote whose hub is unreachable, or no longer holds the DE1, connects
     *  the machine itself. */
    fun connectDirectly() = remoteConfig.connectDirectly()

    /** The Hub mode switch (Settings ▸ Multi-device); [anyway] is the user's
     *  "Turn on anyway" past a discovered hub's warning. */
    fun setHubMode(on: Boolean, anyway: Boolean = false) = remoteConfig.setHubMode(on, anyway)

    /** The T6 confirmation's answer: "Use <hub>'s" ([useHub]) or "Keep mine". */
    fun resolveAdoption(useHub: Boolean) = remoteConfig.resolveAdoption(useHub)

    /** A full AppPrefs from the current UI state ([SettingsController.currentPrefs]). */
    private fun currentPrefs() = settings.currentPrefs()

    /** [SettingsController.persistPrefsNow]: the T6 commit's awaited save. */
    private suspend fun persistPrefsNow() = settings.persistPrefsNow()

    /** Persist the current prefs snapshot ([SettingsController.persistPrefs]);
     *  a no-op until the initial load. */
    private fun persistPrefs() = settings.persistPrefs()

    /** Queue a user-facing message (snackbar) + keep it in the log. Public so the
     *  Activity can surface e.g. a denied BLE permission — a connect tap must never
     *  fail silently. */
    fun notifyUser(message: String) {
        appendLog(message)
        _ui.update { it.copy(userMessages = it.userMessages + message, status = message) }
    }

    /** MainActivity consumed the head snackbar message. */
    fun consumeUserMessage() {
        _ui.update { it.copy(userMessages = it.userMessages.drop(1)) }
    }

    /**
     * Set the flow-calibration multiplier. Routes the resulting MMR write
     * through the shared command path and re-reads the register so the row
     * reflects the machine's confirmed value.
     */
    fun setFlowMultiplier(multiplier: Float) {
        editOwnSetting(Setting.FLOW_MULTIPLIER) {
            val raw = runCatching { bridge.setCalibrationFlowMultiplier(multiplier) }.getOrElse {
                appendLog("Set flow multiplier failed: ${it.message}")
                return
            }
            lane.deliver(raw)
            runCatching { bridge.readMmr(MmrReg.CALIBRATION_FLOW_MULTIPLIER) }.getOrNull()?.let(lane::deliver)
        }
    }

    /** Start a descale cycle (Settings → Water → "Run now"). */
    fun startDescale() = machine.execute(MachineCommand.StartDescale)

    /** Start a cleaning cycle (Settings → Water → "Run now"). */
    fun startClean() = machine.execute(MachineCommand.StartClean)

    /**
     * Cancel the maintenance cycle core is following (Settings → Water's
     * Cancel): drops a Descale / Clean held for preheat on old firmware, or
     * asks a running one to stop. Progress arrives as `MaintenanceProgress`.
     */
    fun cancelMaintenance() = machine.execute(MachineCommand.CancelMaintenance)

    /** Enable/disable auto-tare at shot start (Quick Controls). Optimistic. Persisted. */
    fun setAutoTare(enabled: Boolean) = machine.execute(MachineCommand.SetAutoTare(enabled))

    /**
     * Enable/disable stop-on-weight (Quick Controls). The yield target comes from
     * the active profile; this just arms/disarms the behaviour. Optimistic.
     */
    fun setStopOnWeight(enabled: Boolean) = machine.execute(MachineCommand.SetStopOnWeight(enabled))

    /**
     * Opt into the profile's max-volume stop racing stop-at-weight while a
     * scale is connected (Settings → Shot behaviour). Off (default) = volume
     * only applies as the no-scale fallback. Purely core-side (SAV) — the
     * firmware tail volume stop is never uploaded, so no re-upload needed;
     * a mid-shot flip re-targets the armed AutoStop via the core's refresh.
     */
    fun setVolumeStopWithScale(on: Boolean) {
        editOwnSetting(Setting.VOLUME_STOP_WITH_SCALE) {
            _ui.update { it.copy(volumeStopWithScale = on) }
            runCatching { bridge.setVolumeStopWithScale(on) }.onFailure {
                appendLog("Set volume-stop-with-scale failed: ${it.message}")
            }
            persistPrefs()
        }
    }

    // ── Auto-connect (per device) ─────────────────────────────────────────────

    /** "Forget" the remembered DE1: no more reconnects (after a dropout or on
     *  launch) and its "nearby" association goes; a live session stays. */
    fun forgetDe1() = setDe1AutoConnect(false)

    /** "Forget" the remembered scale (the scale-side twin of [forgetDe1]). */
    fun forgetScale() = setScaleAutoConnect(false)

    /** Per-device "Auto-connect" toggle for the DE1 — delegate of
     *  [ConnectionController.setDe1AutoConnect]. */
    private fun setDe1AutoConnect(on: Boolean) {
        // Forgetting the DE1 also drops its companion association.
        if (!on) connection.state.value.rememberedDe1Address?.let { if (companion.isAssociated(it)) companion.remove(it) }
        connection.setDe1AutoConnect(on)
        if (!on) refreshCompanion()
    }

    /** Per-device "Auto-connect" toggle for the scale (the scale-side twin). */
    private fun setScaleAutoConnect(on: Boolean) {
        if (!on) connection.state.value.rememberedScaleAddress?.let { if (companion.isAssociated(it)) companion.remove(it) }
        connection.setScaleAutoConnect(on)
        if (!on) refreshCompanion()
    }

    /**
     * Enable/disable steam eco mode (Quick Controls). Returns a `CoreOutput` (an
     * MMR write) routed through the shared command path. Optimistic.
     */
    fun setSteamEco(enabled: Boolean) {
        editOwnSetting(Setting.STEAM_ECO) {
            _ui.update { it.copy(steamEco = enabled) }
            persistPrefs()
            val raw = runCatching {
                bridge.enableSteamEcoMode(enabled, SystemClock.elapsedRealtime().toULong())
            }.getOrElse {
                appendLog("Set steam eco failed: ${it.message}")
                return
            }
            lane.deliver(raw)
        }
    }

    // ── Quick-Controls steam / hot-water / flush (issue 14) ──────────────────
    // Persisted QC overrides that stick + take priority. On change we update
    // UiState, persist, and write to the machine. Steam temp/time + hot-water
    // temp/vol share one packet (cuuid_0B), which the core writes
    // read-modify-write: the group target is the effective profile's, and the
    // fields QC doesn't model (flags, timeouts, espresso volume) survive;
    // steam flow + flush temp/time are separate writes.

    /** The hot-water dose — the Quick Controls' Time · ml · Ratio card sets the
     *  mode and the mode's value in one edit. A machine setting: a remote
     *  refuses it ("Change on <hub>"). */
    fun setHotWaterDose(dose: coffee.crema.core.HotWaterDose) = machine.execute(MachineCommand.SetHotWaterDose(dose))

    /** Steam timeout, seconds (Quick Controls). Persisted; RMW write. */
    fun setQcSteamTime(seconds: Float) = machine.execute(MachineCommand.SetQc(QcField.STEAM_TIME, seconds))

    /** Steam flow rate, ml/s (Quick Controls). Persisted; standalone write. */
    fun setQcSteamFlow(mlPerS: Float) = machine.execute(MachineCommand.SetQc(QcField.STEAM_FLOW, mlPerS))

    /** Steam temperature, °C (Quick Controls). Persisted; RMW write. */
    fun setQcSteamTemp(tempC: Float) = machine.execute(MachineCommand.SetQc(QcField.STEAM_TEMP, tempC))

    /** Hot-water temperature, °C (Quick Controls). Persisted; RMW write. */
    fun setQcHotWaterTemp(tempC: Float) = machine.execute(MachineCommand.SetQc(QcField.HOT_WATER_TEMP, tempC))

    /** Hot-water volume, ml: also switches the dose to volume mode (the card
     *  edits the whole dose with [setHotWaterDose]). Persisted; RMW write. */
    fun setQcHotWaterVolume(ml: Float) = machine.execute(MachineCommand.SetQc(QcField.HOT_WATER_VOLUME, ml))

    /** Flush timeout, seconds (Quick Controls). Persisted; standalone write. */
    fun setQcFlushTime(seconds: Float) = machine.execute(MachineCommand.SetQc(QcField.FLUSH_TIME, seconds))

    /** Flush temperature, °C (Quick Controls). Persisted; standalone write. */
    fun setQcFlushTemp(tempC: Float) = machine.execute(MachineCommand.SetQc(QcField.FLUSH_TEMP, tempC))

    /**
     * Grinder click (Quick Controls). Persisted so it sticks + recorded onto the
     * next shot (issue 15). Grind never reaches the machine — it's a log/record
     * value — so there's no machine write, just persistence.
     */
    fun setQcGrind(clicks: Float) = machine.execute(MachineCommand.SetQc(QcField.GRIND, clicks))

    /** Show/hide a live-chart channel (Quick Controls). Persisted display pref. */
    fun toggleChartChannel(key: String, enabled: Boolean) = settings.toggleChartChannel(key, enabled)

    // ── Display units (issue 44) ──────────────────────────────────────────────
    // Display prefs: nothing is sent to the machine and no canonical value
    // changes — flipping a unit only re-renders existing readouts through
    // `Format.kt`. Persisted so the choice survives a restart. The weight unit
    // also goes to the core, which picks the scale's LCD unit from it.

    /** Weight readout unit (`"g" | "oz"`). Persisted display pref; the core and a connected scale follow it. */
    fun setWeightUnit(unit: String) = machineConfig.setWeightUnit(unit)

    /** Temperature readout unit (`"C" | "F"`). Persisted display pref. */
    fun setTempUnit(unit: String) = settings.setTempUnit(unit)

    /** Pressure readout unit (`"bar" | "psi"`). Persisted display pref. */
    fun setPressureUnit(unit: String) = settings.setPressureUnit(unit)

    /** Volume readout unit (`"ml" | "floz"`). Persisted display pref. */
    fun setVolumeUnit(unit: String) = settings.setVolumeUnit(unit)

    /** Water-tank readout style (`"ml" | "percent"`). Persisted display pref. */
    fun setWaterLevelUnit(unit: String) = settings.setWaterLevelUnit(unit)

    /** Low-water warning threshold, canonical ml (0 = off). Persisted. */
    fun setWaterWarnMl(ml: Float) = machineConfig.setWaterWarnMl(ml)

    /** The machine's own refill threshold, raw sensor mm. Persisted and written through. */
    fun setWaterRefillPointMm(mm: Float) = machineConfig.setWaterRefillPointMm(mm)

    /** Daily automatic Google Drive backup toggle (issue #36). Turning it on
     *  runs a backup right away if one is due. */
    fun setAutoDriveBackup(on: Boolean) {
        _ui.update { it.copy(autoDriveBackup = on) }
        persistPrefs()
        if (on) drive.autoBackupIfDue(true)
    }

    /** Fan-on temperature threshold, °C (0..=60). Persisted and written through. */
    fun setFanThreshold(tempC: Float) = machineConfig.setFanThreshold(tempC)

    /** Opt-in "require scale to start" block (geota/crema#29). Persisted. */
    fun setRequireScale(on: Boolean) = machineConfig.setRequireScale(on)

    /** DE1 firmware two-tap steam stop. Persisted and written through. */
    fun setSteamTwoTap(enabled: Boolean) = machineConfig.setSteamTwoTap(enabled)

    /** The pre-shot flush preference: persisted, and latched in the core. */
    fun setPreFlush(enabled: Boolean) = machineConfig.setPreFlush(enabled)

    /** The post-steam purge preference: persisted, and latched in the core. */
    fun setSteamPurge(enabled: Boolean) = machineConfig.setSteamPurge(enabled)

    /** Keep the screen awake while a shot pulls (Settings → Display). Persisted. */
    fun setKeepScreenOnBrew(enabled: Boolean) = settings.setKeepScreenOnBrew(enabled)

    /** Guided-brew cue sound (Settings → Display, the Brew setup bell). Persisted as an explicit choice. */
    fun setBrewCueSound(enabled: Boolean) = settings.setBrewCueSound(enabled)

    /** Guided-brew cue vibration (Settings → Display). Persisted as an explicit choice. */
    fun setBrewCueHaptics(enabled: Boolean) = settings.setBrewCueHaptics(enabled)

    /** Settings → Brew defaults: the dose / ratio / temp / pre-infusion a NEW
     *  profile seeds from ([startNewProfile] → brewDefaultsJson). Persisted. */
    fun setBrewDefaults(doseG: Float, ratio: Float, brewTempC: Float, preinfuseS: Float) =
        settings.setBrewDefaults(doseG, ratio, brewTempC, preinfuseS)

    // ── Bean library — see [LibraryController] ──────────────────────────────

    /** Set (or with `null`, clear) the active bean (the Brew bean block). Persisted. */
    fun setActiveBean(id: String?) = machine.execute(MachineCommand.SetActiveBean(id))

    // ── Maintenance — see [MaintenanceController] ───────────────────────────

    /** Mark the water filter cleaned (rebaseline to the current total). */
    fun markFilterCleaned() = maintenance.markFilterCleaned()

    /** Mark a descale done (rebaseline to the current total). */
    fun markDescaled() = maintenance.markDescaled()

    /** Mark a clean cycle done (reset its hour counter). */
    fun markCleaned() = maintenance.markCleaned()

    // ── App settings ──────────────────────────────────────────────────────────

    /** Load persisted app preferences at startup. */
    private suspend fun loadPrefs() {
        val p = settings.read()
        settings.hydratePrefs(p)
        // Prime the background keep-alive with the resolved setting (#65).
        bgConnection.setEnabled(p.keepConnectedScreenOff ?: settings.isTablet)
        settings.markLoaded()
        // The cap, behaviour toggles, stop targets and idle-sleep timer go
        // to the core now (it doesn't echo them back). On a remote that is
        // the hub's values: a snapshot that landed before the load finished
        // isn't clobbered by this device's own (§7.4).
        machineConfig.pushCoreInputs(effectiveState())
        // The Brew setup opens on the most recent method, now it's known.
        guided.resolveGuidedSetup()
        // Hydrate the remembered addresses, arm each manager's reconnect loop,
        // and cold-start auto-connect to each remembered device.
        connection.hydrateRemembered(p.de1Address, p.scaleAddress, p.scaleName)
        // Companion associations: the system's list is the truth (a removal in
        // system settings wins over the stored id); restart presence observation.
        refreshCompanion()
    }

    // ── Companion-device presence — see [PresenceController] ─────────────────

    /** Re-read the associations, restart presence observation, mirror into the UI. */
    fun refreshCompanion() = presence.refreshCompanion()

    /** The remembered address to associate for [device], or null. */
    fun companionTargetAddress(device: coffee.crema.ble.companion.CompanionDevice): String? =
        presence.companionTargetAddress(device)

    /** The system dialog created the association. */
    fun onCompanionAssociated(device: coffee.crema.ble.companion.CompanionDevice, address: String, id: Int?) =
        presence.onCompanionAssociated(device, address, id)

    /** The association didn't happen (cancelled, not found, unsupported). */
    fun onCompanionSetupEnded(reason: String?) = presence.onCompanionSetupEnded(reason)

    /** Settings → "Remove": stop watching for the device and drop the association. */
    fun removeCompanion(device: coffee.crema.ble.companion.CompanionDevice) = presence.removeCompanion(device)

    /** Equipment-level grinder model (free text). Persisted; rides Visualizer
     *  uploads/patches as `grinder_model`. */
    fun setGrinderModel(model: String) {
        editOwnSetting(Setting.GRINDER_MODEL) {
            _ui.update { it.copy(grinderModel = model) }
            persistPrefs()
        }
    }

    /** Default barista name (free text), stamped on each new shot and brew log. Persisted. */
    fun setBaristaName(name: String) = settings.setBaristaName(name)

    /** "Sleep after" — idle minutes before Crema puts the DE1 to sleep (0 =
     *  Never), snapped to one of the core's choices. Persisted; the timer
     *  restarts from now. */
    fun setIdleSleepMin(minutes: Int) {
        editOwnSetting(Setting.IDLE_SLEEP) {
            val choices = idleSleepChoices()
            val snapped = choices[idleSleepIndex(minutes, choices)]
            _ui.update { it.copy(idleSleepMin = snapped) }
            persistPrefs()
            presence.configureIdleSleep(snapped)
        }
    }

    /** Sleep the machine when Crema is quit (swiped from recents) — the
     *  [QuitSleep] handler consumes this at fire time. */
    fun setSleepOnQuit(on: Boolean) = settings.setSleepOnQuit(on)

    /** Keep the DE1 connected + auto-reconnecting while the screen is off (#65).
     *  Persisted as an explicit value (overriding the form-factor default); the
     *  keep-alive service still only engages while the device is charging. */
    fun setKeepConnectedScreenOff(on: Boolean) {
        _ui.update { it.copy(keepConnectedScreenOff = on) }
        persistPrefs()
        bgConnection.setEnabled(on)
        if (on) presence.setUpNearby(explicit = true)
    }

    /** A screen / route change — activity for the idle-sleep timer. */
    fun noteNavigation() = presence.noteNavigation()

    // ── Sleep & screensaver ───────────────────────────────────────────────────

    /** A touch: activity for the saver's idle clock and the idle-sleep timer
     *  (MainActivity's root interceptor, Initial pass, never consumes). */
    fun noteUserInteraction() = presence.noteUserInteraction()

    /** Foreground/background transitions from [MainActivity]'s
     *  onStart/onStop. Returning counts as user presence: the saver's idle
     *  clock is bumped (so it doesn't fire seconds after a long absence) and
     *  the idle-sleep timer restarts (its throttled presence poke goes with
     *  it). Backgrounding by itself is not activity — the timer keeps
     *  counting in the background for as long as the process lives. */
    fun setAppForeground(foreground: Boolean) {
        presence.appInForeground = foreground
        // The Visualizer retry queue's tick and network trigger run only in
        // the foreground (F17).
        visualizer.onForegroundChanged(foreground)
        // No wake-up tick in the background (the 1 s tick covers the core's
        // deadline while the link is kept up).
        if (!foreground) wake.cancel()
        // A foreground service may only be STARTED from the foreground (Android
        // 12+); the keep-alive is therefore armed while visible and survives the
        // screen turning off after (#65).
        bgConnection.setForeground(foreground)
        if (foreground) {
            presence.noteForegroundReturn()
            // Coming back is when a slow reconnect hurts: interrupt any
            // remembered device's backoff / 60 s lurk wait and try NOW (the
            // foreground scan isn't throttled). Debounced per device, a no-op
            // for READY / mid-handshake / user-disconnected devices.
            connection.kickReconnect(coffee.crema.ble.ReconnectTrigger.FOREGROUND)
            // An association removed in system settings shows up here.
            if (settings.loaded) refreshCompanion()
        }
    }

    /** "Retry now" on a reconnecting device row ([de1] = the DE1, else the scale). */
    fun retryReconnectNow(de1: Boolean) = connection.retryNow(de1)

    /** Tap-to-wake: dismiss the saver (and, if set, wake a sleeping DE1). */
    fun dismissSaver() = presence.dismissSaver()

    /** Set the screensaver idle timeout, minutes (0 = never). Persisted. */
    fun setScreensaverAfterMin(minutes: Int) = settings.setScreensaverAfterMin(minutes)

    /** Toggle the coupled machine wake on saver dismiss. Persisted. */
    fun setWakeMachineWithSaver(on: Boolean) = settings.setWakeMachineWithSaver(on)

    /**
     * The bean half of the quick sheets' scope-aware **Save** (issue #16):
     * write the QC grind dial back to the active bean's reference setting
     * when they differ. Returns true when a bean write happened — the sheet
     * runs this alongside the existing profile-preset half, so one button
     * saves each dirty part to its owner. Overwriting free-text like
     * "6 + a tooth" with the dial number is deliberate: Save is an explicit
     * action and the field keeps its reference-dial meaning.
     */
    /** Undo an aborted-shot discard: persist the held shot after all. */
    fun undoDiscardShot() = library.undoDiscardShot()

    /** The discard snackbar timed out / was dismissed — drop the held shot. */
    fun consumeDiscardToast() = library.consumeDiscardToast()

    fun saveQuickGrindToBean(): Boolean {
        // The dial and bean on screen: on a remote, the hub's.
        val st = effectiveState()
        val dial = st.qcGrind ?: return false
        val bean = st.beans.firstOrNull { it.id == st.activeBeanId } ?: return false
        val fmt = if (dial % 1f == 0f) fmt("%.0f", dial) else fmt("%.1f", dial)
        if (bean.grinderSetting == fmt) return false
        library.mutateBean(bean.id) { it.copy(grinderSetting = fmt) }
        notifyUser("Grind $fmt saved to ${bean.name}")
        return true
    }

    // ── Maintenance intervals (web WaterSection "Maintenance intervals") ─────

    /** Set the water-filter capacity (litres). Persisted + readout recomputed. */
    fun setFilterCapacity(litres: Double) = maintenance.setFilterCapacity(litres)

    /** Set the descale interval (litres). Persisted + readout recomputed. */
    fun setDescaleInterval(litres: Double) = maintenance.setDescaleInterval(litres)

    /** Set the clean-cycle interval (hours). Persisted + readout recomputed. */
    fun setCleanInterval(hours: Double) = maintenance.setCleanInterval(hours)

    fun setFilterReminderEnabled(on: Boolean) = maintenance.setFilterReminderEnabled(on)

    fun setDescaleReminderEnabled(on: Boolean) = maintenance.setDescaleReminderEnabled(on)

    fun setCleanReminderEnabled(on: Boolean) = maintenance.setCleanReminderEnabled(on)

    /** Start a steam-rinse cycle (Settings → Water → "Run now"). */
    fun startSteamRinse() = machine.execute(MachineCommand.StartSteamRinse)

    /** Set the Bengle cup-warmer plate temperature (0–80 °C) and re-read the
     *  register so the UI reflects what the machine accepted. */
    fun setCupWarmerTemp(tempC: Int) {
        editOwnSetting(Setting.CUP_WARMER_TEMP) {
            val raw = runCatching { bridge.setCupWarmerTemperature(tempC.coerceIn(0, 80).toUByte()) }.getOrElse {
                appendLog("Set cup warmer temp failed: ${it.message}")
                return
            }
            lane.deliver(raw)
            runCatching { bridge.readMmr(MmrReg.CUP_WARMER_TEMP) }.getOrNull()?.let(lane::deliver)
        }
    }

    /** Show/hide the inline debug panel (Settings → Advanced). Persisted. */
    fun setShowDebugPanel(on: Boolean) = settings.setShowDebugPanel(on)

    // ── Multi-device proxy: the manual hub address (emulators, no NSD) ──────
    fun setProxyPrimaryHost(host: String) = settings.setProxyPrimaryHost(host)

    fun setProxyPrimaryPort(port: Int) = settings.setProxyPrimaryPort(port)

    /** Debug: replay a captured shot as a fake DE1 when primary (emulator demos).
     *  Restart-to-apply (read at primary-start). Off = a real primary uses live BT. */
    fun setReplayPrimary(enabled: Boolean) = settings.setReplayPrimary(enabled)

    /** Set the theme mode (`"system"` / `"light"` / `"dark"`) and persist. */
    fun setThemeMode(mode: String) = settings.setThemeMode(mode)

    /**
     * Restore defaults — the core's rule (`reset_settings`, the one web uses):
     * every shared setting back to its default except the weight unit and the
     * mains line frequency, which are facts about the user's setup. The role,
     * the hub target, paired devices, the DE1 / scale bindings and the other
     * Android-only extras stay. Applied like a restore ([applyRestoredSettings]:
     * pushed to the core, the machine settings re-sent, persisted).
     */
    fun resetPreferences() {
        val (reset, lineHz) = currentPrefs().resetCommon(_ui.value.lineFreqOverride)
        applyRestoredSettings(reset)
        // The core keeps the line frequency; nothing to send unless it differs.
        if (lineHz != _ui.value.lineFreqOverride) setLineFrequency(lineHz)
    }

    /**
     * Erase ALL user data — bean library, roasters and bag photos, shot
     * history, custom profiles, recipes, preferences, maintenance counters,
     * the learned stop-at-weight model, BLE session captures, and the
     * Visualizer / Decent / Google Drive stores (sign-ins, sharing settings,
     * sync cursors and activity logs). Built-in profiles remain (they aren't
     * user data); the active profile reseeds to the first built-in via
     * [refreshProfiles]. Irreversible.
     *
     * Memory first, so nothing can save the old values again; then, in one
     * coroutine, every store file is DELETED with all its copies (`.bak`,
     * `.tmp`, `.corrupt-*`) — saving an empty value would keep the old file
     * as `.bak` (final review 05 N-1) — and a last sweep removes any copy an
     * in-flight save left behind. The Visualizer and Drive grants are revoked
     * after their tokens are gone from disk.
     */
    fun eraseAll() {
        // Reset the maintenance counters too (a fresh-install state).
        val freshMaintenance = maintenance.erase()
        // A fresh stop-at-weight model (null = start learning again).
        lane.post { runCatching { bridge.loadSawModelJson(null) } }
        // Every preference back to its default in memory too (barista name,
        // grinder, units, …): the next persistPrefs would otherwise write the
        // old values straight back.
        applyRestoredSettings(AppPrefs(idleSleepMin = defaultIdleSleepMin().toInt()))
        // The library/history/custom-profile share (UI rows + the
        // active-profile reseed via refreshProfiles).
        library.eraseAll()
        viewModelScope.launch {
            library.eraseStoredData()
            guided.eraseStoredData()
            settings.erase()
            maintenance.eraseStored(freshMaintenance)
            sawModelStore.erase()
            // Sign-ins are personal data too (review 05 P2-1), and so are the
            // sync logs, settings and cursors (final review 04 P3-3).
            visualizer.eraseAll()
            decent.eraseAll()
            drive.eraseAll()
            // The relay key store, relay-lost/ and the v3 pairing file (SR-3a).
            proxy.eraseRelayData()
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                bleRecorder.eraseCaptures()
                val left = coffee.crema.persist.SafeFile.eraseCopiesIn(getApplication<Application>().filesDir)
                if (left.isNotEmpty()) appendLog("Erase: couldn't delete ${left.joinToString()}")
            }
            notifyUser("Erased all Crema data on this device")
        }
    }

    /** Scan for and connect to a scale — delegate of
     *  [ConnectionController.connectScale]. Independent of the DE1. */
    fun connectScale() = connection.connectScale()

    /** Disconnect the scale — delegate of [ConnectionController.disconnectScale]. */
    fun disconnectScale() = connection.disconnectScale()

    // ── Scale — see [ScaleController] ───────────────────────────────────────

    /** Set the connected scale's beeper volume (clamped to its range). */
    fun setScaleVolume(level: Int) = scaleController.setScaleVolume(level)

    /** Set the connected scale's auto-standby timeout, minutes. */
    fun setScaleStandbyMinutes(minutes: Int) = scaleController.setScaleStandbyMinutes(minutes)

    /** Enable or disable the connected scale's flow smoothing. */
    fun setScaleFlowSmoothing(enabled: Boolean) = scaleController.setScaleFlowSmoothing(enabled)

    /** Enable or disable the connected scale's anti-mistouch. */
    fun setScaleAntiMistouch(enabled: Boolean) = scaleController.setScaleAntiMistouch(enabled)

    /** Switch the connected scale to display mode [modeId]. */
    fun setScaleMode(modeId: Int) = scaleController.setScaleMode(modeId)

    /** Select the connected scale's auto-stop mode (0 = flow-stop, 1 = cup-removal). */
    fun setScaleAutoStop(modeId: Int) = scaleController.setScaleAutoStop(modeId)

    /**
     * Tare the connected scale. Asks the core for the tare bytes, then routes
     * the resulting `Command.WriteScale` through the shared command path so the
     * exact same code handles a manual tare and the core's auto-tare.
     */
    fun tareScale() = machine.execute(MachineCommand.TareScale)

    /** Beep the connected scale (locate / test tone). */
    fun beepScale() = scaleController.beepScale()

    /** Clear the scale's tracked peak + final weight (Scale page "Reset peak"). */
    fun resetScalePeaks() = scaleController.resetScalePeaks()

    /** Start the connected scale's built-in timer. */
    fun startScaleTimer() = scaleController.startScaleTimer()

    private fun appendLog(line: String) {
        val stamped = fmt("%s  %s", 
            android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()),
            line,
        )
        // Mirror into the crash-report ring buffer — it outlives the UI's
        // eventLog (which dies with the process). See coffee.crema.diag.DiagLog.
        coffee.crema.diag.DiagLog.add(stamped)
        // Built INSIDE the atomic update: appendLog runs from Main, the core
        // lane and the firmware scope at once, and a read outside the update
        // dropped concurrent lines (or resurrected a just-cleared log).
        _live.update { it.copy(eventLog = prependLogLine(it.eventLog, stamped, MAX_LOG_LINES)) }
    }

    override fun onCleared() {
        // Quit path (sleep-on-quit): a swipe-away destroys the activity —
        // and clears this VM — ~400 ms before the OS kills the process, so
        // this is the reliable place to fire. viewModelScope is ALREADY
        // cancelled here (lifecycle 2.x closes it before calling onCleared),
        // so the handler's write runs on ProcessScope, and the BLE teardown
        // below waits (bounded) for it: closing the link first would drop it.
        // If the service's onTaskRemoved fired first, fire() hands back the
        // write it started, so the wait covers that one too.
        val quitWrite = QuitSleep.fire()
        // Unregister the power receiver + stop the keep-alive service (#65).
        bgConnection.close()
        teardownAfterQuitWrite(ProcessScope.main, quitWrite, QUIT_WRITE_BUDGET_MS + QUIT_TEARDOWN_SLACK_MS) {
            presence.clearQuitSleep()
            // Adapter watcher off, scans cancelled, both device links down…
            connection.close()
            // The recorder is process-scoped: don't let it pin this VM.
            coffee.crema.diag.ReconnectTimelines.recorder.releaseOnFinished(timelineLogHook)
            // Close the transport last — it unregisters the Nordic environment's
            // Bluetooth receiver and tears down the central manager. The managers'
            // disconnect() calls above are fire-and-forget coroutines; closing the
            // transport also cancels its scope, so any in-flight disconnect ends.
            proxy.close()
        }
        // The Visualizer retry queue's network callback and tick (F17): the
        // app normally leaves the foreground first, but don't rely on it.
        visualizer.onForegroundChanged(false)
        // The HTTP clients (viewModelScope is already cancelled, so no
        // Visualizer / Decent call is still in flight). They sit on the
        // shared, process-lifetime OkHttp engine, which stays up.
        visualizerClient.close()
        decentClient.close()
    }

    internal companion object {
        /**
         * Tests set this before building a VM to hold its startup load until
         * they complete it, so they can act while the prefs are still unloaded
         * (the load may otherwise finish inside the constructor). Null in the
         * app: nothing waits.
         */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var holdStartupLoad: kotlinx.coroutines.Deferred<Unit>? = null

        private const val MAX_LOG_LINES = 200

        /** Grace past [QUIT_WRITE_BUDGET_MS] before onCleared's deferred BLE
         *  teardown runs anyway (the write's own timeout normally fires first). */
        private const val QUIT_TEARDOWN_SLACK_MS = 250L
    }
}

/** These UI settings as the shared [CommonSettings] (via [AppPrefs], whose
 *  mapping is the canonical one) — what [MachineConfigController.pushCoreInputs] hands
 *  the core. */
internal fun MainUiState.commonSettings(): CommonSettings = AppPrefs(
    themeMode = themeMode,
    maxShotDurationS = maxShotDurationS,
    autoTare = autoTare,
    stopOnWeight = stopOnWeight,
    volumeStopWithScale = volumeStopWithScale,
    requireScale = requireScale,
    steamEco = steamEco,
    steamTwoTap = steamTwoTap,
    fanThresholdC = fanThresholdC,
    usbChargingMode = usbChargingMode,
    preFlush = preFlush,
    steamPurge = steamPurge,
    weightUnit = weightUnit,
    tempUnit = tempUnit,
    pressureUnit = pressureUnit,
    volumeUnit = volumeUnit,
    waterLevelUnit = waterLevelUnit,
    waterWarnMl = waterWarnMl,
    waterRefillPointMm = waterRefillPointMm,
    chartChannels = chartChannels,
    keepScreenOnBrew = keepScreenOnBrew,
    brewCueSound = brewCueSound,
    brewCueHaptics = brewCueHaptics,
    idleSleepMin = idleSleepMin,
    grinderModel = grinderModel,
    baristaName = baristaName,
    showDebugPanel = showDebugPanel,
    defaultDoseG = defaultDoseG,
    defaultRatio = defaultRatio,
    defaultBrewTempC = defaultBrewTempC,
    defaultPreinfuseS = defaultPreinfuseS,
    qcSteamTimeS = qcSteamTimeS,
    qcSteamFlowMlS = qcSteamFlowMlS,
    qcSteamTempC = qcSteamTempC,
    qcHotWaterTempC = qcHotWaterTempC,
    qcHotWaterVolumeMl = qcHotWaterVolumeMl,
    qcHotWaterMode = qcHotWaterMode,
    qcHotWaterTimeS = qcHotWaterTimeS,
    qcHotWaterTimeNoLimit = qcHotWaterTimeNoLimit,
    qcHotWaterRatio = qcHotWaterRatio,
    qcFlushTimeS = qcFlushTimeS,
    qcFlushTempC = qcFlushTempC,
).toCommonSettings()
