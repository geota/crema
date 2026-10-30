package coffee.crema.settings

import android.content.Context
import coffee.crema.core.CommonSettings
import coffee.crema.persist.eraseStore
import coffee.crema.persist.loadStore
import coffee.crema.persist.saveStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import coffee.crema.core.defaultCommonSettingsJson
import coffee.crema.core.resetSettingsJson
import coffee.crema.core.sanitizeCommonSettingsJson
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/*
 * App preferences — the user's display/behaviour settings, persisted via the
 * same file-JSON pattern as the bean library and shot history.
 *
 * v1 holds just the theme mode; units (weight/temp/pressure) and other prefs are
 * additive later (older records deserialise cleanly via defaults).
 */

/** Decodes the core's settings JSON (the typeshare'd [CommonSettings]). */
private val coreSettingsJson = Json { ignoreUnknownKeys = true }

/**
 * The core's default settings bundle (`default_common_settings`, the one both
 * shells build their defaults from), decoded once. The [AppPrefs] defaults
 * read it, so Android holds no copy of the shared defaults. Its "unset reads
 * as" fields are resolved; the unsets that mean something (`idleSleepMin`,
 * `brewCue*`, the water thresholds, `baristaName`) stay null.
 */
internal object CommonDefaults {
    val common: CommonSettings by lazy {
        // The core is required to run at all; if its native library can't
        // load, fail here with a plain reason rather than a bare link error
        // from deep inside the first AppPrefs() construction.
        val json = try {
            defaultCommonSettingsJson()
        } catch (e: Throwable) {
            throw IllegalStateException("The Crema core (libde1_ffi) didn't load, so the settings defaults are unavailable", e)
        }
        coreSettingsJson.decodeFromString(CommonSettings.serializer(), json)
    }
}

/** Read any settings blob through the core's `sanitize_common_settings`: a
 *  wrong-typed field falls back to its default without dropping the rest, and
 *  bad values are repaired. */
fun sanitizeCommonSettings(blob: JsonElement): CommonSettings =
    coreSettingsJson.decodeFromString(CommonSettings.serializer(), sanitizeCommonSettingsJson(blob.toString()))

/** [sanitizeCommonSettings] for a typed [CommonSettings]. */
fun sanitizeCommonSettings(c: CommonSettings): CommonSettings =
    sanitizeCommonSettings(coreSettingsJson.encodeToJsonElement(CommonSettings.serializer(), c))

/** "Restore defaults" — the core's `reset_settings`: these prefs with every
 *  common setting back to its default except the weight unit; the line
 *  frequency [lineFrequencyHz] (the override, `0` = auto) is kept too and
 *  returned for the caller to apply. Platform extras are untouched. */
fun AppPrefs.resetCommon(lineFrequencyHz: Float): Pair<AppPrefs, Float> {
    val out = coreSettingsJson.parseToJsonElement(
        resetSettingsJson(coreSettingsJson.encodeToString(CommonSettings.serializer(), toCommonSettings()), lineFrequencyHz),
    ).jsonObject
    val common = coreSettingsJson.decodeFromJsonElement(CommonSettings.serializer(), out.getValue("common"))
    val hz = (out["lineFrequencyHz"] as? JsonPrimitive)?.floatOrNull ?: 0f
    return copy(idleSleepMin = common.idleSleepMin?.toInt()).withCommonSettings(common) to hz
}

