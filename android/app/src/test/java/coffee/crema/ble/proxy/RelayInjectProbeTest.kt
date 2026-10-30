package coffee.crema.ble.proxy

import coffee.crema.core.RelayKeys
import coffee.crema.net.HttpClients
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame as WsFrame
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-24, the relay-and-inject probe (P1b probe E, ported to relay v4): a
 * WebSocket relay ([WsRelayProbe]) between a real remote and a real
 * [LanRelayServer] forwards the whole handshake verbatim and the remote is
 * welcomed ("Connected through"). Then, in separate runs, the relay injects
 * a text control (close 4400), a forged `RECORD` (4401), a replayed remote
 * `RECORD` (4401), or swaps two remote records (4401); the same four with the
 * relay bound on the hub's own address; and a replay of the captured IK
 * message 1 on a second connection while the real session is live (the live
 * session keeps working, nothing is admitted, the replay times out with
 * 4408). In every run the hub's dispatcher runs zero injected controls.
 */
class RelayInjectProbeTest {

    private class Run(val rig: RelayRig, val probe: WsRelayProbe, val proxy: ProxyTransport, val scope: CoroutineScope, val controls: MutableList<String>)

    /** A remote welcomed through a relay probe bound on [bindHost]. */
    private fun welcomedThroughProbe(bindHost: String = "127.0.0.1"): Run {
        val controls = CopyOnWriteArrayList<String>()
        val hub = RelayRig.defaultHub(controlHandler = { method, _, _, _ -> controls += method; Result.success(Unit) })
        val remote = RelayKeys.generate()
        val rig = RelayRig(hub).also { it.trust.pin(remote) }.start()
        val probe = WsRelayProbe(rig.port, bindHost).start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val welcomed = AtomicBoolean(false)
        val link = ReconnectingClientLink(probe.url, scope, remote, rig.hubKeys.publicKey())
        val proxy = ProxyTransport(link, scope, requestTimeoutMs = 3_000, onWelcome = { _, _ -> welcomed.set(true) })
        try {
            runBlocking { eventually(what = "the remote welcomed through the relay") { welcomed.get() } }
        } catch (e: AssertionError) {
            val msgs = probe.captured.joinToString(" ") { (if (it.toHub) ">" else "<") + (if (it.text) "T" else "%02x".format(it.bytes[0])) + ":" + it.bytes.size }
            throw AssertionError("${e.message}; link ${link.state.value}; admitted ${rig.trust.admitted}; hub closes ${probe.hubCloseCodes}; msgs $msgs", e)
        }
        // Every byte of the handshake crossed the relay untouched.
        assertTrue(probe.captured.first().text, "the ClientHello is the only text message")
        return Run(rig, probe, proxy, scope, controls)
    }

    private fun Run.close() {
        scope.cancel()
        probe.stop()
        rig.stop()
    }

    /** The hub's close code on the relayed connection that was live. */
    private fun Run.firstHubClose(): Short = runBlocking {
        eventually(what = "the hub closing the relayed connection") { probe.hubCloseCodes.isNotEmpty() }
        probe.hubCloseCodes.first()
    }

    private fun record(): WsRelayProbe.Msg = WsRelayProbe.Msg(true, false, byteArrayOf(WsRelayProbe.KIND_RECORD) + ByteArray(80) { it.toByte() })

    private fun injectText(bindHost: String) {
        val r = welcomedThroughProbe(bindHost)
        try {
            runBlocking { r.probe.injectToHub(WsRelayProbe.Msg(true, true, """{"type":"control","method":"startShot"}""".encodeToByteArray())) }
            assertEquals(4400, r.firstHubClose().toInt(), "a text message after the ClientHello is a protocol error")
            assertTrue(r.controls.isEmpty(), "no control ran: ${r.controls}")
        } finally {
            r.close()
        }
    }

