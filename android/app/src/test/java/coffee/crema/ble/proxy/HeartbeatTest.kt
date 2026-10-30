package coffee.crema.ble.proxy

import coffee.crema.core.RelayKeys
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AC-34, heartbeat: a hub that stops sending records (the probe relay still
 * answers WebSocket pings on each hop, so TCP looks alive) is closed by the
 * remote's core after 30 s without a record (`RECV_TIMEOUT_MS`), and the
 * remote goes back to reconnecting. WebSocket pings never count as liveness.
 */
class HeartbeatTest {

    @Test
    fun `a hub that goes silent behind live pings is dropped after 30 s`() {
        val remote = RelayKeys.generate()
        val rig = RelayRig().also { it.trust.pin(remote) }.start()
        val probe = WsRelayProbe(rig.port).start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val welcomed = AtomicBoolean(false)
            val link = ReconnectingClientLink(probe.url, scope, remote, rig.hubKeys.publicKey())
            ProxyTransport(link, scope, onWelcome = { _, _ -> welcomed.set(true) })
            runBlocking { eventually(what = "welcomed") { welcomed.get() } }
            val silentFrom = System.nanoTime()
            probe.dropHubRecords = true
            runBlocking { withTimeout(40_000) { link.state.first { it == LinkState.CONNECTING } } }
            val secs = (System.nanoTime() - silentFrom) / 1e9
            assertTrue(secs in 25.0..35.0, "dropped after ~30 s of silence, was $secs s")
        } finally {
            scope.cancel()
            probe.stop()
            rig.stop()
        }
    }
}