@Serializable
data class AppPrefs(
    /** `"system" | "light" | "dark"` — drives CremaTheme. Defaults to dark
     *  (the machine app is dark-skinned). */
    val themeMode: String = CommonDefaults.common.themeMode,
    /** Max shot duration cap, seconds (`0` = none). Pushed to the core on load
     *  + shown as a Time stop-condition on Brew. Defaults OFF — the profile
     *  dictates shot length; a silent 45 s cap truncated long profiles with no
     *  cue (geota/crema#32). Existing users keep their saved value. */
    val maxShotDurationS: Float = CommonDefaults.common.maxShotDurationS,
    // ── Shot behaviour (re-applied to the core on launch) ────────────────────
    /** Auto-tare the scale on shot start. Defaults ON to match the core's own
     *  default ([CremaCore] `auto_tare = true`) and the web shell (which
     *  defaults `autoTareOnShotStart` true). Android historically defaulted
     *  false, so a fresh install silently shipped auto-tare DISABLED — the scale
     *  never zeroed at shot start, unlike web. Existing users keep their saved
     *  value (this only changes the no-prefs / absent-key default). */
    val autoTare: Boolean = CommonDefaults.common.autoTare,
    /** Stop the shot at the yield target (stop-on-weight). Defaults ON for the
     *  same web/core parity reason as [autoTare] (web defaults `stopOnWeight`
     *  true); a no-op when no scale or yield target is present. */
    val stopOnWeight: Boolean = CommonDefaults.common.stopOnWeight,
    /** Opt-in: arm the profile's max-volume stop even while a scale is
     *  connected. Default off — volume is a no-scale fallback, never a
     *  competitor to stop-at-weight (reference-app consensus; the firmware's
     *  own tail volume stop is never uploaded either way). */
    val volumeStopWithScale: Boolean = CommonDefaults.common.volumeStopWithScale!!,
    /** Opt-in: refuse to start a shot when stop-at-weight would arm (a weight
     *  target resolves) but no scale is connected — the de1app
     *  `start_espresso_only_if_scale_connected` / reaprime `blockOnNoScale`
     *  pattern, both also default-off (geota/crema#29). Off = warn
     *  non-blockingly instead. */
    val requireScale: Boolean = CommonDefaults.common.requireScale!!,
    val steamEco: Boolean = CommonDefaults.common.steamEco,
    /** DE1 firmware two-tap steam stop (MMR `SteamTwoTapStop`): first stop tap
     *  ends steam without the wand auto-purge; a second tap purges. Written on
     *  change + re-seeded on connect (geota/crema#34). */
    val steamTwoTap: Boolean = CommonDefaults.common.steamTwoTap!!,
    /** Fan-on temperature threshold, °C (MMR `FanThreshold`, 0..=60 —
     *  de1app/Decenza's range; 55 splits their 60 and reaprime's 50).
     *  Re-seeded on every connect — the DE1 boots with a low firmware
     *  default, so without this the fan runs near-constantly
     *  (geota/crema#31). */
    val fanThresholdC: Float = CommonDefaults.common.fanThresholdC!!,
    /** Tablet charging from the DE1's USB port (Decenza `BatteryManager`
     *  modes): `"alwaysOn"` (the default, as decaid), `"smart"` (55–65 %) or
     *  `"smartHigh"` (90–95 %). Asserted on connect and every minute after. */
    val usbChargingMode: String = CommonDefaults.common.usbChargingMode!!,
    /** Flush the group before each shot. */
    val preFlush: Boolean = CommonDefaults.common.preFlush,
    /** Run a short group flush after steaming (app-level; distinct from the
     *  firmware's own wand purge — see [steamTwoTap]). */
    val steamPurge: Boolean = CommonDefaults.common.steamPurge,
    // ── Units (issue 44) ──────────────────────────────────────────────────────
    /** Weight unit for dose/yield/scale readouts — `"g" | "oz"`. */
    val weightUnit: String = CommonDefaults.common.weightUnit,
    /** Temperature unit for every temp readout — `"C" | "F"`. */
    val tempUnit: String = CommonDefaults.common.tempUnit,
    /** Pressure unit for the pressure channel — `"bar" | "psi"`. */
    val pressureUnit: String = CommonDefaults.common.pressureUnit,
    /** Volume unit for water/dispensed readouts — `"ml" | "floz"`. */
    val volumeUnit: String = CommonDefaults.common.volumeUnit,
    /** Water-tank readout style — `"ml" | "percent"` (geota/crema#33). */
    val waterLevelUnit: String = CommonDefaults.common.waterLevelUnit!!,
    /** Low-water warning threshold, canonical ml (geota/crema#33 follow-up).
     *  Null = unset → the 110 ml (~10%) default; 0 disables. The Settings
     *  row dials it in [waterLevelUnit]. */
    val waterWarnMl: Float? = null,
    /** The DE1's own refill threshold, RAW sensor mm — pushed to the machine at
     *  connect (geota/crema#47). Null = unset → core's 5 mm default, matching
     *  de1app and Decenza. */
    val waterRefillPointMm: Float? = null,
    // ── Display ──────────────────────────────────────────────────────────────
    /** Live-chart channel keys (Quick Controls chart strip). */
    val chartChannels: Set<String> = CommonDefaults.common.chartChannels.toSet(),
    /** Hold FLAG_KEEP_SCREEN_ON while a shot is pulling. */
    val keepScreenOnBrew: Boolean = CommonDefaults.common.keepScreenOnBrew,
    /** Guided-brew cue sound (issue #10). Null = never chosen → off
     *  ([coffee.crema.ui.brewlog.BrewCueDefaults]); only a toggle stores a value. */
    val brewCueSound: Boolean? = null,
    /** Guided-brew cue vibration. Null = never chosen → on. */
    val brewCueHaptics: Boolean? = null,
    // ── Machine sleep ────────────────────────────────────────────────────────
    /** "Sleep after": idle minutes before Crema puts the DE1 to sleep — one
     *  of the core's `idleSleepChoices()`, 0 = Never (de1app
     *  `screen_saver_delay`, Decenza `autoSleepMinutes`). Cross-shell
     *  (`CommonSettings.idleSleepMin`). Null = never set: [SettingsStore.load]
     *  migrates it from the old screensaver coupling, or uses the default (30). */
    val idleSleepMin: Int? = null,
    // ── Screensaver (platform extras — deliberately NOT in CommonSettings; the
    // backup design keeps screensaver per-shell) ───────────────────────────────
    /** Idle minutes before the screensaver shows; 0 = never. Display only — the
     *  machine's sleep is [idleSleepMin]'s. */
    val screensaverAfterMin: Int = 30,
    /** LEGACY, read only to migrate into [idleSleepMin]: the old "Sleep machine
     *  with screensaver" coupling, the only app-side idle sleep before the
     *  idle timer. No longer shown or acted on. */
    val sleepMachineWithSaver: Boolean = true,
    /** Wake a sleeping DE1 when the saver is dismissed; OFF = the tap only
     *  clears the overlay and the machine stays asleep. */
    val wakeMachineWithSaver: Boolean = true,
    /** Put the DE1 to sleep when Crema is QUIT (task swiped from recents) —
     *  never on mere backgrounding. Best-effort (see `QuitSleep`); only
     *  fires while the machine is idle and this device is the primary.
     *  Platform extra — the swipe-away signal is Android-only. */
    val sleepOnQuit: Boolean = true,
    /** Keep the DE1 connected — and auto-reconnecting — while the screen is OFF,
     *  via a foreground service (geota/crema#65). `null` = unset → use the
     *  form-factor default (ON for tablets, OFF for phones). When effectively ON
     *  the service still only engages while the device is on external power, so a
     *  battery device never drains and never shows the notification unless it's
     *  charging. Platform extra — Android-only, per-device. */
    val keepConnectedScreenOff: Boolean? = null,
    /** Show the inline debug / event-log panel in Settings → Advanced
     *  (web `showDebugPanel`). */
    val showDebugPanel: Boolean = CommonDefaults.common.showDebugPanel,
    /** Daily automatic Google Drive backup while Drive is linked (issue #36).
     *  Platform extra — per-device like the screensaver, not in
     *  CommonSettings. Default ON: linking Drive expresses backup intent,
     *  and the toggle is a no-op until it's linked. */
    val autoDriveBackup: Boolean = true,
    /** Daily automatic release check against GitHub (issue #36 follow-up).
     *  Default OFF — Crema never phones home unless the user opts in; the
     *  manual About → Check button always works. Platform extra (update
     *  checking is Android-only; web auto-deploys). */
    val autoUpdateCheck: Boolean = false,
    /** Epoch ms of the last automatic release-check ATTEMPT — stamped up
     *  front so a failing check retries tomorrow, not on every launch. */
    val lastUpdateCheckAtMs: Long? = null,
    /** The newest version the user was already notified about, so a new
     *  build notifies exactly once. */
    val lastSeenLatestVersion: String? = null,
    /** The method of the last saved Brew Log entry — the log form opens on
     *  it (issue #10; the web keeps the same per-device memory in
     *  localStorage). Null = never logged; the core then opens on pourover. */
    val lastBrewLogMethod: String? = null,
    // ── Brew defaults (seed new profiles + the Quick-Controls fallbacks) ─────
    val defaultDoseG: Float = CommonDefaults.common.defaultDoseG,
    val defaultRatio: Float = CommonDefaults.common.defaultRatio,
    val defaultBrewTempC: Float = CommonDefaults.common.defaultBrewTempC,
    val defaultPreinfuseS: Float = CommonDefaults.common.defaultPreinfuseS,
    /** Equipment-level grinder model (web `grinderModel`) — free text shown on
     *  Brew + sent with Visualizer uploads/patches. Blank = unset. */
    val grinderModel: String = CommonDefaults.common.grinderModel,
    /** Default barista name (web `baristaName`) — stamped on each new shot and
     *  brew log. Blank = unset. */
    val baristaName: String = CommonDefaults.common.baristaName.orEmpty(),
    // ── Quick-Controls steam / hot-water / flush (issue 14) ──────────────────
    /** Persisted QC overrides for the machine's steam / hot-water / flush params.
     *  These stick (no throwaway state) and take priority; on change they're
     *  applied to the machine via read-modify-write (the machine's reported
     *  timeouts / espresso volume / group temp are preserved). Defaults mirror
     *  the former QuickControls seed values. */
    val qcSteamTimeS: Float = CommonDefaults.common.qcSteamTimeS,
    val qcSteamFlowMlS: Float = CommonDefaults.common.qcSteamFlowMlS,
    val qcSteamTempC: Float = CommonDefaults.common.qcSteamTempC,
    val qcHotWaterTempC: Float = CommonDefaults.common.qcHotWaterTempC,
    val qcHotWaterVolumeMl: Float = CommonDefaults.common.qcHotWaterVolumeMl,
    val qcFlushTimeS: Float = CommonDefaults.common.qcFlushTimeS,
    val qcFlushTempC: Float = CommonDefaults.common.qcFlushTempC,
    /** Persisted Quick-Controls grinder click (issue 15). Null = never dialed —
     *  the stepper shows a seed value and shots record no grind until the user
     *  sets it; once set it sticks and is recorded onto each shot. */
    val qcGrind: Float? = null,
    // ── Session restore ──────────────────────────────────────────────────────
    /** The last active profile id, restored on launch (web `crema.profiles.activeId`). */
    val activeProfileId: String? = null,
    // ── Connection (auto-connect is per-device = a remembered address) ───────
    /** Remembered DE1 Bluetooth address — the auto-connect target (reconnect on
     *  an unexpected drop + connect on launch). Non-null ⟺ the DE1's
     *  "Auto-connect" is ON; turning the toggle OFF clears it (forget). */
    val de1Address: String? = null,
    /** Remembered scale Bluetooth address (same per-device auto-connect rule). */
    val scaleAddress: String? = null,
    /** The remembered scale's advertised name — lets a background reconnect
     *  connect DIRECTLY by address (no scan; Android throttles unfiltered
     *  scans to zero with the screen off, reaprime #107) while still telling
     *  the core codec which scale it is. Additive → nullable. */
    val scaleName: String? = null,
    // ── Multi-device LAN proxy: hub and remotes ──────────────────────────────
    /** Proxy role — `"normal" | "primary" | "secondary"` (users see normal,
     *  hub, remote). `normal` = today's single-device behaviour. Set live by the
     *  Hub mode switch and "Connect through"; the device restarts in it. */
    val proxyRole: String = "normal",
    /** The hub a remote connects through: its host — an IP, or `10.0.2.2` to
     *  reach an `adb forward`ed port on the dev host from an emulator. Also the
     *  picker's manual hub in every role. */
    val proxyPrimaryHost: String = "",
    /** The hub's relay port (see [proxyPrimaryHost]). */
    val proxyPrimaryPort: Int = 0,
    /** Debug/emulator only: when a `primary` starts, **replay the newest captured
     *  shot as a fake DE1** instead of using live Bluetooth. Default OFF — a real
     *  primary always uses the radio. This must be opt-in: otherwise a primary's
     *  own session recordings (in `captures/`) get auto-replayed on the next launch,
     *  hijacking it into a fake DE1 + blocking the real DE1/scale. Set it in
     *  `prefs.json` for an emulator with no Bluetooth. */
    val replayPrimary: Boolean = false,
    // ── Companion-device association (Android CDM, optional per device) ──────
    /** The remembered DE1's companion association id, or null when it has none.
     *  [COMPANION_ID_UNKNOWN] = associated on API 31–32, where the platform keeps
     *  no id (the address is the key). The system's list stays the source of
     *  truth: a removal in system settings clears this on the next refresh. */
    val de1CompanionId: Int? = null,
    /** The remembered scale's companion association id (same rules). */
    val scaleCompanionId: Int? = null,
    /** Device addresses whose "Reconnect when nearby" offer was declined —
     *  never offered again (Settings still offers it). */
    val companionDeclined: List<String> = emptyList(),
)

