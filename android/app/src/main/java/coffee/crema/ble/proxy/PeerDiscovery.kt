package coffee.crema.ble.proxy

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Another Crema instance on the LAN, discovered via NSD. */
data class Peer(
    /** Stable per-install id (the self-filter key). */
    val id: String,
    /** Display name (the device model). */
    val name: String,
    /** `"normal" | "primary" | "secondary"`. */
    val role: String,
    /** Whether this peer currently holds the real DE1 link. */
    val holdsDe1: Boolean,
    /** Resolved host address. */
    val host: String,
    /** The peer's relay port — connect a [ProxyTransport] here to mirror it. */
    val port: Int,
) {
    /** A peer you can "Mirror from": it's a primary holding the DE1 with a live relay. */
    val isMirrorSource: Boolean get() = role == "primary" && holdsDe1 && port > 0
}

/**
 * LAN peer discovery for the multi-device picker (M2), over Android [NsdManager]
 * (`_crema._tcp`) — **zero new permissions** (`INTERNET` covers NSD). It both
 * **advertises** this instance (so other devices' pickers can find it) and
 * **browses** for peers, exposing them as a live [peers] list. The relay
 * [Peer.port] in a primary's advertisement is exactly what a secondary dials to
 * mirror it.
 *
 * NSD is finicky and **does not run on the emulator's NAT** (two AVDs can't see
 * each other), so this is hardware-validated; the picker also accepts a
 * manual/debug peer for emulator runs. Every NSD call is wrapped defensively —
 * a discovery hiccup never crashes the app, the list just stays as-is.
 *
 * `resolveService` is deprecated on API 34+ but is the cross-version path for
 * minSdk 31; resolves are serialized through [resolveLock] to avoid NSD's
 * "resolve already active" failure.
 */
class PeerDiscovery(
    context: Context,
    private val scope: CoroutineScope,
    /** This install's stable id — advertised, and used to filter ourselves out. */
    private val selfId: String,
    /** Resolves one discovered service; tests pass a fake. */
    resolver: (suspend (NsdServiceInfo) -> NsdResolveOutcome<NsdServiceInfo>)? = null,
) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    /** Resolved peers, keyed by NSD service name. */
    private val found = ConcurrentHashMap<String, Peer>()
    private val resolveLock = Mutex()

    private val resolveOne: suspend (NsdServiceInfo) -> NsdResolveOutcome<NsdServiceInfo> = resolver ?: { resolve(it) }

    /** Service names currently on the network (found and not lost): a resolve
     *  that finishes, or a retry that fires, for anything else is dropped. */
    private val wanted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Pending resolve retries, by service name. */
    private val retries = ConcurrentHashMap<String, Job>()

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    /** (Re)advertise this instance's current role/state. Idempotent — replaces any
     *  prior registration (NSD has no in-place attribute update pre-API-34). */
    fun advertise(name: String, role: String, holdsDe1: Boolean, port: Int) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = "Crema-${selfId.take(8)}"
            serviceType = SERVICE_TYPE
            // NSD requires a positive port; non-hosts advertise a placeholder.
            this.port = if (port > 0) port else PLACEHOLDER_PORT
            setAttribute("id", selfId)
            setAttribute("name", name)
            setAttribute("role", role)
            setAttribute("de1", if (holdsDe1) "1" else "0")
            setAttribute("v", PROXY_PROTOCOL_VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "advertised as ${s.serviceName} ($role, de1=$holdsDe1, :$port)") }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { Log.w(TAG, "advertise failed: $code") }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "registerService threw", it) }
    }

    fun stopAdvertising() {
        registration?.let { l -> runCatching { nsd.unregisterService(l) } }
        registration = null
    }

    fun startDiscovery() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { Log.w(TAG, "discovery start failed: $code") }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = onFound(info)
            override fun onServiceLost(info: NsdServiceInfo) = onLost(info)
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "discoverServices threw", it) }
    }

    fun stopDiscovery() {
        discovery?.let { l -> runCatching { nsd.stopServiceDiscovery(l) } }
        discovery = null
        wanted.clear()
        retries.values.forEach { it.cancel() }
        retries.clear()
    }

    internal fun onFound(info: NsdServiceInfo) {
        wanted += info.serviceName
        scope.launch { resolveAndAdd(info) }
    }

    internal fun onLost(info: NsdServiceInfo) {
        wanted -= info.serviceName
        retries.remove(info.serviceName)?.cancel()
        found.remove(info.serviceName)?.let { publish() }
    }

    fun close() {
        stopDiscovery()
        stopAdvertising()
        found.clear()
        _peers.value = emptyList()
    }

    /** Resolve [info] and list it. A resolve that couldn't run (NSD busy with an
     *  earlier, stuck one, or timed out where it can't be cancelled) is retried
     *  after a pause, a few times, so one stuck resolve can't hide a peer for
     *  good. */
    private suspend fun resolveAndAdd(info: NsdServiceInfo, attempt: Int = 1) {
        val name = info.serviceName
        if (name !in wanted) return
        val outcome = resolveLock.withLock { resolveOne(info) }
        val resolved = when (outcome) {
            is NsdResolveOutcome.Resolved -> outcome.value
            else -> {
                if (shouldRetryNsdResolve(outcome, attempt, Build.VERSION.SDK_INT)) {
                    Log.i(TAG, "resolve of ${info.serviceName} didn't run ($outcome); retrying")
                    retries[name] = scope.launch {
                        delay(NSD_RESOLVE_RETRY_MS * attempt)
                        retries.remove(name)
                        resolveAndAdd(info, attempt + 1)
                    }
                }
                return
            }
        }
        // The peer may have left (or discovery stopped) while this resolved.
        if (name in wanted) addResolved(resolved)
    }

    private fun addResolved(resolved: NsdServiceInfo) {
        val attrs = resolved.attributes
        val id = attrs["id"]?.let { String(it) } ?: return
        if (id == selfId) return // ignore our own advertisement
        @Suppress("DEPRECATION") val host = resolved.host?.hostAddress ?: return
        found[resolved.serviceName] = Peer(
            id = id,
            name = attrs["name"]?.let { String(it) } ?: id,
            role = attrs["role"]?.let { String(it) } ?: "normal",
            holdsDe1 = attrs["de1"]?.let { String(it) } == "1",
            host = host,
            port = resolved.port,
        )
        publish()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdResolveOutcome<NsdServiceInfo> {
        val deferred = CompletableDeferred<NsdResolveOutcome<NsdServiceInfo>>()
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, code: Int) { deferred.complete(NsdResolveOutcome.Failed(code)) }
            override fun onServiceResolved(s: NsdServiceInfo) { deferred.complete(NsdResolveOutcome.Resolved(s)) }
        }
        return awaitNsdResolve(
            start = { nsd.resolveService(info, listener) },
            result = deferred,
            cancel = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    runCatching { nsd.stopServiceResolution(listener) }
                }
            },
        )
    }

    private fun publish() {
        _peers.value = found.values.sortedBy { it.name }
    }

    private companion object {
        const val TAG = "PeerDiscovery"
        const val SERVICE_TYPE = "_crema._tcp."
        const val PLACEHOLDER_PORT = 1
    }
}

