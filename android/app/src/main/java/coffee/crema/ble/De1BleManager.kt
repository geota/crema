package coffee.crema.ble

import android.os.SystemClock
import android.util.Log
import coffee.crema.core.CremaBridge
import coffee.crema.ble.proxy.Hex
import coffee.crema.core.NotificationSource
import coffee.crema.core.WriteTarget
import coffee.crema.core.de1Uuids
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    /** The manager's coroutine scope; connection + observation jobs run here. The
     *  CoroutineExceptionHandler is a last-resort backstop: an uncaught exception
     *  in any connect/observe job — e.g. a UniFFI-rethrown Rust panic that slips
     *  past the per-notification guards — is logged, never allowed to crash the
     *  process. (handleNotification catches its own FFI call so the stream
     *  survives; this only catches what it doesn't.) */
    private val scope = CoroutineScope(
        SupervisorJob() +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught in DE1 BLE scope", e) },
    )

    /** The connected DE1, or null when not connected. */
    private var device: BleTransport.DeviceHandle? = null

    /** The connected DE1's Bluetooth address (for the Settings "BLE" row), or null. */
    val connectedAddress: String? get() = device?.address

    /** The job collecting the merged StateInfo + ShotSample stream. */
    private var observeJob: Job? = null

    /**
     * Whether this manager currently holds a slot in the shared [recorder]'s
     * connection counter. Set when [recorder.open] is called and cleared by the
     * matching [recorder.close], so the two stay exactly balanced no matter
     * which path — success, connect failure, or [disconnect] — releases it.
     */
    private var recording = false

    /**
     * Whether to auto-reconnect after an *unexpected* link drop (issue: M0).
     * Owned by the user's "Auto-reconnect" setting; [coffee.crema.ui.MainViewModel]
     * keeps it in sync with [coffee.crema.settings.AppPrefs.autoReconnect].
     */
    @Volatile
    var autoReconnectEnabled: Boolean = true

    /**
     * Set the instant a user-initiated [disconnect] begins, so the reconnect
     * [session] loop knows a drop was intentional (don't reconnect) vs. a link
     * loss (do). Reset to false at the start of each [connect].
     */
    @Volatile
    private var userInitiated = false

    /** The connect-and-reconnect supervisor loop; cancelled on [disconnect]. */
    private var sessionJob: Job? = null

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
        userInitiated = false
        _state.value = State.CONNECTING
        onStatus("Connecting…")
        // One supervisor coroutine owns the whole connect → (drop → reconnect)*
        // lifecycle; a user-initiated disconnect() cancels it.
        sessionJob?.cancel()
        sessionJob = scope.launch { session(device) }
    }

    /**
     * Establish the link, then watch for drops and auto-reconnect (M0).
     *
     * After a successful [establish] the loop suspends on the transport's
     * [BleTransport.connectionState] until the link is gone. A clean teardown
     * (`DISCONNECTED` — e.g. the user tapped Disconnect) ends the session; an
     * unexpected loss (`FAILED`) triggers a backoff reconnect on the SAME
     * peripheral handle (it survives in the transport), up to the shared
     * [reconnectingSession] cap. The DE1 keeps running its profile autonomously
     * during the gap; on reconnect the seed reads re-sync machine state.
     */
    private suspend fun session(device: BleTransport.DeviceHandle) {
        reconnectingSession(
            label = "DE1",
            logTag = TAG,
            status = onStatus,
            isUserInitiated = { userInitiated },
            isAutoReconnectEnabled = { autoReconnectEnabled },
            establish = { firstConnect -> establish(device, firstConnect = firstConnect) },
            awaitDrop = {
                transport.connectionState(device).first {
                    it == BleTransport.ConnState.FAILED || it == BleTransport.ConnState.DISCONNECTED
                }
            },
            onConnected = {
                _state.value = State.READY
                onStatus("Ready — receiving DE1 notifications")
                // Connect-time one-shot seed reads (firmware / machine-state /
                // shot-settings): the DE1 answers them on a direct GATT read but
                // does NOT notify them at connect, so subscribing alone leaves
                // those rows blank. Re-seeded on every (re)connect so a reconnect
                // re-syncs state. Best-effort, off the loop.
                scope.launch { seedReads(device) }
            },
            onConnecting = { _state.value = State.CONNECTING },
            teardown = {
                if (recording) {
                    recording = false
                    recorder.close()
                }
                this@De1BleManager.device = null
                _state.value = State.DISCONNECTED
            },
        )
    }

    /**
     * Bring the link up for one attempt: connect + discover, join the shared
     * recording once per session, then (re)subscribe. Throws on failure so the
     * [session] loop can back off and retry. [firstConnect] distinguishes the
     * initial attempt (open the recorder, "Connecting…") from a reconnect
     * ("Reconnecting…") so the recorder ref-count stays balanced across drops.
     */
    private suspend fun establish(device: BleTransport.DeviceHandle, firstConnect: Boolean) {
        _state.value = State.CONNECTING
        consecutiveWriteFailures = 0 // fresh link, fresh dead-link budget
        onStatus(if (firstConnect) "Connecting…" else "Reconnecting…")
        // BleTransport.connect suspends until connected AND services discovered.
        transport.connect(device)
        _state.value = State.DISCOVERING
        onStatus("Connected — discovering services…")
        // Join the shared session recording ONCE per session (not per reconnect),
        // so the recorder's open/close ref-count stays balanced across drops.
        if (!recording) {
            recording = true
            recorder.open()?.let { onStatus("Recording session to $it") }
        }
        _state.value = State.SUBSCRIBING
        onStatus("Subscribing to StateInfo + ShotSample…")
        startObserving(device)
    }

    fun disconnect() {
        // Mark intentional BEFORE cancelling so the session loop's finally and
        // any in-flight connectionState wait know not to reconnect.
        userInitiated = true
        sessionJob?.cancel()
        sessionJob = null
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
        // Drop any prior subscription job (a reconnect re-subscribes on the same
        // handle); the old Nordic subscribe flows complete on the link drop, but
        // cancelling here keeps exactly one merged collector alive.
        observeJob?.cancel()
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

    /**
     * Connect-time one-shot reads of the characteristics the DE1 answers on a
     * direct GATT read but does NOT spontaneously notify at connect: Version
     * (firmware string → `de1Firmware`), StateInfo (current machine state), and
     * ShotSettings. The web connect program seeds these the same way; without
     * them the Settings firmware row and machine-state readout stay blank even
     * though the connection is healthy and the MMR identity sweep populated.
     *
     * Each read is best-effort + time-boxed: a failure (or a notify-only char on
     * some firmware) just logs and the others still run. The read bytes are fed
     * to the core through the same seam as a notification, so they decode and
     * record identically.
     */
    private suspend fun seedReads(device: BleTransport.DeviceHandle) {
        val seeds = listOf(
            Triple(VERSION, NotificationSource.DE1_VERSION, "firmware version"),
            Triple(De1Uuids.STATE_INFO, NotificationSource.DE1_STATE, "machine state"),
            Triple(De1Uuids.SHOT_SETTINGS, NotificationSource.DE1_SHOT_SETTINGS, "shot-settings"),
        )
        for ((char, source, label) in seeds) {
            runCatching {
                val bytes = kotlinx.coroutines.withTimeoutOrNull(3_000) {
                    transport.read(device, De1Uuids.SERVICE, char)
                } ?: error("read timed out")
                val tMs = SystemClock.elapsedRealtime()
                recorder.recordInbound(source, bytes, tMs)
                val json = bridge.onNotification(source, bytes, tMs.toULong())
                onCoreOutput(json)
                onStatus("DE1 $label read ✓")
            }.onFailure { onStatus("DE1 $label seed read skipped: ${it.message}") }
        }
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
        feedCore(source, notification.data, tMs, "DE1 ${source.name}")
    }

    /**
     * Hand [bytes] to the core under [source] and forward the `CoreOutput` JSON.
     * The FFI call can throw — a UniFFI-rethrown Rust panic from anywhere in the
     * core would otherwise escape this flow collector uncaught and crash the
     * process. Catch it, log the offending bytes ([what] names the stream; the
     * Rust panic hook has already written file:line to the diagnostics log), and
     * drop just this one notification so the stream survives a single bad packet.
     */
    private fun feedCore(source: NotificationSource, bytes: ByteArray, tMs: Long, what: String) {
        // Guard decode AND the onCoreOutput forward together: a failure in either
        // skips only THIS notification — feedCore returns normally, so the onEach
        // collector continues with the NEXT packet. It is not a permanent stop.
        runCatching {
            onCoreOutput(bridge.onNotification(source, bytes, tMs.toULong()))
        }.onFailure {
            Log.e(TAG, "$what failed — skipping this notification (${Hex.encode(bytes)})", it)
            onStatus("$what error — skipped (see diagnostics)")
        }
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
    suspend fun writeCharacteristic(
        target: WriteTarget,
        bytes: ByteArray,
        /** The stop-write preemption lane (issue #15): an urgent write first
         *  cancels every registered non-critical in-flight write — Nordic's
         *  GATT mutex is process-GLOBAL, so a wedged poke or a scale op
         *  would otherwise make the shot-stop wait — and itself skips
         *  registration so stops never preempt stops. */
        urgent: Boolean = false,
    ) {
        val d = device ?: run {
            onStatus("Write ignored — DE1 not connected")
            return
        }
        try {
            if (urgent) {
                UrgentWriteGate.preempt("an urgent DE1 $target write")
                writeWithOneRetry(d, De1Uuids.forWriteTarget(target), bytes)
            } else {
                UrgentWriteGate.preemptible(coroutineContext[Job], "DE1 $target write") {
                    writeWithOneRetry(d, De1Uuids.forWriteTarget(target), bytes)
                }
            }
            consecutiveWriteFailures = 0
        } catch (t: TimeoutCancellationException) {
            onWriteFailure(d)
            throw t // callers log per-write context (executeCommand's guard)
        } catch (c: CancellationException) {
            throw c // the caller's scope is going away — never swallow
        } catch (e: Exception) {
            onWriteFailure(d)
            throw e
        }
        if (target == WriteTarget.De1ProfileFrame) {
            feedCore(NotificationSource.DE1_FRAME_ACK, bytes, SystemClock.elapsedRealtime(), "DE1 frame-ACK")
        }
    }

    /**
     * Best-effort connection-priority hint for the DE1 link — HIGH during a
     * shot (tighter connection interval → steadier ShotSample cadence and a
     * lower-latency stop-at-weight write), BALANCED otherwise. Decenza runs
     * its DE1 link at HIGH for the same reason; the shot-window gating (and
     * leaving the scale at BALANCED) avoids the dual-HIGH radio starvation it
     * had to add a backoff latch for (#1093/#1176). No-op when disconnected;
     * a refused request is logged by the transport and changes nothing.
     */
    suspend fun setConnectionPriority(high: Boolean) {
        val d = device ?: return
        transport.requestConnectionPriority(d, high)
    }

    /**
     * Consecutive [writeCharacteristic] failures — the keep-awake poke (60 s)
     * and any user-action write feed this; on [MAX_CONSECUTIVE_WRITE_FAILURES]
     * the link is presumed dead and torn down at the TRANSPORT level (not
     * [disconnect], the user path): the state flow drops, the reconnect
     * supervisor sees a non-user drop, and its ladder takes over. Mirrors
     * Decenza's write-retry-exhaustion → `de1LinkFault` and reaprime's
     * consecutive-op-timeout teardown.
     */
    private var consecutiveWriteFailures = 0

    private suspend fun onWriteFailure(d: BleTransport.DeviceHandle) {
        consecutiveWriteFailures++
        if (consecutiveWriteFailures < MAX_CONSECUTIVE_WRITE_FAILURES) return
        consecutiveWriteFailures = 0
        Log.w(TAG, "DE1 link unresponsive ($MAX_CONSECUTIVE_WRITE_FAILURES consecutive write failures) — forcing teardown")
        onStatus("DE1 link unresponsive — reconnecting…")
        runCatching { transport.disconnect(d) }
            .onFailure { Log.w(TAG, "Forced DE1 teardown failed", it) }
    }

    /**
     * One GATT write with a single spaced retry, each attempt bounded — a hang
     * counts as a failure (a dead link doesn't always deliver a disconnect
     * event; reaprime observed writes hanging forever after a DE1 power
     * outage). de1app double-sends DE1 command writes and Decenza retries up
     * to 10 × 500 ms; one retry covers the common transient without the full
     * ladder — a second failure surfaces to the caller AND the dead-link
     * counter. Safe to repeat: state requests, MMR writes, and profile-frame
     * writes are all idempotent (same bytes to the same target).
     */
    private suspend fun writeWithOneRetry(
        d: BleTransport.DeviceHandle,
        characteristic: UUID,
        bytes: ByteArray,
    ) {
        val first = try {
            withTimeout(WRITE_TIMEOUT_MS) { transport.write(d, De1Uuids.SERVICE, characteristic, bytes) }
            return
        } catch (t: TimeoutCancellationException) {
            t
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            e
        }
        Log.i(TAG, "DE1 write failed (${first.message}) — one retry in ${WRITE_RETRY_DELAY_MS}ms")
        delay(WRITE_RETRY_DELAY_MS)
        withTimeout(WRITE_TIMEOUT_MS) { transport.write(d, De1Uuids.SERVICE, characteristic, bytes) }
    }

    companion object {
        private const val TAG = "De1BleManager"

        /** Ceiling on one GATT write before it counts as a dead-link signal —
         *  Decenza uses the same 5 s write timeout. */
        private const val WRITE_TIMEOUT_MS = 5_000L

        /** Consecutive write failures before the link is presumed dead and
         *  torn down for the reconnect supervisor (reaprime uses 3). */
        private const val MAX_CONSECUTIVE_WRITE_FAILURES = 3

        /** Spacing before the single write retry (Decenza's retry delay). */
        private const val WRITE_RETRY_DELAY_MS = 500L

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
