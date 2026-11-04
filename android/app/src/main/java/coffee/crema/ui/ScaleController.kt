package coffee.crema.ui

import coffee.crema.ble.ScaleBleManager
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.ScaleCapabilities
import coffee.crema.settings.Setting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The scale session as the UI sees it (MainViewModel breakup step 4): the
 * readings and echoed settings, the capability refresh, the scale settings
 * and the beep / timer / peak / tare verbs, and the once-per-connection
 * low-battery warning. [ConnectionController] keeps the link lifecycle.
 *
 * Registers its core-event handlers on [lane] as it is built.
 */
internal class ScaleController(
    private val lane: CoreLane,
    private val json: Json,
    private val bridge: CremaBridge,
    private val scope: CoroutineScope,
    private val uiState: () -> MainUiState,
    private val updateUi: ((MainUiState) -> MainUiState) -> Unit,
    private val appendLog: (String) -> Unit,
    private val notify: (String) -> Unit,
    /** The VM's remote check for a read-only setter (§7.3): true = refused, said so. */
    private val refusesOwnEdit: (Setting) -> Boolean,
    /** The scale left: re-project the stop targets (stop-at-weight is off). */
    private val refreshStopTargets: () -> Unit,
    /** Tear the scale link down for the reconnect supervisor. */
    private val presumeDead: suspend (String) -> Unit,
) {
    /** Whether the low-battery snackbar fired for the current scale
     *  connection (issue #29) — reset in [clearScaleSessionUi] so the next
     *  session warns again, but one connection never nags twice. Lane-owned:
     *  written only on the core lane. */
    private var scaleLowBatteryWarned = false

    init {
        lane.on<Event.ScaleReading> { event ->
            val r = event.content
            // The Bookoo echoes its live settings in every weight
            // notification. Project them into the config controls so they
            // show the scale's real current state — `device_*` is null for
            // scales that do not report a setting, in which case the
            // control keeps its last value.
            updateUi { it.copy(
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
            val batt = uiState().scaleBatteryPercent
            if (batt != null && batt <= SCALE_LOW_BATTERY_PCT && !scaleLowBatteryWarned) {
                scaleLowBatteryWarned = true
                notify("Scale battery low ($batt%) — charge it soon")
            }
        }
        // A tare that arrived mid-pour was dropped by the core rather
        // than obeyed (geota/crema#50). Say so — otherwise the Tare
        // button just looks broken.
        lane.on<Event.TareRefusedMidShot> {
            appendLog("Tare refused — a shot is pouring")
            notify("Tare ignored — a shot is running. Taring now would break stop-at-weight.")
        }
        lane.on<Event.ScaleStale> {
            updateUi { it.copy(scaleWeightG = null) }
            appendLog("Scale stale")
        }
        lane.on<Event.ScaleLinkDead> { event ->
            // The core presumed the scale link dead (silent 5 s past
            // ScaleStale, or three failed writes with no reading between;
            // once per episode). Tear it down for the reconnect supervisor,
            // but only while the link still claims READY — one already
            // reconnecting is left to its ladder.
            updateUi { it.copy(scaleWeightG = null) }
            appendLog("Scale link dead: ${event.content.reason.string}")
            if (uiState().scaleState == ScaleBleManager.State.READY) {
                scope.launch { presumeDead(event.content.reason.string) }
            }
        }
        lane.on<Event.ScaleButtonPressed> { event ->
            // Logged like de1app (bluetooth.tcl:2825-2828) — no hard-wired
            // action yet; the event is the hook for a future mapping.
            appendLog("Scale button pressed: ${event.content.button}")
        }
        lane.on<Event.ScaleConfig> { event ->
            val c = event.content
            // The Bookoo's `ff12` command channel reports its dynamic
            // config: a `03 0c` serial response carries anti_mistouch (plus
            // serial / firmware), a `03 0e` settings response carries
            // active_mode. Any one notification fills only one set, so the
            // other fields are null — fold in whatever is present and keep
            // the last value for the rest, the same two-way pattern the
            // weight-stream settings use.
            updateUi { it.copy(
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
    }

    /** Run [edit] unless this device is a remote ([refusesOwnEdit]). */
    private inline fun editOwn(setting: Setting, edit: () -> Unit) {
        if (refusesOwnEdit(setting)) return
        edit()
    }

    /** A scale disconnect closed the session: clear the readings, capabilities,
     *  and identity so the scale card/config UI hides until the next scale.
     *  Wired as [ConnectionController]'s onScaleSessionClosed. */
    fun clearScaleSessionUi() {
        // Losing the scale mid-shot silently kills stop-at-weight — the exact
        // complaint in issue #29 ("shots which did not stop on weight due to
        // the scale being disconnected"). The Brew card keeps the yield row
        // with a "no scale" note, but a card the user isn't looking at is not
        // enough for something that will overrun the cup in their hand.
        if (uiState().shotInProgress && uiState().stopTargets?.weightConfiguredG != null) {
            notify("Scale disconnected — stop-at-weight is off for this shot")
        }
        // Re-arm the once-per-connection low-battery warning (issue #29), on
        // the lane: a reading from the old connection still queued there must
        // not see the reset (cohort review 2026-10-25, Android P2-3).
        lane.post { scaleLowBatteryWarned = false }
        refreshStopTargets()
        updateUi { it.copy(
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
    fun refreshScaleCapabilities(advertisedName: String) =
        // Bridge read + heartbeat-guard mutation → core lane (review #28).
        lane.post { refreshScaleCapabilitiesOnCoreLane(advertisedName) }

    private fun refreshScaleCapabilitiesOnCoreLane(advertisedName: String) {
        val capsJson = runCatching { bridge.scaleCapabilities() }.getOrNull()
        val caps = capsJson?.let {
            runCatching {
                json.decodeFromString(ScaleCapabilities.serializer(), it)
            }.getOrNull()
        }
        updateUi { it.copy(
            scaleCapabilities = caps,
            scaleName = advertisedName,
        ) }

        val queryRaw = runCatching { bridge.queryScaleSettings() }.getOrElse {
            appendLog("Query scale settings failed: ${it.message}")
            return
        }
        lane.deliver(queryRaw)
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
        editOwn(Setting.SCALE_VOLUME) {
            val range = uiState().scaleCapabilities?.volume
            val clamped = if (range != null) {
                level.coerceIn(range.min.toInt(), range.max.toInt())
            } else {
                level
            }
            updateUi { it.copy(scaleVolume = clamped) }
            val raw = runCatching {
                bridge.setScaleVolume(clamped.toUByte())
            }.getOrElse {
                appendLog("Set volume failed: ${it.message}")
                return
            }
            lane.deliver(raw)
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
        editOwn(Setting.SCALE_STANDBY) {
            val range = uiState().scaleCapabilities?.standby
            val clamped = if (range != null) {
                minutes.coerceIn(range.min.toInt(), range.max.toInt())
            } else {
                minutes
            }
            updateUi { it.copy(scaleStandbyMinutes = clamped) }
            val raw = runCatching {
                bridge.setScaleStandby(clamped.toUByte())
            }.getOrElse {
                appendLog("Set standby failed: ${it.message}")
                return
            }
            lane.deliver(raw)
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
        editOwn(Setting.SCALE_FLOW_SMOOTHING) {
            updateUi { it.copy(scaleFlowSmoothing = enabled) }
            val raw = runCatching {
                bridge.setScaleFlowSmoothing(enabled)
            }.getOrElse {
                appendLog("Set flow smoothing failed: ${it.message}")
                return
            }
            lane.deliver(raw)
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
        editOwn(Setting.SCALE_ANTI_MISTOUCH) {
            updateUi { it.copy(scaleAntiMistouch = enabled) }
            val raw = runCatching {
                bridge.setScaleAntiMistouch(enabled)
            }.getOrElse {
                appendLog("Set anti-mistouch failed: ${it.message}")
                return
            }
            lane.deliver(raw)
        }
    }

    /**
     * Switch the connected scale to display mode [modeId]. Asks the core for
     * the command bytes, then routes the result through the shared command
     * path. Switching a mode is **three** `Command.WriteScale` entries; the
     * core emits them in order and [CoreLane.applyOutput]'s `commands.forEach`
     * loop preserves that order, so all three reach `ScaleBleManager` in
     * sequence. Capability-gated: the core emits commands only for a scale that
     * exposes switchable modes and only for a listed [modeId].
     *
     * The UI's shown active mode is updated optimistically; the live
     * `Event.ScaleConfig.active_mode` stream (the scale's `03 0e` settings
     * response) then catches up and confirms the scale's real mode.
     */
    fun setScaleMode(modeId: Int) {
        editOwn(Setting.SCALE_MODE) {
            updateUi { it.copy(scaleActiveMode = modeId) }
            val raw = runCatching {
                bridge.setScaleMode(modeId.toUByte())
            }.getOrElse {
                appendLog("Set mode failed: ${it.message}")
                return
            }
            lane.deliver(raw)
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
        editOwn(Setting.SCALE_AUTO_STOP) {
            updateUi { it.copy(scaleAutoStop = modeId) }
            val raw = runCatching {
                bridge.setScaleAutoStop(modeId.toUByte())
            }.getOrElse {
                appendLog("Set auto-stop failed: ${it.message}")
                return
            }
            lane.deliver(raw)
        }
    }

    /** [MainViewModel.tareScale]'s body on the device holding the machine. */
    fun applyTareScale() {
        val raw = runCatching { bridge.tareScale() }.getOrElse {
            appendLog("Tare failed: ${it.message}")
            return
        }
        lane.deliver(raw)
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
        lane.deliver(raw)
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
        lane.deliver(raw)
    }
}
