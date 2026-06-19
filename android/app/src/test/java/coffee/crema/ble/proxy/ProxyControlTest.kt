package coffee.crema.ble.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2 T1b: a secondary's user-intent [ProxyTransport.control] crosses as a
 * [Frame.Control], dispatches through the primary's `controlHandler`, and acks
 * with [Frame.ControlOk] — proven over the in-process [InMemoryFrameLink] with no
 * socket, NSD, emulator or Bluetooth (the same harness as the M1 loopback). The
 * relay stays a read-only mirror for *autonomous* writes ([Frame.Write] →
 * `not-authoritative`); only this explicit user-intent path reaches the primary.
 */
class ProxyControlTest {

    private val addr = "AA:BB:CC:DD:EE:FF"

    @Test
    fun `a secondary's control relays to the primary and acks`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (clientEnd, serverEnd) = InMemoryFrameLink.pair()
            val relayed = CopyOnWriteArrayList<Pair<String, String>>()
            val hub = RelayHub(
                primaryId = "primary-1",
                primaryName = "Kitchen tablet",
                roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
                readSource = { _, _, _ -> error("no read in this test") },
                isSnapshotChar = { _, _ -> true },
                controlHandler = { method, args ->
                    relayed.add(method to args)
                    if (method == "boom") Result.failure(IllegalStateException("nope")) else Result.success(Unit)
                },
            )
            scope.launch { hub.serve(serverEnd) }
            val proxy = ProxyTransport(clientEnd, scope, clientId = "phone-1", clientName = "Phone")

            // A relayed machine-state intent dispatches on the primary and acks.
            val ok = withTimeout(2_000) { proxy.control("machineState", "IDLE") }
            assertTrue(ok.isSuccess, "the primary acks a dispatched control")
            assertEquals("machineState" to "IDLE", relayed.single(), "the exact intent reaches the primary router")

            // A handler failure surfaces as ControlErr → Result.failure (never a throw
            // into the UI thread).
            val err = withTimeout(2_000) { proxy.control("boom") }
            assertTrue(err.isFailure, "a refused control returns failure, not an exception")
        } finally {
            scope.cancel()
        }
    }
}
