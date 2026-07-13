package coffee.crema.settings

import android.content.Context
import coffee.crema.core.CommonSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

/*
 * App preferences — the user's display/behaviour settings, persisted via the
 * same file-JSON pattern as the bean library and shot history.
 *
 * v1 holds just the theme mode; units (weight/temp/pressure) and other prefs are
 * additive later (older records deserialise cleanly via defaults).
 */
@Serializable
data class AppPrefs(
    /** `"system" | "light" | "dark"` — drives CremaTheme. Defaults to dark
     *  (the machine app is dark-skinned). */
    val themeMode: String = "dark",
    /** Max shot duration cap, seconds. Pushed to the core on load + shown as a
     *  Time stop-condition on Brew. */
    val maxShotDurationS: Float = 45f,
    // ── Shot behaviour (re-applied to the core on launch) ────────────────────
    /** Auto-tare the scale on shot start. Defaults ON to match the core's own
     *  default ([CremaCore] `auto_tare = true`) and the web shell (which
     *  defaults `autoTareOnShotStart` true). Android historically defaulted
     *  false, so a fresh install silently shipped auto-tare DISABLED — the scale
     *  never zeroed at shot start, unlike web. Existing users keep their saved
     *  value (this only changes the no-prefs / absent-key default). */
    val autoTare: Boolean = true,
    /** Stop the shot at the yield target (stop-on-weight). Defaults ON for the
     *  same web/core parity reason as [autoTare] (web defaults `stopOnWeight`
     *  true); a no-op when no scale or yield target is present. */
    val stopOnWeight: Boolean = true,
    /** Opt-in: arm the profile's max-volume stop even while a scale is
     *  connected. Default off — volume is a no-scale fallback, never a
     *  competitor to stop-at-weight (reference-app consensus; the firmware's
     *  own tail volume stop is never uploaded either way). */
    val volumeStopWithScale: Boolean = false,
    val steamEco: Boolean = false,
    /** Pre-shot group flush / post-steam purge requests. Persisted preference;
     *  the shot sequence does not consume them yet (Settings rows carry the
     *  "not implemented" pill until it does). */
    val preFlush: Boolean = false,
    val steamPurge: Boolean = false,
    // ── Units (issue 44) ──────────────────────────────────────────────────────
    /** Weight unit for dose/yield/scale readouts — `"g" | "oz"`. */
    val weightUnit: String = "g",
    /** Temperature unit for every temp readout — `"C" | "F"`. */
    val tempUnit: String = "C",
    /** Pressure unit for the pressure channel — `"bar" | "psi"`. */
    val pressureUnit: String = "bar",
    /** Volume unit for water/dispensed readouts — `"ml" | "floz"`. */
    val volumeUnit: String = "ml",
    // ── Display ──────────────────────────────────────────────────────────────
    /** Live-chart channel keys (Quick Controls chart strip). */
    val chartChannels: Set<String> = setOf("pressure", "flow", "weight"),
    /** Hold FLAG_KEEP_SCREEN_ON while a shot is pulling. */
    val keepScreenOnBrew: Boolean = false,
    // ── Sleep & screensaver (platform extras — deliberately NOT in
    // CommonSettings; the backup design keeps screensaver per-shell) ─────────
    /** Idle minutes before the screensaver shows; 0 = never. */
    val screensaverAfterMin: Int = 30,
    /** Put the DE1 to sleep when the saver starts (tap wakes both). */
    val sleepMachineWithSaver: Boolean = true,
    /** Wake a sleeping DE1 when the saver is dismissed; OFF = the tap only
     *  clears the overlay and the machine stays asleep. */
    val wakeMachineWithSaver: Boolean = true,
    /** Show the inline debug / event-log panel in Settings → Advanced
     *  (web `showDebugPanel`). */
    val showDebugPanel: Boolean = false,
    // ── Brew defaults (seed new profiles + the Quick-Controls fallbacks) ─────
    val defaultDoseG: Float = 18f,
    val defaultRatio: Float = 2f,
    val defaultBrewTempC: Float = 93f,
    val defaultPreinfuseS: Float = 8f,
    /** Equipment-level grinder model (web `grinderModel`) — free text shown on
     *  Brew + sent with Visualizer uploads/patches. Blank = unset. */
    val grinderModel: String = "",
    /** Keep the DE1 awake while Crema is open (web `suppressDe1Sleep`) — a
     *  60 s UserPresent (MMR 0x803858) heartbeat resets the sleep timer. */
    val suppressDe1Sleep: Boolean = true,
    // ── Quick-Controls steam / hot-water / flush (issue 14) ──────────────────
    /** Persisted QC overrides for the machine's steam / hot-water / flush params.
     *  These stick (no throwaway state) and take priority; on change they're
     *  applied to the machine via read-modify-write (the machine's reported
     *  timeouts / espresso volume / group temp are preserved). Defaults mirror
     *  the former QuickControls seed values. */
    val qcSteamTimeS: Float = 12f,
    val qcSteamFlowMlS: Float = 1.2f,
    val qcSteamTempC: Float = 148f,
    val qcHotWaterTempC: Float = 80f,
    val qcHotWaterVolumeMl: Float = 150f,
    val qcFlushTimeS: Float = 4f,
    val qcFlushTempC: Float = 95f,
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
    // ── Multi-device LAN proxy (M1, debug/demo) ──────────────────────────────
    /** Proxy role — `"normal" | "primary" | "secondary"`. `normal` = today's
     *  single-device behaviour. A debug/demo setting: the transport is built at
     *  startup, so a change is **restart-to-apply**. */
    val proxyRole: String = "normal",
    /** For `secondary`: the primary's host — an IP, or `10.0.2.2` to reach an
     *  `adb forward`ed port on the dev host from an emulator. */
    val proxyPrimaryHost: String = "",
    /** For `secondary`: the primary's relay port. */
    val proxyPrimaryPort: Int = 0,
    /** Debug/emulator only: when a `primary` starts, **replay the newest captured
     *  shot as a fake DE1** instead of using live Bluetooth. Default OFF — a real
     *  primary always uses the radio. This must be opt-in: otherwise a primary's
     *  own session recordings (in `captures/`) get auto-replayed on the next launch,
     *  hijacking it into a fake DE1 + blocking the real DE1/scale. Set it in
     *  `prefs.json` for an emulator with no Bluetooth. */
    val replayPrimary: Boolean = false,
    /** Devices this host has approved to mirror it (issue 02 — TOFU pairing). A
     *  remembered peer skips the "Allow this device?" prompt on reconnect; absence
     *  ⟺ re-prompt. Host-side only — never pushed to mirrors (not in [ConfigSnapshot]). */
    val pairedDevices: List<PairedDevice> = emptyList(),
)

