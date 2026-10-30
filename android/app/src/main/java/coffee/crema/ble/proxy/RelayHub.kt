package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.ble.BleTransport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** A remote currently connected through this hub: the hub's read-only
 *  "Remotes connected" list. [canControl] is false for a view-only remote. */
data class RemoteClient(val clientId: String, val clientName: String, val canControl: Boolean)

/**
 * The remote an admitted relay session belongs to (relay v4): its device id,
 * the name and scope the hub stored with its pin at pairing. The core's
 * handshake authenticated it before [RelayHub.serve] sees the link.
 */
data class RemotePeer(val deviceId: String, val name: String, val canControl: Boolean)

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
    private val primaryName: String,
    /** The devices the primary currently holds; evaluated per [Frame.Welcome]. */
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
     * Per-client outbox capacity. Bounded, so a client that stops reading is
     * dropped (its link closes; it resyncs on reconnect) instead of growing the
     * primary's heap. Tests that need a tiny or unlimited outbox pass one.
     */
    private val clientCapacity: Int = DEFAULT_CLIENT_CAPACITY,
    /**
     * Dispatch a relayed user-intent [Frame.Control] from a secondary to the
     * primary's command router (the machine command seam in production):
     * `success` when accepted — the effect returns to the secondary over the
     * [Frame.Notify] stream — `failure` with the reason to reply
     * [Frame.ControlErr]. Called one control at a time per session, in arrival
     * order. The default rejects every control; the real handler is
     * wired only on the primary (a normal/loopback relay has no machine to
     * drive), so M1 and the unit tests keep a read-only mirror by construction.
     */
    private val controlHandler: suspend (method: String, args: String, originId: String?, originName: String?) -> Result<Unit> =
        { _, _, _, _ -> Result.failure(UnsupportedOperationException("relay has no control handler")) },
    /**
     * The primary's current session-config JSON (an app-level `ConfigSnapshot`),
     * pushed to a secondary on attach — the single-owner config a mirror
     * snaps to (the settings-drift fix). Suspending: the hub builds every
     * snapshot on its UI thread (spec §6.1, so its `rev` is taken in the same
     * step as the state it reads). Empty string = nothing to send (M1 / a
     * normal relay / the unit tests).
     */
    private val configSource: suspend () -> String = { "" },
    /** Fired when a remote joins or leaves, so the hub's UI can refresh its
     *  "Remotes connected" list. Default no-op (tests / loopback). */
    private val onClientsChanged: () -> Unit = {},
    /** At most this many admitted sessions at once; more are closed. */
    private val maxSessions: Int = MAX_SESSIONS,
) {
    private val clients = CopyOnWriteArrayList<ClientSession>()

    /** A cached snapshot value with the global sequence it was assigned on the
     *  primary — the per-client monotonic guard's key to never regressing a
     *  characteristic to a stale value (snapshot-vs-live attach race). */
    private class CachedNotify(val seq: Long, val frame: Frame.Notify)

    /** Last-value cache of snapshot characteristics, keyed `"address|char"`. */
    private val cache = ConcurrentHashMap<String, CachedNotify>()

    /** Global, monotonically increasing notify sequence — assigned at [onInbound],
     *  compared per client per characteristic in [deliverNotify]. */
    private val seqGen = AtomicLong(0L)

    /** Latest connection state per device address (a [BleTransport.ConnState] name). */
    private val connStates = ConcurrentHashMap<String, String>()

    /** Whether the device [clientId] currently has a live session. */
    fun hasClient(clientId: String): Boolean = clients.any { it.approved && it.clientId == clientId }

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
        val key = cacheKey(address, char)
        val seq = seqGen.incrementAndGet()
        // Keep the newest by seq: two overlapping calls for one characteristic
        // must not leave the older value cached for later attaches.
        val snapshot = isSnapshotChar(service, char)
        if (snapshot) {
            cache.merge(key, CachedNotify(seq, frame)) { old, new -> if (new.seq > old.seq) new else old }
        }
        // Only snapshot chars race the attach burst; counted sample streams
        // are never cached, so they skip the per-char guard (no map work per
        // sample on the primary's notification path).
        for (c in clients) if (c.approved && c.isAttached(address)) {
            if (snapshot) deliverNotify(c, key, seq, frame) else deliver(c, frame)
        }
    }

    /** A device's connection state changed: fan a [Frame.State] out to attached
     *  secondaries (their managers' reconnect loops react to it). */
    fun onConnState(address: String, state: String) {
        connStates[address] = state
        val frame = Frame.State(address, state)
        for (c in clients) if (c.approved && c.isAttached(address)) deliver(c, frame)
    }

    /** Push [json], the primary's current session config, to every attached
     *  secondary — called when the primary's config changes, so mirrors stay in
     *  sync between attaches (the attach-time snapshot covers a fresh join).
     *  Synchronous: a relayed edit's snapshot goes out before its reply. */
    fun pushConfig(json: String) {
        if (json.isEmpty()) return
        val frame = Frame.Config(json)
        for (c in clients) if (c.approved) deliver(c, frame)
    }

    /** Push the primary's current device roster to every connected secondary —
     *  called when a device connects/disconnects (issue 04). The [Frame.Welcome]
     *  roster only covers the set present at attach, so a mirror that joined
     *  before a scale was connected (or after one left) would never see the
     *  change; this re-push keeps every mirror's scan/roster live without a
     *  reconnect. [Frame.Roster] is the standalone update the secondary's
     *  `ProxyTransport` already folds into its `deviceRoster`. */
    fun pushRoster() {
        val frame = Frame.Roster(roster())
        for (c in clients) if (c.approved) deliver(c, frame)
    }

    /** Fan a transient "who's driving" notice (issue 11) to every connected
     *  secondary EXCEPT [exceptClientId] — the originator already knows it acted
     *  (and sees the resulting telemetry). Display-only. */
    fun broadcastEvent(text: String, exceptClientId: String?) {
        val frame = Frame.Event(text)
        for (c in clients) if (c.approved && c.clientId != exceptClientId) deliver(c, frame)
    }

    /** Every live remote session, view-only ones included: the hub's
     *  "Remotes connected" list. Only admitted sessions reach the hub. */
    fun remotes(): List<RemoteClient> =
        clients.mapNotNull { c ->
            val id = c.clientId
            if (id != null && c.approved && !c.isClosed) RemoteClient(id, c.clientName ?: id, c.canControl) else null
        }

    /** End every live session of [clientId] (the host forgot the device):
     *  nothing more reaches it, and its link closes with an authenticated
     *  `CLOSE(REVOKED)` ([FrameLink.revoke], spec §2.10). */
    fun revoke(clientId: String) {
        for (c in clients) if (c.clientId == clientId) {
            c.approved = false
            c.canControl = false
            c.revoked = true
            c.closeOutbox()
        }
        onClientsChanged()
    }

    /** Every live session, for an authenticated close when hosting stops. */
    internal fun links(): List<FrameLink> = clients.map { it.link }

    // ---- One client connection --------------------------------------------

    /** Serve one admitted remote, [peer], for the life of its [link]. Returns
     *  when the link closes. The core's handshake authenticated the peer before
     *  this runs (relay v4), so the session is approved from the start: the
     *  [Frame.Welcome] goes first, with the scope stored at pairing. The caller
     *  (the secure acceptor / the loopback) provides one link per connection. */
    suspend fun serve(link: FrameLink, peer: RemotePeer): Unit = coroutineScope {
        val session = ClientSession(link, clientCapacity)
        val admitted = synchronized(clients) { (clients.size < maxSessions).also { if (it) clients += session } }
        if (!admitted) {
            Log.w(TAG, "Closing an admitted session: $maxSessions sessions already open")
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) { runCatching { link.close() } }
            return@coroutineScope
        }
        session.clientId = peer.deviceId
        session.clientName = peer.name
        session.canControl = peer.canControl
        session.approved = true
        deliver(session, Frame.Welcome(primaryName, AUTHORITY_PRIMARY, roster(), if (peer.canControl) SCOPE_CONTROL else SCOPE_MIRROR))
        onClientsChanged() // the hub's Remotes list shows it
        val reader = launch { readFrames(session, link) }
        // One writer drains the outbox to the link, so the primary's notification
        // path (onInbound) and request handlers never block on the socket. When
        // the outbox is CLOSED (a lag-drop, a revoke), the pump drains what's
        // queued, closes the LINK — with an authenticated REVOKED for a revoke —
        // so the peer actually observes the drop (its incoming() completes → its
        // reconnect machinery resyncs) — and ends the session.
        val pump = launch {
            for (frame in session.outbox) {
                // A lag-dropped or revoked peer gets nothing more.
                if (session.dropped || session.revoked) break
                link.send(frame)
            }
            runCatching { if (session.revoked) link.revoke() else link.close() }
            reader.cancel()
        }
        try {
            reader.join()
        } finally {
            clients.remove(session)
            session.closeOutbox()
            pump.cancel()
            // Belt-and-braces: make sure the socket dies with the session even
            // when the collect ended abnormally (the pump's drain-then-close
            // covers the outbox-close paths). Bounded, so a stuck flush can't
            // hold this coroutine past the server's stop.
            withContext(NonCancellable) {
                withTimeoutOrNull(CLOSE_TIMEOUT_MS) { runCatching { if (session.revoked) link.revoke() else link.close() } }
            }
            onClientsChanged()
        }
    }

    /** The session's read loop: requests from an admitted remote. */
    private suspend fun readFrames(session: ClientSession, link: FrameLink): Unit = coroutineScope {
        // This session's controls run one at a time, in the order they
        // arrived: a setting then a Start must not swap. Off the read loop, so
        // reads aren't held up behind a control.
        val controls = Channel<Frame.Control>(CONTROL_QUEUE_CAPACITY)
        launch { for (frame in controls) handleControl(session, frame) }
        try {
            link.incoming().collect { frame ->
                // A revoked session serves nothing more (its link is closing).
                if (!session.approved) {
                    Log.w(TAG, "Refusing ${frame::class.simpleName} from a revoked session")
                    refuseUnapproved(session, frame)
                    return@collect
                }
                // Reads are suspend (they hit the real link): run them off the
                // collect loop so they don't head-of-line-block later frames.
                // Controls join the session's ordered queue.
                when (frame) {
                    is Frame.Read -> launch { handleRead(session, frame) }
                    is Frame.Control ->
                        if (!controls.trySend(frame).isSuccess) deliver(session, Frame.ControlErr(frame.id, CONTROLS_BUSY))
                    else -> handle(session, frame)
                }
            }
        } finally {
            controls.close()
        }
    }

    /** Reply to a request from a session that was revoked mid-flight. Every
     *  request gets exactly one reply, so the peer fails at once instead of
     *  waiting out its timeout. Nothing is served. */
    private fun refuseUnapproved(session: ClientSession, frame: Frame) {
        val reply = when (frame) {
            is Frame.Read -> Frame.ReadErr(frame.id, NOT_PAIRED)
            is Frame.Write -> Frame.WriteErr(frame.id, NOT_PAIRED)
            is Frame.Control -> Frame.ControlErr(frame.id, NOT_PAIRED)
            // The protocol has no attach error frame; a secondary completes a
            // pending request on any reply that carries its id.
            is Frame.Attach -> Frame.ControlErr(frame.id, NOT_PAIRED)
            // Nothing is attached, so a detach is trivially done.
            is Frame.Detach -> Frame.Detached(frame.id, frame.address)
            else -> null
        }
        if (reply != null) deliver(session, reply)
    }

    private suspend fun handle(session: ClientSession, frame: Frame) {
        when (frame) {
            is Frame.Attach -> {
                // A (re-)attach means the peer's core is converging afresh: clear
                // its per-char seq marks for this device FIRST so the snapshot
                // burst is delivered even for chars it saw in a previous attach.
                // On a re-attach the session is already attached, so live frames
                // CAN interleave with this. That is safe: the cache only ever
                // moves forward and onInbound updates it before delivering, so
                // after the clear the burst can repeat the newest value but never
                // enqueue one older than a frame already delivered.
                val prefix = frame.address + "|"
                session.seenSeq.keys.removeIf { it.startsWith(prefix) }
                session.attach(frame.address)
                // A device the hub has never seen a state change for is NOT
                // assumed connected: fall back to the roster's live state (the
                // primary's transport truth), and only then to an honest
                // DISCONNECTED — never a fabricated CONNECTED the State stream
                // would have to walk back.
                val state = connStates[frame.address]
                    ?: roster().firstOrNull { it.address == frame.address }?.state
                    ?: BleTransport.ConnState.DISCONNECTED.name
                deliver(session, Frame.Attached(frame.id, frame.address, state))
                // Snapshot burst: replay every cached state/identity char for this
                // device so the secondary converges to current machine state. The
                // per-client seq guard in [deliverNotify] makes this race-proof
                // against concurrent live fan-out: a cached value older than one
                // already delivered live is skipped, never regressing the mirror.
                for ((key, cached) in cache) if (key.startsWith(prefix)) {
                    deliverNotify(session, key, cached.seq, cached.frame)
                }
                // Config snapshot: the secondary snaps to the primary's session
                // config (active profile/bean, SAW, QC, units) — the drift fix.
                configSource().takeIf { it.isNotEmpty() }?.let { deliver(session, Frame.Config(it)) }
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
     *  and reply [Frame.ControlOk] (accepted) / [Frame.ControlErr] (refused, with
     *  the reason). The action drives the primary's real link; its effect
     *  returns to every secondary as machine state over the [Frame.Notify] stream. */
    private suspend fun handleControl(session: ClientSession, frame: Frame.Control) {
        // Scope gate (issue 02): a view-only (mirror-only) peer may observe but not
        // drive — refuse every Control frame it sends, config verbs included.
        if (!session.canControl) {
            Log.i(TAG, "control refused (mirror-only peer): ${frame.method}")
            deliver(session, Frame.ControlErr(frame.id, "not authorized"))
            return
        }
        Log.i(TAG, "relayed control: ${frame.method}${if (frame.args.isEmpty()) "" else " (${frame.args})"}")
        val reply = controlHandler(frame.method, frame.args, session.clientId, session.clientName).fold(
            onSuccess = { Frame.ControlOk(frame.id) },
            onFailure = { Frame.ControlErr(frame.id, it.message ?: "control failed") },
        )
        deliver(session, reply)
    }

    /**
     * Enqueue a [Frame.Notify] for [session] behind its per-char monotonic seq
     * guard: only a seq NEWER than the last one delivered for that char wins.
     * This closes the attach-time snapshot-vs-live race in BOTH orders — a
     * cached value can't be enqueued after a fresher live one (stale-regression),
     * and an equal-seq replay of an already-delivered live frame is skipped
     * (no duplicate). Counted sample streams pass untouched: their seq is
     * strictly increasing per char and they are never snapshotted.
     */
    private fun deliverNotify(session: ClientSession, key: String, seq: Long, frame: Frame.Notify) {
        if (session.isClosed) return
        var overflow = false
        // The check and the enqueue happen together, under the map's lock for
        // this key, so two threads can't pass the check and then enqueue in the
        // opposite order.
        session.seenSeq.compute(key) { _, cur ->
            if (cur == null || seq > cur) {
                if (!session.offer(frame)) overflow = true
                seq
            } else {
                cur
            }
        }
        if (overflow) dropLagging(session)
    }

    /** Enqueue a frame for [session]. If its outbox is full (a lagging client on a
     *  bounded queue), drop the client — it reconnects and resyncs via snapshot,
     *  rather than stalling the primary. Closing the outbox ends the pump, which
     *  drains what's queued and then closes the LINK (see [serve]) so the peer
     *  actually observes the drop. */
    private fun deliver(session: ClientSession, frame: Frame) {
        // A session already closed (denied, dropped, ending) gets nothing more.
        if (session.isClosed) return
        if (!session.offer(frame)) dropLagging(session)
    }

    /** The peer stopped keeping up: end its session now (the pump skips the
     *  backlog and closes the link). */
    private fun dropLagging(session: ClientSession) {
        if (session.dropped) return
        session.dropped = true
        Log.w(TAG, "Client outbox full — dropping client (will resync on reconnect)")
        session.closeOutbox()
    }

    private fun cacheKey(address: String, char: UUID): String = "$address|$char"

    /** One connected secondary: its outbox and the set of devices it has attached. */
    private class ClientSession(val link: FrameLink, capacity: Int) {
        val outbox = Channel<Frame>(capacity)
        /** Dropped for lagging: the pump sends nothing more. */
        @Volatile var dropped: Boolean = false
        /** The outbox is closed: the session is ending and gets nothing more. */
        @Volatile var isClosed: Boolean = false
            private set
        fun closeOutbox() {
            isClosed = true
            outbox.close()
        }
        /** The session is served: set at admission, cleared by a revoke. The
         *  fan-out methods skip a session that isn't. */
        @Volatile var approved: Boolean = false
        /** The scope stored with the peer's pin: false = view-only mirror. */
        @Volatile var canControl: Boolean = false
        /** The peer's device id and pinned name, for the hub's Remotes list. */
        @Volatile var clientId: String? = null
        @Volatile var clientName: String? = null
        /** The hub forgot this device: the link closes with `CLOSE(REVOKED)`. */
        @Volatile var revoked: Boolean = false
        /** Highest notify seq delivered per `"address|char"` — the monotonic guard
         *  behind [deliverNotify]; cleared per device on (re-)attach. */
        val seenSeq = ConcurrentHashMap<String, Long>()
        private val attached = ConcurrentHashMap.newKeySet<String>()
        fun isAttached(address: String): Boolean = attached.contains(address)
        fun attach(address: String) { attached.add(address) }
        fun detach(address: String) { attached.remove(address) }
        fun offer(frame: Frame): Boolean = outbox.trySend(frame).isSuccess
    }

    private companion object {
        const val TAG = "RelayHub"
        const val AUTHORITY_PRIMARY = "primary"
        const val NOT_PAIRED = DenyReason.NOT_PAIRED
        const val CLOSE_TIMEOUT_MS = 1_000L
        const val DEFAULT_CLIENT_CAPACITY = 1024
        const val MAX_SESSIONS = 8
        /** Controls one session may have waiting; more are refused. */
        const val CONTROL_QUEUE_CAPACITY = 64
        const val CONTROLS_BUSY = "too many controls waiting"
    }
}
