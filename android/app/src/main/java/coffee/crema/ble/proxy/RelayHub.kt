package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.ble.BleTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The primary-side relay: it forwards the one real BLE link out to N secondaries
 * over the proxy protocol. Transport-agnostic — it is fed the primary's
 * notification stream through [onInbound] / [onConnState] (in production by the
 * `TappingBleTransport` decorator; in tests directly) and serves each secondary
 * connection in [serve]. The WebSocket/Ktor server is a thin shell that accepts
 * a socket, wraps it in a [FrameLink], and calls [serve]; the in-memory loopback
 * does the same with no socket.
 *
 * ## What it does
 *
 * - **Fan-out.** Every inbound notification is delivered to every secondary
 *   attached to that device. Delivery is *decoupled* from the primary's own
 *   notification path: [onInbound] only `trySend`s into each client's bounded
 *   outbox, so a slow remote never back-pressures the primary's core. (A remote
 *   that overflows its outbox is dropped and resyncs via a fresh snapshot — see
 *   [deliver]; M1's in-memory loopback uses an unbounded outbox so the
 *   losslessness test sees every sample.)
 * - **Snapshot-on-attach.** A per-characteristic last-value [cache] of the
 *   state/identity characteristics (NOT the counted sample streams) is replayed
 *   to a secondary the moment it attaches, so its fresh core converges to the
 *   machine's current state.
 * - **Reads** are served from [readSource] (the real `transport.read` in
 *   production).
 * - **Writes are rejected** (`not-authoritative`) — M1 is a read-only mirror;
 *   the primary is the sole controller.
 */