/** Map the portable subset of [AppPrefs] → the shared cross-shell [CommonSettings].
 *  Android's field names + chart-channel vocabulary ARE the canonical shape, so
 *  this is a near-identity projection (the web shell does the renaming). */
fun AppPrefs.toCommonSettings(): CommonSettings = CommonSettings(
    themeMode = themeMode,
    maxShotDurationS = maxShotDurationS,
    autoTare = autoTare,
    stopOnWeight = stopOnWeight,
    volumeStopWithScale = volumeStopWithScale,
    steamEco = steamEco,
    preFlush = preFlush,
    steamPurge = steamPurge,
    weightUnit = weightUnit,
    tempUnit = tempUnit,
    pressureUnit = pressureUnit,
    volumeUnit = volumeUnit,
    chartChannels = chartChannels.toList(),
    keepScreenOnBrew = keepScreenOnBrew,
    showDebugPanel = showDebugPanel,
    defaultDoseG = defaultDoseG,
    defaultRatio = defaultRatio,
    defaultBrewTempC = defaultBrewTempC,
    defaultPreinfuseS = defaultPreinfuseS,
    grinderModel = grinderModel,
    suppressDe1Sleep = suppressDe1Sleep,
    qcSteamTimeS = qcSteamTimeS,
    qcSteamFlowMlS = qcSteamFlowMlS,
    qcSteamTempC = qcSteamTempC,
    qcHotWaterTempC = qcHotWaterTempC,
    qcHotWaterVolumeMl = qcHotWaterVolumeMl,
    qcFlushTimeS = qcFlushTimeS,
    qcFlushTempC = qcFlushTempC,
)

/** Overlay a restored [CommonSettings] onto these prefs, keeping the platform
 *  extras (BLE/proxy/qcGrind/activeProfileId) untouched. */
