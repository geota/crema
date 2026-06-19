package coffee.crema.ble.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M2 T2: config has a single owner, the primary. On attach the primary pushes
 * its session-config snapshot and the secondary snaps to it — the settings-drift
 * fix, proven over the in-process [InMemoryFrameLink] with no socket / emulator.
 */
class ProxyConfigTest {

    private val addr = "AA:BB:CC:DD:EE:FF"

    @Test
    fun `attach delivers the primary's config snapshot to the secondary`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (clientEnd, serverEnd) = InMemoryFrameLink.pair()
            val configJson = """{"pressureUnit":"psi","tempUnit":"F","activeProfileId":"abc"}"""
            val hub = RelayHub(
                primaryId = "primary-1",
                primaryName = "Kitchen tablet",
                roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
                readSource = { _, _, _ -> error("no read in this test") },
                isSnapshotChar = { _, _ -> true },
                configSource = { configJson },
            )
            scope.launch { hub.serve(serverEnd) }

            val received = CompletableDeferred<String>()
            val proxy = ProxyTransport(
                clientEnd, scope, clientId = "phone-1", clientName = "Phone",
                onConfig = { if (!received.isCompleted) received.complete(it) },
            )

            // Attach triggers the config push (right after the BLE-char snapshot burst).
            val match = withTimeout(2_000) { proxy.scan { it == "DE1" }.first() }
            withTimeout(2_000) { proxy.connect(match.device) }

            assertEquals(
                configJson,
                withTimeout(2_000) { received.await() },
                "the mirror snaps to the primary's config the moment it attaches",
            )
        } finally {
            scope.cancel()
        }
    }
}
