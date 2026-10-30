package coffee.crema.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import coffee.crema.ProcessScope
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.os.BatteryManager
import coffee.crema.core.ConnectSweepSettings
import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.ProfileSyncOutput
import coffee.crema.core.ProfileSyncStatus
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.MaintenancePhase
import coffee.crema.core.newShotId
import coffee.crema.core.MachineRequest
import coffee.crema.core.WaterSessionKind
import coffee.crema.core.MmrReg
import coffee.crema.core.MmrRegister
import coffee.crema.core.ScaleCapabilities
import coffee.crema.core.Bean
import coffee.crema.core.MaintenanceState
import coffee.crema.core.WriteTarget
import coffee.crema.core.SteamHotWaterTargets
import coffee.crema.core.WeightUnit
import coffee.crema.drive.DriveStore
import coffee.crema.drive.DriveSync
import coffee.crema.core.MaintenanceReadout
import coffee.crema.core.builtinCremaProfiles
import coffee.crema.core.cremaProfileToWire
import coffee.crema.core.exportBackupJsonl
import coffee.crema.core.CommonSettings
import coffee.crema.core.defaultIdleSleepMin
import coffee.crema.settings.resetCommon
import coffee.crema.settings.toCommonSettings
import coffee.crema.core.maintenanceReadout
import androidx.core.content.FileProvider
import coffee.crema.beans.beanDaysOffRoast
import coffee.crema.beans.isFrozen
import coffee.crema.history.StoredShot
import coffee.crema.maintenance.MaintenanceStore
import coffee.crema.maintenance.defaultMaintenanceState
import coffee.crema.persist.KeptAside
import coffee.crema.persist.KeptAsideNotices
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.ConfigSnapshot
import coffee.crema.settings.MirroredSettings
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
import coffee.crema.machine.ConfigEcho
import coffee.crema.machine.MachineActions
import coffee.crema.machine.MachineCommand
import coffee.crema.machine.MachineCommands
import coffee.crema.machine.QcField
import coffee.crema.core.ShotDisposition
import coffee.crema.core.ShotQualityReport
import coffee.crema.core.brewTempRange
import coffee.crema.core.normalizeBrewOverrides
import coffee.crema.core.preinfusionDial
import coffee.crema.profiles.CremaProfile
import coffee.crema.profiles.brewDefaultsJson
import coffee.crema.core.MachineState
import coffee.crema.profiles.SegmentEdit
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToInt
import coffee.crema.decent.DecentClient
import coffee.crema.decent.DecentStore
import coffee.crema.decent.DecentSync
import coffee.crema.decent.UniffiDecentCore
import coffee.crema.decent.decentShotRecordJson

/** How long after a DE1 connects the one-time "nearby" setup waits — long
 *  enough for the machine state to arrive (it only runs on an idle machine). */
private const val NEARBY_SETUP_DELAY_MS = 5_000L

