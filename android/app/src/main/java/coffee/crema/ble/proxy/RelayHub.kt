package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.ble.BleTransport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The host's verdict on a secondary's pairing request (issue 02): either
 * [Denied] (no mirror) or [Allowed] with a capability scope — `canControl=false`
 * is a view-only mirror whose `Control`/`Handoff` are refused. [Allowed.issuedSecret]
 * is set when this decision completes a new pairing; it rides the Welcome.
 */
sealed class PairingDecision {
    data class Allowed(val canControl: Boolean, val issuedSecret: String? = null) : PairingDecision()
    data class Denied(val reason: String) : PairingDecision()
}

/** A secondary currently mirroring this primary, as a **push-handoff** target
 *  (issue 07) — the primary's picker lists these so it can "hand off to <name>". */
data class HandoffTarget(val clientId: String, val clientName: String)

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
     * Per-client outbox capacity. Bounded, so a client that stops reading is
     * dropped (its link closes; it resyncs on reconnect) instead of growing the
     * primary's heap. Tests that need a tiny or unlimited outbox pass one.
     */
    private val clientCapacity: Int = DEFAULT_CLIENT_CAPACITY,
    /**
     * Dispatch a relayed user-intent [Frame.Control] from a secondary to the
     * primary's command router (the VM's machine-control methods in production):
     * `success` once dispatched on the real link — the effect returns to the
     * secondary over the [Frame.Notify] stream — `failure` to reply
     * [Frame.ControlErr]. The default rejects every control; the real handler is
     * wired only on the primary (a normal/loopback relay has no machine to
     * drive), so M1 and the unit tests keep a read-only mirror by construction.
     */
    private val controlHandler: suspend (method: String, args: String, originId: String?, originName: String?) -> Result<Unit> =
        { _, _, _, _ -> Result.failure(UnsupportedOperationException("relay has no control handler")) },
    /**
     * The primary's current session-config JSON (an app-level `ConfigSnapshot`),
     * pushed to a secondary on attach and via [pushConfig] on change — the
     * single-owner config a mirror snaps to (the settings-drift fix). Empty
     * string = nothing to send (M1 / a normal relay / the unit tests).
     */
    private val configSource: () -> String = { "" },
    /**
     * Pairing authorization (issue 02): decide whether a connecting secondary may
     * mirror, and with what scope. Called once per session, after the peer
     * answered the [Frame.Challenge]; the [PairingRequest] checks the peer's
     * proof against a pairing secret. The real handler (on the primary) accepts
     * a valid proof for a paired device and otherwise prompts only while the
     * host's pairing window is open. The default **allows control** — it keeps
     * M1 / loopback / unit-test behaviour (no UI to prompt with); the production
     * primary always injects the gate.
     */
    private val authorize: suspend (request: PairingRequest) -> PairingDecision =
        { PairingDecision.Allowed(canControl = true) },
    /** Fired when a secondary attaches/detaches (issue 07) so the primary's UI can
     *  refresh its "hand off to <device>" list. Default no-op (tests / loopback). */
    private val onClientsChanged: () -> Unit = {},
    /** Fired with the peer's name when a secondary on another protocol version
     *  is turned away, so the host can say an update is needed. */
    private val onOutdatedPeer: (clientName: String) -> Unit = {},
    /** The pairing secret this host issued to a device, by client id, or null:
     *  the host proves it in the Challenge so the device can tell it's talking
     *  to the host that paired it. Default: none (tests / loopback). */
    private val pairingSecretFor: (clientId: String) -> String? = { null },
    /** At most this many sessions (approved or not) at once; more are refused. */
    private val maxSessions: Int = MAX_SESSIONS,
    /** A session that hasn't answered the handshake ([Frame.Hello] then
     *  [Frame.Auth]) within this long is closed. The host's own prompt time
     *  doesn't count: the deadline stops once the Auth arrives. */
    private val handshakeDeadlineMs: Long = HANDSHAKE_DEADLINE_MS,
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

    /** Whether [clientId] currently has a live, approved session — the handoff
     *  release gate: the granting primary waits for the taker's disconnect (its
     *  mode switch closes the link) instead of a blind timer. A session that only
     *  claimed the id in its Hello doesn't count. */
    fun hasClient(clientId: String): Boolean = clients.any { it.approved && it.clientId == clientId }

    /** Bumped whenever a session joins, is approved or leaves. */
    private val clientsVersion = MutableStateFlow(0L)

    /** Wait until [clientId] has no live session, at most [timeoutMs]; true when
     *  it left in time. Event-driven: wakes when a session ends. */
    suspend fun awaitClientGone(clientId: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { clientsVersion.first { !hasClient(clientId) } } != null

    private fun clientsChanged() {
        clientsVersion.update { it + 1 }
        onClientsChanged()
    }

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

    /** Push the primary's current session config to every attached secondary —
     *  called when the primary's config changes, so mirrors stay in sync between
     *  attaches (the attach-time snapshot covers a fresh join). */
    fun pushConfig() {
        val json = configSource().takeIf { it.isNotEmpty() } ?: return
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

    /** Control-capable secondaries currently mirroring this primary — the
     *  "hand off to <device>" targets (issue 07). View-only peers are excluded:
     *  their take-over would be refused by the scope gate anyway. */
    fun handoffTargets(): List<HandoffTarget> =
        clients.mapNotNull { c ->
            val id = c.clientId
            if (id != null && c.canControl && !c.isClosed) HandoffTarget(id, c.clientName ?: id) else null
        }

    /** Push a [Frame.HandoffOffer] to a specific mirroring secondary (issue 07).
     *  The peer prompts and, on accept, runs its normal pull back at us. Returns
     *  false if no such client is connected. */
    fun offerHandoff(clientId: String): Boolean {
        val session = clients.firstOrNull { it.approved && it.clientId == clientId && !it.isClosed } ?: return false
        deliver(session, Frame.HandoffOffer(primaryName))
        return true
    }

    /** End every live session of [clientId] (the host forgot the device): it
     *  gets a [Frame.Denied] and its link closes; nothing else reaches it. */
    fun revoke(clientId: String) {
        for (c in clients) if (c.clientId == clientId) {
            c.approved = false
            c.canControl = false
            deliver(c, Frame.Denied(REVOKED))
            c.closeOutbox()
        }
        clientsChanged()
    }

    // ---- One client connection --------------------------------------------

    /** Serve one secondary for the life of its [link]. Returns when the link
     *  closes. The caller (WS server / loopback) provides one [FrameLink] per
     *  connection. */
    suspend fun serve(link: FrameLink): Unit = coroutineScope {
        val session = ClientSession(link, clientCapacity)
        val admitted = synchronized(clients) { (clients.size < maxSessions).also { if (it) clients += session } }
        if (!admitted) {
            Log.w(TAG, "Refusing a connection: $maxSessions sessions already open")
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                runCatching { link.send(Frame.Denied(BUSY)) }
                runCatching { link.close() }
            }
            return@coroutineScope
        }
        val reader = launch { readFrames(session, link) }
        // One writer drains the outbox to the link, so the primary's notification
        // path (onInbound) and request handlers never block on the socket. When
        // the outbox is CLOSED (a lag-drop, a denial, a revoke, a missed
        // handshake deadline), the pump drains what's queued, closes the LINK —
        // so the peer actually observes the drop (its incoming() completes → its
        // reconnect machinery resyncs) — and ends the session.
        val pump = launch {
            for (frame in session.outbox) {
                // A lag-dropped peer resyncs from a snapshot anyway: don't trickle
                // its backlog out at the slow peer's pace first.
                if (session.dropped) break
                link.send(frame)
            }
            runCatching { link.close() }
            reader.cancel()
        }
        val deadline = launch {
            delay(handshakeDeadlineMs)
            if (!session.authReceived) {
                Log.i(TAG, "Closing a session that didn't complete the handshake in ${handshakeDeadlineMs}ms")
                session.closeOutbox()
            }
        }
        try {
            reader.join()
        } finally {
            deadline.cancel()
            clients.remove(session)
            session.closeOutbox()
            pump.cancel()
            // Belt-and-braces: make sure the socket dies with the session even
            // when the collect ended abnormally (the pump's drain-then-close
            // covers the outbox-close paths). Bounded, so a stuck flush can't
            // hold this coroutine past the server's stop.
            withContext(NonCancellable) { withTimeoutOrNull(CLOSE_TIMEOUT_MS) { runCatching { link.close() } } }
            clientsChanged()
        }
    }

    /** The session's read loop: handshake frames inline, then requests. */
    private suspend fun readFrames(session: ClientSession, link: FrameLink): Unit = coroutineScope {
        link.incoming().collect { frame ->
            // The handshake is handled INLINE (suspending the collect loop) so
            // the pairing decision lands before any later frame from this
            // client is served (issue 02).
            if (frame is Frame.Hello) { handleHello(session, frame); return@collect }
            if (frame is Frame.Auth) { handleAuth(session, frame); return@collect }
            // Pre-pairing gate (issue 03): until a Hello has been APPROVED, an
            // unapproved peer may not attach, read, or observe. A peer that skips
            // Hello (or was denied) gets no attach, snapshot, read or stream —
            // only an error reply to each request, so it fails fast.
            if (!session.approved) {
                Log.w(TAG, "Refusing ${frame::class.simpleName} from unapproved peer (no approved Hello)")
                refuseUnapproved(session, frame)
                return@collect
            }
            // Reads and controls are suspend (they hit the real link / drive the
            // machine); run them off the collect loop so they don't head-of-line-
            // block later frames.
            when (frame) {
                is Frame.Read -> launch { handleRead(session, frame) }
                is Frame.Control -> launch { handleControl(session, frame) }
                else -> handle(session, frame)
            }
        }
    }

    /** First handshake step: check the version, then challenge the peer. The
     *  session is not approved until its [Frame.Auth] passes [authorize]. */
    private fun handleHello(session: ClientSession, frame: Frame.Hello) {
        // A repeated Hello (a reconnect race can send two on one session) must not
        // restart the handshake: one challenge per session, one decision.
        if (session.approved || session.nonce != null) {
            Log.w(TAG, "Ignoring repeated Hello from ${frame.clientName}")
            return
        }
        // Refuse a protocol-version skew up front (issue 05): ignoreUnknownKeys
        // already absorbs additive changes, so a different PROXY_PROTOCOL_VERSION
        // means a structurally breaking change — deny rather than risk mis-decoding a
        // later frame. (Secondaries do the mirror check on Frame.Welcome.v.)
        if (frame.v != PROXY_PROTOCOL_VERSION) {
            Log.i(TAG, "denied ${frame.clientName}: protocol v${frame.v} != v$PROXY_PROTOCOL_VERSION")
            onOutdatedPeer(sanitizePeerName(frame.clientName))
            deliver(session, Frame.Denied(VERSION_MISMATCH))
            session.closeOutbox()
            return
        }
        session.clientId = frame.clientId
        session.clientName = sanitizePeerName(frame.clientName)
        val clientNonce = frame.nonce.orEmpty()
        session.clientNonce = clientNonce
        val nonce = PairingCrypto.newNonce()
        session.nonce = nonce
        val hostProof = frame.nonce?.let { cn ->
            pairingSecretFor(frame.clientId)?.let { secret ->
                runCatching { PairingCrypto.hostProof(secret, primaryId, frame.clientId, cn, nonce) }.getOrNull()
            }
        }
        deliver(session, Frame.Challenge(primaryId, primaryName, nonce, hostProof))
    }

    /** Second handshake step: the host decides on the peer's proof, then
     *  Welcome (with its granted scope, and a new secret if it just paired) or
     *  Denied. On denial the session ends — an unapproved client gets no roster,
     *  snapshot, or stream. */
    private suspend fun handleAuth(session: ClientSession, frame: Frame.Auth) {
        val nonce = session.nonce
        val clientId = session.clientId
        if (session.approved || session.authReceived || nonce == null || clientId == null) {
            Log.w(TAG, "Ignoring Auth outside the handshake")
            return
        }
        session.authReceived = true
        val name = session.clientName ?: clientId
        val clientNonce = session.clientNonce.orEmpty()
        val request = PairingRequest(clientId, name, hasProof = frame.proof != null) { secret ->
            PairingCrypto.verifyClient(secret, primaryId, clientId, clientNonce, nonce, frame.proof)
        }
        when (val decision = authorize(request)) {
            is PairingDecision.Allowed -> {
                session.approved = true
                session.canControl = decision.canControl
                val scope = if (decision.canControl) SCOPE_CONTROL else SCOPE_MIRROR
                deliver(
                    session,
                    Frame.Welcome(PROXY_PROTOCOL_VERSION, primaryId, primaryName, AUTHORITY_PRIMARY, roster(), scope, decision.issuedSecret),
                )
                clientsChanged() // identity known → the primary's hand-off list can list it
            }
            is PairingDecision.Denied -> {
                Log.i(TAG, "pairing denied for $name ($clientId): ${decision.reason}")
                deliver(session, Frame.Denied(decision.reason))
                session.closeOutbox()
            }
        }
    }

    /** Reply to a request from a peer without an approved Hello. Every request
     *  gets exactly one reply, in every state, so a peer that sent early fails
     *  at once instead of waiting out its timeout. Nothing is served. */
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

    private fun handle(session: ClientSession, frame: Frame) {
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
     *  and reply [Frame.ControlOk] / [Frame.ControlErr]. The action drives the
     *  primary's real link; its effect returns to every secondary as machine
     *  state over the [Frame.Notify] stream, so the reply only confirms dispatch. */
    private suspend fun handleControl(session: ClientSession, frame: Frame.Control) {
        // Scope gate (issue 02): a view-only (mirror-only) peer may observe but not
        // drive — refuse every Control/Handoff/config verb it sends.
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
    private class ClientSession(@Suppress("unused") val link: FrameLink, capacity: Int) {
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
        /** Set true once this peer's Hello has been APPROVED (issue 03). Until then it
         *  may not attach, read, or observe, and the fan-out methods skip it — a peer
         *  that never sent (or was denied) a Hello gets no roster, snapshot, or stream. */
        @Volatile var approved: Boolean = false
        /** Granted control scope (issue 02): false = view-only mirror, set on Welcome. */
        @Volatile var canControl: Boolean = false
        /** Peer identity from [Frame.Hello], for push-handoff targeting (issue 07). */
        @Volatile var clientId: String? = null
        @Volatile var clientName: String? = null
        /** This session's challenge nonce, set when the Hello is answered. */
        @Volatile var nonce: String? = null
        /** The nonce the peer sent in its Hello. */
        @Volatile var clientNonce: String? = null
        /** The peer answered the challenge (stops the handshake deadline). */
        @Volatile var authReceived: Boolean = false
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
        const val SCOPE_CONTROL = "control"
        const val SCOPE_MIRROR = "mirror"
        const val NOT_PAIRED = DenyReason.NOT_PAIRED
        const val CLOSE_TIMEOUT_MS = 1_000L
        const val BUSY = DenyReason.BUSY
        const val REVOKED = DenyReason.REVOKED
        const val VERSION_MISMATCH = DenyReason.VERSION_MISMATCH
        const val DEFAULT_CLIENT_CAPACITY = 1024
        const val MAX_SESSIONS = 8
        const val HANDSHAKE_DEADLINE_MS = 5_000L
    }
}