/** Bound on one NSD resolve — it holds the discovery's resolve lock, so it must never hang. */
internal const val NSD_RESOLVE_TIMEOUT_MS = 5_000L

/** How one NSD resolve ended. */
sealed class NsdResolveOutcome<out T> {
    data class Resolved<T>(val value: T) : NsdResolveOutcome<T>()
    /** NSD reported a failure ([code] is an `NsdManager.FAILURE_*`), or the call threw (-1). */
    data class Failed(val code: Int) : NsdResolveOutcome<Nothing>()
    /** No callback within the timeout. */
    data object TimedOut : NsdResolveOutcome<Nothing>()
}

/** Pause before retrying a resolve that couldn't run (multiplied by the attempt). */
internal const val NSD_RESOLVE_RETRY_MS = 10_000L

private const val NSD_RESOLVE_MAX_ATTEMPTS = 4

/**
 * Whether a resolve that ended with [outcome] on its [attempt]th try should be
 * tried again. NSD reports "already active" while an earlier resolve is still
 * registered. Before API 34 a timed-out resolve can't be cancelled, so it can
 * stay registered, and later resolves fail until it clears. Both are worth a
 * later retry; a plain failure is not.
 */
internal fun shouldRetryNsdResolve(outcome: NsdResolveOutcome<*>, attempt: Int, sdk: Int): Boolean {
    if (attempt >= NSD_RESOLVE_MAX_ATTEMPTS) return false
    return when (outcome) {
        is NsdResolveOutcome.Resolved -> false
        NsdResolveOutcome.TimedOut -> true
        is NsdResolveOutcome.Failed ->
            outcome.code == NSD_FAILURE_ALREADY_ACTIVE || (sdk < 34 && outcome.code == NSD_FAILURE_INTERNAL_ERROR)
    }
}

/** `NsdManager.FAILURE_ALREADY_ACTIVE` / `FAILURE_INTERNAL_ERROR`, as plain ints for JVM tests. */
private const val NSD_FAILURE_ALREADY_ACTIVE = 3
private const val NSD_FAILURE_INTERNAL_ERROR = 0

/**
 * Start one NSD resolve and wait for its [result], at most [timeoutMs]. NSD
 * occasionally calls back NEITHER success nor failure, and the caller holds a
 * lock that serializes resolves, so without a bound one hung resolve would wedge
 * every later peer resolution for the life of the process. Whenever the wait
 * ends without a result (timeout, or this coroutine cancelled) the resolve is
 * [cancel]led. That is possible on API 34+; before that it stays registered,
 * and [shouldRetryNsdResolve] covers the resolves it then blocks.
 */
internal suspend fun <T> awaitNsdResolve(
    start: () -> Unit,
    result: Deferred<NsdResolveOutcome<T>>,
    cancel: () -> Unit,
    timeoutMs: Long = NSD_RESOLVE_TIMEOUT_MS,
): NsdResolveOutcome<T> {
    try {
        start()
    } catch (e: Exception) {
        Log.w("PeerDiscovery", "resolveService threw", e)
        return NsdResolveOutcome.Failed(-1)
    }
    try {
        return withTimeoutOrNull(timeoutMs) { result.await() } ?: NsdResolveOutcome.TimedOut
    } finally {
        if (!result.isCompleted) cancel()
    }
}
