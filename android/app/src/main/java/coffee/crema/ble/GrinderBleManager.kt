package coffee.crema.ble

import android.os.SystemClock
import android.util.Log
import coffee.crema.core.CremaBridge
import coffee.crema.core.GrinderUuids
import coffee.crema.core.grinderShownName
import coffee.crema.core.NotificationSource
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * BLE manager for a connected grinder (SPEC grinder-ble §2.6), on top of
 * [BleTransport] — the scale manager's sibling, with the same session,
 * reconnect and flash-hold model. The protocol is the core's
 * (`de1-grinder`): this manager only moves bytes.
 *
 * One connect:
 *  1. connect and discover; the core identifies the grinder from its name
 *     and services ([CremaBridge.connectGrinder]) and names its UUIDs; a
 *     device without the grinder's service is refused ("doesn't look like…");
 *  2. ask for a large MTU, best effort, as decaid does on Android — the
 *     grinder then sends bigger fragments; writes stay 20-byte packets, as
 *     both prior Motto drivers write them;
 *  3. subscribe to the notify characteristic, awaiting the CCCD write;
 *  4. [CremaBridge.grinderSubscribed] returns the handshake as
 *     `Command.WriteGrinder`s, which go out in order through the core lane
 *     ([writeCommand]);
 *  5. every notification goes to the core as [NotificationSource.GRINDER_NOTIFY].
 *     The core's tick runs the first-reading and silence deadlines and says
 *     `GrinderLinkDead`; the owner answers with [presumeDead], and the
 *     reconnect supervisor builds a new link (the core a new driver, so the
 *     handshake is sent again).
 *
 * Nothing here records grinder bytes: the grinder's `baseInfo` carries its
 * serial and the user's Wi-Fi name, so [BleSessionRecorder] (which writes raw
 * bytes) gets nothing from the grinder. The core records them redacted by the
 * driver (`redact_for_capture`) in its own capture, which Android doesn't
 * export yet; a grinder in Android's capture files waits for that.
 */