/** The DE1 registers a hub sends its remotes (spec §6.1: A12, A13, A15). */
private val MIRRORED_REGISTERS = setOf(MmrRegister.HeaterVoltage, MmrRegister.CalibrationFlowMultiplier, MmrRegister.CupWarmerTemp)

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

    /** The idle-sleep timer's feed (see [IdleSleepRunner]): every input goes to
     *  the core with `elapsedRealtime`, and the output (the Sleep write, a
     *  throttled UserPresent poke on activity, events) routes like any other. */
    // Declared up here, before any `init` block: [startScreensaverClock] starts
    // it during construction, and a property declared below the init that
    // reads it is still null then.
    private val idleSleep by lazy {
        IdleSleepRunner(
            scope = viewModelScope,
            clock = { SystemClock.elapsedRealtime() },
            feed = { input, now ->
                routeWrite("Idle sleep") { bridge.idleSleep(idleSleepInputJson(input), now.toULong()) }
            },
        )
    }

    /** Monotonic ms of the idle timer's last Sleep send (0 = none) — so
     *  sleep-on-quit never doubles it ([quitSleepAllowed]). */
    @Volatile
    private var lastIdleSleepAtMs: Long = 0L


    /**
     * Single-threaded home for core-event application and every HEAVY
     * FFI interaction (review #28). The generated bridge is synchronous
     * blocking JNA and `viewModelScope` runs on Main — so the profile
     * upload chain janked the UI thread at the most latency-sensitive
     * moment, and `applyEvent` ran concurrently from three threads (the
     * two BLE manager scopes + Main), racing the ticker check-then-act
     * guards. All `CoreOutput` now applies on this lane (FIFO per
     * sender), and the heavy paths (startShot, the heartbeat clock)
     * run their bridge calls here instead of Main.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val coreDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

    /** Where the dial's debounce wait runs ([pushDesiredProfile]); the push
     *  itself always runs on the core lane. Test seam: a test dispatcher
     *  makes the 0.5 s virtual time. */
    @androidx.annotation.VisibleForTesting
    internal var dialDebounceContext: kotlin.coroutines.CoroutineContext = coreDispatcher

    /** Run [block] on the core lane, FIFO behind any queued event application.
     *  Every write to lane-owned state ([pendingBrew],
     *  [maintenanceTotalLitres]) goes through here or runs
     *  on the lane already: a plain Main-thread write is not ordered against a
     *  queued lane task (cohort review 2026-10-25, Android P2-3). */
    private fun onCoreLane(block: () -> Unit) {
        viewModelScope.launch(coreDispatcher) { block() }
    }

    private val _ui = MutableStateFlow(MainUiState())

    /**
     * The UI state the screens collect: the effective projection of [_ui]
     * ([effective]: on a remote, the hub's mirrored values laid over this
     * device's own; elsewhere [_ui] itself). While the screensaver covers
     * everything, a change confined to the high-rate live fields
     * ([hiddenBySaver]) is not published, so the screen tree behind the saver
     * stops recomposing several times a second for hours on a wall tablet
     * (cohort review 2026-10-25, P2-4). Any other change — including the
     * saver going away — publishes the latest state in full.
     *
     * [_ui] holds this device's own values plus the mirror slice, and is what
     * the VM persists. VM code reads it directly for its own values and
     * [effectiveState] for what is on screen (spec §7.2).
     */
    val ui: StateFlow<MainUiState> = _ui
        .map { it.effective() }
        .distinctUntilChanged(::hiddenBySaver)
        .stateIn(viewModelScope, SharingStarted.Eagerly, _ui.value.effective())

    /** This device's own state, for tests (AC-16): what [persistPrefs] saves. */
    @androidx.annotation.VisibleForTesting
    internal val ownState: StateFlow<MainUiState> = _ui

    /** The state the screens show ([effective]). */
    private fun effectiveState(): MainUiState = _ui.value.effective()

    /**
     * Run [edit], the body of a read-only machine or session setter (§7.3),
     * unless this device is a remote: there it changes nothing — no own value,
     * no machine write — and says where to change it.
     */
    private inline fun editOwnSetting(setting: Setting, edit: () -> Unit) {
        if (_ui.value.mirror != null) {
            appendLog("Not changing ${setting.name} on a remote")
            notifyUser("Change on ${_ui.value.remoteHubLabel()}")
            return
        }
        edit()
    }

    /**
     * Set when [startShot] waits for the DE1 to hold its profile before the
     * Espresso request (the gated start): the core's profile sync is uploading
     * it. Cleared on the matching `ProfileSyncHolds` (after firing Espresso), on
     * `ProfileUploadFailed`, on the timeout, and on disconnect.
     */
    private var pendingBrew = false

    /** The effective-profile fingerprint [pendingBrew] waits for. Lane-owned. */
    private var pendingBrewFingerprint: String? = null

    /** Which start armed [pendingBrew]: its backstop and its upload's
     *  outcome act only on that start. Lane-owned, like [pendingBrew]. */
    private var pendingBrewStart: ShotStartSequence.Start? = null

    /**
     * The one shot start in flight (05 F2): a second Start is refused while it
     * runs, and a user's machine request (Stop above all) cancels it rather
     * than letting the pre-flush release it to Espresso. Its writes go through
     * [writeMachineState], so they don't cancel it.
     */
    private val startSequence: ShotStartSequence = ShotStartSequence(
        scope = viewModelScope,
        request = { state ->
            // Records whether this shot came from Crema or from the machine's
            // GHC — the distinction that hid geota/crema#56. Set at the
            // Espresso request, so a start that was stopped first leaves the
            // next (group-head) shot attributed to the machine.
            if (state == MachineRequest.ESPRESSO) shotStartedByApp = true
            writeMachineState(state)
        },
        machineState = { _ui.value.machineStateName },
        onActiveChanged = { _ui.update { it.copy(shotStarting = startSequence.active) } },
        log = ::appendLog,
        notifyUser = ::notifyUser,
    )

    /** Whether the low-battery snackbar fired for the current scale
     *  connection (issue #29) — reset in [clearScaleSessionUi] so the next
     *  session warns again, but one connection never nags twice. */
    private var scaleLowBatteryWarned = false


    /** Ticker driving [MainUiState.modeElapsedMs] while a service mode runs. */
    private var modeTickerJob: Job? = null

    /**
     * The outstanding stop write, if any (issue #15 follow-up). Two rules:
     * stop writes COALESCE (the enforcer re-commands every second, but
     * stacking identical Idle bytes behind a wedged op adds nothing — at most
     * one is ever queued/waiting), and the survivor is CANCELLED the moment
     * the shot ends — a stale Idle delivered seconds late is not harmless: it
     * wakes a sleeping machine and kills a steam/flush the user starts right
     * after the shot.
     */
    private val pendingStopWrite = java.util.concurrent.atomic.AtomicReference<Job?>(null)

    /** Start the 4 Hz mode-clock ticker when a service mode is running, stop it
     *  when idle. Elapsed is recomputed from the monotonic anchor each tick, so
     *  tick jitter never accumulates into the readout. */
    private fun updateModeTicker() {
        val active = _ui.value.modeStartedAtMs != null
        if (active) {
            if (modeTickerJob?.isActive == true) return
            modeTickerJob = viewModelScope.launch {
                while (true) {
                    val started = _ui.value.modeStartedAtMs ?: break
                    _ui.update { it.copy(modeElapsedMs = SystemClock.elapsedRealtime() - started) }
                    delay(250)
                }
            }
        } else {
            modeTickerJob?.cancel()
            modeTickerJob = null
        }
    }

    /** App-preferences persistence — a JSON file in filesDir. */
    private val settingsStore = SettingsStore(app, json)

    /** Learned SAW drip-model persistence — an opaque core-owned JSON blob. */
    private val sawModelStore = SawModelStore(app)
    private val stolenSerials = coffee.crema.decent.StolenSerialsCheck(
        coffee.crema.decent.StolenSerialsFileStore(app),
    )

    /** Maintenance-state persistence — a JSON file in filesDir. */
    private val maintenanceStore = MaintenanceStore(app, json)

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
        beanLibrary = { effectiveState().let { coffee.crema.beans.BeanLibrary(it.beans, it.roasters, it.activeBeanId) } },
        onBeansSynced = { result, beanIds, roasterIds -> library.applyBeanSync(result, beanIds, roasterIds) },
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
     * that stay here (pushStopTargets, configSnapshotJson, startShot) read
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
        applyRestoredRecipes = { recipes, meta, wipe -> applyRestoredRecipes(recipes, meta, wipe) },
        applyRestoredCustomMethods = { methods, wipe -> applyRestoredCustomMethods(methods, wipe) },
        uiState = { _ui.value },
        effectiveState = { effectiveState() },
        updateUi = { transform -> _ui.update(transform) },
        notify = { notifyUser(it) },
        appendLog = { appendLog(it) },
        currentPrefs = { currentPrefs() },
        applyRestoredSettings = { applyRestoredSettings(it) },
        applyRestoredMaintenance = { m ->
            // Reseed the live water integrator + persist (maintenance is VM-owned).
            onCoreLane { maintenanceTotalLitres = m.totalLitres }
            saveMaintenance(m)
        },
        persistSawModel = {
            runCatching { sawModelStore.save(bridge.sawModelJson()) }
                .onFailure { appendLog("SAW model save failed: ${it.message}") }
        },
        setActiveProfile = { setActiveProfile(it) },
        resetBrewParams = { resetBrewParams() },
        onShotAnnotationsEdited = { id -> decent.scheduleReplaceAfterEdit(id) },
    )

    // ── Guided brew sessions (issue #10) ─────────────────────────────────
    //
    // The scale-only session lane: commands round-trip through the core's
    // BrewSessionMonitor; while a session is live a 250 ms tick drives its
    // countdowns (the core's on_tick is timestamp-based, so cadence jitter
    // is harmless). Cues render as a tone + vibration; no DE1 write ever
    // originates here.

    private val _guidedSetup = MutableStateFlow(coffee.crema.ui.brewlog.GuidedBrewSetup())
    private val _recipeEdit = MutableStateFlow<coffee.crema.ui.brewlog.RecipeEditDraft?>(null)
    private val _liveBrewSeries = MutableStateFlow(coffee.crema.core.BrewSeries(samples = emptyList(), stageMarks = emptyList()))

    /**
     * The running session's weight curve + stage marks, for the live chart.
     * Sampled on the session tick from the smoothed scale estimate; the core
     * keeps the authoritative recording (it lands in the summary).
     */
    val liveBrewSeries: StateFlow<coffee.crema.core.BrewSeries> = _liveBrewSeries.asStateFlow()

    private val recipeStore = coffee.crema.brew.RecipeFileStore(app, json)
    private var brewTickJob: kotlinx.coroutines.Job? = null

    // ── Declared above every `init` block (cohort review 2026-10-25, P3-2) ──
    // Kotlin initialises properties and runs `init` blocks in source order, so
    // a property declared below an init is still null/0 while that init runs
    // (the July 2026 launch crash). These four used to sit far below.

    /**
     * The open Log-brew form's draft, or null. VM-held (not a screen
     * `remember`) so it survives rotation and the phone↔tablet host swap:
     * the phone's pushed `log-brew` route and the tablet's side sheet on the
     * owning tab render this same draft (see [NavRestore.restoreRoute]).
     */
    private val _logBrew = MutableStateFlow<coffee.crema.ui.brewlog.BrewLogDraft?>(null)
    val logBrew: StateFlow<coffee.crema.ui.brewlog.BrewLogDraft?> = _logBrew.asStateFlow()

    /** Which profile writes may still go out and be acked (cohort review 2026-10-28, #2 N2). */
    private val profileWrites = ProfileWriteGenerations()

    /**
     * The 1 s core clock while the DE1 link is up or coming up (cohort review
     * 2026-10-28, #2 N1): the upload ack watchdog, the machine-model wait, the
     * re-upload repeat, the lost-scale watchdog and steam eco. The bridge call
     * and its output both run on the core lane, so a tick's events are never
     * stale against a start queued on the lane.
     */
    private val coreTicker by lazy {
        CoreTicker(viewModelScope) {
            kotlinx.coroutines.withContext(coreDispatcher) {
                applyCoreOutputJson(bridge.onTick(SystemClock.elapsedRealtime().toULong()))
            }
        }
    }

    /** The core's wake hint (`WakeAt`, F16): one one-shot tick on the core
     *  lane, cancelled when the link goes or the app is backgrounded. */
    private val wake by lazy {
        WakeTimer(viewModelScope, { SystemClock.elapsedRealtime() }, { at ->
            kotlinx.coroutines.withContext(coreDispatcher) {
                applyCoreOutputJson(bridge.onTick(at.toULong()))
            }
        })
    }

    /** Test seam: a wake-up tick is pending. */
    @androidx.annotation.VisibleForTesting
    internal val wakePending: Boolean get() = wake.pending

    /** Runs [seedMachineSettings] on each ready and [usbChargerTick] every minute while ready. */
    private val sweepRunner by lazy {
        ConnectSweepRunner(viewModelScope, sweep = ::seedMachineSettings, tick = ::usbChargerTick)
    }

    /** Monotonic ms of the last user interaction (the screensaver's idle
     *  clock) — bumped by the activity's root touch interceptor and by machine
     *  transitions into active modes. */
    @Volatile
    private var lastInteractionAtMs: Long = SystemClock.elapsedRealtime()

    /** Whether the activity is started (visible). */
    @Volatile
    private var appInForeground = true

    private fun startBrewTick() {
        if (brewTickJob?.isActive == true) return
        brewTickJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(250)
                val now = SystemClock.elapsedRealtime()
                onCoreOutputJson(bridge.onTick(now.toULong()))
                sampleLiveBrew(now)
            }
        }
    }

    /** Append the current scale reading to the live chart while the clock runs. */
    private fun sampleLiveBrew(now: Long) {
        val st = _ui.value
        val session = st.guidedBrew
        if (session.phase != "running") return
        val w = st.scaleWeightG ?: return
        if (st.scaleState != coffee.crema.ble.ScaleBleManager.State.READY) return
        val at = session.elapsedMs(now)
        _liveBrewSeries.update { s ->
            if (s.samples.size >= 4_500) {
                s
            } else {
                s.copy(samples = s.samples + coffee.crema.core.BrewSample(elapsedMs = at, weightG = w, flowGS = st.scaleFlowGPerS))
            }
        }
    }

    private fun stopBrewTick() {
        brewTickJob?.cancel()
        brewTickJob = null
    }

    /** Arm a session for [recipe]; [startOnPour] starts it at the first pour. */
    fun brewSessionArm(recipe: coffee.crema.core.BrewRecipe, startOnPour: Boolean) {
        val recipeJson = json.encodeToString(coffee.crema.core.BrewRecipe.serializer(), recipe)
        onCoreOutputJson(bridge.brewSessionArm(recipeJson, startOnPour))
        rememberRecipeUsed(recipe)
        _ui.update {
            it.copy(guidedBrew = GuidedBrewUi(phase = "armed", recipe = recipe, startOnPour = startOnPour))
        }
        _liveBrewSeries.value = coffee.crema.core.BrewSeries(samples = emptyList(), stageMarks = emptyList())
        _guidedSetup.update { it.copy(scaleMode = coffee.crema.ui.brewlog.ScaleMode.BREW) }
        startBrewTick()
        bgConnection.setBrewSessionActive(true)
    }

    /** Start an armed session's clock (the Start tap). */
    fun brewSessionBegin() =
        onCoreOutputJson(bridge.brewSessionBegin(SystemClock.elapsedRealtime().toULong()))

    fun brewSessionPause() {
        val now = SystemClock.elapsedRealtime()
        onCoreOutputJson(bridge.brewSessionPause(now.toULong()))
        _ui.update { st ->
            st.copy(guidedBrew = st.guidedBrew.copy(phase = "paused", pausedSinceRealtime = now))
        }
    }

    fun brewSessionResume() {
        val now = SystemClock.elapsedRealtime()
        onCoreOutputJson(bridge.brewSessionResume(now.toULong()))
        _ui.update { st ->
            val since = st.guidedBrew.pausedSinceRealtime
            st.copy(
                guidedBrew = st.guidedBrew.copy(
                    phase = "running",
                    pausedAccumMs = st.guidedBrew.pausedAccumMs + (since?.let { s -> now - s } ?: 0L),
                    pausedSinceRealtime = null,
                ),
            )
        }
    }

    fun brewSessionSkip() =
        onCoreOutputJson(bridge.brewSessionSkip(SystemClock.elapsedRealtime().toULong()))

    fun brewSessionFinish() =
        onCoreOutputJson(bridge.brewSessionFinish(SystemClock.elapsedRealtime().toULong()))

    /** Drop the session without a summary (backing out of setup / discard). */
    fun brewSessionCancel() {
        onCoreOutputJson(bridge.brewSessionCancel())
        stopBrewTick()
        bgConnection.setBrewSessionActive(false)
        _ui.update { it.copy(guidedBrew = GuidedBrewUi()) }
    }

    /** Clear a finished session's summary card (after save or discard). */
    fun brewSessionClearSummary() {
        _ui.update { it.copy(guidedBrew = GuidedBrewUi()) }
    }

    // ── Recipe library ───────────────────────────────────────────────

    /** Create-or-replace a recipe (bumps `updatedAt`) and persist. */
    fun upsertBrewRecipe(recipe: coffee.crema.core.BrewRecipe) {
        val next = recipe.copy(updatedAt = System.currentTimeMillis())
        _ui.update { st ->
            val list = st.brewRecipes.filter { it.id != recipe.id } + next
            st.copy(brewRecipes = list)
        }
        persistRecipes()
    }

    /**
     * Clone [id] (a user recipe or a built-in) into an editable copy — the
     * core's `duplicate_recipe`: fresh id, "<name> (copy)", credit "Adapted
     * from …" — and persist it. The Profiles Duplicate / "Duplicate to edit"
     * door. Returns the copy so the caller can open the editor.
     */
    fun duplicateBrewRecipe(id: String): coffee.crema.core.BrewRecipe? {
        val st = _ui.value
        val base = coffee.crema.brew.RecipeLibraryRules.find(id, st.brewRecipes, st.builtinRecipes) ?: return null
        val copy = runCatching {
            coffee.crema.brew.RecipeLibraryRules.duplicate(base, coffee.crema.core.newRecipeId(), System.currentTimeMillis())
        }.getOrNull() ?: return null
        _ui.update { it.copy(brewRecipes = it.brewRecipes + copy) }
        persistRecipes()
        return copy
    }

    /** Whether [id] is a built-in (read-only) recipe. */
    fun isBuiltinRecipe(id: String): Boolean =
        coffee.crema.brew.RecipeLibraryRules.isBuiltin(id, _ui.value.builtinRecipes)

    /** Tombstone a user recipe and drop any default pointer at it. Built-ins can't be deleted. */
    fun deleteBrewRecipe(id: String) {
        if (isBuiltinRecipe(id)) return
        val now = System.currentTimeMillis()
        _ui.update { st ->
            st.copy(
                brewRecipes = st.brewRecipes.map { if (it.id == id) it.copy(deletedAt = now) else it },
                lastRecipeByMethod = st.lastRecipeByMethod.filterValues { it != id },
            )
        }
        persistRecipes()
        resolveGuidedSetup()
    }

    /** Hide (true) or restore (false) a built-in recipe in the library + picker. */
    fun setBuiltinRecipeHidden(id: String, hidden: Boolean) {
        if (!isBuiltinRecipe(id)) return
        _ui.update {
            it.copy(hiddenBuiltinRecipes = if (hidden) it.hiddenBuiltinRecipes + id else it.hiddenBuiltinRecipes - id)
        }
        persistRecipes()
    }

    /** Make [recipe] what the Scale screen opens for its method. */
    fun setDefaultBrewRecipe(recipe: coffee.crema.core.BrewRecipe) = rememberRecipeUsed(recipe)

    /** The recipe [method] opens on (pointer, else its default built-in), or null. */
    fun defaultBrewRecipeFor(method: String): coffee.crema.core.BrewRecipe? {
        val st = effectiveState()
        return coffee.crema.brew.RecipeLibraryRules.defaultFor(method, st.brewRecipes, st.builtinRecipes, st.lastRecipeByMethod)
    }

    // ── Setup selections + the recipe editor (VM-held: survive rotation) ──


    /**
     * The Scale screen's Weigh | Brew mode and the Brew setup's method /
     * recipe / start-on-pour picks. VM-held, not a screen `remember`, so a
     * rotation across 840dp (a nav-host swap) keeps them.
     */
    val guidedSetup: StateFlow<coffee.crema.ui.brewlog.GuidedBrewSetup> = _guidedSetup.asStateFlow()

    private fun resolveGuidedSetup() {
        val st = effectiveState()
        _guidedSetup.update {
            coffee.crema.ui.brewlog.GuidedSetupRules.resolve(
                it,
                st.brewRecipes + st.builtinRecipes,
                st.lastRecipeByMethod,
                // A deleted custom method has no chip: open on the default instead.
                st.lastBrewLogMethod?.takeUnless { m -> isDeletedCustomMethod(m) },
            ) { m ->
                coffee.crema.brew.RecipeLibraryRules.defaultFor(m, st.brewRecipes, st.builtinRecipes, st.lastRecipeByMethod)
            }
        }
    }

    fun setScaleMode(mode: String) = _guidedSetup.update { it.copy(scaleMode = mode) }

    /** A method chip: the method's default recipe (pointer, else its built-in), or none. */
    fun selectGuidedMethod(method: String) {
        _guidedSetup.update { it.copy(method = method, recipe = null, methodChosen = true) }
        resolveGuidedSetup()
    }

    /** The multi-recipe dropdown. */
    fun selectGuidedRecipe(recipe: coffee.crema.core.BrewRecipe) {
        _guidedSetup.update { it.copy(method = recipe.method, recipe = recipe, methodChosen = true) }
    }

    fun setGuidedStartOnPour(on: Boolean) = _guidedSetup.update { it.copy(startOnPour = on) }

    /**
     * The setup's bean picker: a bag id or [coffee.crema.ui.brewlog.GuidedBeanRules.NO_BEAN].
     * Setup-only — the app's active bag is untouched (the log form's rule too).
     */
    fun selectGuidedBean(key: String) = _guidedSetup.update { it.copy(beanPick = key) }

    /** The bag the guided brew will debit — the setup's pick, else the active bag. */
    fun guidedBeanId(st: MainUiState = effectiveState()): String? =
        coffee.crema.ui.brewlog.GuidedBeanRules.resolve(_guidedSetup.value.beanPick, st.beans, st.activeBeanId)


    /**
     * The open recipe editor's working copy, or null. VM-held so edits
     * survive rotation: the phone pushes the `recipe-edit` route, the tablet
     * shows a side sheet on the owning tab (Scale's Brew setup, or Profiles),
     * both over this one draft (see [NavRestore.restoreRoute]).
     */
    val recipeEdit: StateFlow<coffee.crema.ui.brewlog.RecipeEditDraft?> = _recipeEdit.asStateFlow()

    fun openRecipeEdit(owner: String, recipe: coffee.crema.core.BrewRecipe, isNew: Boolean = false) {
        _recipeEdit.value = coffee.crema.ui.brewlog.RecipeEditDraft.of(owner, recipe, isNew)
    }

    fun updateRecipeEdit(transform: (coffee.crema.ui.brewlog.RecipeEditDraft) -> coffee.crema.ui.brewlog.RecipeEditDraft) {
        _recipeEdit.update { it?.let(transform) }
    }

    /** The editor's method selector (re-seeds the blank plan for a new recipe). */
    fun setRecipeEditMethod(method: String) = updateRecipeEdit { d ->
        d.withMethod(method) { m -> coffee.crema.brew.newRecipeFor(m, System.currentTimeMillis()) }
    }

    fun closeRecipeEdit() {
        _recipeEdit.value = null
    }

    /**
     * Save the editor's recipe and close it. A method's first saved recipe
     * becomes its default; an edit opened from the Scale setup comes back as
     * that setup's selection, so Save returns to the same setup, updated.
     */
    fun saveRecipeEdit() {
        val d = _recipeEdit.value ?: return
        val now = System.currentTimeMillis()
        val edited = d.toRecipe()
        // Built-ins are read-only: an edit handed a built-in saves as a
        // credited copy, never over the bundled id.
        val saved = if (isBuiltinRecipe(edited.id)) {
            coffee.crema.brew.RecipeLibraryRules.duplicate(edited, coffee.crema.core.newRecipeId(), now)
                .copy(name = edited.name.takeIf { it != d.base.name } ?: "${edited.name} (copy)")
        } else {
            edited.copy(updatedAt = now)
        }
        upsertBrewRecipe(saved)
        if (coffee.crema.brew.RecipeLibraryRules.becomesDefaultOnSave(saved.method, _ui.value.lastRecipeByMethod)) {
            rememberRecipeUsed(saved)
        }
        if (d.owner == coffee.crema.ui.brewlog.RecipeEditOwner.SCALE) {
            _guidedSetup.update { it.copy(method = saved.method, recipe = saved, methodChosen = true) }
        }
        _recipeEdit.value = null
        resolveGuidedSetup()
    }

    // ── Your own brewing methods (issue #10 feedback) ────────────────
    //
    // The add / edit dialog's draft is VM-held like the other editors: the
    // phone pushes the `method-edit` route (PhoneNavHost follows the draft),
    // the tablet shows a side sheet over whatever is open, and a rotation
    // across 840dp keeps what was typed (see [NavRestore.restoreRoute]).

    private val _methodEdit = MutableStateFlow<coffee.crema.ui.brewlog.MethodEditDraft?>(null)
    val methodEdit: StateFlow<coffee.crema.ui.brewlog.MethodEditDraft?> = _methodEdit.asStateFlow()

    /** Whether [method] names a custom method that has been deleted. */
    private fun isDeletedCustomMethod(method: String): Boolean =
        coffee.crema.brew.isCustomMethodId(method) &&
            _ui.value.customMethods.none { it.id == method.trim().lowercase() && it.deletedAt == null }

    /** The tab a [target]'s dialog belongs to (the opener's owning tab). */
    private fun methodEditOwnerTab(target: String): String = when (target) {
        coffee.crema.ui.brewlog.MethodEditTarget.LOG -> _logBrew.value?.owner ?: "history"
        coffee.crema.ui.brewlog.MethodEditTarget.RECIPE -> _recipeEdit.value?.owner ?: "profiles"
        coffee.crema.ui.brewlog.MethodEditTarget.SCALE -> "scale"
        else -> "profiles"
    }

    /** "+ Add method…" from [target] (a [coffee.crema.ui.brewlog.MethodEditTarget]). */
    fun openNewMethod(target: String, label: String = "") {
        _methodEdit.value = coffee.crema.ui.brewlog.MethodEditRules.open(
            target = target,
            ownerTab = methodEditOwnerTab(target),
            session = System.nanoTime(),
            label = label,
        )
    }

    /** Profiles → Your methods → Rename / Edit defaults. */
    fun openEditMethod(id: String) {
        val m = _ui.value.customMethods.firstOrNull { it.id == id } ?: return
        _methodEdit.value = coffee.crema.ui.brewlog.MethodEditRules.edit(
            target = coffee.crema.ui.brewlog.MethodEditTarget.MANAGE,
            ownerTab = "profiles",
            session = System.nanoTime(),
            m = m,
        )
    }

    fun updateMethodEdit(transform: (coffee.crema.ui.brewlog.MethodEditDraft) -> coffee.crema.ui.brewlog.MethodEditDraft) {
        _methodEdit.update { it?.let(transform) }
    }

    fun setMethodEditStyle(style: coffee.crema.core.BrewMethodStyle) = updateMethodEdit {
        coffee.crema.ui.brewlog.MethodEditRules.withStyle(it, style)
    }

    fun closeMethodEdit() {
        _methodEdit.value = null
    }

    /**
     * Validate + save the dialog's method, then select it where it was asked
     * for (the log form, the recipe editor, the Brew setup). Returns false —
     * the dialog stays open with the message — on a bad name.
     */
    fun saveMethodEdit(): Boolean {
        val d = _methodEdit.value ?: return false
        val label = coffee.crema.brew.validateCustomMethodLabel(d.label, d.base?.id, _ui.value.customMethods)
            .getOrElse { e ->
                _methodEdit.value = d.copy(error = e.message)
                return false
            }
        val method = coffee.crema.ui.brewlog.MethodEditRules.toMethod(
            d,
            label,
            coffee.crema.core.newCustomMethodId(),
            System.currentTimeMillis(),
        )
        setCustomMethods(coffee.crema.ui.brewlog.MethodEditRules.upsert(_ui.value.customMethods, method))
        _methodEdit.value = null
        when (d.target) {
            coffee.crema.ui.brewlog.MethodEditTarget.LOG -> if (d.isNew) reseedLogBrew(method.id)
            coffee.crema.ui.brewlog.MethodEditTarget.RECIPE -> if (d.isNew) setRecipeEditMethod(method.id)
            coffee.crema.ui.brewlog.MethodEditTarget.SCALE -> if (d.isNew) selectGuidedMethod(method.id)
            else -> {}
        }
        if (!d.isNew) resolveGuidedSetup()
        return true
    }

    /**
     * Delete a custom method: a tombstone, so past brews keep their name and
     * "Log again" still seeds. Recipes that use it are KEPT — still runnable
     * from Profiles, labelled with the method's name. A setup or log form
     * sitting on it falls back to the usual opening method.
     */
    fun deleteCustomMethod(id: String) {
        setCustomMethods(coffee.crema.ui.brewlog.MethodEditRules.tombstone(_ui.value.customMethods, id, System.currentTimeMillis()))
        if (_ui.value.lastBrewLogMethod == id) {
            _ui.update { it.copy(lastBrewLogMethod = null) }
            persistPrefs()
        }
        if (_guidedSetup.value.method == id) {
            _guidedSetup.update { it.copy(method = null, recipe = null, methodChosen = false) }
        }
        resolveGuidedSetup()
    }

    /** Keep the registry + UI state in step and persist (the recipe store's envelope). */
    private fun setCustomMethods(list: List<coffee.crema.core.CustomBrewMethod>) {
        coffee.crema.brew.CustomMethods.all = list
        _ui.update { it.copy(customMethods = list) }
        persistRecipes()
    }

    /**
     * "Save 'X' as a method?" → yes: create the method (percolation; editable
     * later in Profiles) and re-tag that brew with its id + label snapshot. A
     * name that clashes with an existing method re-tags to that one instead.
     */
    fun acceptSaveMethodPrompt() {
        val p = _ui.value.saveMethodPrompt ?: return
        _ui.update { it.copy(saveMethodPrompt = null) }
        val existing = _ui.value.customMethods.firstOrNull {
            it.deletedAt == null && it.label.equals(p.label, ignoreCase = true)
        }
        val method = existing ?: run {
            val label = coffee.crema.brew.validateCustomMethodLabel(p.label, null, _ui.value.customMethods)
                .getOrElse { e -> notifyUser(e.message ?: "Couldn’t save that method"); return }
            val now = System.currentTimeMillis()
            coffee.crema.core.CustomBrewMethod(
                id = coffee.crema.core.newCustomMethodId(),
                label = label,
                style = coffee.crema.core.BrewMethodStyle.Percolation,
                createdAt = now,
                updatedAt = now,
            ).also { setCustomMethods(_ui.value.customMethods + it) }
        }
        library.retagBrewMethod(p.shotId, method.id, method.label)
        if (_ui.value.lastBrewLogMethod == p.freeText) {
            _ui.update { it.copy(lastBrewLogMethod = method.id) }
            persistPrefs()
        }
        notifyUser("Saved “${method.label}” as a method")
    }

    fun dismissSaveMethodPrompt() = _ui.update { it.copy(saveMethodPrompt = null) }

    /** Apply a backup's custom methods (MERGE by id, newer wins / WIPE replaces). */
    private fun applyRestoredCustomMethods(methods: List<coffee.crema.core.CustomBrewMethod>, wipe: Boolean): Int {
        if (methods.isEmpty() && !wipe) return 0
        val (list, added) = coffee.crema.ui.brewlog.MethodEditRules.mergeRestored(_ui.value.customMethods, methods, wipe)
        setCustomMethods(list)
        return added
    }

    /** The finished session's "Save brew…": the Log-brew form, pre-filled with measured truth. */
    fun openGuidedLogBrew() {
        val s = effectiveState()
        val session = s.guidedBrew
        val recipe = session.recipe ?: _guidedSetup.value.recipe
        val summary = session.summary
        _logBrew.value = coffee.crema.ui.brewlog.BrewLogSeeds.open(
            owner = coffee.crema.ui.brewlog.BrewLogOwner.SCALE,
            history = s.history,
            activeBeanId = s.activeBeanId,
            guidedMethod = summary?.method ?: recipe?.method,
            guidedDoseG = recipe?.doseG,
            // The scale's final reading (else the plan): the core seeding
            // routes it to espresso's yield slot or a filter's water-in.
            guidedWeightG = summary?.finalWeightG ?: recipe?.waterG,
            guidedTempC = recipe?.tempC,
            guidedDurationMs = summary?.durationMs,
            guidedRecipeName = summary?.recipeName,
            // A scale-less session records stage marks but no weight: the
            // row saves without a series (spec §8), like a manual log.
            guidedSeries = summary?.series?.takeIf { it.samples.isNotEmpty() },
            // The setup's bag, not the active one; still changeable in the form.
            guidedBeanId = guidedBeanId(s),
        )
    }

    /**
     * Remember [recipe] as its method's default (persisting a new user
     * recipe). A built-in only moves the pointer — it's bundled, never stored.
     */
    private fun rememberRecipeUsed(recipe: coffee.crema.core.BrewRecipe) {
        val builtin = isBuiltinRecipe(recipe.id)
        _ui.update { st ->
            val list = if (builtin || st.brewRecipes.any { it.id == recipe.id }) st.brewRecipes
            else st.brewRecipes + recipe
            st.copy(
                brewRecipes = list,
                lastRecipeByMethod = st.lastRecipeByMethod + (recipe.method to recipe.id),
            )
        }
        persistRecipes()
    }

    /**
     * Apply a backup's recipes: adopt user recipes by id (MERGE keeps what's
     * here; WIPE replaces the library), skipping any built-in id, then the
     * `recipeMeta` pointers + hidden built-ins. Returns the recipes added.
     */
    private fun applyRestoredRecipes(
        recipes: List<coffee.crema.core.BrewRecipe>,
        meta: kotlinx.serialization.json.JsonObject?,
        wipe: Boolean,
    ): Int {
        val st = _ui.value
        val builtinIds = st.builtinRecipes.map { it.id }.toHashSet()
        val base = if (wipe) emptyList() else st.brewRecipes
        val have = base.map { it.id }.toHashSet()
        val added = recipes.filter { it.id !in builtinIds && it.id !in have }
        val defaults = (meta?.get("defaults") as? kotlinx.serialization.json.JsonObject)
            ?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k to it } }
            ?.toMap().orEmpty()
        val hidden = (meta?.get("hiddenBuiltins") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.filter { it in builtinIds }?.toSet().orEmpty()
        _ui.update {
            it.copy(
                brewRecipes = base + added,
                // MERGE: this device's pointers win; the bundle fills the gaps.
                lastRecipeByMethod = if (wipe) defaults else defaults + it.lastRecipeByMethod,
                hiddenBuiltinRecipes = if (wipe) hidden else it.hiddenBuiltinRecipes + hidden,
            )
        }
        persistRecipes()
        resolveGuidedSetup()
        return added.size
    }

    private fun persistRecipes() {
        val st = _ui.value
        viewModelScope.launch {
            recipeStore.save(
                coffee.crema.brew.RecipeFileStore.Envelope(
                    st.brewRecipes.filterNot { r -> st.builtinRecipes.any { it.id == r.id } },
                    st.lastRecipeByMethod,
                    st.hiddenBuiltinRecipes,
                    st.customMethods,
                ),
            )
        }
    }

    // ── Cues ─────────────────────────────────────────────────────────

    /**
     * Render one guided-brew cue as a short tone + vibration. Best-effort
     * on both legs — a cue is advisory by definition. Tones ride the
     * notification stream (kitchen volume), vibration uses the default
     * amplitude; devices without a vibrator (most tablets) get tone-only.
     */
    private fun playBrewCue(kind: String) {
        val st = _ui.value
        // Visual cue: always on (the step card flashes on this stamp).
        if (kind != "done") {
            _ui.update { it.copy(guidedBrew = it.guidedBrew.copy(cueAtRealtime = SystemClock.elapsedRealtime(), cueKind = kind)) }
        }
        if (coffee.crema.ui.brewlog.BrewCueDefaults.soundOn(st.brewCueSound)) playBrewTone(kind)
        if (coffee.crema.ui.brewlog.BrewCueDefaults.hapticsOn(st.brewCueHaptics)) playBrewHaptic(kind)
    }

    private fun playBrewTone(kind: String) {
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
            when (kind) {
                "approach" -> tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
                "boundary" -> tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 220)
                "step" -> tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 180)
                "done" -> tone.startTone(android.media.ToneGenerator.TONE_CDMA_CONFIRM, 400)
            }
            // Release after the tone has played out.
            viewModelScope.launch {
                kotlinx.coroutines.delay(600)
                runCatching { tone.release() }
            }
        }
    }

    private fun playBrewHaptic(kind: String) {
        runCatching {
            val vibrator = androidx.core.content.ContextCompat.getSystemService(
                getApplication(),
                android.os.Vibrator::class.java,
            )
            val pattern = when (kind) {
                "approach" -> longArrayOf(0, 60)
                "boundary" -> longArrayOf(0, 80, 60, 80)
                "step" -> longArrayOf(0, 120)
                else -> longArrayOf(0, 100, 60, 100, 60, 160)
            }
            vibrator?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        }
    }

    /**
     * The live integrated water total, litres — seeded from the loaded
     * [MaintenanceState.totalLitres] in [loadMaintenance] and advanced on every
     * `Event.Telemetry` (group_flow × Δseconds / 1000). Held in a `var` rather
     * than the ui-state so the ~25–40 Hz accumulate does NOT thrash recomposition;
     * it is flushed into the persisted state (and the readout recomputed) only on
     * shot / water-session completion and after a mark action.
     */
    private var maintenanceTotalLitres: Double = 0.0

    /**
     * What this device does for each [MachineCommand]: the bodies behind the
     * verbs. [ProxyController]'s local machine runs them for this device's own
     * UI and for a secondary's relayed commands alike. Declared before [proxy],
     * which takes it; its members reach later fields only when invoked.
     */
    private val machineActions = object : MachineActions {
        override fun applyStartShot(): CommandOutcome = this@MainViewModel.applyStartShot()
        override fun applyMachineState(request: MachineRequest) = this@MainViewModel.applyMachineState(request)
        override fun applyCancelMaintenance() = this@MainViewModel.applyCancelMaintenance()
        override fun applyTareScale() = this@MainViewModel.applyTareScale()
        override fun showConfig(cmd: MachineCommand.Config) = this@MainViewModel.showConfig(cmd)
        override fun commitConfig(cmd: MachineCommand.Config) = this@MainViewModel.commitConfig(cmd)
        override fun hasProfile(id: String): Boolean = library.hasProfile(id)
        override fun hasBean(id: String): Boolean = _ui.value.beans.any { it.id == id }
        // Someone is using Crema on a mirror: that's presence at the machine.
        override fun notePeerActivity() = idleSleep.activity(coffee.crema.core.IdleActivity.Mirror)
        override fun configReady(): Boolean = prefsLoaded
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
        snapshotConfig = { configSnapshotJson() },
        applyConfig = { applyRemoteConfig(it) },
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
        onRoleStarting = { role, refind -> onRoleStarting(role, refind) },
        showPending = { showPending(it) },
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
                prefsReady.await()
                _ui.update { it.copy(proxyPrimaryHost = host, proxyPrimaryPort = port) }
                persistPrefs()
            }
        },
        prefsReady = { prefsReady.await() },
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
        onCoreOutput = ::onCoreOutputJson,
        onStatus = { line -> _ui.update { it.copy(status = line) } },
        timeline = coffee.crema.diag.ReconnectTimelines.recorder,
    )

    /** Keeps the process — and thus the DE1 link + its reconnect loop — alive
     *  while the screen is off (geota/crema#65). Gated on the user's pref AND
     *  charging by [BackgroundConnectionController]; fed the live DE1 state,
     *  effective setting, and foreground state below. */
    private val bgConnection = coffee.crema.ble.BackgroundConnectionController(app)

    // ── Firmware updates (alpha) ──────────────────────────────────────────
    // The core's flash state machine (`de1_app::firmware_flash`) driven over
    // the DE1 link by its FirmwareFlashDriver; [firmware] is the Settings flow. The
    // driver runs off Main (29k blocking FFI round trips per flash) in a child
    // of viewModelScope; the foreground service + screen-on keep it alive.

    private val firmwareScope = kotlinx.coroutines.CoroutineScope(
        viewModelScope.coroutineContext +
            kotlinx.coroutines.SupervisorJob(viewModelScope.coroutineContext[kotlinx.coroutines.Job]) +
            Dispatchers.Default +
            // A throw in the driver must reach the log, not kill the process
            // mid-flash (audit L9); the core keeps the flash and its lock.
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> appendLog("Firmware: driver error: ${e.message}") },
    )

    private val firmwareCore = coffee.crema.firmware.BridgeFlashCore(bridge)

    /** The DE1 link surface for the flash: its own writes pass the exclusive
     *  gate; the gate waits for ordinary writes already on the air (audit M1). */
    private val firmwareIo = object : coffee.crema.firmware.FlashIoPort {
        override suspend fun write(target: WriteTarget, data: ByteArray) = ble.firmwareWrite(target, data)
        override suspend fun readFwMap(): ByteArray = ble.firmwareReadFwMap()
        override suspend fun subscribeFwMap(onFrame: (ByteArray) -> Unit) = ble.firmwareSubscribeFwMap(onFrame)
        override suspend fun awaitQuiet(): Boolean =
            ble.awaitNonFlashWritesDrained(coffee.crema.firmware.DRAIN_TIMEOUT_MS)
        override fun setExclusive(on: Boolean) {
            ble.firmwareExclusive = on
            if (!on) ble.firmwareUnsubscribeFwMap()
        }
    }

    /**
     * The connect-time setup (identity reads + settings sweep) has run on this
     * link. A flash only starts after it, so its writes are not still queued
     * behind the gate (audit M1). Reset on every drop.
     */
    @Volatile
    private var de1SetupDone = false

    /** Settings → Machine → "Update firmware…" (alpha). */
    val firmware: coffee.crema.firmware.FirmwareUpdateController = coffee.crema.firmware.FirmwareUpdateController(
        scope = firmwareScope,
        core = firmwareCore,
        catalog = object : coffee.crema.firmware.FirmwareCatalog {
            override fun manifest() = json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(coffee.crema.core.FirmwareArtifact.serializer()),
                coffee.crema.core.firmwareManifest(),
            )
            override fun validate(image: ByteArray) = json.decodeFromString(
                coffee.crema.core.FirmwareImageCheck.serializer(),
                coffee.crema.core.validateFirmwareImage(image),
            )
        },
        fetcher = coffee.crema.firmware.KtorFirmwareFetcher(),
        conditions = {
            val state = ble.state.value
            coffee.crema.core.FlashShellConditions(
                connected = state == De1BleManager.State.READY,
                ready = state == De1BleManager.State.READY && de1SetupDone,
                guidedBrewActive = _ui.value.guidedBrew.live,
                powerOk = firmwarePowerOk(app),
            )
        },
        installedBuild = { _ui.value.de1MachineInfo[MmrRegister.FirmwareVersion] },
        log = ::appendLog,
        markers = coffee.crema.firmware.SharedPrefsAwaitingMarkers(app),
        machineId = { ble.connectedAddress },
        linkUp = { ble.state.value == De1BleManager.State.READY },
        // The flash let go of the link: run the connect-time work it held off.
        onRelease = { viewModelScope.launch { afterFirmwareRelease() } },
        driverFactory = { onStatus ->
            coffee.crema.firmware.FirmwareFlashDriver(
                core = firmwareCore,
                io = firmwareIo,
                scope = firmwareScope,
                clock = SystemClock::elapsedRealtime,
                onStatus = onStatus,
                onLog = ::appendLog,
                keepAlive = { active ->
                    viewModelScope.launch { bgConnection.setFirmwareFlashActive(active) }
                    // HIGH priority for the transfer: one acknowledged 16-byte
                    // write at a time makes the connection interval the
                    // speed limit (~29,000 round trips), so this cuts it from
                    // Android's default ~30-50 ms to ~11-15 ms (Decenza keeps
                    // its DE1 link at HIGH). Back to BALANCED when it ends.
                    viewModelScope.launch { ble.setConnectionPriority(high = active) }
                },
                transportLabel = {
                    "Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}), " +
                        "Nordic BLE, DE1 ${ble.connectedAddress ?: "unknown"}"
                },
            )
        },
    )

    /**
     * A flash (or its erase / power-cycle window) holds the DE1 link: the
     * reconnect side-effects — settings sweep, identity reads, charger checks,
     * presence pokes, the saver's sleep — stay off the machine (audit H2). The
     * core refuses them too; this keeps them from running (and being marked
     * done) at all.
     */
    private fun firmwareHolds(): Boolean =
        runCatching { bridge.firmwareLocksWrites() }.getOrDefault(firmware.ui.value.holdsLink)

    private fun afterFirmwareRelease() {
        if (ble.state.value != De1BleManager.State.READY) return
        appendLog("Firmware: link released — running the connect setup")
        readMachineInfo()
        sweepRunner.onReady()
    }

    /** True on tablet-class displays (≥600dp smallest width) — the form-factor
     *  default for [MainUiState.keepConnectedScreenOff] (tablet on / phone off). */
    private val isTablet = app.resources.configuration.smallestScreenWidthDp >= 600

    /**
     * The Bookoo scale connection. Shares the one [bridge] with [ble]: the core
     * is internally `Mutex`-guarded, so both managers feeding it concurrently
     * is safe. Its `CoreOutput` goes through the same [onCoreOutputJson] path.
     * It also shares the one [bleRecorder] so its weight notifications and
     * tare/timer writes land in the same interleaved capture file as the DE1's.
     */
    private val scale: ScaleBleManager = ScaleBleManager(
        transport = proxy.transport,
        bridge = bridge,
        recorder = bleRecorder,
        onCoreOutput = ::onCoreOutputJson,
        onStatus = { line -> _ui.update { it.copy(status = line) } },
        onScaleIdentified = ::refreshScaleCapabilities,
        timeline = coffee.crema.diag.ReconnectTimelines.recorder,
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
        coreDispatcher = coreDispatcher,
        ble = ble,
        scale = scale,
        bleScanner = bleScanner,
        transport = proxy.transport,
        appendLog = { appendLog(it) },
        persistPrefs = { persistPrefs() },
        onConnectStarted = { _ui.update { it.copy(eventLog = emptyList()) } },
        onDe1SessionClosed = { clearDe1SessionUi() },
        onScaleSessionClosed = { clearScaleSessionUi() },
        onDe1Ready = {
            // Into a DE1 still in a flash / reboot window, nothing but the
            // flash's own build read (audit H2); [afterFirmwareRelease] runs
            // these once it lets go.
            if (firmwareHolds()) {
                appendLog("Firmware: DE1 ready during a firmware update — connect setup deferred")
            } else {
                readMachineInfo()
                sweepRunner.onReady()
            }
            idleSleep.link(true)
            // Re-assert the stop targets on every (re)connect. The core now
            // carries them across `reset()` itself, so this is belt-and-braces
            // — but it is the only thing standing between a GHC-started shot
            // and an unarmed auto-stop if the core is ever rebuilt some other
            // way (geota/crema#56). Pure core state, no BLE.
            pushStopTargets()
            // "Keep connected in the background" sets up Android's "nearby"
            // association once — a few seconds in, once the machine state is
            // known (it only runs on an idle machine).
            scheduleNearbySetup()
        },
        // The core's profile sync forgets what this connection pushed when
        // the link drops (`de1LinkLost` / `reset`) and uploads again after
        // the next connect sweep.
        onDe1Dropped = {
            de1SetupDone = false
            sweepRunner.onDropped()
            idleSleep.link(false)
            // The last state the DE1 reported is history once the link is
            // gone. Until the next connect's StateInfo seed read lands, the
            // Brew hero must say "syncing", not replay "Idle" from before the
            // drop (BrewReadiness).
            _ui.update { it.copy(machineState = null, machineStateName = null, machineSubstate = null) }
        },
        pushRoster = { proxy.pushRoster() },
        refreshAdvertisement = { proxy.refreshAdvertisement() },
        heartbeatIntervalMs = { _ui.value.scaleCapabilities?.heartbeat_interval_ms?.toLong() },
        sendScaleHeartbeat = { onCoreOutputJson(bridge.scaleHeartbeat()) },
        isAppForeground = { appInForeground },
        timeline = coffee.crema.diag.ReconnectTimelines.recorder,
        companion = companion,
        presenceEvents = coffee.crema.ble.companion.CompanionPresence.events,
    )

    /** Every store file reported unreadable this process (see [KeptAsideNotices]). */
    private var keptAside: List<KeptAside> = emptyList()

    /** This VM's reconnect-timeline hook and sleep-on-quit registration. The
     *  deferred teardown in [onCleared] releases exactly these, so a VM built
     *  meanwhile keeps its own (final cohort review 2026-10-28, 04 P3-1). */
    private val timelineLogHook: (coffee.crema.ble.ReconnectTimeline) -> Unit = { appendLog(it.compactLine()) }
    private var quitSleepRegistration: Any? = null

    // ── Declared above every `init` block too (P3-2) ──────────────────────
    // Moved up from their feature sections below, in their original order,
    // so that no property is declared after an `init` block.

    /** The post-steam auto-purge (issue 15). Only the device holding the DE1
     *  runs it, and it stands down for a pending shot start. */
    private val autoPurge = AutoPurge(
        scope = viewModelScope,
        authoritative = { proxy.state.value.role != "secondary" },
        startPending = { startSequence.active },
        flush = { writeMachineState(MachineRequest.FLUSH) },
    )

    /** Connect-time machine-info sweep (see [readMachineInfo]); cancelled on reconnect/disconnect. */
    private var machineInfoJob: kotlinx.coroutines.Job? = null

    /** True once [loadPrefs] has hydrated the UI from disk — writes before that
     *  would snapshot half-initialised state and clobber the saved prefs (e.g.
     *  erase the restored activeProfileId during boot). */
    @Volatile private var prefsLoaded = false

    /** Completes with [prefsLoaded] (or when the startup load ends without
     *  it): the hub's relay waits for it, so it never serves before the
     *  paired devices are known. */
    private val prefsReady = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** The whole startup load (library, prefs, custom profiles, …). Tests wait
     *  for it: [prefsReady] completes partway through. */
    @androidx.annotation.VisibleForTesting
    internal lateinit var startupLoad: kotlinx.coroutines.Job

    /** The pending "nearby" setup after a DE1 connects (see [setUpNearby]). */
    private var nearbySetupJob: kotlinx.coroutines.Job? = null

    /** The address of a DE1 link the setup paused, to reconnect afterwards. */
    private var nearbyPausedAddress: String? = null

    /** Last whole-millimetre tank level written to the event log, so an
     *  unchanged reading doesn't flood the diagnostics buffer (#56). */
    private var lastLoggedWaterMm: Int? = null

    /** The core's last-armed stop targets, kept for the shot record's verdict
     *  line. Sourced from `Event.StopTargetsArmed`, i.e. what the core ACTUALLY
     *  armed — not what the Brew card computed from the UI profile. When these
     *  disagree, that discrepancy IS the bug (geota/crema#56). */
    private var lastArmedTargets: String? = null

    /** Whether the CURRENT shot was started from Crema rather than at the
     *  machine. A GHC start runs none of the shell's shot-start path, which is
     *  how geota/crema#56 hid — worth recording per shot. */
    @Volatile
    private var shotStartedByApp: Boolean = false

    /** Monotonic ms of the last sleep the app itself asked for (the Brew power
     *  button, the phone menu, a mirrored peer), or 0 once consumed. */
    @Volatile
    private var appSleepRequestedAtMs: Long = 0L

    // ── `init` blocks: every property is declared above this point (P3-2) ──

    init {
        // Multi-device (M2): start the persisted proxy role (a primary's relay,
        // a secondary's mirror link), arm the recorder gate, start NSD peer
        // discovery and advertise this instance. First, so the managers'
        // transport is the role's before anything can drive it, and here, not
        // in the controller's construction: it reaches [bleRecorder]/[ble].
        proxy.start()
        // Seed the recipe library from disk — cheap (a handful of rows).
        viewModelScope.launch {
            val loaded = recipeStore.load()
            // One-time clean-up: untouched legacy "… classic" starters go,
            // their default pointers move to the method's built-in.
            val migrated = coffee.crema.brew.RecipeLibraryRules.migrate(loaded)
            if (migrated != null) recipeStore.save(migrated)
            val env = migrated ?: loaded
            coffee.crema.brew.CustomMethods.all = env.customMethods
            _ui.update {
                it.copy(
                    brewRecipes = env.recipes,
                    lastRecipeByMethod = env.lastUsedByMethod,
                    hiddenBuiltinRecipes = env.hiddenBuiltins,
                    builtinRecipes = coffee.crema.brew.BUILTIN_BREW_RECIPES,
                    customMethods = env.customMethods,
                )
            }
            resolveGuidedSetup()
        }
        // Rows saved with a custom method snapshot its label; keep the
        // id → label fallback current for methods this device doesn't know.
        viewModelScope.launch {
            _ui.map { it.history }.distinctUntilChanged().collect { rows ->
                coffee.crema.brew.CustomMethods.snapshotLabels = buildMap {
                    rows.forEach { r ->
                        val id = r.brewMethod?.trim()?.lowercase() ?: return@forEach
                        val label = r.brewMethodLabel ?: return@forEach
                        if (coffee.crema.brew.isCustomMethodId(id) && id !in this) put(id, label)
                    }
                }
            }
        }
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
        // Sleep-on-quit (see [QuitSleep]): swiping the task from recents is
        // the one "I'm done" signal mere backgrounding never sends. Fired
        // from [QuitSleepService.onTaskRemoved]; every gate re-checks at
        // fire time — never mid-shot (idle only), and never from a
        // secondary (its sleep() would relay to the machine the primary is
        // still using).
        quitSleepRegistration = QuitSleep.install {
            val s = _ui.value
            // "Not busy", not "exactly Idle": a machine that is heating,
            // running a calibration or simply hasn't reported a state yet is
            // still safe to sleep, and requiring MachineState.Idle silently
            // skipped those quits. The exclusions are the ones that must never
            // be cut off mid-way — the same set the screensaver refuses to
            // interrupt — plus an already-asleep machine (nothing to do).
            // ...and never a sleep the firmware would latch in Refill and
            // honour right after the next refill (firmware < 1357).
            val sleepable = !machineBusyForSaver(s) &&
                s.machineStateName != MachineState.Sleep &&
                s.machineStateName != MachineState.GoingToSleep &&
                !sleepLatchesInRefill(s)
            val primary = s.proxyRole != "secondary"
            // The idle timer may have sent Sleep moments ago (not yet
            // confirmed): don't send a second one.
            val notDoubled = quitSleepAllowed(lastIdleSleepAtMs, SystemClock.elapsedRealtime(), APP_SLEEP_WINDOW_MS)
            // Logcat, not appendLog: the in-app event log dies with the task.
            if (s.sleepOnQuit && sleepable && primary && notDoubled) {
                Log.i("QuitSleep", "quit while idle — putting the DE1 to sleep")
                launchQuitSleepWrite()
            } else {
                Log.i(
                    "QuitSleep",
                    "quit — sleep skipped (on=${s.sleepOnQuit} state=${s.machineStateName} role=${s.proxyRole} idleSleepPending=${!notDoubled})",
                )
                null
            }
        }
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
            pushStopTargets()
            loadMaintenance()
            visualizer.load()
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
            prefsReady.complete(Unit)
        }
        startScreensaverClock()
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
    fun openLogBrew(owner: String, prefill: StoredShot? = null, beanId: String? = null) {
        val s = effectiveState()
        _logBrew.value = coffee.crema.ui.brewlog.BrewLogSeeds.open(
            owner = owner,
            history = s.history,
            activeBeanId = s.activeBeanId,
            prefill = prefill,
            prefillBeanId = beanId,
            lastUsedMethod = s.lastBrewLogMethod,
            grinderOf = ::beanGrinderSetting,
        )
    }

    /** A library bag's own grinder setting — the log form's grind fallback. */
    private fun beanGrinderSetting(beanId: String): String? =
        _ui.value.beans.firstOrNull { it.id == beanId }?.grinderSetting

    fun updateLogBrew(transform: (coffee.crema.ui.brewlog.BrewLogDraft) -> coffee.crema.ui.brewlog.BrewLogDraft) {
        _logBrew.update { it?.let(transform) }
    }

    /** A method chip: re-template the draft's seeds for [method]. */
    fun reseedLogBrew(method: String) {
        val history = _ui.value.history
        _logBrew.update { it?.let { d -> coffee.crema.ui.brewlog.BrewLogSeeds.reseed(d, history, method, ::beanGrinderSetting) } }
    }

    fun closeLogBrew() {
        _logBrew.value = null
    }

    /**
     * Validate + save the draft as a manual brew (bag debit included) and
     * close the form. Returns false — the draft stays open, flagged
     * `attempted` — when the method name or a debit-able dose is missing.
     */
    fun saveLogBrew(nowMs: Long = System.currentTimeMillis()): Boolean {
        val d = _logBrew.value ?: return false
        val flagged = d.copy(attempted = true)
        val bean = d.beanId?.let { id -> _ui.value.beans.firstOrNull { it.id == id } }
        // Never persist an empty method (blank → null → not saved); the
        // presets are already normalized ids.
        val storedMethod = coffee.crema.core.normalizeBrewMethod(if (d.isCustom) d.customMethod else d.method)
        if (storedMethod == null || d.doseMissing(bean != null)) {
            _logBrew.value = flagged
            return false
        }
        // A custom method's label rides on the row, so the brew keeps its
        // name if the method is later deleted (or lost on another device).
        val customLabel = if (coffee.crema.brew.isCustomMethodId(storedMethod)) {
            coffee.crema.brew.CustomMethods.find(storedMethod)?.label
        } else {
            null
        }
        val saved = library.addManualBrew(
            LibraryController.ManualBrewInput(
                method = storedMethod,
                methodLabel = customLabel,
                completedAtMs = nowMs - (d.minutesAgo * 60_000).toLong(),
                beanId = bean?.id,
                doseG = d.dose.toFloat().takeIf { it > 0f },
                waterG = if (!d.espresso) d.water.toFloat().takeIf { it > 0f } else null,
                yieldG = if (d.espresso) d.water.toFloat().takeIf { it > 0f } else null,
                grindSetting = d.grind.toFloat().takeIf { it > 0f },
                brewTempC = d.temp.toFloat().takeIf { it > 0f },
                durationMs = coffee.crema.ui.brewlog.parseBrewDurationMs(d.timeStr),
                rating = d.rating.takeIf { it > 0 },
                notes = d.notes.ifBlank { null },
                nextPlan = d.nextPlan.ifBlank { null },
                recipeName = d.recipeName,
                brewSeries = d.series,
            ),
        )
        // "Other…" free text → a one-time offer to keep it as a method (not
        // when it already names a preset — the normalized id would match).
        if (d.isCustom && coffee.crema.brew.BREW_METHOD_PRESETS.none { it.id == storedMethod }) {
            _ui.update {
                it.copy(saveMethodPrompt = coffee.crema.ui.brewlog.SaveMethodPrompt(saved.id, d.customMethod.trim(), storedMethod))
            }
        }
        // Remember the method so the next log opens on it (web parity).
        if (_ui.value.lastBrewLogMethod != storedMethod) {
            _ui.update { it.copy(lastBrewLogMethod = storedMethod) }
            persistPrefs()
            resolveGuidedSetup()
        }
        _logBrew.value = null
        // A guided session's summary card ends once its brew is saved.
        if (d.owner == coffee.crema.ui.brewlog.BrewLogOwner.SCALE && _ui.value.guidedBrew.phase == "done") {
            brewSessionClearSummary()
        }
        return true
    }

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

    /**
     * Push the stop-at-weight (SAW) / stop-at-volume (SAV) targets into the core
     * so a shot actually auto-stops. The core arms its `AutoStop` from these on
     * the next shot's first flowing phase and resolves the weight target as
     * `shotTarget.or(profileTarget)` — with neither pushed it can't arm and the
     * shot never stops on weight (the bug: Android never wired these, unlike the
     * web's applyProfileTargetWeight / applyShotTargetWeight / applyProfileVolume-
     * Limit). Profile yield + volume come from the active profile's recipe; the
     * shot target is the Quick-Controls dial override (null when unset, so the
     * profile recipe wins). Safe while disconnected — pure core state, no BLE.
     */
    private fun pushStopTargets(s: MainUiState = effectiveState(), desiredDelayMs: Long = 0) {
        // On a remote, a hub's custom profile this library lacks is the overlay.
        val active = s.activeProfile()
        val profileYield = active?.yieldOut?.takeIf { it.isFinite() && it > 0f }
        val volumeLimit = active?.maxTotalVolumeMl?.takeIf { it > 0 }?.toFloat()
        val shotYield = s.brewParams?.yieldOut?.toFloat()?.takeIf { it.isFinite() && it > 0f }
        runCatching {
            bridge.setProfileTargetWeight(profileYield)
            bridge.setProfileVolumeLimit(volumeLimit)
            bridge.setShotTargetWeight(shotYield)
        }.onFailure { appendLog("Push stop targets failed: ${it.message}") }
        // The active profile itself: the core latches its beverage type (a
        // cleaning run must never be recorded as a shot — review #40) and its
        // per-step weight exits (issue 11). Pushed alongside the stop targets —
        // on select, startup restore, every DE1 (re)connect and every shot
        // start — not only on upload, because activation can skip the upload:
        // after an app restart the DE1 still holds the profile and startShot
        // skips it on the fingerprint match.
        pushActiveProfile(
            activeCremaJson = active?.id?.let { library.profileJson(it) } ?: s.mirroredProfileJson,
            toWire = ::cremaProfileToWire,
            setActiveProfile = { bridge.setActiveProfile(it) },
            log = { appendLog(it) },
        )
        pushDesiredProfile(s, desiredDelayMs)
        refreshStopTargetProjection()
    }

    /**
     * Re-read the core's stop-target projection into the snapshot.
     *
     * The core pushes `StopTargetsArmed` on every change, but only from the
     * scale-reading and telemetry paths — so with nothing connected there is no
     * beat to ride. This covers that: called wherever the shell itself changes
     * an input, which is the one moment it knows the picture moved.
     */
    private fun refreshStopTargetProjection() {
        val json = runCatching { bridge.stopTargetsProjection() }.getOrNull() ?: return
        runCatching {
            val o = this.json.parseToJsonElement(json).jsonObject
            fun f(k: String) = o[k]?.jsonPrimitive?.floatOrNull
            _ui.update { it.copy(stopTargets = StopTargetsView(
                weightG = f("weight"),
                volumeMl = f("volume"),
                maxTimeS = f("maxTime"),
                armed = o["armed"]?.jsonPrimitive?.booleanOrNull ?: false,
                weightConfiguredG = f("weightConfigured"),
                weightBlocked = o["weightBlocked"]?.jsonPrimitive?.contentOrNull,
            )) }
        }.onFailure { appendLog("Stop-target projection parse failed: ${it.message}") }
    }

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
        _ui.update { it.copy(
            idleSleepMin = p.idleSleepMin ?: it.idleSleepMin,
            themeMode = p.themeMode,
            maxShotDurationS = p.maxShotDurationS,
            autoTare = p.autoTare,
            stopOnWeight = p.stopOnWeight,
            volumeStopWithScale = p.volumeStopWithScale,
            requireScale = p.requireScale,
            steamEco = p.steamEco,
            steamTwoTap = p.steamTwoTap,
            fanThresholdC = p.fanThresholdC,
            usbChargingMode = p.usbChargingMode,
            preFlush = p.preFlush,
            steamPurge = p.steamPurge,
            chartChannels = p.chartChannels,
            keepScreenOnBrew = p.keepScreenOnBrew,
            brewCueSound = p.brewCueSound,
            brewCueHaptics = p.brewCueHaptics,
            grinderModel = p.grinderModel,
            baristaName = p.baristaName,
            showDebugPanel = p.showDebugPanel,
            defaultDoseG = p.defaultDoseG,
            defaultRatio = p.defaultRatio,
            defaultBrewTempC = p.defaultBrewTempC,
            defaultPreinfuseS = p.defaultPreinfuseS,
            weightUnit = p.weightUnit,
            tempUnit = p.tempUnit,
            pressureUnit = p.pressureUnit,
            volumeUnit = p.volumeUnit,
            waterLevelUnit = p.waterLevelUnit,
            waterWarnMl = p.waterWarnMl,
            waterRefillPointMm = p.waterRefillPointMm,
            qcSteamTimeS = p.qcSteamTimeS,
            qcSteamFlowMlS = p.qcSteamFlowMlS,
            qcSteamTempC = p.qcSteamTempC,
            qcHotWaterTempC = p.qcHotWaterTempC,
            qcHotWaterVolumeMl = p.qcHotWaterVolumeMl,
            qcFlushTimeS = p.qcFlushTimeS,
            qcFlushTempC = p.qcFlushTempC,
            qcGrind = p.qcGrind,
        ) }
        // On a remote the core keeps following the hub's values (§7.4).
        pushCoreInputs(effectiveState())
        // Re-send the connect sweep's settings with the restored prefs — machine
        // registers plus the Quick-Controls steam / hot-water / flush params —
        // so a restore takes effect without waiting for the next connect or QC
        // edit (its writes are a no-op when disconnected or off-primary). Not
        // the sweep itself: only the on-ready sweep settles the link for the
        // profile sync, and a restore while the DE1 is down must not.
        seedMachineSettings(onReady = false)
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
     * request itself fires from [applyEvent] on `ProfileUploadCompleted`.
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

    /** [startShot]'s body on the device holding the machine (also a mirror's
     *  relayed start): refused, with the reason for whoever asked, when a shot
     *  can't start. */
    private fun applyStartShot(): CommandOutcome {
        // One start at a time (05 F2): a second chain's late Espresso request
        // would land mid-shot. Stop is how to end or cancel either.
        if (startSequence.active) return CommandOutcome.Refused("A shot is already starting")
        if (_ui.value.machineStateName == MachineState.Espresso) return CommandOutcome.Refused("A shot is already running")
        val cremaJson = _ui.value.activeProfileId?.let { library.profileJson(it) }
            ?: return CommandOutcome.Refused("Can\u2019t start shot \u2014 no profile selected")
        // Pre-shot scale gate (issue #29). Only when this shot actually
        // resolves a weight target (QC dial override, else the active
        // profile's own yield \u2014 the same precedence pushStopTargets uses): a
        // profile with no target never needed a scale, so it gets no noise
        // (the Decenza/reaprime "profile uses weight" gate).
        val s = _ui.value
        val activeProfile = s.profiles.firstOrNull { it.id == s.activeProfileId }
        // A profile saved before the DE1's 20-step limit can't upload: say
        // what to change instead of failing the upload (cohort review
        // 2026-10-28, #6 #6).
        activeProfile?.let { coffee.crema.profiles.StepCap.refusal(it.name, it.segments.size) }?.let {
            return CommandOutcome.Refused(it)
        }
        val weightTarget = s.brewParams?.yieldOut?.toFloat()?.takeIf { it.isFinite() && it > 0f }
            ?: activeProfile?.yieldOut?.takeIf { it.isFinite() && it > 0f }
        if (s.stopOnWeight && weightTarget != null && s.scaleState != ScaleBleManager.State.READY) {
            val remembered = connection.state.value.rememberedScaleAddress != null
            if (remembered) connection.kickScaleReconnect()
            if (s.requireScale) {
                // Opt-in hard block (de1app `start_espresso_only_if_scale_connected`
                // / reaprime `blockOnNoScale` \u2014 both also default off). The DE1
                // is left untouched; the reconnect kick above may make the next
                // attempt succeed.
                return CommandOutcome.Refused(
                    if (remembered) "Shot not started \u2014 reconnecting the scale\u2026 try again in a moment"
                    else "Shot not started \u2014 no scale connected, and \u201cRequire scale to start\u201d is on"
                )
            }
            // Default: NON-blocking cue \u2014 a modal between the user and a
            // tamped puck is workflow poison. The #15 fix arms SAW mid-shot,
            // so a quick reconnect can still save this very shot.
            notifyUser(
                if (remembered) "Scale not connected \u2014 reconnecting\u2026 stop-at-weight is off until it lands"
                else "No scale connected \u2014 stop-at-weight is off for this shot"
            )
        }
        val start = startSequence.begin()
            ?: return CommandOutcome.Refused(if (startSequence.active) "A shot is already starting" else "A shot is already running")
        // The heavy chain below — several bridge calls including the full
        // profile-frame build in Rust — runs on the core lane instead of
        // the Main tap handler (review #28).
        viewModelScope.launch(coreDispatcher) { startShotOnCoreLane(cremaJson, start) }
        return CommandOutcome.Accepted
    }

    private fun startShotOnCoreLane(cremaJson: String, start: ShotStartSequence.Start) {
        // Stopped before the lane got to it.
        if (!startSequence.isLive(start)) return
        // Make sure the core's stop-at-weight / volume targets are current before
        // the shot arms its AutoStop \u2014 covers a profile restored at startup that
        // never went through setActiveProfile (otherwise SAW silently never arms).
        pushStopTargets()
        // Re-assert the auto-tare / stop-on-weight flags from the saved setting on
        // every shot, for the same reason as pushStopTargets above: bridge.reset()
        // on a DE1 reconnect re-creates the core (CremaCore::new) back to its
        // defaults, and nothing else re-applies these flags — so without this a
        // reconnect could silently drop the user's choice before the next shot.
        // The UI state + prefs already hold the truth; the core just needs to
        // match it at shot time (direct bridge calls, like pushStopTargets).
        runCatching { bridge.setAutoTare(_ui.value.autoTare) }
            .onFailure { appendLog("Re-assert auto-tare failed: ${it.message}") }
        runCatching { bridge.setStopOnWeight(_ui.value.stopOnWeight) }
            .onFailure { appendLog("Re-assert stop-on-weight failed: ${it.message}") }
        runCatching { bridge.setVolumeStopWithScale(_ui.value.volumeStopWithScale) }
            .onFailure { appendLog("Re-assert volume-stop-with-scale failed: ${it.message}") }
        // The shot's profile — the library profile with the Quick-Controls
        // override, applied by the core — becomes the desired one. The core's
        // profile sync uploads it unless this connection already pushed it
        // (issue 11's skip, now the core's), and the start waits for it.
        val status = setDesiredOnLane(cremaJson, _ui.value.brewParams.overridesJson(json)) ?: run {
            startSequence.end(start)
            notifyUser("Can\u2019t start shot \u2014 profile conversion failed")
            return
        }
        if (status.holdsDesired()) {
            appendLog("Profile already on the DE1 \u2014 no upload")
            startSequence.proceed(start, _ui.value.preFlush)
            return
        }
        // Set after the call's own output was applied; the upload's acks and
        // its ProfileSyncHolds queue behind this lane task.
        pendingBrew = true
        pendingBrewStart = start
        pendingBrewFingerprint = status.desired
        // Backstop: if the DE1 does not confirm the profile in time, end the
        // start so the UI doesn't hang in "uploading" (the core keeps retrying
        // on its own). On the core lane so pendingBrew stays single-threaded.
        viewModelScope.launch(coreDispatcher) {
            delay(PROFILE_UPLOAD_TIMEOUT_MS)
            if (pendingBrew && pendingBrewStart === start) {
                pendingBrew = false
                pendingBrewStart = null
                pendingBrewFingerprint = null
                val live = startSequence.end(start)
                // A start that was stopped, or overtaken by a group-head
                // shot, needs no toast (mid-shot it would be wrong).
                if (live) notifyUser("Profile upload timed out \u2014 shot not started")
                else appendLog("Profile upload timed out after its start was cancelled")
            }
        }
    }

    /**
     * On the core lane: make the library [cremaJson] (null = no active
     * profile) with the Quick-Controls overrides [qcJson] the core's desired
     * profile (`setDesiredProfile`, this shell's only way to put a profile on
     * the DE1) and apply the output NOW — an upload the core's profile sync
     * started. Returns the sync's status, or null when the core can't read the
     * profile.
     */
    private fun setDesiredOnLane(cremaJson: String?, qcJson: String?): ProfileSyncStatus? {
        val sync = runCatching {
            json.decodeFromString(
                ProfileSyncOutput.serializer(),
                bridge.setDesiredProfile(cremaJson, qcJson, SystemClock.elapsedRealtime().toULong()),
            )
        }.getOrElse {
            appendLog("Setting the desired profile failed: ${it.message}")
            return null
        }
        applyCoreOutput(sync.output)
        if (sync.output.events.any { it is Event.DecodeError }) return null
        return sync.status
    }

    /**
     * The profile on screen — the active profile and its Quick-Controls
     * override — as the core's desired profile. Pushed with the stop targets
     * (select, override change, startup restore, every DE1 connect, every shot
     * start): the core's profile sync decides when to upload. A remote pushes
     * nothing: its core is a read-only mirror that never uploads (the hub holds
     * the machine), and it pushes again once it stops being a remote.
     */
    private fun pushDesiredProfile(s: MainUiState = effectiveState(), delayMs: Long = 0) {
        if (_ui.value.mirror != null) return
        val cremaJson = s.activeProfile()?.id?.let { library.profileJson(it) } ?: s.mirroredProfileJson
        val qcJson = s.brewParams.overridesJson(json)
        // The latest push wins: a dial change waits [delayMs] for the dial to
        // rest (de1app's 0.5 s), and any later push replaces a pending one.
        desiredPushJob?.cancel()
        desiredPushJob = viewModelScope.launch(if (delayMs > 0) dialDebounceContext else coreDispatcher) {
            if (delayMs > 0) delay(delayMs)
            kotlinx.coroutines.withContext(coreDispatcher) { setDesiredOnLane(cremaJson, qcJson) }
        }
    }

    /** The pending [pushDesiredProfile] (a dial change waiting to settle). */
    private var desiredPushJob: Job? = null

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
            routeWrite("USB charger on") { bridge.setUsbChargerOn(true) }
        }
        sweepRunner.onDropped()
        idleSleep.link(false)
        connection.disconnect()
    }

    /** A DE1 disconnect closed the session: stop the in-flight connect-time
     *  register sweep, drop the pending gated-start, and clear the live
     *  telemetry + machine identity so the Brew/Settings surfaces show "—"
     *  for the next connection rather than freezing on the last machine's
     *  values. Wired as [ConnectionController]'s onDe1SessionClosed. */
    private fun clearDe1SessionUi() {
        machineInfoJob?.cancel() // stop any in-flight connect-time register sweep
        // A start never resumes on a later connection (Decenza cancels its
        // deferred start on disconnect too).
        cancelStartSequence("disconnected")
        onCoreLane {
            pendingBrew = false
            pendingBrewStart = null
            pendingBrewFingerprint = null
        }
        _ui.update { it.copy(
            machineState = null,
            machineStateName = null,
            machineSubstate = null,
            shotPhase = null,
            telemetry = null,
            // Drop the live telemetry so the Brew cards clear to "—" for the
            // next connection rather than freezing on the last machine's values.
            pressure = null,
            flow = null,
            headTemp = null,
            mixTemp = null,
            steamTemp = null,
            dispensedVolume = null,
            resistance = null,
            resistanceWeight = null,
            shotElapsedMs = 0,
            shotFrame = 0,
            shotInProgress = false,
            shotTelemetry = emptyList(),
            waterLevelMm = null,
            // Drop the machine identity / register readouts so the Settings
            // machine rows clear to "—" for the next connection rather than
            // freezing on the last machine's values. The firmware label and
            // line frequency clear too (line freq is re-read on next connect).
            de1Firmware = null,
            de1MachineInfo = emptyMap(),
            serialStolen = false,
            lineFreqHz = null,
            lineFreqOverride = 0f,
            profileUploading = false,
            profileUploadProgress = null,
        ) }
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

    /** The visible half of a config command on the device that owns the
     *  config: update the UI state now; [commitConfig] does the rest. A remote
     *  shows its relayed edits in its mirror slice instead ([showPending]). */
    private fun showConfig(cmd: MachineCommand.Config) {
        _ui.update { cmd.applyTo(it) }
        // The brew override is stored normalized by the core (this device holds
        // the machine: its own dial and a remote's relayed one alike) — brew
        // temp clamped into the active profile's range, values back at the
        // profile's own dropped, nothing left = no override (Decenza model).
        if (cmd is MachineCommand.AdjustBrew) {
            val activeJson = _ui.value.activeProfileId?.let { library.profileJson(it) }
            _ui.update {
                it.copy(
                    brewParams = normalizedBrewParams(
                        it.brewParams, activeJson, json, ::normalizeBrewOverrides, ::brewTempRange,
                    ),
                )
            }
        }
    }

    /** The brew-temperature dial for the profile on screen (the core's range,
     *  anchor and pre-infusion seed); `null` with no active profile. */
    fun brewDial(): BrewDial? {
        val s = effectiveState()
        // On a remote the hub's copy of its custom profile wins over this
        // library's (possibly out-of-date) one.
        val cremaJson = s.mirroredProfileJson?.takeIf { _ui.value.mirror != null }
            ?: s.activeProfileId?.let { library.profileJson(it) } ?: s.mirroredProfileJson
        return brewDialOf(cremaJson, json, ::brewTempRange, ::preinfusionDial)
    }

    /** The rest of a config command on the device that owns the config:
     *  persist it, write the machine, push it to mirrors. */
    private fun commitConfig(cmd: MachineCommand.Config) {
        when (cmd) {
            is MachineCommand.SetActiveProfile -> {
                persistPrefs()
                pushStopTargets() // new profile's recipe yield/volume become the stop targets
            }
            is MachineCommand.SetActiveBean -> {
                library.persistLibrary()
                proxy.pushConfig() // bean activation persists via the library, not prefs
                // Linked-profile auto-load (web activateBean parity). Only user acts
                // reach this — boot/library restore writes activeBeanId directly —
                // so firing here matches the "explicit activation only" rule.
                if (cmd.id != null) library.maybeLoadLinkedProfile(cmd.id)
            }
            is MachineCommand.SetStopOnWeight -> {
                runCatching { bridge.setStopOnWeight(cmd.on) }.onFailure {
                    appendLog("Set stop-on-weight failed: ${it.message}")
                }
                persistPrefs()
            }
            is MachineCommand.SetAutoTare -> {
                runCatching { bridge.setAutoTare(cmd.on) }.onFailure {
                    appendLog("Set auto-tare failed: ${it.message}")
                }
                persistPrefs()
            }
            is MachineCommand.SetQc -> {
                persistPrefs()
                when (cmd.field) {
                    // One read-modify-write packet carries these four.
                    QcField.STEAM_TIME, QcField.STEAM_TEMP, QcField.HOT_WATER_TEMP, QcField.HOT_WATER_VOLUME ->
                        applySteamHotWater()
                    QcField.STEAM_FLOW -> routeWrite("Set steam flow") { bridge.setSteamFlow(cmd.value) }
                    QcField.FLUSH_TIME -> routeWrite("Set flush time") { bridge.setFlushTimeout((cmd.value * 1000f).toUInt()) }
                    QcField.FLUSH_TEMP -> routeWrite("Set flush temp") { bridge.setFlushTemp(cmd.value) }
                    // Grind never reaches the machine: it's recorded onto the next shot.
                    QcField.GRIND -> Unit
                }
            }
            is MachineCommand.AdjustBrew -> {
                // The new yield override becomes the live stop-at-weight target
                // now; the dialled profile goes to the DE1 once the dial rests.
                pushStopTargets(desiredDelayMs = DIAL_PUSH_DEBOUNCE_MS)
                proxy.pushConfig() // mirrors track the primary's live override (issue 06)
            }
            MachineCommand.ResetBrew -> {
                pushStopTargets() // drop the per-shot override; the profile recipe target takes over
                proxy.pushConfig() // mirrors drop their override too (issue 06)
            }
        }
    }

    /** A one-line roaster · name · freshness summary of the active bean for the
     *  config snapshot (issue 05) so a mirror can render the bean chip even when
     *  the bean isn't in its own library. Mirrors `beanLine` on the Brew screen. */
    private fun activeBeanSummaryLine(): String? {
        val ui = _ui.value
        val bean = ui.beans.firstOrNull { it.id == ui.activeBeanId } ?: return null
        val roaster = ui.roasters.firstOrNull { it.id == bean.roasterId }?.name
        val days = beanDaysOffRoast(bean)
        return listOfNotNull(
            roaster,
            bean.name,
            when {
                bean.isFrozen -> "Frozen"
                days != null -> "${days}d off roast"
                else -> null
            },
        ).joinToString(" · ").ifBlank { null }
    }

    /** Build the primary's session-config snapshot JSON for a mirror (see
     *  [ConfigSnapshot]) — sourced from the live [MainUiState] so it reflects any
     *  unsaved in-session change. Fed to the relay as its config source (see [ProxyController]). */
    private fun configSnapshotJson(): String = runCatching {
        // Read and numbered in one step, on Main (§6.1): a remote drops a
        // snapshot at or below the last rev it applied.
        val ui = _ui.value
        val rev = configRev++
        json.encodeToString(
            ConfigSnapshot.serializer(),
            ConfigSnapshot(
                activeProfileId = ui.activeProfileId,
                // Custom profiles only (issue 05): a mirror already has every
                // built-in by id, so we don't ship those — keeps the frame lean
                // while still covering the profile a secondary couldn't otherwise show.
                activeProfileJson = ui.activeProfileId
                    ?.takeIf { !library.isBuiltinProfile(it) }
                    ?.let { library.profileJson(it) },
                activeBeanId = ui.activeBeanId,
                activeBeanSummary = activeBeanSummaryLine(),
                stopOnWeight = ui.stopOnWeight,
                autoTare = ui.autoTare,
                maxShotDurationS = ui.maxShotDurationS,
                grinderModel = ui.grinderModel,
                qcSteamTimeS = ui.qcSteamTimeS,
                qcSteamFlowMlS = ui.qcSteamFlowMlS,
                qcSteamTempC = ui.qcSteamTempC,
                qcHotWaterTempC = ui.qcHotWaterTempC,
                qcHotWaterVolumeMl = ui.qcHotWaterVolumeMl,
                qcFlushTimeS = ui.qcFlushTimeS,
                qcFlushTempC = ui.qcFlushTempC,
                qcGrind = ui.qcGrind,
                // Live brew override so a mirror's targets track the dial (issue 06).
                brewDoseG = ui.brewParams?.dose,
                brewYieldG = ui.brewParams?.yieldOut,
                brewTempC = ui.brewParams?.brewTemp,
                brewPreinfuseS = ui.brewParams?.preinf,
                primaryName = deviceLabel(),
                authority = "primary",
                rev = rev,
                steamEco = ui.steamEco,
                steamTwoTap = ui.steamTwoTap,
                fanThresholdC = ui.fanThresholdC,
                waterRefillPointMm = ui.refillPointMm(),
                heaterVoltage = ui.de1MachineInfo[MmrRegister.HeaterVoltage]?.toInt(),
                flowMultiplier = ui.de1MachineInfo[MmrRegister.CalibrationFlowMultiplier]?.let { it.toInt() / 1000f },
                lineFrequencyHz = ui.lineFreqHz,
                lineFrequencyOverride = ui.lineFreqOverride,
                cupWarmerTempC = ui.de1MachineInfo[MmrRegister.CupWarmerTemp]?.toInt(),
                volumeStopWithScale = ui.volumeStopWithScale,
                requireScale = ui.requireScale,
                preFlush = ui.preFlush,
                steamPurge = ui.steamPurge,
                idleSleepMin = ui.idleSleepMin,
            ),
        )
    }.getOrDefault("")

    /** The next config snapshot's `rev`: a plain counter from 0 (Main only). */
    private var configRev = 0L

    /**
     * Apply a hub's pushed [ConfigSnapshot] on this remote: it goes into the
     * mirror slice only, never into this device's own values, so nothing a
     * hub sends is ever persisted here (spec §7.2, I1). Dropped when there is
     * no slice (this device isn't a remote) or the slice is frozen. The
     * custom-profile overlay (issue 05) shows the hub's profile even when this
     * device never saved it.
     */
    private fun applyRemoteConfig(jsonStr: String) {
        val mirror = _ui.value.mirror
        if (mirror == null || mirror.frozen) {
            appendLog("Config snapshot dropped: ${if (mirror == null) "not a remote" else "leaving the remote role"}")
            return
        }
        val cfg = runCatching { json.decodeFromString(ConfigSnapshot.serializer(), jsonStr) }.getOrElse {
            appendLog("Config snapshot parse failed: ${it.message}")
            return
        }
        if (mirror.isStale(cfg.rev)) {
            appendLog("Config snapshot ${cfg.rev} dropped: ${mirror.lastRev} already applied")
            return
        }
        val overlay = cfg.activeProfileJson?.let { raw ->
            runCatching { json.decodeFromString(CremaProfile.serializer(), raw) }.getOrElse {
                appendLog("Mirror profile overlay parse failed: ${it.message}")
                null
            }
        }
        _ui.update { s ->
            val m = s.mirror?.takeUnless { it.frozen || it.isStale(cfg.rev) } ?: return@update s
            s.copy(mirror = m.applied(cfg, overlay, cfg.activeProfileJson))
        }
        // The remote's core follows the hub's values (§7.4).
        pushCoreInputs(effectiveState())
        appendLog("Config from the hub applied (profile ${cfg.activeProfileId})")
    }

    /** Test seam: feed a hub's config frame to this device, whatever its role
     *  (AC-20: a normal device or a hub drops it). */
    @androidx.annotation.VisibleForTesting
    internal fun deliverConfigForTest(json: String) = applyRemoteConfig(json)

    /**
     * A role is starting ([ProxyController], before its transport is built):
     * the mirror slice exists exactly while this device is a remote (I6). A
     * re-find (a remote dialing its hub at a new address) keeps the slice,
     * marked reconnecting; any other start of the remote role gets an empty
     * one, and every other role drops it.
     */
    private fun onRoleStarting(role: String, refind: Boolean) {
        val wasRemote = _ui.value.mirror != null
        // Any switch cancels an open T6 confirmation (a refusal, a re-find):
        // nothing is adopted (spec §7.5 step 3). The dialog goes, the slice
        // unfreezes and the re-find is released, so the user can leave again.
        val cancelled = pendingLeave != null || _ui.value.mirror?.frozen == true
        if (cancelled) {
            pendingLeave = null
            appendLog("Leaving the hub cancelled: another mode switch ran")
        }
        _ui.update { s ->
            when {
                role != "secondary" -> s.copy(mirror = null, adoptPrompt = null)
                refind && s.mirror != null -> s.copy(mirror = s.mirror.reconnectingNow().copy(frozen = false), adoptPrompt = null)
                else -> s.copy(mirror = MirroredSettings(hubName = s.mirroringPrimaryName), adoptPrompt = null)
            }
        }
        if (cancelled) {
            // After this start returns: releasing the hold re-evaluates the re-find.
            viewModelScope.launch { proxy.holdRefind(false) }
            // A refusal explains itself; still a remote, say why nothing happened.
            if (role == "secondary") notifyUser("Couldn't leave ${_ui.value.remoteHubLabel()} — try again")
        }
        // Leaving the remote role: the core goes back to this device's own values.
        if (wasRemote && role != "secondary") pushCoreInputs(effectiveState())
    }

    /**
     * The core inputs that follow settings, from [state] (spec §7.4): the
     * stop-on-weight, auto-tare, max-duration and volume-stop flags, the stop
     * targets, the idle-sleep timer and the weight unit (the scale LCD and
     * set-grams writes follow it). Called with [effectiveState]: after
     * each applied snapshot on a remote, at the end of every own-state writer
     * ([loadPrefs], [applyRestoredSettings], [resetPreferences], [eraseAll])
     * in every role, and on leaving the remote role.
     */
    private fun pushCoreInputs(state: MainUiState) {
        // Every settings-driven core input in one call (auto-tare,
        // stop-on-weight, volume stop, max shot, weight unit, low-water
        // threshold) — the core's apply_common_settings, as web does.
        runCatching { bridge.applyCommonSettings(json.encodeToString(CommonSettings.serializer(), state.commonSettings())) }
            .onFailure { appendLog("Apply settings to the core failed: ${it.message}") }
        pushStopTargets(state)
        idleSleep.configure(state.idleSleepMin)
    }

    /**
     * Show a relayed config edit on this remote until the hub answers (spec
     * §7.3): it goes into the slice as pending, never into own values. The
     * returned echo settles it with the hub's answer.
     */
    private fun showPending(cmd: MachineCommand.Config): ConfigEcho {
        _ui.value.mirror?.takeIf { it.frozen }?.let { return ConfigEcho.refused("Leaving ${_ui.value.remoteHubLabel()}") }
        var generation = 0L
        _ui.update { s ->
            val m = s.mirror?.takeUnless { it.frozen } ?: return@update s
            val (next, gen) = m.withPending(cmd)
            generation = gen
            s.copy(mirror = next)
        }
        if (generation == 0L) return ConfigEcho.NONE
        val key = cmd.setting()
        return ConfigEcho { outcome ->
            _ui.update { s ->
                val m = s.mirror?.takeUnless { it.frozen } ?: return@update s
                s.copy(mirror = m.settled(key, generation, outcome))
            }
        }
    }

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

    /** A user's machine request (a control, or one relayed from a peer). It
     *  cancels a pending shot start first (05 F2): a Stop, or another mode,
     *  during the guard or the pre-flush must not end in Espresso. */
    private fun applyMachineState(state: MachineRequest) {
        if (state == MachineRequest.SLEEP) markAppSleepRequest()
        cancelStartSequence(state.name)
        writeMachineState(state)
    }

    /** A sleep the app asked for is deliberate, not idle: mark it so the
     *  Sleep state report that follows doesn't raise the screensaver (see
     *  [appAskedForSleep]). Both ends of a mirrored session (the secondary
     *  that tapped, the primary that writes) mark it. */
    private fun markAppSleepRequest() {
        appSleepRequestedAtMs = SystemClock.elapsedRealtime()
    }

    /** Drop the pending shot start, if any. Its upload runs on, and the
     *  upload hand-off stays armed so the backstop still ends a stuck one;
     *  the hand-off's proceed is a no-op for a cancelled start. */
    private fun cancelStartSequence(why: String) {
        if (startSequence.cancel() != null) appendLog("Shot start cancelled ($why)")
    }

    /** The machine write behind [requestMachineState], also used by the shot
     *  start's own steps and the auto-purge, which must not cancel the start.
     *  Never relayed: an automated write on a mirror is not a user's intent,
     *  and the primary would run it as one (a relayed Flush cancels its
     *  pending start). A mirror's read-only core drops the write. */
    private fun writeMachineState(state: MachineRequest) {
        val raw = runCatching { bridge.requestMachineState(state) }.getOrElse {
            appendLog("requestMachineState failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /**
     * Sleep-on-quit's write. NOT [sleep]: that routes through the core lane
     * and [executeCommand], both on [viewModelScope], which is already
     * cancelled when [onCleared] fires the quit. The core output is computed
     * synchronously here and its DE1 writes go out on [ProcessScope], bounded
     * by [QUIT_WRITE_BUDGET_MS]. The result is true only when every write
     * completed on a live link, so [QuitSleep]'s latch re-arms otherwise.
     * The output's events are dropped: the UI they would update is going away.
     */
    private fun launchQuitSleepWrite(): Deferred<Boolean> {
        appSleepRequestedAtMs = SystemClock.elapsedRealtime()
        val writes = runCatching {
            json.decodeFromString(CoreOutput.serializer(), bridge.requestMachineState(MachineRequest.SLEEP))
                .commands.filterIsInstance<Command.WriteCharacteristic>()
        }.getOrElse {
            Log.w("QuitSleep", "sleep request failed", it)
            emptyList()
        }
        if (ble.connectedAddress == null) return CompletableDeferred(false)
        return ProcessScope.main.async {
            sendQuitWrites(
                writes.map { cmd ->
                    suspend {
                        ble.writeCharacteristic(cmd.content.target, cmd.content.data.map { it.toByte() }.toByteArray())
                        Unit
                    }
                },
                QUIT_WRITE_BUDGET_MS,
            ).also { sent -> Log.i("QuitSleep", if (sent) "sleep write sent" else "sleep write did not go out") }
        }
    }

    /** Wake a sleeping DE1 (the request also stops anything running, so a
     *  mirror sends it only while it shows the machine asleep). */
    fun wake() = machine.execute(MachineCommand.Wake)

    /** Put the DE1 to sleep. Marked here too, for a mirror's own screensaver. */
    fun sleep() {
        markAppSleepRequest()
        machine.execute(MachineCommand.Sleep)
    }

    /** The debug panel's raw Espresso request, skipping the start sequence.
     *  Not available while mirroring. */
    fun startEspresso() = machine.execute(MachineCommand.RawEspresso)

    /** Stop a running shot (return to idle). */
    fun stopShot() = machine.execute(MachineCommand.Stop)

    /**
     * Read the DE1's identity + service registers once a connection is READY.
     * Each [CremaBridge.readMmr] emits a `Command.WriteCharacteristic` read
     * request (the same shared command path the scale/state writes use); the
     * DE1 answers later on its MMR / version notify characteristics, surfacing
     * as `Event.MmrValue` / `Event.Firmware` folded into [MainUiState].
     *
     * Sequencing matters on real hardware: [De1BleManager] flips to READY the
     * moment the notification flows are *launched* (startObserving → launchIn),
     * not when their CCCD writes land — so firing all nine reads the instant we
     * see READY races the subscriptions and the DE1's notify replies get
     * dropped, stranding firmware / heater-voltage on "—". So we mirror the web
     * connect program: let the subscriptions settle, issue the reads serially
     * with a small gap (web uses concurrency:1), and re-read until the
     * user-visible identity (firmware + heater voltage) has answered or the
     * attempt budget is spent. Reads are idempotent — a re-request re-answers.
     *
     * Also folds the two pure (no-BLE) core reads — the firmware build report
     * and the resolved line frequency — straight into the snapshot. Safe to
     * call while disconnected: with no BLE link the read requests write nothing
     * and the pure reads still return; the UI just shows "—" until replies land.
     */
    /** Re-seed the machine registers Crema owns a preference for. The DE1 boots
     *  with its own firmware values and de1app rewrote these each connect, so
     *  without this the machine silently runs the firmware defaults (the
     *  constant-fan report, geota/crema#31). Safe to call any time — the writes
     *  route through [routeWrite], a no-op when disconnected. */
    private fun seedMachineSettings(onReady: Boolean = true) {
        // One core-owned list (`CremaCore::connect_sweep`, web runs the same):
        // USB charger (Tablet charging setting), the user-presence feature flag
        // (FeatureFlags bit 0 — UserPresent pokes, sent on real activity by the
        // idle-sleep timer, only count with it set), fan threshold, de1app's
        // heater tweaks (phase-1 / phase-2 flow, hot-water idle temp, espresso
        // warm-up timeout, two-tap steam stop), the machine's own refill point
        // (geota/crema#47), the active profile's tank-temperature threshold,
        // the Quick-Controls steam / hot-water packet, steam flow, flush
        // time / temp, and steam eco last. de1app and Decenza re-send these on
        // every connect; the DE1 forgets them across power cycles.
        val ui = _ui.value
        val tankTempC = ui.profiles.firstOrNull { it.id == ui.activeProfileId }?.tankTemperatureC ?: 0f
        val settings = ui.connectSweepSettings(tankTempC, batteryPercent())
        routeWrite("Connect sweep") {
            // The notification clock (elapsedRealtime), like every core
            // timestamp: wall-clock ms pushed the core's clock decades ahead
            // and every watchdog compared against the wrong epoch.
            val settingsJson = json.encodeToString(ConnectSweepSettings.serializer(), settings)
            val now = SystemClock.elapsedRealtime().toULong()
            // Only the link's ready transition settles the profile sync (its
            // first sweep uploads the desired profile).
            if (onReady) bridge.connectSweep(settingsJson, now) else bridge.reapplyMachineSettings(settingsJson, now)
        }
    }

    /** The once-a-minute USB-charger check (Decenza `BatteryManager`) — see [ConnectSweepRunner]. */
    private fun usbChargerTick() {
        if (firmwareHolds()) return // no charger writes into a flash (audit H2)
        routeWrite("USB charger check") {
            bridge.usbChargerTick(_ui.value.usbChargingMode, batteryPercent()?.toUByte())
        }
    }

    /** The tablet battery, %, or null when the platform won't say. */
    private fun batteryPercent(): Int? = runCatching {
        val bm = getApplication<Application>().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }.getOrNull()

    /** Smart-charging mode for the DE1's USB port. Persisted; the next minute's check applies it. */
    fun setUsbChargingMode(mode: String) {
        _ui.update { it.copy(usbChargingMode = mode) }
        persistPrefs()
        usbChargerTick()
    }

    private fun readMachineInfo() {
        // The core's identity table (web reads the same one): firmware build
        // first, then board, model, GHC, refill kit, serial, heater voltage,
        // flush temp and flow multiplier. The passes and pacing below stay
        // this shell's policy.
        val registers = coffee.crema.core.connectIdentityRegisters()
        machineInfoJob?.cancel()
        machineInfoJob = viewModelScope.launch {
            // Pacing/retry budget — generous enough for a real DE1's notify
            // round-trips, cheap because it stops as soon as identity lands.
            val settleMs = 400L     // let the just-launched CCCD writes go active
            val readGapMs = 40L     // keep ~one outstanding GATT op at a time
            val retryGapMs = 1_500L // wait for replies before re-reading gaps
            val attempts = 3
            delay(settleMs)
            for (attempt in 0 until attempts) {
                for (reg in registers) {
                    val raw = runCatching { bridge.readMmr(reg) }.getOrElse {
                        appendLog("Read MMR $reg failed: ${it.message}")
                        null
                    }
                    if (raw != null) onCoreOutputJson(raw)
                    delay(readGapMs)
                }
                // Stop once the identity the UI actually shows has answered.
                if (_ui.value.de1Firmware != null &&
                    MmrRegister.HeaterVoltage in _ui.value.de1MachineInfo
                ) {
                    break
                }
                delay(retryGapMs)
            }
            // Pure reads — no BLE traffic. Resolved after the sweep so the line
            // frequency reflects whatever the sweep just brought in. The
            // firmware build report is logged; Settings classifies the live
            // build itself (FirmwareBuildCopy).
            runCatching { bridge.firmwareBuildReport() }.onSuccess { report ->
                appendLog("Firmware build: $report")
            }.onFailure { appendLog("Firmware build report failed: ${it.message}") }
            val freq = runCatching { bridge.lineFrequencyHz() }.getOrNull()
            _ui.update { it.copy(lineFreqHz = freq) }
            de1SetupDone = true
        }
    }

    // ── Settings: machine setters (Settings → Machine / Advanced / Service) ────
    // Thin VM wrappers so the Settings screen calls the VM (matching the
    // theme / scale-config pattern) rather than the bridge directly. Reads /
    // setters are safe while disconnected — the bridge returns a command JSON
    // and with no BLE link nothing is written.

    /**
     * Set the mains heater voltage. The core @Throws unless [volts] is 120 or
     * 230, so callers (the Settings segmented control behind a confirm) must
     * only pass those — guarded here too so an out-of-range value is a no-op.
     */
    fun setHeaterVoltage(volts: Int) {
        editOwnSetting(Setting.HEATER_VOLTAGE) {
            if (volts != 120 && volts != 230) {
                appendLog("Heater voltage $volts ignored (only 120 / 230 allowed)")
                return
            }
            val raw = runCatching { bridge.setHeaterVoltage(volts.toUByte()) }.getOrElse {
                appendLog("Set heater voltage failed: ${it.message}")
                return
            }
            onCoreOutputJson(raw)
            runCatching { bridge.readMmr(MmrReg.HEATER_VOLTAGE) }.getOrNull()?.let(::onCoreOutputJson)
        }
    }

    /**
     * Override the AC mains frequency. The core @Throws unless [hz] is 0.0
     * (auto-detect), 50.0, or 60.0 — guarded here so a bad value is a no-op.
     * Returns Unit (no BLE write); the resolved value is re-read into
     * [MainUiState.lineFreqHz].
     */
    fun setLineFrequency(hz: Float) {
        editOwnSetting(Setting.LINE_FREQUENCY) {
            if (hz != 0.0f && hz != 50.0f && hz != 60.0f) {
                appendLog("Line frequency $hz ignored (only 0 / 50 / 60 allowed)")
                return
            }
            runCatching { bridge.setLineFrequencyOverride(hz) }.onFailure {
                appendLog("Set line frequency failed: ${it.message}")
                return
            }
            val resolved = runCatching { bridge.lineFrequencyHz() }.getOrNull() ?: hz
            _ui.update { it.copy(lineFreqHz = resolved, lineFreqOverride = hz) }
            proxy.pushConfig() // remotes show the hub's line frequency (§6.1)
        }
    }

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

    /** Re-read the auto-detector's current resolved line frequency so the AC-
     *  frequency hint can show "Currently locked at X Hz" live while on Auto. */
    fun refreshLineFrequency() {
        val resolved = runCatching { bridge.lineFrequencyHz() }.getOrNull()
        _ui.update { it.copy(lineFreqHz = resolved) }
    }

    /**
     * Set the maximum shot duration, seconds (null = clear the cap). Returns
     * Unit (a sans-IO core setting); no event echo.
     */
    fun setMaxShotDuration(seconds: Float?) {
        editOwnSetting(Setting.MAX_SHOT_DURATION) {
            runCatching { bridge.setMaxShotDuration(seconds) }.onFailure {
                appendLog("Set max shot duration failed: ${it.message}")
            }
            if (seconds != null) {
                _ui.update { it.copy(maxShotDurationS = seconds) }
                persistPrefs() // also pushes the hub's snapshot (§6.1)
            }
            refreshStopTargetProjection()
        }
    }

    // ── Live mode switches (M2 — no restart) — see [ProxyController] ────────

    /** Become a standalone NORMAL device. On a remote this is Stop, which
     *  leaves the remote role like Connect directly. */
    fun switchToNormal() {
        if (proxy.state.value.role == "secondary") leaveRemote("normal") else proxy.switchToNormal()
    }

    /** Connect through the hub at [host]:[port]: the picker's "Connect through". */
    fun switchToSecondary(host: String, port: Int) = proxy.switchToSecondary(host, port)

    /** A remote whose hub is unreachable, or no longer holds the DE1, connects
     *  the machine itself. */
    fun connectDirectly() {
        if (proxy.state.value.role == "secondary") leaveRemote("normal")
    }

    /**
     * The Hub mode switch (Settings ▸ Multi-device). On makes this device the
     * hub; on a remote whose hub is gone it leaves the remote role first. Off
     * frees the machine: the hub steps down without reconnecting, so another
     * device can connect. Refused on a remote whose hub holds the DE1
     * ([hubModeBlockedReason]) and, by the controller, during a firmware flash.
     * A discovered hub's advertisement only warns ([discoveredHubWarning]):
     * the switch asks first, and [anyway] is the user's "Turn on anyway".
     */
    fun setHubMode(on: Boolean, anyway: Boolean = false) {
        val role = proxy.state.value.role
        if (!on) {
            if (role == "primary") proxy.setHubMode(false)
            return
        }
        _ui.value.hubModeBlockedReason()?.let { reason ->
            notifyUser(reason)
            return
        }
        if (!anyway) {
            _ui.value.discoveredHubWarning()?.let { warning ->
                notifyUser(warning)
                return
            }
        }
        when (role) {
            "primary" -> Unit
            "secondary" -> leaveRemote("primary")
            else -> proxy.setHubMode(true)
        }
    }

    /** A remote → standalone in progress, waiting on [adoptPrompt]'s answer. */
    private class PendingLeave(val next: String, val epoch: Long, val snapshot: ConfigSnapshot, val changes: Set<Setting>)

    private var pendingLeave: PendingLeave? = null

    /**
     * Leave the remote role for [next] (`"normal"` or `"primary"`): Stop,
     * Connect directly and Hub mode on with the hub gone all end here (T6,
     * spec §7.5).
     *
     * 1. Freeze the slice: it stays on screen, but frames, edits and the
     *    re-find stop. Only its last applied snapshot can be adopted.
     * 2. If that snapshot differs from this device's own adoptable values,
     *    ask ([adoptPrompt]); else go on with "keep mine".
     * 3. Commit under the mode lock, only if no other switch ran meanwhile
     *    (a refusal, a re-find): adopt and save, awaited, then drop the slice,
     *    re-push the core inputs and switch, so the first sweep writes the
     *    chosen values (I3).
     */
    private fun leaveRemote(next: String) {
        val m = _ui.value.mirror
        if (m == null) {
            if (next == "primary") proxy.setHubMode(true) else proxy.switchToNormal()
            return
        }
        if (m.frozen) return // already leaving
        val epoch = proxy.modeEpoch
        proxy.holdRefind(true)
        _ui.update { s -> s.copy(mirror = s.mirror?.copy(frozen = true)) }
        val snapshot = m.snapshot
        val changes = snapshot?.let { adoptableChanges(_ui.value, it) }.orEmpty()
        if (snapshot == null || changes.isEmpty()) {
            commitLeave(next, epoch, adopt = null, changes = emptySet())
            return
        }
        pendingLeave = PendingLeave(next, epoch, snapshot, changes)
        _ui.update { it.copy(adoptPrompt = AdoptPrompt(it.remoteHubLabel(), changes.size)) }
    }

    /** The T6 confirmation's answer: "Use <hub>'s" ([useHub]) or "Keep mine"
     *  (also what dismissing it means). */
    fun resolveAdoption(useHub: Boolean) {
        // The dialog always closes, even when a switch already cancelled the leave.
        _ui.update { it.copy(adoptPrompt = null) }
        val leave = pendingLeave ?: return
        pendingLeave = null
        commitLeave(leave.next, leave.epoch, if (useHub) leave.snapshot else null, leave.changes)
    }

    private fun commitLeave(next: String, epoch: Long, adopt: ConfigSnapshot?, changes: Set<Setting>) {
        val hub = _ui.value.remoteHubLabel()
        proxy.applyModeIfEpoch(
            next, epoch,
            before = {
                if (adopt != null) {
                    adoptHubSettings(adopt, changes)
                    persistPrefsNow()
                    library.persistLibraryNow()
                }
                _ui.update { it.copy(mirror = null) }
                pushCoreInputs(effectiveState())
            },
            onStale = {
                // Another switch ran first, or a firmware flash refused this
                // one (R2): this device may still be a remote. Unless a switch
                // already cancelled the leave (onRoleStarting), unfreeze and
                // say so. After a flash refusal nothing else will.
                val stillLeaving = _ui.value.mirror?.frozen == true
                proxy.holdRefind(false)
                _ui.update { s -> s.copy(mirror = s.mirror?.copy(frozen = false)) }
                if (stillLeaving && proxy.state.value.role == "secondary") notifyUser("Couldn't leave $hub — try again")
            },
        )
    }

    /** Take the hub's [changes] from [cfg] as this device's own (spec §7.5
     *  step 4): a custom profile into the library first, the bean only if
     *  this library has it. */
    private suspend fun adoptHubSettings(cfg: ConfigSnapshot, changes: Set<Setting>) {
        if (Setting.ACTIVE_PROFILE in changes) {
            val id = cfg.activeProfileId
            val json = _ui.value.mirror?.profileJson
            if (id != null && !library.hasProfile(id) && json != null) library.adoptCustomProfile(json)
        }
        val profileKnown = cfg.activeProfileId?.let { library.hasProfile(it) } ?: true
        val take = if (profileKnown) changes else changes - Setting.ACTIVE_PROFILE
        _ui.update { s -> adopt(s, cfg, take, hasBean = { id -> s.beans.any { it.id == id } }) }
        appendLog("Adopted ${take.size} settings from the hub")
    }

    /** Build a full AppPrefs from the current UI state so each setter persists
     *  without clobbering the other fields. */
    private fun currentPrefs() = AppPrefs(
        themeMode = _ui.value.themeMode,
        maxShotDurationS = _ui.value.maxShotDurationS,
        autoTare = _ui.value.autoTare,
        stopOnWeight = _ui.value.stopOnWeight,
        volumeStopWithScale = _ui.value.volumeStopWithScale,
        requireScale = _ui.value.requireScale,
        steamEco = _ui.value.steamEco,
        steamTwoTap = _ui.value.steamTwoTap,
        fanThresholdC = _ui.value.fanThresholdC,
        usbChargingMode = _ui.value.usbChargingMode,
        preFlush = _ui.value.preFlush,
        steamPurge = _ui.value.steamPurge,
        chartChannels = _ui.value.chartChannels,
        keepScreenOnBrew = _ui.value.keepScreenOnBrew,
        brewCueSound = _ui.value.brewCueSound,
        brewCueHaptics = _ui.value.brewCueHaptics,
        idleSleepMin = _ui.value.idleSleepMin,
        screensaverAfterMin = _ui.value.screensaverAfterMin,
        wakeMachineWithSaver = _ui.value.wakeMachineWithSaver,
        sleepOnQuit = _ui.value.sleepOnQuit,
        keepConnectedScreenOff = _ui.value.keepConnectedScreenOff,
        autoDriveBackup = _ui.value.autoDriveBackup,
        autoUpdateCheck = _ui.value.autoUpdateCheck,
        lastUpdateCheckAtMs = _ui.value.lastUpdateCheckAtMs,
        lastSeenLatestVersion = _ui.value.lastSeenLatestVersion,
        lastBrewLogMethod = _ui.value.lastBrewLogMethod,
        grinderModel = _ui.value.grinderModel,
        baristaName = _ui.value.baristaName,
        showDebugPanel = _ui.value.showDebugPanel,
        defaultDoseG = _ui.value.defaultDoseG,
        defaultRatio = _ui.value.defaultRatio,
        defaultBrewTempC = _ui.value.defaultBrewTempC,
        defaultPreinfuseS = _ui.value.defaultPreinfuseS,
        weightUnit = _ui.value.weightUnit,
        tempUnit = _ui.value.tempUnit,
        pressureUnit = _ui.value.pressureUnit,
        volumeUnit = _ui.value.volumeUnit,
        waterLevelUnit = _ui.value.waterLevelUnit,
        waterWarnMl = _ui.value.waterWarnMl,
        waterRefillPointMm = _ui.value.waterRefillPointMm,
        qcSteamTimeS = _ui.value.qcSteamTimeS,
        qcSteamFlowMlS = _ui.value.qcSteamFlowMlS,
        qcSteamTempC = _ui.value.qcSteamTempC,
        qcHotWaterTempC = _ui.value.qcHotWaterTempC,
        qcHotWaterVolumeMl = _ui.value.qcHotWaterVolumeMl,
        qcFlushTimeS = _ui.value.qcFlushTimeS,
        qcFlushTempC = _ui.value.qcFlushTempC,
        qcGrind = _ui.value.qcGrind,
        activeProfileId = _ui.value.activeProfileId,
        // The remembered addresses live on the connection controller — its
        // setters call persistPrefs AFTER their state update, so this read is
        // always fresh (the MainUiState copies are display mirrors).
        de1Address = connection.state.value.rememberedDe1Address,
        scaleAddress = connection.state.value.rememberedScaleAddress,
        scaleName = connection.state.value.rememberedScaleName,
        de1CompanionId = companionIdFor(connection.state.value.rememberedDe1Address),
        scaleCompanionId = companionIdFor(connection.state.value.rememberedScaleAddress),
        companionDeclined = _ui.value.companionDeclined,
        proxyRole = _ui.value.proxyRole,
        proxyPrimaryHost = _ui.value.proxyPrimaryHost,
        proxyPrimaryPort = _ui.value.proxyPrimaryPort,
        replayPrimary = _ui.value.replayPrimary,
    )

    /** [persistPrefs], awaited: the T6 commit saves the adopted values before
     *  the role switch starts (I3). Numbered like every save, so an earlier
     *  fire-and-forget one can never land after it. */
    private suspend fun persistPrefsNow() {
        // Like persistPrefs: never save a half-default AppPrefs before the load.
        if (!prefsLoaded) return
        val snapshot = currentPrefs()
        val generation = ++prefsGeneration
        settingsStore.save(snapshot, generation)
    }

    /** The last [persistPrefs] snapshot's number (Main only). */
    private var prefsGeneration = 0L

    /** Persist the current prefs snapshot (best-effort, off the main thread).
     *  No-op until the initial [loadPrefs] hydration completes. */
    private fun persistPrefs() {
        if (!prefsLoaded) return
        val snapshot = currentPrefs()
        // Numbered on Main as the snapshot is built: the store skips one that
        // lands after a newer one.
        val generation = ++prefsGeneration
        viewModelScope.launch { settingsStore.save(snapshot, generation) }
        // Multi-device: if we're hosting, push the updated session config to every
        // mirror so a secondary tracks our config mid-session (the attach-time
        // snapshot only covers a fresh join). No-op off-primary.
        proxy.pushConfig()
    }

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
            onCoreOutputJson(raw)
            runCatching { bridge.readMmr(MmrReg.CALIBRATION_FLOW_MULTIPLIER) }.getOrNull()?.let(::onCoreOutputJson)
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

    private fun applyCancelMaintenance() {
        val raw = runCatching { bridge.cancelMaintenance() }.getOrElse {
            appendLog("cancelMaintenance failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

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
            onCoreOutputJson(raw)
        }
    }

    // ── Quick-Controls steam / hot-water / flush (issue 14) ──────────────────
    // Persisted QC overrides that stick + take priority. On change we update
    // UiState, persist, and write to the machine. Steam temp/time + hot-water
    // temp/vol share one packet (cuuid_0B), which the core writes
    // read-modify-write: the group target is the effective profile's, and the
    // fields QC doesn't model (flags, timeouts, espresso volume) survive;
    // steam flow + flush temp/time are separate writes.

    /** Route a core-output-producing machine write through the shared command
     *  path, logging (not throwing) on failure. */
    private fun routeWrite(label: String, block: () -> String) {
        val raw = runCatching { block() }.getOrElse {
            appendLog("$label failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /** Write the four QC steam / hot-water targets; the core does the
     *  read-modify-write of the combined packet. */
    private fun applySteamHotWater() {
        val ui = _ui.value
        val targets = SteamHotWaterTargets(
            steamTempC = ui.qcSteamTempC,
            steamTimeoutS = ui.qcSteamTimeS,
            hotWaterTempC = ui.qcHotWaterTempC,
            hotWaterVolumeMl = ui.qcHotWaterVolumeMl,
        )
        routeWrite("Set steam/hot-water") { bridge.setSteamHotwaterTargets(targets) }
    }

    /** Steam timeout, seconds (Quick Controls). Persisted; RMW write. */
    fun setQcSteamTime(seconds: Float) = machine.execute(MachineCommand.SetQc(QcField.STEAM_TIME, seconds))

    /** Steam flow rate, ml/s (Quick Controls). Persisted; standalone write. */
    fun setQcSteamFlow(mlPerS: Float) = machine.execute(MachineCommand.SetQc(QcField.STEAM_FLOW, mlPerS))

    /** Steam temperature, °C (Quick Controls). Persisted; RMW write. */
    fun setQcSteamTemp(tempC: Float) = machine.execute(MachineCommand.SetQc(QcField.STEAM_TEMP, tempC))

    /** Hot-water temperature, °C (Quick Controls). Persisted; RMW write. */
    fun setQcHotWaterTemp(tempC: Float) = machine.execute(MachineCommand.SetQc(QcField.HOT_WATER_TEMP, tempC))

    /** Hot-water volume, ml (Quick Controls). Persisted; RMW write. */
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
    fun toggleChartChannel(key: String, enabled: Boolean) {
        val next = _ui.value.chartChannels.toMutableSet().apply {
            if (enabled) add(key) else remove(key)
        }
        _ui.update { it.copy(chartChannels = next) }
        persistPrefs()
    }

    // ── Display units (issue 44) ──────────────────────────────────────────────
    // Display prefs: nothing is sent to the machine and no canonical value
    // changes — flipping a unit only re-renders existing readouts through
    // `Format.kt`. Persisted so the choice survives a restart. The weight unit
    // also goes to the core, which picks the scale's LCD unit from it.

    /**
     * Weight readout unit (`"g" | "oz"`). Persisted display pref. The core
     * gets it too, and a connected scale's display switches now rather than
     * on the next Idle entry or reconnect (as on web).
     */
    fun setWeightUnit(unit: String) {
        _ui.update { it.copy(weightUnit = unit) }
        persistPrefs()
        val wire = coreWeightUnit(unit)
        runCatching { bridge.setWeightUnitPref(wire) }.onFailure {
            appendLog("Set weight unit failed: ${it.message}")
            return
        }
        // Each call is capability-gated and fails for a scale without it (or
        // no scale): a no-op is intended.
        runCatching { bridge.enableScaleLcd(wire) }.onSuccess(::onCoreOutputJson)
        if (wire == WeightUnit.Grams.string) {
            runCatching { bridge.setScaleUnitGrams() }.onSuccess(::onCoreOutputJson)
        }
    }

    /** Temperature readout unit (`"C" | "F"`). Persisted display pref. */
    fun setTempUnit(unit: String) {
        _ui.update { it.copy(tempUnit = unit) }
        persistPrefs()
    }

    /** Pressure readout unit (`"bar" | "psi"`). Persisted display pref. */
    fun setPressureUnit(unit: String) {
        _ui.update { it.copy(pressureUnit = unit) }
        persistPrefs()
    }

    /** Volume readout unit (`"ml" | "floz"`). Persisted display pref. */
    fun setVolumeUnit(unit: String) {
        _ui.update { it.copy(volumeUnit = unit) }
        persistPrefs()
    }

    /** Water-tank readout style (`"ml" | "percent"`). Persisted display pref. */
    fun setWaterLevelUnit(unit: String) {
        _ui.update { it.copy(waterLevelUnit = unit) }
        persistPrefs()
    }

    /** Low-water warning threshold, canonical ml (0 = off) — #33 follow-up.
     *  Persisted; re-arms the once-per-dip warning so a raised threshold can
     *  fire on the very next level report. */
    fun setWaterWarnMl(ml: Float) {
        _ui.update { it.copy(waterWarnMl = ml) }
        persistPrefs()
        // The core's monitor re-arms on a threshold change.
        onCoreLane { runCatching { bridge.setWaterWarnMl(ml) } }
    }

    /** The machine's own refill threshold, raw sensor mm (geota/crema#47).
     *  Persisted AND pushed straight through, so the DE1 stops (or starts)
     *  demanding water at the new level without waiting for a reconnect. */
    fun setWaterRefillPointMm(mm: Float) {
        editOwnSetting(Setting.WATER_REFILL_POINT) {
            val clamped = mm.coerceIn(REFILL_POINT_MIN_MM, REFILL_POINT_MAX_MM)
            _ui.update { it.copy(waterRefillPointMm = clamped) }
            persistPrefs()
            routeWrite("Set refill point") { bridge.setRefillThreshold(clamped) }
        }
    }

    /** Daily automatic Google Drive backup toggle (issue #36). Turning it on
     *  runs a backup right away if one is due. */
    fun setAutoDriveBackup(on: Boolean) {
        _ui.update { it.copy(autoDriveBackup = on) }
        persistPrefs()
        if (on) drive.autoBackupIfDue(true)
    }

    /** Fan-on temperature threshold, °C (0..=60). Persisted + written to the
     *  machine immediately; re-seeded on every connect. */
    fun setFanThreshold(tempC: Float) {
        editOwnSetting(Setting.FAN_THRESHOLD) {
            _ui.update { it.copy(fanThresholdC = tempC) }
            persistPrefs()
            routeWrite("Set fan threshold") {
                bridge.setFanThreshold(tempC.roundToInt().coerceIn(0, 60).toUByte())
            }
        }
    }

    /** Opt-in "require scale to start" block (geota/crema#29). Persisted. */
    fun setRequireScale(on: Boolean) {
        editOwnSetting(Setting.REQUIRE_SCALE) {
            _ui.update { it.copy(requireScale = on) }
            persistPrefs()
        }
    }

    /** DE1 firmware two-tap steam stop. Persisted + written to the machine
     *  immediately; re-seeded on every connect. */
    fun setSteamTwoTap(enabled: Boolean) {
        editOwnSetting(Setting.STEAM_TWO_TAP) {
            _ui.update { it.copy(steamTwoTap = enabled) }
            persistPrefs()
            routeWrite("Set steam two-tap stop") {
                bridge.setSteamTwoTapStop(if (enabled) 1u else 0u)
            }
        }
    }

    /** Persist the pre-shot flush preference (consumed by a later shot-sequence pass). */
    fun setPreFlush(enabled: Boolean) {
        editOwnSetting(Setting.PRE_FLUSH) {
            _ui.update { it.copy(preFlush = enabled) }
            persistPrefs()
        }
    }

    /** Persist the post-steam purge preference (consumed by a later shot-sequence pass). */
    fun setSteamPurge(enabled: Boolean) {
        editOwnSetting(Setting.STEAM_PURGE) {
            _ui.update { it.copy(steamPurge = enabled) }
            persistPrefs()
        }
    }

    /** Keep the screen awake while a shot pulls (Settings → Display). Persisted. */
    fun setKeepScreenOnBrew(enabled: Boolean) {
        _ui.update { it.copy(keepScreenOnBrew = enabled) }
        persistPrefs()
    }

    /** Guided-brew cue sound (Settings → Display, the Brew setup bell). Persisted as an explicit choice. */
    fun setBrewCueSound(enabled: Boolean) {
        _ui.update { it.copy(brewCueSound = enabled) }
        persistPrefs()
    }

    /** Guided-brew cue vibration (Settings → Display). Persisted as an explicit choice. */
    fun setBrewCueHaptics(enabled: Boolean) {
        _ui.update { it.copy(brewCueHaptics = enabled) }
        persistPrefs()
    }

    /** Settings → Brew defaults: the dose / ratio / temp / pre-infusion a NEW
     *  profile seeds from ([startNewProfile] → brewDefaultsJson). Persisted. */
    fun setBrewDefaults(doseG: Float, ratio: Float, brewTempC: Float, preinfuseS: Float) {
        _ui.update { it.copy(
            defaultDoseG = doseG,
            defaultRatio = ratio,
            defaultBrewTempC = brewTempC,
            defaultPreinfuseS = preinfuseS,
        ) }
        persistPrefs()
    }

    // ── Bean library — see [LibraryController] ──────────────────────────────

    /** Set (or with `null`, clear) the active bean (the Brew bean block). Persisted. */
    fun setActiveBean(id: String?) = machine.execute(MachineCommand.SetActiveBean(id))

    // ── Maintenance (water accumulation) ────────────────────────────────────────
    //
    // The DE1 has no cumulative water counter; we integrate group flow over
    // telemetry into a running litre total (the de1app / web-shell approach) and
    // derive the filter / descale / clean readouts from it via the pure core FFI.
    // The live total lives in [maintenanceTotalLitres] (not the ui-state) so the
    // ~25–40 Hz accumulate doesn't thrash recomposition; it's flushed into the
    // persisted [MaintenanceState] (and the readout recomputed) only at load, on
    // shot / water-session completion, and after a mark action.

    /** Load the persisted maintenance state, seed the live total, compute the readout. */
    private suspend fun loadMaintenance() {
        val state = maintenanceStore.load()
        onCoreLane { maintenanceTotalLitres = state.totalLitres }
        _ui.update { it.copy(
            maintenance = state,
            maintenanceReadout = computeMaintenanceReadout(state),
        ) }
    }

    /**
     * Derive the filter / descale / clean readout from [state] via the pure core
     * FFI (`maintenanceReadout` — a MaintenanceState JSON in, a MaintenanceReadout
     * JSON out; @Throws on a bad input). The same derivation the web shell uses,
     * so both produce byte-identical readouts. Failures fall back to null.
     */
    private fun computeMaintenanceReadout(state: MaintenanceState): MaintenanceReadout? = runCatching {
        val stateJson = json.encodeToString(MaintenanceState.serializer(), state)
        val resultJson = maintenanceReadout(stateJson, System.currentTimeMillis())
        json.decodeFromString(MaintenanceReadout.serializer(), resultJson)
    }.getOrElse {
        appendLog("Maintenance readout failed: ${it.message}")
        null
    }

    /**
     * Flush the live integrated [maintenanceTotalLitres] into the persisted
     * [MaintenanceState], save, and recompute the readout. Called on shot /
     * water-session completion (the natural "a pour just finished" boundaries) —
     * NOT per telemetry sample.
     */
    private fun flushMaintenance() {
        val next = _ui.value.maintenance.copy(totalLitres = maintenanceTotalLitres)
        _ui.update { it.copy(
            maintenance = next,
            maintenanceReadout = computeMaintenanceReadout(next),
        ) }
        viewModelScope.launch { maintenanceStore.save(next) }
    }

    /**
     * Mark the water filter cleaned — rebaseline its litre counter to the current
     * total and stamp `filterAtMs` (the web store's `markFilterCleaned`). Persists
     * + recomputes the readout.
     */
    fun markFilterCleaned() = onCoreLane {
        val next = _ui.value.maintenance.copy(
            totalLitres = maintenanceTotalLitres,
            filterBaselineLitres = maintenanceTotalLitres,
            filterAtMs = System.currentTimeMillis(),
        )
        saveMaintenance(next)
    }

    /**
     * Mark a descale done — rebaseline the descale litre counter to the current
     * total and stamp `descaleAtMs` (the web store's `markDescaled`). Persists +
     * recomputes the readout.
     */
    fun markDescaled() = onCoreLane {
        val next = _ui.value.maintenance.copy(
            totalLitres = maintenanceTotalLitres,
            descaleBaselineLitres = maintenanceTotalLitres,
            descaleAtMs = System.currentTimeMillis(),
        )
        saveMaintenance(next)
    }

    /**
     * Mark a clean cycle done — reset its hour counter by stamping `cleanAtMs`
     * (the web store's `markCleaned`). Persists + recomputes the readout.
     */
    fun markCleaned() = onCoreLane {
        val next = _ui.value.maintenance.copy(
            totalLitres = maintenanceTotalLitres,
            cleanAtMs = System.currentTimeMillis(),
        )
        saveMaintenance(next)
    }

    /** Fold a mark action's new state into the ui-state, recompute, and persist. */
    private fun saveMaintenance(next: MaintenanceState) {
        _ui.update { it.copy(
            maintenance = next,
            maintenanceReadout = computeMaintenanceReadout(next),
        ) }
        viewModelScope.launch { maintenanceStore.save(next) }
    }

    // ── App settings ──────────────────────────────────────────────────────────

    /** Load persisted app preferences at startup. */
    private suspend fun loadPrefs() {
        val p = settingsStore.load()
        _ui.update { it.copy(
            themeMode = p.themeMode,
            maxShotDurationS = p.maxShotDurationS,
            autoTare = p.autoTare,
            stopOnWeight = p.stopOnWeight,
            volumeStopWithScale = p.volumeStopWithScale,
            requireScale = p.requireScale,
            steamEco = p.steamEco,
            steamTwoTap = p.steamTwoTap,
            fanThresholdC = p.fanThresholdC,
            usbChargingMode = p.usbChargingMode,
            preFlush = p.preFlush,
            steamPurge = p.steamPurge,
            chartChannels = p.chartChannels,
            keepScreenOnBrew = p.keepScreenOnBrew,
            brewCueSound = p.brewCueSound,
            brewCueHaptics = p.brewCueHaptics,
            idleSleepMin = p.idleSleepMin ?: DEFAULT_IDLE_SLEEP_MIN,
            screensaverAfterMin = p.screensaverAfterMin,
            wakeMachineWithSaver = p.wakeMachineWithSaver,
            sleepOnQuit = p.sleepOnQuit,
            // Resolve the nullable pref against the form-factor default (#65).
            keepConnectedScreenOff = p.keepConnectedScreenOff ?: isTablet,
            autoDriveBackup = p.autoDriveBackup,
            autoUpdateCheck = p.autoUpdateCheck,
            lastUpdateCheckAtMs = p.lastUpdateCheckAtMs,
            lastSeenLatestVersion = p.lastSeenLatestVersion,
            lastBrewLogMethod = p.lastBrewLogMethod,
            grinderModel = p.grinderModel,
            baristaName = p.baristaName,
            showDebugPanel = p.showDebugPanel,
            defaultDoseG = p.defaultDoseG,
            defaultRatio = p.defaultRatio,
            defaultBrewTempC = p.defaultBrewTempC,
            defaultPreinfuseS = p.defaultPreinfuseS,
            weightUnit = p.weightUnit,
            tempUnit = p.tempUnit,
            pressureUnit = p.pressureUnit,
            volumeUnit = p.volumeUnit,
            waterLevelUnit = p.waterLevelUnit,
            waterWarnMl = p.waterWarnMl,
            waterRefillPointMm = p.waterRefillPointMm,
            qcSteamTimeS = p.qcSteamTimeS,
            qcSteamFlowMlS = p.qcSteamFlowMlS,
            qcSteamTempC = p.qcSteamTempC,
            qcHotWaterTempC = p.qcHotWaterTempC,
            qcHotWaterVolumeMl = p.qcHotWaterVolumeMl,
            qcFlushTimeS = p.qcFlushTimeS,
            qcFlushTempC = p.qcFlushTempC,
            qcGrind = p.qcGrind,
            // Restore the last session's profile selection; refreshProfiles
            // self-heals if the id no longer resolves (e.g. deleted custom).
            activeProfileId = p.activeProfileId ?: it.activeProfileId,
            proxyRole = p.proxyRole,
            proxyPrimaryHost = p.proxyPrimaryHost,
            proxyPrimaryPort = p.proxyPrimaryPort,
            replayPrimary = p.replayPrimary,
            companionDeclined = p.companionDeclined,
        ) }
        // Prime the background keep-alive with the resolved setting (#65).
        bgConnection.setEnabled(p.keepConnectedScreenOff ?: isTablet)
        prefsLoaded = true
        prefsReady.complete(Unit)
        // The cap, behaviour toggles, stop targets and idle-sleep timer go
        // to the core now (it doesn't echo them back). On a remote that is
        // the hub's values: a snapshot that landed before the load finished
        // isn't clobbered by this device's own (§7.4).
        pushCoreInputs(effectiveState())
        // The Brew setup opens on the most recent method, now it's known.
        resolveGuidedSetup()
        // Hydrate the remembered addresses, arm each manager's reconnect loop,
        // and cold-start auto-connect to each remembered device.
        connection.hydrateRemembered(p.de1Address, p.scaleAddress, p.scaleName)
        // Companion associations: the system's list is the truth (a removal in
        // system settings wins over the stored id); restart presence observation.
        refreshCompanion()
    }

    // ── Companion-device presence (Android CDM) ──────────────────────────────

    /** The stored association id for a remembered address (see [coffee.crema.settings.COMPANION_ID_UNKNOWN]). */
    private fun companionIdFor(address: String?): Int? {
        if (address == null || !companion.isAssociated(address)) return null
        return companion.associationId(address) ?: coffee.crema.settings.COMPANION_ID_UNKNOWN
    }

    /** Re-read the associations, restart presence observation, mirror into the UI. */
    fun refreshCompanion() {
        val cs = connection.state.value
        val before = companion.associated.value
        runCatching { companion.refresh(listOf(cs.rememberedDe1Address, cs.rememberedScaleAddress)) }
        val associatedAny = companion.associated.value.isNotEmpty()
        _ui.update {
            it.copy(
                companionSupported = companion.isSupported,
                de1Companion = companion.isAssociated(cs.rememberedDe1Address),
                scaleCompanion = companion.isAssociated(cs.rememberedScaleAddress),
            )
        }
        // The CDM background-FGS-start exemption applies once an association exists.
        bgConnection.setBackgroundStartAllowed(associatedAny)
        if (companion.associated.value != before) persistPrefs()
    }

    /** Try the one-time "nearby" setup a few seconds after a DE1 connects. */
    private fun scheduleNearbySetup() {
        if (!companion.isSupported) return
        nearbySetupJob?.cancel()
        nearbySetupJob = viewModelScope.launch {
            kotlinx.coroutines.delay(NEARBY_SETUP_DELAY_MS)
            setUpNearby(explicit = false)
        }
    }

    /**
     * Part of "Keep connected in the background": set up Android's "nearby"
     * association for the remembered DE1 once, so the keep-alive service can
     * restart from the background (after a power cut) and Crema hears when the
     * DE1 is back in range. Runs when the setting is turned on ([explicit]) and
     * a few seconds after a DE1 connects while it's on, until it's set up or
     * declined (an explicit turn-on tries again after a decline).
     *
     * Android finds the DE1 by its advertisement, which a connected DE1
     * doesn't send — the old "Set up" button searched forever for that reason —
     * so a live link is paused while the system's confirm is up and resumed
     * after. Only on an idle machine, on the primary device, and never while a
     * firmware flash holds the DE1.
     */
    private fun setUpNearby(explicit: Boolean) {
        if (!companion.isSupported) return
        val s = _ui.value
        if (!s.keepConnectedScreenOff || s.proxyRole == "secondary" || s.companionRequest != null) return
        val address = connection.state.value.rememberedDe1Address ?: return
        if (companion.isAssociated(address)) return
        if (!explicit && coffee.crema.ble.companion.normalizeAddress(address) in s.companionDeclined) return
        if (firmwareHolds()) return
        if (s.bleState == De1BleManager.State.READY) {
            val idle = s.machineStateName == MachineState.Idle || s.machineStateName == MachineState.Sleep
            if (!idle) return
            appendLog("Nearby: pausing the DE1 link so Android can find it")
            nearbyPausedAddress = address
            disconnect()
        }
        notifyUser("Android will ask to confirm the DE1 once, so Crema can reconnect in the background")
        _ui.update { it.copy(companionRequest = coffee.crema.ble.companion.CompanionDevice.DE1) }
    }

    /** Reconnect the DE1 link the setup paused, if it paused one. */
    private fun resumeAfterNearby() {
        val address = nearbyPausedAddress ?: return
        nearbyPausedAddress = null
        connection.connectTo(address)
    }

    /** The remembered address to associate for [device], or null. */
    fun companionTargetAddress(device: coffee.crema.ble.companion.CompanionDevice): String? {
        val cs = connection.state.value
        return when (device) {
            coffee.crema.ble.companion.CompanionDevice.DE1 -> cs.rememberedDe1Address
            coffee.crema.ble.companion.CompanionDevice.SCALE -> cs.rememberedScaleAddress
        }
    }

    /** The system dialog created the association. */
    fun onCompanionAssociated(device: coffee.crema.ble.companion.CompanionDevice, address: String, id: Int?) {
        val observing = companion.onAssociated(address, id)
        _ui.update { it.copy(companionRequest = null) }
        refreshCompanion()
        resumeAfterNearby()
        val name = if (device == coffee.crema.ble.companion.CompanionDevice.DE1) "the DE1" else "the scale"
        notifyUser(
            if (observing) "Crema can now reconnect to $name in the background"
            else "Associated $name, but Android wouldn't watch for it — reconnecting as before",
        )
    }

    /** The association didn't happen (cancelled, not found, unsupported):
     *  reconnect a paused link, and don't ask again on connect (turning
     *  "Keep connected in the background" on again retries). */
    fun onCompanionSetupEnded(reason: String?) {
        val address = connection.state.value.rememberedDe1Address?.let { coffee.crema.ble.companion.normalizeAddress(it) }
        _ui.update {
            it.copy(
                companionRequest = null,
                companionDeclined = if (address == null) it.companionDeclined else (it.companionDeclined + address).distinct(),
            )
        }
        persistPrefs()
        resumeAfterNearby()
        reason?.let { appendLog("Companion setup: $it") }
    }

    /** Settings → "Remove": stop watching for the device and drop the association. */
    fun removeCompanion(device: coffee.crema.ble.companion.CompanionDevice) {
        companionTargetAddress(device)?.let { companion.remove(it) }
        refreshCompanion()
    }

    /** Equipment-level grinder model (free text). Persisted; rides Visualizer
     *  uploads/patches as `grinder_model`. */
    fun setGrinderModel(model: String) {
        editOwnSetting(Setting.GRINDER_MODEL) {
            _ui.update { it.copy(grinderModel = model) }
            persistPrefs()
        }
    }

    /** Default barista name (free text), stamped on each new shot and brew log. Persisted. */
    fun setBaristaName(name: String) {
        _ui.update { it.copy(baristaName = name) }
        persistPrefs()
    }

    /** "Sleep after" — idle minutes before Crema puts the DE1 to sleep (0 =
     *  Never), snapped to one of the core's choices. Persisted; the timer
     *  restarts from now. */
    fun setIdleSleepMin(minutes: Int) {
        editOwnSetting(Setting.IDLE_SLEEP) {
            val choices = idleSleepChoices()
            val snapped = choices[idleSleepIndex(minutes, choices)]
            _ui.update { it.copy(idleSleepMin = snapped) }
            persistPrefs()
            idleSleep.configure(snapped)
        }
    }

    /** Sleep the machine when Crema is quit (swiped from recents) — the
     *  [QuitSleep] handler consumes this at fire time. */
    fun setSleepOnQuit(on: Boolean) {
        _ui.update { it.copy(sleepOnQuit = on) }
        persistPrefs()
    }

    /** Keep the DE1 connected + auto-reconnecting while the screen is off (#65).
     *  Persisted as an explicit value (overriding the form-factor default); the
     *  keep-alive service still only engages while the device is charging. */
    fun setKeepConnectedScreenOff(on: Boolean) {
        _ui.update { it.copy(keepConnectedScreenOff = on) }
        persistPrefs()
        bgConnection.setEnabled(on)
        if (on) setUpNearby(explicit = true)
    }

    /** A screen / route change — activity for the idle-sleep timer. */
    fun noteNavigation() = idleSleep.activity(coffee.crema.core.IdleActivity.Navigation)

    // ── Sleep & screensaver ───────────────────────────────────────────────────

    /**
     * The one-line verdict at the top of a shot record: what was armed, what
     * actually stopped the shot, and what it yielded. Deliberately answers
     * "why didn't it stop" without anyone reading down the log — an unarmed
     * weight leg shows as `armed=weight — · …` next to `stopped=none`.
     */
    private fun shotDiagSummary(c: coffee.crema.core.EventShotCompletedInner): String {
        val s = effectiveState()
        val yieldG = c.final_weight?.let { fmt("%.1f g", it) } ?: "—"
        val peakG = c.peak_weight?.let { fmt("%.1f g", it) } ?: "—"
        return buildString {
            append("profile=").append(s.profiles.firstOrNull { it.id == s.activeProfileId }?.name ?: "—")
            append(" · armed=").append(lastArmedTargets ?: "NEVER ARMED")
            append(" · stopped=").append(s.lastStopReason?.string ?: "none")
            append(" · yield=").append(yieldG).append(" (peak ").append(peakG).append(")")
            append(" · ").append(c.duration).append("ms")
            append(" · scale=").append(s.scaleName ?: "none")
            append(" · start=").append(if (shotStartedByApp) "app" else "GHC/machine")
        }
    }

    /** Bump the saver's idle clock and tell the idle-sleep timer (throttled to
     *  one a second, see [IdleSleepRunner.activity]). Called on every touch
     *  (MainActivity's root interceptor, Initial pass, never consumes). */
    fun noteUserInteraction() {
        lastInteractionAtMs = SystemClock.elapsedRealtime()
        idleSleep.activity(coffee.crema.core.IdleActivity.Touch)
    }

    /** Foreground/background transitions from [MainActivity]'s
     *  onStart/onStop. Returning counts as user presence: the saver's idle
     *  clock is bumped (so it doesn't fire seconds after a long absence) and
     *  the idle-sleep timer restarts (its throttled presence poke goes with
     *  it). Backgrounding by itself is not activity — the timer keeps
     *  counting in the background for as long as the process lives. */
    fun setAppForeground(foreground: Boolean) {
        appInForeground = foreground
        // No wake-up tick in the background (the 1 s tick covers the core's
        // deadline while the link is kept up).
        if (!foreground) wake.cancel()
        // A foreground service may only be STARTED from the foreground (Android
        // 12+); the keep-alive is therefore armed while visible and survives the
        // screen turning off after (#65).
        bgConnection.setForeground(foreground)
        if (foreground) {
            lastInteractionAtMs = SystemClock.elapsedRealtime()
            idleSleep.activity(coffee.crema.core.IdleActivity.Foreground)
            // Coming back is when a slow reconnect hurts: interrupt any
            // remembered device's backoff / 60 s lurk wait and try NOW (the
            // foreground scan isn't throttled). Debounced per device, a no-op
            // for READY / mid-handshake / user-disconnected devices.
            connection.kickReconnect(coffee.crema.ble.ReconnectTrigger.FOREGROUND)
            // An association removed in system settings shows up here.
            if (prefsLoaded) refreshCompanion()
        }
    }

    /** "Retry now" on a reconnecting device row ([de1] = the DE1, else the scale). */
    fun retryReconnectNow(de1: Boolean) = connection.retryNow(de1)

    /** True while a Sleep state report can still be attributed to our own
     *  request. Deliberately putting the machine to sleep from the app must
     *  not raise the saver — only an *external* sleep (a GHC tap, the DE1's
     *  own sleep timer) does. Time-boxed rather than a sticky flag so a write
     *  that never lands can't swallow a later external sleep. Consuming it at
     *  the transition keeps the coupled sleep in [showSaver] a no-op (the
     *  saver is already up by then). */
    private fun appAskedForSleep(): Boolean {
        if (appSleepRequestedAtMs == 0L) return false
        val fresh = SystemClock.elapsedRealtime() - appSleepRequestedAtMs < APP_SLEEP_WINDOW_MS
        appSleepRequestedAtMs = 0L
        return fresh
    }

    /** The idle checker: every 30 s, show the saver once idle passes the
     *  threshold — never during a shot / steam / cleaning / an upload
     *  (de1app + Decenza's operation guards; a blocked trigger simply
     *  re-arms). Screen only: machine sleep is the idle-sleep timer's, which
     *  starts ticking here too. */
    private fun startScreensaverClock() {
        idleSleep.start()
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                val s = _ui.value
                val afterMin = s.screensaverAfterMin
                if (afterMin <= 0 || s.saverVisible) continue
                val idleMs = SystemClock.elapsedRealtime() - lastInteractionAtMs
                if (idleMs < afterMin * 60_000L) continue
                if (machineBusyForSaver(s)) continue
                showSaver()
            }
        }
    }

    /** Machine states / app work that must never be interrupted by the saver. */
    private fun machineBusyForSaver(s: MainUiState): Boolean =
        firmwareHolds() || s.shotInProgress || s.profileUploading || s.guidedBrew.live ||
            s.machineStateName in setOf(
                MachineState.Espresso, MachineState.Steam, MachineState.HotWater,
                MachineState.HotWaterRinse, MachineState.Descale, MachineState.Clean,
            )

    /** Show the saver. Display only: the machine sleeps on the idle-sleep
     *  timer ("Sleep after"), whatever the saver does. */
    private fun showSaver() {
        _ui.update { it.copy(saverVisible = true) }
    }

    /** Tap-to-wake: dismiss the saver, re-arm the idle clock, and — when
     *  [MainUiState.wakeMachineWithSaver] is on — wake the DE1 if we (or the
     *  GHC) put it to sleep, so one tap does both, like de1app's saver button
     *  and Decenza's ScreensaverPage.wake(). With it off the tap only clears
     *  the overlay: you can use the app (history, profiles) without heating
     *  the machine, and the power button wakes it when you mean to. */
    fun dismissSaver() {
        noteUserInteraction()
        if (!_ui.value.saverVisible) return
        _ui.update { it.copy(saverVisible = false) }
        if (_ui.value.wakeMachineWithSaver &&
            ble.state.value == De1BleManager.State.READY &&
            _ui.value.machineStateName == MachineState.Sleep
        ) {
            wake()
        }
    }

    /** Set the screensaver idle timeout, minutes (0 = never). Persisted. */
    fun setScreensaverAfterMin(minutes: Int) {
        _ui.update { it.copy(screensaverAfterMin = minutes.coerceIn(0, 120)) }
        persistPrefs()
    }

    /** Toggle the coupled machine wake on saver dismiss. Persisted. */
    fun setWakeMachineWithSaver(on: Boolean) {
        _ui.update { it.copy(wakeMachineWithSaver = on) }
        persistPrefs()
    }

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
    fun setFilterCapacity(litres: Double) =
        saveMaintenance(_ui.value.maintenance.copy(filterCapacityLitres = litres.coerceIn(5.0, 500.0)))

    /** Set the descale interval (litres). Persisted + readout recomputed. */
    fun setDescaleInterval(litres: Double) =
        saveMaintenance(_ui.value.maintenance.copy(descaleIntervalLitres = litres.coerceIn(10.0, 1000.0)))

    /** Set the clean-cycle interval (hours). Persisted + readout recomputed. */
    fun setCleanInterval(hours: Double) =
        saveMaintenance(_ui.value.maintenance.copy(cleanIntervalHours = hours.coerceIn(1.0, 500.0)))

    // Per-reminder arming (additive Option field: null = enabled). A disabled
    // reminder is never "due" — the core readout forces its *_ok true, which
    // silences the Brew banner on every shell with no special-casing.
    fun setFilterReminderEnabled(on: Boolean) =
        saveMaintenance(_ui.value.maintenance.copy(filterEnabled = on))

    fun setDescaleReminderEnabled(on: Boolean) =
        saveMaintenance(_ui.value.maintenance.copy(descaleEnabled = on))

    fun setCleanReminderEnabled(on: Boolean) =
        saveMaintenance(_ui.value.maintenance.copy(cleanEnabled = on))

    /** Start a steam-rinse cycle (Settings → Water → "Run now"). */
    fun startSteamRinse() = machine.execute(MachineCommand.StartSteamRinse)

    /** Check the DE1's serial against Decent's stolen-machine list (fetched at
     *  most daily, cached, failures silent) and raise the Settings notice. */
    private fun checkStolenSerial(serial: UInt) {
        viewModelScope.launch {
            val stolen = stolenSerials.isStolen(serial)
            _ui.update {
                if (it.de1MachineInfo[MmrRegister.SerialNumber] == serial) it.copy(serialStolen = stolen) else it
            }
        }
    }

    /** Set the Bengle cup-warmer plate temperature (0–80 °C) and re-read the
     *  register so the UI reflects what the machine accepted. */
    fun setCupWarmerTemp(tempC: Int) {
        editOwnSetting(Setting.CUP_WARMER_TEMP) {
            val raw = runCatching { bridge.setCupWarmerTemperature(tempC.coerceIn(0, 80).toUByte()) }.getOrElse {
                appendLog("Set cup warmer temp failed: ${it.message}")
                return
            }
            onCoreOutputJson(raw)
            runCatching { bridge.readMmr(MmrReg.CUP_WARMER_TEMP) }.getOrNull()?.let(::onCoreOutputJson)
        }
    }

    /** Show/hide the inline debug panel (Settings → Advanced). Persisted. */
    fun setShowDebugPanel(on: Boolean) {
        _ui.update { it.copy(showDebugPanel = on) }
        persistPrefs()
    }

    // ── Multi-device proxy: the manual hub address (emulators, no NSD) ──────
    fun setProxyPrimaryHost(host: String) {
        _ui.update { it.copy(proxyPrimaryHost = host.trim()) }
        persistPrefs()
    }

    fun setProxyPrimaryPort(port: Int) {
        _ui.update { it.copy(proxyPrimaryPort = port) }
        persistPrefs()
    }

    /** Debug: replay a captured shot as a fake DE1 when primary (emulator demos).
     *  Restart-to-apply (read at primary-start). Off = a real primary uses live BT. */
    fun setReplayPrimary(enabled: Boolean) {
        _ui.update { it.copy(replayPrimary = enabled) }
        persistPrefs()
    }

    /** Set the theme mode (`"system"` / `"light"` / `"dark"`) and persist. */
    fun setThemeMode(mode: String) {
        _ui.update { it.copy(themeMode = mode) }
        persistPrefs()
    }

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
        // Reset the maintenance counters too — a fresh-install state (baselines and
        // total zeroed, the three *AtMs = now so the readouts read "0 since").
        val freshMaintenance = defaultMaintenanceState(System.currentTimeMillis())
        onCoreLane {
            maintenanceTotalLitres = 0.0
            // A fresh stop-at-weight model (null = start learning again).
            runCatching { bridge.loadSawModelJson(null) }
        }
        _ui.update { it.copy(
            maintenance = freshMaintenance,
            maintenanceReadout = computeMaintenanceReadout(freshMaintenance),
        ) }
        // Every preference back to its default in memory too (barista name,
        // grinder, units, …): the next persistPrefs would otherwise write the
        // old values straight back.
        applyRestoredSettings(AppPrefs(idleSleepMin = defaultIdleSleepMin().toInt()))
        // The library/history/custom-profile share (UI rows + the
        // active-profile reseed via refreshProfiles).
        library.eraseAll()
        viewModelScope.launch {
            library.eraseStoredData()
            recipeStore.erase()
            settingsStore.erase()
            maintenanceStore.erase()
            maintenanceStore.save(freshMaintenance)
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

    /** A scale disconnect closed the session: clear the readings, capabilities,
     *  and identity so the scale card/config UI hides until the next scale.
     *  Wired as [ConnectionController]'s onScaleSessionClosed. */
    private fun clearScaleSessionUi() {
        // Losing the scale mid-shot silently kills stop-at-weight — the exact
        // complaint in issue #29 ("shots which did not stop on weight due to
        // the scale being disconnected"). The Brew card keeps the yield row
        // with a "no scale" note, but a card the user isn't looking at is not
        // enough for something that will overrun the cup in their hand.
        if (_ui.value.shotInProgress && _ui.value.stopTargets?.weightConfiguredG != null) {
            notifyUser("Scale disconnected — stop-at-weight is off for this shot")
        }
        // Re-arm the once-per-connection low-battery warning (issue #29).
        scaleLowBatteryWarned = false
        refreshStopTargetProjection()
        _ui.update { it.copy(
            scaleWeightG = null,
            scaleFlowGPerS = null,
            scaleTimerMs = null,
            // Drop the capabilities so the config UI hides until the next scale.
            scaleCapabilities = null,
            scaleVolume = DEFAULT_SCALE_VOLUME,
            scaleStandbyMinutes = DEFAULT_SCALE_STANDBY_MINUTES,
            scaleFlowSmoothing = false,
            scaleAutoStop = null,
            scaleAntiMistouch = false,
            scaleActiveMode = null,
            scaleEchoed = emptySet(),
            // Drop the scale's identity so the card clears for the next scale.
            scaleBatteryPercent = null,
            scaleName = null,
            scaleFirmware = null,
            scaleSerial = null,
        ) }
    }

    /**
     * Read the connected scale's [ScaleCapabilities] from the core and fold
     * them into the UI snapshot. Called by [ScaleBleManager] once the core has
     * identified the scale; the UI gates configuration controls on the result.
     *
     * Capability-driven, never device-driven: the UI never branches on the
     * concrete scale model, only on the capability flags.
     *
     * [advertisedName] is the scale's BLE name the scanner resolved — captured
     * into [MainUiState.scaleName] here so the scale card shows the device's
     * identity immediately, before the core's `0x0a` connect query returns the
     * firmware / serial.
     *
     * Also fires a baseline settings query at connect time: the core emits a
     * `Command.WriteScale` carrying the scale's `0x0f` settings-query command
     * (capability-gated — empty for a weight-only scale), routed through the
     * shared command path so the scale's `03 0e` settings response lands in
     * the capture.
     */
    private fun refreshScaleCapabilities(advertisedName: String) =
        // Bridge read + heartbeat-guard mutation → core lane (review #28).
        viewModelScope.launch(coreDispatcher) { refreshScaleCapabilitiesOnCoreLane(advertisedName) }

    private fun refreshScaleCapabilitiesOnCoreLane(advertisedName: String) {
        val capsJson = runCatching { bridge.scaleCapabilities() }.getOrNull()
        val caps = capsJson?.let {
            runCatching {
                json.decodeFromString(ScaleCapabilities.serializer(), it)
            }.getOrNull()
        }
        _ui.update { it.copy(
            scaleCapabilities = caps,
            scaleName = advertisedName,
        ) }

        val queryRaw = runCatching { bridge.queryScaleSettings() }.getOrElse {
            appendLog("Query scale settings failed: ${it.message}")
            return
        }
        onCoreOutputJson(queryRaw)
    }

    /**
     * Set the connected scale's beeper volume to [level]. Asks the core for the
     * command bytes, then routes the resulting `Command.WriteScale` through the
     * shared command path — the same path as Tare and the core's auto-tare.
     *
     * The requested level is clamped to the connected scale's
     * `ScaleCapabilities.volume` `[min, max]` bounds; the core clamps again, so
     * an out-of-range value is harmless either way. The core only emits a
     * command for a scale whose capabilities include a settable `volume`, so
     * this is capability-gated end to end. The UI's shown volume is updated
     * optimistically; the live `Event.ScaleReading.device_volume` stream then
     * catches up and confirms the scale's real value.
     */
    fun setScaleVolume(level: Int) {
        editOwnSetting(Setting.SCALE_VOLUME) {
            val range = _ui.value.scaleCapabilities?.volume
            val clamped = if (range != null) {
                level.coerceIn(range.min.toInt(), range.max.toInt())
            } else {
                level
            }
            _ui.update { it.copy(scaleVolume = clamped) }
            val raw = runCatching {
                bridge.setScaleVolume(clamped.toUByte())
            }.getOrElse {
                appendLog("Set volume failed: ${it.message}")
                return
            }
            onCoreOutputJson(raw)
        }
    }

    /**
     * Set the connected scale's auto-standby timeout to [minutes]. Asks the
     * core for the command bytes, then routes the resulting `Command.WriteScale`
     * through the shared command path — the same path as volume and Tare.
     *
     * The requested value is clamped to the connected scale's
     * `ScaleCapabilities.standby_minutes` `[min, max]` bounds; the core clamps
     * again, so an out-of-range value is harmless. Capability-gated end to end:
     * the core emits a command only for a scale that exposes a configurable
     * auto-standby. The UI's shown value is updated optimistically; the live
     * `Event.ScaleReading.device_standby` stream then catches up.
     */
    fun setScaleStandbyMinutes(minutes: Int) {
        editOwnSetting(Setting.SCALE_STANDBY) {
            val range = _ui.value.scaleCapabilities?.standby
            val clamped = if (range != null) {
                minutes.coerceIn(range.min.toInt(), range.max.toInt())
            } else {
                minutes
            }
            _ui.update { it.copy(scaleStandbyMinutes = clamped) }
            val raw = runCatching {
                bridge.setScaleStandby(clamped.toUByte())
            }.getOrElse {
                appendLog("Set standby failed: ${it.message}")
                return
            }
            onCoreOutputJson(raw)
        }
    }

    /**
     * Enable or disable the connected scale's flow smoothing. Asks the core for
     * the command bytes, then routes the resulting `Command.WriteScale` through
     * the shared command path. Capability-gated: the core emits a command only
     * for a scale that supports flow smoothing. The UI's shown toggle is
     * updated optimistically; the live `Event.ScaleReading.device_flow_smoothing`
     * stream then catches up and confirms the scale's real state.
     */
    fun setScaleFlowSmoothing(enabled: Boolean) {
        editOwnSetting(Setting.SCALE_FLOW_SMOOTHING) {
            _ui.update { it.copy(scaleFlowSmoothing = enabled) }
            val raw = runCatching {
                bridge.setScaleFlowSmoothing(enabled)
            }.getOrElse {
                appendLog("Set flow smoothing failed: ${it.message}")
                return
            }
            onCoreOutputJson(raw)
        }
    }

    /**
     * Enable or disable the connected scale's anti-mistouch. Asks the core for
     * the command bytes, then routes the resulting `Command.WriteScale` through
     * the shared command path. Capability-gated: the core emits a command only
     * for a scale that supports anti-mistouch. The UI's shown toggle is updated
     * optimistically (the value is not read back).
     */
    fun setScaleAntiMistouch(enabled: Boolean) {
        editOwnSetting(Setting.SCALE_ANTI_MISTOUCH) {
            _ui.update { it.copy(scaleAntiMistouch = enabled) }
            val raw = runCatching {
                bridge.setScaleAntiMistouch(enabled)
            }.getOrElse {
                appendLog("Set anti-mistouch failed: ${it.message}")
                return
            }
            onCoreOutputJson(raw)
        }
    }

    /**
     * Switch the connected scale to display mode [modeId]. Asks the core for
     * the command bytes, then routes the result through the shared command
     * path. Switching a mode is **three** `Command.WriteScale` entries; the
     * core emits them in order and [onCoreOutputJson]'s `commands.forEach`
     * loop preserves that order, so all three reach `ScaleBleManager` in
     * sequence. Capability-gated: the core emits commands only for a scale that
     * exposes switchable modes and only for a listed [modeId].
     *
     * The UI's shown active mode is updated optimistically; the live
     * `Event.ScaleConfig.active_mode` stream (the scale's `03 0e` settings
     * response) then catches up and confirms the scale's real mode.
     */
    fun setScaleMode(modeId: Int) {
        editOwnSetting(Setting.SCALE_MODE) {
            _ui.update { it.copy(scaleActiveMode = modeId) }
            val raw = runCatching {
                bridge.setScaleMode(modeId.toUByte())
            }.getOrElse {
                appendLog("Set mode failed: ${it.message}")
                return
            }
            onCoreOutputJson(raw)
        }
    }

    /**
     * Select the connected scale's auto-stop mode ([modeId]: 0 = flow-stop,
     * 1 = cup-removal). Asks the core for the command bytes, then routes the
     * resulting `Command.WriteScale` through the shared command path.
     * Capability-gated end to end: the core emits a command only for a scale
     * that supports an auto-stop-mode setting and only for an in-range
     * [modeId]. The UI's shown selection is updated optimistically; the live
     * `Event.ScaleReading.device_auto_stop` stream then catches up and confirms
     * the scale's real mode.
     */
    fun setScaleAutoStop(modeId: Int) {
        editOwnSetting(Setting.SCALE_AUTO_STOP) {
            _ui.update { it.copy(scaleAutoStop = modeId) }
            val raw = runCatching {
                bridge.setScaleAutoStop(modeId.toUByte())
            }.getOrElse {
                appendLog("Set auto-stop failed: ${it.message}")
                return
            }
            onCoreOutputJson(raw)
        }
    }

    /**
     * Tare the connected scale. Asks the core for the tare bytes, then routes
     * the resulting `Command.WriteScale` through the shared command path so the
     * exact same code handles a manual tare and the core's auto-tare.
     */
    fun tareScale() = machine.execute(MachineCommand.TareScale)

    private fun applyTareScale() {
        val raw = runCatching { bridge.tareScale() }.getOrElse {
            appendLog("Tare failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /**
     * Beep the connected scale (locate / test tone). Capability-gated — the
     * core only emits a command for a scale that supports it; it throws
     * `CremaException` for one that doesn't, which we surface and swallow.
     */
    fun beepScale() {
        val raw = runCatching { bridge.scaleBeep() }.getOrElse {
            appendLog("Beep failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /** Clear the scale's tracked peak + final weight (Scale page "Reset peak"). */
    fun resetScalePeaks() {
        runCatching { bridge.resetScalePeaks() }.onFailure {
            appendLog("Reset peak failed: ${it.message}")
        }
    }

    /**
     * Start the connected scale's built-in timer. Capability-gated like
     * [beepScale]; routed through the shared command path.
     */
    fun startScaleTimer() {
        val raw = runCatching { bridge.startTimer() }.getOrElse {
            appendLog("Start timer failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /**
     * The FFI/BLE seam: a JSON `CoreOutput` string came back from
     * [CremaBridge.onNotification]. Deserialize it and fold its [Event]s into
     * the UI state.
     *
     * Runs on a BLE binder thread; [MutableStateFlow] assignment is thread-safe
     * and Compose collects it on the main thread.
     */
    private fun onCoreOutputJson(raw: String) {
        // Confine ALL event/command application to the core lane
        // (review #28): callers arrive from Main, the DE1 manager scope,
        // and the scale manager scope — launches enqueue FIFO per sender
        // onto the single thread, so applyEvent's check-then-act state
        // (tickers, pending flags) is single-threaded by construction.
        // The guard also keeps one bad payload from killing the lane.
        viewModelScope.launch(coreDispatcher) {
            runCatching { applyCoreOutputJson(raw) }
                .onFailure { appendLog("Core output application failed: ${it.message}") }
        }
    }

    private fun applyCoreOutputJson(raw: String) {
        val output: CoreOutput = runCatching {
            json.decodeFromString(CoreOutput.serializer(), raw)
        }.getOrElse {
            appendLog("JSON parse error: ${it.message}")
            return
        }
        applyCoreOutput(output)
    }

    private fun applyCoreOutput(output: CoreOutput) {
        output.events.forEach(::applyEvent)
        // DE1 writes of one output go out IN ORDER on one coroutine (cohort
        // review 02 P2-2): a write retried after a failure must not land
        // after the writes that followed it — an Idle bounce retried behind
        // its Espresso would stop the shot that just started, and a retried
        // profile frame would arrive out of order. Scale writes are another
        // device and keep their own coroutines.
        val de1Writes = output.commands.filterIsInstance<Command.WriteCharacteristic>()
        output.commands.filterIsInstance<Command.WriteScale>().forEach(::executeCommand)
        if (de1Writes.isNotEmpty()) executeDe1Writes(de1Writes, startedUploadId(output.events))
    }

    /**
     * Centralised command execution: every `CoreOutput` — from the DE1 manager
     * AND the scale manager — funnels its `commands` here. This single path
     * makes both the manual Tare button and the core's automatic shot-start
     * auto-tare work, because both surface as a [Command.WriteScale].
     *
     * A scale write is dispatched on [viewModelScope]: `writeCommand` is a
     * `suspend fun`, so it awaits the BLE write on a coroutine rather than
     * blocking the BLE binder thread this may be called from.
     */
    private fun executeCommand(command: Command) {
        when (command) {
            is Command.WriteScale -> {
                // The core hands over the exact bytes as a List<UByte>.
                val bytes = command.content.data
                    .map { it.toByte() }
                    .toByteArray()
                // Fire-and-forget: a BLE write can fail (peer dropped, transient
                // stack error → Nordic throws OperationFailedException). Log it here
                // rather than let it escape the launch and crash the app; the
                // reconnect supervisor handles an actual disconnect on its own.
                viewModelScope.launch {
                    try {
                        scale.writeCommand(bytes)
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        appendLog("Scale write failed: ${e.message}")
                    }
                }
            }
            is Command.WriteCharacteristic -> executeDe1Writes(listOf(command))
        }
    }

    /**
     * Write [writes] (the DE1 commands of ONE `CoreOutput`) in order on a single
     * coroutine — each waits for the previous, including its one retry — so a
     * retry can never reorder them (cohort review 02 P2-2; reaprime awaits
     * each write in sequence, de1app runs one FIFO). A failed write is logged
     * and the rest still go out, as before. The BLE manager maps each target
     * to a UUID and writes; a profile header / frame write also gets its
     * synthesized ack. This is the machine-control path (AND5).
     */
    private fun executeDe1Writes(writes: List<Command.WriteCharacteristic>, uploadId: UInt? = null) {
        // A machine-state write during a shot IS the stop (issue #15).
        val shotRunning = _ui.value.shotInProgress
        fun isStop(c: Command.WriteCharacteristic) =
            c.content.target == WriteTarget.De1RequestedState && shotRunning
        // Coalesce stop writes: if one is already queued/waiting, a second
        // copy of the same Idle byte buys nothing — and every extra copy is a
        // stale-write risk after the machine stops.
        val batch = writes.filter { c ->
            if (isStop(c) && pendingStopWrite.get()?.isActive == true) {
                appendLog("Stop write already in flight — not stacking another")
                false
            } else {
                true
            }
        }
        if (batch.isEmpty()) return
        // The profile writes of this output: a header write supersedes every
        // older upload's remaining writes (cohort review 2026-10-28, #2 N2).
        val profileBatch = profileWrites.begin(batch.map { it.content.target }, uploadId)
        if (profileWrites.unnamed(profileBatch)) appendLog(UNNAMED_PROFILE_BATCH_LOG)
        val job = viewModelScope.launch {
            executeInOrder(batch) { c ->
                val profile = profileBatch?.takeIf { isProfileWriteTarget(c.content.target) }
                if (profile == null) {
                    deliverDe1Write(c, stopWrite = isStop(c))
                    return@executeInOrder
                }
                // Superseded, or an earlier write of this upload failed:
                // nothing more of it reaches the DE1.
                if (!profileWrites.mayWrite(profile)) return@executeInOrder
                val delivered = deliverDe1Write(c, stopWrite = false) {
                    profile.uploadId.takeIf { profileWrites.mayWrite(profile) }
                }
                if (profileWrites.settle(profile, delivered) == ProfileWriteGenerations.Outcome.FAILED) {
                    // Fail the upload now rather than on the ack watchdog.
                    appendLog("Profile write failed — stopping this upload")
                    // Named by its upload: this runs on Main, so a shot start
                    // on the core lane may already have begun a newer upload,
                    // which the core must leave alone (round-2 V3 #1).
                    profile.uploadId?.let { id ->
                        onCoreOutputJson(bridge.profileWriteFailed(SystemClock.elapsedRealtime().toULong(), id))
                    }
                }
            }
        }
        if (batch.any(::isStop)) {
            pendingStopWrite.set(job)
            job.invokeOnCompletion { pendingStopWrite.compareAndSet(job, null) }
        }
    }

    /** One DE1 write from [executeDe1Writes]; failures are logged, never thrown.
     *  Returns whether the write reached the DE1. [ackUploadId] names a profile
     *  write's upload for its synthesized ack, null when superseded (see [ProfileWriteGenerations]). */
    private suspend fun deliverDe1Write(
        command: Command.WriteCharacteristic,
        stopWrite: Boolean,
        ackUploadId: () -> UInt? = { null },
    ): Boolean {
        val bytes = command.content.data
            .map { it.toByte() }
            .toByteArray()
        // Time a stop write, so a field log shows exactly how long the stop
        // took to reach the machine (queue contention, retries, a wedged link
        // all show up here as milliseconds).
        val queuedAt = if (stopWrite) SystemClock.elapsedRealtime() else 0L
        // A failed machine write (e.g. the keep-alive poke as the DE1 drops)
        // must not become an uncaught crash.
        // A write's timeout or preemption arrives as De1WriteFailedException
        // (an IOException, logged here); only the batch's own teardown is a
        // cancellation (cohort review 2026-10-28, Android P2-1).
        return deliverOrLog(
            write = {
                // Stop writes ride the urgent lane (issue #15): they preempt any
                // in-flight non-critical GATT op instead of queuing behind it on
                // the global op mutex.
                val written = ble.writeCharacteristic(command.content.target, bytes, urgent = stopWrite, ackUploadId = ackUploadId)
                if (stopWrite && written) {
                    appendLog("Stop write delivered in ${SystemClock.elapsedRealtime() - queuedAt} ms")
                }
                written
            },
            log = { e ->
                appendLog(
                    if (stopWrite) {
                        "STOP WRITE FAILED after ${SystemClock.elapsedRealtime() - queuedAt} ms: ${e.message}"
                    } else {
                        "DE1 write failed (${command.content.target}): ${e.message}"
                    },
                )
            },
        )
    }

    private fun applyEvent(event: Event) {
        when (event) {
            is Event.MachineStateChanged -> {
                val c = event.content
                // Service-mode clock (steam / hot water / flush): anchor a
                // monotonic start on entry, re-anchor on a direct mode→mode
                // swap, clear on exit. The 4 Hz ticker feeds the Brew timers
                // while a mode runs — mirrors the web BrewDashboard's
                // modeStartedAtMs / modeNowMs pair.
                val mode = c.state == MachineState.Steam ||
                    c.state == MachineState.HotWater ||
                    c.state == MachineState.HotWaterRinse
                // Screensaver ↔ machine-sleep coupling, both directions
                // (de1app gui.tcl:60-64; Decenza main.qml:3019-3027):
                //  • a LIVE *external* transition into Sleep/GoingToSleep (GHC
                //    tap or the machine's own timer) shows the saver — but not
                //    a sleep the app asked for ([appAskedForSleep]: the power
                //    button is a deliberate act, not an idle one, and raising
                //    the saver over it made manual sleep near-unusable — the
                //    next tap woke the machine straight back up), and not the
                //    first state report after connect (Decenza's startup grace:
                //    connecting to an already-asleep DE1 must not hijack the UI);
                //  • the machine waking EXTERNALLY while the saver is up
                //    dismisses it (no wake() needed — it's already awake);
                //  • entering any active mode counts as user presence.
                val prevState = _ui.value.machineStateName
                val asleep = c.state.isAsleep()
                val wasAsleep = prevState.isAsleep()
                // The flash's own Sleep is not the user walking away (audit L8).
                if (!firmwareHolds() && shouldRaiseSaver(prevState, c.state, ::appAskedForSleep)) {
                    _ui.update { it.copy(saverVisible = true) }
                } else if (!asleep && wasAsleep && _ui.value.saverVisible) {
                    _ui.update { it.copy(saverVisible = false) }
                    lastInteractionAtMs = SystemClock.elapsedRealtime()
                }
                // The saver's idle clock only — the core's idle-sleep timer sees
                // every machine state itself.
                if (mode || c.state == MachineState.Espresso) lastInteractionAtMs = SystemClock.elapsedRealtime()
                _ui.update { prev ->
                    val started = when {
                        !mode -> null
                        prev.machineStateName != c.state -> SystemClock.elapsedRealtime()
                        else -> prev.modeStartedAtMs
                    }
                    prev.copy(
                        machineState = "${c.state.string} / ${c.substate.string}",
                        machineStateName = c.state,
                        machineSubstate = c.substate.string,
                        modeStartedAtMs = started,
                        modeElapsedMs = if (started == null) 0L else prev.modeElapsedMs,
                    )
                }
                updateModeTicker()
                appendLog("MachineState -> ${c.state.string} / ${c.substate.string}")
                // After the UI state, so a start that ends here hands the
                // Coffee button straight to Stop.
                startSequence.onMachineState(c.state)
            }
            is Event.IdleSleepRequested -> {
                lastIdleSleepAtMs = SystemClock.elapsedRealtime()
                appendLog(
                    "Idle for ${_ui.value.idleSleepMin} min — putting the DE1 to sleep" +
                        if (event.content.attempt > 1u) " (retry)" else "",
                )
            }
            is Event.IdleSleepBlocked -> appendLog(
                "Idle sleep due but waiting: ${event.content.reason}" +
                    (event.content.state?.let { " (${it.string})" } ?: ""),
            )
            is Event.IdleSleepUnconfirmed -> appendLog("Idle sleep: the DE1 never reported Sleep — timer re-armed")
            is Event.MaintenanceProgress -> {
                val c = event.content
                val terminal = isMaintenanceTerminal(c.phase)
                val phaseChanged = _ui.value.maintenanceRun?.phase != c.phase
                _ui.update { it.copy(maintenanceRun = if (terminal) null else c) }
                if (phaseChanged) {
                    appendLog(
                        "Maintenance ${c.state.string}: ${c.phase}" +
                            if (c.cold_workaround) " (cold-start workaround)" else "",
                    )
                }
                if (c.cold_workaround) {
                    // The DE1 holds core's 1 °C profile now, not ours; the
                    // core's profile sync puts the real one back once the
                    // cycle ends. The cold profile never loaded (or was replaced), so core
                    // did not send the request (round-2 V3 #4): say so, and why,
                    // instead of going quiet.
                    if (c.phase == MaintenancePhase.Failed) notifyUser(maintenanceFailedText(c.state, c.failure))
                }
            }
            is Event.MachineErrorChanged -> {
                // The fault to surface, decided in core so web and Android agree:
                // every Error* substate at once, except ErrorNoAc, which the DE1
                // reports spuriously while waking / heating and core holds until
                // it persists (firmware >= 1337, 6 s — Decenza 98215217 /
                // b8d625ba). A null message clears it.
                val c = event.content
                _ui.update { it.copy(machineError = c.message) }
                appendLog(
                    if (c.message == null) "Machine fault cleared (${c.substate.string})"
                    else "Machine fault: ${c.substate.string} — ${c.message}",
                )
            }
            is Event.ShotStarted -> {
                // Entering a shot: flip the resting↔extracting flag and zero the
                // per-shot counters so the Brew timer / phase / volume restart.
                _ui.update { it.copy(
                    shotInProgress = true,
                    shotFrame = 0,
                    shotElapsedMs = 0,
                    dispensedVolume = 0f,
                    shotTelemetry = emptyList(),
                    // Fresh shot — the previous stop attribution no longer applies.
                    lastStopReason = null,
                ) }
                // Fresh shot — clear the previous shot's record markers so a
                // stale "armed" line can never be attributed to this one (#56).
                lastArmedTargets = null
                // Tighten the DE1 link for the shot window (steadier ShotSample
                // cadence + lower-latency SAW stop write); restored to BALANCED
                // on ShotCompleted. Best-effort — see De1BleManager.
                viewModelScope.launch { ble.setConnectionPriority(high = true) }
                appendLog("Shot started")
            }
            is Event.ShotPhaseChanged -> {
                val phase = event.content.phase.string
                _ui.update { it.copy(shotPhase = phase) }
                appendLog("Shot phase -> $phase")
            }
            is Event.ShotFrameChanged -> {
                _ui.update { it.copy(shotFrame = event.content.frame.toInt()) }
                appendLog("Shot frame -> ${event.content.frame}")
            }
            is Event.Telemetry -> {
                val t = event.content
                // Add the core's counted water to the running total (the DE1 has no
                // cumulative counter): `counted_ml` is set only for espresso and hot
                // water, gap-capped — the core's rule, shared with web (Android used
                // to count descale, clean, steam rinse and air purge too). In-memory
                // only — flushed on shot / water-session completion.
                // SIDE EFFECT — stays OUTSIDE the update lambda so it adds exactly
                // once per frame even if the CAS retries (issue 11).
                t.counted_ml?.let { maintenanceTotalLitres += it / 1000.0 }
                val line = fmt("t=%dms  P=%.1fbar  flow=%.1fmL/s  head=%.1f°C", 
                    t.elapsed.toLong(), t.group_pressure, t.group_flow, t.head_temp,
                )
                // Atomic read-modify-write: build the chart buffer from the lambda's
                // `prev` (the live state), so a concurrent Event.ScaleReading update{}
                // landing mid-frame isn't clobbered (issue 11).
                _ui.update { prev ->
                    // Append a chart sample only during the FLOW window
                    // (preinfusion / pouring), like the core's own ShotRecord:
                    // the pre-pour heating phase piles zero-elapsed points and
                    // the Espresso/Ending tail (pump wind-down after the stop)
                    // used to keep the curve growing past the stop point —
                    // issue #44's "truncate at the stop" report. Fold in the
                    // latest scale weight/flow so the WEIGHT curves track the
                    // cup. FIFO-capped.
                    val flowing = prev.machineSubstate == "Preinfusion" ||
                        prev.machineSubstate == "Pouring"
                    val nextBuffer = if (prev.shotInProgress && flowing) {
                        val sample = TelemetrySample(
                            elapsedMs = t.elapsed.toLong(),
                            pressure = t.group_pressure,
                            flow = t.group_flow,
                            headTemp = t.head_temp,
                            mixTemp = t.mix_temp,
                            weight = prev.scaleWeightG,
                            weightFlow = prev.scaleFlowGPerS,
                            dispensedVolume = t.dispensed_volume,
                            resistance = t.resistance,
                            resistanceWeight = t.resistance_weight,
                            setHeadTemp = t.set_head_temp,
                            setGroupPressure = t.set_group_pressure,
                            setGroupFlow = t.set_group_flow,
                            frameNumber = prev.shotFrame,
                        )
                        val appended = prev.shotTelemetry + sample
                        if (appended.size > SHOT_TELEMETRY_CAP) {
                            appended.takeLast(SHOT_TELEMETRY_CAP)
                        } else {
                            appended
                        }
                    } else {
                        prev.shotTelemetry
                    }
                    // Keep the debug string AND project the structured channels the
                    // Brew dashboard reads. High-rate; no log line.
                    prev.copy(
                        telemetry = line,
                        pressure = t.group_pressure,
                        flow = t.group_flow,
                        headTemp = t.head_temp,
                        mixTemp = t.mix_temp,
                        steamTemp = t.steam_temp,
                        dispensedVolume = t.dispensed_volume,
                        resistance = t.resistance,
                        resistanceWeight = t.resistance_weight,
                        shotElapsedMs = t.elapsed.toLong(),
                        shotTelemetry = nextBuffer,
                    )
                }
            }
            is Event.ScaleReading -> {
                val r = event.content
                // The Bookoo echoes its live settings in every weight
                // notification. Project them into the config controls so they
                // show the scale's real current state — `device_*` is null for
                // scales that do not report a setting, in which case the
                // control keeps its last value.
                _ui.update { it.copy(
                    scaleWeightG = r.weight,
                    // Native per-packet flow when the scale reports one (Bookoo),
                    // else the core's derived estimate (FlowEstimator) — web
                    // parity (`device_flow ?? flow`). Binding only device_flow
                    // left every non-Bookoo scale (Half Decent, Acaia, …)
                    // showing "— g/s" forever (issue #24).
                    scaleFlowGPerS = r.device_flow ?: r.flow,
                    scaleTimerMs = r.device_timer?.toLong(),
                    scaleVolume = r.device_volume?.toInt() ?: it.scaleVolume,
                    scaleStandbyMinutes = r.device_standby?.toInt()
                        ?: it.scaleStandbyMinutes,
                    scaleBatteryPercent = r.device_battery?.toInt()
                        ?: it.scaleBatteryPercent,
                    scaleFlowSmoothing = r.device_flow_smoothing
                        ?: it.scaleFlowSmoothing,
                    scaleAutoStop = r.device_auto_stop?.toInt()
                        ?: it.scaleAutoStop,
                    scaleEchoed = it.scaleEchoed.withEchoes(
                        r.device_volume != null, r.device_standby != null, r.device_flow_smoothing != null, r.device_auto_stop != null,
                    ),
                ) }
                // Weight is high-rate; do not flood the log with every reading.
                // Low-battery warning (issue #29): one snackbar per connection
                // the first time the battery reads at or below the threshold,
                // so it can be charged before it dies mid-shot.
                val batt = _ui.value.scaleBatteryPercent
                if (batt != null && batt <= SCALE_LOW_BATTERY_PCT && !scaleLowBatteryWarned) {
                    scaleLowBatteryWarned = true
                    notifyUser("Scale battery low ($batt%) — charge it soon")
                }
            }
            is Event.WaterLevel -> {
                _ui.update { it.copy(
                    waterLevelMm = event.content.level,
                    waterRefillThresholdMm = event.content.refill_threshold,
                ) }
                // Log only when the whole millimetre actually changes. The DE1
                // reports the tank level continuously (~2.4/s), and logging
                // every report drowned the diagnostics buffer: a real report on
                // geota/crema#56 arrived as 299 identical "Water level: 35mm"
                // lines out of 300, covering 124 seconds — the shot being
                // investigated had been evicted hours earlier. Decenza throttles
                // the same channel for the same reason (de1device.cpp: emit only
                // when the level changes).
                val mm = event.content.level.roundToInt()
                if (mm != lastLoggedWaterMm) {
                    lastLoggedWaterMm = mm
                    appendLog("Water level: ${mm}mm")
                }
            }
            is Event.WaterLow -> {
                // The core's low-water monitor (#33 follow-up, shared with web):
                // once per dip below the user's threshold, re-armed on a refill,
                // a threshold change or a new link. Shown in the user's tank unit.
                val s = _ui.value
                val left = formatTankLevel(event.content.level, s.waterLevelUnit, s.volumeUnit)
                notifyUser("Water low — ${left.value} ${left.unit} left, refill soon")
            }
            is Event.ShotSettingsRead -> {
                // Project into UI state: the mode chips + steam / hot-water
                // timers read the machine's own targets (web de1ShotSettings parity).
                _ui.update { it.copy(de1ShotSettings = event.content) }
            }
            is Event.SawAutoZeroed -> {
                val w = fmt("%.0f", event.content.offset_g)
                appendLog("SAW re-zeroed on the fly ($w g cup)")
                notifyUser("Scale re-zeroed on the fly — $w g was on it")
            }
            is Event.SawSuppressedUntaredCup -> {
                val w = fmt("%.0f", event.content.weight_g)
                appendLog("SAW suppressed — untared cup ($w g)")
                notifyUser("Stop-at-weight off for this shot — the scale wasn\u2019t tared ($w g on it)")
            }
            // A tare that arrived mid-pour was dropped by the core rather
            // than obeyed (geota/crema#50). Say so — otherwise the Tare
            // button just looks broken.
            is Event.TareRefusedMidShot -> {
                appendLog("Tare refused — a shot is pouring")
                notifyUser("Tare ignored — a shot is running. Taring now would break stop-at-weight.")
            }
            is Event.StopTargetsArmed -> {
                // The SAW-visibility line (issue #15): what will stop this
                // shot, straight from the core's armed targets — a silent
                // arming failure now reads as "weight —" in the log.
                val c = event.content
                val armed = "weight " + (c.weight?.let { fmt("%.1f g", it) } ?: "\u2014") +
                    " · volume " + (c.volume?.let { fmt("%.0f ml", it) } ?: "\u2014") +
                    " · max " + (c.max_time?.let { fmt("%.0f s", it) } ?: "\u2014")
                // Kept for the frozen shot record's verdict line (#56).
                lastArmedTargets = armed
                // Mirror into the snapshot so every stop-condition surface
                // renders the core's answer instead of its own (#56).
                _ui.update { it.copy(stopTargets = StopTargetsView(
                    c.weight, c.volume, c.max_time, c.armed,
                    c.weight_configured, c.weight_blocked,
                )) }
                appendLog((if (c.armed) "Stop targets armed: " else "Stop targets (next shot): ") + armed)
            }
            is Event.StopRetried -> {
                // The machine hasn't honoured a commanded stop — the core is
                // re-commanding it (issue #15). Loud on purpose.
                appendLog("Stop NOT honoured — re-commanding (attempt ${event.content.attempt}, ${event.content.reason.string})")
            }
            is Event.StopTriggered -> {
                appendLog("Auto-stop: ${event.content.reason.string}")
                // Attribute the stop ON the stop-conditions UI (the fired
                // row/card gets a gold highlight) instead of a toast — an
                // unexplained early stop reads as a bug, but a snackbar over
                // the fresh shot was clutter. Espresso only: a hot-water
                // weight stop must not ring the espresso stop cards.
                _ui.update {
                    if (it.shotInProgress) it.copy(lastStopReason = event.content.reason) else it
                }
            }
            is Event.ShotCompleted -> {
                // The machine stopped — cancel any stop write still queued or
                // waiting on the GATT mutex. Delivered late, that same Idle
                // byte would wake a sleeping machine or kill a steam/flush the
                // user starts next (issue #15 follow-up).
                pendingStopWrite.getAndSet(null)?.let { job ->
                    if (job.isActive) {
                        job.cancel(kotlinx.coroutines.CancellationException("machine already stopped"))
                        appendLog("Stale stop write cancelled — machine already stopped")
                    }
                }
                val c = event.content
                val now = System.currentTimeMillis()
                // One UUID-v7 shot id (core minter), shared by the "Last shot"
                // card and the StoredShot captureCompletedShot persists, so
                // tapping the card opens the stored shot.
                val shotId = newShotId()
                // Leaving a shot: clear the extracting flag and capture the
                // summary for the Brew "Last shot" card.
                _ui.update { it.copy(
                    shotInProgress = false,
                    lastShot = LastShot(
                        durationMs = c.duration.toLong(),
                        yieldG = c.final_weight,
                        peakPressure = c.peak_pressure,
                        peakTemp = c.peak_temp,
                        completedAtMs = now,
                        id = shotId,
                    ),
                ) }
                appendLog("Shot completed: ${c.duration}ms, ${c.sample_count} samples")
                // A weight/volume/max-time target was armed, yet the app
                // never triggered its own stop — the DE1's own profile
                // frame timing ended the pour on its own instead (core:
                // Event::ShotCompleted.ended_without_triggering_stop). Most
                // often a Quick Controls yield bump past what the profile's
                // frames can physically produce in their configured
                // duration. Called out distinctly so "why did my shot end
                // short" doesn't require correlating timestamps by hand.
                if (c.ended_without_triggering_stop == true) {
                    appendLog(
                        "Shot ended on the profile's own timing, not the app's stop " +
                            "(armed: ${lastArmedTargets ?: "unknown"}) — the profile's frames " +
                            "ran out before the target could be reached",
                    )
                }
                // Shot window over — release the HIGH-priority link hint
                // requested on ShotStarted.
                viewModelScope.launch { ble.setConnectionPriority(high = false) }
                library.captureCompletedShot(
                    c.duration.toLong(), c.final_weight, c.peak_pressure, c.peak_temp, now, shotId,
                    // Core-classified (review #40); null only on a version-skewed
                    // event stream → record.
                    c.disposition ?: ShotDisposition.Record,
                    peakWeightG = c.peak_weight,
                )
                // A pour just finished: flush the integrated water into the persisted
                // maintenance state and recompute the filter / descale / clean readout.
                flushMaintenance()
                // Freeze this shot's event trail to disk while it is still in
                // the rolling buffer. A "stop at weight didn't work" report
                // reaches us long after the shot, by which time the buffer has
                // scrolled — see [ShotDiag] (geota/crema#56).
                coffee.crema.diag.ShotDiag.finish(getApplication(), shotDiagSummary(c))
                // Consumed — the next shot is a machine start until the app
                // says otherwise.
                shotStartedByApp = false
            }
            is Event.WaterSessionStarted -> {
                appendLog("Water session started: ${event.content.kind.string}")
                if (event.content.kind == WaterSessionKind.Flush) startSequence.onFlushStarted()
            }
            is Event.WaterSessionCompleted -> {
                appendLog("Water session completed: ${event.content.kind.string}")
                // Flush the integrated water (same boundary as a completed shot).
                flushMaintenance()
                // Release the pre-shot flush waiter so the shot can proceed to
                // Espresso (issue 15) — only for a group flush, not a hot-water pour.
                if (event.content.kind == WaterSessionKind.Flush) {
                    startSequence.onFlushCompleted()
                }
            }
            is Event.BrewSessionStarted -> {
                // Anchor the shell clock on the SAME monotonic timebase the
                // notifications feed the core (elapsedRealtime).
                _ui.update { st ->
                    st.copy(
                        guidedBrew = st.guidedBrew.copy(
                            phase = "running",
                            startedAtRealtime = SystemClock.elapsedRealtime(),
                            stepIndex = 0,
                            stepStartedAtMs = 0L,
                            pausedAccumMs = 0L,
                            pausedSinceRealtime = null,
                        ),
                    )
                }
                _liveBrewSeries.value = coffee.crema.core.BrewSeries(
                    samples = emptyList(),
                    stageMarks = listOf(coffee.crema.ui.brewlog.liveStageMark(_ui.value.guidedBrew.recipe, 0, 0L)),
                )
                appendLog("Guided brew started — ${event.content.recipe_name}")
            }
            is Event.BrewStepChanged -> {
                _ui.update { st ->
                    st.copy(
                        guidedBrew = st.guidedBrew.copy(
                            stepIndex = event.content.step_index.toInt(),
                            stepStartedAtMs = event.content.at_ms.toLong(),
                        ),
                    )
                }
                if (event.content.step_index.toInt() > 0) {
                    _liveBrewSeries.update { s ->
                        s.copy(
                            stageMarks = s.stageMarks + coffee.crema.ui.brewlog.liveStageMark(
                                _ui.value.guidedBrew.recipe,
                                event.content.step_index.toInt(),
                                event.content.at_ms.toLong(),
                            ),
                        )
                    }
                }
                // The chime for an auto-advanced boundary rides the step
                // change itself (Boundary cues cover hold-steps).
                if (event.content.step_index.toInt() > 0) playBrewCue("step")
            }
            is Event.BrewCueDue ->
                playBrewCue(if (event.content.cue == coffee.crema.core.BrewCue.Approach) "approach" else "boundary")
            is Event.BrewSessionCompleted -> {
                stopBrewTick()
                bgConnection.setBrewSessionActive(false)
                _ui.update { st ->
                    st.copy(guidedBrew = st.guidedBrew.copy(phase = "done", summary = event.content.summary))
                }
                playBrewCue("done")
                appendLog("Guided brew completed — ${event.content.summary.recipeName}")
            }
            is Event.SteamSessionStarted -> appendLog("Steam session started")
            is Event.SteamSessionCompleted -> {
                appendLog("Steam session completed: ${event.content.duration}ms")
                // Auto-purge after steam (issue 15 — Quick Controls / Settings
                // "Steam purge"). A short group flush clears the plumbing once the
                // user releases steam; gated on the pref + stands down if a shot
                // flush already owns the group. DE1-gated.
                if (_ui.value.steamPurge) autoPurge.schedule()
            }
            is Event.SteamClogSuspected ->
                appendLog("Steam clog suspected: ${event.content.reason.string}")
            is Event.SteamEcoModeChanged ->
                appendLog("Steam eco mode: ${event.content.eco}")
            is Event.ScaleStale -> {
                _ui.update { it.copy(scaleWeightG = null) }
                appendLog("Scale stale")
            }
            is Event.ScaleLinkDead -> {
                // The core presumed the scale link dead (silent 5 s past
                // ScaleStale, or three failed writes with no reading between;
                // once per episode). Tear it down for the reconnect supervisor,
                // but only while the link still claims READY — one already
                // reconnecting is left to its ladder.
                _ui.update { it.copy(scaleWeightG = null) }
                appendLog("Scale link dead: ${event.content.reason.string}")
                if (_ui.value.scaleState == ScaleBleManager.State.READY) {
                    viewModelScope.launch { scale.presumeDead(event.content.reason.string) }
                }
            }
            is Event.ScaleButtonPressed ->
                // Logged like de1app (bluetooth.tcl:2825-2828) — no hard-wired
                // action yet; the event is the hook for a future mapping.
                appendLog("Scale button pressed: ${event.content.button}")
            is Event.ScaleConfig -> {
                val c = event.content
                // The Bookoo's `ff12` command channel reports its dynamic
                // config: a `03 0c` serial response carries anti_mistouch (plus
                // serial / firmware), a `03 0e` settings response carries
                // active_mode. Any one notification fills only one set, so the
                // other fields are null — fold in whatever is present and keep
                // the last value for the rest, the same two-way pattern the
                // weight-stream settings use.
                _ui.update { it.copy(
                    scaleAntiMistouch = c.anti_mistouch ?: it.scaleAntiMistouch,
                    scaleActiveMode = c.active_mode?.toInt() ?: it.scaleActiveMode,
                    // The `03 0c` serial response carries firmware + serial;
                    // the `03 0e` settings response leaves both null — fold in
                    // whatever is present and keep the last value otherwise.
                    scaleFirmware = c.firmware ?: it.scaleFirmware,
                    scaleSerial = c.serial ?: it.scaleSerial,
                ) }
                when {
                    c.anti_mistouch != null ->
                        appendLog(
                            "Scale config: anti-mistouch=${c.anti_mistouch}" +
                                (c.serial?.let { ", serial=$it" } ?: "") +
                                (c.firmware?.let { ", fw=$it" } ?: ""),
                        )
                    c.active_mode != null ->
                        appendLog(
                            "Scale config: active mode=${c.active_mode}, " +
                                "enabled modes=${c.enabled_modes}",
                        )
                }
            }
            is Event.DecodeError ->
                appendLog("Decode error: ${event.content.message}")
            // The version / MMR read paths now feed the Settings machine rows.
            // The preformatted firmware label is folded straight in; each MMR
            // value lands raw in de1MachineInfo (the UI applies per-register
            // scaling). Keep a log line so the data stays visible in the debug
            // readout too.
            is Event.Firmware -> {
                _ui.update { it.copy(de1Firmware = event.content.firmware_string) }
                appendLog("Firmware: ${event.content.firmware_string}")
            }
            is Event.MmrValue -> {
                val c = event.content
                val before = _ui.value.de1MachineInfo[c.register]
                _ui.update { it.copy(
                    de1MachineInfo = it.de1MachineInfo + (c.register to c.value),
                ) }
                // Remotes show these registers (A12, A13, A15): push the change.
                // They arrive after the write's reply, and none is relayed, so
                // only the push itself is owed here (§6.1). Built on Main.
                if (c.register in MIRRORED_REGISTERS && before != c.value) {
                    viewModelScope.launch(Dispatchers.Main) { proxy.pushConfig() }
                }
                appendLog("MMR ${c.register}: ${c.value}")
                if (c.register == MmrRegister.SerialNumber) checkStolenSerial(c.value)
            }
            // Calibration value replies (current / factory) have no settings row
            // yet, so keep logging them. Core surfaces only WriteKey-0 replies,
            // never echoes of our own requests; the stored value is `measured`.
            is Event.Calibration ->
                appendLog(
                    "Calibration ${event.content.target} " +
                        "(${event.content.command}): ${event.content.measured}",
                )
            is Event.WakeAt -> wake.arm(event.content.at_ms.toLong())
            is Event.MachineRequestHeld -> {
                appendLog("${event.content.state.string} request held until the profile finishes loading")
                notifyUser(REQUEST_HELD_TEXT)
            }
            is Event.MachineRequestDropped -> {
                val text = machineRequestDropText(event.content.state, event.content.reason)
                appendLog(text)
                notifyUser(text)
            }
            is Event.MachineRequestRefused -> {
                val text = machineRequestRefusalText(event.content.state, event.content.reason)
                appendLog("Request refused: $text")
                notifyUser(text)
            }
            // v1 stub never fires this in practice.
            is Event.FirmwareLockoutHit ->
                appendLog(
                    "Write refused (firmware update in progress): " +
                        event.content.method,
                )
            is Event.ProfileUploadStarted -> {
                _ui.update { it.copy(profileUploading = true, profileUploadProgress = null) }
                appendLog("Uploading profile: ${event.content.title}")
            }
            is Event.ProfileUploadWaitingForModel ->
                appendLog("Waiting for the machine model before uploading ${event.content.title}")
            is Event.ProfileUploadProgress -> {
                val c = event.content
                // High-rate (one per frame ack); update the progress hint, no log.
                _ui.update { it.copy(
                    profileUploadProgress = "${c.acks_received}/${c.total_acks}",
                ) }
            }
            is Event.ProfileUploadCompleted -> {
                _ui.update { it.copy(profileUploading = false, profileUploadProgress = null) }
                appendLog("Profile uploaded: ${event.content.title}")
            }
            is Event.ProfileSyncHolds -> {
                // Gated start: the DE1 holds the start's profile now. Observe
                // the firmware's profile-download guard window, optionally
                // pre-flush, then request Espresso (issue 15).
                if (pendingBrew && event.content.fingerprint == pendingBrewFingerprint) {
                    pendingBrew = false
                    pendingBrewFingerprint = null
                    pendingBrewStart?.let { startSequence.proceed(it, _ui.value.preFlush) }
                    pendingBrewStart = null
                }
            }
            is Event.ProfileUploadFailed -> {
                // The start waiting on the DE1 is over (the user taps Coffee
                // again); the core retries the upload on its own backoff.
                if (pendingBrew) pendingBrewStart?.let(startSequence::end)
                pendingBrew = false
                pendingBrewStart = null
                pendingBrewFingerprint = null
                _ui.update { it.copy(profileUploading = false, profileUploadProgress = null) }
                appendLog("Profile upload failed: ${uploadFailureLabel(event.content.reason)}")
            }
            is Event.ProfileSyncWarning -> {
                // The core's one non-blocking warning per streak of failed
                // profile uploads; it keeps retrying (3 s, 10 s, then 30 s).
                if (event.content.active) {
                    notifyUser("Couldn\u2019t load the profile on the DE1 \u2014 retrying")
                    appendLog("Profile upload to the DE1 failed \u2014 the core is retrying")
                } else {
                    appendLog("Profile upload to the DE1 recovered")
                }
            }
            // Shot-settings-read events surface in the real screens (M5), not this
            // Phase-0 debug readout. An explicit else keeps the sealed `when`
            // exhaustive as `Event` grows.
            else -> Unit
        }
    }

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
        _ui.update { it.copy(eventLog = prependLogLine(it.eventLog, stamped, MAX_LOG_LINES)) }
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
            QuitSleep.clear(quitSleepRegistration)
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

        /** How long a Sleep state report is still attributable to our own
         *  request (BLE write → firmware → notify, plus a GoingToSleep dwell). */
        private const val APP_SLEEP_WINDOW_MS = 15_000L

        /** Grace past [QUIT_WRITE_BUDGET_MS] before onCleared's deferred BLE
         *  teardown runs anyway (the write's own timeout normally fires first). */
        private const val QUIT_TEARDOWN_SLACK_MS = 250L

        /** Abort a pending gated-start brew if the upload never completes. */
        private const val PROFILE_UPLOAD_TIMEOUT_MS = 15_000L

        /** How long the Quick-Controls dial must rest before its override
         *  becomes the desired profile (de1app debounces its temperature dial by
         *  0.5 s too), so a run of stepper taps uploads once. */
        private const val DIAL_PUSH_DEBOUNCE_MS = 500L
    }
}

/** Charging, or battery ≥ 50 % — the firmware flash's power precondition. */
private fun firmwarePowerOk(context: android.content.Context): Boolean {
    val status = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return true
    val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
    return coffee.crema.firmware.powerOk(plugged, percent)
}

/** These UI settings as the shared [CommonSettings] (via [AppPrefs], whose
 *  mapping is the canonical one) — what [MainViewModel.pushCoreInputs] hands
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
    qcFlushTimeS = qcFlushTimeS,
    qcFlushTempC = qcFlushTempC,
).toCommonSettings()

/** The core's wire spelling (`"grams" | "ounces"`) of the `"g" | "oz"` weight-unit pref. */
private fun coreWeightUnit(pref: String): String =
    if (pref == "oz") WeightUnit.Ounces.string else WeightUnit.Grams.string
