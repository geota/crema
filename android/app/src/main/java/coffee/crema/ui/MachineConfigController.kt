package coffee.crema.ui

import coffee.crema.core.CommonSettings
import coffee.crema.core.CremaBridge
import coffee.crema.core.HotWaterTarget
import coffee.crema.core.MmrReg
import coffee.crema.core.SteamHotWaterTargets
import coffee.crema.core.WeightUnit
import coffee.crema.core.brewTempRange
import coffee.crema.core.normalizeBrewOverrides
import coffee.crema.core.preinfusionDial
import coffee.crema.machine.MachineCommand
import coffee.crema.machine.QcField
import coffee.crema.settings.Setting
import coffee.crema.settings.hotWaterDose
import kotlin.math.roundToInt
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json

/**
 * The machine's configuration on the device that owns it (MainViewModel
 * breakup step 9c): what a config command does here ([showConfig],
 * [commitConfig]; [MainViewModel]'s machine actions forward to them for this
 * device's own UI and a remote's relayed commands alike), the settings the
 * core follows ([pushCoreInputs]), the steam / hot-water packet and its target,
 * and the machine and session setters that reach the core. A remote refuses
 * those setters ([refusesOwnEdit]). Machine state and the shot path are
 * [MachineSessionController]'s; hub / remote config is [RemoteConfigController]'s.
 *
 * Holds no state of its own and registers no core-event handlers. Building it
 * only stores these values: it is built before the controllers its callbacks
 * reach, which run only when invoked.
 */