/**
 * Resolve the idle-sleep setting on load. A stored value wins. A settings file
 * from before the idle timer migrates the old screensaver coupling, but only a
 * value the user chose: a saver changed from its old 30 min default to N, with
 * "Sleep machine with screensaver" on, becomes an idle sleep of N, snapped to
 * a choice; everyone else gets the default (30). No file at all (a fresh
 * install) is the default too. [fromSaver] / [defaultMin] are the core's rules
 * (`idleSleepMinutesFromSaver`, `defaultIdleSleepMin`), injectable so the JVM
 * tests run without the native library.
 */
internal fun resolveIdleSleep(
    stored: AppPrefs?,
    fromSaver: (Int, Boolean) -> Int = { saver, on -> coffee.crema.core.idleSleepMinutesFromSaver(saver, on).toInt() },
    defaultMin: () -> Int = { coffee.crema.core.defaultIdleSleepMin().toInt() },
): AppPrefs = when {
    stored == null -> AppPrefs(idleSleepMin = defaultMin())
    stored.idleSleepMin != null -> stored
    else -> stored.copy(idleSleepMin = fromSaver(stored.screensaverAfterMin, stored.sleepMachineWithSaver))
}

/** A companion association that exists but has no platform id (API 31–32). */
const val COMPANION_ID_UNKNOWN = -1

