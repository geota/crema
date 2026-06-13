package coffee.crema.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.MachineRequest
import coffee.crema.core.MmrReg
import coffee.crema.core.MmrRegister
import coffee.crema.core.ScaleCapabilities
import coffee.crema.core.Bean
import coffee.crema.core.Roaster
import coffee.crema.core.MaintenanceState
import coffee.crema.core.MaintenanceReadout
import coffee.crema.core.blankCremaProfile
import coffee.crema.core.builtinCremaProfiles
import coffee.crema.core.cremaProfileToWire
import coffee.crema.core.importBeanconquerorJson
import coffee.crema.core.exportBeanconquerorMainJson
import coffee.crema.core.maintenanceReadout
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.LibraryStore
import coffee.crema.beans.newBean
import coffee.crema.beans.newRoaster
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.history.downsampleForStorage
import coffee.crema.maintenance.MaintenanceStore
import coffee.crema.maintenance.MAINTENANCE_MAX_SAMPLE_DT_S
import coffee.crema.maintenance.MAINTENANCE_MAX_SAMPLE_ML
import coffee.crema.maintenance.defaultMaintenanceState
import coffee.crema.settings.AppPrefs
import coffee.crema.visualizer.VisualizerClient
import coffee.crema.visualizer.VisualizerStore
import coffee.crema.visualizer.VisualizerSync
import coffee.crema.settings.SettingsStore
import coffee.crema.ble.BleScanner
import coffee.crema.ble.BleSessionRecorder
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.NordicBleTransport
import coffee.crema.ble.ScaleBleManager
import coffee.crema.core.ShotBean
import coffee.crema.core.profileFingerprint
import coffee.crema.core.subStateErrorMessage
import coffee.crema.profiles.BrewDefaults
import coffee.crema.profiles.CremaProfile
import coffee.crema.profiles.CustomProfileStore
import coffee.crema.profiles.HiddenProfileStore
import coffee.crema.profiles.PinnedProfileStore
import coffee.crema.profiles.setProfilePinnedJson
import coffee.crema.profiles.brewDefaultsJson
import coffee.crema.profiles.duplicatedCustomProfileJson
import coffee.crema.profiles.patchCremaProfileJson
import coffee.crema.profiles.overrideBrewParamsJson
import coffee.crema.profiles.quickPresetJson
import coffee.crema.profiles.profileIdOf
import coffee.crema.profiles.SegmentEdit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive

/**
 * Default scale beeper-volume step shown before the first live reading.
 *
 * The scale's true volume is decoded live from every Bookoo weight
 * notification (`Event.ScaleReading.device_volume`) and projected into
 * [MainUiState.scaleVolume] as soon as it arrives — this default is only the
 * fallback step the control shows in the brief window before that first
 * reading. The control's range is taken from `ScaleCapabilities.volume`.
 */
const val DEFAULT_SCALE_VOLUME: Int = 3

/**
 * Default scale auto-standby timeout (minutes) shown before the first live
 * reading.
 *
 * Like [DEFAULT_SCALE_VOLUME], this is only the fallback shown until the first
 * `Event.ScaleReading.device_standby` arrives, after which the control
 * tracks the scale's real value. The range comes from
 * `ScaleCapabilities.standby_minutes`.
 */
const val DEFAULT_SCALE_STANDBY_MINUTES: Int = 15

/**
 * Summary of the most recently completed espresso shot — assembled from
 * `Event.ShotCompleted` for the Brew screen's "Last shot" card. Null until a
 * shot finishes; held until the next shot starts.
 */
data class LastShot(
    /** Total shot duration, milliseconds. */
    val durationMs: Long,
    /** Final scale weight before the shot ended (the yield), grams, or null. */
    val yieldG: Float?,
    /** Peak group pressure across the shot, bar, or null. */
    val peakPressure: Float?,
    /** Peak group-head temperature across the shot, °C, or null. */
    val peakTemp: Float?,
    /** Unix epoch ms the shot completed — drives the card's "· N min ago" eyebrow. */
    val completedAtMs: Long = 0,
    /** The matching [StoredShot] id (same `"shot:$now"`), so tapping the card opens
     *  it in History. Null for the pre-first-shot default. */
    val id: String? = null,
)

/** A flat snapshot of everything the current screen shows. */
/**
 * Transient Quick-Controls brew override — the dose / yield / brew-temp the user
 * nudged in the Quick Controls sheet. NOT persisted to any profile (mirrors the
 * web shell's BrewParamState): it is baked into the NEXT shot's uploaded profile
 * at [MainViewModel.startShot] and cleared on profile switch / Reset. The live
 * mid-shot SAW dial (web `setShotTargetWeight`) needs a bridge FFI not yet
 * exposed — that's the documented Phase-B follow-up.
 */
data class BrewParams(val dose: Double, val yieldOut: Double, val brewTemp: Double)

