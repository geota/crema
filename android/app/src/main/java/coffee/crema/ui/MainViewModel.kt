package coffee.crema.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.newShotId
import coffee.crema.core.MachineRequest
import coffee.crema.core.WaterSessionKind
import coffee.crema.core.MmrReg
import coffee.crema.core.MmrRegister
import coffee.crema.core.ScaleCapabilities
import coffee.crema.core.Bean
import coffee.crema.core.Roaster
import coffee.crema.core.MaintenanceState
import coffee.crema.core.WriteTarget
import coffee.crema.drive.DriveStore
import coffee.crema.drive.DriveSync
import coffee.crema.core.MaintenanceReadout
import coffee.crema.core.builtinCremaProfiles
import coffee.crema.core.cremaProfileToWire
import coffee.crema.core.exportBackupJsonl
import coffee.crema.core.maintenanceReadout
import androidx.core.content.FileProvider
import coffee.crema.beans.LibraryStore
import coffee.crema.beans.daysOffRoast
import coffee.crema.beans.isFrozen
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.maintenance.MaintenanceStore
import coffee.crema.maintenance.MAINTENANCE_MAX_SAMPLE_DT_S
import coffee.crema.maintenance.MAINTENANCE_MAX_SAMPLE_ML
import coffee.crema.maintenance.defaultMaintenanceState
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.ConfigSnapshot
import coffee.crema.settings.PairedDevice
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
import coffee.crema.ble.proxy.HandoffTarget
import coffee.crema.ble.proxy.Peer
import coffee.crema.core.EventShotSettingsReadInner
import coffee.crema.core.ShotDisposition
import coffee.crema.core.ShotQualityReport
import coffee.crema.core.SteamHotWaterSettings
import coffee.crema.core.StopReason
import coffee.crema.core.profileFingerprint
import coffee.crema.core.subStateErrorMessage
import coffee.crema.profiles.BrewDefaults
import coffee.crema.profiles.CremaProfile
import coffee.crema.profiles.brewDefaultsJson
import coffee.crema.core.MachineState
import coffee.crema.profiles.overrideBrewParamsJson
import coffee.crema.profiles.profileIdOf
import coffee.crema.profiles.SegmentEdit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import android.net.Uri
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

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
    /** The matching [StoredShot] id (a shared UUID-v7 `newShotId()`), so tapping
     *  the card opens it in History. Null for the pre-first-shot default. */
    val id: String? = null,
)

/** A flat snapshot of everything the current screen shows. */
/**
 * Transient Quick-Controls brew override — the dose / yield / brew-temp the user
 * nudged in the Quick Controls sheet. NOT persisted to any profile (mirrors the
 * web shell's BrewParamState): it is baked into the NEXT shot's uploaded profile
 * at [MainViewModel.startShot] and cleared on profile switch / Reset. The yield
 * is also pushed to the core as the stop-at-weight target (web
 * `setShotTargetWeight`) by [MainViewModel.pushStopTargets], so SAW arms on it.
 */
/**
 * The transient Quick-Controls brew override baked into the next shot's upload
 * (the library profile is untouched). dose/yield/brewTemp always carry the
 * effective value; [preinf] is null until the user touches the pre-infuse
 * stepper — pre-infusion isn't a separate machine setting, so an override just
 * caps the leading pre-infuse segment's time (issue 15).
 */
data class BrewParams(
    val dose: Double,
    val yieldOut: Double,
    val brewTemp: Double,
    val preinf: Double? = null,
)

/**
 * The brew parameters actually in effect: the live Quick-Controls override
 * ([brewParams]) if present, else the active profile's recipe, else the seed
 * defaults — 18 g dose, 93 °C, and a 1:2 yield (`dose * 2`). One source for the
 * `brewParams ?: active ?: 18/dose*2/93` triple that was copied across the brew &
 * scale surfaces (issue 28). The yield seed is ratio-preserving rather than a flat
 * 36 g so it stays a 1:2 shot if the dose seed ever changes; at today's 18 g dose
 * it is exactly 36 g, so this is a no-op vs the old hardcoded value.
 * NB this is the 3-way override→profile→default fallback; it is deliberately
 * distinct from the 2-way profile-only recipe (`active?.dose ?: 18f`) and the
 * per-segment temperature default (`seg.temp ?: 93f`), which are not folded in.
 */
data class EffectiveBrew(val dose: Double, val yieldOut: Double, val brewTemp: Double)

fun effectiveBrew(brewParams: BrewParams?, active: CremaProfile?): EffectiveBrew {
    val dose = brewParams?.dose ?: active?.dose?.toDouble() ?: 18.0
    return EffectiveBrew(
        dose = dose,
        yieldOut = brewParams?.yieldOut ?: active?.yieldOut?.toDouble() ?: (dose * 2),
        brewTemp = brewParams?.brewTemp ?: active?.brewTemp?.toDouble() ?: 93.0,
    )
}

/**
 * The profile currently driving the Brew surface: the mirror overlay
 * ([MainUiState.mirroredProfile]) when this device is showing a primary's custom
 * profile it doesn't itself have (issue 05), else the library lookup by
 * [MainUiState.activeProfileId]. One source so the header, curve, targets, and
 * scale dose all agree on a secondary.
 */
fun MainUiState.activeProfile(): CremaProfile? =
    mirroredProfile ?: profiles.firstOrNull { it.id == activeProfileId }

fun MainUiState.effectiveBrew(): EffectiveBrew =
    effectiveBrew(brewParams, activeProfile())

/**
 * Headroom (mm) above the DE1's refill threshold at or below which the "refill
 * soon" cue shows — the tank is close to, but not yet at, the machine's own
 * refill point. Mirrors the web shell's `REFILL_SOON_MARGIN_MM`
 * (`web/src/lib/state/ui-state.svelte.ts`) so every shell warns at the same level.
 */
const val REFILL_SOON_MARGIN_MM = 5f

/**
 * Battery percentage at or below which the one-per-connection "charge your
 * scale" warning fires (issue #29) — early enough to charge before a session,
 * late enough not to nag. Web parity: `SCALE_LOW_BATTERY_PCT` in
 * `app.svelte.ts`.
 */
const val SCALE_LOW_BATTERY_PCT = 25

/** Minimum gap between two automatic release-check attempts (issue #36). */
const val AUTO_UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

/**
 * Whether the tank is low enough to warrant a "refill soon" cue: the live level
 * is within [REFILL_SOON_MARGIN_MM] of (or already below) the DE1's *reported*
 * refill threshold. Both readings arrive together in one `Event.WaterLevel`
 * packet, so the cue is naturally suppressed before the first report and while
 * disconnected (the level is null). Mirrors web `waterRefillSoon` — the shell
 * defers to the machine's own refill point rather than a hardcoded threshold,
 * reconciling the former phone 20 mm vs tablet 5 mm constants (issue 29).
 */
