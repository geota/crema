package coffee.crema.ble

import android.os.SystemClock
import android.util.Log
import coffee.crema.core.CremaBridge
import coffee.crema.core.NotificationSource
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
import kotlinx.coroutines.runBlocking

/**
 * BLE manager for a Bookoo coffee scale, on top of [BleTransport].
 *
 * A close sibling of [De1BleManager] — same scope/observation model.
 * Responsibilities:
 *  1. Connect to a Bookoo scale via [BleTransport], which discovers services,
 *     and tell the core which scale it is via [CremaBridge.connectScale] so the
 *     core selects the right codec.
 *  2. Observe the weight-notify characteristic ([ScaleUuids.WEIGHT_NOTIFY]).
 *  3. On every weight notification, hand the raw bytes to
 *     [CremaBridge.onNotification] and forward the returned JSON `CoreOutput`
 *     string to [onCoreOutput].
 *  4. Write tare / timer commands to [ScaleUuids.COMMAND] via [writeCommand].
 *  5. Also observe the command characteristic ([ScaleUuids.COMMAND], `ff12`),
 *     which the GATT dump shows has the NOTIFY property: the scale pushes its
 *     serial / settings responses back on it. Each such notification is
 *     recorded as a `SCALE_FF12` `dir:"in"` line AND fed to the core under the
 *     [NotificationSource.SCALE_COMMAND] source, so the core decodes it into an
 *     `Event.ScaleConfig`.
 *
 * Device discovery is not this manager's job: the shared [BleScanner] runs the
 * one app-wide scan and hands a matched scale to [connect]. The "is this a
 * Bookoo" name rule lives with the device — see [isBookooName].
 *
 * The hand-rolled `BluetoothGatt` callback ladder and subscribe queue are gone:
 * [BleTransport] (Nordic-backed) serialises GATT operations itself.
 *
 * Like [De1BleManager], this manager records its BLE traffic into the shared
 * session [BleSessionRecorder]: each weight notification becomes a `dir:"in"`
 * `SCALE_WEIGHT` line, each `ff12` command-characteristic notification a
 * `dir:"in"` `SCALE_FF12` line, and each tare/timer write a `dir:"out"`
 * `SCALE_COMMAND` line. The recorder is owned by [coffee.crema.ui.MainViewModel] and shared
 * with the DE1 manager, so a DE1-and-scale session lands in ONE interleaved
 * capture file that replays through a single `CremaCore`.
 *
 * The scale connection is independent of the DE1: a user can connect a scale
 * with or without a DE1. Two simultaneous connections are fine, and
 * [CremaBridge] is internally `Mutex`-guarded, so both managers feeding it
 * concurrently is safe.
 *
 * Threading: weight notifications are collected on [scope] (a background
 * dispatcher); the bridge call and [onCoreOutput] dispatch happen there. UI
 * consumers must hop to the main thread themselves.
 */
