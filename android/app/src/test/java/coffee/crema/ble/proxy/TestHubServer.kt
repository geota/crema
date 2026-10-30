package coffee.crema.ble.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.net.InetAddress

/**
 * A LAN endpoint a remote under test can dial: a MockWebServer that upgrades
 * one WebSocket to [link], on the loopback at [port]. Either a real
 * [RelayHub] serves it ([relay]), or the test scripts it ([scripted]).
 */
internal class TestHubServer private constructor() {
    private val server = MockWebServer()
    val link = MockWsServerLink()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    val port: Int get() = server.port

    /** Every text the remote sent, in order. */
    val received: List<String> get() = link.receivedText

    /** The `auth` frames the remote sent: `{"type":"auth"}` without a proof
     *  is a pairing request. */
    val auths: List<String> get() = received.filter { "\"type\":\"auth\"" in it }

    private fun start(): TestHubServer {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(link).build())
        server.start(InetAddress.getLoopbackAddress(), 0)
        return this
    }

    /** Drop the remote's socket and stop listening: the hub process is gone. */
    fun kill() {
        runBlocking { withTimeoutOrNull(2_000) { runCatching { link.close() } } }
        scope.cancel()
        server.close()
    }

    companion object {
        /** A real relay as host [hostId] that proves [secret] to [clientId] and
         *  admits only a proof of it. */
        fun relay(
            hostId: String,
            name: String,
            secret: String,
            clientId: () -> String,
            roster: () -> List<DeviceInfo> = { listOf(DeviceInfo("AA:BB", "DE1", "de1", "CONNECTED")) },
            configSource: () -> String = { "" },
            controlHandler: suspend (method: String, args: String, originId: String?, originName: String?) -> Result<Unit> =
                { _, _, _, _ -> Result.success(Unit) },
        ): Pair<TestHubServer, RelayHub> {
            val endpoint = TestHubServer().start()
            val hub = RelayHub(
                primaryId = hostId,
                primaryName = name,
                roster = roster,
                readSource = { _, _, _ -> ByteArray(0) },
                isSnapshotChar = { _, _ -> false },
                configSource = configSource,
                controlHandler = controlHandler,
                authorize = { request ->
                    if (request.proves(secret)) PairingDecision.Allowed(canControl = true)
                    else PairingDecision.Denied(DenyReason.NOT_AUTHORIZED)
                },
                pairingSecretFor = { id -> secret.takeIf { id == clientId() } },
            )
            endpoint.scope.launch { hub.serve(endpoint.link) }
            return endpoint to hub
        }

        /** An endpoint that answers the remote's first Hello with [reply]. */
        fun scripted(reply: (hello: Frame.Hello) -> Frame): TestHubServer {
            val endpoint = TestHubServer().start()
            endpoint.scope.launch {
                val hello = endpoint.link.incoming().first { it is Frame.Hello } as Frame.Hello
                endpoint.link.send(reply(hello))
            }
            return endpoint
        }
    }
}