/** Map the portable subset of [AppPrefs] → the shared cross-shell [CommonSettings].
 *  Android's field names + chart-channel vocabulary ARE the canonical shape, so
 *  this is a near-identity projection (the web shell does the renaming). */
fun AppPrefs.toCommonSettings(): CommonSettings = CommonSettings(
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
    chartChannels = chartChannels.toList(),
    keepScreenOnBrew = keepScreenOnBrew,
    brewCueSound = brewCueSound,
    brewCueHaptics = brewCueHaptics,
    showDebugPanel = showDebugPanel,
    defaultDoseG = defaultDoseG,
    defaultRatio = defaultRatio,
    defaultBrewTempC = defaultBrewTempC,
    defaultPreinfuseS = defaultPreinfuseS,
    grinderModel = grinderModel,
    baristaName = baristaName.trim().ifEmpty { null },
    // Retired (presence follows activity; the app owns sleep) — still written
    // because older versions require the field when reading a backup.
    suppressDe1Sleep = true,
    idleSleepMin = idleSleepMin?.toUInt(),
    qcSteamTimeS = qcSteamTimeS,
    qcSteamFlowMlS = qcSteamFlowMlS,
    qcSteamTempC = qcSteamTempC,
    qcHotWaterTempC = qcHotWaterTempC,
    qcHotWaterVolumeMl = qcHotWaterVolumeMl,
    qcFlushTimeS = qcFlushTimeS,
    qcFlushTempC = qcFlushTempC,
)