class RelayHub(
    private val primaryId: String,
    private val primaryName: String,
    /** The devices the primary currently holds; evaluated per [Frame.Hello]. */
    private val roster: () -> List<DeviceInfo>,
    /** Serves a GATT read for a [Frame.Read] — `transport.read(...)` in production. */
    private val readSource: suspend (address: String, service: UUID, char: UUID) -> ByteArray,
    /**
     * Whether (`service`, `char`) is a latest-value state/identity characteristic
     * to snapshot on attach, vs a counted sample stream (ShotSample / scale
     * weight) that must stay live-only or the core's sample counters double-count.
     */
    private val isSnapshotChar: (service: UUID, char: UUID) -> Boolean,
    /**
     * Per-client outbox capacity. [Channel.UNLIMITED] in tests (lossless); bounded
     * in production so a lagging client is dropped instead of throttling the
     * primary.
     */
    private val clientCapacity: Int = Channel.UNLIMITED,
    /**
     * Dispatch a relayed user-intent [Frame.Control] from a secondary to the
     * primary's command router (the VM's machine-control methods in production):
     * `success` once dispatched on the real link — the effect returns to the
     * secondary over the [Frame.Notify] stream — `failure` to reply
     * [Frame.ControlErr]. The default rejects every control; the real handler is
     * wired only on the primary (a normal/loopback relay has no machine to
     * drive), so M1 and the unit tests keep a read-only mirror by construction.
     */
    private val controlHandler: suspend (method: String, args: String) -> Result<Unit> =
        { _, _ -> Result.failure(UnsupportedOperationException("relay has no control handler")) },
) {
    private val clients = CopyOnWriteArrayList<ClientSession>()

    /** Last-value cache of snapshot characteristics, keyed `"address|char"`. */
    private val cache = ConcurrentHashMap<String, Frame.Notify>()

    /** Latest connection state per device address (a [BleTransport.ConnState] name). */
    private val connStates = ConcurrentHashMap<String, String>()

    // ---- Tap: fed by the primary's notification path ----------------------

    /** A notification arrived on the real link: cache it (if a snapshot char) and
     *  fan it out to every attached secondary. Non-suspending — never throttles
     *  the caller (the primary's lossless collection). */
    fun onInbound(
        address: String,
        service: UUID,
        char: UUID,
        data: ByteArray,
        atMs: Long,
        src: String? = null,
    ) {
        val frame = Frame.Notify(address, service.toString(), char.toString(), Hex.encode(data), atMs, src)
        if (isSnapshotChar(service, char)) cache[cacheKey(address, char)] = frame
        for (c in clients) if (c.isAttached(address)) deliver(c, frame)
    }

    /** A device's connection state changed: fan a [Frame.State] out to attached
     *  secondaries (their managers' reconnect loops react to it). */
    fun onConnState(address: String, state: String) {
        connStates[address] = state
        val frame = Frame.State(address, state)
        for (c in clients) if (c.isAttached(address)) deliver(c, frame)
    }

    // ---- One client connection --------------------------------------------

    /** Serve one secondary for the life of its [link]. Returns when the link
     *  closes. The caller (WS server / loopback) provides one [FrameLink] per
     *  connection. */
    suspend fun serve(link: FrameLink): Unit = coroutineScope {
        val session = ClientSession(link, clientCapacity)
        clients += session
        // One writer drains the outbox to the link, so the primary's notification
        // path (onInbound) and request handlers never block on the socket.
        val pump = launch { for (frame in session.outbox) link.send(frame) }
        try {
            link.incoming().collect { frame ->
                // Reads and controls are suspend (they hit the real link / drive
                // the machine); run them off the collect loop so they don't
                // head-of-line-block later frames.
                when (frame) {
                    is Frame.Read -> launch { handleRead(session, frame) }
                    is Frame.Control -> launch { handleControl(session, frame) }
                    else -> handle(session, frame)
                }
            }
        } finally {
            clients.remove(session)
            session.outbox.close()
            pump.cancel()
        }
    }

    private fun handle(session: ClientSession, frame: Frame) {
        when (frame) {
            is Frame.Hello ->
                deliver(session, Frame.Welcome(PROXY_PROTOCOL_VERSION, primaryId, primaryName, AUTHORITY_PRIMARY, roster()))
            is Frame.Attach -> {
                session.attach(frame.address)
                val state = connStates[frame.address] ?: BleTransport.ConnState.CONNECTED.name
                deliver(session, Frame.Attached(frame.id, frame.address, state))
                // Snapshot burst: replay every cached state/identity char for this
                // device so the secondary converges to current machine state.
                val prefix = frame.address + "|"
                for ((key, cached) in cache) if (key.startsWith(prefix)) deliver(session, cached)
            }
            is Frame.Detach -> {
                session.detach(frame.address)
                deliver(session, Frame.Detached(frame.id, frame.address))
            }
            is Frame.Write ->
                // M1 read-only mirror: the primary is the sole controller. (The M2
                // control path relays user intent through the primary's command
                // router instead.)
                deliver(session, Frame.WriteErr(frame.id, "not-authoritative"))
            else -> Log.w(TAG, "Relay ignoring unexpected client frame: $frame")
        }
    }

    private suspend fun handleRead(session: ClientSession, frame: Frame.Read) {
        val reply = try {
            val bytes = readSource(frame.address, UUID.fromString(frame.service), UUID.fromString(frame.char))
            Frame.ReadOk(frame.id, Hex.encode(bytes))
        } catch (e: Exception) {
            Frame.ReadErr(frame.id, e.message ?: "read failed")
        }
        deliver(session, reply)
    }

    /** Dispatch a secondary's relayed user intent to the primary's command router
     *  and reply [Frame.ControlOk] / [Frame.ControlErr]. The action drives the
     *  primary's real link; its effect returns to every secondary as machine
     *  state over the [Frame.Notify] stream, so the reply only confirms dispatch. */
    private suspend fun handleControl(session: ClientSession, frame: Frame.Control) {
        Log.i(TAG, "relayed control: ${frame.method}${if (frame.args.isEmpty()) "" else " (${frame.args})"}")
        val reply = controlHandler(frame.method, frame.args).fold(
            onSuccess = { Frame.ControlOk(frame.id) },
            onFailure = { Frame.ControlErr(frame.id, it.message ?: "control failed") },
        )
        deliver(session, reply)
    }

    /** Enqueue a frame for [session]. If its outbox is full (a lagging client on a
     *  bounded queue), drop the client — it reconnects and resyncs via snapshot,
     *  rather than stalling the primary. */
    private fun deliver(session: ClientSession, frame: Frame) {
        if (!session.offer(frame)) {
            Log.w(TAG, "Client outbox full — dropping client (will resync on reconnect)")
            session.outbox.close()
        }
    }

    private fun cacheKey(address: String, char: UUID): String = "$address|$char"

    /** One connected secondary: its outbox and the set of devices it has attached. */
    private class ClientSession(@Suppress("unused") val link: FrameLink, capacity: Int) {
        val outbox = Channel<Frame>(capacity)
        private val attached = ConcurrentHashMap.newKeySet<String>()
        fun isAttached(address: String): Boolean = attached.contains(address)
        fun attach(address: String) { attached.add(address) }
        fun detach(address: String) { attached.remove(address) }
        fun offer(frame: Frame): Boolean = outbox.trySend(frame).isSuccess
    }

    private companion object {
        const val TAG = "RelayHub"
        const val AUTHORITY_PRIMARY = "primary"
    }
}
