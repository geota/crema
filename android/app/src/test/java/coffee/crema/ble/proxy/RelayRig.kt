package coffee.crema.ble.proxy

import coffee.crema.core.RelayKeys
import coffee.crema.core.RelayPairingGate
import coffee.crema.core.RelayPinSet
import coffee.crema.core.relayDeviceId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A [HubTrust] for tests: an in-memory pin store, every callback recorded.
 * [storeDelayMs] delays the pin write (AC-47); [storeSaves] false makes it
 * fail (the `reject_pairing` path).
 */
internal class TestHubTrust(
    override val keys: RelayKeys,
    override val gate: RelayPairingGate = RelayPairingGate(),
    override val pins: RelayPinSet = RelayPinSet(emptyList()),
    override val hubName: String = "Kitchen tablet",
) : HubTrust {
    val remotes = ConcurrentHashMap<String, RelayRemotePin>()
    val requests = CopyOnWriteArrayList<HubPairingRequest>()
    val failures = CopyOnWriteArrayList<String>()
    val paired = CopyOnWriteArrayList<String>()
    val notSaved = CopyOnWriteArrayList<String>()
    val legacy = CopyOnWriteArrayList<String>()
    val admitted = CopyOnWriteArrayList<String>()
    val turnedAway: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile var storeDelayMs = 0L
    @Volatile var storeSaves = true

    /** Runs inside the pin write (a test closes the window there, so `confirm_paired` fails). */
    @Volatile var duringStore: () -> Unit = {}

    /** Pin [remote] as if it had paired, with [canControl]. */
    fun pin(remote: RelayKeys, name: String = "Phone", canControl: Boolean = true) {
        remotes[remote.deviceId()] = RelayRemotePin(
            remote.deviceId(), RelayKeyStore.b64(remote.publicKey()), name,
            if (canControl) SCOPE_CONTROL else SCOPE_MIRROR, pairedAtMs = 1,
        )
        pins.insert(remote.publicKey())
    }

    /** Forget [deviceId] the way the controller does: the live set first, then the store. */
    fun forget(deviceId: String) {
        remotes.remove(deviceId)?.let { pins.remove(RelayKeyStore.publicKeyOf(it.public)) }
    }

    override fun onPairRequest(request: HubPairingRequest) {
        requests += request
    }

    override fun storePin(remoteId: String, remoteName: String, publicKey: ByteArray, canControl: Boolean): PinWrite? {
        if (storeDelayMs > 0) Thread.sleep(storeDelayMs)
        duringStore()
        val previous = remotes[remoteId]
        remotes[remoteId] = RelayRemotePin(
            remoteId, RelayKeyStore.b64(publicKey), remoteName,
            if (canControl) SCOPE_CONTROL else SCOPE_MIRROR, pairedAtMs = 2,
        )
        return PinWrite(previous, storeSaves)
    }

    override fun restorePin(remoteId: String, previous: RelayRemotePin?) {
        if (previous == null) remotes.remove(remoteId) else remotes[remoteId] = previous
    }

    override fun onPairingFailed(request: HubPairingRequest, reason: String, failures: Int, locked: Boolean) {
        this.failures += reason
    }

    override fun onPairingNotSaved(request: HubPairingRequest) {
        notSaved += request.remoteName
    }

    override fun onPaired(request: HubPairingRequest) {
        paired += request.remoteId
    }

    override fun onLegacyPeer(name: String) {
        legacy += name
    }

    override fun peerFor(deviceId: String): RemotePeer? =
        remotes[deviceId]?.let { RemotePeer(it.id, it.name, it.scope == SCOPE_CONTROL) }

    override fun onAdmitted(peer: RemotePeer) {
        admitted += peer.deviceId
    }

    override fun isTurnedAway(address: String): Boolean = address in turnedAway
}

/**
 * A real relay v4 hub on the loopback for socket tests: keys, gate, live pin
 * set, a [RelayHub] behind a [SecureRelayAcceptor] in a [LanRelayServer]. The
 * remote side builds [ReconnectingClientLink]s and [PairingDialer]s against it.
 */
