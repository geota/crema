package coffee.crema.ble.proxy

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [RelayHub] and [ProxyTransport] end to end over a [CodecFrameLink] (every
 * frame through the real codec) that mistreats frames on cue, on virtual
 * time: what a secondary's request does when the link delays, duplicates,
 * reorders or drops frames, or goes half-open. These are the transport
 * behaviours a typed-command seam sits on, so they're pinned before it.
 */
class ProxyFaultTest {

    private val handled = CopyOnWriteArrayList<Pair<String, String>>()

    private class Stack(val proxy: ProxyTransport, val client: CodecFrameLink, val server: CodecFrameLink)

    /** A primary's hub and a secondary's transport over one codec link pair. */
    private fun TestScope.stack(proxyScope: CoroutineScope = backgroundScope): Stack {
        val (client, server) = CodecFrameLink.pair(backgroundScope)
        val hub = RelayHub(
            primaryName = "Tablet",
            roster = { listOf(DeviceInfo("AA:BB:CC:DD:EE:FF", "DE1", "de1", "CONNECTED")) },
            readSource = { _, _, _ -> error("no reads in this test") },
            isSnapshotChar = { _, _ -> true },
            controlHandler = { method, args, _, _ ->
                handled += method to args
                Result.success(Unit)
            },
        )
        backgroundScope.launch { hub.serve(server, TEST_PEER) }
        val proxy = ProxyTransport(client, proxyScope, requestTimeoutMs = TIMEOUT_MS)
        return Stack(proxy, client, server)
    }

    private fun List<String>.ofType(type: String): List<Frame> =
        filter { "\"type\":\"$type\"" in it }.map { FrameCodec.decode(it) }

    @Test
    fun `a request waits out a delayed Welcome and never overtakes it`() = runTest {
        val s = stack()
        s.server.faults.delay(1_000) { it is Frame.Welcome }

        val result = s.proxy.control("tareScale")

        assertTrue(result.isSuccess, "$result")
        assertTrue(currentTime >= 1_000, "the control went out only after the delayed Welcome: t=$currentTime")
        assertEquals(listOf("tareScale" to ""), handled.toList())
        assertEquals(1, s.client.sentText.count { "\"control\"" in it }, "${s.client.sentText}")
    }

    @Test
    fun `a duplicated reply completes its request once and the next request is unaffected`() = runTest {
        val s = stack()
        s.server.faults.duplicate { it is Frame.ControlOk }

        assertTrue(s.proxy.control("tareScale").isSuccess)
        assertTrue(s.proxy.control("machineState", "IDLE").isSuccess)

        assertEquals(listOf("tareScale" to "", "machineState" to "IDLE"), handled.toList())
        val oks = s.server.sentText.ofType("controlOk")
        assertEquals(3, oks.size, "the first reply went out twice: $oks")
        assertEquals(oks[0], oks[1])
    }

    @Test
    fun `reordered replies still complete the right requests`() = runTest {
        val s = stack()
        s.server.faults.holdUntilNext { it is Frame.ControlOk }

        val tare = async { s.proxy.control("tareScale") }
        val stop = async { s.proxy.control("machineState", "IDLE") }

        assertTrue(tare.await().isSuccess)
        assertTrue(stop.await().isSuccess)
        val sentIds = s.client.sentText.ofType("control").map { (it as Frame.Control).id }
        val replyIds = s.server.sentText.ofType("controlOk").map { (it as Frame.ControlOk).id }
        assertEquals(sentIds.reversed(), replyIds, "the replies crossed in reverse order")
    }

    /** A lost ack fails the request at its timeout although the primary ran
     *  it: for an optimistic config verb, the secondary reverts an edit the
     *  primary kept (until the primary's next config push). */
    @Test
    fun `a dropped reply fails its request at the timeout and the link recovers`() = runTest {
        val s = stack()
        s.server.faults.drop { it is Frame.ControlOk }

        val start = currentTime
        val lost = s.proxy.control("setQcGrind", "14.5")

        assertTrue(lost.isFailure)
        assertEquals(TIMEOUT_MS, currentTime - start, "failed at the request timeout, on virtual time")
        assertEquals(listOf("setQcGrind" to "14.5"), handled.toList(), "the primary did run it")
        assertTrue(s.proxy.control("tareScale").isSuccess, "the next request is unaffected")
    }

    @Test
    fun `a half-open link fails a request at the timeout instead of hanging`() = runTest {
        val s = stack()
        assertTrue(s.proxy.control("tareScale").isSuccess)
        s.client.halfOpen()

        val start = currentTime
        val result = s.proxy.control("machineState", "IDLE")

        assertTrue(result.isFailure)
        assertEquals(TIMEOUT_MS, currentTime - start)
        assertEquals(listOf("tareScale" to ""), handled.toList(), "nothing crossed after the link went quiet")
    }

    /** Today (D-1 not done) an unknown frame type is fatal to the session: the
     *  decode error ends the secondary's inbound processing, so replies stop
     *  arriving. (ReconnectingClientLink catches it and redials.) Flip this
     *  when the codec learns to skip unknown frames. */
    @Test
    fun `an unknown frame type from the primary ends the secondary's session`() = runTest {
        val errors = CopyOnWriteArrayList<Throwable>()
        val proxyScope = CoroutineScope(
            backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job) +
                CoroutineExceptionHandler { _, e -> errors += e },
        )
        val s = stack(proxyScope)
        assertTrue(s.proxy.control("tareScale").isSuccess)

        s.server.sendRaw(Golden.require("unknown/type.json"))
        runCurrent()

        assertIs<SerializationException>(errors.single())
        val start = currentTime
        assertTrue(s.proxy.control("machineState", "IDLE").isFailure, "no reply is read any more")
        assertEquals(TIMEOUT_MS, currentTime - start)
        assertEquals(listOf("tareScale" to "", "machineState" to "IDLE"), handled.toList(), "the primary still ran it")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