fun MainUiState.refillSoon(): Boolean {
    val level = waterLevelMm ?: return false
    val threshold = waterRefillThresholdMm ?: return false
    return level <= threshold + REFILL_SOON_MARGIN_MM
}

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
    /** Human-readable message when the current substate is an *error* (e.g.
     *  `"No water — refill the tank"`), else null. Sourced from core
     *  `subStateErrorMessage` so it matches the web shell's copy verbatim. */
    val machineError: String? = null,
    /** Latest tank level, mm (`Event.WaterLevel`), or null before the first report. */
    val waterLevelMm: Float? = null,
    /** The DE1's own refill threshold, mm — reported alongside the level in the
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
    /** Non-null while an aborted-shot discard can be undone — the message
     *  MainActivity shows in an action snackbar ("Undo"). */
    val discardToastMessage: String? = null,
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
    // Default ON to match [AppPrefs.autoTare] / [AppPrefs.stopOnWeight], the
    // core's own defaults, and the web shell — loadPrefs() overwrites these with
    // the persisted value at startup, but the pre-load default must not diverge.
    val autoTare: Boolean = true,
    val stopOnWeight: Boolean = true,
    /** Opt-in: arm the profile's max-volume stop even while a scale is
     *  connected (Settings → Shot behaviour). Default off — volume is a
     *  no-scale fallback, never a competitor to stop-at-weight. */
    val volumeStopWithScale: Boolean = false,
    /** Opt-in: refuse to start a shot with a weight target while no scale is
     *  connected (geota/crema#29 — de1app/reaprime's block pattern). */
    val requireScale: Boolean = false,
    val steamEco: Boolean = false,
    /** DE1 firmware two-tap steam stop (MMR `SteamTwoTapStop`): first stop tap
     *  ends steam without the wand auto-purge; a second tap purges. Written on
     *  change + re-seeded on connect (geota/crema#34). */
    val steamTwoTap: Boolean = false,
    /** Fan-on temperature threshold, °C (MMR `FanThreshold`, 0..=60). Re-seeded
     *  on every connect (geota/crema#31). */
    val fanThresholdC: Float = 55f,
    /** Persisted pre-shot flush / post-steam purge preferences (not yet consumed
     *  by the shot sequence — Settings rows carry the pill until then). */
    val preFlush: Boolean = false,
    val steamPurge: Boolean = false,
    /** Hold the screen on while a shot is pulling (Settings → Display). */
    val keepScreenOnBrew: Boolean = false,
    // ── Sleep & screensaver (Settings → Display; per-shell platform prefs) ──
    /** Idle minutes before the screensaver shows; 0 = never. */
    val screensaverAfterMin: Int = 30,
    /** Also put the DE1 to sleep when the saver starts (de1app/Decenza
     *  coupling; OFF = display-only dimming). */
    val sleepMachineWithSaver: Boolean = true,
    /** Wake a sleeping DE1 when the saver is dismissed (de1app's saver-tap
     *  behaviour). OFF = the tap only dismisses; the machine stays asleep
     *  until the power button. */
    val wakeMachineWithSaver: Boolean = true,
    /** Daily automatic Google Drive backup while Drive is linked (issue #36). */
    val autoDriveBackup: Boolean = true,
    /** Daily automatic release check against GitHub — default OFF (opt-in
     *  phone-home; the manual About → Check button always works). */
    val autoUpdateCheck: Boolean = false,
    /** Epoch ms of the last automatic release-check attempt (24 h throttle). */
    val lastUpdateCheckAtMs: Long? = null,
    /** The newest version already notified about — notify once per build. */
    val lastSeenLatestVersion: String? = null,
    /** Whether the screensaver overlay is currently shown. Set by the idle
     *  checker or a live machine-sleep transition; cleared by tap-to-wake. */
    val saverVisible: Boolean = false,
    /** Brew defaults (Settings → Brew defaults) — seed new profiles + QC fallbacks.
     *  Seeded from the core's [BrewDefaults] so web + Android share one source. */
    val defaultDoseG: Float = BrewDefaults.INSTANCE.doseG,
    val defaultRatio: Float = BrewDefaults.INSTANCE.ratio,
    val defaultBrewTempC: Float = BrewDefaults.INSTANCE.brewTempC,
    val defaultPreinfuseS: Float = BrewDefaults.INSTANCE.preinfusionS,
    /** Persisted Quick-Controls steam / hot-water / flush overrides (issue 14) —
     *  seed the QC steppers + applied to the machine on change (read-modify-write). */
    val qcSteamTimeS: Float = 12f,
    val qcSteamFlowMlS: Float = 1.2f,
    val qcSteamTempC: Float = QcSteam.DEFAULT_TEMP_C.toFloat(),
    val qcHotWaterTempC: Float = 80f,
    val qcHotWaterVolumeMl: Float = 150f,
    val qcFlushTimeS: Float = 4f,
    val qcFlushTempC: Float = 95f,
    /** Persisted Quick-Controls grinder click (issue 15); null = never dialed. */
    val qcGrind: Float? = null,
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
    val maxShotDurationS: Float = 0f,
    /**
     * Which telemetry channels the live chart draws — keys: `pressure`, `flow`,
     * `headTemp`, `mixTemp`, `weight`, `weightFlow`, `dispensedVolume`,
     * `resistance`. A local display pref toggled from Quick Controls; defaults
     * match the web (pressure / flow / weight).
     */
    val chartChannels: Set<String> = setOf("pressure", "flow", "weight"),
    // ── Display units (issue 44) ─────────────────────────────────────────────
    // The user's chosen display unit per dimension; everything is stored
    // canonical (g / °C / bar / ml) and routed through `Format.kt` for display.
    // Persisted in AppPrefs; defaults match the web shell.
    /** Weight unit for dose/yield/scale readouts — `"g" | "oz"`. */
    val weightUnit: String = "g",
    /** Temperature unit for every temp readout — `"C" | "F"`. */
    val tempUnit: String = "C",
    /** Pressure unit for the pressure channel — `"bar" | "psi"`. */
    val pressureUnit: String = "bar",
    /** Volume unit for water/dispensed readouts — `"ml" | "floz"`. */
    val volumeUnit: String = "ml",
    /** Water-tank readout style — `"ml" | "percent"` (geota/crema#33). */
    val waterLevelUnit: String = "ml",
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
    /** Google Drive backup state (configured / connected / busy) — mirrored from
     *  [MainViewModel.drive]'s controller flow. */
    val drive: DriveSync.UiState = DriveSync.UiState(),
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
    // Multi-device LAN proxy (M1/M2). Live mode switches via the device picker.
    val proxyRole: String = "normal",
    val proxyPrimaryHost: String = "",
    val proxyPrimaryPort: Int = 0,
    /** Debug: a primary replays the newest capture as a fake DE1 instead of using
     *  live BT (emulator demos only; default off — see [AppPrefs.replayPrimary]). */
    val replayPrimary: Boolean = false,
    /** A secondary's link is mid-redial (primary dropped) — distinguishes a
     *  reconnecting mirror from a healthy one so a frozen view doesn't read as
     *  live (issue 03). Only meaningful while [proxyRole] == "secondary". */
    val mirrorReconnecting: Boolean = false,
    /** The primary's display name while mirroring (issue 10) — drives the app-wide
     *  "Mirroring <primary>" authority banner. Blank when not a secondary. */
    val mirroringPrimaryName: String = "",
    /** This secondary was granted **view-only** (mirror, not control) by the host
     *  (issue 02) — its control attempts are refused, so the UI can say "view-only". */
    val mirrorViewOnly: Boolean = false,
    /** A pending host-side pairing prompt (issue 02): a new secondary is asking to
     *  mirror this device. Non-null ⟺ the Activity shows the "Allow this device?"
     *  dialog; the user's choice resolves the held handshake. */
    val pendingPairing: PairingPrompt? = null,
    /** Devices this host has approved to mirror it (issue 02) — shown in Settings ▸
     *  "Paired devices" for review/revoke. */
    val pairedDevices: List<PairedDevice> = emptyList(),
    /** Secondaries currently mirroring THIS device while it's a primary (issue 07) —
     *  the "hand off to <device>" targets shown in the picker. */
    val mirrorClients: List<HandoffTarget> = emptyList(),
    /** A pending **push** handoff offer (issue 07): a primary ([the name]) is offering
     *  us the machine. Non-null ⟺ the Activity shows the "Take the machine?" dialog. */
    val pendingHandoffOffer: String? = null,
    /**
     * Transient mirror overlay (issue 05): the primary's active profile, decoded
     * for **display only**, when it's a custom profile this device's own library
     * lacks. Preferred over the [activeProfileId] library lookup while mirroring so
     * the Brew curve/targets/name match the primary exactly. Null = use the library
     * (built-in, or not mirroring). Cleared on any mode switch. */
    val mirroredProfile: CremaProfile? = null,
    /** The raw wire JSON behind [mirroredProfile] — kept so a take-over (#01) can
     *  promote it into the taker's real library (the thin [mirroredProfile] model
     *  drops fields the upload needs). Set/cleared in lock-step with it. */
    val mirroredProfileJson: String? = null,
    /** A one-line summary of the primary's active bean (issue 05), shown on the bean
     *  chip when that bean isn't in this device's library. Null = use the library. */
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