    private fun injectForgedRecord(bindHost: String) {
        val r = welcomedThroughProbe(bindHost)
        try {
            runBlocking { r.probe.injectToHub(record()) }
            assertEquals(4401, r.firstHubClose().toInt(), "a forged record fails to decrypt")
            assertTrue(r.controls.isEmpty(), "no control ran: ${r.controls}")
        } finally {
            r.close()
        }
    }

    private fun replayRecord(bindHost: String) {
        val r = welcomedThroughProbe(bindHost)
        try {
            val captured = r.probe.captured.last { it.toHub && !it.text && it.bytes[0] == WsRelayProbe.KIND_RECORD }
            runBlocking { r.probe.injectToHub(captured) }
            assertEquals(4401, r.firstHubClose().toInt(), "a replayed record fails to decrypt")
            assertTrue(r.controls.isEmpty(), "no control ran: ${r.controls}")
        } finally {
            r.close()
        }
    }

    private fun swapRecords(bindHost: String) {
        val r = welcomedThroughProbe(bindHost)
        try {
            var held: WsRelayProbe.Msg? = null
            r.probe.tamper = { _, m ->
                if (m.text || m.bytes[0] != WsRelayProbe.KIND_RECORD) listOf(m)
                else {
                    val first = held
                    if (first == null) { held = m; emptyList() } else listOf(m, first)
                }
            }
            runBlocking {
                val a = r.scope.async { r.proxy.control("startShot") }
                val b = r.scope.async { r.proxy.control("machineState", "IDLE") }
                runCatching { a.await() }
                runCatching { b.await() }
            }
            assertEquals(4401, r.firstHubClose().toInt(), "swapped records fail to decrypt")
            assertTrue(r.controls.isEmpty(), "no control ran: ${r.controls}")
        } finally {
            r.close()
        }
    }

    @Test fun `a injected text control closes 4400`() = injectText("127.0.0.1")
    @Test fun `b forged record closes 4401`() = injectForgedRecord("127.0.0.1")
    @Test fun `c replayed remote record closes 4401`() = replayRecord("127.0.0.1")
    @Test fun `d swapped remote records close 4401`() = swapRecords("127.0.0.1")

    /** (e): the relay on the hub's own address (the gap P1b left open). The
     *  loopback hub listens on every interface, so the relay binds the
     *  address the hub is reached at. */
    @Test fun `e the same with the relay on the hub's own address`() {
        val hubAddress = java.net.InetAddress.getLoopbackAddress().hostAddress!!
        injectText(hubAddress)
        injectForgedRecord(hubAddress)
        replayRecord(hubAddress)
        swapRecords(hubAddress)
    }

    @Test
    fun `f a replayed IK message 1 on a second connection is never admitted and times out`() {
        val r = welcomedThroughProbe()
        try {
            val hello = r.probe.captured.first { it.toHub && it.text }
            val msg1 = r.probe.captured.first { it.toHub && !it.text && it.bytes[0] == WsRelayProbe.KIND_NOISE_HS }
            val client = HttpClients.webSocketClient()
            val closeCode = runBlocking {
                val ws = client.webSocketSession { url(r.rig.url) }
                ws.send(WsFrame.Text(true, hello.bytes))
                ws.send(WsFrame.Binary(true, msg1.bytes))
                withTimeout(10_000) {
                    for (f in ws.incoming) Unit
                    ws.closeReason.await()?.code?.toInt()
                }
            }
            client.close()
            assertEquals(4408, closeCode, "the replay gets IK message 2 and then times out")
            assertEquals(1, r.rig.trust.admitted.size, "nothing else was admitted: ${r.rig.trust.admitted}")
            // The real session is unaffected.
            assertTrue(runBlocking { r.proxy.control("tareScale") }.isSuccess, "the live session keeps working")
            assertEquals(listOf("tareScale"), r.controls.toList(), "only the real remote's control ran")
        } finally {
            r.close()
        }
    }
}
