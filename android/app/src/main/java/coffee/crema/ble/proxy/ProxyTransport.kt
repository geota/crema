package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.ble.BleTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * [BleTransport] implemented as a network client to a primary's [RelayHub] — the
 * secondary half of the M1 LAN proxy. It holds no BLE radio; `scan/connect/
 * observe/read/write/connectionState` are round-trips over a [FrameLink], so the
 * `De1BleManager` / `ScaleBleManager` above it run unchanged and feed the same
 * Rust core. "It cannot tell it isn't holding the radio."
 *
 * ## Faithful to the seam
 *
 * - **`observe` stays lossless, non-conflating, ordered.** Each device
 *   characteristic gets its own unbounded [Channel]; the receive loop routes
 *   every [Frame.Notify] into it and `observe` drains it, so a snapshot value
 *   buffered before the manager subscribes is not lost, and the Rust core sees
 *   every sample. (Unbounded locally mirrors `NordicBleTransport`'s buffer; the
 *   primary, not the secondary, sheds load by dropping a lagging client.)
 * - **`atMs` is forwarded verbatim** (the primary's `elapsedRealtime`), never
 *   re-stamped, so a mirrored stream decodes identically to a replayed capture.
 * - **`read` round-trips**; **`write` is a read-only-mirror no-op** in M1 — the
 *   relay rejects it (`not-authoritative`) and this swallows the rejection so the
 *   unmodified core/manager write path is undisturbed and never throws. (M2's
 *   `read_only` core flag stops the emission upstream.)
 *
 * ## Lifecycle
 *
 * On construction it sends [Frame.Hello] and collects the link; `scan`/`connect`
 * await the primary's [Frame.Welcome]. The link's own reconnection (NSD
 * re-resolve, redial, re-attach, re-snapshot) is a layer above this class and is
 * added with the WebSocket binding; here a closed link simply ends the flows.
 */
class ProxyTransport(
    private val link: FrameLink,
    scope: CoroutineScope,
    private val clientId: String,
    private val clientName: String,
    private val requestTimeoutMs: Long = 5_000L,
    /** Invoked with the primary's [Frame.Config] JSON on attach + on every change
     *  — the secondary applies it so its config mirrors the primary (single-owner
     *  config; the settings-drift fix). Default no-op (tests / read-only path). */
    private val onConfig: (json: String) -> Unit = {},
) : BleTransport {

    /** Devices the primary holds, from [Frame.Welcome] / [Frame.Roster]; drives [scan]. */
    private val roster = MutableStateFlow<List<DeviceInfo>>(emptyList())

    /** Per-device connection state, driven by [Frame.State] / [Frame.Attached]. */
    private val connStates = ConcurrentHashMap<String, MutableStateFlow<BleTransport.ConnState>>()

    /** In-flight requests (attach/detach/read/write) awaiting a correlated reply. */
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Frame>>()

    /** Per-`(address, char)` lossless inbound notification channels. */
    private val streams = ConcurrentHashMap<String, ConcurrentHashMap<String, Channel<Frame.Notify>>>()

    private val nextId = AtomicLong(1L)
    private val welcomed = CompletableDeferred<Unit>()

    init {
        link.incoming().onEach { dispatch(it) }.launchIn(scope)
        scope.launch { link.send(Frame.Hello(PROXY_PROTOCOL_VERSION, ROLE_SECONDARY, clientId, clientName)) }
    }

    private fun dispatch(frame: Frame) {
        when (frame) {
            is Frame.Welcome -> {
                applyRoster(frame.roster)
                if (!welcomed.isCompleted) welcomed.complete(Unit)
            }
            is Frame.Roster -> applyRoster(frame.devices)
            is Frame.Attached -> {
                stateFlow(frame.address).value = parseState(frame.state)
                pending.remove(frame.id)?.complete(frame)
            }
            is Frame.Detached -> pending.remove(frame.id)?.complete(frame)
            is Frame.ReadOk -> pending.remove(frame.id)?.complete(frame)
            is Frame.ReadErr -> pending.remove(frame.id)?.complete(frame)
            is Frame.WriteOk -> pending.remove(frame.id)?.complete(frame)
            is Frame.WriteErr -> pending.remove(frame.id)?.complete(frame)
            is Frame.ControlOk -> pending.remove(frame.id)?.complete(frame)
            is Frame.ControlErr -> pending.remove(frame.id)?.complete(frame)
            is Frame.Notify -> channelFor(frame.address, frame.char).trySend(frame)
            is Frame.State -> stateFlow(frame.address).value = parseState(frame.state)
            is Frame.Config -> onConfig(frame.json)
            is Frame.Denied ->
                if (!welcomed.isCompleted) welcomed.completeExceptionally(IllegalStateException("Proxy denied: ${frame.reason}"))
            else -> Log.w(TAG, "Secondary ignoring unexpected server frame: $frame")
        }
    }

    private fun applyRoster(devices: List<DeviceInfo>) {
        roster.value = devices
        for (d in devices) stateFlow(d.address).value = parseState(d.state)
    }

    // ---- BleTransport -----------------------------------------------------

    override fun scan(matches: (name: String) -> Boolean): Flow<BleTransport.ScanMatch> =
        roster.transform { devices ->
            for (d in devices) if (matches(d.name)) {
                emit(BleTransport.ScanMatch(ProxyDeviceHandle(d.name, d.address), d.name))
            }
        }

    override suspend fun connect(device: BleTransport.DeviceHandle) {
        welcomed.await()
        val id = nextId.getAndIncrement()
        val reply = request(id) { Frame.Attach(id, device.address) }
        check(reply is Frame.Attached) { "Unexpected reply to attach: $reply" }
    }

    override suspend fun disconnect(device: BleTransport.DeviceHandle) {
        val id = nextId.getAndIncrement()
        runCatching { request(id) { Frame.Detach(id, device.address) } }
        streams.remove(device.address)?.values?.forEach { it.close() }
        stateFlow(device.address).value = BleTransport.ConnState.DISCONNECTED
    }

    override fun connectionState(device: BleTransport.DeviceHandle): StateFlow<BleTransport.ConnState> =
        stateFlow(device.address).asStateFlow()

    override fun observe(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
    ): Flow<BleTransport.Notification> =
        channelFor(device.address, characteristic.toString()).receiveAsFlow().map { n ->
            BleTransport.Notification(characteristic, Hex.decode(n.hex), n.t)
        }

    override suspend fun write(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
    ) {
        val id = nextId.getAndIncrement()
        val reply = request(id) {
            Frame.Write(id, device.address, service.toString(), characteristic.toString(), Hex.encode(data))
        }
        if (reply is Frame.WriteErr) {
            // M1 read-only mirror: swallow rather than throw (see the class KDoc).
            Log.i(TAG, "write rejected (read-only mirror): ${reply.reason}")
        }
    }

    /**
     * Relay a **user-intent** control action to the primary (the secondary's own
     * core is a read-only mirror and cannot drive the machine). Suspends until
     * the primary acks dispatch via [Frame.ControlOk]; the action's *effect*
     * arrives asynchronously over [observe] as the primary's machine state. Not
     * part of [BleTransport] — the VM calls it directly when relaying a tap on a
     * secondary's Brew controls. Returns failure on [Frame.ControlErr]/timeout so
     * the caller can surface it, never throwing into the UI thread.
     */
    suspend fun control(method: String, args: String = ""): Result<Unit> {
        val id = nextId.getAndIncrement()
        return runCatching {
            when (val reply = request(id) { Frame.Control(id, method, args) }) {
                is Frame.ControlOk -> Unit
                is Frame.ControlErr -> error("control rejected: ${reply.reason}")
                else -> error("Unexpected reply to control: $reply")
            }
        }
    }

    override suspend fun read(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
    ): ByteArray {
        val id = nextId.getAndIncrement()
        return when (val reply = request(id) {
            Frame.Read(id, device.address, service.toString(), characteristic.toString())
        }) {
            is Frame.ReadOk -> Hex.decode(reply.hex)
            is Frame.ReadErr -> error("Proxy read failed: ${reply.reason}")
            else -> error("Unexpected reply to read: $reply")
        }
    }

    // ---- Internals --------------------------------------------------------

    private suspend fun request(id: Long, build: () -> Frame): Frame {
        val deferred = CompletableDeferred<Frame>()
        pending[id] = deferred
        return try {
            link.send(build())
            withTimeout(requestTimeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun stateFlow(address: String): MutableStateFlow<BleTransport.ConnState> =
        connStates.computeIfAbsent(address) { MutableStateFlow(BleTransport.ConnState.DISCONNECTED) }

    private fun channelFor(address: String, char: String): Channel<Frame.Notify> =
        streams.computeIfAbsent(address) { ConcurrentHashMap() }
            .computeIfAbsent(char) { Channel(Channel.UNLIMITED) }

    private class ProxyDeviceHandle(
        override val name: String?,
        override val address: String,
    ) : BleTransport.DeviceHandle {
        override fun equals(other: Any?): Boolean = other is ProxyDeviceHandle && other.address == address
        override fun hashCode(): Int = address.hashCode()
    }

    private companion object {
        const val TAG = "ProxyTransport"
        const val ROLE_SECONDARY = "secondary"

        fun parseState(s: String): BleTransport.ConnState =
            runCatching { BleTransport.ConnState.valueOf(s) }.getOrDefault(BleTransport.ConnState.DISCONNECTED)
    }
}
