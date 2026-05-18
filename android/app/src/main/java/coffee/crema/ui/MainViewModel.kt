package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.ScaleCapabilities
import coffee.crema.ble.BleScanner
import coffee.crema.ble.BleSessionRecorder
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.NordicBleTransport
import coffee.crema.ble.ScaleBleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Default scale beeper-volume step shown when a volume-capable scale connects.
 * The scale's true current volume is not read back in this slice (it needs the
 * `0x0f` settings decode), so the control starts here and is write-only. The
 * control's actual range is taken from the scale's `ScaleCapabilities.volume`
 * descriptor; this default is only the starting step.
 */
const val DEFAULT_SCALE_VOLUME: Int = 3

/**
 * Default scale auto-standby timeout (minutes) shown when a standby-capable
 * scale connects. Like the volume default this is write-only for this slice —
 * the scale's true value is not read back (it needs the `0x0f` settings
 * decode). The control's actual range comes from the scale's
 * `ScaleCapabilities.standby_minutes` descriptor; this is only the starting step.
 */
const val DEFAULT_SCALE_STANDBY_MINUTES: Int = 15

/** A flat snapshot of everything the current screen shows. */
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
    /** Latest telemetry sample, pre-formatted. */
    val telemetry: String? = null,
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
     * The scale beeper-volume step (0..5) the UI currently shows. Write-only
     * for this slice: the scale's actual current volume is not read back (that
     * needs the `0x0f` settings decode, not yet implemented), so this starts
     * at a sensible default and tracks only what the user last set.
     */
    val scaleVolume: Int = DEFAULT_SCALE_VOLUME,
    /**
     * The scale auto-standby timeout (minutes) the UI currently shows.
     * Write-only for this slice, like [scaleVolume] — the scale's actual value
     * is not read back. Starts at [DEFAULT_SCALE_STANDBY_MINUTES].
     */
    val scaleStandbyMinutes: Int = DEFAULT_SCALE_STANDBY_MINUTES,
    /**
     * Whether the scale's flow smoothing toggle is shown as on. Write-only —
     * the scale's actual state is not read back; starts off.
     */
    val scaleFlowSmoothing: Boolean = false,
    /**
     * Whether the scale's anti-mistouch toggle is shown as on. Write-only —
     * the scale's actual state is not read back; starts off.
     */
    val scaleAntiMistouch: Boolean = false,
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
        onStatus = { line -> _ui.value = _ui.value.copy(status = line) },
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
        onStatus = { line -> _ui.value = _ui.value.copy(status = line) },
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
        onStatus = { line -> _ui.value = _ui.value.copy(status = line) },
        onScaleIdentified = ::refreshScaleCapabilities,
    )

    init {
        // Mirror the BLE manager's coarse state into the UI snapshot.
        // (Read once here for the initial value; see observeBleState below.)
    }

    /** Called from the UI once the BLE runtime permissions have been granted. */
    fun connect() {
        _ui.value = _ui.value.copy(eventLog = emptyList())
        // Show "scanning" on the connection-status UI while the shared scanner
        // hunts; the scanner's onFound hands the matched DE1 to ble.connect.
        ble.markScanning()
        bleScanner.scanFor(SCAN_LABEL_DE1, De1BleManager::isDe1Name) { device, _ ->
            ble.connect(device)
        }
        // Reflect the manager's state as it advances.
        observeBleState()
    }

    fun disconnect() {
        // Drop any outstanding scan want too — the user may disconnect mid-scan.
        bleScanner.cancel(SCAN_LABEL_DE1)
        ble.disconnect()
        _ui.value = _ui.value.copy(
            bleState = De1BleManager.State.DISCONNECTED,
            machineState = null,
            shotPhase = null,
            telemetry = null,
        )
    }

    /** Scan for and connect to a Bookoo scale. Independent of the DE1. */
    fun connectScale() {
        scale.markScanning()
        bleScanner.scanFor(SCAN_LABEL_SCALE, ScaleBleManager::isBookooName) { device, name ->
            scale.connect(device, name)
        }
        observeBleState()
    }

    fun disconnectScale() {
        bleScanner.cancel(SCAN_LABEL_SCALE)
        scale.disconnect()
        _ui.value = _ui.value.copy(
            scaleState = ScaleBleManager.State.DISCONNECTED,
            scaleWeightG = null,
            scaleFlowGPerS = null,
            scaleTimerMs = null,
            // Drop the capabilities so the config UI hides until the next scale.
            scaleCapabilities = null,
            scaleVolume = DEFAULT_SCALE_VOLUME,
            scaleStandbyMinutes = DEFAULT_SCALE_STANDBY_MINUTES,
            scaleFlowSmoothing = false,
            scaleAntiMistouch = false,
        )
    }

    /**
     * Read the connected scale's [ScaleCapabilities] from the core and fold
     * them into the UI snapshot. Called by [ScaleBleManager] once the core has
     * identified the scale; the UI gates configuration controls on the result.
     *
     * Capability-driven, never device-driven: the UI never branches on the
     * concrete scale model, only on the capability flags.
     */
    private fun refreshScaleCapabilities() {
        val capsJson = runCatching { bridge.scaleCapabilities() }.getOrNull()
        val caps = capsJson?.let {
            runCatching {
                json.decodeFromString(ScaleCapabilities.serializer(), it)
            }.getOrNull()
        }
        _ui.value = _ui.value.copy(scaleCapabilities = caps)
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
     * optimistically (the value is not read back).
     */
    fun setScaleVolume(level: Int) {
        val range = _ui.value.scaleCapabilities?.volume
        val clamped = if (range != null) {
            level.coerceIn(range.min.toInt(), range.max.toInt())
        } else {
            level
        }
        _ui.value = _ui.value.copy(scaleVolume = clamped)
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
     * auto-standby. The UI's shown value is updated optimistically.
     */
    fun setScaleStandbyMinutes(minutes: Int) {
        val range = _ui.value.scaleCapabilities?.standby_minutes
        val clamped = if (range != null) {
            minutes.coerceIn(range.min.toInt(), range.max.toInt())
        } else {
            minutes
        }
        _ui.value = _ui.value.copy(scaleStandbyMinutes = clamped)
        val raw = runCatching {
            bridge.setScaleStandbyMinutes(clamped.toUByte())
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
     * updated optimistically (the value is not read back).
     */
    fun setScaleFlowSmoothing(enabled: Boolean) {
        _ui.value = _ui.value.copy(scaleFlowSmoothing = enabled)
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
        _ui.value = _ui.value.copy(scaleAntiMistouch = enabled)
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
     */
    fun setScaleMode(modeId: Int) {
        val raw = runCatching {
            bridge.setScaleMode(modeId.toUByte())
        }.getOrElse {
            appendLog("Set mode failed: ${it.message}")
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

    private fun observeBleState() {
        // The managers expose StateFlows; for now their current values are
        // polled whenever a status line lands. Collecting them in coroutines is
        // a follow-up; the values are folded in here on each call.
        _ui.value = _ui.value.copy(
            bleState = ble.state.value,
            scaleState = scale.state.value,
        )
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
        // Reflect current BLE state too.
        observeBleState()
        output.events.forEach(::applyEvent)
        output.commands.forEach(::executeCommand)
    }

    /**
     * Centralised command execution: every `CoreOutput` — from the DE1 manager
     * AND the scale manager — funnels its `commands` here. This single path
     * makes both the manual Tare button and the core's automatic shot-start
     * auto-tare work, because both surface as a [Command.WriteScale].
     */
    private fun executeCommand(command: Command) {
        when (command) {
            is Command.WriteScale -> {
                // The core hands over the exact bytes as a List<UByte>.
                val bytes = command.content.data
                    .map { it.toByte() }
                    .toByteArray()
                scale.writeCommand(bytes)
            }
            is Command.WriteCharacteristic -> {
                // DE1 characteristic writes are not yet routed from here; the
                // app does not currently drive machine control. Left for a
                // follow-up so this stays a single, complete command sink.
            }
        }
    }

    private fun applyEvent(event: Event) {
        when (event) {
            is Event.MachineStateChanged -> {
                val c = event.content
                _ui.value = _ui.value.copy(
                    machineState = "${c.state.string} / ${c.substate.string}",
                )
                appendLog("MachineState -> ${c.state.string} / ${c.substate.string}")
            }
            is Event.ShotStarted -> appendLog("Shot started")
            is Event.ShotPhaseChanged -> {
                val phase = event.content.phase.string
                _ui.value = _ui.value.copy(shotPhase = phase)
                appendLog("Shot phase -> $phase")
            }
            is Event.ShotFrameChanged ->
                appendLog("Shot frame -> ${event.content.frame}")
            is Event.Telemetry -> {
                val t = event.content
                val line = "t=%dms  P=%.1fbar  flow=%.1fmL/s  head=%.1f°C".format(
                    t.elapsed_ms.toLong(), t.group_pressure, t.group_flow, t.head_temp,
                )
                _ui.value = _ui.value.copy(telemetry = line)
                // Telemetry is high-rate; do not flood the log with it.
            }
            is Event.ScaleReading -> {
                val r = event.content
                _ui.value = _ui.value.copy(
                    scaleWeightG = r.weight_g,
                    scaleFlowGPerS = r.device_flow_g_per_s,
                    scaleTimerMs = r.device_timer_ms?.toLong(),
                )
                // Weight is high-rate; do not flood the log with every reading.
            }
            is Event.WaterLevel ->
                appendLog("Water level: %.0fmm".format(event.content.level_mm))
            is Event.StopTriggered ->
                appendLog("Auto-stop: ${event.content.reason.string}")
            is Event.ShotCompleted ->
                appendLog("Shot completed: ${event.content.duration_ms}ms, " +
                    "${event.content.sample_count} samples")
            is Event.WaterSessionStarted ->
                appendLog("Water session started: ${event.content.kind.string}")
            is Event.WaterSessionCompleted ->
                appendLog("Water session completed: ${event.content.kind.string}")
            is Event.SteamSessionStarted -> appendLog("Steam session started")
            is Event.SteamSessionCompleted ->
                appendLog("Steam session completed: ${event.content.duration_ms}ms")
            is Event.SteamClogSuspected ->
                appendLog("Steam clog suspected: ${event.content.reason.string}")
            is Event.SteamEcoModeChanged ->
                appendLog("Steam eco mode: ${event.content.eco}")
            is Event.ScaleStale -> {
                _ui.value = _ui.value.copy(scaleWeightG = null)
                appendLog("Scale stale")
            }
            is Event.DecodeError ->
                appendLog("Decode error: ${event.content.message}")
        }
    }

    private fun appendLog(line: String) {
        val stamped = "%s  %s".format(
            android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()),
            line,
        )
        val log = (listOf(stamped) + _ui.value.eventLog).take(MAX_LOG_LINES)
        _ui.value = _ui.value.copy(eventLog = log)
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

        /** [BleScanner] want labels — one per device the app discovers. */
        const val SCAN_LABEL_DE1 = "DE1"
        const val SCAN_LABEL_SCALE = "Scale"
    }
}
