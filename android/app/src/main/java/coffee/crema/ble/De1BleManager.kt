package coffee.crema.ble

import android.os.SystemClock
import android.util.Log
import coffee.crema.core.CremaBridge
import coffee.crema.core.NotificationSource
import coffee.crema.core.WriteTarget
import coffee.crema.core.de1Uuids
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * BLE manager for a DE1 espresso machine, on top of [BleTransport].
 *
 * Responsibilities — and nothing more:
 *  1. Connect to a DE1 via [BleTransport], which discovers services.
 *  2. Observe the StateInfo and ShotSample notify characteristics.
 *  3. On every notification, hand the raw bytes to [CremaBridge.onNotification]
 *     and forward the returned JSON `CoreOutput` string to [onCoreOutput].
 *
 * Device discovery is not this manager's job: the shared [BleScanner] runs the
 * one app-wide scan and hands a matched DE1 [BleTransport.DeviceHandle] to
 * [connect]. The "is this a DE1" name rule still belongs with the device — see
 * [isDe1Name].
 *
 * The hand-rolled `BluetoothGatt` callback ladder and one-outstanding-op
 * subscribe queue this class used to carry are gone: [BleTransport]
 * (Nordic-backed) serialises GATT operations and enables CCCDs itself.
 *
 * ## Lossless, ordered notifications
 *
 * StateInfo and ShotSample are [merge]d into ONE collected flow and consumed
 * on one coroutine, so a DE1's notifications are processed strictly in arrival
 * order — they are never split into independently-collected flows that could
 * reorder. [BleTransport.observe] is lossless and non-conflating, and each
 * [BleTransport.Notification] is arrival-stamped inside the transport, so the
 * Rust core sees every ShotSample with a faithful timestamp.
 *
 * Threading: notifications are collected on [scope] (a background dispatcher);
 * the bridge call and [onCoreOutput] dispatch happen there. [CremaBridge] is
 * internally `Mutex`-guarded, so concurrent calls are safe; UI consumers must
 * hop to the main thread themselves.
 */
