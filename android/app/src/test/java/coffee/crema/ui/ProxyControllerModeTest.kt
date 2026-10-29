package coffee.crema.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.ble.proxy.Frame
import coffee.crema.ble.proxy.InMemoryFrameLink
import coffee.crema.ble.proxy.PROXY_PROTOCOL_VERSION
import coffee.crema.ble.proxy.PairingDecision
import coffee.crema.ble.proxy.RelayHub
import coffee.crema.settings.PairedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ProxyController] mode switching and the pairing prompt, on virtual time.
 * Built in the normal role (no prefs file), with every VM callback recorded.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ProxyControllerModeTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val events = CopyOnWriteArrayList<String>()
    private val log = CopyOnWriteArrayList<String>()
    private val remembered = CopyOnWriteArrayList<PairedDevice>()
    @Volatile private var flashing = false

    private val controller = ProxyController(
        app = ApplicationProvider.getApplicationContext(),
        json = Json { ignoreUnknownKeys = true },
        scope = scope,
        setCoreReadOnly = {},
        setRecorderEnabled = {},
        notify = {},
        appendLog = { log += it },
        connect = {},
        disconnect = {},
        disconnectDevices = { events += "teardown" },
        connectScale = {},
        scaleAttachWanted = { false },
        de1Connected = { false },
        machineStateName = { "Idle" },
        dispatchControl = { _, _ -> },
        snapshotConfig = { "" },
        applyConfig = {},
        roster = { emptyList() },
        isSnapshotChar = { _, _ -> false },
        pairedDevices = { remembered.toList() },
        rememberPaired = { id, name, canControl -> remembered += PairedDevice(id, name, canControl) },
        onHandoffGranted = {},
        onModeApplied = { role, _, _ -> events += "applied:$role" },
        firmwareHoldsLink = { flashing },
    )

    @After
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    @Test
    fun `mode switches run one at a time in request order`() {
        controller.switchToNormal()
        controller.switchToNormal()
        scheduler.advanceUntilIdle()

        assertEquals(listOf("teardown", "applied:normal", "teardown", "applied:normal"), events.toList())
    }

    @Test
    fun `a queued switch re-checks the firmware lock when it gets its turn`() {
        controller.switchToNormal()
        scheduler.runCurrent() // the first switch holds the lock, inside its settle delay
        controller.switchToNormal() // passes the outer check, queues on the lock
        scheduler.runCurrent()
        flashing = true // a flash takes the DE1 while the second switch waits
        scheduler.advanceUntilIdle()

        assertEquals(listOf("teardown", "applied:normal"), events.toList(), "the queued switch must not run")
        assertTrue(log.any { it.contains("refused") && it.contains(FIRMWARE_BUSY_REASON) }, "$log")
    }

    @Test
    fun `a cancelled pairing prompt is cleared and frees the slot`() {
        val first = scope.launch { controller.authorizePeer("id-1", "Phone") }
        scheduler.runCurrent()
        assertEquals(PairingPrompt("id-1", "Phone"), controller.state.value.pendingPairing)

        first.cancel()
        scheduler.runCurrent()
        assertNull(controller.state.value.pendingPairing, "the prompt does not outlive its request")

        val second = scope.async { controller.authorizePeer("id-2", "Tablet") }
        scheduler.runCurrent()
        assertNotNull(controller.state.value.pendingPairing, "the next unknown peer gets the slot")
        controller.resolvePairing(PairingChoice.ALLOW_CONTROL)
        scheduler.runCurrent()
        assertEquals(PairingDecision.Allowed(true), second.getCompleted())
        assertEquals(listOf("id-2"), remembered.map { it.id })
    }

    @Test
    fun `a second connection queued behind a denied prompt is not prompted again`() {
        val first = scope.async { controller.authorizePeer("id-1", "Phone") }
        val second = scope.async { controller.authorizePeer("id-1", "Phone") }
        scheduler.runCurrent()
        assertEquals(PairingPrompt("id-1", "Phone"), controller.state.value.pendingPairing)
        controller.resolvePairing(PairingChoice.DENY)
        scheduler.runCurrent()
        assertEquals(PairingDecision.Denied("not authorized"), first.getCompleted())
        assertEquals(PairingDecision.Denied("not authorized"), second.getCompleted())
        assertNull(controller.state.value.pendingPairing, "the queued connection got no prompt")
    }

    @Test
    fun `a denial is remembered while hosting`() {
        val first = scope.async { controller.authorizePeer("id-1", "Phone") }
        scheduler.runCurrent()
        controller.resolvePairing(PairingChoice.DENY)
        scheduler.runCurrent()
        assertEquals(PairingDecision.Denied("not authorized"), first.getCompleted())

        val again = scope.async { controller.authorizePeer("id-1", "Phone") }
        scheduler.runCurrent()
        assertEquals(PairingDecision.Denied("not authorized"), again.getCompleted())
        assertNull(controller.state.value.pendingPairing, "a denied device can't re-prompt by reconnecting")
    }

    /** Drives the real path: a relayed "handoff" from an approved session →
     *  handleRelayedControl → grantHandoff(takerId) → release on that
     *  session's disconnect. */
    @Test
    fun `a relayed handoff releases only when the taker's session ends`() {
        val hub = RelayHub("p", "P", roster = { emptyList() }, readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, _ -> false })
        controller.relayHub = hub
        val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            io.launch { hub.serve(server) }
            val rx = CopyOnWriteArrayList<Frame>()
            io.launch { client.incoming().collect { rx += it } }
            runBlocking {
                client.send(Frame.Hello(PROXY_PROTOCOL_VERSION, "secondary", "taker", "Taker"))
                withTimeout(2_000) { while (!hub.hasClient("taker")) delay(5) }
            }

            assertTrue(controller.handleRelayedControl("handoff", "", "taker", "Taker").isSuccess)
            repeat(20) { scheduler.runCurrent(); Thread.sleep(5) }
            scheduler.advanceTimeBy(200)
            scheduler.runCurrent()
            assertTrue("applied:normal" !in events, "no release while the taker is connected: $events")

            runBlocking { client.close() }
            val deadline = System.currentTimeMillis() + 2_000
            while ("applied:normal" !in events && System.currentTimeMillis() < deadline) {
                scheduler.runCurrent()
                scheduler.advanceTimeBy(200) // the switch's settle delay; stays far below the 3 s cap
                Thread.sleep(5)
            }
            assertTrue("applied:normal" in events, "released on the taker's disconnect: $events")
        } finally {
            io.cancel()
        }
    }
}
