package coffee.crema.ble.proxy

import coffee.crema.core.RelayKeys
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-32, ordering: 1 000 frames sent from 8 concurrent coroutines on the
 * remote all decrypt at the hub, in send order per coroutine, with no decrypt
 * failure (the connection's one send lock keeps encryption order equal to wire
 * order, spec §2.7, §3.5).
 */
class NoiseFrameLinkOrderTest {

    @Test
    fun `concurrent senders never break the record order`() {
        val remote = RelayKeys.generate()
        val got = CopyOnWriteArrayList<String>()
        val hub = TestHubServer.scripted(remote) { link -> link.incoming().collect { if (it is Frame.Event) got += it.text } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ReconnectingClientLink("ws://127.0.0.1:${hub.port}${LanRelayServer.PATH}", scope, remote, hub.hubKeys.publicKey())
            val epoch = runBlocking { link.events().filterIsInstance<LinkEvent.SessionStarted>().first().epoch }
            runBlocking {
                (0 until 8).map { c ->
                    scope.async { repeat(125) { i -> check(link.sendOn(epoch, Frame.Event("$c:$i"))) { "send $c:$i failed" } } }
                }.awaitAll()
                eventually(ms = 10_000, what = "every frame at the hub") { got.size == 1_000 }
            }
            for (c in 0 until 8) {
                val mine = got.filter { it.startsWith("$c:") }.map { it.substringAfter(':').toInt() }
                assertEquals((0 until 125).toList(), mine, "coroutine $c's frames in send order")
            }
            assertTrue(hub.dials.get() == 1, "one session, never closed for a decrypt failure: ${hub.dials.get()} dials")
        } finally {
            scope.cancel()
            hub.kill()
        }
    }
}
