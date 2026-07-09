package coffee.crema.ble

import android.os.SystemClock
import android.util.Log
import coffee.crema.ble.proxy.Hex
import coffee.crema.core.CremaBridge
import coffee.crema.core.NotificationSource
import coffee.crema.core.ScaleUuids as CoreScaleUuids
import coffee.crema.core.scaleScanUuids
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * BLE manager for a coffee scale, on top of [BleTransport].
 *
 * A close sibling of [De1BleManager] — same scope/observation model.
 * Responsibilities:
 *  1. Connect to a Bookoo scale via [BleTransport], which discovers services,
 *     and tell the core which scale it is via [CremaBridge.connectScale] so the
 *     core selects the right codec.
 *  2. Observe the weight-notify characteristic (the weight-notify characteristic).
 *  3. On every weight notification, hand the raw bytes to
 *     [CremaBridge.onNotification] and forward the returned JSON `CoreOutput`
 *     string to [onCoreOutput].
 *  4. Write tare / timer commands to the command characteristic via [writeCommand].
 *  5. Also observe the command characteristic (the command characteristic, `ff12`),
 *     which the GATT dump shows has the NOTIFY property: the scale pushes its
 *     serial / settings responses back on it. Each such notification is
 *     recorded as a `SCALE_FF12` `dir:"in"` line AND fed to the core under the
 *     [NotificationSource.SCALE_COMMAND] source, so the core decodes it into an
 *     `Event.ScaleConfig`.
 *
 * Device discovery is not this manager's job: the shared [BleScanner] runs the
 * one app-wide scan and hands a matched scale to [connect]. Which advertised
 * names count as a scale is the core's call — every supported model's prefix,
 * see [supportedScaleNamePrefixes] (AND6) — not a hardcoded single-scale rule.
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

    /** The manager's coroutine scope; connection + observation jobs run here. The
     *  CoroutineExceptionHandler is a last-resort backstop: an uncaught exception
     *  in any connect/observe job — e.g. a UniFFI-rethrown Rust panic that slips
     *  past the per-notification guards — is logged, never allowed to crash the
     *  process. (The weight/command handlers below catch their own FFI calls so
     *  the stream survives; this only catches what they don't.) */
    private val scope = CoroutineScope(
        SupervisorJob() +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught in scale BLE scope", e) },
    )

    /** The connected scale, or null when not connected. */
    private var device: BleTransport.DeviceHandle? = null

    /** The connected scale's Bluetooth address (for remembering it), or null. */
    val connectedAddress: String? get() = device?.address

    /** The connected scale's advertised name, or null — what a mirror's roster
     *  advertises so a secondary's scale manager scan-matches it (issue 04). */
    val connectedName: String? get() = device?.let { advertisedName }

    /** The connected scale's weight-notify characteristic, or null until resolved —
     *  a **counted stream** the relay must NOT snapshot (it'd double-count in the
     *  mirror's core, same hazard as the DE1 ShotSample); issue 04. */
    val weightNotifyChar: UUID? get() = weightNotifyUuid

    /** The job collecting the weight-notify stream. */
    private var observeJob: Job? = null

    /**
     * Whether this manager currently holds a slot in the shared [recorder]'s
     * connection counter. Set when [recorder.open] is called and cleared by the
     * matching [recorder.close], so the two stay exactly balanced no matter
     * which path — success, connect failure, or [disconnect] — releases it.
     */
    private var recording = false

    /** Guards the [recording] ref-count across the session/caller threads. */
    private val recordingLock = Any()

    /** Parses the core's `scaleUuids()` JSON into [CoreScaleUuids]. */
    private val json = Json { ignoreUnknownKeys = true }

    /** JSON shape of the core's `scaleScanUuids()` (AND6) — the generic
     *  pre-connect scan filter. `service_uuids` is for Web Bluetooth's
     *  `optionalServices`; Android's name-scan uses only `name_prefixes`. */
    @Serializable
    private data class ScaleScanFilter(
        val service_uuids: List<String> = emptyList(),
        val name_prefixes: List<String> = emptyList(),
    )

    /**
     * Every supported scale's advertised-name prefix, from the core's generic
     * scan registry (`scaleScanUuids()`) — so discovery scans for ALL supported
     * models instead of a hardcoded Bookoo rule (AND6). The connected model's
     * codec + UUIDs are then resolved from the core in [establish]. Empty if the
     * core call fails (fail-safe: match nothing rather than guess a scale).
     */
    fun supportedScaleNamePrefixes(): List<String> =
        runCatching {
            json.decodeFromString(ScaleScanFilter.serializer(), scaleScanUuids())
                .name_prefixes
        }.getOrElse {
            Log.w(TAG, "scaleScanUuids() failed; no scales will be discovered", it)
            emptyList()
        }

    /**
     * The connected scale's GATT characteristics, sourced from the core
     * (`bridge.scaleUuids()`) once it identifies the scale — NOT hardcoded, so
     * the manager subscribes / writes to whatever scale the core recognised
     * (the web shell does the same). `null` until a scale is connected.
     */
    private var serviceUuid: UUID? = null
    private var weightNotifyUuid: UUID? = null
    private var commandUuid: UUID? = null

    /** The scale's on-scale button characteristic (Skale II `EF82`), or null
     *  for scales without one — subscribed when the core reports it and
     *  forwarded as [NotificationSource.SCALE_BUTTON]. */
    private var buttonNotifyUuid: UUID? = null

    /** The advertised name of the connected scale, kept so a reconnect can
     *  re-identify the codec via [CremaBridge.connectScale]. */
    private var advertisedName: String? = null

    /**
     * Whether to auto-reconnect after an *unexpected* link drop (M0). Kept in
     * sync with the user's "Auto-reconnect" setting by
     * [coffee.crema.ui.MainViewModel].
     */
    @Volatile
    var autoReconnectEnabled: Boolean = true

    /** Set when a user-initiated [disconnect] begins, so the reconnect [session]
     *  loop knows a drop was intentional (don't reconnect). */
    @Volatile
    private var userInitiated = false

    /** The connect-and-reconnect supervisor loop; cancelled on [disconnect]. */
    private var sessionJob: Job? = null

    // ---- Connect ----------------------------------------------------------

    /**
     * Move the manager into [State.SCANNING]. Called by the owner when it hands
     * the discovery request to the shared [BleScanner], so the connection-status
     * UI shows "scanning" while the scanner hunts.
     */
    fun markScanning() {
        _state.value = State.SCANNING
        onStatus("Scanning for a scale…")
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
        this.advertisedName = advertisedName
        userInitiated = false
        _state.value = State.CONNECTING
        onStatus("Connecting to scale…")
        // One supervisor coroutine owns connect → (drop → reconnect)*; a
        // user-initiated disconnect() cancels it.
        sessionJob?.cancel()
        sessionJob = scope.launch { session(device, advertisedName) }
    }

    /**
     * Establish the scale link, then watch for drops and auto-reconnect (M0) —
     * the scale-side twin of [De1BleManager.session]. A clean teardown ends the
     * session; an unexpected `FAILED` triggers a backoff reconnect on the same
     * handle (re-identifying the codec each time), up to the shared
     * [reconnectingSession] cap.
     */
    private suspend fun session(device: BleTransport.DeviceHandle, advertisedName: String) {
        reconnectingSession(
            label = "Scale",
            logTag = TAG,
            status = onStatus,
            isUserInitiated = { userInitiated },
            isAutoReconnectEnabled = { autoReconnectEnabled },
            establish = { firstConnect ->
                establish(device, advertisedName, firstConnect = firstConnect)
            },
            awaitDrop = {
                transport.connectionState(device).first {
                    it == BleTransport.ConnState.FAILED || it == BleTransport.ConnState.DISCONNECTED
                }
            },
            onConnected = {
                _state.value = State.READY
                onStatus("Ready — receiving scale weight")
            },
            onConnecting = { _state.value = State.CONNECTING },
            teardown = {
                // Clear the scale slice so a vanished scale leaves no stale weight
                // (the DE1 manager resets the whole core on its own teardown).
                if (recording) {
                    recording = false
                    recorder.close()
                }
                this@ScaleBleManager.device = null
                serviceUuid = null
                weightNotifyUuid = null
                commandUuid = null
                buttonNotifyUuid = null
                bridge.disconnectScale()
                _state.value = State.DISCONNECTED
            },
        )
    }

    /** One connect attempt: connect + discover + identify codec + (re)subscribe.
     *  Throws on failure so [session] can back off and retry. */
    private suspend fun establish(
        device: BleTransport.DeviceHandle,
        advertisedName: String,
        firstConnect: Boolean,
    ) {
        _state.value = State.CONNECTING
        consecutiveWriteFailures = 0 // fresh link, fresh dead-link budget
        onStatus(if (firstConnect) "Connecting to scale…" else "Reconnecting to scale…")
        // BleTransport.connect suspends until connected AND services discovered.
        transport.connect(device)
        _state.value = State.DISCOVERING
        onStatus("Scale connected — discovering services…")

        // Join the shared session recording ONCE per session (not per reconnect),
        // so the recorder open/close ref-count stays balanced across drops.
        // Synchronised (review #36): establish() runs on the manager scope
        // while disconnect() runs on the caller — an interleaving used to
        // leak the capture file handle or wedge `recording`.
        synchronized(recordingLock) {
            if (!recording) {
                recording = true
                recorder.open()?.let { onStatus("Recording session to $it") }
            }
        }

        // Identify the scale with the core so it selects the right codec. An
        // unrecognised scale can't be decoded — bail rather than subscribe to a
        // guessed characteristic. The discovered GATT services help when the
        // advertised name is ambiguous (Acaia generation) or mixed-case — a
        // distinctive service names the codec; empty falls back to the name.
        val services = runCatching { transport.discoveredServices(device) }.getOrDefault(emptyList())
        val label = runCatching { bridge.connectScale(advertisedName, services) }.getOrNull()
            ?: error("Core did not recognise scale '$advertisedName'")
        onStatus("Core recognised scale: $label")

        // Source the GATT characteristics from the CONNECTED scale: the core
        // knows them per model (`scaleUuids()`), so this manager subscribes to
        // whatever scale the core identified. (The web shell does the same.)
        val coreUuids = json.decodeFromString(
            CoreScaleUuids.serializer(),
            bridge.scaleUuids() ?: error("Core reported no UUIDs for '$label'"),
        )
        // Build the UUIDs into locals, then publish them to the fields. The
        // observe below subscribes with the LOCALS, so a concurrent disconnect()
        // nulling the fields can't NPE this in-flight establish — the old
        // `serviceUuid!!` re-reads raced disconnect()'s field clears.
        val service = UUID.fromString(coreUuids.service)
        val weight = UUID.fromString(coreUuids.weight_notify)
        val command = UUID.fromString(coreUuids.command_write)
        serviceUuid = service
        weightNotifyUuid = weight
        commandUuid = command

        // The core now knows the scale's codec; let the owner read its
        // capabilities and render capability-gated config UI.
        onScaleIdentified(advertisedName)

        _state.value = State.SUBSCRIBING
        onStatus("Subscribing to scale weight…")
        // Observe the weight-notify characteristic, plus the command
        // characteristic ONLY when the core says it also notifies — the Bookoo's
        // ff12 pushes serial / settings responses (→ SCALE_COMMAND, see
        // handleCommandNotification). For a WRITE-ONLY command char (e.g. the
        // Decent's 36f5) enabling notifications fails at the GATT layer and
        // crashes the connect, so subscribe to weight only there; writes still go
        // to commandUuid. The streams merge into one collected flow so per-device
        // notification ordering is preserved (see BleTransport.observe's contract).
        observeJob?.cancel() // a reconnect re-subscribes on the same handle
        var notifications = if (coreUuids.command_notifies && command != weight) {
            merge(
                transport.observe(device, service, weight),
                transport.observe(device, service, command),
            )
        } else {
            transport.observe(device, service, weight)
        }
        // A third stream for scales with an on-scale button characteristic
        // (Skale II EF82) — de1app subscribes to it too (bluetooth.tcl:221).
        val button = coreUuids.button_notify?.let { UUID.fromString(it) }
        buttonNotifyUuid = button
        if (button != null) {
            notifications = merge(notifications, transport.observe(device, service, button))
        }
        observeJob = notifications.onEach { handleNotification(it) }.launchIn(scope)
    }

    fun disconnect() {
        // Mark intentional BEFORE cancelling so the session loop knows not to
        // reconnect.
        userInitiated = true
        sessionJob?.cancel()
        sessionJob = null
        val d = device
        // Leave the shared recording; the capture file closes only once the
        // last device (DE1 or scale) has disconnected. Synchronised with the
        // session-side open (review #36).
        synchronized(recordingLock) {
            if (recording) {
                recording = false
                recorder.close()
            }
        }
        observeJob?.cancel()
        observeJob = null
        device = null
        serviceUuid = null
        weightNotifyUuid = null
        commandUuid = null
        // Reset the core's scale slice so a vanished scale leaves no stale
        // weight and a reconnect starts clean (AND4). The De1 manager resets
        // the whole core on its disconnect; this is the scale-only equivalent.
        bridge.disconnectScale()
        if (d != null) {
            scope.launch { transport.disconnect(d) }
        }
        _state.value = State.DISCONNECTED
        onStatus("Scale disconnected")
    }

    // ---- Command write ----------------------------------------------------

    /**
     * Write [data] to the Bookoo command characteristic (the command characteristic).
     *
     * Used for tare (and, later, timer) commands. The exact bytes come from the
     * Rust core via a `Command.WriteScale`; this manager owns no protocol.
     * Returns true if the write was dispatched and completed, false if the
     * scale is not connected or the write failed.
     *
     * This is a `suspend fun`: it awaits the transport's suspend write on the
     * caller's coroutine instead of blocking a thread, so it must never be run
     * on the BLE binder thread (a `runBlocking` there risks an ANR).
     */
    suspend fun writeCommand(data: ByteArray): Boolean {
        val d = device
        if (d == null) {
            onStatus("Cannot write scale command — scale not connected")
            return false
        }
        val service = serviceUuid
        val command = commandUuid
        if (service == null || command == null) {
            onStatus("Cannot write scale command — scale not connected")
            return false
        }
        return try {
            // Registered as preemptible (issue #15): the Nordic GATT mutex is
            // process-global, so a scale op in flight would make a DE1 shot
            // stop wait behind it — an urgent stop cancels us instead.
            UrgentWriteGate.preemptible(coroutineContext[Job], "scale command") {
                writeWithOneRetry(d, service, command, data)
            }
            // Record the write as a dir:"out" SCALE_COMMAND line so tare/timer
            // writes appear in the interleaved capture. Stamp it with the same
            // elapsedRealtime() clock the recorder and inbound lines use.
            recorder.recordOutbound("SCALE_COMMAND", data, SystemClock.elapsedRealtime())
            consecutiveWriteFailures = 0
            true
        } catch (t: TimeoutCancellationException) {
            Log.w(TAG, "Scale command write timed out", t)
            onStatus("Scale command write timed out")
            onWriteFailure(d)
            false
        } catch (c: CancellationException) {
            throw c // the caller's scope is going away — never swallow
        } catch (e: Exception) {
            Log.w(TAG, "Scale command write failed", e)
            onStatus("Scale command write was rejected")
            onWriteFailure(d)
            false
        }
    }

    /**
     * Consecutive [writeCommand] failures — the scale keep-alives (Decent 2 s /
     * Acaia 3 s / Timemore 10 s) write on a steady cadence, so a wedged link
     * trips this within seconds. On [MAX_CONSECUTIVE_WRITE_FAILURES] the link
     * is presumed dead and torn down at the TRANSPORT level (not [disconnect],
     * which is the user path and would stop the session): the state flow drops,
     * the reconnect supervisor sees a non-user drop, and its ladder takes over.
     * The same detector reaprime runs on GATT op timeouts; Decenza's
     * write-retry exhaustion feeds its `de1LinkFault` the same way.
     */
    private var consecutiveWriteFailures = 0

    private suspend fun onWriteFailure(d: BleTransport.DeviceHandle) {
        consecutiveWriteFailures++
        if (consecutiveWriteFailures < MAX_CONSECUTIVE_WRITE_FAILURES) return
        consecutiveWriteFailures = 0
        Log.w(TAG, "Scale link unresponsive ($MAX_CONSECUTIVE_WRITE_FAILURES consecutive write failures) — forcing teardown")
        onStatus("Scale link unresponsive — reconnecting…")
        runCatching { transport.disconnect(d) }
            .onFailure { Log.w(TAG, "Forced scale teardown failed", it) }
    }

    /**
     * One GATT write with a single spaced retry. de1app double-sends every
     * scale command unconditionally because "sometimes the [scale's] command
     * buffer has not finished the previous command and drops the next one"
     * (bluetooth.tcl:25), and Decenza retries writes up to 10 × 500 ms; one
     * retry covers that common transient without their full ladder — a second
     * failure falls through to the dead-link counter. Each attempt is bounded
     * (a hang counts as a failure — see [writeCommand]); retrying a scale
     * command twice is safe, the whole surface is idempotent (tare of an
     * already-zero pan, timer start when started, heartbeats…).
     */
    private suspend fun writeWithOneRetry(
        d: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
    ) {
        val first = try {
            withTimeout(WRITE_TIMEOUT_MS) { transport.write(d, service, characteristic, data) }
            return
        } catch (t: TimeoutCancellationException) {
            t
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            e
        }
        Log.i(TAG, "Scale write failed (${first.message}) — one retry in ${WRITE_RETRY_DELAY_MS}ms")
        delay(WRITE_RETRY_DELAY_MS)
        withTimeout(WRITE_TIMEOUT_MS) { transport.write(d, service, characteristic, data) }
    }

    /** External dead-link verdict (the core's stale watchdog with no recovery
     *  inside the grace window — see the VM's ScaleStale escalation): tear the
     *  link down at the transport level so the reconnect supervisor takes
     *  over. No-op when not connected. */
    suspend fun presumeDead(reason: String) {
        val d = device ?: return
        Log.w(TAG, "Scale link presumed dead ($reason) — forcing teardown")
        onStatus("Scale unresponsive — reconnecting…")
        runCatching { transport.disconnect(d) }
            .onFailure { Log.w(TAG, "Forced scale teardown failed", it) }
    }

    // ---- Core integration -------------------------------------------------

    /**
     * The FFI/BLE seam: raw GATT bytes -> [CremaBridge] -> JSON `CoreOutput`
     * -> UI.
     *
     * Routes by source characteristic:
     *  - the weight-notify characteristic (`ff11`) — the live weight stream: record
     *    it as a `SCALE_WEIGHT` `dir:"in"` line and feed it to the core.
     *  - the command characteristic (`ff12`) — see [handleCommandNotification]:
     *    record it as a `SCALE_FF12` `dir:"in"` line and feed it to the core.
     */
    private fun handleNotification(notification: BleTransport.Notification) {
        when (notification.characteristic) {
            weightNotifyUuid -> handleWeightNotification(notification)
            commandUuid -> handleCommandNotification(notification)
            buttonNotifyUuid -> handleButtonNotification(notification)
            else -> Log.w(
                TAG,
                "Notification from unmapped characteristic ${notification.characteristic}",
            )
        }
    }

    /** Button-characteristic path (Skale II `EF82`): record + drive the core,
     *  which decodes the press into an `Event.ScaleButtonPressed`. */
    private fun handleButtonNotification(notification: BleTransport.Notification) {
        val tMs = notification.atMs
        recorder.recordInbound(NotificationSource.SCALE_BUTTON, notification.data, tMs)
        feedCore(NotificationSource.SCALE_BUTTON, notification.data, tMs, "Scale button")
    }

    /** Weight-notify (`ff11`) path: record as `SCALE_WEIGHT` and drive the core. */
    private fun handleWeightNotification(notification: BleTransport.Notification) {
        // The timestamp was stamped at delivery inside the transport; the same
        // value goes to the recorder and the core, so a replayed capture
        // decodes identically to the live session.
        val tMs = notification.atMs
        recorder.recordInbound(NotificationSource.SCALE_WEIGHT, notification.data, tMs)
        feedCore(NotificationSource.SCALE_WEIGHT, notification.data, tMs, "Scale weight")
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
        Log.d(TAG, "$FF12_SRC notification: ${Hex.encode(notification.data)}")
        feedCore(NotificationSource.SCALE_COMMAND, notification.data, tMs, "Scale command")
    }

    /**
     * Hand [bytes] to the core under [source] and forward the `CoreOutput` JSON.
     * The FFI call can throw — a UniFFI-rethrown Rust panic from ANYWHERE in the
     * core (not just the length-guarded scale codec) would otherwise escape this
     * flow collector uncaught and crash the process. Catch it, log the offending
     * bytes ([what] names the stream; the Rust panic hook has already written the
     * file:line to the diagnostics log), and drop just this one notification so
     * the stream survives a single bad packet.
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

    companion object {
        private const val TAG = "ScaleBleManager"

        /** Ceiling on one GATT write before it counts as a dead-link signal —
         *  Decenza uses the same 5 s write timeout. */
        private const val WRITE_TIMEOUT_MS = 5_000L

        /** Consecutive write failures before the link is presumed dead and
         *  torn down for the reconnect supervisor (reaprime uses 3). */
        private const val MAX_CONSECUTIVE_WRITE_FAILURES = 3

        /** Spacing before the single write retry (Decenza's retry delay). */
        private const val WRITE_RETRY_DELAY_MS = 500L

        /**
         * Capture `src` label for inbound notifications from the Bookoo command
         * characteristic (`ff12`). Distinct from the `SCALE_WEIGHT` label so the
         * replay tool can route it to the core's [NotificationSource.SCALE_COMMAND]
         * source — the channel that carries the scale's serial / settings
         * responses.
         */
        private const val FF12_SRC = "SCALE_FF12"

    }
}