class GrinderBleManager(
    private val transport: BleTransport,
    private val bridge: CremaBridge,
    /** Called with each raw JSON `CoreOutput` string the core returns. */
    private val onCoreOutput: (String) -> Unit,
    /** Human-readable status transitions for the UI log. */
    private val onStatus: (String) -> Unit,
    /** The core identified the grinder: its model id and its
     *  `GrinderCapabilities` JSON. Runs on [scope]. */
    private val onIdentified: (model: String, capabilitiesJson: String?) -> Unit = { _, _ -> },
    /** The device has the grinder's name but not its service; the session
     *  stops (retrying can't help). */
    private val onMismatch: () -> Unit = {},
    /** Monotonic clock, the one the notifications are stamped with. */
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    enum class State { IDLE, SCANNING, CONNECTING, DISCOVERING, SUBSCRIBING, READY, DISCONNECTED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught in grinder BLE scope", e) },
    )

    @Volatile
    private var device: BleTransport.DeviceHandle? = null

    @Volatile
    private var advertisedName: String? = null

    /** The connected grinder's Bluetooth address, or null. */
    val connectedAddress: String? get() = device?.address

    /** The connected grinder's advertised name, or null. */
    val connectedName: String? get() = device?.let { advertisedName }

    @Volatile
    private var uuids: GrinderUuids? = null

    private var observeJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true }

    /** Reconnect after an unexpected drop (the remembered grinder's Auto-connect). */
    @Volatile
    var autoReconnectEnabled: Boolean = true

    /** A DE1 firmware flash runs: connects are refused (see [coffee.crema.ui.ScaleFlashPause]). */
    @Volatile
    var heldForFirmwareFlash: Boolean = false

    @Volatile
    private var userInitiated = false

    private var sessionJob: Job? = null

    @Volatile
    private var sessionGen = 0

    private val kicker = ReconnectKicker(nowMs = SystemClock::elapsedRealtime)

    /** Where the reconnect supervisor is (IDLE = no session). */
    val reconnectPhase: ReconnectKicker.Phase get() = kicker.phase

    /** [reconnectPhase], observable. */
    val reconnectPhaseFlow: StateFlow<ReconnectKicker.Phase> get() = kicker.phaseFlow

    /** True after a user [disconnect] (or [giveUp]) until the next [connect]. */
    val isUserDisconnected: Boolean get() = userInitiated

    @Volatile
    var lurkPolicy: () -> LurkPolicy = {
        if (transport.supportsPendingConnect) LurkPolicy.PENDING_CONNECT else LurkPolicy.SCAN_INTERVAL
    }

    /** The owner handed the hunt to the shared scanner. */
    fun markScanning() {
        _state.value = State.SCANNING
    }

    /** The scan gave up: back to idle without a link. */
    fun markNotFound() {
        if (_state.value == State.SCANNING) _state.value = State.DISCONNECTED
    }

    /** Connect to a grinder the shared [BleScanner] matched (or a remembered one). */
    fun connect(device: BleTransport.DeviceHandle, advertisedName: String) {
        if (heldForFirmwareFlash) {
            onStatus("Grinder waits for the firmware update")
            return
        }
        this.device = device
        this.advertisedName = advertisedName
        userInitiated = false
        _state.value = State.CONNECTING
        onStatus("Connecting to grinder…")
        sessionJob?.cancel()
        val gen = ++sessionGen
        sessionJob = scope.launch { session(device, advertisedName, gen) }
    }

    /** Attempt a reconnect now (foreground, Bluetooth back on, "Retry now"). */
    fun kickReconnect(trigger: ReconnectTrigger): ReconnectKicker.Result {
        if (userInitiated || !autoReconnectEnabled || heldForFirmwareFlash) return ReconnectKicker.Result.IGNORED_IDLE
        return kicker.kick(trigger)
    }

    private suspend fun session(device: BleTransport.DeviceHandle, advertisedName: String, gen: Int) {
        reconnectingSession(
            label = "Grinder",
            logTag = TAG,
            status = onStatus,
            isUserInitiated = { userInitiated },
            isAutoReconnectEnabled = { autoReconnectEnabled },
            kicker = kicker,
            lurkPolicy = { lurkPolicy() },
            establishPending = if (transport.supportsPendingConnect) {
                { establish(device, advertisedName, firstConnect = false, pending = true) }
            } else {
                null
            },
            establish = { firstConnect -> establish(device, advertisedName, firstConnect) },
            awaitDrop = {
                transport.connectionState(device).first {
                    it == BleTransport.ConnState.FAILED || it == BleTransport.ConnState.DISCONNECTED
                }
            },
            onConnected = {
                _state.value = State.READY
                onStatus("Grinder connected — waiting for its first reading")
            },
            onConnecting = { _state.value = State.CONNECTING },
            teardown = {
                if (gen != sessionGen) return@reconnectingSession
                this@GrinderBleManager.device = null
                uuids = null
                dropCoreGrinder()
                _state.value = State.DISCONNECTED
            },
        )
    }

    /** One connect attempt; throws so the supervisor backs off and retries. */
    private suspend fun establish(
        device: BleTransport.DeviceHandle,
        advertisedName: String,
        firstConnect: Boolean,
        pending: Boolean = false,
    ) {
        _state.value = State.CONNECTING
        onStatus(if (firstConnect) "Connecting to grinder…" else "Reconnecting to grinder…")
        val onLinkUp: () -> Unit = { _state.value = State.DISCOVERING }
        if (pending) transport.connectWhenAvailable(device, onLinkUp) else transport.connect(device, onLinkUp)
        _state.value = State.DISCOVERING

        // Every connect is a new driver in the core: the grinder clears its
        // subscription and handshake on every disconnect (BOOKOO §4.3).
        dropCoreGrinder()
        val services = runCatching { transport.discoveredServices(device) }.getOrDefault(emptyList())
        val model = bridge.connectGrinder(advertisedName, services)
            ?: error("Core did not recognise grinder '${grinderShownName(advertisedName)}'")
        val u = json.decodeFromString(
            GrinderUuids.serializer(),
            bridge.grinderUuids() ?: error("Core reported no UUIDs for '$model'"),
        )
        // BOOKOO §4.3: the service, a writable RX and a TX that notifies,
        // else disconnect and report a mismatch.
        if (!gattFits(device, u, services)) {
            // Retrying can't help: stop the session (off this coroutine, which
            // the stop cancels).
            onMismatch()
            scope.launch { giveUp(MISMATCH_MESSAGE) }
            throw GattMismatch(advertisedName)
        }
        uuids = u
        onIdentified(model, runCatching { bridge.grinderCapabilities() }.getOrNull())

        val mtu = transport.requestLargeMtu(device)
        onStatus(if (mtu != null) "Grinder MTU $mtu" else "Grinder MTU unchanged")

        _state.value = State.SUBSCRIBING
        val service = UUID.fromString(u.service)
        val notify = UUID.fromString(u.notify)
        observeJob?.cancel()
        observeJob = transport.observe(device, service, notify)
            .onEach { feedCore(it) }
            .launchIn(scope)
        transport.enableNotifications(device, service, notify)
        // Writes stay 20-byte packets (BOOKOO's client and Sabotage1 both
        // write 20; it fits the default MTU on every phone).
        onCoreOutput(bridge.grinderSubscribed(SAFE_PACKET.toUInt(), nowMs().toULong()))
    }

    /**
     * BOOKOO §4.3 as its client checks it (`mt80_gatt_client.py`): the custom
     * service, RX with `write` or `write-without-response`, and TX with
     * `notify`. A transport that can't list characteristics falls back to the
     * service list; one that listed none can't tell, and the subscribe decides.
     */
    private fun gattFits(device: BleTransport.DeviceHandle, u: GrinderUuids, services: List<String>): Boolean {
        val service = UUID.fromString(u.service)
        val rx = transport.characteristicProperties(device, service, UUID.fromString(u.write))
        val tx = transport.characteristicProperties(device, service, UUID.fromString(u.notify))
        if (rx == null || tx == null) return services.isEmpty() || services.any { it.equals(u.service, ignoreCase = true) }
        return (GattProperty.WRITE in rx || GattProperty.WRITE_WITHOUT_RESPONSE in rx) && GattProperty.NOTIFY in tx
    }

    /** The device answered the scan by name but has no grinder service. Its
     *  message names the model prefix only: it reaches the status lines. */
    class GattMismatch(name: String) : IllegalStateException("${grinderShownName(name)} has no grinder service")

    private fun dropCoreGrinder() {
        runCatching { onCoreOutput(bridge.disconnectGrinder()) }
            .onFailure { Log.w(TAG, "disconnectGrinder failed", it) }
    }

    /** The user disconnected (or the beta was turned off): no reconnect. */
    fun disconnect() {
        userInitiated = true
        sessionJob?.cancel()
        sessionJob = null
        val d = device
        observeJob?.cancel()
        observeJob = null
        device = null
        uuids = null
        dropCoreGrinder()
        if (d != null) scope.launch { transport.disconnect(d) }
        _state.value = State.DISCONNECTED
    }

    /** The grinder can't be used (firmware too old): drop it and don't retry. */
    fun giveUp(reason: String) {
        onStatus("Grinder disconnected: $reason")
        disconnect()
    }

    /** End this manager for good (VM teardown, after [disconnect]). */
    fun close() {
        scope.cancel()
    }

    /**
     * One `Command.WriteGrinder`: a handshake fragment. The lane sends one
     * output's writes in order on one coroutine; this awaits each.
     */
    suspend fun writeCommand(data: ByteArray): Boolean {
        val d = device
        val u = uuids
        if (d == null || u == null) {
            onStatus("Cannot write to grinder — not connected")
            return false
        }
        return try {
            UrgentWriteGate.preemptible(coroutineContext[Job], "grinder write") {
                withTimeout(WRITE_TIMEOUT_MS) {
                    transport.write(
                        d, UUID.fromString(u.service), UUID.fromString(u.write), data,
                        withoutResponse = !u.write_with_response,
                    )
                }
            }
            true
        } catch (c: CancellationException) {
            if (c is UrgentWriteGate.WritePreempted) onStatus("Grinder write dropped — ${c.message}")
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Grinder write failed", e)
            onStatus("Grinder write failed")
            false
        }
    }

    /** The core's `GrinderLinkDead`: drop the link at the transport so the
     *  supervisor reconnects (a fresh handshake). No-op when not connected. */
    suspend fun presumeDead(reason: String) {
        val d = device ?: return
        Log.w(TAG, "Grinder link presumed dead ($reason) — forcing teardown")
        onStatus("Grinder stopped answering — reconnecting…")
        runCatching { transport.disconnect(d) }
    }

    private fun feedCore(notification: BleTransport.Notification) {
        runCatching {
            onCoreOutput(
                bridge.onNotification(NotificationSource.GRINDER_NOTIFY, notification.data, notification.atMs.toULong()),
            )
        }.onFailure {
            // Never the bytes: a grinder message can hold its serial.
            Log.e(TAG, "Grinder notification failed — skipped (${notification.data.size} bytes)", it)
        }
    }

    companion object {
        private const val TAG = "GrinderBleManager"

        /** The packet size the handshake is written in (BOOKOO `SAFE_WRITE_PACKET_LEN`). */
        const val SAFE_PACKET = 20

        /** A device with the grinder's name but not its service (SPEC §2.6 step 2). */
        const val MISMATCH_MESSAGE = "This doesn't look like a Motto 80"

        private const val WRITE_TIMEOUT_MS = 5_000L
    }
}
