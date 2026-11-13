package coffee.crema.ui

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import coffee.crema.ProcessScope
import coffee.crema.core.Command
import coffee.crema.core.ConnectSweepSettings
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.HotWaterTarget
import coffee.crema.core.MachineRequest
import coffee.crema.core.MachineState
import coffee.crema.core.MaintenancePhase
import coffee.crema.core.MmrRegister
import coffee.crema.core.ProfileSyncOutput
import coffee.crema.core.ProfileSyncStatus
import coffee.crema.core.ShotDisposition
import coffee.crema.core.StartPhase
import coffee.crema.core.StartRefusal
import coffee.crema.core.StartShotResult
import coffee.crema.core.StopReason
import coffee.crema.core.WriteTarget
import coffee.crema.core.cremaProfileToWire
import coffee.crema.core.newShotId
import coffee.crema.machine.CommandOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** The DE1 registers a hub sends its remotes (spec §6.1: A12, A13, A15). */
private val MIRRORED_REGISTERS = setOf(MmrRegister.HeaterVoltage, MmrRegister.CalibrationFlowMultiplier, MmrRegister.CupWarmerTemp)

/**
 * The machine session (MainViewModel breakup step 9).
 *
 * As the core reports it (9a): every machine event's projection into the UI
 * state and the event log — machine state and faults, the service-mode
 * clock, the shot (start, phase, frame, telemetry chart, stop targets and
 * stops, completion and its record), water and steam sessions, the tank,
 * SAW notices, identity and MMR reads, the core's shot start and
 * machine-request outcomes, and profile uploads.
 *
 * What the shell asks of the machine (9b): the shot start, the desired
 * profile (the core waits for the dial to rest), the stop targets, machine-state writes
 * (sleep-on-quit's included), and the connect-time setup (identity reads,
 * the settings sweep, the USB-charger minute tick) with its session clear.
 *
 * Registers its core-event handlers on [lane] as it is built, before the
 * other owners of the same events (maintenance), the order they ran in as
 * the VM's own.
 */