/**
 * Owns the [CremaBridge] and the [De1BleManager], parses the JSON `CoreOutput`
 * the bridge returns, and projects decoded [Event]s into [MainUiState].
 *
 * Carried over from the Phase-0 proof-of-concept: connection, core ownership,
 * and per-screen state are still folded into one class so the end-to-end path
 * is readable top to bottom. Splitting these into proper layers is future work.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    // coerceInputValues: an unknown enum string (e.g. an out-of-contract profile
    // `mode`/`metric` or a version-skewed peer frame) coerces to the property
    // default (null) rather than throwing — preserves the lenient profile decode
    // after the mode/ramp/metric/compare fields became typed enums.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** The Rust core, behind the UniFFI bridge. */
    private val bridge: CremaBridge = CremaBridge()

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

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

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

    /** Whether the low-battery snackbar fired for the current scale
     *  connection (issue #29) — reset in [clearScaleSessionUi] so the next
     *  session warns again, but one connection never nags twice. */
    private var scaleLowBatteryWarned = false
    private var pendingUploadFingerprint: String? = null

    /** Resolved by `Event.WaterSessionCompleted(Flush)` to release the pre-shot
     *  flush waiter in [proceedToEspresso] (issue 15). Null when no pre-shot
     *  flush is in flight. */
    private var pendingPreshotFlush: (() -> Unit)? = null
    /** True while a shot/flush sequence owns the group, so a just-finished steam
     *  session's auto-purge bails instead of racing it (issue 15). */
    private var rinsePending: Boolean = false

    /** The machine's last-reported steam/hot-water settings (from
     *  `Event.ShotSettingsRead`). Used to read-modify-write QC steam/water
     *  changes so the unmodeled fields (timeouts, espresso volume, group temp)
     *  are preserved rather than reset (issue 14). Null until the DE1 reports. */
    private var machineShotSettings: EventShotSettingsReadInner? = null

    /** Ticker driving [MainUiState.modeElapsedMs] while a service mode runs. */
    private var modeTickerJob: Job? = null

    /** Grace timer between the core's ScaleStale (1 s of silence) and the
     *  presume-dead teardown — cancelled/replaced per stale episode. */
    private var scaleStaleJob: Job? = null

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
        onShotSynced = { localId, visualizerId -> library.markShotSynced(localId, visualizerId) },
        grinderModel = { _ui.value.grinderModel.trim().takeIf { it.isNotEmpty() } },
        onPulledShots = { stubs -> library.insertPulledShots(stubs) },
        onBackfillTelemetry = { localId, samples, durationMs -> library.backfillShotTelemetry(localId, samples, durationMs) },
    )

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
        uiState = { _ui.value },
        updateUi = { transform -> _ui.update(transform) },
        notify = { notifyUser(it) },
        appendLog = { appendLog(it) },
        currentPrefs = { currentPrefs() },
        applyRestoredSettings = { applyRestoredSettings(it) },
        applyRestoredMaintenance = { m ->
            // Reseed the live water integrator + persist (maintenance is VM-owned).
            maintenanceTotalLitres = m.totalLitres
            saveMaintenance(m)
        },
        persistSawModel = {
            runCatching { sawModelStore.save(bridge.sawModelJson()) }
                .onFailure { appendLog("SAW model save failed: ${it.message}") }
        },
        setActiveProfile = { setActiveProfile(it) },
        resetBrewParams = { resetBrewParams() },
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
     * The multi-device proxy controller (review #43) — mode switching (normal /
     * primary / secondary), the LAN relay + mirror-link lifecycle, NSD peer
     * discovery, TOFU pairing, and handoff. A self-contained sibling of
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
     * relayed-control dispatch, the session-config snapshot/apply, the
     * TOFU paired-device list (persisted with the app prefs), and the
     * mirrored-profile promotion on handoff. The lambdas referencing
     * later-declared fields ([ble], [scale], [bleRecorder]) are only INVOKED
     * post-construction — never during the controller's own field init
     * (the field-init-order trap, review #36).
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
        dispatchControl = { method, args -> dispatchRelayedControl(method, args) },
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
        pairedDevices = { _ui.value.pairedDevices },
        rememberPaired = { id, name, canControl -> rememberPaired(id, name, canControl) },
        onHandoffGranted = { promoteMirroredProfile() },
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
                    // Drop any mirror overlay (issue 05) + authority cue (issue 10);
                    // a fresh `secondary` attach repopulates them from the snapshot.
                    mirroredProfile = null,
                    mirroredProfileJson = null,
                    mirroredBeanSummary = null,
                    mirroringPrimaryName = if (role == "secondary") it.mirroringPrimaryName else "",
                )
            }
            persistPrefs()
        },
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
    )

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
            readMachineInfo()
            seedMachineSettings()
        },
        // The DE1 no longer holds our profile — drop the upload-skip cache so
        // the next shot re-uploads (issue 11). Without this, a skip after a
        // reconnect would brew against a stale/absent profile.
        onDe1Dropped = { lastUploadedFingerprint = null },
        pushRoster = { proxy.pushRoster() },
        refreshAdvertisement = { proxy.refreshAdvertisement() },
        heartbeatIntervalMs = { _ui.value.scaleCapabilities?.heartbeat_interval_ms?.toLong() },
        sendScaleHeartbeat = { onCoreOutputJson(bridge.scaleHeartbeat()) },
        // The saver owns the sleep policy while shown: don't re-arm the DE1's
        // presence timer against our own idle decision (reaprime's
        // PresenceController model — the heartbeat keeps the machine awake
        // only while the user is actually present).
        // Also suppressed while the machine is asleep, saver or not: a manual
        // sleep leaves the app awake on the Brew screen, and a presence poke
        // there would argue with the sleep the user just asked for.
        // Suppressed mid-shot (issue #15): the machine can't sleep while it's
        // pouring, and the poke's MMR write would contend with the SAW stop
        // write on the serial GATT queue — the one write that must not wait.
        keepAliveTick = {
            val s = _ui.value
            val asleep = s.machineStateName == MachineState.Sleep ||
                s.machineStateName == MachineState.GoingToSleep
            // Foreground-gated (the reaprime PresenceController model): a
            // backgrounded app is not a present user, so it must stop
            // re-arming the DE1's sleep timer — otherwise pocketing the
            // phone with Crema open held the machine awake indefinitely.
            // The moment the pokes stop, the DE1's own ~30 min timer takes
            // over and sleeps the machine with no phone in range; the
            // screensaver clock (which keeps running in the background)
            // still issues the explicit earlier sleep when still connected.
            if (s.suppressDe1Sleep && appInForeground && !s.saverVisible && !asleep && !s.shotInProgress) {
                pokeUserPresent()
            }
        },
    )

    init {
        // Multi-device (M2): arm the recorder gate, start NSD peer discovery +
        // advertise this instance on the LAN. Runs here, not at construction —
        // it touches [bleRecorder]/[ble], which don't exist during the
        // controller's own field init.
        proxy.start()
        // Mirror the proxy controller's state slice into the UI snapshot (the
        // same pattern as [visualizer]/[drive] below).
        viewModelScope.launch {
            proxy.state.collect { ps ->
                _ui.update {
                    it.copy(
                        peers = ps.peers,
                        mirrorReconnecting = ps.mirrorReconnecting,
                        mirrorViewOnly = ps.mirrorViewOnly,
                        pendingPairing = ps.pendingPairing,
                        mirrorClients = ps.mirrorClients,
                        pendingHandoffOffer = ps.pendingHandoffOffer,
                    )
                }
            }
        }
        // Start the connection controller — the Bluetooth-adapter watcher, the
        // manager-state collectors, and the DE1 keep-awake tick — and mirror
        // its state slice into the UI snapshot.
        connection.start()
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
                    )
                }
            }
        }
        library.loadBuiltinProfiles()
        // Load both persisted stores in one coroutine, sequentially, so their
        // _ui copies don't race (read-modify-write of the shared snapshot).
        viewModelScope.launch {
            library.loadLibrary()
            library.loadHistory()
            loadPrefs()
            // Seed the learned SAW drip model into the core (it survives
            // bridge.reset() in-core, so startup is the only seed point).
            sawModelStore.load()?.let { blob ->
                runCatching { bridge.setSawModelJson(blob) }
                    .onFailure { appendLog("SAW model seed failed: ${it.message}") }
            }
            library.loadProfileMeta()
            library.loadCustomProfiles()
            loadMaintenance()
            visualizer.load()
            drive.load()
            // Daily automatic Drive backup (issue #36) — after both the prefs
            // (the toggle) and the Drive tokens have hydrated.
            drive.autoBackupIfDue(_ui.value.autoDriveBackup)
            // Daily automatic release check (opt-in, default off).
            maybeAutoUpdateCheck()
        }
        startScreensaverClock()
        // Mirror the Visualizer controller's state into the UI snapshot.
        viewModelScope.launch {
            visualizer.state.collect { vs ->
                _ui.update { it.copy(visualizer = vs) }
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
    fun setActiveProfile(id: String) {
        val prev = _ui.value
        // Switching profiles clears any Quick-Controls override (re-seeds from new).
        _ui.update { it.copy(activeProfileId = id, brewParams = null) }
        if (relayConfigOptimistic("setActiveProfile", id) {
                _ui.update { it.copy(activeProfileId = prev.activeProfileId, brewParams = prev.brewParams) }
            }
        ) return
        persistPrefs()
        pushStopTargets() // new profile's recipe yield/volume become the stop targets
    }

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

    /** Close the editor without saving (discards any new / duplicated draft). */
    fun cancelProfileEdit() = library.cancelProfileEdit()

    /** Delete a custom profile (no-op for built-ins). */
    fun deleteProfile(id: String) = library.deleteProfile(id)

    /** Archive (hide) a built-in profile from the active grid. */
    fun archiveBuiltinProfile(id: String) = library.archiveBuiltinProfile(id)

    /** Restore a hidden built-in profile back to the active grid. */
    fun unarchiveBuiltinProfile(id: String) = library.unarchiveBuiltinProfile(id)

    /** Export a profile's full community-v2 JSON via the system share sheet. */
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

    /** All profiles (built-ins + customs) as a JSON array, or null when empty. */
    fun allProfilesJson(): String? = library.allProfilesJson()

    /** Write export [text] to a SAF document Uri (large-payload safe). */
    fun writeTextToUri(uri: Uri, text: String) = library.writeTextToUri(uri, text)

    /** Write raw [bytes] to a SAF document Uri — the binary backup save. */
    fun writeBytesToUri(uri: Uri, bytes: ByteArray) = library.writeBytesToUri(uri, bytes)

    /** Import profiles (Crema / community-v2 / legacy .tcl, via the core). */
    fun importProfiles(uri: Uri) = library.importProfiles(uri)

    /** Import shots from a Crema / de1app / legacy export. */
    fun importShots(uri: Uri) = library.importShots(uri)

    /** Build the whole-app `crema-backup/v1` JSONL bundle, or null + a notice. */
    fun backupBundleJson(): String? = library.backupBundleJson()

    /** Wrap the backup JSONL + bean photos into a `.crema.zip`. */
    fun buildBackupZipBytes(): ByteArray? = library.buildBackupZipBytes()

    /** A suggested filename for the SAF create-document picker. */
    fun backupFileName(): String = library.backupFileName()

    /** Restore a `crema-backup` bundle from a SAF [uri]. */
    fun restoreBackup(uri: Uri, mode: RestoreMode) = library.restoreBackup(uri, mode)

    /** Apply a backup from raw bytes (SAF file or Google Drive). */
    suspend fun restoreBackupBytes(bytes: ByteArray, mode: RestoreMode) = library.restoreBackupBytes(bytes, mode)

    /** Parse + apply a `crema-backup` bundle's raw text. */
    fun restoreBackupFromText(text: String, mode: RestoreMode) = library.restoreBackupFromText(text, mode)

    /** Remove a stored shot from the local history. Persisted. */
    fun deleteShot(id: String) = library.deleteShot(id)

    /** Import a Beanconqueror export (JSON or chunked .zip) into the library. */
    fun importBeanconquerorUri(uri: Uri) = library.importBeanconquerorUri(uri)

    /** Open a bean in the editor (the `bean-edit` route reads this). */
    fun startEditBean(id: String) = library.startEditBean(id)

    /** Start a brand-new bean draft in the full editor. */
    fun startNewBean() = library.startNewBean()

    /** Apply edits to a bean (the editor's Save; find-or-create the roaster). */
    fun updateBean(id: String, roasterName: String, transform: (Bean) -> Bean) =
        library.updateBean(id, roasterName, transform)

    /** A FileProvider Uri for the camera to write a bag photo into. */
    fun newCameraOutputUri(beanId: String): Uri? = library.newCameraOutputUri(beanId)

    /** Store a captured / picked bag photo for [beanId]. Persisted. */
    fun setBeanImageFromUri(beanId: String, uri: Uri) = library.setBeanImageFromUri(beanId, uri)

    /** Remove a bean's bag photo. Persisted. */
    fun clearBeanImage(beanId: String) = library.clearBeanImage(beanId)

    /** Remove a bean bag; reselect the first remaining if it was active. */
    fun deleteBean(id: String) = library.deleteBean(id)

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
    fun addRoaster(name: String, website: String?, city: String?, country: String?, notes: String) =
        library.addRoaster(name, website, city, country, notes)

    /** Update a roaster's editable fields. Persisted. */
    fun updateRoaster(id: String, name: String, website: String?, city: String?, country: String?, notes: String) =
        library.updateRoaster(id, name, website, city, country, notes)

    /** Delete a roaster; detach its bags. Persisted. */
    fun deleteRoaster(id: String) = library.deleteRoaster(id)

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

    /** Apply a Quick-Controls brew override (dose/yield/brew-temp). Transient —
     *  not saved to the profile; baked into the next shot's upload by [startShot]. */
    fun quickAdjustBrew(dose: Double, yieldOut: Double, brewTemp: Double, preinf: Double? = null) {
        val prev = _ui.value.brewParams
        _ui.update { it.copy(brewParams = BrewParams(dose, yieldOut, brewTemp, preinf)) }
        if (relayConfigOptimistic("quickAdjustBrew", "$dose,$yieldOut,$brewTemp,${preinf ?: ""}") {
                _ui.update { it.copy(brewParams = prev) }
            }
        ) return
        pushStopTargets() // the new yield override becomes the live stop-at-weight target
        proxy.pushConfig() // mirrors track the primary's live override (issue 06)
    }

    /** Reset the Quick-Controls override back to the active profile's recipe. */
    fun resetBrewParams() {
        val prev = _ui.value.brewParams
        _ui.update { it.copy(brewParams = null) }
        if (relayConfigOptimistic("resetBrew", "") { _ui.update { it.copy(brewParams = prev) } }) return
        pushStopTargets() // drop the per-shot override; the profile recipe target takes over
        proxy.pushConfig() // mirrors drop their override too (issue 06)
    }

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
    private fun pushStopTargets() {
        val s = _ui.value
        val active = s.profiles.firstOrNull { it.id == s.activeProfileId }
        val profileYield = active?.yieldOut?.takeIf { it.isFinite() && it > 0f }
        val volumeLimit = active?.maxTotalVolumeMl?.takeIf { it > 0 }?.toFloat()
        val shotYield = s.brewParams?.yieldOut?.toFloat()?.takeIf { it.isFinite() && it > 0f }
        runCatching {
            bridge.setProfileTargetWeight(profileYield)
            bridge.setProfileVolumeLimit(volumeLimit)
            bridge.setShotTargetWeight(shotYield)
            // The beverage type drives the core's ShotCompleted disposition
            // (a cleaning run must never be recorded as a shot — review #40).
            // Pushed alongside the stop targets — not only on profile upload —
            // because activation can skip the upload (restore at startup, the
            // shot-start refresh) and bridge.reset() on reconnect rebuilds the
            // core.
            bridge.setActiveBeverageType(active?.beverageType ?: "espresso")
        }.onFailure { appendLog("Push stop targets failed: ${it.message}") }
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
            themeMode = p.themeMode,
            maxShotDurationS = p.maxShotDurationS,
            autoTare = p.autoTare,
            stopOnWeight = p.stopOnWeight,
            volumeStopWithScale = p.volumeStopWithScale,
            requireScale = p.requireScale,
            steamEco = p.steamEco,
            steamTwoTap = p.steamTwoTap,
            fanThresholdC = p.fanThresholdC,
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
            weightUnit = p.weightUnit,
            tempUnit = p.tempUnit,
            pressureUnit = p.pressureUnit,
            volumeUnit = p.volumeUnit,
            waterLevelUnit = p.waterLevelUnit,
            qcSteamTimeS = p.qcSteamTimeS,
            qcSteamFlowMlS = p.qcSteamFlowMlS,
            qcSteamTempC = p.qcSteamTempC,
            qcHotWaterTempC = p.qcHotWaterTempC,
            qcHotWaterVolumeMl = p.qcHotWaterVolumeMl,
            qcFlushTimeS = p.qcFlushTimeS,
            qcFlushTempC = p.qcFlushTempC,
            qcGrind = p.qcGrind,
        ) }
        runCatching { bridge.setMaxShotDuration(p.maxShotDurationS) }
        runCatching { bridge.setAutoTare(p.autoTare) }
        runCatching { bridge.setStopOnWeight(p.stopOnWeight) }
        runCatching { bridge.setVolumeStopWithScale(p.volumeStopWithScale) }
        // Re-seed the restored machine-register prefs (no-ops when disconnected;
        // the next connect's seedMachineSettings covers that case).
        seedMachineSettings()
        // Push the restored Quick-Controls steam / hot-water / flush params to the
        // machine too, so a restore takes effect without waiting for the next QC
        // edit. Each routes through routeWrite → a no-op when disconnected or
        // off-primary (the writes read the just-updated _ui state).
        applySteamHotWater()
        routeWrite("Set steam flow") { bridge.setSteamFlow(p.qcSteamFlowMlS) }
        routeWrite("Set flush time") { bridge.setFlushTimeout((p.qcFlushTimeS * 1000f).toUInt()) }
        routeWrite("Set flush temp") { bridge.setFlushTemp(p.qcFlushTempC) }
        persistPrefs()
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
        if (relayIfSecondary("startShot")) return
        val cremaJson = _ui.value.activeProfileId?.let { library.profileJson(it) }
        if (cremaJson == null) {
            notifyUser("Can\u2019t start shot \u2014 no profile selected")
            return
        }
        // Pre-shot scale gate (issue #29). Only when this shot actually
        // resolves a weight target (QC dial override, else the active
        // profile's own yield \u2014 the same precedence pushStopTargets uses): a
        // profile with no target never needed a scale, so it gets no noise
        // (the Decenza/reaprime "profile uses weight" gate).
        val s = _ui.value
        val activeProfile = s.profiles.firstOrNull { it.id == s.activeProfileId }
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
                notifyUser(
                    if (remembered) "Shot not started \u2014 reconnecting the scale\u2026 try again in a moment"
                    else "Shot not started \u2014 no scale connected, and \u201cRequire scale to start\u201d is on"
                )
                return
            }
            // Default: NON-blocking cue \u2014 a modal between the user and a
            // tamped puck is workflow poison. The #15 fix arms SAW mid-shot,
            // so a quick reconnect can still save this very shot.
            notifyUser(
                if (remembered) "Scale not connected \u2014 reconnecting\u2026 stop-at-weight is off until it lands"
                else "No scale connected \u2014 stop-at-weight is off for this shot"
            )
        }
        // The heavy chain below — several bridge calls including the full
        // profile-frame build in Rust — runs on the core lane instead of
        // the Main tap handler (review #28).
        viewModelScope.launch(coreDispatcher) { startShotOnCoreLane(cremaJson) }
    }

    private fun startShotOnCoreLane(cremaJson: String) {
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
        // Quick-Controls override: bake the transient dose/yield/brew-temp into the
        // uploaded profile (the web shell's lazy re-upload-with-overrides). The
        // library profile is untouched; only this one upload carries the override.
        val bp = _ui.value.brewParams
        val effectiveJson = if (bp != null) {
            runCatching {
                overrideBrewParamsJson(
                    cremaJson,
                    bp.dose.toFloat(),
                    bp.yieldOut.toFloat(),
                    bp.brewTemp.toFloat(),
                    bp.preinf?.toFloat(),
                    json,
                )
            }.getOrDefault(cremaJson)
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
            proceedToEspresso()
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
        // so the UI doesn't hang in "uploading" forever. On the core lane so
        // pendingBrew stays single-threaded.
        viewModelScope.launch(coreDispatcher) {
            delay(PROFILE_UPLOAD_TIMEOUT_MS)
            if (pendingBrew) {
                pendingBrew = false
                pendingUploadFingerprint = null
                _ui.update { it.copy(profileUploading = false, profileUploadProgress = null) }
                notifyUser("Profile upload timed out \u2014 shot not started")
            }
        }
    }

    /**
     * Issue the Espresso state request, optionally preceded by a pre-shot group
     * flush (issue 15 — Quick Controls / Settings "Pre-flush"). Mirrors the web
     * orchestration: observe the firmware's profile-download guard, then — when
     * pre-flush is on — request a FLUSH and wait for its `WaterSessionCompleted
     * (Flush)` (or a ceiling) before Espresso; otherwise go straight to Espresso.
     * The shot sequence owns the group while this runs (`rinsePending`) so a
     * concurrent steam auto-purge stands down. The machine writes are DE1-gated.
     */
    private fun proceedToEspresso() {
        viewModelScope.launch {
            delay(PROFILE_DOWNLOAD_GUARD_MS)
            if (!_ui.value.preFlush) {
                requestMachineState(MachineRequest.ESPRESSO)
                return@launch
            }
            rinsePending = true
            try {
                val flushed = CompletableDeferred<Unit>()
                pendingPreshotFlush = { flushed.complete(Unit) }
                requestMachineState(MachineRequest.FLUSH)
                // Wait out the flush (the WaterSessionCompleted(Flush) event
                // resolves the waiter); the ceiling is the backstop if missed.
                withTimeoutOrNull(PRESHOT_FLUSH_TIMEOUT_MS) { flushed.await() }
            } finally {
                pendingPreshotFlush = null
                rinsePending = false
            }
            requestMachineState(MachineRequest.ESPRESSO)
        }
    }

    /**
     * Auto-purge after a steam session (issue 15): a short deferred group flush
     * that clears the steam plumbing. Deferred so a user who immediately starts a
     * shot (which sets `rinsePending`) wins — the purge stands down rather than
     * racing the shot's own flush. DE1-gated.
     */
    private fun scheduleAutoPurge() {
        viewModelScope.launch {
            delay(AUTO_PURGE_DELAY_MS)
            // Bail if a shot/flush sequence grabbed the group during the defer.
            if (rinsePending || pendingBrew) return@launch
            requestMachineState(MachineRequest.FLUSH)
        }
    }

    /** Scan for and connect to a DE1 — delegate of [ConnectionController.connect].
     *  Called from the UI once the BLE runtime permissions have been granted. */
    fun connect() = connection.connect()

    /** Disconnect the DE1 — delegate of [ConnectionController.disconnect]. */
    fun disconnect() = connection.disconnect()

    /** A DE1 disconnect closed the session: stop the in-flight connect-time
     *  register sweep, drop the pending gated-start, and clear the live
     *  telemetry + machine identity so the Brew/Settings surfaces show "—"
     *  for the next connection rather than freezing on the last machine's
     *  values. Wired as [ConnectionController]'s onDe1SessionClosed. */
    private fun clearDe1SessionUi() {
        machineInfoJob?.cancel() // stop any in-flight connect-time register sweep
        pendingBrew = false
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
    // ── Multi-device: relaying + relayed dispatch (see [ProxyController]) ─────

    /** On a secondary, relay user-intent control [method] to the primary instead
     *  of running it locally; `true` = relayed, the caller must `return`. A thin
     *  delegate of [ProxyController.relayIfSecondary] so the control-path call
     *  sites read unchanged. */
    private fun relayIfSecondary(method: String, args: String = ""): Boolean =
        proxy.relayIfSecondary(method, args)

    /** Relay a config verb from a secondary, optimistic-apply style (issue 06);
     *  `true` = handled as a secondary, the caller must `return` (skipping its
     *  persist + machine write). A thin delegate of
     *  [ProxyController.relayConfigOptimistic]. */
    private fun relayConfigOptimistic(method: String, args: String, revert: () -> Unit): Boolean =
        proxy.relayConfigOptimistic(method, args, revert)

    /** Run a relayed control verb from a secondary on this (primary) device — the
     *  same verbs the primary's own UI calls, so `startShot` runs the full local
     *  shot orchestration and that complexity never crosses the wire. Wired in as
     *  [ProxyController]'s dispatch router; a throw on an unknown method becomes
     *  a `ControlErr` back to the peer. */
    private fun dispatchRelayedControl(method: String, args: String) {
        when (method) {
            "machineState" -> requestMachineState(MachineRequest.valueOf(args))
            "startShot" -> startShot()
            "tareScale" -> tareScale()
            // Config-authority verbs: the primary changes its (single-owner) config
            // and `persistPrefs`/`setActiveBean` push the result back to mirrors.
            "setActiveProfile" -> setActiveProfile(args)
            "setActiveBean" -> setActiveBean(args.ifEmpty { null })
            "setStopOnWeight" -> setStopOnWeight(args.toBoolean())
            "setAutoTare" -> setAutoTare(args.toBoolean())
            // Quick-Controls config verbs (issue 06): a secondary's edit drives the
            // primary's single-owner config, which `persistPrefs` pushes back.
            "setQcSteamTime" -> setQcSteamTime(args.toFloat())
            "setQcSteamFlow" -> setQcSteamFlow(args.toFloat())
            "setQcSteamTemp" -> setQcSteamTemp(args.toFloat())
            "setQcHotWaterTemp" -> setQcHotWaterTemp(args.toFloat())
            "setQcHotWaterVolume" -> setQcHotWaterVolume(args.toFloat())
            "setQcFlushTime" -> setQcFlushTime(args.toFloat())
            "setQcFlushTemp" -> setQcFlushTemp(args.toFloat())
            "setQcGrind" -> setQcGrind(args.toFloat())
            // The live Quick-Controls brew override (dose/yield/temp/preinf). CSV
            // "dose,yield,temp,preinf" — preinf empty = null. resetBrew clears it.
            "quickAdjustBrew" -> args.split(",").let { p ->
                quickAdjustBrew(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p.getOrNull(3)?.toDoubleOrNull())
            }
            "resetBrew" -> resetBrewParams()
            // M3 handoff: a secondary asks to take the DE1. Idle-only; on grant we
            // step down so the taker can acquire the radio.
            "handoff" -> proxy.grantHandoff()
            else -> error("unknown relayed control: $method")
        }
    }

    /** On a take-over (#01) the taker becomes the primary and must be able to
     *  actually drive shots with whatever profile it was mirroring — including a
     *  primary's custom profile it never had locally (issue 05). Promote the
     *  transient overlay JSON into this device's real library (de-duped by id) and
     *  make it the active selection. Set directly, not via [setActiveProfile]: we're
     *  still nominally a secondary at this point, so that path would relay instead. */
    private fun promoteMirroredProfile() {
        val raw = _ui.value.mirroredProfileJson ?: return
        val id = profileIdOf(raw, json)?.takeIf { it.isNotEmpty() } ?: return
        if (!library.hasProfile(id)) {
            library.adoptCustomProfile(raw)
            appendLog("Handoff: promoted mirrored profile into the library ($id)")
        }
        _ui.update { it.copy(activeProfileId = id, brewParams = null) }
    }

    /** Secondary side of an M3 handoff (the picker's "Take over") — delegate of
     *  [ProxyController.requestHandoff]. */
    fun requestHandoff() = proxy.requestHandoff()

    // ── Push handoff ("hand off TO X" — issue 07) ─────────────────────────────

    /** Primary side: offer the machine to a specific mirroring secondary. */
    fun offerHandoff(clientId: String) = proxy.offerHandoff(clientId)

    /** The user accepted a pushed offer → take the machine (the normal pull). */
    fun acceptHandoffOffer() = proxy.acceptHandoffOffer()

    /** The user declined a pushed offer — a local no-op (the host keeps the DE1). */
    fun declineHandoffOffer() = proxy.declineHandoffOffer()

    /** A one-line roaster · name · freshness summary of the active bean for the
     *  config snapshot (issue 05) so a mirror can render the bean chip even when
     *  the bean isn't in its own library. Mirrors `beanLine` on the Brew screen. */
    private fun activeBeanSummaryLine(): String? {
        val ui = _ui.value
        val bean = ui.beans.firstOrNull { it.id == ui.activeBeanId } ?: return null
        val roaster = ui.roasters.firstOrNull { it.id == bean.roasterId }?.name
        val days = daysOffRoast(bean.roastedOn)
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
        val ui = _ui.value
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
                weightUnit = ui.weightUnit,
                tempUnit = ui.tempUnit,
                pressureUnit = ui.pressureUnit,
                volumeUnit = ui.volumeUnit,
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
            ),
        )
    }.getOrDefault("")

    /** Apply a primary's pushed [ConfigSnapshot] on a secondary — snap this
     *  mirror's session config (active profile/bean, SAW, QC, units) to the
     *  primary's: the settings-drift fix. Display-only (the read-only core needs
     *  no command); per-device bindings (DE1/scale address) + UI prefs stay put.
     *  The profile id is applied only if this device has that profile, so a mirror
     *  never blanks its Brew screen on an unknown custom profile. */
    private fun applyRemoteConfig(jsonStr: String) {
        val cfg = runCatching { json.decodeFromString(ConfigSnapshot.serializer(), jsonStr) }.getOrElse {
            appendLog("Config snapshot parse failed: ${it.message}")
            return
        }
        // Decode the custom-profile overlay (issue 05) so the curve/targets/name
        // match the primary even for a profile this mirror never saved. A built-in
        // sends no JSON → overlay null → we fall back to the (guarded) library id.
        val overlay = cfg.activeProfileJson?.let { raw ->
            runCatching { json.decodeFromString(CremaProfile.serializer(), raw) }.getOrElse {
                appendLog("Mirror profile overlay parse failed: ${it.message}")
                null
            }
        }
        // Mirror the primary's live brew override (issue 06): the dose/yield/temp
        // travel together, so a snapshot with them set re-arms the override here;
        // all-null clears it (the profile recipe wins again).
        val brew = if (cfg.brewDoseG != null && cfg.brewYieldG != null && cfg.brewTempC != null) {
            BrewParams(cfg.brewDoseG!!, cfg.brewYieldG!!, cfg.brewTempC!!, cfg.brewPreinfuseS)
        } else {
            null
        }
        _ui.update {
            it.copy(
                activeProfileId = cfg.activeProfileId?.takeIf { id -> library.hasProfile(id) } ?: it.activeProfileId,
                mirroredProfile = overlay,
                mirroredProfileJson = overlay?.let { cfg.activeProfileJson },
                mirroredBeanSummary = cfg.activeBeanSummary,
                mirroringPrimaryName = cfg.primaryName,
                brewParams = brew,
                activeBeanId = cfg.activeBeanId,
                stopOnWeight = cfg.stopOnWeight,
                autoTare = cfg.autoTare,
                maxShotDurationS = cfg.maxShotDurationS,
                grinderModel = cfg.grinderModel,
                weightUnit = cfg.weightUnit,
                tempUnit = cfg.tempUnit,
                pressureUnit = cfg.pressureUnit,
                volumeUnit = cfg.volumeUnit,
                qcSteamTimeS = cfg.qcSteamTimeS,
                qcSteamFlowMlS = cfg.qcSteamFlowMlS,
                qcSteamTempC = cfg.qcSteamTempC,
                qcHotWaterTempC = cfg.qcHotWaterTempC,
                qcHotWaterVolumeMl = cfg.qcHotWaterVolumeMl,
                qcFlushTimeS = cfg.qcFlushTimeS,
                qcFlushTempC = cfg.qcFlushTempC,
                qcGrind = cfg.qcGrind,
            )
        }
        appendLog("Config from primary applied (pressure ${cfg.pressureUnit}, temp ${cfg.tempUnit}, profile ${cfg.activeProfileId})")
    }

    // ── Multi-device pairing (TOFU — issue 02) ────────────────────────────────
    // The gate itself lives in [ProxyController.authorizePeer]; the remembered
    // peers stay here (persisted with the app prefs, listed in Settings).

    /** The Activity resolves a pending [PairingPrompt] with the user's choice. */
    fun resolvePairing(choice: PairingChoice) = proxy.resolvePairing(choice)

    private fun rememberPaired(id: String, name: String, canControl: Boolean) {
        _ui.update {
            it.copy(pairedDevices = it.pairedDevices.filterNot { d -> d.id == id } + PairedDevice(id, name, canControl))
        }
        persistPrefs()
        appendLog("Paired device '$name' (${if (canControl) "control" else "mirror-only"})")
    }

    /** Revoke a paired device (Settings ▸ Paired devices). It re-prompts on its next
     *  connect; a currently-attached peer keeps its live session until then (an
     *  immediate cut would need to drop its session — a follow-up). */
    fun forgetPairedDevice(id: String) {
        _ui.update { it.copy(pairedDevices = it.pairedDevices.filterNot { d -> d.id == id }) }
        persistPrefs()
    }

    private fun requestMachineState(state: MachineRequest) {
        // A sleep the app asked for is deliberate, not idle: mark it so the
        // Sleep state report that follows doesn't raise the screensaver (see
        // [appAskedForSleep]). Marked before the relay so both ends of a
        // mirrored session (the secondary that tapped, the primary that
        // writes) treat it the same.
        if (state == MachineRequest.SLEEP) appSleepRequestedAtMs = SystemClock.elapsedRealtime()
        if (relayIfSecondary("machineState", state.name)) return
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

    /** Connect-time machine-info sweep (see [readMachineInfo]); cancelled on reconnect/disconnect. */
    private var machineInfoJob: kotlinx.coroutines.Job? = null

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
     * Also folds the two pure (no-BLE) core reads — the firmware-update status
     * and the resolved line frequency — straight into the snapshot. Safe to
     * call while disconnected: with no BLE link the read requests write nothing
     * and the pure reads still return; the UI just shows "—" until replies land.
     */
    /** Re-seed the machine registers Crema owns a preference for. The DE1 boots
     *  with its own firmware values and de1app rewrote these each connect, so
     *  without this the machine silently runs the firmware defaults (the
     *  constant-fan report, geota/crema#31). Safe to call any time — the writes
     *  route through [routeWrite], a no-op when disconnected. */
    private fun seedMachineSettings() {
        val ui = _ui.value
        val fan = ui.fanThresholdC.roundToInt().coerceIn(0, 60).toUByte()
        routeWrite("Seed fan threshold") { bridge.setFanThreshold(fan) }
        routeWrite("Seed steam two-tap stop") {
            bridge.setSteamTwoTapStop(if (ui.steamTwoTap) 1u else 0u)
        }
        // Re-push the persisted Quick-Controls machine params too — steam
        // temp/time + hot water (one cuuid_0B packet), steam flow, flush
        // time/temp. The DE1 forgets these across power cycles just like the
        // registers above; de1app and Decenza both re-send them at connect,
        // and without this a power-cycled machine ran firmware defaults until
        // the user next touched a QC dial.
        applySteamHotWater()
        routeWrite("Seed steam flow") { bridge.setSteamFlow(ui.qcSteamFlowMlS) }
        routeWrite("Seed flush time") { bridge.setFlushTimeout((ui.qcFlushTimeS * 1000f).toUInt()) }
        routeWrite("Seed flush temp") { bridge.setFlushTemp(ui.qcFlushTempC) }
        // Eco last: it read-modify-writes the steam packet on top of the
        // plain seed above. Only pushed when ON — eco-off IS the plain seed.
        if (ui.steamEco) {
            routeWrite("Seed steam eco") {
                bridge.enableSteamEcoMode(true, System.currentTimeMillis().toULong())
            }
        }
    }

    private fun readMachineInfo() {
        // Identity (firmware / board / model / serial), GHC presence, heater
        // voltage, flush temp, flow-calibration, refill-kit. Match the generated
        // MmrReg variant spellings. (GhcMode 0x803820 is intentionally NOT read:
        // it's dead in de1app + absent in reaprime — see audit F2/F3.)
        val registers = listOf(
            MmrReg.FIRMWARE_VERSION,
            MmrReg.CPU_BOARD_VERSION,
            MmrReg.MACHINE_MODEL,
            MmrReg.SERIAL_NUMBER,
            MmrReg.GHC_INFO,
            MmrReg.HEATER_VOLTAGE,
            // FlushTemp (0x803844): web reads it to show the live flush setpoint;
            // read it here too so the sweeps match (audit F5). reaprime-only register.
            MmrReg.FLUSH_TEMP,
            MmrReg.CALIBRATION_FLOW_MULTIPLIER,
            MmrReg.REFILL_KIT,
        )
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
            // frequency reflects whatever the sweep just brought in; firmware-
            // UpdateStatus() is logged and the de1Firmware label drives the row.
            runCatching { bridge.firmwareUpdateStatus() }.onSuccess { status ->
                appendLog("Firmware update status: $status")
            }.onFailure { appendLog("Firmware update status failed: ${it.message}") }
            val freq = runCatching { bridge.lineFrequencyHz() }.getOrNull()
            _ui.update { it.copy(lineFreqHz = freq) }
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
        _ui.update { it.copy(lineFreqHz = resolved, lineFreqOverride = hz) }
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
            if (candidate != null && candidate != current && candidate != _ui.value.lastSeenLatestVersion) {
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
        runCatching { bridge.setMaxShotDuration(seconds) }.onFailure {
            appendLog("Set max shot duration failed: ${it.message}")
        }
        if (seconds != null) {
            _ui.update { it.copy(maxShotDurationS = seconds) }
            viewModelScope.launch { settingsStore.save(currentPrefs()) }
        }
    }

    // ── Live mode switches (M2 — no restart) — see [ProxyController] ────────

    /** Stop relaying/mirroring: become a standalone NORMAL device. */
    fun switchToNormal() = proxy.switchToNormal()

    /** Hold the DE1 and relay it to others (becomes the primary). */
    fun switchToPrimary() = proxy.switchToPrimary()

    /** Mirror the DE1 from a primary at [host]:[port] — the picker's "Mirror from X". */
    fun switchToSecondary(host: String, port: Int) = proxy.switchToSecondary(host, port)

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
        preFlush = _ui.value.preFlush,
        steamPurge = _ui.value.steamPurge,
        chartChannels = _ui.value.chartChannels,
        keepScreenOnBrew = _ui.value.keepScreenOnBrew,
        screensaverAfterMin = _ui.value.screensaverAfterMin,
        sleepMachineWithSaver = _ui.value.sleepMachineWithSaver,
        wakeMachineWithSaver = _ui.value.wakeMachineWithSaver,
        autoDriveBackup = _ui.value.autoDriveBackup,
        autoUpdateCheck = _ui.value.autoUpdateCheck,
        lastUpdateCheckAtMs = _ui.value.lastUpdateCheckAtMs,
        lastSeenLatestVersion = _ui.value.lastSeenLatestVersion,
        grinderModel = _ui.value.grinderModel,
        suppressDe1Sleep = _ui.value.suppressDe1Sleep,
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
        proxyRole = _ui.value.proxyRole,
        proxyPrimaryHost = _ui.value.proxyPrimaryHost,
        proxyPrimaryPort = _ui.value.proxyPrimaryPort,
        replayPrimary = _ui.value.replayPrimary,
        pairedDevices = _ui.value.pairedDevices,
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
        val prev = _ui.value.autoTare
        _ui.update { it.copy(autoTare = enabled) }
        if (relayConfigOptimistic("setAutoTare", enabled.toString()) { _ui.update { it.copy(autoTare = prev) } }) return
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
        val prev = _ui.value.stopOnWeight
        _ui.update { it.copy(stopOnWeight = enabled) }
        if (relayConfigOptimistic("setStopOnWeight", enabled.toString()) { _ui.update { it.copy(stopOnWeight = prev) } }) return
        runCatching { bridge.setStopOnWeight(enabled) }.onFailure {
            appendLog("Set stop-on-weight failed: ${it.message}")
        }
        persistPrefs()
    }

    /**
     * Opt into the profile's max-volume stop racing stop-at-weight while a
     * scale is connected (Settings → Shot behaviour). Off (default) = volume
     * only applies as the no-scale fallback. Purely core-side (SAV) — the
     * firmware tail volume stop is never uploaded, so no re-upload needed;
     * a mid-shot flip re-targets the armed AutoStop via the core's refresh.
     */
    fun setVolumeStopWithScale(on: Boolean) {
        _ui.update { it.copy(volumeStopWithScale = on) }
        runCatching { bridge.setVolumeStopWithScale(on) }.onFailure {
            appendLog("Set volume-stop-with-scale failed: ${it.message}")
        }
        persistPrefs()
    }

    // ── Auto-connect (per device) ─────────────────────────────────────────────

    /** Per-device "Auto-connect" toggle for the DE1 — delegate of
     *  [ConnectionController.setDe1AutoConnect]. */
    fun setDe1AutoConnect(on: Boolean) = connection.setDe1AutoConnect(on)

    /** Per-device "Auto-connect" toggle for the scale (the scale-side twin). */
    fun setScaleAutoConnect(on: Boolean) = connection.setScaleAutoConnect(on)

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

    // ── Quick-Controls steam / hot-water / flush (issue 14) ──────────────────
    // Persisted QC overrides that stick + take priority. On change we update
    // UiState, persist, and write to the machine. Steam temp/time + hot-water
    // temp/vol share one packet (cuuid_0B) written read-modify-write so the
    // fields QC doesn't model (timeouts, espresso volume, group temp) survive;
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

    /** Write the combined steam + hot-water packet via read-modify-write,
     *  seeding the unmodeled fields from the machine's last-reported settings
     *  (or legacy defaults pre-read). */
    private fun applySteamHotWater() {
        val ui = _ui.value
        val settings = buildSteamHotWaterSettings(
            steamTempC = ui.qcSteamTempC,
            steamTimeoutS = ui.qcSteamTimeS,
            hotWaterTempC = ui.qcHotWaterTempC,
            hotWaterVolumeMl = ui.qcHotWaterVolumeMl,
            machine = machineShotSettings,
        )
        routeWrite("Set steam/hot-water") { bridge.setSteamHotwaterSettings(settings) }
    }

    /** Steam timeout, seconds (Quick Controls). Persisted; RMW write. */
    fun setQcSteamTime(seconds: Float) {
        val prev = _ui.value.qcSteamTimeS
        _ui.update { it.copy(qcSteamTimeS = seconds) }
        if (relayConfigOptimistic("setQcSteamTime", seconds.toString()) { _ui.update { it.copy(qcSteamTimeS = prev) } }) return
        persistPrefs()
        applySteamHotWater()
    }

    /** Steam flow rate, ml/s (Quick Controls). Persisted; standalone write. */
    fun setQcSteamFlow(mlPerS: Float) {
        val prev = _ui.value.qcSteamFlowMlS
        _ui.update { it.copy(qcSteamFlowMlS = mlPerS) }
        if (relayConfigOptimistic("setQcSteamFlow", mlPerS.toString()) { _ui.update { it.copy(qcSteamFlowMlS = prev) } }) return
        persistPrefs()
        routeWrite("Set steam flow") { bridge.setSteamFlow(mlPerS) }
    }

    /** Steam temperature, °C (Quick Controls). Persisted; RMW write. */
    fun setQcSteamTemp(tempC: Float) {
        val prev = _ui.value.qcSteamTempC
        _ui.update { it.copy(qcSteamTempC = tempC) }
        if (relayConfigOptimistic("setQcSteamTemp", tempC.toString()) { _ui.update { it.copy(qcSteamTempC = prev) } }) return
        persistPrefs()
        applySteamHotWater()
    }

    /** Hot-water temperature, °C (Quick Controls). Persisted; RMW write. */
    fun setQcHotWaterTemp(tempC: Float) {
        val prev = _ui.value.qcHotWaterTempC
        _ui.update { it.copy(qcHotWaterTempC = tempC) }
        if (relayConfigOptimistic("setQcHotWaterTemp", tempC.toString()) { _ui.update { it.copy(qcHotWaterTempC = prev) } }) return
        persistPrefs()
        applySteamHotWater()
    }

    /** Hot-water volume, ml (Quick Controls). Persisted; RMW write. */
    fun setQcHotWaterVolume(ml: Float) {
        val prev = _ui.value.qcHotWaterVolumeMl
        _ui.update { it.copy(qcHotWaterVolumeMl = ml) }
        if (relayConfigOptimistic("setQcHotWaterVolume", ml.toString()) { _ui.update { it.copy(qcHotWaterVolumeMl = prev) } }) return
        persistPrefs()
        applySteamHotWater()
    }

    /** Flush timeout, seconds (Quick Controls). Persisted; standalone write. */
    fun setQcFlushTime(seconds: Float) {
        val prev = _ui.value.qcFlushTimeS
        _ui.update { it.copy(qcFlushTimeS = seconds) }
        if (relayConfigOptimistic("setQcFlushTime", seconds.toString()) { _ui.update { it.copy(qcFlushTimeS = prev) } }) return
        persistPrefs()
        routeWrite("Set flush time") { bridge.setFlushTimeout((seconds * 1000f).toUInt()) }
    }

    /** Flush temperature, °C (Quick Controls). Persisted; standalone write. */
    fun setQcFlushTemp(tempC: Float) {
        val prev = _ui.value.qcFlushTempC
        _ui.update { it.copy(qcFlushTempC = tempC) }
        if (relayConfigOptimistic("setQcFlushTemp", tempC.toString()) { _ui.update { it.copy(qcFlushTempC = prev) } }) return
        persistPrefs()
        routeWrite("Set flush temp") { bridge.setFlushTemp(tempC) }
    }

    /**
     * Grinder click (Quick Controls). Persisted so it sticks + recorded onto the
     * next shot (issue 15). Grind never reaches the machine — it's a log/record
     * value — so there's no machine write, just persistence.
     */
    fun setQcGrind(clicks: Float) {
        val prev = _ui.value.qcGrind
        _ui.update { it.copy(qcGrind = clicks) }
        if (relayConfigOptimistic("setQcGrind", clicks.toString()) { _ui.update { it.copy(qcGrind = prev) } }) return
        persistPrefs()
    }

    /** Show/hide a live-chart channel (Quick Controls). Persisted display pref. */
    fun toggleChartChannel(key: String, enabled: Boolean) {
        val next = _ui.value.chartChannels.toMutableSet().apply {
            if (enabled) add(key) else remove(key)
        }
        _ui.update { it.copy(chartChannels = next) }
        persistPrefs()
    }

    // ── Display units (issue 44) ──────────────────────────────────────────────
    // Pure display prefs: nothing is sent to the machine and no canonical value
    // changes — flipping a unit only re-renders existing readouts through
    // `Format.kt`. Persisted so the choice survives a restart.

    /** Weight readout unit (`"g" | "oz"`). Persisted display pref. */
    fun setWeightUnit(unit: String) {
        _ui.update { it.copy(weightUnit = unit) }
        persistPrefs()
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
        _ui.update { it.copy(fanThresholdC = tempC) }
        persistPrefs()
        routeWrite("Set fan threshold") {
            bridge.setFanThreshold(tempC.roundToInt().coerceIn(0, 60).toUByte())
        }
    }

    /** Opt-in "require scale to start" block (geota/crema#29). Persisted. */
    fun setRequireScale(on: Boolean) {
        _ui.update { it.copy(requireScale = on) }
        persistPrefs()
    }

    /** DE1 firmware two-tap steam stop. Persisted + written to the machine
     *  immediately; re-seeded on every connect. */
    fun setSteamTwoTap(enabled: Boolean) {
        _ui.update { it.copy(steamTwoTap = enabled) }
        persistPrefs()
        routeWrite("Set steam two-tap stop") {
            bridge.setSteamTwoTapStop(if (enabled) 1u else 0u)
        }
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

    // ── Bean library — see [LibraryController] ──────────────────────────────

    /** Set (or with `null`, clear) the active bean (the Brew bean block). Persisted. */
    fun setActiveBean(id: String?) {
        val prev = _ui.value.activeBeanId
        _ui.update { it.copy(activeBeanId = id) }
        if (relayConfigOptimistic("setActiveBean", id ?: "") { _ui.update { it.copy(activeBeanId = prev) } }) return
        library.persistLibrary()
        proxy.pushConfig() // bean activation persists via the library, not prefs
        // Linked-profile auto-load (web activateBean parity). Only user acts
        // reach this fun — boot/library restore writes activeBeanId directly —
        // so firing here matches the "explicit activation only" rule.
        if (id != null) library.maybeLoadLinkedProfile(id)
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
            volumeStopWithScale = p.volumeStopWithScale,
            requireScale = p.requireScale,
            steamEco = p.steamEco,
            steamTwoTap = p.steamTwoTap,
            fanThresholdC = p.fanThresholdC,
            preFlush = p.preFlush,
            steamPurge = p.steamPurge,
            chartChannels = p.chartChannels,
            keepScreenOnBrew = p.keepScreenOnBrew,
            screensaverAfterMin = p.screensaverAfterMin,
            sleepMachineWithSaver = p.sleepMachineWithSaver,
            wakeMachineWithSaver = p.wakeMachineWithSaver,
            autoDriveBackup = p.autoDriveBackup,
            autoUpdateCheck = p.autoUpdateCheck,
            lastUpdateCheckAtMs = p.lastUpdateCheckAtMs,
            lastSeenLatestVersion = p.lastSeenLatestVersion,
            grinderModel = p.grinderModel,
            suppressDe1Sleep = p.suppressDe1Sleep,
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
            pairedDevices = p.pairedDevices,
        ) }
        // Push the persisted cap + behaviour toggles to the core so they're live
        // from launch (the core doesn't echo these back as events).
        runCatching { bridge.setMaxShotDuration(p.maxShotDurationS) }
        runCatching { bridge.setAutoTare(p.autoTare) }
        runCatching { bridge.setStopOnWeight(p.stopOnWeight) }
        runCatching { bridge.setVolumeStopWithScale(p.volumeStopWithScale) }
        prefsLoaded = true
        // Hydrate the remembered addresses, arm each manager's reconnect loop,
        // and cold-start auto-connect to each remembered device.
        connection.hydrateRemembered(p.de1Address, p.scaleAddress, p.scaleName)
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

    // ── Sleep & screensaver ───────────────────────────────────────────────────

    /** Monotonic ms of the last user interaction — bumped by the activity's
     *  root touch interceptor and by machine transitions into active modes. */
    @Volatile
    private var lastInteractionAtMs: Long = SystemClock.elapsedRealtime()

    /** Bump the idle clock. Called on every touch (MainActivity's root
     *  interceptor, Initial pass, never consumes) — cheap and lock-free. */
    fun noteUserInteraction() {
        lastInteractionAtMs = SystemClock.elapsedRealtime()
    }

    /** Whether the activity is started (visible). Presence follows this: the
     *  keep-awake heartbeat only runs foregrounded — see [keepAliveTick]. */
    @Volatile
    private var appInForeground = true

    /** Foreground/background transitions from [MainActivity]'s
     *  onStart/onStop. Returning counts as user presence: the idle clock is
     *  bumped (so the saver doesn't fire seconds after a long absence) and,
     *  when keep-awake is on, one immediate poke re-arms the DE1 — it may be
     *  minutes from its own sleep deadline after a background stretch. */
    fun setAppForeground(foreground: Boolean) {
        appInForeground = foreground
        if (foreground) {
            noteUserInteraction()
            if (_ui.value.suppressDe1Sleep) pokeUserPresent()
        }
    }

    /** Monotonic ms of the last sleep the app itself asked for (the Brew power
     *  button, the phone menu, a mirrored peer), or 0 once consumed. */
    @Volatile
    private var appSleepRequestedAtMs: Long = 0L

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
     *  re-arms). Coupled machine sleep only from Idle. */
    private fun startScreensaverClock() {
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
        s.shotInProgress || s.profileUploading ||
            s.machineStateName in setOf(
                MachineState.Espresso, MachineState.Steam, MachineState.HotWater,
                MachineState.HotWaterRinse, MachineState.Descale, MachineState.Clean,
            )

    /** Show the saver; when coupled, also put the DE1 to sleep (only from a
     *  known-Idle machine — de1app's start_sleep gate). */
    private fun showSaver() {
        _ui.update { it.copy(saverVisible = true) }
        if (_ui.value.sleepMachineWithSaver &&
            ble.state.value == De1BleManager.State.READY &&
            _ui.value.machineStateName == MachineState.Idle
        ) {
            sleep()
        }
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

    /** Toggle the coupled machine sleep. Persisted. */
    fun setSleepMachineWithSaver(on: Boolean) {
        _ui.update { it.copy(sleepMachineWithSaver = on) }
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
        val dial = _ui.value.qcGrind ?: return false
        val bean = _ui.value.beans.firstOrNull { it.id == _ui.value.activeBeanId } ?: return false
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

    // ── Multi-device proxy (M1, debug). Restart-to-apply. ────────────────────
    fun setProxyRole(role: String) {
        _ui.update { it.copy(proxyRole = role) }
        persistPrefs()
    }

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
            weightUnit = def.weightUnit,
            tempUnit = def.tempUnit,
            pressureUnit = def.pressureUnit,
            volumeUnit = def.volumeUnit,
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
        // Reset the maintenance counters too — a fresh-install state (baselines and
        // total zeroed, the three *AtMs = now so the readouts read "0 since").
        val freshMaintenance = defaultMaintenanceState(System.currentTimeMillis())
        maintenanceTotalLitres = 0.0
        lastTelemetryMs = null
        viewModelScope.launch {
            settingsStore.save(AppPrefs())
            maintenanceStore.save(freshMaintenance)
        }
        _ui.update { it.copy(
            maintenance = freshMaintenance,
            maintenanceReadout = computeMaintenanceReadout(freshMaintenance),
            themeMode = AppPrefs().themeMode,
        ) }
        // The library/history/custom-profile share (disk + UI rows + the
        // active-profile reseed via refreshProfiles).
        library.eraseAll()
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
        // Re-arm the once-per-connection low-battery warning (issue #29).
        scaleLowBatteryWarned = false
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
        if (relayIfSecondary("tareScale")) return
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
            is Command.WriteCharacteristic -> {
                // The core hands the exact bytes + which DE1 characteristic to
                // write them to. The BLE manager maps the target to a UUID and
                // writes; a profile-frame write also gets a synthesized
                // De1FrameAck. This is the machine-control path (AND5).
                val bytes = command.content.data
                    .map { it.toByte() }
                    .toByteArray()
                // A machine-state write during a shot IS the stop (issue #15) —
                // time it, so a field log shows exactly how long the stop took
                // to reach the machine (queue contention, retries, a wedged
                // link all show up here as milliseconds).
                val stopWrite = command.content.target == WriteTarget.De1RequestedState &&
                    _ui.value.shotInProgress
                // Coalesce stop writes: if one is already queued/waiting, a
                // second copy of the same Idle byte buys nothing — and every
                // extra copy is a stale-write risk after the machine stops.
                if (stopWrite && pendingStopWrite.get()?.isActive == true) {
                    appendLog("Stop write already in flight — not stacking another")
                    return
                }
                val queuedAt = if (stopWrite) SystemClock.elapsedRealtime() else 0L
                // Same guard: a failed machine write (e.g. the keep-alive poke as
                // the DE1 drops) must not become an uncaught crash.
                val job = viewModelScope.launch {
                    try {
                        // Stop writes ride the urgent lane (issue #15): they
                        // preempt any in-flight non-critical GATT op instead
                        // of queuing behind it on the global op mutex.
                        ble.writeCharacteristic(command.content.target, bytes, urgent = stopWrite)
                        if (stopWrite) {
                            appendLog("Stop write delivered in ${SystemClock.elapsedRealtime() - queuedAt} ms")
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        appendLog(
                            if (stopWrite) {
                                "STOP WRITE FAILED after ${SystemClock.elapsedRealtime() - queuedAt} ms: ${e.message}"
                            } else {
                                "DE1 write failed (${command.content.target}): ${e.message}"
                            },
                        )
                    }
                }
                if (stopWrite) {
                    pendingStopWrite.set(job)
                    job.invokeOnCompletion { pendingStopWrite.compareAndSet(job, null) }
                }
            }
        }
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
                if (shouldRaiseSaver(prevState, c.state, ::appAskedForSleep)) {
                    _ui.update { it.copy(saverVisible = true) }
                } else if (!asleep && wasAsleep && _ui.value.saverVisible) {
                    _ui.update { it.copy(saverVisible = false) }
                    noteUserInteraction()
                }
                if (mode || c.state == MachineState.Espresso) noteUserInteraction()
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
                        // Readable error copy for an error substate (null otherwise),
                        // from core so it matches web. Healthy substates clear it.
                        machineError = subStateErrorMessage(c.substate.string),
                        modeStartedAtMs = started,
                        modeElapsedMs = if (started == null) 0L else prev.modeElapsedMs,
                    )
                }
                updateModeTicker()
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
                    // Fresh shot — the previous stop attribution no longer applies.
                    lastStopReason = null,
                ) }
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
                // Integrate group flow into the running water total (the DE1 has no
                // cumulative counter). In-memory only — NOT persisted/recomputed per
                // sample (~25–40 Hz); flushed on shot / water-session completion.
                // SIDE EFFECT — stays OUTSIDE the update lambda so it accumulates
                // exactly once per frame even if the CAS retries (issue 11).
                // Monotonic clock (review #36): a wall-clock jump (NTP, DST)
                // used to distort the water integral; elapsedRealtime can't.
                accumulateMaintenance(t.group_flow, SystemClock.elapsedRealtime())
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
                appendLog(fmt("Water level: %.0fmm", event.content.level))
            }
            is Event.ShotSettingsRead -> {
                // Cache the machine's reported steam/hot-water settings so QC
                // changes can read-modify-write without clobbering the fields QC
                // doesn't model (timeouts, espresso volume, group temp) — issue 14.
                machineShotSettings = event.content
                // Also project into UI state: the mode chips + steam / hot-water
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
            is Event.StopTargetsArmed -> {
                // The SAW-visibility line (issue #15): what will stop this
                // shot, straight from the core's armed targets — a silent
                // arming failure now reads as "weight —" in the log.
                val c = event.content
                appendLog(
                    "Stop targets armed: weight " + (c.weight?.let { fmt("%.1f g", it) } ?: "\u2014") +
                        " · volume " + (c.volume?.let { fmt("%.0f ml", it) } ?: "\u2014") +
                        " · max " + (c.max_time?.let { fmt("%.0f s", it) } ?: "\u2014"),
                )
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
                // Release the pre-shot flush waiter so the shot can proceed to
                // Espresso (issue 15) — only for a group flush, not a hot-water pour.
                if (event.content.kind == WaterSessionKind.Flush) {
                    pendingPreshotFlush?.invoke()
                }
            }
            is Event.SteamSessionStarted -> appendLog("Steam session started")
            is Event.SteamSessionCompleted -> {
                appendLog("Steam session completed: ${event.content.duration}ms")
                // Auto-purge after steam (issue 15 — Quick Controls / Settings
                // "Steam purge"). A short group flush clears the plumbing once the
                // user releases steam; gated on the pref + stands down if a shot
                // flush already owns the group. DE1-gated.
                if (_ui.value.steamPurge) scheduleAutoPurge()
            }
            is Event.SteamClogSuspected ->
                appendLog("Steam clog suspected: ${event.content.reason.string}")
            is Event.SteamEcoModeChanged ->
                appendLog("Steam eco mode: ${event.content.eco}")
            is Event.ScaleStale -> {
                _ui.update { it.copy(scaleWeightG = null) }
                appendLog("Scale stale")
                // Escalation (Decenza treats an in-shot feed stall as a link
                // fault; reaprime probes on silence): the core flags staleness
                // after 1 s without a reading. Scales stream weight
                // continuously by design, so give the stream a short grace to
                // resume — a fresh reading repopulates scaleWeightG — and if
                // it stays silent while the link still claims READY, presume
                // the link dead and let the reconnect supervisor recover it.
                // Covers scales with no keep-alive writes (Bookoo), where the
                // consecutive-write-failure detector never gets a probe.
                scaleStaleJob?.cancel()
                scaleStaleJob = viewModelScope.launch {
                    delay(SCALE_STALE_TEARDOWN_GRACE_MS)
                    if (_ui.value.scaleState == ScaleBleManager.State.READY &&
                        _ui.value.scaleWeightG == null
                    ) {
                        scale.presumeDead(
                            "no readings for ${(1_000 + SCALE_STALE_TEARDOWN_GRACE_MS) / 1_000}s",
                        )
                    }
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
                // firmware's profile-download guard window, optionally pre-flush,
                // then request Espresso (issue 15).
                if (pendingBrew) {
                    pendingBrew = false
                    proceedToEspresso()
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
        val stamped = fmt("%s  %s", 
            android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()),
            line,
        )
        // Mirror into the crash-report ring buffer — it outlives the UI's
        // eventLog (which dies with the process). See coffee.crema.diag.DiagLog.
        coffee.crema.diag.DiagLog.add(stamped)
        val log = (listOf(stamped) + _ui.value.eventLog).take(MAX_LOG_LINES)
        _ui.update { it.copy(eventLog = log) }
    }

    override fun onCleared() {
        super.onCleared()
        // Adapter watcher off, scans cancelled, both device links down…
        connection.close()
        // Close the transport last — it unregisters the Nordic environment's
        // Bluetooth receiver and tears down the central manager. The managers'
        // disconnect() calls above are fire-and-forget coroutines; closing the
        // transport also cancels its scope, so any in-flight disconnect ends.
        proxy.close()
    }

    private companion object {
        const val MAX_LOG_LINES = 200

        /** How long a Sleep state report is still attributable to our own
         *  request (BLE write → firmware → notify, plus a GoingToSleep dwell). */
        const val APP_SLEEP_WINDOW_MS = 15_000L

        /**
         * Wait after `ProfileUploadCompleted` before requesting Espresso — the
         * firmware's profile-download guard window. A state request inside it is
         * aborted to HeaterDown (BC 9788201734); the web uses the same 500 ms.
         */
        const val PROFILE_DOWNLOAD_GUARD_MS = 500L

        /** Abort a pending gated-start brew if the upload never completes. */
        const val PROFILE_UPLOAD_TIMEOUT_MS = 15_000L

        /** Ceiling on the pre-shot group flush before proceeding to Espresso
         *  anyway (issue 15) — the backstop if WaterSessionCompleted is missed.
         *  Matches the web shell's 30 s. */
        const val PRESHOT_FLUSH_TIMEOUT_MS = 30_000L

        /** Defer before a post-steam auto-purge so an immediate shot wins the
         *  group (issue 15). */
        const val AUTO_PURGE_DELAY_MS = 1_500L

        /** Grace after the core's ScaleStale (itself 1 s of silence) before
         *  the scale link is presumed dead and torn down for the reconnect
         *  supervisor. Long enough that a radio hiccup resumes on its own,
         *  short enough that a mid-shot stall recovers within the pour. */
        const val SCALE_STALE_TEARDOWN_GRACE_MS = 5_000L
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

/**
 * Build the steam + hot-water settings packet (cuuid_0B) for a read-modify-write
 * (issue 14). The four QC-modeled fields come from the persisted Quick-Controls
 * values; the rest are preserved from the machine's last-reported settings
 * ([machine], via `Event.ShotSettingsRead`) so a QC steam/water change doesn't
 * reset the machine's timeouts / espresso volume / group temp. Pre-read, the
 * legacy-app defaults stand in. `steamFlags` is always 0 (legacy parity; bits
 * undocumented). Top-level + `internal` so it's pure and unit-testable without a
 * device (the actual BLE write isn't).
 */
/** Sleep and its GoingToSleep run-up both count as "the machine is down". */
internal fun MachineState?.isAsleep(): Boolean =
    this == MachineState.Sleep || this == MachineState.GoingToSleep

/**
 * Does this machine-state report raise the screensaver?
 *
 * Only an *external* sleep does — a GHC tap, or the DE1's own sleep timer.
 * Three things must not:
 *  • a sleep the app itself asked for ([appAskedForSleep], the power button):
 *    it's a deliberate act, not an idle one. Raising the saver over it made
 *    manual sleep near-unusable — the next tap dismissed the saver and woke
 *    the machine straight back up;
 *  • the first report after connect ([prev] == null): connecting to an
 *    already-asleep DE1 must not hijack the UI (Decenza's startup grace);
 *  • a report that only re-states a sleep we're already in.
 *
 * [appAskedForSleep] is a lambda, and called last, because it *consumes* the
 * marker: it may only be spent on a real awake→asleep transition, never on
 * the unrelated state reports that can land between the write and the sleep.
 * Top-level + `internal` so the decision is pure and unit-testable without a
 * DE1 (the BLE round-trip that drives it isn't).
 */
internal fun shouldRaiseSaver(
    prev: MachineState?,
    next: MachineState,
    appAskedForSleep: () -> Boolean,
): Boolean {
    if (prev == null || !next.isAsleep() || prev.isAsleep()) return false
    return !appAskedForSleep()
}

internal fun buildSteamHotWaterSettings(
    steamTempC: Float,
    steamTimeoutS: Float,
    hotWaterTempC: Float,
    hotWaterVolumeMl: Float,
    machine: EventShotSettingsReadInner?,
): SteamHotWaterSettings = SteamHotWaterSettings(
    steamFlags = 0u,
    steamTempC = steamTempC,
    steamTimeoutS = steamTimeoutS,
    hotWaterTempC = hotWaterTempC,
    hotWaterVolumeMl = hotWaterVolumeMl,
    hotWaterTimeoutS = machine?.hot_water_timeout ?: 60f,
    espressoVolumeMl = machine?.espresso_volume ?: 200f,
    groupTempC = machine?.group_temp ?: 92f,
)