/** Overlay a restored [CommonSettings] onto these prefs, keeping the platform
 *  extras (BLE/proxy/qcGrind/activeProfileId) untouched. The settings go
 *  through the core's sanitize first ([sanitizeCommonSettings]), which also
 *  resolves the "unset reads as" fields, so none are defaulted here. */
fun AppPrefs.withCommonSettings(cIn: CommonSettings): AppPrefs = sanitizeCommonSettings(cIn).let { c -> copy(
    themeMode = c.themeMode,
    maxShotDurationS = c.maxShotDurationS,
    autoTare = c.autoTare,
    stopOnWeight = c.stopOnWeight,
    volumeStopWithScale = c.volumeStopWithScale!!,
    requireScale = c.requireScale!!,
    steamEco = c.steamEco,
    steamTwoTap = c.steamTwoTap!!,
    fanThresholdC = c.fanThresholdC!!,
    usbChargingMode = c.usbChargingMode!!,
    preFlush = c.preFlush,
    steamPurge = c.steamPurge,
    weightUnit = c.weightUnit,
    tempUnit = c.tempUnit,
    pressureUnit = c.pressureUnit,
    volumeUnit = c.volumeUnit,
    waterLevelUnit = c.waterLevelUnit!!,
    waterWarnMl = c.waterWarnMl,
    waterRefillPointMm = c.waterRefillPointMm,
    chartChannels = c.chartChannels.toSet(),
    keepScreenOnBrew = c.keepScreenOnBrew,
    brewCueSound = c.brewCueSound,
    brewCueHaptics = c.brewCueHaptics,
    showDebugPanel = c.showDebugPanel,
    defaultDoseG = c.defaultDoseG,
    defaultRatio = c.defaultRatio,
    defaultBrewTempC = c.defaultBrewTempC,
    defaultPreinfuseS = c.defaultPreinfuseS,
    grinderModel = c.grinderModel,
    baristaName = c.baristaName.orEmpty(),
    // A backup from before the idle timer carries none: keep this device's.
    idleSleepMin = c.idleSleepMin?.toInt() ?: idleSleepMin,
    qcSteamTimeS = c.qcSteamTimeS,
    qcSteamFlowMlS = c.qcSteamFlowMlS,
    qcSteamTempC = c.qcSteamTempC,
    qcHotWaterTempC = c.qcHotWaterTempC,
    qcHotWaterVolumeMl = c.qcHotWaterVolumeMl,
    qcFlushTimeS = c.qcFlushTimeS,
    qcFlushTempC = c.qcFlushTempC,
) }