data class MainUiState(
    val bleState: De1BleManager.State = De1BleManager.State.IDLE,
    /** Coarse state of the scale connection. */
    val scaleState: ScaleBleManager.State = ScaleBleManager.State.IDLE,
    /** Most recent status line (scan / connect / error transitions). */
    val status: String = "Idle",
    /** Latest decoded machine state + substate, or null before the first one. */
    val machineState: String? = null,
    /** Latest shot phase. */
    val shotPhase: String? = null,
    /** Latest telemetry sample, pre-formatted (debug readout). */
    val telemetry: String? = null,
    // ── Structured live telemetry (Brew dashboard) ───────────────────────────
    // Projected from `Event.Telemetry`; null until the first sample of a session.
    /** Group pressure, bar. */
    val pressure: Float? = null,
    /** Group flow, ml/s. */
    val flow: Float? = null,
    /** Group-head temperature, °C (the COFFEE channel). */
    val headTemp: Float? = null,
    /** Mix temperature, °C — the DE1's blended group water (COFFEE-card secondary). */
    val mixTemp: Float? = null,
    /** Steam-heater temperature, °C (foot "Steam" readout). */
    val steamTemp: Float? = null,
    /** Running shot volume dispensed so far, ml — integrated by the core. */
    val dispensedVolume: Float? = null,
    /** Puck resistance, `bar/(ml/s)²` — pump-flow derived, or null near zero flow. */
    val resistance: Float? = null,
    /** Scale-derived puck resistance, `bar/(g/s)²` — preferred when a scale is paired. */
    val resistanceWeight: Float? = null,
    /** Milliseconds since the shot began (`Event.Telemetry.elapsed`); resets each shot. */
    val shotElapsedMs: Long = 0,
    /** Zero-based active frame index (`Event.ShotFrameChanged`); resets each shot. */
    val shotFrame: Int = 0,
    /** True between `ShotStarted` and `ShotCompleted` — drives resting↔extracting UI. */
    val shotInProgress: Boolean = false,
    /**
     * Buffered per-sample telemetry for the live shot chart — appended on each
     * `Event.Telemetry` while [shotInProgress], reset on `ShotStarted`, capped at
     * [SHOT_TELEMETRY_CAP]. Empty when no shot is running. The scalar fields
     * above stay live outside a shot (for the resting channel cards); this series
     * only fills during extraction (when the chart draws curves).
     */
    val shotTelemetry: List<TelemetrySample> = emptyList(),
    /** Raw machine-state name (e.g. `"Espresso"`) for `==` comparisons, or null. */
    val machineStateName: String? = null,
    /** Raw machine-substate name (e.g. `"Pouring"`), or null. */
    val machineSubstate: String? = null,
    /** Human-readable message when the current substate is an *error* (e.g.
     *  `"No water — refill the tank"`), else null. Sourced from core
     *  `subStateErrorMessage` so it matches the web shell's copy verbatim. */
    val machineError: String? = null,
    /** Latest tank level, mm (`Event.WaterLevel`), or null before the first report. */
    val waterLevelMm: Float? = null,
    /**
     * The DE1's pre-formatted firmware label (e.g. `"v1.0.142 (API 4)"`), or
     * null until the machine's `Version` characteristic reply arrives.
     * Read-only — folded from `Event.Firmware.firmware_string`. Live only with
     * a connected DE1; the Settings machine rows show "—" otherwise.
     */
    val de1Firmware: String? = null,
    /**
     * Raw MMR register values the DE1 reported, keyed by [MmrRegister]. Folded
     * from each `Event.MmrValue`; values are the **raw** 32-bit words — the UI
     * applies the per-register scaling (heater-voltage − 1000, flow-multiplier ÷
     * 1000, GHC-info bitmask, etc.). Populated by the connect-time read sweep
     * once the DE1 reaches READY; empty when disconnected, so the Settings rows
     * fall back to "—".
     */
    val de1MachineInfo: Map<MmrRegister, UInt> = emptyMap(),
    /**
     * The mains line frequency the core resolved (`50.0` / `60.0` Hz), or `0.0`
     * when the override is "auto-detect", or null before it is read. A pure
     * (no-BLE) core read, refreshed by the connect-time sweep. Surfaces the AC
     * mains-frequency selection in Settings → Advanced.
     */
    val lineFreqHz: Float? = null,
    /** Summary of the last completed shot (Last-shot card), or null. */
    val lastShot: LastShot? = null,
    /**
     * Built-in profiles from the core (`builtinCremaProfiles()`), loaded once at
     * startup. The Brew header picker chooses from these until the profile
     * library (M3) adds user profiles.
     */
    val profiles: List<CremaProfile> = emptyList(),
    /** Hidden (archived) built-in profile ids — filtered out of the active grid
     *  except under the Profiles "Hidden" facet. */
    val hiddenProfileIds: Set<String> = emptySet(),
    /** The selected profile's id (the Brew header's active profile), or null. */
    val activeProfileId: String? = null,
    /** Transient Quick-Controls brew override, or null = use the active profile. */
    val brewParams: BrewParams? = null,
    /**
     * True while a profile is being uploaded to the DE1 (between
     * `ProfileUploadStarted` and `ProfileUploadCompleted`/`Failed`) — drives the
     * header "Uploading…" hint and the Coffee button's syncing state during the
     * gated shot start.
     */
    val profileUploading: Boolean = false,
    /** Upload progress as `"received/total"` acks while uploading, or null. */
    val profileUploadProgress: String? = null,
    /**
     * Shot-behaviour toggles surfaced in Quick Controls. Shell-managed and
     * optimistic — the core applies them via its setters but does not echo them
     * back as events, so these hold the last user choice (the same pattern the
     * scale config toggles use).
     */
    val autoTare: Boolean = false,
    val stopOnWeight: Boolean = false,
    val steamEco: Boolean = false,
    /** Persisted pre-shot flush / post-steam purge preferences (not yet consumed
     *  by the shot sequence — Settings rows carry the pill until then). */
    val preFlush: Boolean = false,
    val steamPurge: Boolean = false,
    /** Hold the screen on while a shot is pulling (Settings → Display). */
    val keepScreenOnBrew: Boolean = false,
    /** Brew defaults (Settings → Brew defaults) — seed new profiles + QC fallbacks.
     *  Seeded from the core's [BrewDefaults] so web + Android share one source. */
    val defaultDoseG: Float = BrewDefaults.INSTANCE.doseG,
    val defaultRatio: Float = BrewDefaults.INSTANCE.ratio,
    val defaultBrewTempC: Float = BrewDefaults.INSTANCE.brewTempC,
    val defaultPreinfuseS: Float = BrewDefaults.INSTANCE.preinfusionS,
    /**
     * Queued user-facing feedback lines (imports, exports, blocked actions).
     * MainActivity surfaces them as snackbars, dequeuing via
     * [MainViewModel.consumeUserMessage] — the web shell's ToastHost equivalent.
     * A queue, not a single field, so two rapid notifications both surface.
     */
    val userMessages: List<String> = emptyList(),
    /** Max shot duration cap, seconds (persisted in AppPrefs). Shown as a Time
     *  stop-condition on Brew + edited in Settings → Brew defaults. */
    val maxShotDurationS: Float = 45f,
    /**
     * Which telemetry channels the live chart draws — keys: `pressure`, `flow`,
     * `headTemp`, `mixTemp`, `weight`, `weightFlow`, `dispensedVolume`,
     * `resistance`. A local display pref toggled from Quick Controls; defaults
     * match the web (pressure / flow / weight).
     */
    val chartChannels: Set<String> = setOf("pressure", "flow", "weight"),
    /** The bean library — user bean bags, persisted via [LibraryStore]. */
    val beans: List<Bean> = emptyList(),
    /** The roaster directory (FK target for [beans]). */
    val roasters: List<Roaster> = emptyList(),
    /** The active bean id — the Brew bean block + burn-down, or null. */
    val activeBeanId: String? = null,
    /** The bean currently open in the editor (`bean-edit` route), or null. */
    val editingBeanId: String? = null,
    /** The roaster open in the pushed `roaster-edit` route (phone), or null =
     *  a new-roaster draft when that route is open. */
    val editingRoasterId: String? = null,
    /** A not-yet-saved new-bean draft the full editor edits — the `bean-edit`
     *  route resolves this when [editingBeanId] names a bean not yet in [beans]
     *  (the "Add bean" → full-editor path). Committed to the library on Save. */
    val draftBean: Bean? = null,
    /** The profile currently open in the editor (`profile-edit` route), or null. */
    val editingProfileId: String? = null,
    /**
     * A brand-new or duplicated profile being edited but not yet saved. The editor
     * seeds from this when [editingProfileId] names a profile not yet in [profiles]
     * (New / Duplicate); null when editing an existing saved custom profile. Cleared
     * on save or cancel.
     */
    val draftProfile: CremaProfile? = null,
    /** Completed-shot log (newest first), persisted via [HistoryStore]. */
    val history: List<StoredShot> = emptyList(),
    /** Visualizer sync state (account / prefs / in-flight uploads) — mirrored
     *  from [MainViewModel.visualizer]'s controller flow. */
    val visualizer: VisualizerSync.UiState = VisualizerSync.UiState(),
    /** A shot id the History screen should select on next open (set when the Brew
     *  "Last shot" card is tapped). Consumed + cleared by HistoryScreen. */
    val pendingHistoryShotId: String? = null,
    /**
     * The persisted water-accumulation & maintenance state — counters, baselines,
     * and user-set intervals. The DE1 has no cumulative water counter, so the
     * shell integrates group flow over telemetry into [MaintenanceState.totalLitres]
     * (see [MainViewModel.applyEvent]'s Telemetry branch); persisted via
     * [MaintenanceStore]. Seeded with the fresh-install default until [loadMaintenance].
     */
    val maintenance: MaintenanceState = defaultMaintenanceState(0L),
    /**
     * The derived filter / descale / clean readouts the Settings Water section +
     * the Brew maintenance banner read, or null before the first compute. Produced
     * from [maintenance] by the pure core FFI (`maintenanceReadout`); recomputed
     * only at load, on shot/water-session completion, and after a mark action —
     * NOT per telemetry sample (that would thrash at ~25–40 Hz).
     */
    val maintenanceReadout: MaintenanceReadout? = null,
    /** Theme mode — `"system" | "light" | "dark"` (Settings), persisted. */
    val themeMode: String = "dark",
    val grinderModel: String = "",
    val suppressDe1Sleep: Boolean = true,
    val showDebugPanel: Boolean = false,
    /** Latest scale weight in grams, or null before the first reading. */
    val scaleWeightG: Float? = null,
    /**
     * Latest scale-reported native mass-flow rate in g/s, or null — only
     * scales that report their own flow (the Bookoo) populate this; it is
     * distinct from the core-computed flow.
     */
    val scaleFlowGPerS: Float? = null,
    /**
     * Latest scale-reported built-in-timer reading in milliseconds, or null —
     * only scales with a built-in timer (the Bookoo) populate this.
     */
    val scaleTimerMs: Long? = null,
    /**
     * What the connected scale can do beyond reporting a bare weight, or null
     * before a scale is connected / identified. The UI gates configuration
     * controls on this descriptor — Crema is capability-driven, never
     * device-driven, so the UI never branches on the concrete scale model.
     */
    val scaleCapabilities: ScaleCapabilities? = null,
    /**
     * The scale beeper-volume step (0..5) the UI currently shows.
     *
     * Two-way: it follows the scale's live value, decoded from every Bookoo
     * weight notification (`Event.ScaleReading.device_volume`), and is also
     * updated optimistically the moment the user changes the control — the
     * stream then catches up. Starts at [DEFAULT_SCALE_VOLUME] until the first
     * reading arrives.
     */
    val scaleVolume: Int = DEFAULT_SCALE_VOLUME,
    /**
     * The scale auto-standby timeout (minutes) the UI currently shows.
     *
     * Two-way like [scaleVolume]: it follows the scale's live value
     * (`Event.ScaleReading.device_standby`) and is updated
     * optimistically on user change. Starts at [DEFAULT_SCALE_STANDBY_MINUTES].
     */
    val scaleStandbyMinutes: Int = DEFAULT_SCALE_STANDBY_MINUTES,
    /**
     * The scale's battery charge percentage, or null before the first reading
     * (or for a scale that does not report it). Read-only — only the Bookoo
     * reports a battery level, decoded from its weight notification.
     */
    val scaleBatteryPercent: Int? = null,
    /**
     * The connected scale's BLE advertised name (e.g. `"BOOKOO_SC 502158"`),
     * or null when no scale is connected. Captured on connect from the name
     * the scanner resolved off the peripheral's advertisement; cleared on
     * [MainViewModel.disconnectScale]. Read-only — it is the device's BLE
     * identity, not a configurable setting.
     */
    val scaleName: String? = null,
    /**
     * The connected scale's firmware version, pre-formatted `"M.m.p"` (e.g.
     * `"1.4.1"`), or null until the scale's `03 0c` serial response arrives
     * (or for a scale that does not report it). Read-only — decoded from
     * `Event.ScaleConfig.firmware_version`, the `u16` the Bookoo answers its
     * connect-time `0x0a` query with, encoded `major × 100 + minor × 10 +
     * patch`.
     */
    val scaleFirmware: String? = null,
    /**
     * The connected scale's serial number, or null until the scale's `03 0c`
     * serial response arrives (or for a scale that does not report one).
     * Read-only — taken straight from `Event.ScaleConfig.serial`.
     */
    val scaleSerial: String? = null,
    /**
     * Whether the scale's flow smoothing toggle is shown as on.
     *
     * Two-way like [scaleVolume]: the Bookoo echoes its real flow-smoothing
     * state in every weight notification (`Event.ScaleReading.device_flow_smoothing`),
     * so the toggle follows that live value; it is also updated optimistically
     * the moment the user flips the control, after which the stream confirms
     * it. Starts off until the first reading arrives.
     */
    val scaleFlowSmoothing: Boolean = false,
    /**
     * The scale's currently selected auto-stop mode id (`0` = Flow-Stop, `1` =
     * Cup-Removal), or `null` until the first reading arrives (or for a scale
     * that does not report it).
     *
     * Two-way like [scaleFlowSmoothing]: the Bookoo echoes its real auto-stop
     * mode in every weight notification (`Event.ScaleReading.device_auto_stop`),
     * so the selector highlights that live value; it is also updated
     * optimistically the moment the user picks a mode, after which the stream
     * confirms it.
     */
    val scaleAutoStop: Int? = null,
    /**
     * Whether the scale's anti-mistouch toggle is shown as on.
     *
     * Two-way like [scaleFlowSmoothing]: the Bookoo reports its real
     * anti-mistouch state on its `ff12` command channel — in the `03 0c` serial
     * response to a connect-time query and after every anti-mistouch write
     * (`Event.ScaleConfig.anti_mistouch`) — so the toggle follows that live
     * value; it is also updated optimistically the moment the user flips the
     * control, after which the response confirms it. Starts off until the
     * first `ScaleConfig` arrives.
     */
    val scaleAntiMistouch: Boolean = false,
    /**
     * The scale's active display-mode index (`0` = Flow-Rate, `1` = Timer,
     * `2` = Auto), or `null` until the first `ff12` settings response arrives.
     *
     * Two-way like [scaleAutoStop]: the Bookoo reports its active mode on its
     * `ff12` command channel in the `03 0e` settings response
     * (`Event.ScaleConfig.active_mode`), so the mode selector highlights that
     * live value; it is also updated optimistically the moment the user picks
     * a mode, after which the response confirms it.
     */
    val scaleActiveMode: Int? = null,
    /** A rolling event log, newest first. */
    val eventLog: List<String> = emptyList(),
)