class De1BleManager(
    private val transport: BleTransport,
    private val bridge: CremaBridge,
    /**
     * The session-wide BLE recorder, shared with [ScaleBleManager] and owned by
     * [coffee.crema.ui.MainViewModel]. A connection counter inside it opens one
     * capture file when the first device connects and closes it when the last
     * disconnects, so a DE1-and-scale session lands in ONE interleaved file.
     */
    private val recorder: BleSessionRecorder,
    /** Called with each raw JSON `CoreOutput` string the core returns. */
    private val onCoreOutput: (String) -> Unit,
    /** Called with human-readable status transitions for the UI log. */
    private val onStatus: (String) -> Unit,
) {
    enum class State { IDLE, SCANNING, CONNECTING, DISCOVERING, SUBSCRIBING, READY, DISCONNECTED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The manager's coroutine scope; connection + observation jobs run here. */
    private val scope = CoroutineScope(SupervisorJob())

    /** The connected DE1, or null when not connected. */
    private var device: BleTransport.DeviceHandle? = null

    /** The job collecting the merged StateInfo + ShotSample stream. */
    private var observeJob: Job? = null

    /**
     * Whether this manager currently holds a slot in the shared [recorder]'s
     * connection counter. Set when [recorder.open] is called and cleared by the
     * matching [recorder.close], so the two stay exactly balanced no matter
     * which path — success, connect failure, or [disconnect] — releases it.
     */
    private var recording = false

    // ---- Connect ----------------------------------------------------------

    /**
     * Move the manager into [State.SCANNING]. Called by the owner when it hands
     * the discovery request to the shared [BleScanner], so the connection-status
     * UI shows "scanning" while the scanner hunts — the scanner has no per-device
     * [State] of its own.
     */
    fun markScanning() {
        _state.value = State.SCANNING
        onStatus("Scanning for a DE1…")
    }

    /**
     * Connect to a DE1 the shared [BleScanner] has matched. This is the
     * scanner's `onFound` entry point — discovery happens upstream; this
     * manager owns the connection and the notification observation from here.
     */
    fun connect(device: BleTransport.DeviceHandle) {
        this.device = device
        _state.value = State.CONNECTING
        onStatus("Connecting…")
        scope.launch {
            try {
                // BleTransport.connect suspends until connected AND services
                // are discovered.
                transport.connect(device)
                _state.value = State.DISCOVERING
                onStatus("Connected — discovering services…")

                // Join the shared session recording; if this connection opened
                // the capture file, surface its path once so the user can find
                // it. A later scale connection appends to the same file.
                recording = true
                recorder.open()?.let { onStatus("Recording session to $it") }

                _state.value = State.SUBSCRIBING
                onStatus("Subscribing to StateInfo + ShotSample…")
                startObserving(device)

                _state.value = State.READY
                onStatus("Ready — receiving DE1 notifications")
            } catch (e: Exception) {
                Log.w(TAG, "DE1 connect failed", e)
                onStatus("Connection failed: ${e.message}")
                _state.value = State.DISCONNECTED
                // Leave the shared recording only if this connection had joined
                // it (a failure during transport.connect never opened it).
                if (recording) {
                    recording = false
                    recorder.close()
                }
            }
        }
    }

    fun disconnect() {
        val d = device
        // Leave the shared recording; the capture file closes only once the
        // last device (DE1 or scale) has disconnected.
        if (recording) {
            recording = false
            recorder.close()
        }
        observeJob?.cancel()
        observeJob = null
        device = null
        if (d != null) {
            scope.launch { transport.disconnect(d) }
        }
        bridge.reset()
        _state.value = State.DISCONNECTED
        onStatus("Disconnected")
    }

    // ---- Observation ------------------------------------------------------

    /**
     * Observe StateInfo and ShotSample as ONE merged, ordered stream.
     *
     * [merge] interleaves the two characteristic flows into a single flow
     * collected on one coroutine: a DE1's notifications are therefore consumed
     * strictly in arrival order, never split into independently-collected
     * flows that could reorder. Each [BleTransport.Notification] is already
     * arrival-stamped inside the transport.
     */
    private fun startObserving(device: BleTransport.DeviceHandle) {
        val stateInfo = transport.observe(device, De1Uuids.SERVICE, De1Uuids.STATE_INFO)
        val shotSample = transport.observe(device, De1Uuids.SERVICE, De1Uuids.SHOT_SAMPLE)
        // The core needs these too: tank level, shot-settings read-backs, and
        // MMR answers (firmware / model / GHC / calibration). All NOTIFY chars
        // on a real DE1 — the same fatal set the web connect program subscribes.
        val waterLevels = transport.observe(device, De1Uuids.SERVICE, De1Uuids.WATER_LEVELS)
        val shotSettings = transport.observe(device, De1Uuids.SERVICE, De1Uuids.SHOT_SETTINGS)
        val mmr = transport.observe(device, De1Uuids.SERVICE, De1Uuids.MMR_READ)
        // The Version (A001) and Calibration (A012) characteristics: the DE1
        // answers a read request on these via notify, so the connect-time
        // read-sweep's firmware + calibration replies fire as Firmware /
        // Calibration events. Both are NOTIFY chars on a real DE1.
        val version = transport.observe(device, De1Uuids.SERVICE, VERSION)
        val calibration = transport.observe(device, De1Uuids.SERVICE, CALIBRATION)
        observeJob = merge(stateInfo, shotSample, waterLevels, shotSettings, mmr, version, calibration)
            .onEach { handleNotification(it) }
            .launchIn(scope)
    }

    // ---- Core integration -------------------------------------------------

    /**
     * The FFI/BLE seam: raw GATT bytes -> [CremaBridge] -> JSON `CoreOutput`
     * -> UI.
     */
    private fun handleNotification(notification: BleTransport.Notification) {
        val source = when (notification.characteristic) {
            De1Uuids.STATE_INFO -> NotificationSource.DE1_STATE
            De1Uuids.SHOT_SAMPLE -> NotificationSource.DE1_SHOT_SAMPLE
            De1Uuids.WATER_LEVELS -> NotificationSource.DE1_WATER_LEVELS
            De1Uuids.SHOT_SETTINGS -> NotificationSource.DE1_SHOT_SETTINGS
            De1Uuids.MMR_READ -> NotificationSource.DE1_MMR_READ
            VERSION -> NotificationSource.DE1_VERSION
            CALIBRATION -> NotificationSource.DE1_CALIBRATION
            else -> {
                Log.w(TAG, "Notification from unmapped characteristic ${notification.characteristic}")
                return
            }
        }
        // The timestamp was stamped at delivery inside the transport; the same
        // value goes to the recorder and the core, so a replayed capture
        // decodes identically to the live session.
        val tMs = notification.atMs
        recorder.recordInbound(source, notification.data, tMs)
        // CremaBridge.onNotification returns the CoreOutput as a JSON string.
        val json = bridge.onNotification(source, notification.data, tMs.toULong())
        onCoreOutput(json)
    }

    // ---- Machine control (AND5) -------------------------------------------

    /**
     * Write a core-issued `Command.WriteCharacteristic` to the DE1: map the
     * [target] to its GATT characteristic and write [bytes].
     *
     * The DE1 emits NO notification on a `FrameWrite` (it only ACKs the ATT
     * write), so after a [WriteTarget.De1ProfileFrame] write we synthesize a
     * `De1FrameAck` by feeding the just-written bytes back into the core — that
     * is what advances the core's profile-upload state machine. Mirrors the web
     * shell's `De1FrameAck` synthesis.
     */
    suspend fun writeCharacteristic(target: WriteTarget, bytes: ByteArray) {
        val d = device ?: run {
            onStatus("Write ignored — DE1 not connected")
            return
        }
        transport.write(d, De1Uuids.SERVICE, De1Uuids.forWriteTarget(target), bytes)
        if (target == WriteTarget.De1ProfileFrame) {
            val tMs = SystemClock.elapsedRealtime()
            val json = bridge.onNotification(NotificationSource.DE1_FRAME_ACK, bytes, tMs.toULong())
            onCoreOutput(json)
        }
    }

    companion object {
        private const val TAG = "De1BleManager"

        /**
         * The DE1 `Version` (`A001`) and `Calibration` (`A012`) characteristic
         * UUIDs, sourced from the core's single DE1 UUID map (`de1Uuids()`) —
         * the same source the shell's [De1Uuids] accessor parses. Resolved once
         * here because [De1Uuids] does not surface these two accessors; this
         * keeps every DE1 UUID literal in the core (no cross-shell drift) while
         * letting this manager subscribe to the firmware-version and
         * sensor-calibration notify characteristics.
         *
         * `DE1_VERSION` carries the BLE + firmware versions (→ `Event.Firmware`);
         * `DE1_CALIBRATION` carries sensor-calibration replies
         * (→ `Event.Calibration`). Both are read-request + notify on a real DE1.
         */
        private val coreUuids: coffee.crema.core.De1Uuids by lazy {
            Json.decodeFromString(
                coffee.crema.core.De1Uuids.serializer(),
                de1Uuids(),
            )
        }
        private val VERSION: UUID by lazy { UUID.fromString(coreUuids.version) }
        private val CALIBRATION: UUID by lazy { UUID.fromString(coreUuids.calibration) }

        /**
         * Whether a scanned advertisement name identifies a DE1. The DE1
         * advertises a name starting "DE1"; some units advertise "BENGLE". This
         * mirrors the legacy de1app discovery rule in `bluetooth.tcl`
         * (`de1_ble_handler`). The shared [BleScanner] stays device-agnostic, so
         * this — the "is this a DE1" rule — lives with the DE1 manager.
         */
        fun isDe1Name(name: String): Boolean {
            val upper = name.uppercase()
            return upper.startsWith(De1Uuids.ADVERTISED_NAME_PREFIX) ||
                upper.startsWith("BENGLE")
        }
    }
}