/**
 * The on-disk shape of [AppPrefs] (`prefs.json`) — the shared cross-shell
 * [CommonSettings] block plus this device's platform extras alongside it. "Storage
 * IS the shared shape": both shells persist `common` identically, and a backup's
 * settings line reuses it. Pre-unification files were a FLAT [AppPrefs]; [SettingsStore]
 * detects the absence of `common` and migrates them forward.
 */
@Serializable
private data class PersistedPrefs(
    val common: CommonSettings,
    // ── Platform extras (per-device / Android-only; never cross-shell) ────────
    val screensaverAfterMin: Int = 30,
    val sleepMachineWithSaver: Boolean = true,
    val wakeMachineWithSaver: Boolean = true,
    val sleepOnQuit: Boolean = true,
    val keepConnectedScreenOff: Boolean? = null,
    val autoDriveBackup: Boolean = true,
    val autoUpdateCheck: Boolean = false,
    val lastUpdateCheckAtMs: Long? = null,
    val lastSeenLatestVersion: String? = null,
    val lastBrewLogMethod: String? = null,
    val qcGrind: Float? = null,
    val activeProfileId: String? = null,
    val de1Address: String? = null,
    val scaleAddress: String? = null,
    val scaleName: String? = null,
    val proxyRole: String = "normal",
    val proxyPrimaryHost: String = "",
    val proxyPrimaryPort: Int = 0,
    val replayPrimary: Boolean = false,
    val de1CompanionId: Int? = null,
    val scaleCompanionId: Int? = null,
    val companionDeclined: List<String> = emptyList(),
)

private fun AppPrefs.toPersisted(): PersistedPrefs = PersistedPrefs(
    common = toCommonSettings(),
    screensaverAfterMin = screensaverAfterMin,
    sleepMachineWithSaver = sleepMachineWithSaver,
    wakeMachineWithSaver = wakeMachineWithSaver,
    sleepOnQuit = sleepOnQuit,
    keepConnectedScreenOff = keepConnectedScreenOff,
    autoDriveBackup = autoDriveBackup,
    autoUpdateCheck = autoUpdateCheck,
    lastUpdateCheckAtMs = lastUpdateCheckAtMs,
    lastSeenLatestVersion = lastSeenLatestVersion,
    lastBrewLogMethod = lastBrewLogMethod,
    qcGrind = qcGrind,
    activeProfileId = activeProfileId,
    de1Address = de1Address,
    scaleAddress = scaleAddress,
    scaleName = scaleName,
    proxyRole = proxyRole,
    proxyPrimaryHost = proxyPrimaryHost,
    proxyPrimaryPort = proxyPrimaryPort,
    replayPrimary = replayPrimary,
    de1CompanionId = de1CompanionId,
    scaleCompanionId = scaleCompanionId,
    companionDeclined = companionDeclined,
)

