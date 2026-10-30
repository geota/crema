package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.core.RelayCloseReason
import coffee.crema.core.RelayEvent
import coffee.crema.core.RelayFfiException
import coffee.crema.core.RelayHubConnection
import coffee.crema.core.RelayKeys
import coffee.crema.core.RelayPairingGate
import coffee.crema.core.RelayPinSet
import coffee.crema.core.relayDeviceId
import io.ktor.websocket.WebSocketSession
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * What the hub's pairing ceremony and admission need from the app (the
 * [coffee.crema.ui.ProxyController] implements it): the process-wide key,
 * gate and live pin set, the pin store, and the UI hooks.
 */
interface HubTrust {
    val keys: RelayKeys
    val gate: RelayPairingGate
    val pins: RelayPinSet

    /** This hub's display name (the ServerHello's). */
    val hubName: String

    /** A remote asks to pair: show the code dialog for [request]. */
    fun onPairRequest(request: HubPairingRequest)

    /**
     * The code matched (`PairPending`): store the pin `{remoteId, key, name,
     * scope}`. Returns the record it replaced (a re-pair) and whether the store
     * wrote it, or null when it couldn't be stored at all.
     */
    fun storePin(remoteId: String, remoteName: String, publicKey: ByteArray, canControl: Boolean): PinWrite?

    /** Put back what [storePin] replaced: [previous], or nothing (unpin [remoteId]). */
    fun restorePin(remoteId: String, previous: RelayRemotePin?)

    /** A pairing attempt on [request] ended without success. */
    fun onPairingFailed(request: HubPairingRequest, reason: String, failures: Int, locked: Boolean)

    /** The pin couldn't be stored: `reject_pairing` ran. */
    fun onPairingNotSaved(request: HubPairingRequest)

    /** Pairing completed for [request]. */
    fun onPaired(request: HubPairingRequest)

    /** A v2/v3 remote got the legacy denial. */
    fun onLegacyPeer(name: String)

    /** The pinned remote with this device id, as served, or null when it isn't pinned. */
    fun peerFor(deviceId: String): RemotePeer?

    /** A pinned remote was admitted (writes its last-seen time). */
    fun onAdmitted(peer: RemotePeer)

    /** Whether the hub user turned [address] away in this window (the squat rule, spec §2.5). */
    fun isTurnedAway(address: String): Boolean
}

/** What [HubTrust.storePin] did. */
data class PinWrite(val previous: RelayRemotePin?, val saved: Boolean)

/**
 * One pairing request on the hub, between `PairRequest` and the attempt's end:
 * the code dialog's handle. [enterCode] and [cancel] run on the connection
 * that asked.
 */
class HubPairingRequest internal constructor(
    /** Distinguishes requests (a new connection is a new request). */
    val id: Long,
    /** The remote's claimed device id (unauthenticated until pairing completes). */
    val remoteId: String,
    /** The remote's display name, sanitized by the core. */
    val remoteName: String,
    /** The remote's address (the squat rule's key). */
    val address: String,
    private val session: SecureRelayAcceptor.Session,
) {
    /** The first 8 hex characters of [remoteId], as the dialog shows it. */
    val shortId: String get() = remoteId.take(8)

    /**
     * The hub user typed [code] and chose a scope. False when the core refused
     * it without starting SPAKE2 (not six digits) or the attempt already ended.
     */
    suspend fun enterCode(code: String, canControl: Boolean): Boolean = session.enterCode(code, canControl)

    /** The hub user cancelled: `ABORT(CANCELLED)`, no failure counted. */
    suspend fun cancel() = session.cancelPairing()

    /** Tick the connection now (the window was just closed: its abort goes out at once, spec §2.5). */
    suspend fun tick() = session.poke()
}

/**
 * The hub's side of the relay v4 transport (spec §8 PR-3b): one
 * [RelayHubConnection] per accepted WebSocket, driven by a [RelayWire], with
 * the acceptor's limits (spec §2.5, AC-33, AC-55):
 *
 * - at most [maxSessions] sessions in all, of which at most [maxUnauth]
 *   unauthenticated, and at most [maxUnauthPerAddress] per remote address;
 * - when a cap is full, a new connection **evicts the oldest unauthenticated
 *   session** (close 4408) instead of being refused, so a flood can't keep a
 *   real resume out; a session waiting for code entry is never evicted (when
 *   nothing can be evicted, the new connection is refused with 4408);
 * - 5 s to send a ClientHello (the core's deadline, 4408);
 * - a remote address the hub user turned away gets `ServerHello(BUSY)` for
 *   `intent=pair` for the rest of the window ([HubTrust.isTurnedAway]).
 *
 * The pairing commit follows spec §2.4 step 9: on `PairPending` the pin is
 * stored, inserted into the live pin set, then `confirm_paired()`; anything but
 * `Ok` restores the prior state; a store that couldn't write gets
 * `reject_pairing()`. An admitted session becomes a [NoiseFrameLink] served by
 * [hub] with the scope stored at pairing.
 */
