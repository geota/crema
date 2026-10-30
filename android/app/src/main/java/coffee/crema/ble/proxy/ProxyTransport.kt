package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.ble.BleTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** How a relayed control ended, as far as the secondary can tell. */
sealed interface ControlReply {
    /** The primary accepted it ([Frame.ControlOk]). */
    data object Ok : ControlReply

    /** The primary refused it ([Frame.ControlErr]), with its reason. */
    data class Err(val reason: String) : ControlReply

    /** It never reached the primary (no welcomed session in time, or this
     *  device was refused): not applied. */
    data class NotSent(val detail: String) : ControlReply

    /** It went out but no reply came (a timeout, or the session ended first):
     *  the primary may have run it. */
    data class NoReply(val detail: String) : ControlReply
}

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
 * It collects the link's [FrameLink.events]. Relay v4 authenticates each
 * session in the core before the link reports it (spec §2.6), so a session
 * here starts at [LinkEvent.SessionStarted] and is ready at the hub's
 * [Frame.Welcome], which carries the scope the hub stored at pairing (a
 * missing or unknown scope reads as view-only, never as control, R6). Every
 * request (attach, detach, read, write, control) waits for the current
 * session's Welcome and is sent on that session only, so nothing lands on a
 * later session. A [LinkEvent.Refused] reaches [onRefused] with whether the
 * hub's refusal was authenticated; a terminal one fails every request at once
 * (the link stopped redialing). A Welcome after the first one (a redial)
 * re-attaches every device. A closed link simply ends the flows.
 */
