package coffee.crema.ui

import coffee.crema.core.EventMaintenanceProgressInner
import coffee.crema.core.MmrRegister
import coffee.crema.core.ScaleCapabilities
import coffee.crema.core.Bean
import coffee.crema.core.Roaster
import coffee.crema.core.MaintenanceState
import coffee.crema.drive.DriveSync
import coffee.crema.core.MaintenanceReadout
import coffee.crema.beans.LibraryStore
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.maintenance.MaintenanceStore
import coffee.crema.maintenance.defaultMaintenanceState
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.MirroredSettings
import coffee.crema.visualizer.VisualizerSync
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.ble.proxy.Peer
import coffee.crema.ble.proxy.RemoteClient
import coffee.crema.core.EventShotSettingsReadInner
import coffee.crema.core.StopReason
import coffee.crema.profiles.CremaProfile
import coffee.crema.core.MachineState
import coffee.crema.decent.DecentSync

/** The default preferences ([AppPrefs], built from the core's default
 *  bundle): [MainUiState]'s settings fields start from these. */
private val DefaultPrefs = AppPrefs()

/** A flat snapshot of everything the current screen shows. */
data class MainUiState(
    val bleState: De1BleManager.State = De1BleManager.State.IDLE,
    /** Coarse state of the scale connection. */
    val scaleState: ScaleBleManager.State = ScaleBleManager.State.IDLE,
    /** Whether the phone's Bluetooth adapter is on. Drives the Devices sheet's
     *  "Bluetooth is off" banner + greyed Pair buttons; a transition to ON
     *  re-triggers auto-connect for remembered devices. */
    val bluetoothOn: Boolean = true,
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
    val machineStateName: MachineState? = null,
    /** Raw machine-substate name (e.g. `"Pouring"`), or null. */
    val machineSubstate: String? = null,
    /** Human-readable message for the machine fault core says to surface
     *  (`Event.MachineErrorChanged`), else null — the same copy as web. */
    val machineError: String? = null,
    /** The maintenance cycle (Descale / Clean / AirPurge) core is following
     *  (`Event.MaintenanceProgress`) — held for preheat on old firmware,
     *  requested, or running with the descale countdown; null when none is
     *  (including once it finishes or is cancelled). Web: `snapshot.maintenance`. */
    val maintenanceRun: EventMaintenanceProgressInner? = null,
    /** Latest tank DEPTH, mm (`Event.WaterLevel.level` — raw + core's 5 mm
     *  sensor offset, smoothed), or null before the first report. */
    val waterLevelMm: Float? = null,
    /** The DE1's own refill threshold, RAW sensor mm — reported alongside the level in the
     *  same `Event.WaterLevel` packet (null before the first report / while
     *  disconnected). The shell defers to this live machine value for the
     *  "refill soon" cue rather than a hardcoded constant, matching the web
     *  shell's `waterRefillThreshold` (issue 29). */
    val waterRefillThresholdMm: Float? = null,
    /** The DE1's own steam / hot-water settings (`Event.ShotSettingsRead`) —
     *  the machine's live targets, read at connect and echoed on change. Null
     *  until the first read. Drives the mode chips' target sub-labels and the
     *  steam / hot-water timers (web `ui.de1ShotSettings` parity). */
    val de1ShotSettings: EventShotSettingsReadInner? = null,
    /** The core's resolved hot-water target (`Event.HotWaterTarget`, or a
     *  re-read after this device's own input changes): the chips and mode
     *  card show its amount. Null until the core has a dose. */
    val hotWaterTarget: coffee.crema.core.HotWaterTarget? = null,
    /** Monotonic ms when the DE1 entered the current service mode (Steam /
     *  HotWater / HotWaterRinse), or null when no service mode is running. */
    val modeStartedAtMs: Long? = null,
    /** Milliseconds since the current service mode began — ticked at 4 Hz
     *  while a mode runs (the steam / hot-water / flush timers); 0 when idle. */
    val modeElapsedMs: Long = 0,
    /**
     * The DE1's pre-formatted firmware label (e.g. `"v1.0.142 (API 4)"`), or
     * null until the machine's `Version` characteristic reply arrives.
     * Read-only — folded from `Event.Firmware.firmware_string`. Live only with
     * a connected DE1; the Settings machine rows show "—" otherwise.
     */
    val de1Firmware: String? = null,
    /** The connected DE1's Bluetooth address, for the Settings "BLE" row (the
     *  web shows the device id here); null when disconnected. */
    val de1BluetoothAddress: String? = null,
    // ── Auto-connect (per device = a remembered address) ──────────────────────
    /** The remembered DE1 address; non-null ⟺ the DE1's "Auto-connect" is ON
     *  (reconnect on drop + connect on launch). Distinct from the LIVE
     *  [de1BluetoothAddress], which is set only while actually connected. */
    val rememberedDe1Address: String? = null,
    /** The remembered scale address; non-null ⟺ the scale's Auto-connect is ON. */
    val rememberedScaleAddress: String? = null,
    /** The remembered scale's advertised name — feeds the scan-free direct
     *  reconnect (codec identify needs the name). */
    val rememberedScaleName: String? = null,
    /** The DE1 is reconnecting (a remembered device between / during
     *  re-attempts) — the device rows show "Reconnecting…" and "Retry now". */
    val de1Reconnecting: Boolean = false,
    /** The scale twin of [de1Reconnecting]. */
    val scaleReconnecting: Boolean = false,
    // ── Companion-device presence (Android CDM) ──────────────────────────────
    /** The platform supports companion-device association (else the option is hidden). */
    val companionSupported: Boolean = false,
    /** The remembered DE1 has a companion association ("Reconnect when nearby" is on). */
    val de1Companion: Boolean = false,
    /** The remembered scale has a companion association. */
    val scaleCompanion: Boolean = false,
    /** Show the one-time "Reconnect automatically when it's nearby?" offer for this device. */
    /** The activity should launch the system association dialog for this device. */
    val companionRequest: coffee.crema.ble.companion.CompanionDevice? = null,
    /** Addresses whose offer was declined (persisted; never re-offered). */
    val companionDeclined: List<String> = emptyList(),
    /**
     * Raw MMR register values the DE1 reported, keyed by [MmrRegister]. Folded
     * from each `Event.MmrValue`; values are the **raw** 32-bit words — the UI
     * applies the per-register scaling (heater-voltage − 1000, flow-multiplier ÷
     * 1000, GHC-info bitmask, etc.). Populated by the connect-time read sweep
     * once the DE1 reaches READY; empty when disconnected, so the Settings rows
     * fall back to "—".
     */
    val de1MachineInfo: Map<MmrRegister, UInt> = emptyMap(),
    /** The connected DE1's serial is on Decent's stolen-machine list
     *  ([coffee.crema.decent.StolenSerialsCheck]) — a one-line Settings notice. */
    val serialStolen: Boolean = false,
    /**
     * The mains line frequency the core resolved (`50.0` / `60.0` Hz), or `0.0`
     * when the override is "auto-detect", or null before it is read. A pure
     * (no-BLE) core read, refreshed by the connect-time sweep. Surfaces the AC
     * mains-frequency selection in Settings → Advanced.
     */
    val lineFreqHz: Float? = null,
    /** The user's AC-frequency OVERRIDE (0 = auto-detect, 50, 60) — kept apart
     *  from the resolved [lineFreqHz] so the toggle reads "Auto" even after the
     *  detector locks a value. */
    val lineFreqOverride: Float = 0f,
    /** Summary of the last completed shot (Last-shot card), or null. */
    val lastShot: LastShot? = null,
    /** Which auto-stop condition ended the last shot (weight / volume /
     *  max-time), or null when the user stopped it (or no shot yet).
     *  Cleared on the next ShotStarted — the stop-conditions card
     *  highlights the row that fired instead of toasting. */
    val lastStopReason: StopReason? = null,
    /** What will actually end the shot, straight from the core — live armed
     *  targets during a shot, the prospective picture between them. The ONLY
     *  source for stop-condition UI: re-deriving it from the active profile is
     *  what let the Brew card promise a target the core had never armed
     *  (geota/crema#56). Null before the core has reported once. */
    val stopTargets: StopTargetsView? = null,
    /** Non-null while an aborted-shot discard can be undone — the message
     *  MainActivity shows in an action snackbar ("Undo"). */
    val discardToastMessage: String? = null,
    /** Non-null when a store file was unreadable at load and was kept aside
     *  ("Couldn't read your shot history; …") — MainActivity shows it once in an
     *  action snackbar whose "Share" sends the kept-aside copies. */
    val keptAsideNotice: String? = null,
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
    /**
     * True while a shot start is pending (05 F2): from the Coffee tap through
     * the upload, the guard and the pre-flush until the machine reports
     * Espresso. The Coffee button offers Stop meanwhile, which cancels it.
     */
    val shotStarting: Boolean = false,
    /** Upload progress as `"received/total"` acks while uploading, or null. */
    val profileUploadProgress: String? = null,
    /**
     * Shot-behaviour toggles surfaced in Quick Controls. Shell-managed and
     * optimistic — the core applies them via its setters but does not echo them
     * back as events, so these hold the last user choice (the same pattern the
     * scale config toggles use).
     */
    // Default ON to match [AppPrefs.autoTare] / [AppPrefs.stopOnWeight], the
    // core's own defaults, and the web shell — loadPrefs() overwrites these with
    // the persisted value at startup, but the pre-load default must not diverge.
    val autoTare: Boolean = DefaultPrefs.autoTare,
    val stopOnWeight: Boolean = DefaultPrefs.stopOnWeight,
    /** Opt-in: arm the profile's max-volume stop even while a scale is
     *  connected (Settings → Shot behaviour). Default off — volume is a
     *  no-scale fallback, never a competitor to stop-at-weight. */
    val volumeStopWithScale: Boolean = DefaultPrefs.volumeStopWithScale,
    /** Opt-in: refuse to start a shot with a weight target while no scale is
     *  connected (geota/crema#29 — de1app/reaprime's block pattern). */
    val requireScale: Boolean = DefaultPrefs.requireScale,
    val steamEco: Boolean = DefaultPrefs.steamEco,
    /** DE1 firmware two-tap steam stop (MMR `SteamTwoTapStop`): first stop tap
     *  ends steam without the wand auto-purge; a second tap purges. Written on
     *  change + re-seeded on connect (geota/crema#34). */
    val steamTwoTap: Boolean = DefaultPrefs.steamTwoTap,
    /** Fan-on temperature threshold, °C (MMR `FanThreshold`, 0..=60). Re-seeded
     *  on every connect (geota/crema#31). */
    val fanThresholdC: Float = DefaultPrefs.fanThresholdC,
    /** Tablet charging from the DE1's USB port (`"alwaysOn"`, the default as
     *  in decaid / `"smart"` / `"smartHigh"`, Decenza `BatteryManager`'s
     *  modes). Persisted; asserted on connect and every minute after by the
     *  core's USB-charger check. */
    val usbChargingMode: String = DefaultPrefs.usbChargingMode,
    /** Persisted pre-shot flush / post-steam purge preferences (not yet consumed
     *  by the shot sequence — Settings rows carry the pill until then). */
    val preFlush: Boolean = DefaultPrefs.preFlush,
    val steamPurge: Boolean = DefaultPrefs.steamPurge,
    /** Hold the screen on while a shot is pulling (Settings → Display). */
    val keepScreenOnBrew: Boolean = DefaultPrefs.keepScreenOnBrew,
    /** Guided-brew cue sound / vibration (Settings → Display + the Brew setup
     *  bell). Null = never chosen — read through [coffee.crema.ui.brewlog.BrewCueDefaults]. */
    val brewCueSound: Boolean? = null,
    val brewCueHaptics: Boolean? = null,
    // ── Machine sleep & screensaver (Settings → Display) ─────────────────────
    /** "Sleep after": idle minutes before Crema puts the DE1 to sleep, 0 =
     *  Never. The core's idle-sleep timer owns the decision (see [IdleSleepRunner]);
     *  the same setting on phone, tablet and web (default 30). */
    val idleSleepMin: Int = DEFAULT_IDLE_SLEEP_MIN,
    /** Idle minutes before the screensaver shows; 0 = never. Display only —
     *  machine sleep is [idleSleepMin]'s. */
    val screensaverAfterMin: Int = 30,
    /** Wake a sleeping DE1 when the saver is tapped (de1app's saver button runs
     *  `start_idle`; Decenza's ScreensaverPage wakes too). OFF = the tap only
     *  dismisses; the machine stays asleep until the power button. */
    val wakeMachineWithSaver: Boolean = true,
    /** Put the DE1 to sleep when Crema is QUIT (task swiped from recents) —
     *  never on mere backgrounding. Best-effort; idle + primary only. */
    val sleepOnQuit: Boolean = true,
    /** Keep the DE1 connected + auto-reconnecting while the screen is OFF, via a
     *  foreground service (geota/crema#65). The EFFECTIVE value (the raw pref
     *  resolved against the form-factor default: tablet on / phone off); the
     *  service still only engages while charging. */
    val keepConnectedScreenOff: Boolean = false,
    /** Daily automatic Google Drive backup while Drive is linked (issue #36). */
    val autoDriveBackup: Boolean = true,
    /** Daily automatic release check against GitHub — default OFF (opt-in
     *  phone-home; the manual About → Check button always works). */
    val autoUpdateCheck: Boolean = false,
    /** Epoch ms of the last automatic release-check attempt (24 h throttle). */
    val lastUpdateCheckAtMs: Long? = null,
    /** The newest version already notified about — notify once per build. */
    val lastSeenLatestVersion: String? = null,
    /** The last saved Brew Log method — the log form opens on it (issue #10). */
    val lastBrewLogMethod: String? = null,
    /** Whether the screensaver overlay is currently shown. Set by the idle
     *  checker or a live machine-sleep transition; cleared by tap-to-wake. */
    val saverVisible: Boolean = false,
    /** Brew defaults (Settings → Brew defaults) — seed new profiles + QC fallbacks.
     *  Seeded from the core's default settings bundle so web + Android share one source. */
    val defaultDoseG: Float = DefaultPrefs.defaultDoseG,
    val defaultRatio: Float = DefaultPrefs.defaultRatio,
    val defaultBrewTempC: Float = DefaultPrefs.defaultBrewTempC,
    val defaultPreinfuseS: Float = DefaultPrefs.defaultPreinfuseS,
    /** Persisted Quick-Controls steam / hot-water / flush overrides (issue 14) —
     *  seed the QC steppers + applied to the machine on change (read-modify-write). */
    val qcSteamTimeS: Float = DefaultPrefs.qcSteamTimeS,
    val qcSteamFlowMlS: Float = DefaultPrefs.qcSteamFlowMlS,
    val qcSteamTempC: Float = DefaultPrefs.qcSteamTempC,
    val qcHotWaterTempC: Float = DefaultPrefs.qcHotWaterTempC,
    val qcHotWaterVolumeMl: Float = DefaultPrefs.qcHotWaterVolumeMl,
    /** The hot-water dose's mode and time / No limit / ratio values (the
     *  volume is [qcHotWaterVolumeMl]); carried with the settings bundle. */
    val qcHotWaterMode: coffee.crema.core.HotWaterMode? = DefaultPrefs.qcHotWaterMode,
    val qcHotWaterTimeS: Float? = DefaultPrefs.qcHotWaterTimeS,
    val qcHotWaterTimeNoLimit: Boolean? = DefaultPrefs.qcHotWaterTimeNoLimit,
    val qcHotWaterRatio: Float? = DefaultPrefs.qcHotWaterRatio,
    val qcFlushTimeS: Float = DefaultPrefs.qcFlushTimeS,
    val qcFlushTempC: Float = DefaultPrefs.qcFlushTempC,
    /** Persisted Quick-Controls grinder click (issue 15); null = never dialed. */
    val qcGrind: Float? = null,
    // ── Brew Log (issue #10) ─────────────────────────────────────────────
    /** The live guided-brew session, when one is armed/running. */
    val guidedBrew: GuidedBrewUi = GuidedBrewUi(),
    /** The guided-brew recipe library (tombstones filtered on read). */
    val brewRecipes: List<coffee.crema.core.BrewRecipe> = emptyList(),
    /** Per-method default recipe pointer (method → recipe id; may name a built-in). */
    val lastRecipeByMethod: Map<String, String> = emptyMap(),
    /** The bundled, credited, read-only built-in recipes (core catalogue). */
    val builtinRecipes: List<coffee.crema.core.BrewRecipe> = emptyList(),
    /** Built-in recipe ids hidden from the library + picker. */
    val hiddenBuiltinRecipes: Set<String> = emptySet(),
    /** The user's own brewing methods (`custom:<uuid>`), tombstones included. */
    val customMethods: List<coffee.crema.core.CustomBrewMethod> = emptyList(),
    /** The one-time "Save 'X' as a method?" offer after an "Other…" log. */
    val saveMethodPrompt: coffee.crema.ui.brewlog.SaveMethodPrompt? = null,
    /**
     * Queued user-facing feedback lines (imports, exports, blocked actions).
     * MainActivity surfaces them as snackbars, dequeuing via
     * [MainViewModel.consumeUserMessage] — the web shell's ToastHost equivalent.
     * A queue, not a single field, so two rapid notifications both surface.
     */
    val userMessages: List<String> = emptyList(),
    /** Result of the last on-demand release check (Settings → About,
     *  issue #36) — null until the user taps Check. */
    val updateInfo: coffee.crema.update.UpdateInfo? = null,
    /** True while a release check is in flight. */
    val updateCheckBusy: Boolean = false,
    /** Max shot duration cap, seconds (persisted in AppPrefs; `0` = none —
     *  the default, the profile dictates shot length). Shown as a Time
     *  stop-condition on Brew + edited in Settings → Brew defaults. */
    val maxShotDurationS: Float = DefaultPrefs.maxShotDurationS,
    /**
     * Which telemetry channels the live chart draws — keys: `pressure`, `flow`,
     * `headTemp`, `mixTemp`, `weight`, `weightFlow`, `dispensedVolume`,
     * `resistance`. A local display pref toggled from Quick Controls; defaults
     * match the web (pressure / flow / weight).
     */
    val chartChannels: Set<String> = DefaultPrefs.chartChannels,
    // ── Display units (issue 44) ─────────────────────────────────────────────
    // The user's chosen display unit per dimension; everything is stored
    // canonical (g / °C / bar / ml) and routed through `Format.kt` for display.
    // Persisted in AppPrefs; defaults match the web shell.
    /** Weight unit for dose/yield/scale readouts — `"g" | "oz"`. */
    val weightUnit: String = DefaultPrefs.weightUnit,
    /** Temperature unit for every temp readout — `"C" | "F"`. */
    val tempUnit: String = DefaultPrefs.tempUnit,
    /** Pressure unit for the pressure channel — `"bar" | "psi"`. */
    val pressureUnit: String = DefaultPrefs.pressureUnit,
    /** Volume unit for water/dispensed readouts — `"ml" | "floz"`. */
    val volumeUnit: String = DefaultPrefs.volumeUnit,
    /** Water-tank readout style — `"ml" | "percent"` (geota/crema#33). */
    val waterLevelUnit: String = DefaultPrefs.waterLevelUnit,
    /** The DE1's own refill threshold, raw sensor mm — pushed to the machine at
     *  connect. Null = unset → core's 5 mm default. See [refillPointMm]. */
    val waterRefillPointMm: Float? = null,
    /** Low-water warning threshold, canonical ml (#33 follow-up). Null =
     *  unset → [WATER_WARN_DEFAULT_ML]; 0 disables. See [waterWarnThresholdMl]. */
    val waterWarnMl: Float? = null,
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
    /** Decent account shot-upload state (#84) — mirrored from [MainViewModel.decent]. */
    val decent: DecentSync.UiState = DecentSync.UiState(),
    /**
     * Cloud-destination state derived by [SharingController]: the catch-up
     * offer, per-shot upload targets, the "Upload N" backlog, the connected
     * serial's Decent registration, the merged activity log.
     */
    val sharing: SharingUiState = SharingUiState(),
    /** Google Drive backup state (configured / connected / busy) — mirrored from
     *  [MainViewModel.drive]'s controller flow. */
    val drive: DriveSync.UiState = DriveSync.UiState(),
    /** A shot id the History screen should select on next open (set when the Brew
     *  "Last shot" card is tapped). Consumed + cleared by HistoryScreen. */
    val pendingHistoryShotId: String? = null,
    /** A bean id the History screen should filter to on next open (the bean
     *  detail's "See all N shots"). Consumed + cleared by HistoryScreen. */
    val pendingHistoryBeanId: String? = null,
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
    val themeMode: String = DefaultPrefs.themeMode,
    val grinderModel: String = DefaultPrefs.grinderModel,
    /** Default barista stamped on new shots / brew logs (Settings). Blank = unset. */
    val baristaName: String = DefaultPrefs.baristaName,
    val showDebugPanel: Boolean = DefaultPrefs.showDebugPanel,
    // Multi-device LAN proxy (M1/M2). Live mode switches via the device picker.
    val proxyRole: String = "normal",
    val proxyPrimaryHost: String = "",
    val proxyPrimaryPort: Int = 0,
    /** Debug: a primary replays the newest capture as a fake DE1 instead of using
     *  live BT (emulator demos only; default off — see [AppPrefs.replayPrimary]). */
    val replayPrimary: Boolean = false,
    /** A remote's link is mid-redial (hub dropped) or not yet welcomed —
     *  distinguishes a reconnecting remote from a healthy one so a frozen view
     *  doesn't read as live (issue 03). Only meaningful while [proxyRole] ==
     *  "secondary". */
    val mirrorReconnecting: Boolean = false,
    /** The hub's display name while this device is a remote (issue 10), from
     *  the hub's Welcome and then its config snapshots — names the hub in the
     *  "Connected through <hub>" status. Blank when not a remote. */
    val mirroringPrimaryName: String = "",
    /** A remote's hub lists the DE1 in its roster (it holds the machine).
     *  Only meaningful while [proxyRole] == "secondary". */
    val remoteHubHasMachine: Boolean = false,
    /** A remote's re-find gave up: no hub it found proved this device's
     *  pairing ("Couldn't verify <hub>"). */
    val refindGaveUp: Boolean = false,
    /** NSD lists a remote's hub address as a device that isn't a hub any more. */
    val hubStoppedHosting: Boolean = false,
    /** This secondary was granted **view-only** (mirror, not control) by the host
     *  (issue 02) — its control attempts are refused, so the UI can say "view-only". */
    val mirrorViewOnly: Boolean = false,
    /** The hub's code-entry dialog (relay v4): a remote asks to pair. Non-null ⟺
     *  the Activity shows "Pair ‹remote›?" with the 6-digit field and the scope. */
    val pendingPairing: PairingPrompt? = null,
    /** Devices this host has approved to mirror it (issue 02) — shown in Settings ▸
     *  "Paired devices" for review/revoke. */
    val pairedDevices: List<PairedDevice> = emptyList(),
    /** Seconds left in this host's pairing window; 0 = closed. */
    val pairingSecondsLeft: Int = 0,
    /** Seconds left in the pairing lockout after three wrong codes; 0 = not locked. */
    val pairingLockedSecondsLeft: Int = 0,
    /** The hubs this remote has pinned (relay v4 Paired hubs). */
    val pairedHubs: List<coffee.crema.ble.proxy.RelayHubPin> = emptyList(),
    /** The remote's pairing with a hub, for the code sheet (relay v4, spec §6.1). */
    val relayPairing: coffee.crema.ble.proxy.PairingState = coffee.crema.ble.proxy.PairingState.Idle,
    /** The relay keys couldn't be read this start: the relay is off until a restart. */
    val relayKeysUnavailable: Boolean = false,
    /** The pairing key was reset (a definitive loss): the one-time notice. */
    val relayKeyResetNotice: Boolean = false,
    /** The v3 upgrade: this remote pairs again before it dials. */
    val relayNeedsRepair: Boolean = false,
    /** The v3 upgrade: this hub's one-time "pair your phones again" banner. */
    val relayUpgradeNotice: Boolean = false,
    /** Paired devices holding a pairing secret from this host; a listed device
     *  missing here must pair again. */
    val pairedWithSecret: Set<String> = emptySet(),
    /** Remotes connected through THIS device while it's the hub: the
     *  read-only "Remotes connected" list. */
    val remotes: List<RemoteClient> = emptyList(),
    /** On a normal device: a discovered hub (not this device) whose NSD
     *  advertisement says it holds the DE1. Unauthenticated, so only advice:
     *  the connect surfaces and the Hub mode switch warn, and name it only
     *  when [isVerifiedHub]. Null in the other roles. */
    val hubHoldingMachine: Peer? = null,
    /** This device holds a pairing secret for its pinned hub (the target at
     *  [proxyPrimaryHost]): what [isVerifiedHub] checks a discovered hub against. */
    val pairedHubPinned: Boolean = false,
    /** A host whose identity changed (it couldn't prove this device's pairing),
     *  as (host id, name): non-null ⟺ the Activity offers to forget it. */
    val changedHost: Pair<String, String>? = null,
    /**
     * A remote's mirror slice (spec §7.2): the hub's values as last received,
     * in memory only. Non-null exactly while this device is a remote (I6). The
     * screens see it laid over this device's own values ([effective]); the VM
     * persists only the own values.
     */
    val mirror: MirroredSettings? = null,
    /** The remote → standalone confirmation (T6, spec §7.5), while it's open:
     *  "Use <hub>'s machine and brew settings on this device?". */
    val adoptPrompt: AdoptPrompt? = null,
    /** The scale settings the connected scale's weight stream has reported
     *  (Bookoo): a remote knows those values (A16–A18, A21); cleared with
     *  the scale's session. */
    val scaleEchoed: Set<coffee.crema.settings.Setting> = emptySet(),
    /**
     * Mirror overlay (issue 05), projected from [mirror] by [effective] and
     * never held in the own state: the hub's active profile, decoded for
     * **display only**, when it's a custom profile this device's own library
     * lacks. Preferred over the [activeProfileId] library lookup so the Brew
     * curve/targets/name match the hub exactly. Null = use the library. */
    val mirroredProfile: CremaProfile? = null,
    /** The raw wire JSON behind [mirroredProfile] — the full profile, which the
     *  thin [mirroredProfile] model doesn't keep: what a remote adopts into its
     *  own library when it becomes standalone. Projected with it. */
    val mirroredProfileJson: String? = null,
    /** A one-line summary of the hub's active bean (issue 05), shown on the bean
     *  chip when that bean isn't in this device's library. Projected from
     *  [mirror]; null = use the library. */
    val mirroredBeanSummary: String? = null,
    /** Crema instances discovered on the LAN (NSD) — the device picker's list. */
    val peers: List<Peer> = emptyList(),
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
