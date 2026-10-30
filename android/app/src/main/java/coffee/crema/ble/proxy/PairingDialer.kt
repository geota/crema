package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.core.RelayCloseReason
import coffee.crema.core.RelayEvent
import coffee.crema.core.RelayFfiException
import coffee.crema.core.RelayKeys
import coffee.crema.core.RelayRemoteConnection
import coffee.crema.net.HttpClients
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a remote's pairing with a hub shows (spec §6.1); the code sheet renders it. */
sealed interface PairingState {
    /** Nothing in progress. */
    data object Idle : PairingState

    /** Dialing, or waiting for the hub's `AWAITING_CODE`: no code is shown yet. */
    data class Waiting(val hubName: String, val attempt: Int, val retryAfterWrongCode: Boolean) : PairingState

    /**
     * Show [code] until [expiresAtMs] (wall clock). The code lives only here and
     * in the core: it is never logged or put in a notification.
     */
    data class ShowCode(val hubName: String, val code: String, val expiresAtMs: Long, val attempt: Int, val retryAfterWrongCode: Boolean) : PairingState {
        override fun toString(): String = "ShowCode(hubName=$hubName, code=[redacted], attempt=$attempt)"
    }

    /** Every attempt of this tap failed (wrong code each time): "Try again". */
    data class Failed(val hubName: String) : PairingState

    /** The hub refused (advisory, spec §6.1's table): [reason] is the core's name. No auto-retry. */
    data class Refused(val hubName: String, val reason: String) : PairingState

    /** Paired; the hub pin is stored. The caller starts the resume. */
    data class Paired(val hubName: String) : PairingState
}

/**
 * The remote's pairing with one hub (spec §2.4, §2.5, §8 PR-3b): one
 * WebSocket per attempt running a [RelayRemoteConnection.pair], and up to
 * [maxAttempts] automatic attempts (each a new connection and a new code) per
 * user tap. Started only by a user action; a resume never pairs.
 *
 * A `PairingFailed` without a `Refused` in the same Step, other than
 * `Timeout` (the code expired), is a failed attempt and retries; a `Refused`
 * or a `Timeout` stops (PROTOCOL.md §10). A connection that drops without
 * either is a failed attempt too. On `Paired` the hub's pin is stored through
 * [storePin] before the state says [PairingState.Paired], so the resume that
 * follows always finds it.
 */
class PairingDialer(
    private val url: String,
    /** What to call the hub until its ServerHello names it (the picker's name or the host). */
    private val hubLabel: String,
    private val keys: RelayKeys,
    /** This device's name, sent in the ClientHello. */
    private val myName: String,
    private val scope: CoroutineScope,
    /** Store the hub pin; true when stored (a failure ends the pairing, nothing half-trusted). */
    private val storePin: suspend (hubId: String, hubName: String, hubKey: ByteArray) -> Boolean,
    private val client: HttpClient = HttpClients.webSocketClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = MAX_PAIR_ATTEMPTS,
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile private var wire: RelayWire? = null

    /** Start (or restart, after a stop) the attempts of one user tap. */
    fun start() {
        job?.cancel()
        job = scope.launch {
            var hubName = hubLabel
            var retry = false
            for (attempt in 1..maxAttempts) {
                _state.value = PairingState.Waiting(hubName, attempt, retry)
                val outcome = try {
                    attempt(attempt, retry) { hubName = it }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Not a failed code: the hub isn't there. Say so instead of retrying.
                    Log.i(TAG, "Pairing attempt $attempt couldn't reach the hub: ${e.javaClass.simpleName}")
                    Outcome.Stop(UNREACHABLE)
                }
                when (outcome) {
                    Outcome.Paired -> {
                        _state.value = PairingState.Paired(hubName)
                        return@launch
                    }
                    is Outcome.Stop -> {
                        _state.value = PairingState.Refused(hubName, outcome.reason)
                        return@launch
                    }
                    Outcome.Retry -> retry = true
                }
            }
            _state.value = PairingState.Failed(hubName)
        }
    }

    /** Stop: a running attempt sends `ABORT(CANCELLED)`; the state goes back to idle. */
    fun cancel() {
        val w = wire
        val j = job
        job = null
        scope.launch {
            w?.call { closeSession(RelayCloseReason.NORMAL, it) }
            j?.cancel()
        }
        _state.value = PairingState.Idle
    }

    private sealed interface Outcome {
        data object Paired : Outcome
        data object Retry : Outcome
        data class Stop(val reason: String) : Outcome
    }

    /** One connection: returns how it ended. */
    private suspend fun attempt(attempt: Int, retry: Boolean, named: (String) -> Unit): Outcome = coroutineScope {
        val ws = client.webSocketSession { url(this@PairingDialer.url) }
        val core = try {
            RelayRemoteConnection.pair(keys, myName, clock().toULong())
        } catch (e: RelayFfiException) {
            Log.w(TAG, "Couldn't start pairing: ${RelayWire.kindOf(e)}")
            ws.cancel()
            return@coroutineScope Outcome.Stop("Failed")
        }
        var hubName = hubLabel
        var outcome: Outcome = Outcome.Retry
        var decided = false
        lateinit var w: RelayWire
        val listener = object : RelayWire.Listener {
            override suspend fun onEvents(events: List<RelayEvent>) {
                if (decided) return
                val refused = events.filterIsInstance<RelayEvent.Refused>().firstOrNull()
                for (event in events) when (event) {
                    is RelayEvent.HubHello -> {
                        hubName = event.content.hub_name
                        named(hubName)
                        _state.value = PairingState.Waiting(hubName, attempt, retry)
                    }
                    is RelayEvent.ShowCode -> {
                        val code = w.peek { (this as RemoteConn).core.code() }
                        if (code != null) {
                            // The core's clock is [clock]: the expiry is a wall-clock time.
                            _state.value = PairingState.ShowCode(hubName, code, event.content.expires_at_ms, attempt, retry)
                        }
                    }
                    is RelayEvent.Paired -> {
                        val key = w.peek { peerPublicKey() }
                        decided = true
                        outcome = if (key != null && storePin(event.content.peer_id, event.content.peer_name, key)) {
                            Outcome.Paired
                        } else {
                            Log.w(TAG, "Paired, but the hub pin couldn't be stored")
                            Outcome.Stop("NotSaved")
                        }
                    }
                    is RelayEvent.PairingFailed -> {
                        decided = true
                        outcome = when {
                            refused != null -> Outcome.Stop(refused.content.reason)
                            event.content.reason == "Timeout" -> Outcome.Stop("Timeout")
                            else -> Outcome.Retry
                        }
                    }
                    is RelayEvent.Refused -> {
                        decided = true
                        outcome = Outcome.Stop(event.content.reason)
                    }
                    else -> Log.d(TAG, "relay event ${RelayWire.nameOf(event)}")
                }
            }
        }
        w = RelayWire(ws, RemoteConn(core), this, listener, clock)
        wire = w
        try {
            w.callChecked { (this as RemoteConn).core.start() }
            w.pump()
        } finally {
            wire = null
        }
        outcome
    }

    companion object {
        private const val TAG = "PairingDialer"

        /** Automatic attempts per user tap (spec Appendix A). */
        const val MAX_PAIR_ATTEMPTS = 3

        /** [PairingState.Refused.reason] when the hub couldn't be reached at all. */
        const val UNREACHABLE = "Unreachable"
    }
}