private fun PersistedPrefs.toAppPrefs(): AppPrefs = AppPrefs().withCommonSettings(common).copy(
    screensaverAfterMin = screensaverAfterMin,
    sleepMachineWithSaver = sleepMachineWithSaver,
    wakeMachineWithSaver = wakeMachineWithSaver,
    sleepOnQuit = sleepOnQuit,
    keepConnectedScreenOff = keepConnectedScreenOff,
    autoDriveBackup = autoDriveBackup,
    autoUpdateCheck = autoUpdateCheck,
    lastUpdateCheckAtMs = lastUpdateCheckAtMs,
    lastSeenLatestVersion = lastSeenLatestVersion,
    lastBrewLogMethod = lastBrewLogMethod,
    qcGrind = qcGrind,
    activeProfileId = activeProfileId,
    de1Address = de1Address,
    scaleAddress = scaleAddress,
    scaleName = scaleName,
    proxyRole = proxyRole,
    proxyPrimaryHost = proxyPrimaryHost,
    proxyPrimaryPort = proxyPrimaryPort,
    replayPrimary = replayPrimary,
    de1CompanionId = de1CompanionId,
    scaleCompanionId = scaleCompanionId,
    companionDeclined = companionDeclined,
)

/**
 * The **single-owner session config** a primary pushes to its mirrors
 * (multi-device M2 T2) — a config-only subset of [AppPrefs] plus the live
 * `activeBeanId`. It deliberately EXCLUDES per-device identity
 * (`de1Address`/`scaleAddress`) and UI-only prefs (theme, keep-screen-on, debug
 * panel, chart channels): a secondary snaps its active profile/bean, SAW, QC and
 * units to the primary on attach, but keeps its own device bindings and look.
 * Ferried as JSON inside a `Frame.Config`. All fields default so an older mirror
 * decodes a newer primary's snapshot cleanly.
 */
@Serializable
data class ConfigSnapshot(
    val activeProfileId: String? = null,
    /** The active profile's full wire JSON, sent **only for a custom profile** the
     *  mirror might lack (built-ins are present on every install, covered by the id).
     *  A secondary shows it as a transient display overlay so the curve/targets/name
     *  match the primary even for a profile it never saved — see
     *  `MainViewModel.mirroredProfile`. Null ⟺ the active profile is a built-in. */
    val activeProfileJson: String? = null,
    val activeBeanId: String? = null,
    /** A one-line summary (roaster · name · freshness) of the active bean, so the
     *  mirror's bean chip renders even when that bean isn't in its own library.
     *  Full bean sync is out of scope — this is display only. */
    val activeBeanSummary: String? = null,
    val stopOnWeight: Boolean = false,
    val autoTare: Boolean = false,
    val maxShotDurationS: Float = 45f,
    val grinderModel: String = "",
    val qcSteamTimeS: Float = 12f,
    val qcSteamFlowMlS: Float = 1.2f,
    val qcSteamTempC: Float = 148f,
    val qcHotWaterTempC: Float = 80f,
    val qcHotWaterVolumeMl: Float = 150f,
    val qcFlushTimeS: Float = 4f,
    val qcFlushTempC: Float = 95f,
    val qcGrind: Float? = null,
    /** The live Quick-Controls **brew override** (issue 06) — the transient
     *  dose/yield/brew-temp/preinfuse the primary dialled for the next shot, so a
     *  mirror's Brew targets track it. All-null ⟺ no override (use the profile
     *  recipe). dose/yield/temp travel together; preinfuse is optional. */
    val brewDoseG: Double? = null,
    val brewYieldG: Double? = null,
    val brewTempC: Double? = null,
    val brewPreinfuseS: Double? = null,
    /** The primary's display name (its device label), so a mirror's authority cue
     *  can read "Mirroring <primary>" instead of a bare "Mirroring" (issue 10). */
    val primaryName: String = "",
    /** Config owner — always `"primary"` from a primary; a secondary uses it to
     *  flag that it is viewing as a read-only mirror. */
    val authority: String = "primary",
    // ── Settings ownership (spec §6.1). Every v4 hub sends these, so they are
    // required: a snapshot without one fails to decode and is dropped. ─────
    /** A counter from 0 the hub bumps for every snapshot it builds; a remote
     *  drops one at or below the last it applied, and resets on each Welcome. */
    val rev: Long,
    val steamEco: Boolean,
    val steamTwoTap: Boolean,
    val fanThresholdC: Float,
    /** The resolved refill point (the default when unset), raw sensor mm. */
    val waterRefillPointMm: Float,
    /** The DE1's mains heater voltage (MMR `HeaterVoltage`); null until the
     *  hub has read the register. */
    val heaterVoltage: Int? = null,
    /** The DE1's flow calibration multiplier, decoded; null until read. */
    val flowMultiplier: Float? = null,
    /** The hub core's resolved line frequency, Hz (null until it is known),
     *  and its override (0 = auto). */
    val lineFrequencyHz: Float? = null,
    val lineFrequencyOverride: Float,
    /** The cup-warmer setpoint (Bengle, MMR `CupWarmerTemp`); null on other machines. */
    val cupWarmerTempC: Int? = null,
    val volumeStopWithScale: Boolean,
    val requireScale: Boolean,
    val preFlush: Boolean,
    val steamPurge: Boolean,
    val idleSleepMin: Int,
)