internal class MachineSessionController(
    private val lane: CoreLane,
    private val app: Context,
    private val scope: CoroutineScope,
    private val uiState: () -> MainUiState,
    private val updateUi: ((MainUiState) -> MainUiState) -> Unit,
    private val updateLive: ((LiveTelemetry) -> LiveTelemetry) -> Unit,
    private val effectiveState: () -> MainUiState,
    private val appendLog: (String) -> Unit,
    private val notify: (String) -> Unit,
    /** Presence's half of a state report, before the new state is shown. */
    private val onMachineState: (prev: MachineState?, state: MachineState, mode: Boolean) -> Unit,
    /** The DE1 link's connection priority: HIGH for the shot window. */
    private val setLinkPriority: suspend (Boolean) -> Unit,
    /** The library records a completed shot ([LibraryController.captureCompletedShot]). */
    private val captureCompletedShot: (Long, Float?, Float?, Float?, Long, String, ShotDisposition, Float?) -> Unit,
    /** Show the core's hot-water target (a hub tells its remotes). */
    private val setHotWaterTarget: (HotWaterTarget?) -> Unit,
    /** Push the hub's config snapshot to its remotes (a no-op elsewhere). */
    private val pushConfig: () -> Unit,
    /** Arm the core's one-shot wake tick (`WakeAt`). */
    private val armWake: (Long) -> Unit,
    /** A scale is remembered (so a missing one is reconnecting). */
    private val scaleRemembered: () -> Boolean,
    private val kickScaleReconnect: () -> Unit,
    private val bridge: CremaBridge,
    private val json: Json,
    /** The library's raw profile JSON by id. */
    private val profileJson: (String) -> String?,
    /** Re-read the core's hot-water target (its ratio follows the target yield). */
    private val refreshHotWaterTarget: () -> Unit,
    /** Presence: the next Sleep report is the app's own request. */
    private val markAppSleepRequest: () -> Unit,
    /** A firmware flash holds the DE1 link (no charger writes). */
    private val firmwareHolds: () -> Boolean,
    /** The connect-time setup has run on this link (the flash may start). */
    private val markSetupDone: () -> Unit,
    /** The DE1 link is READY. */
    private val de1Ready: () -> Boolean,
    /** The connected DE1's address, or null. */
    private val de1Address: () -> String?,
    /** One direct DE1 write, outside the lane (sleep-on-quit). */
    private val writeDe1: suspend (WriteTarget, ByteArray) -> Boolean,
) {
    /** Ticker driving [LiveTelemetry.modeElapsedMs] while a service mode runs. */
    private var modeTickerJob: Job? = null

    /** Start the 4 Hz mode-clock ticker when a service mode is running, stop it
     *  when idle. Elapsed is recomputed from the monotonic anchor each tick, so
     *  tick jitter never accumulates into the readout. */
    private fun updateModeTicker() {
        val active = uiState().modeStartedAtMs != null
        if (active) {
            if (modeTickerJob?.isActive == true) return
            modeTickerJob = scope.launch {
                while (true) {
                    val started = uiState().modeStartedAtMs ?: break
                    updateLive { it.copy(modeElapsedMs = SystemClock.elapsedRealtime() - started) }
                    delay(250)
                }
            }
        } else {
            modeTickerJob?.cancel()
            modeTickerJob = null
        }
    }

    /** Decent's stolen-machine list (fetched at most daily, cached). */
    private val stolenSerials = coffee.crema.decent.StolenSerialsCheck(
        coffee.crema.decent.StolenSerialsFileStore(app),
    )

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

    // Declared above `init`: a property below it is unset while it runs (P3-2).

    /** Runs [seedMachineSettings] on each ready and [usbChargerTick] every minute while ready. */
    val sweepRunner by lazy {
        ConnectSweepRunner(scope, sweep = ::seedMachineSettings, tick = ::usbChargerTick)
    }

    /** Connect-time machine-info sweep (see [readMachineInfo]); cancelled on reconnect/disconnect. */
    private var machineInfoJob: kotlinx.coroutines.Job? = null

    /** The pending [pushDesiredProfile] (a dial change waiting to settle). */
    private var desiredPushJob: Job? = null

    init {
        lane.on<Event.MachineStateChanged> { event ->
            val c = event.content
            // Service-mode clock (steam / hot water / flush): anchor a
            // monotonic start on entry, re-anchor on a direct mode→mode
            // swap, clear on exit. The 4 Hz ticker feeds the Brew timers
            // while a mode runs — mirrors the web BrewDashboard's
            // modeStartedAtMs / modeNowMs pair.
            val mode = c.state == MachineState.Steam ||
                c.state == MachineState.HotWater ||
                c.state == MachineState.HotWaterRinse
            // The screensaver ↔ machine-sleep coupling, before this state is shown.
            onMachineState(uiState().machineStateName, c.state, mode)
            updateUi { prev ->
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
                )
            }
            // After the state: the mode's card leaves with it, so its timer
            // never shows a zero first.
            if (uiState().modeStartedAtMs == null) updateLive { it.copy(modeElapsedMs = 0L) }
            updateModeTicker()
            appendLog("MachineState -> ${c.state.string} / ${c.substate.string}")
        }
        lane.on<Event.MaintenanceProgress> { event ->
            val c = event.content
            val terminal = isMaintenanceTerminal(c.phase)
            val phaseChanged = uiState().maintenanceRun?.phase != c.phase
            updateUi { it.copy(maintenanceRun = if (terminal) null else c) }
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
                if (c.phase == MaintenancePhase.Failed) notify(maintenanceFailedText(c.state, c.failure))
            }
        }
        lane.on<Event.MachineErrorChanged> { event ->
            // The fault to surface, decided in core so web and Android agree:
            // every Error* substate at once, except ErrorNoAc, which the DE1
            // reports spuriously while waking / heating and core holds until
            // it persists (firmware >= 1337, 6 s — Decenza 98215217 /
            // b8d625ba). A null message clears it.
            val c = event.content
            updateUi { it.copy(machineError = c.message) }
            appendLog(
                if (c.message == null) "Machine fault cleared (${c.substate.string})"
                else "Machine fault: ${c.substate.string} — ${c.message}",
            )
        }
        lane.on<Event.ShotStarted> {
            // Entering a shot: flip the resting↔extracting flag and zero the
            // per-shot counters so the Brew timer / phase / volume restart.
            // The readouts first: nothing sees a running shot over the last
            // shot's samples. The next sample lands after both, on this lane.
            updateLive { it.copy(
                shotElapsedMs = 0,
                dispensedVolume = 0f,
                shotTelemetry = emptyList(),
            ) }
            updateUi { it.copy(
                shotInProgress = true,
                shotFrame = 0,
                // Fresh shot — the previous stop attribution no longer applies.
                lastStopReason = null,
            ) }
            // Fresh shot — clear the previous shot's record markers so a
            // stale "armed" line can never be attributed to this one (#56).
            lastArmedTargets = null
            // Tighten the DE1 link for the shot window (steadier ShotSample
            // cadence + lower-latency SAW stop write); restored to BALANCED
            // on ShotCompleted. Best-effort — see De1BleManager.
            scope.launch { setLinkPriority(true) }
            appendLog("Shot started")
        }
        lane.on<Event.ShotPhaseChanged> { event ->
            val phase = event.content.phase.string
            updateUi { it.copy(shotPhase = phase) }
            appendLog("Shot phase -> $phase")
        }
        lane.on<Event.ShotFrameChanged> { event ->
            updateUi { it.copy(shotFrame = event.content.frame.toInt()) }
            appendLog("Shot frame -> ${event.content.frame}")
        }
        lane.on<Event.Telemetry> { event ->
            val t = event.content
            // The counted water (`counted_ml`) is [MaintenanceController]'s.
            // Atomic read-modify-write: build the chart buffer from the lambda's
            // `prev` (the live state), so a concurrent Event.ScaleReading update{}
            // landing mid-frame isn't clobbered (issue 11). The shot's state is
            // read in the lambda too, so a retry sees a disconnect's clear.
            updateLive { prev ->
                val shot = uiState()
                // Append a chart sample only during the FLOW window
                // (preinfusion / pouring), like the core's own ShotRecord:
                // the pre-pour heating phase piles zero-elapsed points and
                // the Espresso/Ending tail (pump wind-down after the stop)
                // used to keep the curve growing past the stop point —
                // issue #44's "truncate at the stop" report. Fold in the
                // latest scale weight/flow so the WEIGHT curves track the
                // cup. FIFO-capped.
                val flowing = shot.shotPhase == "Preinfusion" || shot.shotPhase == "Pouring"
                val nextBuffer = if (shot.shotInProgress && flowing) {
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
                        frameNumber = shot.shotFrame,
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
                // Project the structured channels the Brew dashboard reads.
                // High-rate; no log line.
                prev.copy(
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
        lane.on<Event.WaterLevel> { event ->
            // The threshold first: `refillSoon` reads both, and a level never
            // shows without the threshold from its own packet.
            updateUi { it.copy(waterRefillThresholdMm = event.content.refill_threshold) }
            updateLive { it.copy(waterLevelMm = event.content.level) }
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
        lane.on<Event.WaterLow> { event ->
            // The core's low-water monitor (#33 follow-up, shared with web):
            // once per dip below the user's threshold, re-armed on a refill,
            // a threshold change or a new link. Shown in the user's tank unit.
            val s = uiState()
            val left = formatTankLevel(event.content.level, s.waterLevelUnit, s.volumeUnit)
            notify("Water low — ${left.value} ${left.unit} left, refill soon")
        }
        lane.on<Event.HotWaterTarget> { event ->
            // A remote's own core is a read-only mirror: not the hub's number.
            if (uiState().mirror == null) setHotWaterTarget(event.content.target)
        }
        lane.on<Event.ShotSettingsForeignChange> { event ->
            appendLog(
                if (event.content.restored) "Steam / hot-water settings changed outside Crema: restoring them"
                else "Steam / hot-water settings changed outside Crema again: left as they are",
            )
        }
        lane.on<Event.ShotSettingsRead> { event ->
            // Project into UI state: the mode chips + steam / hot-water
            // timers read the machine's own targets (web de1ShotSettings parity).
            updateUi { it.copy(de1ShotSettings = event.content) }
        }
        lane.on<Event.SawAutoZeroed> { event ->
            val w = fmt("%.0f", event.content.offset_g)
            appendLog("SAW re-zeroed on the fly ($w g cup)")
            notify("Scale re-zeroed on the fly — $w g was on it")
        }
        lane.on<Event.SawSuppressedUntaredCup> { event ->
            val w = fmt("%.0f", event.content.weight_g)
            appendLog("SAW suppressed — untared cup ($w g)")
            notify("Stop-at-weight off for this shot — the scale wasn\u2019t tared ($w g on it)")
        }
        lane.on<Event.StopTargetsArmed> { event ->
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
            updateUi { it.copy(stopTargets = StopTargetsView(
                c.weight, c.volume, c.max_time, c.armed,
                c.weight_configured, c.weight_blocked, c.weight_blocked_note,
            )) }
            appendLog((if (c.armed) "Stop targets armed: " else "Stop targets (next shot): ") + armed)
        }
        lane.on<Event.StopRetried> { event ->
            // The machine hasn't honoured a commanded stop — the core is
            // re-commanding it (issue #15). Loud on purpose.
            appendLog("Stop NOT honoured — re-commanding (attempt ${event.content.attempt}, ${event.content.reason?.string ?: "your Stop"})")
        }
        lane.on<Event.StopNotHonoured> { event ->
            // The machine ignores Crema's stop: the user must press it.
            appendLog("Stop not honoured: ${event.content.message}")
            notify(event.content.message)
        }
        lane.on<Event.StopTriggered> { event ->
            appendLog(
                if (event.content.reason == StopReason.EstimatedWeight) {
                    "Auto-stop: on estimate, scale lost"
                } else {
                    "Auto-stop: ${event.content.reason.string}"
                },
            )
            // Attribute the stop ON the stop-conditions UI (the fired
            // row/card gets a gold highlight) instead of a toast — an
            // unexplained early stop reads as a bug, but a snackbar over
            // the fresh shot was clutter. Espresso only: a hot-water
            // weight stop must not ring the espresso stop cards.
            updateUi {
                if (it.shotInProgress) it.copy(lastStopReason = event.content.reason) else it
            }
        }
        lane.on<Event.ShotCompleted> { event ->
            // A stop still queued was cancelled when the machine wound down
            // (`Command.CancelStop`).
            val c = event.content
            val now = System.currentTimeMillis()
            // One UUID-v7 shot id (core minter), shared by the "Last shot"
            // card and the StoredShot captureCompletedShot persists, so
            // tapping the card opens the stored shot.
            val shotId = newShotId()
            // Leaving a shot: clear the extracting flag and capture the
            // summary for the Brew "Last shot" card.
            updateUi { it.copy(
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
            scope.launch { setLinkPriority(false) }
            captureCompletedShot(
                c.duration.toLong(), c.final_weight, c.peak_pressure, c.peak_temp, now, shotId,
                // Core-classified (review #40); null only on a version-skewed
                // event stream → record.
                c.disposition ?: ShotDisposition.Record,
                c.peak_weight, // peakWeightG
            )
            // The water flush is [MaintenanceController]'s own handler.
            // Freeze this shot's event trail to disk while it is still in
            // the rolling buffer. A "stop at weight didn't work" report
            // reaches us long after the shot, by which time the buffer has
            // scrolled — see [ShotDiag] (geota/crema#56).
            coffee.crema.diag.ShotDiag.finish(app, shotDiagSummary(c))
            // Consumed — the next shot is a machine start until the app
            // says otherwise.
            shotStartedByApp = false
        }
        lane.on<Event.WaterSessionStarted> { event -> appendLog("Water session started: ${event.content.kind.string}") }
        lane.on<Event.WaterSessionCompleted> { event -> appendLog("Water session completed: ${event.content.kind.string}") }
        lane.on<Event.SteamSessionStarted> { appendLog("Steam session started") }
        lane.on<Event.SteamSessionCompleted> { event ->
            // The post-steam purge is the core's (F16, the `steam_purge` setting).
            appendLog("Steam session completed: ${event.content.duration}ms")
        }
        lane.on<Event.SteamClogSuspected> { event -> appendLog("Steam clog suspected: ${event.content.reason.string}") }
        lane.on<Event.SteamEcoModeChanged> { event -> appendLog("Steam eco mode: ${event.content.eco}") }
        lane.on<Event.DecodeError> { event -> appendLog("Decode error: ${event.content.message}") }
        lane.on<Event.UnknownMachineByte> { event -> appendLog("Unknown machine substate byte: ${event.content.byte}") }
        // The version / MMR read paths now feed the Settings machine rows.
        // The preformatted firmware label is folded straight in; each MMR
        // value lands raw in de1MachineInfo (the UI applies per-register
        // scaling). Keep a log line so the data stays visible in the debug
        // readout too.
        lane.on<Event.Firmware> { event ->
            updateUi { it.copy(de1Firmware = event.content.firmware_string) }
            appendLog("Firmware: ${event.content.firmware_string}")
        }
        lane.on<Event.MmrValue> { event ->
            val c = event.content
            val before = uiState().de1MachineInfo[c.register]
            updateUi { it.copy(
                de1MachineInfo = it.de1MachineInfo + (c.register to c.value),
            ) }
            // Remotes show these registers (A12, A13, A15): push the change.
            // They arrive after the write's reply, and none is relayed, so
            // only the push itself is owed here (§6.1). Built on Main.
            if (c.register in MIRRORED_REGISTERS && before != c.value) {
                scope.launch(Dispatchers.Main) { pushConfig() }
            }
            appendLog("MMR ${c.register}: ${c.value}")
            if (c.register == MmrRegister.SerialNumber) checkStolenSerial(c.value)
        }
        // Calibration value replies (current / factory) have no settings row
        // yet, so keep logging them. Core surfaces only WriteKey-0 replies,
        // never echoes of our own requests; the stored value is `measured`.
        lane.on<Event.Calibration> { event ->
            appendLog(
                "Calibration ${event.content.target} " +
                    "(${event.content.command}): ${event.content.measured}",
            )
        }
        lane.on<Event.WakeAt> { event -> armWake(event.content.at_ms.toLong()) }
        // F16: the core runs the shot start.
        lane.on<Event.ShotStartProgress> { event ->
            updateUi { it.copy(shotStarting = true) }
            // Records whether this shot came from Crema or from the
            // machine's GHC — the distinction that hid geota/crema#56.
            if (event.content.phase == StartPhase.Requested) shotStartedByApp = true
        }
        lane.on<Event.ShotStartEnded> { event ->
            updateUi { it.copy(shotStarting = false) }
            val end = event.content.end
            appendLog("Shot start ended: ${end::class.simpleName}")
            event.content.message?.let(notify)
        }
        lane.on<Event.StartNotice> { event ->
            // No scale for a shot that stops on weight: non-blocking — the
            // stop-at-weight arms mid-shot if the scale lands.
            val remembered = scaleRemembered()
            if (remembered) kickScaleReconnect()
            notify(
                if (remembered) "Scale not connected \u2014 reconnecting\u2026 stop-at-weight is off until it lands"
                else event.content.notice.message,
            )
        }
        lane.on<Event.GroupHeadStartUnchecked> { event ->
            // A shot started on the machine skipped the app-only steps
            // that applied: one non-blocking notice; it never stops the shot.
            val lines = event.content.notices.map { it.message }
            appendLog("Shot started on the machine: ${lines.joinToString("; ")}")
            notify(lines.joinToString("\n"))
        }
        lane.on<Event.MachineRequestHeld> { event ->
            appendLog("${event.content.state.string} request held until the profile finishes loading")
            notify(REQUEST_HELD_TEXT)
        }
        lane.on<Event.MachineRequestDropped> { event ->
            val text = machineRequestDropText(event.content.state, event.content.reason)
            appendLog(text)
            notify(text)
        }
        lane.on<Event.MachineRequestRefused> { event ->
            val text = machineRequestRefusalText(event.content.state, event.content.reason)
            appendLog("Request refused: $text")
            notify(text)
        }
        // v1 stub never fires this in practice.
        lane.on<Event.FirmwareLockoutHit> { event ->
            appendLog(
                "Write refused (firmware update in progress): " +
                    event.content.method,
            )
        }
        lane.on<Event.ProfileUploadStarted> { event ->
            updateUi { it.copy(profileUploading = true, profileUploadProgress = null) }
            appendLog("Uploading profile: ${event.content.title}")
        }
        lane.on<Event.ProfileUploadWaitingForModel> { event ->
            appendLog("Waiting for the machine model before uploading ${event.content.title}")
        }
        lane.on<Event.ProfileUploadProgress> { event ->
            val c = event.content
            // High-rate (one per frame ack); update the progress hint, no log.
            updateUi { it.copy(
                profileUploadProgress = "${c.acks_received}/${c.total_acks}",
            ) }
        }
        lane.on<Event.ProfileUploadCompleted> { event ->
            updateUi { it.copy(profileUploading = false, profileUploadProgress = null) }
            appendLog("Profile uploaded: ${event.content.title}")
        }
        // The core's shot start waits for its own (F16).
        // ProfileSyncHolds: nothing here — the core's shot start waits for its own (F16).
        lane.on<Event.ProfileUploadFailed> { event ->
            updateUi { it.copy(profileUploading = false, profileUploadProgress = null) }
            appendLog("Profile upload failed: ${uploadFailureLabel(event.content.reason)}")
        }
        lane.on<Event.ProfileSyncWarning> { event ->
            // The core's one non-blocking warning per streak of failed
            // profile uploads; it keeps retrying (3 s, 10 s, then 30 s).
            if (event.content.active) {
                notify("Couldn\u2019t load the profile on the DE1 \u2014 retrying")
                appendLog("Profile upload to the DE1 failed \u2014 the core is retrying")
            } else {
                appendLog("Profile upload to the DE1 recovered")
            }
        }
    }

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

    /** Check the DE1's serial against Decent's stolen-machine list (fetched at
     *  most daily, cached, failures silent) and raise the Settings notice. */
    private fun checkStolenSerial(serial: UInt) {
        scope.launch {
            val stolen = stolenSerials.isStolen(serial)
            updateUi {
                if (it.de1MachineInfo[MmrRegister.SerialNumber] == serial) it.copy(serialStolen = stolen) else it
            }
        }
    }

    // ── The shot start, the desired profile and the stop targets ─────────────

    fun afterFirmwareRelease() {
        if (!de1Ready()) return
        appendLog("Firmware: link released — running the connect setup")
        readMachineInfo()
        sweepRunner.onReady()
    }

    /**
     * Push the profile on screen into the core, which takes the stop targets
     * from it (`DESIGN.md` §3.10): the yield is the Quick-Controls dial's, else
     * the profile's, and the volume limit is the profile's. Safe while
     * disconnected — pure core state, no BLE.
     */
    fun pushStopTargets(s: MainUiState = effectiveState()) {
        // On a remote, a hub's custom profile this library lacks is the overlay.
        val active = s.activeProfile()
        // The active profile itself: the core latches its beverage type (a
        // cleaning run must never be recorded as a shot — review #40) and its
        // per-step weight exits (issue 11). Pushed alongside the stop targets —
        // on select, startup restore, every DE1 (re)connect and every shot
        // start — not only on upload, because activation can skip the upload:
        // after an app restart the DE1 still holds the profile and startShot
        // skips it on the fingerprint match.
        pushActiveProfile(
            activeCremaJson = active?.id?.let { profileJson(it) } ?: s.mirroredProfileJson,
            toWire = ::cremaProfileToWire,
            setActiveProfile = { bridge.setActiveProfile(it) },
            log = { appendLog(it) },
        )
        // The desired profile carries the yield and the volume limit; the
        // projection is re-read now for the settings, and again once the core
        // has the profile.
        pushDesiredProfile(s)
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
    fun refreshStopTargetProjection() {
        // The target yield is also the hot-water ratio's basis.
        refreshHotWaterTarget()
        val json = runCatching { bridge.stopTargetsProjection() }.getOrNull() ?: return
        runCatching {
            val o = this.json.parseToJsonElement(json).jsonObject
            fun f(k: String) = o[k]?.jsonPrimitive?.floatOrNull
            updateUi { it.copy(stopTargets = StopTargetsView(
                weightG = f("weight"),
                volumeMl = f("volume"),
                maxTimeS = f("maxTime"),
                armed = o["armed"]?.jsonPrimitive?.booleanOrNull ?: false,
                weightConfiguredG = f("weightConfigured"),
                weightBlocked = o["weightBlocked"]?.jsonPrimitive?.contentOrNull,
                weightBlockedNote = o["weightBlockedNote"]?.jsonPrimitive?.contentOrNull,
            )) }
        }.onFailure { appendLog("Stop-target projection parse failed: ${it.message}") }
    }

    /** [startShot]'s body on the device holding the machine (also a mirror's
     *  relayed start): the core runs the whole start (F16, `shot_start.rs`) —
     *  the profile sync wait, the firmware's profile-download guard, the
     *  optional pre-flush and Espresso — and refuses one that can't start,
     *  with the reason for whoever asked. */
    fun applyStartShot(): CommandOutcome {
        val s = uiState()
        val cremaJson = s.activeProfileId?.let { profileJson(it) }
            ?: return CommandOutcome.Refused("Can\u2019t start shot \u2014 no profile selected")
        // The core's stop targets and flags are current before the shot arms
        // its AutoStop (a profile restored at startup never went through
        // setActiveProfile; a DE1 reconnect re-created the core).
        pushStopTargets()
        runCatching { bridge.setAutoTare(s.autoTare) }
            .onFailure { appendLog("Re-assert auto-tare failed: ${it.message}") }
        runCatching { bridge.setStopOnWeight(s.stopOnWeight) }
            .onFailure { appendLog("Re-assert stop-on-weight failed: ${it.message}") }
        runCatching { bridge.setVolumeStopWithScale(s.volumeStopWithScale) }
            .onFailure { appendLog("Re-assert volume-stop-with-scale failed: ${it.message}") }
        pushStartSettings(s)
        // The start's own push supersedes a dial push still settling.
        desiredPushJob?.cancel()
        val result = runCatching {
            json.decodeFromString(
                StartShotResult.serializer(),
                bridge.startShot(cremaJson, s.brewParams.overridesJson(json), SystemClock.elapsedRealtime().toULong()),
            )
        }.getOrElse {
            appendLog("Start shot failed: ${it.message}")
            return CommandOutcome.Refused("Can\u2019t start shot \u2014 profile conversion failed")
        }
        lane.post { lane.applyOutput(result.output) }
        // The start made its profile the desired one: the targets with it.
        refreshStopTargetProjection()
        if (result.accepted) return CommandOutcome.Accepted
        val remembered = scaleRemembered()
        if (result.refusal is StartRefusal.ScaleRequired && remembered) {
            // The reconnect kick may make the next attempt succeed.
            kickScaleReconnect()
            return CommandOutcome.Refused("Shot not started \u2014 reconnecting the scale\u2026 try again in a moment")
        }
        return CommandOutcome.Refused(result.message ?: "Shot not started")
    }

    /** Latch the start settings (pre-shot flush, require-scale, post-steam
     *  purge) in the core, which runs the start, the purge and the
     *  group-head banner (F16). */
    fun pushStartSettings(s: MainUiState = uiState()) {
        runCatching { bridge.setStartSettings(s.preFlush, s.requireScale, s.steamPurge) }
            .onFailure { appendLog("Set start settings failed: ${it.message}") }
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
        lane.applyOutput(sync.output)
        if (sync.output.events.any { it is Event.DecodeError }) return null
        return sync.status
    }

    /**
     * The profile on screen — the active profile and its Quick-Controls
     * override — as the core's desired profile, which the stop targets come
     * from. Pushed on select, override change, startup restore, every DE1
     * connect and every shot start: the core's profile sync decides when to
     * upload. A remote pushes too, the hub's selection: its core is a
     * read-only mirror that never uploads, but it shows the hub's targets.
     */
    private fun pushDesiredProfile(s: MainUiState = effectiveState()) {
        val cremaJson = s.activeProfile()?.id?.let { profileJson(it) } ?: s.mirroredProfileJson
        val qcJson = s.brewParams.overridesJson(json)
        // The latest push wins. The core waits for a dial to rest (de1app's
        // 0.5 s) before it uploads, on a WakeAt deadline: no timer here.
        desiredPushJob?.cancel()
        desiredPushJob = scope.launch(lane.dispatcher) {
            setDesiredOnLane(cremaJson, qcJson)
            // The targets came with it: re-read them on the UI side.
            scope.launch { refreshStopTargetProjection() }
        }
    }

    /** A DE1 disconnect closed the session: stop the in-flight connect-time
     *  register sweep, drop the pending gated-start, and clear the live
     *  telemetry + machine identity so the Brew/Settings surfaces show "—"
     *  for the next connection rather than freezing on the last machine's
     *  values. Wired as [ConnectionController]'s onDe1SessionClosed. */
    fun clearDe1SessionUi() {
        machineInfoJob?.cancel() // stop any in-flight connect-time register sweep
        // A pending start ends in the core on the drop (Decenza cancels its
        // deferred start on disconnect too).
        updateUi { it.copy(
            machineState = null,
            machineStateName = null,
            machineSubstate = null,
            shotPhase = null,
            shotFrame = 0,
            shotInProgress = false,
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
        // Drop the live telemetry so the Brew cards clear to "—" for the next
        // connection rather than freezing on the last machine's values. After
        // the state: a sample landing between the two can't add to a shot
        // that has ended, and this clear still wipes it.
        updateLive { it.copy(
            pressure = null,
            flow = null,
            headTemp = null,
            mixTemp = null,
            steamTemp = null,
            dispensedVolume = null,
            resistance = null,
            resistanceWeight = null,
            shotElapsedMs = 0,
            shotTelemetry = emptyList(),
            waterLevelMm = null,
        ) }
    }

    /** A user's machine request (a control, or one relayed from a peer). The
     *  core cancels a pending shot start with it (05 F2, F16): a Stop, or
     *  another mode, during the sync or the pre-flush never ends in Espresso. */
    fun applyMachineState(state: MachineRequest) {
        if (state == MachineRequest.SLEEP) markAppSleepRequest()
        writeMachineState(state)
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
        lane.deliver(raw)
    }

    /**
     * Sleep-on-quit's write. NOT [sleep]: that routes through the core lane
     * and its command path, both on [scope], which is already
     * cancelled when [onCleared] fires the quit. The core output is computed
     * synchronously here and its DE1 writes go out on [ProcessScope], bounded
     * by [QUIT_WRITE_BUDGET_MS]. The result is true only when every write
     * completed on a live link, so [QuitSleep]'s latch re-arms otherwise.
     * The output's events are dropped: the UI they would update is going away.
     * [presence] marks the sleep as the app's before calling this.
     */
    fun launchQuitSleepWrite(): Deferred<Boolean> {
        val writes = runCatching {
            json.decodeFromString(CoreOutput.serializer(), bridge.requestMachineState(MachineRequest.SLEEP))
                .commands.filterIsInstance<Command.WriteCharacteristic>()
        }.getOrElse {
            Log.w("QuitSleep", "sleep request failed", it)
            emptyList()
        }
        if (de1Address() == null) return CompletableDeferred(false)
        return ProcessScope.main.async {
            sendQuitWrites(
                writes.map { cmd ->
                    suspend {
                        writeDe1(cmd.content.target, cmd.content.data.map { it.toByte() }.toByteArray())
                        Unit
                    }
                },
                QUIT_WRITE_BUDGET_MS,
            ).also { sent -> Log.i("QuitSleep", if (sent) "sleep write sent" else "sleep write did not go out") }
        }
    }

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
     *  route through [CoreLane.route], a no-op when disconnected. */
    fun seedMachineSettings(onReady: Boolean = true) {
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
        val ui = uiState()
        val tankTempC = ui.profiles.firstOrNull { it.id == ui.activeProfileId }?.tankTemperatureC ?: 0f
        val settings = ui.connectSweepSettings(tankTempC, batteryPercent())
        lane.route("Connect sweep") {
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
    fun usbChargerTick() {
        if (firmwareHolds()) return // no charger writes into a flash (audit H2)
        lane.route("USB charger check") {
            bridge.usbChargerTick(uiState().usbChargingMode, batteryPercent()?.toUByte())
        }
    }

    /** The tablet battery, %, or null when the platform won't say. */
    private fun batteryPercent(): Int? = runCatching {
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }.getOrNull()

    fun readMachineInfo() {
        // The core's identity table (web reads the same one): firmware build
        // first, then board, model, GHC, refill kit, serial, heater voltage,
        // flush temp and flow multiplier. The passes and pacing below stay
        // this shell's policy.
        val registers = coffee.crema.core.connectIdentityRegisters()
        machineInfoJob?.cancel()
        machineInfoJob = scope.launch {
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
                    if (raw != null) lane.deliver(raw)
                    delay(readGapMs)
                }
                // Stop once the identity the UI actually shows has answered.
                if (uiState().de1Firmware != null &&
                    MmrRegister.HeaterVoltage in uiState().de1MachineInfo
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
            updateUi { it.copy(lineFreqHz = freq) }
            markSetupDone()
        }
    }

    fun applyCancelMaintenance() {
        val raw = runCatching { bridge.cancelMaintenance() }.getOrElse {
            appendLog("cancelMaintenance failed: ${it.message}")
            return
        }
        lane.deliver(raw)
    }
}