/**
 * Owns the [CremaBridge] and the [De1BleManager], parses the JSON `CoreOutput`
 * the bridge returns, and projects decoded [Event]s into [MainUiState].
 *
 * Carried over from the Phase-0 proof-of-concept: connection, core ownership,
 * and per-screen state are still folded into one class so the end-to-end path
 * is readable top to bottom. Splitting these into proper layers is future work.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val json = Json { ignoreUnknownKeys = true }

    /** The Rust core, behind the UniFFI bridge. */
    private val bridge: CremaBridge = CremaBridge()

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

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
     * [startShot] feeds to [cremaProfileToWire]. Rebuilt by [refreshProfiles].
     */
    private var profileJsonById: Map<String, String> = emptyMap()

    /**
     * A new / duplicated profile's complete base JSON, held between [startNewProfile]
     * / [duplicateProfile] and the editor's [saveProfile]; null otherwise.
     */
    private var draftProfileJson: String? = null

    /**
     * Set when [startShot] kicks off a profile upload that should be followed by an
     * Espresso request once the upload completes (the gated start). Cleared on
     * `ProfileUploadCompleted` (after firing Espresso), on `ProfileUploadFailed`,
     * on the upload timeout, and on disconnect.
     */
    private var pendingBrew = false

    /**
     * Profile-upload-skip cache (issue 11, mirrors the web shell). The djb2
     * fingerprint of the profile last successfully uploaded to the *currently
     * connected* DE1 — so a re-`startShot` of the same profile skips the
     * redundant upload and goes straight to Espresso. [pendingUploadFingerprint]
     * holds the in-flight upload's fingerprint, committed to [lastUploadedFingerprint]
     * on `ProfileUploadCompleted`. The cache is invalidated on any drop out of
     * READY (the DE1 no longer holds our bytes) — without that, a skip after a
     * power-cycle would start a shot against a stale/absent profile.
     *
     * NOTE: unverified — there is no DE1 simulator, so the skip→Espresso path
     * cannot be exercised in CI/emulator. The decision is unit-tested
     * ([shouldSkipProfileUpload]); the wiring degrades safely (a null fingerprint
     * ⇒ never skip ⇒ the previous always-upload behaviour).
     */
    private var lastUploadedFingerprint: String? = null
    private var pendingUploadFingerprint: String? = null

    /** Bean-library persistence — a JSON file in filesDir. */
    private val library = LibraryStore(app, json)

    /** Shot-history persistence — a JSON file in filesDir. */
    private val historyStore = HistoryStore(app, json)

    /** App-preferences persistence — a JSON file in filesDir. */
    private val settingsStore = SettingsStore(app, json)

    /** Custom-profile persistence — a JSON file in filesDir. */
    private val customProfileStore = CustomProfileStore(app, json)

    /** Pinned built-in ids (Favorites star) — a JSON file in filesDir. */
    private val pinnedProfileStore = PinnedProfileStore(app, json)
    private val hiddenProfileStore = HiddenProfileStore(app, json)

    /** Maintenance-state persistence — a JSON file in filesDir. */
    private val maintenanceStore = MaintenanceStore(app, json)

    /**
     * Visualizer sync — sign-in, account, shot upload. A self-contained
     * controller (deliberately NOT folded into this ViewModel); its [state]
     * is mirrored into [MainUiState.visualizer] below and the UI calls its
     * methods through this public handle.
     */
    val visualizer: VisualizerSync = VisualizerSync(
        store = VisualizerStore(app, json),
        client = VisualizerClient(json),
        json = json,
        scope = viewModelScope,
        clientId = coffee.crema.BuildConfig.VISUALIZER_CLIENT_ID,
        appVersion = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "dev",
        notify = { msg -> notifyUser(msg) },
        onShotSynced = { localId, visualizerId -> markShotSynced(localId, visualizerId) },
        grinderModel = { _ui.value.grinderModel.trim().takeIf { it.isNotEmpty() } },
        onPulledShots = { stubs -> insertPulledShots(stubs) },
        onBackfillTelemetry = { localId, samples, durationMs -> backfillShotTelemetry(localId, samples, durationMs) },
    )

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
     * Timestamp (System.currentTimeMillis) of the previous telemetry sample, for
     * the wall-clock Δ the accumulate integrates over; null at session start.
     * Reset to null on `ShotStarted` / `WaterSessionStarted` so the FIRST sample
     * of a session adds no Δ (avoids a huge between-session gap being integrated).
     */
    private var lastTelemetryMs: Long? = null

    /**
     * The app-wide BLE transport — the one Nordic-backed [NordicBleTransport].
     * Created once here and [NordicBleTransport.close]d from [onCleared]; it
     * owns the Nordic `Environment` (a broadcast receiver that must be closed).
     * The scanner and both managers sit on top of it.
     */
    private val bleTransport = NordicBleTransport(app)

    /**
     * The one app-wide BLE scanner, shared by both managers. A single
     * unfiltered scan dispatches each result by advertised-name match to
     * whichever device currently wants to connect — see [connect] / [connectScale].
     */
    private val bleScanner = BleScanner(
        transport = bleTransport,
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

    private val ble = De1BleManager(
        transport = bleTransport,
        bridge = bridge,
        recorder = bleRecorder,
        onCoreOutput = ::onCoreOutputJson,
        onStatus = { line -> _ui.update { it.copy(status = line) } },
    )

    /**
     * The Bookoo scale connection. Shares the one [bridge] with [ble]: the core
     * is internally `Mutex`-guarded, so both managers feeding it concurrently
     * is safe. Its `CoreOutput` goes through the same [onCoreOutputJson] path.
     * It also shares the one [bleRecorder] so its weight notifications and
     * tare/timer writes land in the same interleaved capture file as the DE1's.
     */
    private val scale = ScaleBleManager(
        transport = bleTransport,
        bridge = bridge,
        recorder = bleRecorder,
        onCoreOutput = ::onCoreOutputJson,
        onStatus = { line -> _ui.update { it.copy(status = line) } },
        onScaleIdentified = ::refreshScaleCapabilities,
    )

    init {
        // Collect the managers' coarse connection-state flows so the UI
        // snapshot updates promptly when either advances — rather than only
        // when an unrelated event happens to call observeBleState().
        viewModelScope.launch {
            var wasReady = false
            ble.state.collect { state ->
                _ui.update { it.copy(bleState = state) }
                // Fire the machine read-sweep once per connection, on the first
                // transition into READY (services discovered + subscribed). The
                // reads emit read-request commands whose replies arrive later as
                // Firmware / MmrValue events; the pure reads fold straight in.
                if (state == De1BleManager.State.READY && !wasReady) {
                    wasReady = true
                    readMachineInfo()
                } else if (state != De1BleManager.State.READY) {
                    wasReady = false
                    // The DE1 no longer holds our profile — drop the upload-skip
                    // cache so the next shot re-uploads (issue 11). Without this, a
                    // skip after a reconnect would brew against a stale/absent profile.
                    lastUploadedFingerprint = null
                }
            }
        }
        viewModelScope.launch {
            scale.state.collect { state ->
                _ui.update { it.copy(scaleState = state) }
            }
        }
        loadBuiltinProfiles()
        // Load both persisted stores in one coroutine, sequentially, so their
        // _ui copies don't race (read-modify-write of the shared snapshot).
        viewModelScope.launch {
            loadLibrary()
            loadHistory()
            loadPrefs()
            pinnedIds = pinnedProfileStore.load()
            hiddenIds = hiddenProfileStore.load()
            loadCustomProfiles()
            loadMaintenance()
            visualizer.load()
        }
        startDe1KeepAlive()
        // Mirror the Visualizer controller's state into the UI snapshot.
        viewModelScope.launch {
            visualizer.state.collect { vs ->
                _ui.update { it.copy(visualizer = vs) }
            }
        }
    }

    /** Insert pulled Visualizer stubs into the history (dedup by id, newest first). Persisted. */
    private fun insertPulledShots(stubs: List<StoredShot>) {
        if (stubs.isEmpty()) return
        _ui.update { st ->
            val existing = st.history.map { it.id }.toHashSet()
            val fresh = stubs.filter { it.id !in existing }
            if (fresh.isEmpty()) {
                st
            } else {
                st.copy(history = (fresh + st.history).sortedByDescending { it.completedAtMs }.take(HistoryStore.MAX_SHOTS))
            }
        }
        val snapshot = _ui.value.history
        viewModelScope.launch { historyStore.save(snapshot) }
    }

    /** Backfill a curve-less local shot's telemetry from a Visualizer pull. Persisted. */
    private fun backfillShotTelemetry(localId: String, samples: List<TelemetrySample>, durationMs: Long) {
        _ui.update { st ->
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
        val snapshot = _ui.value.history
        viewModelScope.launch { historyStore.save(snapshot) }
    }

    /** Stamp a successful Visualizer upload onto the local shot. Persisted. */
    private fun markShotSynced(localId: String, visualizerId: String) {
        _ui.update { st ->
            st.copy(history = st.history.map { if (it.id == localId) it.copy(visualizerId = visualizerId) else it })
        }
        val snapshot = _ui.value.history
        viewModelScope.launch { historyStore.save(snapshot) }
    }

    /**
     * Load the core's built-in profiles once and seed the Brew header's active
     * selection. `builtinCremaProfiles()` is a sans-IO core call returning a
     * JSON array of `CremaProfile`; the adapter logic lives in the Rust core
     * (de1_domain::crema_profile) and every shell shares it. Failures are
     * swallowed — the header just shows "No profile selected".
     */
    private fun loadBuiltinProfiles() {
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
        val current = _ui.value.activeProfileId?.takeIf { id -> all.any { it.id == id } }
        _ui.update { it.copy(
            profiles = all,
            hiddenProfileIds = hiddenIds,
            activeProfileId = current ?: all.firstOrNull { it.id !in hiddenIds }?.id,
        ) }
    }

    /** Load the persisted custom profiles into the merged list at startup. */
    private suspend fun loadCustomProfiles() {
        customProfilesJson = customProfileStore.load()
        refreshProfiles()
    }

    /** Persist the current custom profiles (best-effort, off the main thread). */
    private fun persistCustomProfiles() {
        val snapshot = customProfilesJson
        viewModelScope.launch { customProfileStore.save(snapshot) }
    }

    /** Select the Brew header's active profile. (M1: display only — uploading the
     *  profile to the DE1 on Coffee is the M2 gated-start sequence.) Persisted
     *  (AppPrefs.activeProfileId) so the selection survives a restart. */
    fun setActiveProfile(id: String) {
        // Switching profiles clears any Quick-Controls override (re-seeds from new).
        _ui.update { it.copy(activeProfileId = id, brewParams = null) }
        persistPrefs()
    }

    /**
     * Toggle a profile's Favorites star (the `pinned` flag the Quick-Controls
     * favorites strip reads). Custom → patch + persist its own JSON; built-in →
     * flip membership in the persisted [pinnedIds] set. Either way [refreshProfiles]
     * re-derives the merged list so the card, header count, and favorites strip
     * stay in lock-step.
     */
    fun togglePinProfile(id: String) {
        val profile = _ui.value.profiles.firstOrNull { it.id == id } ?: return
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
            viewModelScope.launch { pinnedProfileStore.save(snapshot) }
        }
    }

    /** Apply a Quick-Controls brew override (dose/yield/brew-temp). Transient —
     *  not saved to the profile; baked into the next shot's upload by [startShot]. */
    fun quickAdjustBrew(dose: Double, yieldOut: Double, brewTemp: Double) {
        _ui.update { it.copy(brewParams = BrewParams(dose, yieldOut, brewTemp)) }
    }

    /** Reset the Quick-Controls override back to the active profile's recipe. */
    fun resetBrewParams() {
        _ui.update { it.copy(brewParams = null) }
    }

    /**
     * Save the current Quick-Controls dial values as a NEW custom profile (the web
     * shell's "Save preset") — clones the active profile, overwrites dose/yield/
     * brew-temp, upserts into the custom store, makes it active. Never mutates the
     * source profile. Pure shell (reuses the duplicate + custom-store path).
     */
    fun saveQuickPreset(name: String) {
        val activeId = _ui.value.activeProfileId ?: return
        val base = profileJsonById[activeId] ?: return
        val bp = _ui.value.brewParams
        val active = _ui.value.profiles.firstOrNull { it.id == activeId }
        val d = bp?.dose?.toFloat() ?: active?.dose ?: BrewDefaults.INSTANCE.doseG
        val y = bp?.yieldOut?.toFloat() ?: active?.yieldOut
            ?: (BrewDefaults.INSTANCE.doseG * BrewDefaults.INSTANCE.ratio)
        val t = bp?.brewTemp?.toFloat() ?: active?.brewTemp ?: BrewDefaults.INSTANCE.brewTempC
        val preset = runCatching { quickPresetJson(base, name, d, y, t, json) }.getOrElse {
            appendLog("Save preset failed: ${it.message}")
            return
        }
        customProfilesJson = listOf(preset) + customProfilesJson
        refreshProfiles()
        persistCustomProfiles()
        profileIdOf(preset, json)?.let { setActiveProfile(it) }
    }

    // ── Profile editor (the `profile-edit` route) ─────────────────────────────

    /** Open an existing saved (custom) profile in the editor. */
    fun startEditProfile(id: String) {
        draftProfileJson = null
        _ui.update { it.copy(editingProfileId = id, draftProfile = null) }
    }

    /**
     * Begin a brand-new custom profile: the core mints a complete blank
     * `CremaProfile` (a fresh id, `source: "custom"`, the default segment list)
     * seeded from the user's brew defaults. Held as a draft until the editor saves.
     */
    fun startNewProfile() {
        // Seed from the persisted Settings → Brew defaults (the wiring point
        // brewDefaultsJson documents).
        val defaults = brewDefaultsJson(
            doseG = _ui.value.defaultDoseG,
            ratio = _ui.value.defaultRatio,
            brewTempC = _ui.value.defaultBrewTempC,
            preinfusionS = _ui.value.defaultPreinfuseS,
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
            ?: draftProfileJson?.takeIf { _ui.value.editingProfileId == id }
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
        _ui.update { it.copy(editingProfileId = thin.id, draftProfile = thin) }
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
            draftProfileJson != null && _ui.value.editingProfileId == id -> draftProfileJson
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
        _ui.update { it.copy(draftProfile = null) }
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
        _ui.update { it.copy(editingProfileId = null, draftProfile = null) }
    }

    /**
     * Delete a custom profile (built-ins are not deletable — they have no store
     * entry, so this is a no-op for them). Clears the active selection if it was
     * the deleted one; [refreshProfiles] then reseeds it to the first remaining.
     */
    fun deleteProfile(id: String) {
        if (customProfilesJson.none { profileIdOf(it, json) == id }) return
        customProfilesJson = customProfilesJson.filterNot { profileIdOf(it, json) == id }
        if (_ui.value.activeProfileId == id) {
            _ui.update { it.copy(activeProfileId = null) }
        }
        // Cascade: a deleted custom is gone for good — clear bean links so
        // the auto-load never chases a tombstone (web onDeleted parity).
        if (_ui.value.beans.any { it.linkedProfileId == id }) {
            _ui.update { st ->
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
        if (_ui.value.activeProfileId == id) {
            _ui.update { it.copy(activeProfileId = null) }
        }
        refreshProfiles()
        val snapshot = hiddenIds
        viewModelScope.launch { hiddenProfileStore.save(snapshot) }
    }

    /** Restore a hidden built-in profile back to the active grid. Persisted. */
    fun unarchiveBuiltinProfile(id: String) {
        hiddenIds = hiddenIds - id
        refreshProfiles()
        val snapshot = hiddenIds
        viewModelScope.launch { hiddenProfileStore.save(snapshot) }
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
        val shot = _ui.value.history.firstOrNull { it.id == id } ?: return
        val text = runCatching { json.encodeToString(StoredShot.serializer(), shot) }
            .getOrElse { appendLog("Export shot failed: ${it.message}"); return }
        shareText(text, "Share shot")
    }

    /** Export the whole shot history as a JSON array — Settings → Sharing. */
    fun exportAllShots() {
        val shots = _ui.value.history
        if (shots.isEmpty()) { appendLog("Export: no shots"); return }
        val text = runCatching { json.encodeToString(ListSerializer(StoredShot.serializer()), shots) }
            .getOrElse { appendLog("Export shots failed: ${it.message}"); return }
        shareText(text, "Export shots")
    }

    /** Export the bean library (beans + roasters) as JSON — Settings → Sharing. */
    fun exportBeansLibrary() {
        val lib = BeanLibrary(beans = _ui.value.beans, roasters = _ui.value.roasters, activeBeanId = _ui.value.activeBeanId)
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
        val lib = BeanLibrary(beans = _ui.value.beans, roasters = _ui.value.roasters, activeBeanId = _ui.value.activeBeanId)
        if (lib.beans.isEmpty() && lib.roasters.isEmpty()) { notifyUser("Export — empty library"); return null }
        return runCatching { json.encodeToString(BeanLibrary.serializer(), lib) }.getOrNull()
    }

    /** Library in Beanconqueror's format (via the core). */
    fun beansBeanconquerorJson(): String? {
        val beans = _ui.value.beans
        if (beans.isEmpty()) { notifyUser("Export — no beans"); return null }
        val beansJson = runCatching { json.encodeToString(ListSerializer(Bean.serializer()), beans) }.getOrElse { "[]" }
        val roastersJson = runCatching { json.encodeToString(ListSerializer(Roaster.serializer()), _ui.value.roasters) }.getOrElse { "[]" }
        val envelope = "{\"beans\":$beansJson,\"roasters\":$roastersJson,\"shots\":[]}"
        return runCatching { exportBeanconquerorMainJson(envelope, System.currentTimeMillis()) }
            .getOrElse { notifyUser("Beanconqueror export failed"); null }
    }

    /** Shots as Crema JSON — all (ids null) or only the given ids. */
    fun shotsJson(ids: List<String>?): String? {
        val shots = if (ids == null) _ui.value.history else { val w = ids.toHashSet(); _ui.value.history.filter { it.id in w } }
        if (shots.isEmpty()) { notifyUser("Export — no shots"); return null }
        return runCatching { json.encodeToString(ListSerializer(StoredShot.serializer()), shots) }.getOrNull()
    }

    /** All profiles (built-ins + customs) as a JSON array. */
    fun allProfilesJson(): String? {
        val all = _ui.value.profiles.mapNotNull { profileJsonById[it.id] }
        if (all.isEmpty()) { notifyUser("Export — no profiles"); return null }
        return "[${all.joinToString(",")}]"
    }

    /** Write export [text] to a SAF document Uri (large-payload safe). */
    fun writeTextToUri(uri: Uri, text: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    true
                }.getOrDefault(false)
            }
            notifyUser(if (ok) "Exported to file" else "Export failed — couldn't write")
        }
    }

    /** Read a content Uri as UTF-8 text, off the main thread. */
    private suspend fun readUriText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
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

    /** Import profiles from a Crema .json export (array / single object / .jsonl).
     *  Each becomes a new custom profile. */
    fun importProfiles(uri: Uri) {
        viewModelScope.launch {
            val text = readUriText(uri)?.trim()
            if (text.isNullOrBlank()) { notifyUser("Import failed — couldn't read that file"); return@launch }
            val elements = runCatching {
                when {
                    text.startsWith("[") -> json.parseToJsonElement(text).jsonArray.map { it.toString() }
                    text.startsWith("{") -> listOf(text)
                    else -> text.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.toList()
                }
            }.getOrElse { emptyList() }
            val adopted = elements.mapNotNull { adoptProfileJson(it) }
            if (adopted.isEmpty()) { notifyUser("No profiles imported — unrecognised file"); return@launch }
            customProfilesJson = adopted + customProfilesJson
            refreshProfiles()
            persistCustomProfiles()
            notifyUser("Imported ${adopted.size} profile(s)")
        }
    }

    /** Import shots from a Crema .json export (array / single / .jsonl), skipping
     *  ids already in history. */
    fun importShots(uri: Uri) {
        viewModelScope.launch {
            val text = readUriText(uri)?.trim()
            if (text.isNullOrBlank()) { notifyUser("Import failed — couldn't read that file"); return@launch }
            val shots = runCatching {
                if (text.startsWith("[")) json.decodeFromString(ListSerializer(StoredShot.serializer()), text)
                else listOf(json.decodeFromString(StoredShot.serializer(), text))
            }.getOrElse {
                runCatching {
                    text.lineSequence().map { it.trim() }.filter { it.startsWith("{") }
                        .map { json.decodeFromString(StoredShot.serializer(), it) }.toList()
                }.getOrElse { emptyList() }
            }
            if (shots.isEmpty()) { notifyUser("No shots imported — unrecognised file"); return@launch }
            val existing = _ui.value.history.map { it.id }.toHashSet()
            val toAdd = shots.filterNot { it.id in existing }
            val next = toAdd + _ui.value.history
            _ui.update { it.copy(history = next) }
            notifyUser("Imported ${toAdd.size} shot(s)")
            historyStore.save(next)
        }
    }

    /** Remove a stored shot from the local history. Persisted. */
    fun deleteShot(id: String) {
        val next = _ui.value.history.filterNot { it.id == id }
        _ui.update { it.copy(history = next) }
        viewModelScope.launch { historyStore.save(next) }
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

    /** Load a history shot's profile onto Brew (find by name → set active). */
    fun loadProfileOnBrew(profileName: String?) {
        val name = profileName ?: return
        val match = _ui.value.profiles.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: run { notifyUser("Profile \u201c$name\u201d isn\u2019t in your library"); return }
        setActiveProfile(match.id)
        notifyUser("Loaded \u201c${match.name}\u201d on Brew")
    }

    /** Fire an ACTION_SEND chooser with [text] (application/json). Best-effort. */
    private fun shareText(text: String, title: String) {
        val ctx = getApplication<Application>()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, title)
        }
        val chooser = Intent.createChooser(send, title).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { ctx.startActivity(chooser) }.onFailure { appendLog("Share failed: ${it.message}") }
    }

    /** Start steaming. */
    fun steam() = requestMachineState(MachineRequest.STEAM)

    /** Start hot water. */
    fun hotWater() = requestMachineState(MachineRequest.HOT_WATER)

    /** Start a group-head flush / rinse. */
    fun flush() = requestMachineState(MachineRequest.FLUSH)

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
    fun startShot() {
        val cremaJson = _ui.value.activeProfileId?.let { profileJsonById[it] }
        if (cremaJson == null) {
            notifyUser("Can\u2019t start shot \u2014 no profile selected")
            return
        }
        // Quick-Controls override: bake the transient dose/yield/brew-temp into the
        // uploaded profile (the web shell's lazy re-upload-with-overrides). The
        // library profile is untouched; only this one upload carries the override.
        val bp = _ui.value.brewParams
        val effectiveJson = if (bp != null) {
            runCatching { overrideBrewParamsJson(cremaJson, bp.dose.toFloat(), bp.yieldOut.toFloat(), bp.brewTemp.toFloat(), json) }
                .getOrDefault(cremaJson)
        } else {
            cremaJson
        }
        // Issue 11: if the connected DE1 already holds this exact effective profile
        // (fingerprint match), skip the redundant upload and go straight to the
        // gated Espresso request. A null fingerprint (hash failure) ⇒ never skip.
        val fingerprint = runCatching { profileFingerprint(effectiveJson, null) }.getOrNull()
        if (shouldSkipProfileUpload(
                fingerprint,
                lastUploadedFingerprint,
                _ui.value.bleState == De1BleManager.State.READY,
            )
        ) {
            appendLog("Profile unchanged on DE1 (fingerprint match) — skipping re-upload")
            viewModelScope.launch {
                delay(PROFILE_DOWNLOAD_GUARD_MS)
                requestMachineState(MachineRequest.ESPRESSO)
            }
            return
        }
        pendingUploadFingerprint = fingerprint
        val wireJson = runCatching { cremaProfileToWire(effectiveJson) }.getOrElse {
            notifyUser("Can\u2019t start shot \u2014 profile conversion failed")
            return
        }
        val raw = runCatching {
            bridge.uploadProfile(wireJson, System.currentTimeMillis().toULong())
        }.getOrElse {
            notifyUser("Can\u2019t start shot \u2014 profile upload failed")
            return
        }
        pendingBrew = true
        _ui.update { it.copy(profileUploading = true, profileUploadProgress = null) }
        onCoreOutputJson(raw)
        // Backstop: if no ProfileUploadCompleted arrives, clear the pending brew
        // so the UI doesn't hang in "uploading" forever.
        viewModelScope.launch {
            delay(PROFILE_UPLOAD_TIMEOUT_MS)
            if (pendingBrew) {
                pendingBrew = false
                pendingUploadFingerprint = null
                _ui.update { it.copy(profileUploading = false, profileUploadProgress = null) }
                notifyUser("Profile upload timed out \u2014 shot not started")
            }
        }
    }

    /** Called from the UI once the BLE runtime permissions have been granted. */
    fun connect() {
        _ui.update { it.copy(eventLog = emptyList()) }
        // Show "scanning" on the connection-status UI while the shared scanner
        // hunts; the scanner's onFound hands the matched DE1 to ble.connect.
        ble.markScanning()
        bleScanner.scanFor(SCAN_LABEL_DE1, De1BleManager::isDe1Name) { device, _ ->
            ble.connect(device)
        }
        // The manager's state flow is collected in `init`; markScanning() above
        // already pushed the new state, so no manual reflection is needed here.
    }

    fun disconnect() {
        // Drop any outstanding scan want too — the user may disconnect mid-scan.
        bleScanner.cancel(SCAN_LABEL_DE1)
        ble.disconnect()
        pendingBrew = false
        _ui.update { it.copy(
            bleState = De1BleManager.State.DISCONNECTED,
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
            lineFreqHz = null,
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
    private fun requestMachineState(state: MachineRequest) {
        val raw = runCatching { bridge.requestMachineState(state) }.getOrElse {
            appendLog("requestMachineState failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /** Wake the DE1 / return to idle (also stops a running shot). */
    fun wake() = requestMachineState(MachineRequest.IDLE)

    /** Put the DE1 to sleep. */
    fun sleep() = requestMachineState(MachineRequest.SLEEP)

    /** Start an espresso shot. */
    fun startEspresso() = requestMachineState(MachineRequest.ESPRESSO)

    /** Stop a running shot (return to idle). */
    fun stopShot() = requestMachineState(MachineRequest.IDLE)

    /**
     * Read the DE1's identity + service registers once a connection is READY.
     * Each [CremaBridge.readMmr] emits a `Command.WriteCharacteristic` read
     * request (the same shared command path the scale/state writes use); the
     * DE1 answers later on its MMR / version notify characteristics, surfacing
     * as `Event.MmrValue` / `Event.Firmware` folded into [MainUiState].
     *
     * Also folds the two pure (no-BLE) core reads — the firmware-update status
     * and the resolved line frequency — straight into the snapshot. Safe to
     * call while disconnected: with no BLE link the read requests write nothing
     * and the pure reads still return; the UI just shows "—" until replies land.
     */
    private fun readMachineInfo() {
        // Connect-time register sweep: identity (firmware / board / model /
        // serial), GHC presence + mode, heater voltage, flow-calibration, and
        // refill-kit presence. Match the generated MmrReg variant spellings.
        val registers = listOf(
            MmrReg.FIRMWARE_VERSION,
            MmrReg.CPU_BOARD_VERSION,
            MmrReg.MACHINE_MODEL,
            MmrReg.SERIAL_NUMBER,
            MmrReg.GHC_INFO,
            MmrReg.GHC_MODE,
            MmrReg.HEATER_VOLTAGE,
            MmrReg.CALIBRATION_FLOW_MULTIPLIER,
            MmrReg.REFILL_KIT,
        )
        registers.forEach { reg ->
            val raw = runCatching { bridge.readMmr(reg) }.getOrElse {
                appendLog("Read MMR $reg failed: ${it.message}")
                return@forEach
            }
            onCoreOutputJson(raw)
        }
        // Pure reads — no BLE traffic, safe anytime. firmwareUpdateStatus() is
        // a JSON FirmwareUpdateStatus (logged for now; the de1Firmware label
        // drives the Settings firmware row). lineFrequencyHz() resolves the
        // mains frequency the core is using.
        runCatching { bridge.firmwareUpdateStatus() }.onSuccess { status ->
            appendLog("Firmware update status: $status")
        }.onFailure { appendLog("Firmware update status failed: ${it.message}") }
        val freq = runCatching { bridge.lineFrequencyHz() }.getOrNull()
        _ui.update { it.copy(lineFreqHz = freq) }
    }

    // ── Settings: machine setters (Settings → Machine / Advanced / Service) ────
    // Thin VM wrappers so the Settings screen calls the VM (matching the
    // theme / scale-config pattern) rather than the bridge directly. Reads /
    // setters are safe while disconnected — the bridge returns a command JSON
    // and with no BLE link nothing is written.

    /**
     * Enable / disable the Group Head Controller (start-shots-from-the-machine).
     * Routes the resulting MMR write through the shared command path. Re-reads
     * GHC_MODE so the row reflects the machine's confirmed state.
     */
    fun setGhcMode(enabled: Boolean) {
        // setGhcMode takes a UByte. A CONST `1.toUByte()` trips the const-eval
        // backend bug (Unknown function: toUByte(kotlin.Int)) — derive the Int
        // from the runtime `enabled` first so `.toUByte()` is a runtime call.
        val modeInt = if (enabled) 1 else 0
        val mode: UByte = modeInt.toUByte()
        val raw = runCatching { bridge.setGhcMode(mode) }.getOrElse {
            appendLog("Set GHC mode failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
        // Optimistically reflect the write so the toggle tracks immediately;
        // the GhcMode register reply (re-read below) then confirms it.
        _ui.update { it.copy(
            de1MachineInfo = it.de1MachineInfo + (MmrRegister.GhcMode to (if (enabled) 1u else 0u)),
        ) }
        runCatching { bridge.readMmr(MmrReg.GHC_MODE) }.getOrNull()?.let(::onCoreOutputJson)
    }

    /**
     * Set the mains heater voltage. The core @Throws unless [volts] is 120 or
     * 230, so callers (the Settings segmented control behind a confirm) must
     * only pass those — guarded here too so an out-of-range value is a no-op.
     */
    fun setHeaterVoltage(volts: Int) {
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

    /**
     * Override the AC mains frequency. The core @Throws unless [hz] is 0.0
     * (auto-detect), 50.0, or 60.0 — guarded here so a bad value is a no-op.
     * Returns Unit (no BLE write); the resolved value is re-read into
     * [MainUiState.lineFreqHz].
     */
    fun setLineFrequency(hz: Float) {
        if (hz != 0.0f && hz != 50.0f && hz != 60.0f) {
            appendLog("Line frequency $hz ignored (only 0 / 50 / 60 allowed)")
            return
        }
        runCatching { bridge.setLineFrequencyOverride(hz) }.onFailure {
            appendLog("Set line frequency failed: ${it.message}")
            return
        }
        val resolved = runCatching { bridge.lineFrequencyHz() }.getOrNull() ?: hz
        _ui.update { it.copy(lineFreqHz = resolved) }
    }

    /**
     * Set the maximum shot duration, seconds (null = clear the cap). Returns
     * Unit (a sans-IO core setting); no event echo.
     */
    fun setMaxShotDuration(seconds: Float?) {
        runCatching { bridge.setMaxShotDuration(seconds) }.onFailure {
            appendLog("Set max shot duration failed: ${it.message}")
        }
        if (seconds != null) {
            _ui.update { it.copy(maxShotDurationS = seconds) }
            viewModelScope.launch { settingsStore.save(currentPrefs()) }
        }
    }

    /** Build a full AppPrefs from the current UI state so each setter persists
     *  without clobbering the other fields. */
    private fun currentPrefs() = AppPrefs(
        themeMode = _ui.value.themeMode,
        maxShotDurationS = _ui.value.maxShotDurationS,
        autoTare = _ui.value.autoTare,
        stopOnWeight = _ui.value.stopOnWeight,
        steamEco = _ui.value.steamEco,
        preFlush = _ui.value.preFlush,
        steamPurge = _ui.value.steamPurge,
        chartChannels = _ui.value.chartChannels,
        keepScreenOnBrew = _ui.value.keepScreenOnBrew,
        grinderModel = _ui.value.grinderModel,
        suppressDe1Sleep = _ui.value.suppressDe1Sleep,
        showDebugPanel = _ui.value.showDebugPanel,
        defaultDoseG = _ui.value.defaultDoseG,
        defaultRatio = _ui.value.defaultRatio,
        defaultBrewTempC = _ui.value.defaultBrewTempC,
        defaultPreinfuseS = _ui.value.defaultPreinfuseS,
        activeProfileId = _ui.value.activeProfileId,
    )

    /** True once [loadPrefs] has hydrated the UI from disk — writes before that
     *  would snapshot half-initialised state and clobber the saved prefs (e.g.
     *  erase the restored activeProfileId during boot). */
    @Volatile private var prefsLoaded = false

    /** Persist the current prefs snapshot (best-effort, off the main thread).
     *  No-op until the initial [loadPrefs] hydration completes. */
    private fun persistPrefs() {
        if (!prefsLoaded) return
        val snapshot = currentPrefs()
        viewModelScope.launch { settingsStore.save(snapshot) }
    }

    /** Queue a user-facing message (snackbar) + keep it in the log. */
    private fun notifyUser(message: String) {
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
        val raw = runCatching { bridge.setCalibrationFlowMultiplier(multiplier) }.getOrElse {
            appendLog("Set flow multiplier failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
        runCatching { bridge.readMmr(MmrReg.CALIBRATION_FLOW_MULTIPLIER) }.getOrNull()?.let(::onCoreOutputJson)
    }

    /** Start a descale cycle (Settings → Water → "Run now"). */
    fun startDescale() = requestMachineState(MachineRequest.DESCALE)

    /** Start a cleaning cycle (Settings → Water → "Run now"). */
    fun startClean() = requestMachineState(MachineRequest.CLEAN)

    /** Enable/disable auto-tare at shot start (Quick Controls). Optimistic. Persisted. */
    fun setAutoTare(enabled: Boolean) {
        _ui.update { it.copy(autoTare = enabled) }
        runCatching { bridge.setAutoTare(enabled) }.onFailure {
            appendLog("Set auto-tare failed: ${it.message}")
        }
        persistPrefs()
    }

    /**
     * Enable/disable stop-on-weight (Quick Controls). The yield target comes from
     * the active profile; this just arms/disarms the behaviour. Optimistic.
     */
    fun setStopOnWeight(enabled: Boolean) {
        _ui.update { it.copy(stopOnWeight = enabled) }
        runCatching { bridge.setStopOnWeight(enabled) }.onFailure {
            appendLog("Set stop-on-weight failed: ${it.message}")
        }
        persistPrefs()
    }

    /**
     * Enable/disable steam eco mode (Quick Controls). Returns a `CoreOutput` (an
     * MMR write) routed through the shared command path. Optimistic.
     */
    fun setSteamEco(enabled: Boolean) {
        _ui.update { it.copy(steamEco = enabled) }
        persistPrefs()
        val raw = runCatching {
            bridge.enableSteamEcoMode(enabled, System.currentTimeMillis().toULong())
        }.getOrElse {
            appendLog("Set steam eco failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /** Show/hide a live-chart channel (Quick Controls). Persisted display pref. */
    fun toggleChartChannel(key: String, enabled: Boolean) {
        val next = _ui.value.chartChannels.toMutableSet().apply {
            if (enabled) add(key) else remove(key)
        }
        _ui.update { it.copy(chartChannels = next) }
        persistPrefs()
    }

    /** Persist the pre-shot flush preference (consumed by a later shot-sequence pass). */
    fun setPreFlush(enabled: Boolean) {
        _ui.update { it.copy(preFlush = enabled) }
        persistPrefs()
    }

    /** Persist the post-steam purge preference (consumed by a later shot-sequence pass). */
    fun setSteamPurge(enabled: Boolean) {
        _ui.update { it.copy(steamPurge = enabled) }
        persistPrefs()
    }

    /** Keep the screen awake while a shot pulls (Settings → Display). Persisted. */
    fun setKeepScreenOnBrew(enabled: Boolean) {
        _ui.update { it.copy(keepScreenOnBrew = enabled) }
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

    // ── Bean library ────────────────────────────────────────────────────────

    /** Load the persisted bean library into the UI snapshot at startup. */
    private suspend fun loadLibrary() {
        val lib = library.load()
        _ui.update { it.copy(
            beans = lib.beans,
            roasters = lib.roasters,
            activeBeanId = lib.activeBeanId ?: lib.beans.firstOrNull()?.id,
        ) }
    }

    /** Persist the current bean library (beans + roasters + active selection). */
    private fun persistLibrary() {
        val s = _ui.value
        val lib = BeanLibrary(s.beans, s.roasters, s.activeBeanId)
        viewModelScope.launch { library.save(lib) }
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
        viewModelScope.launch {
            val merged = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)
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
                notifyUser("Import failed — couldn't read that file")
                return@launch
            }
            val planJson = runCatching { importBeanconquerorJson(merged, System.currentTimeMillis()) }
                .getOrElse {
                    appendLog("Beanconqueror import failed: ${it.message}")
                    notifyUser("Import failed — not a Beanconqueror export")
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

        val s = _ui.value
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
                libRoaster != null && wireRoaster != null && wireRoaster.id != libRoaster.id -> bean.copy(roasterId = libRoaster.id)
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
            notifyUser(if (skipped > 0) "Already imported — nothing new to add" else "No beans found in that file")
            return
        }
        _ui.value = s.copy(
            beans = s.beans + newBeans,
            roasters = s.roasters + newRoasters,
            activeBeanId = s.activeBeanId ?: newBeans.firstOrNull()?.id,
        )
        persistLibrary()
        val bn = newBeans.size; val rn = newRoasters.size
        _ui.update { it.copy(
            status = "Imported $bn bean${if (bn == 1) "" else "s"} · $rn roaster${if (rn == 1) "" else "s"}",
        ) }
    }

    /** Open a bean in the editor (the `bean-edit` route reads this). */
    fun startEditBean(id: String) {
        _ui.update { it.copy(editingBeanId = id, draftBean = null) }
    }

    /** Start a brand-new bean in the full editor — stash a blank draft and point
     *  the editor at it. Added to the library only on Save (via [updateBean]). */
    fun startNewBean() {
        val draft = newBean(name = "", roasterId = null, roastLevel = null, roastedOn = null, nowMs = System.currentTimeMillis())
        _ui.update { it.copy(draftBean = draft, editingBeanId = draft.id) }
    }

    /**
     * Apply edits to an existing bean (the editor's Save). Resolves the roaster
     * by name (find-or-create), stamps `updatedAt`, and persists.
     */
    fun updateBean(id: String, roasterName: String, transform: (Bean) -> Bean) {
        val s = _ui.value
        // Resolve from the library, or from a new-bean draft (the "Add bean" →
        // full-editor Save path — the bean isn't in [beans] yet).
        val existing = s.beans.firstOrNull { it.id == id } ?: s.draftBean?.takeIf { it.id == id } ?: return
        val isNew = s.beans.none { it.id == id }
        val now = System.currentTimeMillis()
        val (roasterId, roasters) = resolveRoaster(roasterName, s.roasters, now)
        // The shell owns the full field mapping (the core Bean already models
        // every field); we only resolve the roaster + stamp updatedAt here.
        val updated = transform(existing).copy(roasterId = roasterId, updatedAt = now)
        _ui.update { it.copy(
            beans = if (isNew) s.beans + updated else s.beans.map { if (it.id == id) updated else it },
            roasters = roasters,
            draftBean = null,
        ) }
        persistLibrary()
    }

    /** Set (or with `null`, clear) the active bean (the Brew bean block). Persisted. */
    fun setActiveBean(id: String?) {
        _ui.update { it.copy(activeBeanId = id) }
        persistLibrary()
        // Linked-profile auto-load (web activateBean parity). Only user acts
        // reach this fun — boot/library restore writes activeBeanId directly —
        // so firing here matches the "explicit activation only" rule.
        if (id != null) maybeLoadLinkedProfile(id)
    }

    /**
     * Fire the bean's linked-profile auto-load: no-op when unset or already
     * active; dangling link → notify; shot in progress → skip (profile
     * activation reseeds brew params — not mid-extraction); else activate
     * the profile with a toast naming the link.
     */
    private fun maybeLoadLinkedProfile(beanId: String) {
        val s = _ui.value
        val bean = s.beans.firstOrNull { it.id == beanId } ?: return
        val linkedId = bean.linkedProfileId ?: return
        if (s.activeProfileId == linkedId) return
        val profile = s.profiles.firstOrNull { it.id == linkedId }
        if (profile == null) {
            notifyUser("${bean.name}’s linked profile no longer exists")
            return
        }
        if (s.shotInProgress) {
            notifyUser("Shot in progress — “${profile.name}” not loaded")
            return
        }
        setActiveProfile(linkedId)
        notifyUser("Loaded “${profile.name}” — linked to ${bean.name}")
    }

    /** Remove a bean bag; reselect the first remaining if it was active. Persisted. */
    fun deleteBean(id: String) {
        val remaining = _ui.value.beans.filterNot { it.id == id }
        val wasActive = _ui.value.activeBeanId == id
        _ui.update { it.copy(
            beans = remaining,
            activeBeanId = if (wasActive) remaining.firstOrNull()?.id else it.activeBeanId,
        ) }
        persistLibrary()
    }

    /** Duplicate a bag into a fresh row (new id, "name (copy)", not favourite). Persisted. */
    fun duplicateBean(id: String) {
        val s = _ui.value
        val base = s.beans.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        val copy = base.copy(
            id = "bean:" + java.util.UUID.randomUUID(),
            name = "${base.name} (copy)",
            favourite = false,
            createdAt = now,
            updatedAt = now,
        )
        _ui.value = s.copy(beans = s.beans + copy)
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
    fun toggleBeanFavourite(id: String) = mutateBean(id) { it.copy(favourite = !it.favourite) }

    /** Map a single bean through [transform], stamp `updatedAt`, persist. */
    private fun mutateBean(id: String, transform: (Bean) -> Bean) {
        val s = _ui.value
        val now = System.currentTimeMillis()
        _ui.value = s.copy(beans = s.beans.map { if (it.id == id) transform(it).copy(updatedAt = now) else it })
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
        _ui.update { it.copy(editingRoasterId = id) }
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
        _ui.update { it.copy(roasters = it.roasters + r) }
        persistLibrary()
    }

    /** Update a roaster's editable fields. Persisted. */
    fun updateRoaster(id: String, name: String, website: String?, city: String?, country: String?, notes: String) {
        if (name.isBlank()) return
        val now = System.currentTimeMillis()
        _ui.update { it.copy(
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
        val s = _ui.value
        _ui.value = s.copy(
            roasters = s.roasters.filterNot { it.id == id },
            beans = s.beans.map { if (it.roasterId == id) it.copy(roasterId = null) else it },
        )
        persistLibrary()
    }

    /** Open a roaster's website in the browser (best-effort; prepends https:// if bare). */
    fun visitRoasterWebsite(url: String?) {
        val u = url?.takeIf { it.isNotBlank() } ?: return
        val full = if (u.startsWith("http", ignoreCase = true)) u else "https://$u"
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(full)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { getApplication<Application>().startActivity(intent) }.onFailure { appendLog("Visit failed: ${it.message}") }
    }

    // ── Shot history ──────────────────────────────────────────────────────────

    /** Load the persisted shot log at startup. */
    private suspend fun loadHistory() {
        _ui.update { it.copy(history = historyStore.load()) }
    }

    /**
     * Capture a completed shot into the log (newest first, capped) and persist.
     * Profile / bean names come from the active selections at completion. v1
     * stores a summary only — the telemetry slice for the detail chart is a
     * later increment.
     */
    private fun captureCompletedShot(
        durationMs: Long,
        yieldG: Float?,
        peakPressure: Float?,
        peakTemp: Float?,
        now: Long = System.currentTimeMillis(),
    ) {
        val s = _ui.value
        val profile = s.profiles.firstOrNull { it.id == s.activeProfileId }
        val bean = s.beans.firstOrNull { it.id == s.activeBeanId }
        val roasterName = bean?.roasterId?.let { rid -> s.roasters.firstOrNull { it.id == rid }?.name }
        val beanName = bean?.let { b -> listOfNotNull(roasterName, b.name).joinToString(" · ") }
        // Freeze a full bean snapshot (the core wire shape) at shot time so the
        // Visualizer wire carries roaster / roast date / roast level — issue 06.
        val beanSnapshot = bean?.let { b ->
            ShotBean(
                beanId = b.id,
                name = b.name,
                roasterName = roasterName,
                roastedOn = b.roastedOn,
                roastLevel = b.roastLevel,
                tags = b.tags,
                grinderSetting = b.grinderSetting.takeIf { it.isNotBlank() },
            )
        }
        val shot = StoredShot(
            id = "shot:$now",
            completedAtMs = now,
            durationMs = durationMs,
            yieldG = yieldG,
            doseG = profile?.dose,
            peakPressure = peakPressure,
            peakTemp = peakTemp,
            profileName = profile?.name,
            beanName = beanName,
            bean = beanSnapshot,
            // The buffer still holds the just-finished shot (cleared on the next
            // ShotStarted); downsample it for the detail chart.
            samples = downsampleForStorage(s.shotTelemetry),
        )
        val next = (listOf(shot) + s.history).take(HistoryStore.MAX_SHOTS)
        _ui.update { it.copy(history = next) }
        viewModelScope.launch { historyStore.save(next) }
        // Auto-sync: push the fresh shot to Visualizer when armed + signed in.
        visualizer.maybeAutoUpload(shot)
    }

    /** Apply a user rating / tasting-notes edit to a logged shot. Persisted.
     *  An already-uploaded shot mirrors the edit to Visualizer (soft). */
    fun updateShot(id: String, rating: Int, notes: String) {
        _ui.update { st ->
            st.copy(
                history = st.history.map {
                    if (it.id == id) it.copy(rating = rating.coerceIn(0, 5).takeIf { r -> r > 0 }, notes = notes.ifBlank { null }) else it
                },
            )
        }
        val snapshot = _ui.value.history
        viewModelScope.launch { historyStore.save(snapshot) }
        schedulePatchEdited(id)
    }

    /**
     * Set (or with null, clear) a shot's per-upload Visualizer privacy
     * override. Persisted; mirrors to the uploaded copy like [updateShot].
     */
    fun setShotPrivacy(id: String, privacy: String?) {
        _ui.update { st ->
            st.copy(history = st.history.map { if (it.id == id) it.copy(privacy = privacy) else it })
        }
        val snapshot = _ui.value.history
        viewModelScope.launch { historyStore.save(snapshot) }
        schedulePatchEdited(id)
    }

    /** Debounced edit→Visualizer mirror: the notes field fires per keystroke
     *  and star taps come in bursts — one PATCH goes out 1.5 s after the last
     *  edit, with the shot's then-current state. */
    private var shotPatchJob: kotlinx.coroutines.Job? = null
    private fun schedulePatchEdited(id: String) {
        shotPatchJob?.cancel()
        shotPatchJob = viewModelScope.launch {
            delay(1_500)
            _ui.value.history.firstOrNull { it.id == id }?.let { visualizer.patchEditedShot(it) }
        }
    }

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
        maintenanceTotalLitres = state.totalLitres
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
     * Integrate one telemetry sample into [maintenanceTotalLitres]. [flowMlPerS]
     * is the DE1's group flow; the wall-clock Δ since the previous sample is
     * derived from [nowMs] and [lastTelemetryMs]. Mirrors the web store's clamps:
     * the Δ is capped at [MAINTENANCE_MAX_SAMPLE_DT_S] (ignore between-session
     * gaps), only positive flow accumulates, and an implausibly large per-sample
     * volume (> [MAINTENANCE_MAX_SAMPLE_ML]) is dropped. Does NOT persist or
     * recompute the readout (that happens at the completion boundaries).
     */
    private fun accumulateMaintenance(flowMlPerS: Float, nowMs: Long) {
        val prevMs = lastTelemetryMs
        lastTelemetryMs = nowMs
        if (prevMs == null) return
        val dtSeconds = (nowMs - prevMs) / 1000.0
        if (dtSeconds <= 0.0 || flowMlPerS <= 0f) return
        val cappedDt = dtSeconds.coerceAtMost(MAINTENANCE_MAX_SAMPLE_DT_S)
        val ml = flowMlPerS.toDouble() * cappedDt
        if (ml <= 0.0 || ml > MAINTENANCE_MAX_SAMPLE_ML) return
        maintenanceTotalLitres += ml / 1000.0
    }

    /**
     * Mark the water filter cleaned — rebaseline its litre counter to the current
     * total and stamp `filterAtMs` (the web store's `markFilterCleaned`). Persists
     * + recomputes the readout.
     */
    fun markFilterCleaned() {
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
    fun markDescaled() {
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
    fun markCleaned() {
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
            steamEco = p.steamEco,
            preFlush = p.preFlush,
            steamPurge = p.steamPurge,
            chartChannels = p.chartChannels,
            keepScreenOnBrew = p.keepScreenOnBrew,
            grinderModel = p.grinderModel,
            suppressDe1Sleep = p.suppressDe1Sleep,
            showDebugPanel = p.showDebugPanel,
            defaultDoseG = p.defaultDoseG,
            defaultRatio = p.defaultRatio,
            defaultBrewTempC = p.defaultBrewTempC,
            defaultPreinfuseS = p.defaultPreinfuseS,
            // Restore the last session's profile selection; refreshProfiles
            // self-heals if the id no longer resolves (e.g. deleted custom).
            activeProfileId = p.activeProfileId ?: it.activeProfileId,
        ) }
        // Push the persisted cap + behaviour toggles to the core so they're live
        // from launch (the core doesn't echo these back as events).
        runCatching { bridge.setMaxShotDuration(p.maxShotDurationS) }
        runCatching { bridge.setAutoTare(p.autoTare) }
        runCatching { bridge.setStopOnWeight(p.stopOnWeight) }
        prefsLoaded = true
    }

    /** Equipment-level grinder model (free text). Persisted; rides Visualizer
     *  uploads/patches as `grinder_model`. */
    fun setGrinderModel(model: String) {
        _ui.update { it.copy(grinderModel = model) }
        persistPrefs()
    }

    /** Keep the DE1 awake while Crema is open (web `suppressDe1Sleep`). The
     *  [de1KeepAlive] heartbeat consumes this; an enable pokes it immediately. */
    fun setSuppressDe1Sleep(on: Boolean) {
        _ui.update { it.copy(suppressDe1Sleep = on) }
        persistPrefs()
        if (on) pokeUserPresent()
    }

    /** One UserPresent write (MMR 0x803858) — resets the DE1's sleep timer. */
    private fun pokeUserPresent() {
        if (_ui.value.bleState != De1BleManager.State.READY) return
        runCatching { bridge.setUserPresent(true) }.getOrNull()?.let(::onCoreOutputJson)
    }

    /** The keep-awake heartbeat: while connected with the pref on, rewrite
     *  UserPresent every 60 s (the DE1's sleep timer is minutes-scale, so a
     *  minute cadence keeps it pinned without chattering the bus). */
    private fun startDe1KeepAlive() {
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                if (_ui.value.suppressDe1Sleep) pokeUserPresent()
            }
        }
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

    /** Start a steam-rinse cycle (Settings → Water → "Run now"). */
    fun startSteamRinse() = requestMachineState(MachineRequest.STEAM_RINSE)

    /** Set the Bengle cup-warmer plate temperature (0–80 °C) and re-read the
     *  register so the UI reflects what the machine accepted. */
    fun setCupWarmerTemp(tempC: Int) {
        val raw = runCatching { bridge.setCupWarmerTemperature(tempC.coerceIn(0, 80).toUByte()) }.getOrElse {
            appendLog("Set cup warmer temp failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
        runCatching { bridge.readMmr(MmrReg.CUP_WARMER_TEMP) }.getOrNull()?.let(::onCoreOutputJson)
    }

    /** Show/hide the inline debug panel (Settings → Advanced). Persisted. */
    fun setShowDebugPanel(on: Boolean) {
        _ui.update { it.copy(showDebugPanel = on) }
        persistPrefs()
    }

    /** Set the theme mode (`"system"` / `"light"` / `"dark"`) and persist. */
    fun setThemeMode(mode: String) {
        _ui.update { it.copy(themeMode = mode) }
        persistPrefs()
    }

    /** Reset Crema's preferences (theme, max shot duration) to defaults. Persisted. */
    fun resetPreferences() {
        val def = AppPrefs()
        viewModelScope.launch { settingsStore.save(def) }
        _ui.update { it.copy(
            themeMode = def.themeMode,
            maxShotDurationS = def.maxShotDurationS,
            autoTare = def.autoTare,
            stopOnWeight = def.stopOnWeight,
            steamEco = def.steamEco,
            preFlush = def.preFlush,
            steamPurge = def.steamPurge,
            chartChannels = def.chartChannels,
            keepScreenOnBrew = def.keepScreenOnBrew,
            defaultDoseG = def.defaultDoseG,
            defaultRatio = def.defaultRatio,
            defaultBrewTempC = def.defaultBrewTempC,
            defaultPreinfuseS = def.defaultPreinfuseS,
        ) }
        runCatching { bridge.setMaxShotDuration(def.maxShotDurationS) }
    }

    /**
     * Erase ALL user data — bean library, roasters, shot history, custom
     * profiles, and preferences. Built-in profiles remain (they aren't user
     * data); the active profile reseeds to the first built-in via
     * [refreshProfiles]. Every store is cleared on disk too. Irreversible.
     */
    fun eraseAll() {
        customProfilesJson = emptyList()
        // Reset the maintenance counters too — a fresh-install state (baselines and
        // total zeroed, the three *AtMs = now so the readouts read "0 since").
        val freshMaintenance = defaultMaintenanceState(System.currentTimeMillis())
        maintenanceTotalLitres = 0.0
        lastTelemetryMs = null
        viewModelScope.launch {
            library.save(BeanLibrary())
            historyStore.save(emptyList())
            customProfileStore.save(emptyList())
            settingsStore.save(AppPrefs())
            maintenanceStore.save(freshMaintenance)
        }
        _ui.update { it.copy(
            beans = emptyList(),
            roasters = emptyList(),
            activeBeanId = null,
            history = emptyList(),
            activeProfileId = null,
            maintenance = freshMaintenance,
            maintenanceReadout = computeMaintenanceReadout(freshMaintenance),
            themeMode = AppPrefs().themeMode,
        ) }
        refreshProfiles()
    }

    /** Scan for and connect to a Bookoo scale. Independent of the DE1. */
    fun connectScale() {
        scale.markScanning()
        bleScanner.scanFor(SCAN_LABEL_SCALE, ScaleBleManager::isBookooName) { device, name ->
            scale.connect(device, name)
        }
    }

    fun disconnectScale() {
        bleScanner.cancel(SCAN_LABEL_SCALE)
        scale.disconnect()
        _ui.update { it.copy(
            scaleState = ScaleBleManager.State.DISCONNECTED,
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
    private fun refreshScaleCapabilities(advertisedName: String) {
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

    /**
     * Enable or disable the connected scale's flow smoothing. Asks the core for
     * the command bytes, then routes the resulting `Command.WriteScale` through
     * the shared command path. Capability-gated: the core emits a command only
     * for a scale that supports flow smoothing. The UI's shown toggle is
     * updated optimistically; the live `Event.ScaleReading.device_flow_smoothing`
     * stream then catches up and confirms the scale's real state.
     */
    fun setScaleFlowSmoothing(enabled: Boolean) {
        _ui.update { it.copy(scaleFlowSmoothing = enabled) }
        val raw = runCatching {
            bridge.setScaleFlowSmoothing(enabled)
        }.getOrElse {
            appendLog("Set flow smoothing failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /**
     * Enable or disable the connected scale's anti-mistouch. Asks the core for
     * the command bytes, then routes the resulting `Command.WriteScale` through
     * the shared command path. Capability-gated: the core emits a command only
     * for a scale that supports anti-mistouch. The UI's shown toggle is updated
     * optimistically (the value is not read back).
     */
    fun setScaleAntiMistouch(enabled: Boolean) {
        _ui.update { it.copy(scaleAntiMistouch = enabled) }
        val raw = runCatching {
            bridge.setScaleAntiMistouch(enabled)
        }.getOrElse {
            appendLog("Set anti-mistouch failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
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
        _ui.update { it.copy(scaleActiveMode = modeId) }
        val raw = runCatching {
            bridge.setScaleMode(modeId.toUByte())
        }.getOrElse {
            appendLog("Set mode failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
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
        _ui.update { it.copy(scaleAutoStop = modeId) }
        val raw = runCatching {
            bridge.setScaleAutoStop(modeId.toUByte())
        }.getOrElse {
            appendLog("Set auto-stop failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /**
     * Tare the connected scale. Asks the core for the tare bytes, then routes
     * the resulting `Command.WriteScale` through the shared command path so the
     * exact same code handles a manual tare and the core's auto-tare.
     */
    fun tareScale() {
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
        val output: CoreOutput = runCatching {
            json.decodeFromString(CoreOutput.serializer(), raw)
        }.getOrElse {
            appendLog("JSON parse error: ${it.message}")
            return
        }
        output.events.forEach(::applyEvent)
        output.commands.forEach(::executeCommand)
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
                viewModelScope.launch { scale.writeCommand(bytes) }
            }
            is Command.WriteCharacteristic -> {
                // The core hands the exact bytes + which DE1 characteristic to
                // write them to. The BLE manager maps the target to a UUID and
                // writes; a profile-frame write also gets a synthesized
                // De1FrameAck. This is the machine-control path (AND5).
                val bytes = command.content.data
                    .map { it.toByte() }
                    .toByteArray()
                viewModelScope.launch { ble.writeCharacteristic(command.content.target, bytes) }
            }
        }
    }

    private fun applyEvent(event: Event) {
        when (event) {
            is Event.MachineStateChanged -> {
                val c = event.content
                _ui.update { it.copy(
                    machineState = "${c.state.string} / ${c.substate.string}",
                    machineStateName = c.state.string,
                    machineSubstate = c.substate.string,
                    // Readable error copy for an error substate (null otherwise),
                    // from core so it matches web. Healthy substates clear it.
                    machineError = subStateErrorMessage(c.substate.string),
                ) }
                appendLog("MachineState -> ${c.state.string} / ${c.substate.string}")
            }
            is Event.ShotStarted -> {
                // Entering a shot: flip the resting↔extracting flag and zero the
                // per-shot counters so the Brew timer / phase / volume restart.
                // Reset the maintenance dt-tracker so the first telemetry sample of
                // this session adds no wall-clock Δ (no phantom between-session water).
                lastTelemetryMs = null
                _ui.update { it.copy(
                    shotInProgress = true,
                    shotFrame = 0,
                    shotElapsedMs = 0,
                    dispensedVolume = 0f,
                    shotTelemetry = emptyList(),
                ) }
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
                val prev = _ui.value
                // Integrate group flow into the running water total (the DE1 has no
                // cumulative counter). In-memory only — NOT persisted/recomputed per
                // sample (~25–40 Hz); flushed on shot / water-session completion.
                accumulateMaintenance(t.group_flow, System.currentTimeMillis())
                val line = "t=%dms  P=%.1fbar  flow=%.1fmL/s  head=%.1f°C".format(
                    t.elapsed.toLong(), t.group_pressure, t.group_flow, t.head_temp,
                )
                // Append a chart sample while a shot is running (the buffer fills
                // only during extraction, like the web). Fold in the latest scale
                // weight/flow so the WEIGHT curves track the cup. FIFO-capped.
                val nextBuffer = if (prev.shotInProgress) {
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
                _ui.value = prev.copy(
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
            is Event.ScaleReading -> {
                val r = event.content
                // The Bookoo echoes its live settings in every weight
                // notification. Project them into the config controls so they
                // show the scale's real current state — `device_*` is null for
                // scales that do not report a setting, in which case the
                // control keeps its last value.
                _ui.update { it.copy(
                    scaleWeightG = r.weight,
                    scaleFlowGPerS = r.device_flow,
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
                ) }
                // Weight is high-rate; do not flood the log with every reading.
            }
            is Event.WaterLevel -> {
                _ui.update { it.copy(waterLevelMm = event.content.level) }
                appendLog("Water level: %.0fmm".format(event.content.level))
            }
            is Event.StopTriggered ->
                appendLog("Auto-stop: ${event.content.reason.string}")
            is Event.ShotCompleted -> {
                val c = event.content
                val now = System.currentTimeMillis()
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
                        // Same id captureCompletedShot stamps, so the card links to it.
                        id = "shot:$now",
                    ),
                ) }
                appendLog("Shot completed: ${c.duration}ms, ${c.sample_count} samples")
                captureCompletedShot(c.duration.toLong(), c.final_weight, c.peak_pressure, c.peak_temp, now)
                // A pour just finished: flush the integrated water into the persisted
                // maintenance state and recompute the filter / descale / clean readout.
                flushMaintenance()
            }
            is Event.WaterSessionStarted -> {
                // A hot-water pour also moves water through the group — reset the
                // dt-tracker so its first telemetry sample adds no Δ, then accumulate.
                lastTelemetryMs = null
                appendLog("Water session started: ${event.content.kind.string}")
            }
            is Event.WaterSessionCompleted -> {
                appendLog("Water session completed: ${event.content.kind.string}")
                // Flush the integrated water (same boundary as a completed shot).
                flushMaintenance()
            }
            is Event.SteamSessionStarted -> appendLog("Steam session started")
            is Event.SteamSessionCompleted ->
                appendLog("Steam session completed: ${event.content.duration}ms")
            is Event.SteamClogSuspected ->
                appendLog("Steam clog suspected: ${event.content.reason.string}")
            is Event.SteamEcoModeChanged ->
                appendLog("Steam eco mode: ${event.content.eco}")
            is Event.ScaleStale -> {
                _ui.update { it.copy(scaleWeightG = null) }
                appendLog("Scale stale")
            }
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
                _ui.update { it.copy(
                    de1MachineInfo = it.de1MachineInfo + (c.register to c.value),
                ) }
                appendLog("MMR ${c.register}: ${c.value}")
            }
            // Calibration replies (current / factory) have no settings row yet
            // (no calibration-write core method — read-only), so keep logging
            // the decoded values rather than modelling a half-wired type.
            is Event.Calibration ->
                appendLog(
                    "Calibration ${event.content.target} " +
                        "(${event.content.command}): de1=${event.content.de1_reported} " +
                        "measured=${event.content.measured}",
                )
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
                // The DE1 now holds this profile — cache its fingerprint so an
                // unchanged re-start skips the upload (issue 11).
                lastUploadedFingerprint = pendingUploadFingerprint
                pendingUploadFingerprint = null
                // Gated start: the profile is now on the DE1. Observe the
                // firmware's profile-download guard window, then request Espresso.
                if (pendingBrew) {
                    pendingBrew = false
                    viewModelScope.launch {
                        delay(PROFILE_DOWNLOAD_GUARD_MS)
                        requestMachineState(MachineRequest.ESPRESSO)
                    }
                }
            }
            is Event.ProfileUploadFailed -> {
                pendingBrew = false
                pendingUploadFingerprint = null
                _ui.update { it.copy(profileUploading = false, profileUploadProgress = null) }
                appendLog("Profile upload failed: ${event.content.reason}")
            }
            // Shot-settings-read events surface in the real screens (M5), not this
            // Phase-0 debug readout. An explicit else keeps the sealed `when`
            // exhaustive as `Event` grows.
            else -> Unit
        }
    }

    private fun appendLog(line: String) {
        val stamped = "%s  %s".format(
            android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()),
            line,
        )
        val log = (listOf(stamped) + _ui.value.eventLog).take(MAX_LOG_LINES)
        _ui.update { it.copy(eventLog = log) }
    }

    override fun onCleared() {
        super.onCleared()
        bleScanner.cancel(SCAN_LABEL_DE1)
        bleScanner.cancel(SCAN_LABEL_SCALE)
        ble.disconnect()
        scale.disconnect()
        // Close the Nordic environment last — it unregisters the Bluetooth
        // broadcast receiver and tears down the central manager. The managers'
        // disconnect() calls above are fire-and-forget coroutines; closing the
        // transport also cancels its scope, so any in-flight disconnect ends.
        bleTransport.close()
    }

    private companion object {
        const val MAX_LOG_LINES = 200

        /**
         * Wait after `ProfileUploadCompleted` before requesting Espresso — the
         * firmware's profile-download guard window. A state request inside it is
         * aborted to HeaterDown (BC 9788201734); the web uses the same 500 ms.
         */
        const val PROFILE_DOWNLOAD_GUARD_MS = 500L

        /** Abort a pending gated-start brew if the upload never completes. */
        const val PROFILE_UPLOAD_TIMEOUT_MS = 15_000L

        /** [BleScanner] want labels — one per device the app discovers. */
        const val SCAN_LABEL_DE1 = "DE1"
        const val SCAN_LABEL_SCALE = "Scale"
    }
}

/**
 * Whether [MainViewModel.startShot] can skip the profile upload (issue 11): a
 * non-null effective-profile fingerprint that matches what a still-connected DE1
 * already holds. Top-level + `internal` so it's pure and unit-testable without a
 * device (the FFI fingerprint compute and the BLE/DE1 round-trip aren't).
 */
internal fun shouldSkipProfileUpload(
    effectiveFingerprint: String?,
    lastUploaded: String?,
    de1Ready: Boolean,
): Boolean = de1Ready && effectiveFingerprint != null && effectiveFingerprint == lastUploaded