/** Decode `prefs.json`'s text: the nested shape, or a pre-unification FLAT
 *  [AppPrefs] (decoded directly; the next save rewrites it nested). */
internal fun decodePrefs(json: Json, text: String): AppPrefs {
    val obj = json.parseToJsonElement(text).jsonObject
    val common = obj["common"] ?: return decodeFlatPrefs(json, obj)
    // The core's sanitize first, so one wrong-typed or out-of-range field
    // can't make the whole file unreadable.
    val clean = JsonObject(obj + ("common" to json.encodeToJsonElement(CommonSettings.serializer(), sanitizeCommonSettings(common))))
    return json.decodeFromJsonElement(PersistedPrefs.serializer(), clean).toAppPrefs()
}

/** A pre-unification FLAT `prefs.json`: its shared fields (Android's names
 *  are the canonical ones) go through the core's sanitize, the rest decode as
 *  before, so an old file with one bad value still loads. */
private fun decodeFlatPrefs(json: Json, obj: JsonObject): AppPrefs {
    val commonKeys = CommonSettings.serializer().descriptor.elementNames.toSet()
    val platform = JsonObject(obj.filterKeys { it !in commonKeys })
    return json.decodeFromJsonElement(AppPrefs.serializer(), platform).withCommonSettings(sanitizeCommonSettings(obj))
}

/** File-backed JSON persistence for [AppPrefs] (`filesDir/prefs.json`). */
class SettingsStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    /** One save at a time, and never an older snapshot after a newer one. */
    private val saveLock = Mutex()
    private var lastGeneration = Long.MIN_VALUE

    suspend fun load(): AppPrefs = withContext(Dispatchers.IO) {
        val stored = loadStore(file, "settings") { text -> decodePrefs(json, text) }.valueOrNull()
        val prefs = resolveIdleSleep(stored)
        // Persist a migration once, so a later screensaver change can't move it.
        if (stored != null && stored.idleSleepMin == null) save(prefs)
        prefs
    }

    /**
     * Write [prefs]. Saves run one at a time. A save with a [generation] (the
     * VM numbers each snapshot when it builds it) is skipped when a newer
     * snapshot was already written, so a fire-and-forget save that loses the
     * race can never put older values back (spec §7.5 step 5).
     */
    suspend fun save(prefs: AppPrefs, generation: Long? = null) {
        withContext(Dispatchers.IO) {
            saveLock.withLock {
                if (generation != null) {
                    if (generation < lastGeneration) return@withLock
                    lastGeneration = generation
                }
                runCatching { json.encodeToString(PersistedPrefs.serializer(), prefs.toPersisted()) }
                    .onSuccess { saveStore(file, it) }
            }
        }
    }

    /** Delete the store file and every copy of it (Erase all data; final review 05 N-1). */
    suspend fun erase() {
        withContext(Dispatchers.IO) { eraseStore(file) }
    }

    private companion object {
        const val FILE_NAME = "prefs.json"
    }
}
