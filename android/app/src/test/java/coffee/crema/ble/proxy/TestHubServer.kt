package coffee.crema.ble.proxy

import coffee.crema.core.RelayKeys
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A relay v4 hub endpoint a remote under test can dial on the loopback: a real
 * [LanRelayServer] + [SecureRelayAcceptor] with [hubKeys], which has pinned the
 * remote's key. Either a real [RelayHub] serves admitted sessions ([relay]),
 * or the test scripts them ([scripted]). [received] logs every frame the
 * remote sent on an admitted session (decrypted, re-encoded as JSON), so a
 * test can assert what crossed the wire.
 */
internal class TestHubServer private constructor(
    val hubKeys: RelayKeys,
    val trust: TestHubTrust,
    private val hub: RelayHub?,
    private val script: (suspend (FrameLink) -> Unit)?,
    requestedPort: Int,
) {
    /** Every frame the remote sent on an admitted session, as JSON, in order. */
    val received: MutableList<String> = CopyOnWriteArrayList()

    /** Connections accepted (dials), admitted or not. */
    val dials = AtomicInteger(0)

    /** The current admitted session's link (scripted endpoints). */
    @Volatile var link: FrameLink? = null
        private set

    /** Completes with the first admitted session's link. */
    val firstLink = CompletableDeferred<FrameLink>()

    private val acceptor = SecureRelayAcceptor(
        hub = hub ?: RelayRig.defaultHub(),
        trust = trust,
        serve = { l, peer -> serve(l, peer) },
    )
    private val server = LanRelayServer(handler = { ws, address -> dials.incrementAndGet(); acceptor.accept(ws, address) }, requestedPort = requestedPort)
    var port: Int = 0
        private set

    private suspend fun serve(l: FrameLink, peer: RemotePeer) {
        val recording = Recording(l, received)
        link = recording
        firstLink.complete(recording)
        val h = hub
        if (h != null) h.serve(recording, peer) else script?.invoke(recording)
    }

    private fun start(): TestHubServer {
        port = runBlocking { server.start() }
        return this
    }

    /** Drop the remote's socket and stop listening: the hub process is gone. */
    fun kill() {
        server.stop()
    }

    /** Stop hosting: admitted remotes get an authenticated `CLOSE(HUB_STOPPED)` first. */
    fun stopHosting() {
        runBlocking { withTimeoutOrNull(2_000) { acceptor.closeAll() } }
        server.stop()
    }

    /** A link that logs what the remote sent. */
    private class Recording(private val inner: FrameLink, private val log: MutableList<String>) : FrameLink by inner {
        override fun incoming(): Flow<Frame> = inner.incoming().onEach { log += FrameCodec.encode(it) }
    }

    companion object {
        /** A real relay named [name] that has pinned [remote] with [canControl]. */
        fun relay(
            remote: RelayKeys,
            name: String = "Kitchen tablet",
            hubKeys: RelayKeys = RelayKeys.generate(),
            roster: () -> List<DeviceInfo> = { listOf(DeviceInfo("AA:BB", "DE1", "de1", "CONNECTED")) },
            configSource: () -> String = { "" },
            controlHandler: suspend (method: String, args: String, originId: String?, originName: String?) -> Result<Unit> =
                { _, _, _, _ -> Result.success(Unit) },
            canControl: Boolean = true,
            requestedPort: Int = 0,
        ): Pair<TestHubServer, RelayHub> {
            val hub = RelayHub(
                primaryName = name,
                roster = roster,
                readSource = { _, _, _ -> ByteArray(0) },
                isSnapshotChar = { _, _ -> false },
                configSource = { configSource() },
                controlHandler = controlHandler,
            )
            val trust = TestHubTrust(hubKeys, hubName = name).apply { pin(remote, canControl = canControl) }
            return TestHubServer(hubKeys, trust, hub, null, requestedPort).start() to hub
        }

        /** An endpoint serving the test's own [hub], which has pinned [remote]. */
        fun serving(
            hub: RelayHub,
            remote: RelayKeys,
            name: String = "Kitchen tablet",
            hubKeys: RelayKeys = RelayKeys.generate(),
            canControl: Boolean = true,
        ): TestHubServer {
            val trust = TestHubTrust(hubKeys, hubName = name).apply { pin(remote, canControl = canControl) }
            return TestHubServer(hubKeys, trust, hub, null, 0).start()
        }

        /** An endpoint whose admitted session the test drives through [script] (its [link]). */
        fun scripted(
            remote: RelayKeys,
            name: String = "Kitchen tablet",
            hubKeys: RelayKeys = RelayKeys.generate(),
            canControl: Boolean = true,
            script: suspend (FrameLink) -> Unit = { kotlinx.coroutines.awaitCancellation() },
        ): TestHubServer {
            val trust = TestHubTrust(hubKeys, hubName = name).apply { pin(remote, canControl = canControl) }
            return TestHubServer(hubKeys, trust, null, script, 0).start()
        }
    }
}
