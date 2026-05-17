package coffee.crema.spikeb

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.spikeb.ble.De1BleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** A flat snapshot of everything Spike B's UI shows. */
data class SpikeUiState(
    val bleState: De1BleManager.State = De1BleManager.State.IDLE,
    /** Most recent status line (scan / connect / error transitions). */
    val status: String = "Idle",
    /** Latest decoded machine state + substate, or null before the first one. */
    val machineState: String? = null,
    /** Latest shot phase. */
    val shotPhase: String? = null,
    /** Latest telemetry sample, pre-formatted. */
    val telemetry: String? = null,
    /** A rolling event log, newest first. */
    val eventLog: List<String> = emptyList(),
)

/**
 * Owns the [CremaBridge] and the [De1BleManager], parses the JSON `CoreOutput`
 * the bridge returns, and projects decoded [Event]s into [SpikeUiState].
 *
 * This is the spike's "everything else" — the real app will split connection,
 * core ownership, and per-screen state properly. Here it is one class so the
 * end-to-end path is readable top to bottom.
 */
class SpikeViewModel(app: Application) : AndroidViewModel(app) {

    private val json = Json { ignoreUnknownKeys = true }

    /** The Rust core, behind the UniFFI bridge. */
    private val bridge: CremaBridge = CremaBridge()

    private val _ui = MutableStateFlow(SpikeUiState())
    val ui: StateFlow<SpikeUiState> = _ui.asStateFlow()

    private val ble = De1BleManager(
        context = app,
        bridge = bridge,
        onCoreOutput = ::onCoreOutputJson,
        onStatus = { line -> _ui.value = _ui.value.copy(status = line) },
    )

    init {
        // Mirror the BLE manager's coarse state into the UI snapshot.
        // (Collected without a scope helper to keep the spike dependency-light;
        //  StateFlow is cold-safe to read once here for the initial value.)
    }

    /** Called from the UI once the BLE runtime permissions have been granted. */
    fun connect() {
        _ui.value = _ui.value.copy(eventLog = emptyList())
        ble.startScan()
        // Reflect the manager's state as it advances.
        observeBleState()
    }

    fun disconnect() {
        ble.disconnect()
        _ui.value = _ui.value.copy(
            bleState = De1BleManager.State.DISCONNECTED,
            machineState = null,
            shotPhase = null,
            telemetry = null,
        )
    }

    private fun observeBleState() {
        // The manager exposes a StateFlow; the cheapest correct hookup in a
        // spike is to poll its current value whenever a status line lands.
        // For simplicity the value is also folded in here on each call.
        _ui.value = _ui.value.copy(bleState = ble.state.value)
    }

    /**
     * The heart of Spike B: a JSON `CoreOutput` string came back from
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
            is Event.ScaleReading ->
                appendLog("Scale: %.1fg @ %.2fg/s".format(
                    event.content.weight_g, event.content.flow_g_per_s))
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
            is Event.ScaleStale -> appendLog("Scale stale")
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
        ble.disconnect()
    }

    private companion object {
        const val MAX_LOG_LINES = 200
    }
}