class ProxyTransport(
    private val link: FrameLink,
    private val scope: CoroutineScope,
    private val requestTimeoutMs: Long = 5_000L,
    /** Invoked with the primary's [Frame.Config] JSON on attach + on every change
     *  — the secondary applies it so its config mirrors the primary (single-owner
     *  config; the settings-drift fix). Default no-op (tests / read-only path). */
    private val onConfig: (json: String) -> Unit = {},
    /** Invoked with the granted scope ([SCOPE_CONTROL] / [SCOPE_MIRROR]) and the
     *  hub's name from each [Frame.Welcome], so the remote can reflect view-only
     *  state and name its hub before the first config snapshot. Default no-op. */
    private val onWelcome: (scope: String, primaryName: String) -> Unit = { _, _ -> },
    /** The hub refused this device ([LinkEvent.Refused]): [authenticated] only
     *  for a `CLOSE` record decrypted with the session's keys (spec §2.9), and
     *  [terminal] when the link stopped redialing. The caller decides what it
     *  means for the role. Default no-op. */
    private val onRefused: (reason: String, authenticated: Boolean, terminal: Boolean) -> Unit = { _, _, _ -> },
    /** Invoked with a primary [Frame.Event] — a transient "<who> drove the machine"
     *  notice (issue 11, loose multi-controller). Display-only; the VM surfaces it
     *  as a snackbar. Default no-op. */
    private val onEvent: (text: String) -> Unit = {},
) : BleTransport {

    /** Devices the primary holds, from [Frame.Welcome] / [Frame.Roster]; drives [scan]. */
    private val roster = MutableStateFlow<List<DeviceInfo>>(emptyList())

    /** The primary's device roster (issue 04) — the VM watches it on a secondary to
     *  attach the scale only once the primary actually advertises one (no blind scan). */
    val deviceRoster: StateFlow<List<DeviceInfo>> get() = roster.asStateFlow()

    /** Per-device connection state, driven by [Frame.State] / [Frame.Attached]. */
    private val connStates = ConcurrentHashMap<String, MutableStateFlow<BleTransport.ConnState>>()

    /** In-flight requests (attach/detach/read/write) awaiting a correlated reply. */
    private val pending = ConcurrentHashMap<Long, Sent>()

    /** A request on the wire: the session [epoch] it went out on, and its reply. */
    private class Sent(val epoch: Long, val reply: CompletableDeferred<Frame> = CompletableDeferred())

    /** Fail every request sent on a session older than [epoch] (all of them
     *  when null): its reply can no longer arrive. Not resent — a control may
     *  already have run. */
    private fun failSupersededRequests(epoch: Long?) {
        for ((id, sent) in pending) {
            if (epoch == null || sent.epoch < epoch) {
                pending.remove(id)
                sent.reply.completeExceptionally(IllegalStateException("Proxy session ended before a reply"))
            }
        }
    }

    /** Addresses the managers have attached, so a reconnect can re-attach them all. */
    private val attached = ConcurrentHashMap.newKeySet<String>()

    /** Per-`(address, char)` lossless inbound notification channels. */
    private val streams = ConcurrentHashMap<String, ConcurrentHashMap<String, Channel<Frame.Notify>>>()

    /** Live collectors per `"address|char"` — the double-observe tripwire. */
    private val observerCounts = ConcurrentHashMap<String, Int>()

    private val nextId = AtomicLong(1L)

    /** Where the current session's handshake stands. One per epoch. */
    private sealed class Handshake(val epoch: Long) {
        class Pending(epoch: Long) : Handshake(epoch)
        class Welcomed(epoch: Long) : Handshake(epoch)
        class Refused(epoch: Long, val error: Throwable) : Handshake(epoch)
    }

    private val handshake = MutableStateFlow<Handshake>(Handshake.Pending(0L))

    /** Whether a session has been welcomed before (a later Welcome re-attaches). */
    @Volatile
    private var welcomedBefore = false

    init {
        link.events().onEach { onLinkEvent(it) }.launchIn(scope)
    }

    private fun onLinkEvent(event: LinkEvent) {
        when (event) {
            is LinkEvent.SessionStarted -> {
                // A terminal refusal sticks: the link stopped, nothing redials.
                if (handshake.value is Handshake.Refused) return
                handshake.value = Handshake.Pending(event.epoch)
                failSupersededRequests(event.epoch)
            }
            is LinkEvent.Received -> dispatch(event.frame)
            is LinkEvent.Refused -> {
                if (event.terminal) {
                    handshake.value = Handshake.Refused(
                        handshake.value.epoch,
                        IllegalStateException("Proxy refused: ${event.reason}"),
                    )
                    failSupersededRequests(null)
                }
                onRefused(event.reason, event.authenticated, event.terminal)
            }
        }
    }

    /** Re-attach every device the managers had attached after a redial (issue
     *  03): each re-attach triggers the relay's snapshot burst, so the
     *  secondary's core re-converges and `observe` resumes. */
    private suspend fun reattach() {
        Log.i(TAG, "Session re-established — re-attaching ${attached.size} device(s)")
        for (address in attached.toList()) {
            val id = nextId.getAndIncrement()
            runCatching { request(id) { Frame.Attach(id, address) } }
                .onSuccess { reply ->
                    if (reply !is Frame.Attached) Log.w(TAG, "re-attach $address refused: $reply")
                }
                .onFailure { Log.w(TAG, "re-attach $address failed: ${it.message}") }
        }
    }

    private fun dispatch(frame: Frame) {
        // Until this session is welcomed only the Welcome counts: anything else
        // (config, telemetry, events) before it is ignored.
        if (frame !is Frame.Welcome && handshake.value !is Handshake.Welcomed) {
            Log.w(TAG, "Ignoring ${frame::class.simpleName} before the session's Welcome")
            return
        }
        when (frame) {
            is Frame.Welcome -> {
                val state = handshake.value
                if (state !is Handshake.Pending) return
                applyRoster(frame.roster)
                // Only "control" grants control; anything else is view-only (R6).
                onWelcome(if (frame.grantsControl) SCOPE_CONTROL else SCOPE_MIRROR, frame.primaryName)
                handshake.value = Handshake.Welcomed(state.epoch)
                if (welcomedBefore) scope.launch { reattach() }
                welcomedBefore = true
            }
            is Frame.Roster -> applyRoster(frame.devices)
            is Frame.Attached -> {
                stateFlow(frame.address).value = parseState(frame.state)
                pending.remove(frame.id)?.reply?.complete(frame)
            }
            is Frame.Detached -> pending.remove(frame.id)?.reply?.complete(frame)
            is Frame.ReadOk -> pending.remove(frame.id)?.reply?.complete(frame)
            is Frame.ReadErr -> pending.remove(frame.id)?.reply?.complete(frame)
            is Frame.WriteOk -> pending.remove(frame.id)?.reply?.complete(frame)
            is Frame.WriteErr -> pending.remove(frame.id)?.reply?.complete(frame)
            is Frame.ControlOk -> pending.remove(frame.id)?.reply?.complete(frame)
            is Frame.ControlErr -> pending.remove(frame.id)?.reply?.complete(frame)
            is Frame.Notify -> channelFor(frame.address, frame.char).trySend(frame)
            is Frame.State -> stateFlow(frame.address).value = parseState(frame.state)
            is Frame.Config -> onConfig(frame.json)
            is Frame.Event -> onEvent(frame.text)
            else -> Log.w(TAG, "Secondary ignoring unexpected server frame: ${frame::class.simpleName}")
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
        // The first session may take a while (the hub restarting): no timeout.
        val settled = handshake.first { it !is Handshake.Pending }
        if (settled is Handshake.Refused) throw settled.error
        val id = nextId.getAndIncrement()
        val reply = request(id) { Frame.Attach(id, device.address) }
        if (reply is Frame.ControlErr) error("attach refused: ${reply.reason}")
        check(reply is Frame.Attached) { "Unexpected reply to attach: $reply" }
        attached.add(device.address) // remember for re-attach on reconnect (issue 03)
    }

    override suspend fun disconnect(device: BleTransport.DeviceHandle) {
        attached.remove(device.address)
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
    ): Flow<BleTransport.Notification> {
        val key = "${device.address}|$characteristic"
        return channelFor(device.address, characteristic.toString()).receiveAsFlow()
            // The per-char channel is SINGLE-consumer: two concurrent collectors
            // would each steal a share of the frames — silent sample loss for
            // both (and corrupted core sample counters). The managers subscribe
            // once per char; if that invariant ever breaks, say so loudly.
            .onStart {
                val n = observerCounts.merge(key, 1, Int::plus) ?: 1
                if (n > 1) Log.e(TAG, "DOUBLE observe on $key ($n collectors) — the stream is being split; samples will be lost")
            }
            .onCompletion { observerCounts.merge(key, -1, Int::plus) }
            .map { n -> BleTransport.Notification(characteristic, Hex.decode(n.hex), n.t) }
    }

    /** Live [observe] collectors for one characteristic (the tripwire's count). */
    internal fun observerCount(address: String, characteristic: UUID): Int =
        observerCounts["$address|$characteristic"] ?: 0

    override suspend fun write(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
        withoutResponse: Boolean,
    ) {
        // The proxy frame carries no write type; the far side's own transport
        // picks it (M1 is a read-only mirror anyway).
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
     * part of [BleTransport]. Returns failure on [Frame.ControlErr]/timeout,
     * never throwing into the UI thread. [sendControl] says which failure.
     */
    suspend fun control(method: String, args: String = ""): Result<Unit> = when (val reply = sendControl(method, args)) {
        ControlReply.Ok -> Result.success(Unit)
        is ControlReply.Err -> Result.failure(IllegalStateException("control rejected: ${reply.reason}"))
        is ControlReply.NotSent -> Result.failure(IllegalStateException(reply.detail))
        is ControlReply.NoReply -> Result.failure(IllegalStateException(reply.detail))
    }

    /**
     * Relay a control and say how it ended, as far as this device can tell:
     * the primary's answer, or whether the frame never left (not applied) or
     * left without an answer (the primary may have run it). Never resent.
     */
    suspend fun sendControl(method: String, args: String = ""): ControlReply {
        val id = nextId.getAndIncrement()
        var sent = false
        return try {
            when (val reply = request(id, onWire = { sent = it }) { Frame.Control(id, method, args) }) {
                is Frame.ControlOk -> ControlReply.Ok
                is Frame.ControlErr -> ControlReply.Err(reply.reason)
                else -> ControlReply.NoReply("unexpected reply to control: $reply")
            }
        } catch (e: TimeoutCancellationException) {
            if (sent) ControlReply.NoReply(e.message ?: "no reply in time") else ControlReply.NotSent(e.message ?: "not connected")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = e.message ?: e.toString()
            if (sent) ControlReply.NoReply(detail) else ControlReply.NotSent(detail)
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

    /** Send a request and wait for its reply. [onWire] is true from the moment
     *  the frame may be on a welcomed session's wire, false again if that send
     *  put nothing out. */
    private suspend fun request(id: Long, onWire: (Boolean) -> Unit = {}, build: () -> Frame): Frame {
        return try {
            // The send is inside the timeout too: ReconnectingClientLink.send()
            // SUSPENDS until the link is back, so on a dead/mid-reconnect link an
            // unbounded send would hang the request forever — the caller would
            // neither succeed, fail, nor be able to revert (issue 06). Bounding the
            // whole round-trip makes a control/read fail fast so the optimistic
            // apply can be rolled back and surfaced.
            // The handshake wait is inside the timeout too: a request goes out
            // only on a welcomed session, and only on that one. If the session
            // ends before the send, wait for the next session's Welcome (or
            // fail at once on a refusal). Once sent, a new session or a refusal
            // fails the request at once instead of letting it time out.
            withTimeout(requestTimeoutMs) {
                val frame = build()
                var sent: Sent
                while (true) {
                    val settled = handshake.first { it !is Handshake.Pending }
                    if (settled is Handshake.Refused) throw settled.error
                    sent = Sent(settled.epoch).also { pending[id] = it }
                    onWire(true)
                    if (link.sendOn(settled.epoch, frame)) break
                    onWire(false)
                    pending.remove(id)
                    handshake.first { it.epoch != settled.epoch || it is Handshake.Refused }
                }
                sent.reply.await()
            }
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

        fun parseState(s: String): BleTransport.ConnState =
            runCatching { BleTransport.ConnState.valueOf(s) }.getOrDefault(BleTransport.ConnState.DISCONNECTED)
    }
}