class ScaleBleManager(
    private val transport: BleTransport,
    private val bridge: CremaBridge,
    /**
     * The session-wide BLE recorder, shared with [De1BleManager] and owned by
     * [coffee.crema.ui.MainViewModel]. A connection counter inside it opens one
     * capture file when the first device connects and closes it when the last
     * disconnects, so a DE1-and-scale session lands in ONE interleaved file.
     */
    private val recorder: BleSessionRecorder,
    /** Called with each raw JSON `CoreOutput` string the core returns. */
    private val onCoreOutput: (String) -> Unit,
    /** Called with human-readable status transitions for the UI log. */
    private val onStatus: (String) -> Unit,
    /**
     * Called once the core has identified the connected scale, so the owner
     * can read the scale's [CremaBridge.scaleCapabilities] and drive
     * capability-gated UI. Not called when the core did not recognise the
     * scale. Runs on [scope] (a background dispatcher).
     *
     * The argument is the scale's BLE advertised name (e.g.
     * `"BOOKOO_SC 502158"`) — the one the shared [BleScanner] resolved and
     * this manager passed to [CremaBridge.connectScale]. The owner surfaces it
     * in the scale card; it is the device's identity before the core's `0x0a`
     * connect query returns the firmware / serial.
     */
    private val onScaleIdentified: (advertisedName: String) -> Unit = {},
) {
    enum class State { IDLE, SCANNING, CONNECTING, DISCOVERING, SUBSCRIBING, READY, DISCONNECTED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The manager's coroutine scope; connection + observation jobs run here. */
    private val scope = CoroutineScope(SupervisorJob())

    /** The connected scale, or null when not connected. */
    private var device: BleTransport.DeviceHandle? = null

    /** The job collecting the weight-notify stream. */
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
     * UI shows "scanning" while the scanner hunts.
     */
    fun markScanning() {
        _state.value = State.SCANNING
        onStatus("Scanning for a Bookoo scale…")
    }

    /**
     * Connect to a Bookoo scale the shared [BleScanner] has matched. This is the
     * scanner's `onFound` entry point — discovery happens upstream; this
     * manager owns the connection from here. [advertisedName] is the name the
     * scanner resolved from the advertisement; it is passed to
     * [CremaBridge.connectScale] to pick the codec.
     */
    fun connect(device: BleTransport.DeviceHandle, advertisedName: String) {
        this.device = device
        _state.value = State.CONNECTING
        onStatus("Connecting to scale…")
        scope.launch {
            try {
                // BleTransport.connect suspends until connected AND services
                // are discovered.
                transport.connect(device)
                _state.value = State.DISCOVERING
                onStatus("Scale connected — discovering services…")

                // Join the shared session recording; if this connection opened
                // the capture file, surface its path once so the user can find
                // it. A concurrent DE1 connection appends to the same file.
                recording = true
                recorder.open()?.let { onStatus("Recording session to $it") }

                // Identify the scale with the core so it selects the right codec.
                val label = runCatching { bridge.connectScale(advertisedName) }.getOrNull()
                if (label != null) {
                    onStatus("Core recognised scale: $label")
                    // The core now knows the scale's codec; let the owner read
                    // its capabilities and render capability-gated config UI.
                    // The advertised name goes along so the owner can show the
                    // scale's identity in the card right away.
                    onScaleIdentified(advertisedName)
                } else {
                    onStatus("Core did not recognise scale '$advertisedName'")
                }

                _state.value = State.SUBSCRIBING
                onStatus("Subscribing to scale weight…")
                // Observe BOTH Bookoo characteristics on the 0ffe service:
                //  - WEIGHT_NOTIFY (ff11) — the live weight stream the core
                //    decodes; this is the real product path.
                //  - COMMAND (ff12) — the command channel. The app WRITES
                //    tare/timer/query commands to it, and the GATT dump shows
                //    ff12 also has the NOTIFY property: the scale pushes its
                //    serial / settings responses back on it. Those inbound
                //    notifications are recorded AND fed to the core under the
                //    SCALE_COMMAND source — see handleCommandNotification.
                // The two streams are merged into one collected flow so
                // per-device notification ordering is preserved — see
                // BleTransport.observe's contract.
                observeJob = merge(
                    transport.observe(
                        device,
                        ScaleUuids.SERVICE,
                        ScaleUuids.WEIGHT_NOTIFY,
                    ),
                    transport.observe(
                        device,
                        ScaleUuids.SERVICE,
                        ScaleUuids.COMMAND,
                    ),
                )
                    .onEach { handleNotification(it) }
                    .launchIn(scope)

                _state.value = State.READY
                onStatus("Ready — receiving scale weight")
            } catch (e: Exception) {
                Log.w(TAG, "Scale connect failed", e)
                onStatus("Scale connection failed: ${e.message}")
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
        _state.value = State.DISCONNECTED
        onStatus("Scale disconnected")
    }

    // ---- Command write ----------------------------------------------------

    /**
     * Write [data] to the Bookoo command characteristic ([ScaleUuids.COMMAND]).
     *
     * Used for tare (and, later, timer) commands. The exact bytes come from the
     * Rust core via a `Command.WriteScale`; this manager owns no protocol.
     * Returns true if the write was dispatched and completed, false if the
     * scale is not connected or the write failed.
     *
     * The call is synchronous to the caller (it blocks the calling thread on
     * the underlying suspend write) so the existing `Command.WriteScale`
     * routing in `MainViewModel` — which is not a coroutine — keeps its
     * boolean-returning shape unchanged.
     */
    fun writeCommand(data: ByteArray): Boolean {
        val d = device
        if (d == null) {
            onStatus("Cannot write scale command — scale not connected")
            return false
        }
        return runCatching {
            runBlocking {
                transport.write(d, ScaleUuids.SERVICE, ScaleUuids.COMMAND, data)
            }
            // Record the write as a dir:"out" SCALE_COMMAND line so tare/timer
            // writes appear in the interleaved capture. Stamp it with the same
            // elapsedRealtime() clock the recorder and inbound lines use.
            recorder.recordOutbound("SCALE_COMMAND", data, SystemClock.elapsedRealtime())
            true
        }.getOrElse {
            Log.w(TAG, "Scale command write failed", it)
            onStatus("Scale command write was rejected")
            false
        }
    }

    // ---- Core integration -------------------------------------------------

    /**
     * The FFI/BLE seam: raw GATT bytes -> [CremaBridge] -> JSON `CoreOutput`
     * -> UI.
     *
     * Routes by source characteristic:
     *  - [ScaleUuids.WEIGHT_NOTIFY] (`ff11`) — the live weight stream: record
     *    it as a `SCALE_WEIGHT` `dir:"in"` line and feed it to the core.
     *  - [ScaleUuids.COMMAND] (`ff12`) — see [handleCommandNotification]:
     *    record it as a `SCALE_FF12` `dir:"in"` line and feed it to the core.
     */
    private fun handleNotification(notification: BleTransport.Notification) {
        when (notification.characteristic) {
            ScaleUuids.WEIGHT_NOTIFY -> handleWeightNotification(notification)
            ScaleUuids.COMMAND -> handleCommandNotification(notification)
            else -> Log.w(
                TAG,
                "Notification from unmapped characteristic ${notification.characteristic}",
            )
        }
    }

    /** Weight-notify (`ff11`) path: record as `SCALE_WEIGHT` and drive the core. */
    private fun handleWeightNotification(notification: BleTransport.Notification) {
        // The timestamp was stamped at delivery inside the transport; the same
        // value goes to the recorder and the core, so a replayed capture
        // decodes identically to the live session.
        val tMs = notification.atMs
        recorder.recordInbound(NotificationSource.SCALE_WEIGHT, notification.data, tMs)
        // CremaBridge.onNotification returns the CoreOutput as a JSON string.
        val json = bridge.onNotification(
            NotificationSource.SCALE_WEIGHT,
            notification.data,
            tMs.toULong(),
        )
        onCoreOutput(json)
    }

    /**
     * Command-characteristic (`ff12`) path: record AND drive the core.
     *
     * The Bookoo `ff12` characteristic also has the NOTIFY property, so the
     * scale pushes its serial / settings responses back on the channel the app
     * otherwise only writes commands to (a `03 0c` serial frame carrying the
     * live anti-mistouch state, a `03 0e` settings frame carrying the active
     * display mode). Each notification is recorded to the capture file as a
     * `dir:"in"` line with the distinct `src:"SCALE_FF12"` label, then — exactly
     * like the [handleWeightNotification] path — handed to
     * [CremaBridge.onNotification] under the [NotificationSource.SCALE_COMMAND]
     * source so the core decodes it into an `Event.ScaleConfig`. The same
     * delivery timestamp goes to the recorder and the core, so a replayed
     * capture decodes identically to the live session.
     */
    private fun handleCommandNotification(notification: BleTransport.Notification) {
        val tMs = notification.atMs
        recorder.recordInbound(FF12_SRC, notification.data, tMs)
        Log.d(TAG, "$FF12_SRC notification: ${notification.data.toHex()}")
        // CremaBridge.onNotification returns the CoreOutput as a JSON string.
        val json = bridge.onNotification(
            NotificationSource.SCALE_COMMAND,
            notification.data,
            tMs.toULong(),
        )
        onCoreOutput(json)
    }

    companion object {
        private const val TAG = "ScaleBleManager"

        /**
         * Capture `src` label for inbound notifications from the Bookoo command
         * characteristic (`ff12`). Distinct from the `SCALE_WEIGHT` label so the
         * replay tool can route it to the core's [NotificationSource.SCALE_COMMAND]
         * source — the channel that carries the scale's serial / settings
         * responses.
         */
        private const val FF12_SRC = "SCALE_FF12"

        /** Lowercase hex with no separators — for Logcat dumps of raw payloads. */
        private val HEX = "0123456789abcdef".toCharArray()

        private fun ByteArray.toHex(): String {
            val sb = StringBuilder(size * 2)
            for (b in this) {
                sb.append(HEX[(b.toInt() ushr 4) and 0xF])
                sb.append(HEX[b.toInt() and 0xF])
            }
            return sb.toString()
        }

        /**
         * Whether a scanned advertisement name identifies a Bookoo scale. The
         * scale advertises a name starting "BOOKOO_SC"; the core then identifies
         * the exact model from the full advertised name. The shared [BleScanner]
         * stays device-agnostic, so this rule lives with the scale manager.
         */
        fun isBookooName(name: String): Boolean =
            name.uppercase().startsWith(ScaleUuids.BOOKOO_NAME_PREFIX)
    }
}
