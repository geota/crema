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
                controlHandler = { method, args, _, _ ->
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

    /**
     * Issue 11 (loose "who's driving"): the relay threads the originating mirror's
     * id+name into `controlHandler`, and [RelayHub.broadcastEvent] fans the notice
     * to the OTHER mirrors but never the originator. Two clients, in-process.
     */
    @Test
    fun `a relayed control fans a who-drove event to other mirrors but not the originator`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            lateinit var hub: RelayHub
            val seenOrigin = CopyOnWriteArrayList<Pair<String?, String?>>()
            hub = RelayHub(
                primaryId = "primary-1",
                primaryName = "Kitchen tablet",
                roster = { emptyList() },
                readSource = { _, _, _ -> error("no read in this test") },
                isSnapshotChar = { _, _ -> true },
                controlHandler = { method, _, originId, originName ->
                    seenOrigin.add(originId to originName)
                    // Mimic the VM: only the machine action announces; the sync helper
                    // below ("tareScale") must not, or it would pollute the assertion.
                    if (method == "machineState") hub.broadcastEvent("$originName drove", exceptClientId = originId)
                    Result.success(Unit)
                },
            )
            val (aClient, aServer) = InMemoryFrameLink.pair()
            val (bClient, bServer) = InMemoryFrameLink.pair()
            scope.launch { hub.serve(aServer) }
            scope.launch { hub.serve(bServer) }
            val eventsA = CopyOnWriteArrayList<String>()
            val eventsB = CopyOnWriteArrayList<String>()
            val proxyA = ProxyTransport(aClient, scope, clientId = "phone-A", clientName = "Phone A", onEvent = { eventsA.add(it) })
            val proxyB = ProxyTransport(bClient, scope, clientId = "phone-B", clientName = "Phone B", onEvent = { eventsB.add(it) })

            // Round-trip B once so it's fully attached (Welcome done → in the client list).
            withTimeout(2_000) { proxyB.control("tareScale") }

            // A drives the machine → the notice is enqueued to B.
            withTimeout(2_000) { proxyA.control("machineState", "ESPRESSO") }
            // A second B round-trip drains B's FIFO outbox PAST the event (deterministic,
            // no sleep): when this ack returns, B's onEvent has already fired.
            withTimeout(2_000) { proxyB.control("tareScale") }

            assertTrue(("phone-A" to "Phone A") in seenOrigin, "the relay threads the originator id+name to the handler")
            assertEquals(listOf("Phone A drove"), eventsB.toList(), "another mirror is told who drove the machine")
            assertTrue(eventsA.isEmpty(), "the originator gets no event for its own action")
        } finally {
            scope.cancel()
        }
    }
}