class SecureRelayAcceptor(
    private val hub: RelayHub,
    private val trust: HubTrust,
    private val maxSessions: Int = MAX_SESSIONS,
    private val maxUnauth: Int = MAX_UNAUTH,
    private val maxUnauthPerAddress: Int = MAX_UNAUTH_PER_ADDRESS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Serves one admitted session: [RelayHub.serve] (tests record or script the link). */
    private val serve: suspend (link: FrameLink, peer: RemotePeer) -> Unit = { link, peer ->
        // Checked again once the session is listed: a Forget that ran between
        // the admission check and here missed it in its revoke loop.
        hub.serve(link, peer, stillTrusted = { trust.peerFor(peer.deviceId) != null })
    },
) {
    private val sessions = CopyOnWriteArrayList<Session>()

    /** Sessions currently held (tests wait on it instead of sleeping). */
    @androidx.annotation.VisibleForTesting
    internal val sessionCount: Int get() = sessions.size
    private val seq = AtomicLong(0)

    /** Serve one accepted WebSocket from [address]; returns when it ends. */
    suspend fun accept(ws: WebSocketSession, address: String): Unit = coroutineScope {
        val core = try {
            RelayHubConnection(trust.keys, trust.gate, trust.pins, trust.hubName, clock().toULong())
        } catch (e: RelayFfiException) {
            Log.w(TAG, "Couldn't start a relay connection: ${RelayWire.kindOf(e)}")
            return@coroutineScope
        }
        val session = Session(seq.incrementAndGet(), address, this@SecureRelayAcceptor, this)
        val wire = RelayWire(ws, HubConn(core), this, session, clock)
        session.wire = wire
        if (!admit(session)) {
            Log.w(TAG, "Refusing a relay connection: no session can make room")
            wire.abort(CLOSE_TIMEOUT, "busy")
            wire.shutdown()
            return@coroutineScope
        }
        // The squat rule: before the ClientHello, so a pair hello gets BUSY.
        if (trust.isTurnedAway(address)) core.refusePairingRequests()
        // The ClientHello deadline runs from the accept, before any message.
        wire.arm()
        try {
            wire.pump()
        } finally {
            sessions.remove(session)
        }
    }

    /** Close every session because hosting stops: established ones get an authenticated `CLOSE(HUB_STOPPED)`. */
    suspend fun closeAll() {
        for (s in sessions) {
            val link = s.link
            if (link != null) link.closeWith(RelayCloseReason.HUB_STOPPED) else s.wire?.abort(CLOSE_GOING_AWAY, "hub stopped")
        }
    }

    /** Register [session] under the caps, evicting the oldest unauthenticated session when needed. */
    private suspend fun admit(session: Session): Boolean {
        val victims = mutableListOf<Session>()
        val ok = synchronized(sessions) {
            val unauth = sessions.filter { !it.established }
            fun evictable(from: List<Session>) = from.filterNot { it.awaitingCode || it in victims }.minByOrNull { it.id }
            val sameAddress = unauth.filter { it.address == session.address }
            if (sameAddress.size >= maxUnauthPerAddress) evictable(sameAddress)?.let { victims += it } ?: return@synchronized false
            if (unauth.size - victims.size >= maxUnauth) evictable(unauth)?.let { victims += it } ?: return@synchronized false
            if (sessions.size - victims.size >= maxSessions) evictable(unauth)?.let { victims += it } ?: return@synchronized false
            sessions.removeAll(victims.toSet())
            sessions += session
            true
        }
        for (v in victims) {
            Log.i(TAG, "Evicting the oldest unauthenticated relay session to make room")
            v.wire?.abort(CLOSE_TIMEOUT, "evicted")
        }
        return ok
    }

    /** One accepted WebSocket: its wire, pairing state and, once admitted, its link. */
    class Session internal constructor(
        internal val id: Long,
        internal val address: String,
        private val acceptor: SecureRelayAcceptor,
        /** The accepting connection's scope: an admitted session is served in it. */
        private val scope: CoroutineScope,
    ) : RelayWire.Listener {
        internal var wire: RelayWire? = null

        /** Admitted (the client-finished record decrypted): no longer evictable or unauthenticated. */
        @Volatile internal var established = false

        /** A `PairRequest` is waiting for the hub user's code: never evicted. */
        @Volatile internal var awaitingCode = false

        /** The open request, from `PairRequest` until the attempt ends. */
        @Volatile private var request: HubPairingRequest? = null

        /** The scope the hub user chose with the code. */
        @Volatile private var canControl = true

        /** The admitted session's link. */
        @Volatile internal var link: NoiseFrameLink? = null

        private val trust get() = acceptor.trust

        internal suspend fun enterCode(code: String, canControl: Boolean): Boolean {
            val w = wire ?: return false
            this.canControl = canControl
            val sent = try {
                w.callChecked { (this as HubConn).core.enterCode(code, it) }
            } catch (e: RelayFfiException) {
                // CodeFormat changes nothing; an ended attempt returns its Step on the next tick.
                Log.i(TAG, "code entry refused: ${RelayWire.kindOf(e)}")
                w.call { tick(it) }
                return false
            }
            if (sent) awaitingCode = false
            return sent
        }

        internal suspend fun poke() {
            wire?.call { tick(it) }
        }

        internal suspend fun cancelPairing() {
            awaitingCode = false
            wire?.call { (this as HubConn).core.cancelPairing(it) }
        }

        override suspend fun onDeliver(message: ByteArray) {
            link?.deliver(message)
        }

        override suspend fun onEvents(events: List<RelayEvent>) {
            for (event in events) when (event) {
                is RelayEvent.PairRequest -> {
                    val r = HubPairingRequest(acceptor.seq.incrementAndGet(), event.content.remote_id, event.content.remote_name, address, this)
                    request = r
                    awaitingCode = true
                    trust.onPairRequest(r)
                }
                is RelayEvent.PairPending -> commitPairing(event.content.remote_id, event.content.remote_name)
                is RelayEvent.PairingFailed -> {
                    awaitingCode = false
                    request?.let { trust.onPairingFailed(it, event.content.reason, event.content.failures.toInt(), event.content.locked) }
                    request = null
                }
                is RelayEvent.Paired -> {
                    request?.let { trust.onPaired(it) }
                    request = null
                }
                is RelayEvent.LegacyPeer -> trust.onLegacyPeer(event.content.name)
                is RelayEvent.UnknownRemote -> Log.i(TAG, "An unpaired remote tried to resume; it got NOT_PAIRED")
                is RelayEvent.Established -> admitted()
                else -> Log.i(TAG, "relay event ${RelayWire.nameOf(event)}")
            }
        }

        override fun onClosed(code: Int?) {
            awaitingCode = false
            link?.ended()
            val r = request
            request = null
            // A connection that ended mid-request (the remote went away) ends its dialog.
            if (r != null) trust.onPairingFailed(r, "Closed", 0, false)
        }

        /**
         * The two-phase commit (spec §2.4 step 9): store the pin, insert it into
         * the live pin set, then `confirm_paired()`. Only `Ok` keeps the new
         * record; anything else restores the prior state. A store that couldn't
         * write gets `reject_pairing()` (nothing pinned, no failure counted).
         */
        private suspend fun commitPairing(remoteId: String, remoteName: String) {
            val w = wire ?: return
            val core = { c: RelayConn -> (c as HubConn).core }
            val key = w.peek { peerPublicKey() } ?: return
            val r = request
            val write = trust.storePin(remoteId, remoteName, key, canControl)
            if (write == null || !write.saved) {
                write?.let { trust.restorePin(remoteId, it.previous) }
                // The hub user hears it before the remote does.
                r?.let { trust.onPairingNotSaved(it) }
                w.call { core(this).rejectPairing(it) }
                request = null
                return
            }
            val isNew = try {
                trust.pins.insert(key)
            } catch (e: RelayFfiException) {
                Log.w(TAG, "pin set refused the key: ${RelayWire.kindOf(e)}")
                trust.restorePin(remoteId, write.previous)
                r?.let { trust.onPairingNotSaved(it) }
                w.call { core(this).rejectPairing(it) }
                request = null
                return
            }
            val confirmed = try {
                w.callChecked { core(this).confirmPaired(it) }
            } catch (e: RelayFfiException) {
                Log.i(TAG, "confirm_paired refused: ${RelayWire.kindOf(e)} — restoring the prior pin state")
                false
            }
            if (confirmed) return
            // Restore what was there before: a new key leaves the live set and
            // the store; a re-pair keeps the key pinned with its old record.
            if (isNew) runCatching { trust.pins.remove(key) }
            trust.restorePin(remoteId, write.previous)
            // The call that ended the attempt held its Step for the next call.
            w.call { tick(it) }
        }

        /** The remote's first record decrypted: serve it with its stored scope. */
        private suspend fun admitted() {
            val w = wire ?: return
            val key = w.peek { peerPublicKey() } ?: return
            val deviceId = runCatching { relayDeviceId(key) }.getOrNull()
            val peer = deviceId?.let { trust.peerFor(it) }
            val l = NoiseFrameLink(w)
            link = l
            established = true
            if (peer == null) {
                // Forgotten between the core's check and here: never serve it.
                l.revoke()
                return
            }
            trust.onAdmitted(peer)
            scope.launch { acceptor.serve(l, peer) }
        }
    }

    companion object {
        private const val TAG = "SecureRelayAcceptor"
        const val MAX_SESSIONS = 8
        const val MAX_UNAUTH = 4
        const val MAX_UNAUTH_PER_ADDRESS = 2

        /** Eviction, a refused connection and a missed deadline (spec §4.5). */
        const val CLOSE_TIMEOUT = 4408
        private const val CLOSE_GOING_AWAY = 1001
    }
}
