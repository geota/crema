package coffee.crema.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.ble.proxy.DenyReason
import coffee.crema.ble.proxy.Frame
import coffee.crema.ble.proxy.InMemoryFrameLink
import coffee.crema.ble.proxy.PairingCrypto
import coffee.crema.ble.proxy.PairingDecision
import coffee.crema.ble.proxy.PairingRequest
import coffee.crema.ble.proxy.RelayHub
import coffee.crema.ble.proxy.handshake
import coffee.crema.security.SecretBox
import coffee.crema.security.SecretCipher
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
 * [ProxyController] mode switching and pairing, on virtual time. Built in the
 * normal role (no prefs file), with every VM callback recorded and a fake
 * cipher for the pairing secrets.
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
    private val dispatched = CopyOnWriteArrayList<String>()
    @Volatile private var flashing = false

    /** Not crypto: enough for the store to seal and open. */
    private val box = SecretBox(object : SecretCipher {
        override fun seal(plain: ByteArray) = ByteArray(12) + plain
        override fun open(sealed: ByteArray) = sealed.copyOfRange(12, sealed.size)
    })

    /** A pairing request from [id] proving [secret] (or nothing). */
    private fun request(id: String, secret: String? = null, name: String = "Phone") =
        PairingRequest(id, name, hasProof = secret != null) { candidate -> candidate == secret }

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
        dispatchControl = { method, args -> dispatched += "$method($args)" },
        snapshotConfig = { "" },
        applyConfig = {},
        roster = { emptyList() },
        isSnapshotChar = { _, _ -> false },
        pairedDevices = { remembered.toList() },
        rememberPaired = { id, name, canControl -> remembered += PairedDevice(id, name, canControl) },
        onHandoffGranted = {},
        onModeApplied = { role, _, _ -> events += "applied:$role" },
        firmwareHoldsLink = { flashing },
        secretBox = box,
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
        controller.openPairingWindow()
        scheduler.runCurrent()
        val first = scope.launch { controller.authorizePeer(request("id-1")) }
        scheduler.runCurrent()
        assertEquals(PairingPrompt("id-1", "Phone"), controller.state.value.pendingPairing)

        first.cancel()
        scheduler.runCurrent()
        assertNull(controller.state.value.pendingPairing, "the prompt does not outlive its request")

        scheduler.advanceTimeBy(PROMPT_COOLDOWN_MS + 1)
        val second = scope.async { controller.authorizePeer(request("id-2", name = "Tablet")) }
        scheduler.runCurrent()
        assertNotNull(controller.state.value.pendingPairing, "the next unknown peer gets the slot")
        controller.resolvePairing(PairingChoice.ALLOW_CONTROL)
        scheduler.runCurrent()
        val allowed = second.getCompleted() as PairingDecision.Allowed
        assertTrue(allowed.canControl)
        assertNotNull(allowed.issuedSecret, "approval issues a pairing secret")
        assertEquals(listOf("id-2"), remembered.map { it.id })
    }

    @Test
    fun `with the pairing window closed an unknown device is refused without a prompt`() {
        val decision = scope.async { controller.authorizePeer(request("stranger")) }
        scheduler.runCurrent()
        assertEquals(PairingDecision.Denied(DenyReason.PAIRING_CLOSED), decision.getCompleted())
        assertNull(controller.state.value.pendingPairing)
    }

    @Test
    fun `a paired device proving its secret is admitted without a prompt, a wrong secret is not`() {
        controller.openPairingWindow()
        scheduler.runCurrent()
        val pairingRun = scope.async { controller.authorizePeer(request("phone")) }
        scheduler.runCurrent()
        controller.resolvePairing(PairingChoice.MIRROR_ONLY)
        scheduler.runCurrent()
        val secret = (pairingRun.getCompleted() as PairingDecision.Allowed).issuedSecret!!
        scheduler.runCurrent()
        assertEquals(0, controller.state.value.pairingSecondsLeft, "one approval closes the window")
        assertEquals(setOf("phone"), controller.state.value.pairedWithSecret)

        val again = scope.async { controller.authorizePeer(request("phone", secret)) }
        scheduler.runCurrent()
        assertEquals(PairingDecision.Allowed(canControl = false), again.getCompleted(), "silent, with the stored scope")

        val forged = scope.async { controller.authorizePeer(request("phone", PairingCrypto.newSecret())) }
        scheduler.runCurrent()
        assertEquals(PairingDecision.Denied(DenyReason.PAIRING_CLOSED), forged.getCompleted())
    }

    @Test
    fun `a device remembered without a secret has to pair again`() {
        remembered += PairedDevice("legacy", "Old phone", canControl = true)
        val decision = scope.async { controller.authorizePeer(request("legacy")) }
        scheduler.runCurrent()
        assertEquals(PairingDecision.Denied(DenyReason.PAIRING_CLOSED), decision.getCompleted())
        assertTrue("legacy" !in controller.state.value.pairedWithSecret)
    }

    @Test
    fun `one prompt at a time, a cooldown after it, and a denial is remembered`() {
        controller.openPairingWindow()
        scheduler.runCurrent()
        val first = scope.async { controller.authorizePeer(request("a")) }
        scheduler.runCurrent()
        val second = scope.async { controller.authorizePeer(request("b")) }
        scheduler.runCurrent()
        assertEquals(PairingDecision.Denied(DenyReason.BUSY), second.getCompleted(), "no queue of prompts")

        controller.resolvePairing(PairingChoice.DENY)
        scheduler.runCurrent()
        assertEquals(PairingDecision.Denied(DenyReason.NOT_AUTHORIZED), first.getCompleted())

        val tooSoon = scope.async { controller.authorizePeer(request("c")) }
        scheduler.runCurrent()
        assertEquals(PairingDecision.Denied(DenyReason.BUSY), tooSoon.getCompleted(), "cooldown between prompts")

        scheduler.advanceTimeBy(PROMPT_COOLDOWN_MS + 1)
        val deniedAgain = scope.async { controller.authorizePeer(request("a")) }
        scheduler.runCurrent()
        assertEquals(PairingDecision.Denied(DenyReason.NOT_AUTHORIZED), deniedAgain.getCompleted())
        assertNull(controller.state.value.pendingPairing, "a denied device is not prompted again")
    }

    @Test
    fun `prompts per window are capped`() {
        controller.openPairingWindow()
        scheduler.runCurrent()
        repeat(MAX_PROMPTS_PER_WINDOW) { i ->
            val d = scope.async { controller.authorizePeer(request("p$i")) }
            scheduler.runCurrent()
            controller.resolvePairing(PairingChoice.DENY)
            scheduler.runCurrent()
            assertEquals(PairingDecision.Denied(DenyReason.NOT_AUTHORIZED), d.getCompleted())
            scheduler.advanceTimeBy(PROMPT_COOLDOWN_MS + 1)
        }
        val over = scope.async { controller.authorizePeer(request("one-more")) }
        scheduler.runCurrent()
        assertEquals(PairingDecision.Denied(DenyReason.BUSY), over.getCompleted())
    }

    @Test
    fun `the window closes on its own and takes a pending prompt with it`() {
        controller.openPairingWindow()
        scheduler.runCurrent()
        assertEquals(PAIRING_WINDOW_S, controller.state.value.pairingSecondsLeft)
        val pending = scope.async { controller.authorizePeer(request("late")) }
        scheduler.runCurrent()
        assertNotNull(controller.state.value.pendingPairing)

        scheduler.advanceTimeBy(PAIRING_WINDOW_S * 1_000L + 1)
        scheduler.runCurrent()
        assertEquals(0, controller.state.value.pairingSecondsLeft)
        assertNull(controller.state.value.pendingPairing)
        assertEquals(PairingDecision.Denied(DenyReason.PAIRING_CLOSED), pending.getCompleted(), "expired, not denied")

        controller.openPairingWindow()
        scheduler.runCurrent()
        scheduler.advanceTimeBy(PROMPT_COOLDOWN_MS + 1)
        scope.async { controller.authorizePeer(request("late")) }
        scheduler.runCurrent()
        assertNotNull(controller.state.value.pendingPairing, "an expired request may ask again")
    }

    @Test
    fun `a mode switch closes the pairing window`() {
        controller.openPairingWindow()
        scheduler.runCurrent()
        controller.switchToNormal()
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        assertEquals(0, controller.state.value.pairingSecondsLeft)
    }

    @Test
    fun `forgetting a device drops its secret`() {
        controller.openPairingWindow()
        scheduler.runCurrent()
        val run = scope.async { controller.authorizePeer(request("phone")) }
        scheduler.runCurrent()
        controller.resolvePairing(PairingChoice.ALLOW_CONTROL)
        scheduler.runCurrent()
        val secret = (run.getCompleted() as PairingDecision.Allowed).issuedSecret!!

        controller.forgetPaired("phone")
        assertTrue("phone" !in controller.state.value.pairedWithSecret)
        val after = scope.async { controller.authorizePeer(request("phone", secret)) }
        scheduler.runCurrent()
        assertEquals(PairingDecision.Denied(DenyReason.PAIRING_CLOSED), after.getCompleted())
    }

    @Test
    fun `the NSD instance id is random per launch, not the install id`() {
        assertTrue(controller.instanceId != controller.deviceId)
        val other = ProxyController(
            app = ApplicationProvider.getApplicationContext(), json = Json { ignoreUnknownKeys = true }, scope = scope,
            setCoreReadOnly = {}, setRecorderEnabled = {}, notify = {}, appendLog = {}, connect = {}, disconnect = {},
            disconnectDevices = {}, connectScale = {}, scaleAttachWanted = { false }, de1Connected = { false },
            machineStateName = { null }, dispatchControl = { _, _ -> }, snapshotConfig = { "" }, applyConfig = {},
            roster = { emptyList() }, isSnapshotChar = { _, _ -> false }, pairedDevices = { emptyList() },
            rememberPaired = { _, _, _ -> }, onHandoffGranted = {}, onModeApplied = { _, _, _ -> }, secretBox = box,
        )
        try {
            assertEquals(controller.deviceId, other.deviceId, "the install id is stable")
            assertTrue(other.instanceId != controller.instanceId, "the advertised id is not")
        } finally {
            other.close()
        }
    }

    @Test
    fun `a relayed control is checked before it is dispatched`() {
        assertTrue(controller.handleRelayedControl("machineState", "AIR_PURGE", "phone", "Phone").isFailure)
        assertTrue(controller.handleRelayedControl("setQcSteamTemp", "NaN", "phone", "Phone").isFailure)
        assertTrue(controller.handleRelayedControl("writeMmr", "1", "phone", "Phone").isFailure)
        assertTrue(dispatched.isEmpty(), "nothing refused reaches the router: $dispatched")

        assertTrue(controller.handleRelayedControl("machineState", "IDLE", "phone", "Phone").isSuccess)
        assertEquals(listOf("machineState(IDLE)"), dispatched.toList())
    }

    @Test
    fun `denied reasons map to what the secondary shows`() {
        assertTrue(deniedMessage(DenyReason.PAIRING_CLOSED).contains("Pair a device"))
        assertTrue(deniedMessage(DenyReason.VERSION_MISMATCH).contains("Update Crema"))
        assertTrue(deniedMessage("version mismatch — update Crema on both devices").contains("Update Crema"))
        assertTrue(deniedMessage(DenyReason.REVOKED).contains("pair again"))
        assertTrue(deniedMessage(DenyReason.BUSY).contains("busy"))
        assertEquals("The host hasn't allowed this device", deniedMessage(DenyReason.NOT_AUTHORIZED))
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
            runBlocking { handshake(client, rx, clientId = "taker") }

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