internal class MachineConfigController(
    private val lane: CoreLane,
    private val bridge: CremaBridge,
    private val json: Json,
    private val uiState: () -> MainUiState,
    private val updateUi: ((MainUiState) -> MainUiState) -> Unit,
    private val effectiveState: () -> MainUiState,
    private val appendLog: (String) -> Unit,
    /** Whether [setting] is refused here (a remote), saying so. */
    private val refusesOwnEdit: (setting: Setting) -> Boolean,
    private val persistPrefs: () -> Unit,
    /** Push the config snapshot to mirrors. */
    private val pushConfig: () -> Unit,
    private val profileJson: (String) -> String?,
    private val persistLibrary: () -> Unit,
    /** Load the profile linked to the bean just activated, if any. */
    private val maybeLoadLinkedProfile: (String) -> Unit,
    /** Push the stop targets from a state, the desired profile after a delay in ms. */
    private val pushStopTargets: (state: MainUiState) -> Unit,
    private val refreshStopTargetProjection: () -> Unit,
    /** Latch the start settings in the core, from this device's own state. */
    private val pushStartSettings: () -> Unit,
    /** The idle-sleep timer's "Sleep after", in minutes. */
    private val configureIdleSleep: (Int) -> Unit,
) {
    /**
     * Run [edit], the body of a read-only machine or session setter (§7.3),
     * unless this device is a remote: there it changes nothing — no own value,
     * no machine write — and says where to change it.
     */
    private inline fun editOwnSetting(setting: Setting, edit: () -> Unit) {
        if (refusesOwnEdit(setting)) return
        edit()
    }

    // ── Config commands ───────────────────────────────────────────────────────

    /** The visible half of a config command on the device that owns the
     *  config: update the UI state now; [commitConfig] does the rest. A remote
     *  shows its relayed edits in its mirror slice instead ([RemoteConfigController.showPending]). */
    fun showConfig(cmd: MachineCommand.Config) {
        updateUi { cmd.applyTo(it) }
        // The brew override is stored normalized by the core (this device holds
        // the machine: its own dial and a remote's relayed one alike) — brew
        // temp clamped into the active profile's range, values back at the
        // profile's own dropped, nothing left = no override (Decenza model).
        if (cmd is MachineCommand.AdjustBrew) {
            val activeJson = uiState().activeProfileId?.let { profileJson(it) }
            updateUi {
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
        val cremaJson = s.mirroredProfileJson?.takeIf { uiState().mirror != null }
            ?: s.activeProfileId?.let { profileJson(it) } ?: s.mirroredProfileJson
        return brewDialOf(cremaJson, json, ::brewTempRange, ::preinfusionDial)
    }

    /** The rest of a config command on the device that owns the config:
     *  persist it, write the machine, push it to mirrors. */
    fun commitConfig(cmd: MachineCommand.Config) {
        when (cmd) {
            is MachineCommand.SetActiveProfile -> {
                persistPrefs()
                pushStopTargets(effectiveState()) // new profile's recipe yield/volume become the stop targets
            }
            is MachineCommand.SetActiveBean -> {
                persistLibrary()
                pushConfig() // bean activation persists via the library, not prefs
                // Linked-profile auto-load (web activateBean parity). Only user acts
                // reach this — boot/library restore writes activeBeanId directly —
                // so firing here matches the "explicit activation only" rule.
                if (cmd.id != null) maybeLoadLinkedProfile(cmd.id)
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
                    // One read-modify-write packet carries these and the dose.
                    QcField.STEAM_TIME, QcField.STEAM_TEMP, QcField.HOT_WATER_TEMP -> applySteamHotWater()
                    QcField.HOT_WATER_VOLUME -> {
                        // A bare volume edit (no remote sends one since relay v4):
                        // it switched the dose to volume mode (`applyTo`).
                        appendLog("Hot-water volume ${cmd.value.toInt()} ml: switched to volume mode")
                        applySteamHotWater()
                    }
                    QcField.STEAM_FLOW -> lane.route("Set steam flow") { bridge.setSteamFlow(cmd.value) }
                    QcField.FLUSH_TIME -> lane.route("Set flush time") { bridge.setFlushTimeout((cmd.value * 1000f).toUInt()) }
                    QcField.FLUSH_TEMP -> lane.route("Set flush temp") { bridge.setFlushTemp(cmd.value) }
                    // Grind never reaches the machine: it's recorded onto the next shot.
                    QcField.GRIND -> Unit
                }
            }
            is MachineCommand.AdjustBrew -> {
                // The new yield override becomes the live stop-at-weight target
                // now; the core uploads the dialled profile once the dial rests.
                pushStopTargets(effectiveState())
                pushConfig() // mirrors track the primary's live override (issue 06)
            }
            MachineCommand.ResetBrew -> {
                pushStopTargets(effectiveState()) // drop the per-shot override; the profile recipe target takes over
                pushConfig() // mirrors drop their override too (issue 06)
            }
        }
    }

    /**
     * The core inputs that follow settings, from [state] (spec §7.4): the
     * stop-on-weight, auto-tare, max-duration and volume-stop flags, the stop
     * targets, the idle-sleep timer and the weight unit (the scale LCD and
     * set-grams writes follow it). Called with [effectiveState]: after
     * each applied snapshot on a remote, at the end of every own-state writer
     * (the VM's `loadPrefs`, `applyRestoredSettings`, `resetPreferences`,
     * `eraseAll`) in every role, and on leaving the remote role.
     */
    fun pushCoreInputs(state: MainUiState) {
        // Every settings-driven core input in one call (auto-tare,
        // stop-on-weight, volume stop, max shot, weight unit, low-water
        // threshold, and the start settings: pre-flush, require-scale,
        // steam purge) — the core's apply_common_settings, as web does.
        runCatching { bridge.applyCommonSettings(json.encodeToString(CommonSettings.serializer(), state.commonSettings())) }
            .onFailure { appendLog("Apply settings to the core failed: ${it.message}") }
        // The steam / hot-water packet's targets and the hot-water dose: a
        // restore, reset or erase that changes the dose must not leave the old
        // packet for the group-head button.
        applySteamHotWater(state)
        pushStopTargets(state)
        configureIdleSleep(state.idleSleepMin)
    }

    // ── Quick-Controls steam / hot-water ──────────────────────────────────────

    /** Push the steam / hot-water targets and the hot-water dose to the core —
     *  the one push for the combined cuuid_0B packet (spec §4), from [state]'s
     *  settings: the core keeps the packet in step (the volume and timeout
     *  from the dose, the group target the effective profile's). Every writer
     *  of these settings ends here: a Quick Controls edit, a relayed one, and
     *  the whole-bundle writes through [pushCoreInputs]. */
    private fun applySteamHotWater(state: MainUiState = uiState()) {
        val ui = state
        val targets = SteamHotWaterTargets(
            steamTempC = ui.qcSteamTempC,
            steamTimeoutS = ui.qcSteamTimeS,
            hotWaterTempC = ui.qcHotWaterTempC,
            hotWater = ui.hotWaterDose(),
        )
        lane.route("Set steam/hot-water") {
            bridge.setSteamHotwaterTargets(json.encodeToString(SteamHotWaterTargets.serializer(), targets))
        }
        refreshHotWaterTarget()
    }

    /** Re-read the core's hot-water target after an input change of this
     *  device's own: with nothing connected no output carries its
     *  `HotWaterTarget` event. A remote shows nothing of its own core's. */
    fun refreshHotWaterTarget() {
        if (uiState().mirror != null) return
        val raw = runCatching { bridge.hotWaterTargetJson() }.getOrNull() ?: return
        val target = runCatching {
            json.decodeFromString(HotWaterTarget.serializer().nullable, raw)
        }.getOrNull()
        setHotWaterTarget(target)
    }

    /** Show [target]; a hub tells its remotes when it changed (they show the
     *  hub's own number, from the snapshot). */
    fun setHotWaterTarget(target: HotWaterTarget?) {
        if (uiState().hotWaterTarget == target) return
        updateUi { it.copy(hotWaterTarget = target) }
        pushConfig()
    }

    /** [setHotWaterDose] on the device holding the machine: store, push, show. */
    fun applyHotWaterDose(dose: coffee.crema.core.HotWaterDose) {
        updateUi { it.withHotWaterDose(dose) }
        persistPrefs() // pushes the hub's snapshot too
        applySteamHotWater()
    }

    // ── Machine and session setters ───────────────────────────────────────────

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
            lane.deliver(raw)
            runCatching { bridge.readMmr(MmrReg.HEATER_VOLTAGE) }.getOrNull()?.let(lane::deliver)
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
            updateUi { it.copy(lineFreqHz = resolved, lineFreqOverride = hz) }
            pushConfig() // remotes show the hub's line frequency (§6.1)
        }
    }

    /** Re-read the auto-detector's current resolved line frequency so the AC-
     *  frequency hint can show "Currently locked at X Hz" live while on Auto. */
    fun refreshLineFrequency() {
        val resolved = runCatching { bridge.lineFrequencyHz() }.getOrNull()
        updateUi { it.copy(lineFreqHz = resolved) }
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
                updateUi { it.copy(maxShotDurationS = seconds) }
                persistPrefs() // also pushes the hub's snapshot (§6.1)
            }
            refreshStopTargetProjection()
        }
    }

    /**
     * Weight readout unit (`"g" | "oz"`). Persisted display pref. The core
     * gets it too, and a connected scale's display switches now rather than
     * on the next Idle entry or reconnect (as on web).
     */
    fun setWeightUnit(unit: String) {
        updateUi { it.copy(weightUnit = unit) }
        persistPrefs()
        val wire = coreWeightUnit(unit)
        runCatching { bridge.setWeightUnitPref(wire) }.onFailure {
            appendLog("Set weight unit failed: ${it.message}")
            return
        }
        // Each call is capability-gated and fails for a scale without it (or
        // no scale): a no-op is intended.
        runCatching { bridge.enableScaleLcd(wire) }.onSuccess(lane::deliver)
        if (wire == WeightUnit.Grams.string) {
            runCatching { bridge.setScaleUnitGrams() }.onSuccess(lane::deliver)
        }
    }

    /** Low-water warning threshold, canonical ml (0 = off) — #33 follow-up.
     *  Persisted; re-arms the once-per-dip warning so a raised threshold can
     *  fire on the very next level report. */
    fun setWaterWarnMl(ml: Float) {
        updateUi { it.copy(waterWarnMl = ml) }
        persistPrefs()
        // The core's monitor re-arms on a threshold change.
        lane.post { runCatching { bridge.setWaterWarnMl(ml) } }
    }

    /** The machine's own refill threshold, raw sensor mm (geota/crema#47).
     *  Persisted AND pushed straight through, so the DE1 stops (or starts)
     *  demanding water at the new level without waiting for a reconnect. */
    fun setWaterRefillPointMm(mm: Float) {
        editOwnSetting(Setting.WATER_REFILL_POINT) {
            val clamped = mm.coerceIn(REFILL_POINT_MIN_MM, REFILL_POINT_MAX_MM)
            updateUi { it.copy(waterRefillPointMm = clamped) }
            persistPrefs()
            lane.route("Set refill point") { bridge.setRefillThreshold(clamped) }
        }
    }

    /** Fan-on temperature threshold, °C (0..=60). Persisted + written to the
     *  machine immediately; re-seeded on every connect. */
    fun setFanThreshold(tempC: Float) {
        editOwnSetting(Setting.FAN_THRESHOLD) {
            updateUi { it.copy(fanThresholdC = tempC) }
            persistPrefs()
            lane.route("Set fan threshold") {
                bridge.setFanThreshold(tempC.roundToInt().coerceIn(0, 60).toUByte())
            }
        }
    }

    /** Opt-in "require scale to start" block (geota/crema#29). Persisted. */
    fun setRequireScale(on: Boolean) {
        editOwnSetting(Setting.REQUIRE_SCALE) {
            updateUi { it.copy(requireScale = on) }
            persistPrefs()
            pushStartSettings()
        }
    }

    /** DE1 firmware two-tap steam stop. Persisted + written to the machine
     *  immediately; re-seeded on every connect. */
    fun setSteamTwoTap(enabled: Boolean) {
        editOwnSetting(Setting.STEAM_TWO_TAP) {
            updateUi { it.copy(steamTwoTap = enabled) }
            persistPrefs()
            lane.route("Set steam two-tap stop") {
                bridge.setSteamTwoTapStop(if (enabled) 1u else 0u)
            }
        }
    }

    /** The pre-shot flush preference: persisted, and latched in the core,
     *  which runs the start (F16). */
    fun setPreFlush(enabled: Boolean) {
        editOwnSetting(Setting.PRE_FLUSH) {
            updateUi { it.copy(preFlush = enabled) }
            persistPrefs()
            pushStartSettings()
        }
    }

    /** The post-steam purge preference: persisted, and latched in the core,
     *  which runs the purge (F16). */
    fun setSteamPurge(enabled: Boolean) {
        editOwnSetting(Setting.STEAM_PURGE) {
            updateUi { it.copy(steamPurge = enabled) }
            persistPrefs()
            pushStartSettings()
        }
    }
}

/** The core's wire spelling (`"grams" | "ounces"`) of the `"g" | "oz"` weight-unit pref. */
private fun coreWeightUnit(pref: String): String =
    if (pref == "oz") WeightUnit.Ounces.string else WeightUnit.Grams.string
