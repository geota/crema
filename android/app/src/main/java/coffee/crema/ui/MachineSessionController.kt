package coffee.crema.ui

import android.content.Context
import android.os.SystemClock
import coffee.crema.core.Event
import coffee.crema.core.HotWaterTarget
import coffee.crema.core.MachineState
import coffee.crema.core.MaintenancePhase
import coffee.crema.core.MmrRegister
import coffee.crema.core.ShotDisposition
import coffee.crema.core.StartPhase
import coffee.crema.core.newShotId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The DE1 registers a hub sends its remotes (spec §6.1: A12, A13, A15). */
private val MIRRORED_REGISTERS = setOf(MmrRegister.HeaterVoltage, MmrRegister.CalibrationFlowMultiplier, MmrRegister.CupWarmerTemp)

/**
 * The machine session as the core reports it (MainViewModel breakup step 9a):
 * every machine event's projection into the UI state and the event log —
 * machine state and faults, the service-mode clock, the shot (start, phase,
 * frame, telemetry chart, stop targets and stops, completion and its record),
 * water and steam sessions, the tank, SAW notices, identity and MMR reads,
 * the core's shot start and machine-request outcomes, and profile uploads.
 *
 * Registers its core-event handlers on [lane] as it is built, before the
 * other owners of the same events (maintenance, presence's wiring), the
 * order they ran in as the VM's own.
 */
internal class MachineSessionController(
    private val lane: CoreLane,
    private val app: Context,
    private val scope: CoroutineScope,
    private val uiState: () -> MainUiState,
    private val updateUi: ((MainUiState) -> MainUiState) -> Unit,
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
) {
    /** Ticker driving [MainUiState.modeElapsedMs] while a service mode runs. */
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
                    updateUi { it.copy(modeElapsedMs = SystemClock.elapsedRealtime() - started) }
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
                    modeElapsedMs = if (started == null) 0L else prev.modeElapsedMs,
                )
            }
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
            updateUi { it.copy(
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
            val line = fmt("t=%dms  P=%.1fbar  flow=%.1fmL/s  head=%.1f°C", 
                t.elapsed.toLong(), t.group_pressure, t.group_flow, t.head_temp,
            )
            // Atomic read-modify-write: build the chart buffer from the lambda's
            // `prev` (the live state), so a concurrent Event.ScaleReading update{}
            // landing mid-frame isn't clobbered (issue 11).
            updateUi { prev ->
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
        lane.on<Event.WaterLevel> { event ->
            updateUi { it.copy(
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
                c.weight_configured, c.weight_blocked,
            )) }
            appendLog((if (c.armed) "Stop targets armed: " else "Stop targets (next shot): ") + armed)
        }
        lane.on<Event.StopRetried> { event ->
            // The machine hasn't honoured a commanded stop — the core is
            // re-commanding it (issue #15). Loud on purpose.
            appendLog("Stop NOT honoured — re-commanding (attempt ${event.content.attempt}, ${event.content.reason.string})")
        }
        lane.on<Event.StopTriggered> { event ->
            appendLog("Auto-stop: ${event.content.reason.string}")
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
            // The machine stopped: no stop write still queued may land late.
            lane.cancelStaleStop()
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
}
