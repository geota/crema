package coffee.crema.ble.proxy

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    /** Resolved peers, keyed by NSD service name. */
    private val found = ConcurrentHashMap<String, Peer>()
    private val resolveLock = Mutex()

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
            override fun onServiceFound(info: NsdServiceInfo) { scope.launch { resolveAndAdd(info) } }
            override fun onServiceLost(info: NsdServiceInfo) { found.remove(info.serviceName)?.let { publish() } }
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "discoverServices threw", it) }
    }

    fun stopDiscovery() {
        discovery?.let { l -> runCatching { nsd.stopServiceDiscovery(l) } }
        discovery = null
    }

    fun close() {
        stopDiscovery()
        stopAdvertising()
        found.clear()
        _peers.value = emptyList()
    }

    private suspend fun resolveAndAdd(info: NsdServiceInfo) = resolveLock.withLock {
        val resolved = resolve(info) ?: return@withLock
        val attrs = resolved.attributes
        val id = attrs["id"]?.let { String(it) } ?: return@withLock
        if (id == selfId) return@withLock // ignore our own advertisement
        @Suppress("DEPRECATION") val host = resolved.host?.hostAddress ?: return@withLock
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
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? {
        val deferred = CompletableDeferred<NsdServiceInfo?>()
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, code: Int) { deferred.complete(null) }
            override fun onServiceResolved(s: NsdServiceInfo) { deferred.complete(s) }
        }
        return runCatching { nsd.resolveService(info, listener); deferred.await() }.getOrNull()
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