internal class RelayRig(
    val hub: RelayHub = defaultHub(),
    val hubKeys: RelayKeys = RelayKeys.generate(),
    maxUnauth: Int = SecureRelayAcceptor.MAX_UNAUTH,
    maxUnauthPerAddress: Int = SecureRelayAcceptor.MAX_UNAUTH_PER_ADDRESS,
    maxSessions: Int = SecureRelayAcceptor.MAX_SESSIONS,
    pingPeriodMs: Long = 15_000L,
    pingTimeoutMs: Long = 10_000L,
    requestedPort: Int = 0,
    val trust: TestHubTrust = TestHubTrust(hubKeys),
) {
    val acceptor = SecureRelayAcceptor(hub, trust, maxSessions = maxSessions, maxUnauth = maxUnauth, maxUnauthPerAddress = maxUnauthPerAddress)
    val server = LanRelayServer(
        handler = { ws, address -> acceptor.accept(ws, address) },
        requestedPort = requestedPort,
        pingPeriodMs = pingPeriodMs,
        pingTimeoutMs = pingTimeoutMs,
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var port: Int = 0
        private set

    val url: String get() = "ws://127.0.0.1:$port${LanRelayServer.PATH}"

    fun start(): RelayRig {
        port = runBlocking { server.start() }
        return this
    }

    /** A remote link resuming with [remote]'s key against this hub's key. */
    fun link(
        remote: RelayKeys,
        hubKey: ByteArray = hubKeys.publicKey(),
        at: String = url,
        client: io.ktor.client.HttpClient = coffee.crema.net.HttpClients.webSocketClient(),
    ): ReconnectingClientLink = ReconnectingClientLink(at, scope, remote, hubKey, client)

    fun stop() {
        runBlocking { withTimeoutOrNull(2_000) { acceptor.closeAll() } }
        server.stop()
        scope.cancel()
    }

    companion object {
        const val ADDR = "AA:BB:CC:DD:EE:FF"

        fun defaultHub(
            controlHandler: suspend (method: String, args: String, originId: String?, originName: String?) -> Result<Unit> =
                { _, _, _, _ -> Result.success(Unit) },
            configSource: suspend () -> String = { "" },
        ): RelayHub = RelayHub(
            primaryName = "Kitchen tablet",
            roster = { listOf(DeviceInfo(ADDR, "DE1", "de1", "CONNECTED")) },
            readSource = { _, _, _ -> byteArrayOf(0x01) },
            isSnapshotChar = { _, _ -> false },
            controlHandler = controlHandler,
            configSource = configSource,
        )

        /** The device id of a public key. */
        fun idOf(key: ByteArray): String = relayDeviceId(key)
    }
}

/** Poll [done] for up to [ms] (real time). */
internal suspend fun eventually(ms: Long = 10_000, what: String = "condition", done: () -> Boolean) {
    withTimeout(ms) { while (!done()) delay(10) }.also { if (!done()) throw AssertionError("timed out: $what") }
}

/** The peer every in-memory loopback serves as (relay v4: authenticated before [RelayHub.serve]). */
internal val TEST_PEER = RemotePeer("phone-1", "Phone", canControl = true)

/** What a remote stored when it paired through [pairThrough]. */
internal data class PairedHub(val id: String, val name: String, val key: ByteArray)

/**
 * Pair [remote] with this rig's hub through [url] (the rig itself, or a relay
 * probe in front of it): open the window, run a [PairingDialer], and type the
 * code it shows into the hub's request with [canControl].
 */
internal suspend fun RelayRig.pairThrough(
    remote: RelayKeys,
    url: String = this.url,
    canControl: Boolean = true,
    name: String = "Phone",
): PairedHub {
    var stored: PairedHub? = null
    val dialer = PairingDialer(url, "Kitchen tablet", remote, name, scope, storePin = { id, hubName, key ->
        stored = PairedHub(id, hubName, key)
        true
    })
    trust.gate.open(System.currentTimeMillis().toULong())
    val before = trust.requests.size
    dialer.start()
    eventually(what = "the code") { dialer.state.value is PairingState.ShowCode }
    val code = (dialer.state.value as PairingState.ShowCode).code
    eventually(what = "the hub's request") { trust.requests.size > before }
    check(trust.requests.last().enterCode(code, canControl)) { "the hub refused the code" }
    eventually(what = "paired") { dialer.state.value is PairingState.Paired }
    return stored!!
}