fun AppPrefs.withCommonSettings(c: CommonSettings): AppPrefs = copy(
    themeMode = c.themeMode,
    maxShotDurationS = c.maxShotDurationS,
    autoTare = c.autoTare,
    stopOnWeight = c.stopOnWeight,
    // Nullable in the generated binding (additive-field tolerance): a
    // pre-existing prefs.json / older backup simply reads as "off".
    volumeStopWithScale = c.volumeStopWithScale ?: false,
    steamEco = c.steamEco,
    preFlush = c.preFlush,
    steamPurge = c.steamPurge,
    weightUnit = c.weightUnit,
    tempUnit = c.tempUnit,
    pressureUnit = c.pressureUnit,
    volumeUnit = c.volumeUnit,
    chartChannels = c.chartChannels.toSet(),
    keepScreenOnBrew = c.keepScreenOnBrew,
    showDebugPanel = c.showDebugPanel,
    defaultDoseG = c.defaultDoseG,
    defaultRatio = c.defaultRatio,
    defaultBrewTempC = c.defaultBrewTempC,
    defaultPreinfuseS = c.defaultPreinfuseS,
    grinderModel = c.grinderModel,
    suppressDe1Sleep = c.suppressDe1Sleep,
    qcSteamTimeS = c.qcSteamTimeS,
    qcSteamFlowMlS = c.qcSteamFlowMlS,
    qcSteamTempC = c.qcSteamTempC,
    qcHotWaterTempC = c.qcHotWaterTempC,
    qcHotWaterVolumeMl = c.qcHotWaterVolumeMl,
    qcFlushTimeS = c.qcFlushTimeS,
    qcFlushTempC = c.qcFlushTempC,
)

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
    val qcGrind: Float? = null,
    val activeProfileId: String? = null,
    val de1Address: String? = null,
    val scaleAddress: String? = null,
    val scaleName: String? = null,
    val proxyRole: String = "normal",
    val proxyPrimaryHost: String = "",
    val proxyPrimaryPort: Int = 0,
    val replayPrimary: Boolean = false,
    val pairedDevices: List<PairedDevice> = emptyList(),
)

private fun AppPrefs.toPersisted(): PersistedPrefs = PersistedPrefs(
    common = toCommonSettings(),
    screensaverAfterMin = screensaverAfterMin,
    sleepMachineWithSaver = sleepMachineWithSaver,
    wakeMachineWithSaver = wakeMachineWithSaver,
    qcGrind = qcGrind,
    activeProfileId = activeProfileId,
    de1Address = de1Address,
    scaleAddress = scaleAddress,
    scaleName = scaleName,
    proxyRole = proxyRole,
    proxyPrimaryHost = proxyPrimaryHost,
    proxyPrimaryPort = proxyPrimaryPort,
    replayPrimary = replayPrimary,
    pairedDevices = pairedDevices,
)

private fun PersistedPrefs.toAppPrefs(): AppPrefs = AppPrefs().withCommonSettings(common).copy(
    screensaverAfterMin = screensaverAfterMin,
    sleepMachineWithSaver = sleepMachineWithSaver,
    wakeMachineWithSaver = wakeMachineWithSaver,
    qcGrind = qcGrind,
    activeProfileId = activeProfileId,
    de1Address = de1Address,
    scaleAddress = scaleAddress,
    scaleName = scaleName,
    proxyRole = proxyRole,
    proxyPrimaryHost = proxyPrimaryHost,
    proxyPrimaryPort = proxyPrimaryPort,
    replayPrimary = replayPrimary,
    pairedDevices = pairedDevices,
)

/**
 * A secondary this host has approved (issue 02). [id] is the peer's stable
 * `clientId` (the TOFU key); [name] its display label; [canControl] = false marks
 * a **view-only** mirror whose Control/Handoff the relay refuses.
 */
@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val canControl: Boolean = true,
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
    val weightUnit: String = "g",
    val tempUnit: String = "C",
    val pressureUnit: String = "bar",
    val volumeUnit: String = "ml",
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
)

/** File-backed JSON persistence for [AppPrefs] (`filesDir/prefs.json`). */
class SettingsStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    suspend fun load(): AppPrefs = withContext(Dispatchers.IO) {
        runCatching {
            val text = file.takeIf { it.exists() }?.readText() ?: return@runCatching null
            if (json.parseToJsonElement(text).jsonObject["common"] != null) {
                json.decodeFromString(PersistedPrefs.serializer(), text).toAppPrefs()
            } else {
                // Pre-unification file: a FLAT AppPrefs. Decode it directly; the next
                // save() rewrites it in the nested `common` shape.
                json.decodeFromString(AppPrefs.serializer(), text)
            }
        }.getOrNull() ?: AppPrefs()
    }

    suspend fun save(prefs: AppPrefs) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(PersistedPrefs.serializer(), prefs.toPersisted())) }
        }
    }

    private companion object {
        const val FILE_NAME = "prefs.json"
    }
}
