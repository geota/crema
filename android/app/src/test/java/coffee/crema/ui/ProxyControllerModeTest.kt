package coffee.crema.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.ble.proxy.DenyReason
import coffee.crema.ble.proxy.Peer
import coffee.crema.ble.proxy.PairingCrypto
import coffee.crema.ble.proxy.PairingDecision
import coffee.crema.ble.proxy.PairingRequest
import coffee.crema.machine.CommandOutcome
import coffee.crema.machine.MachineCommand
import coffee.crema.machine.QcField
import coffee.crema.machine.RecordingMachineActions
import coffee.crema.security.SecretBox
import coffee.crema.security.SecretCipher
import coffee.crema.settings.PairedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    private val actions = RecordingMachineActions()
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
        connect = { events += "connect" },
        disconnect = { events += "disconnect" },
        disconnectDevices = { events += "teardown" },
        connectScale = {},
        scaleAttachWanted = { false },
        de1Connected = { false },
        machineStateName = { "Idle" },
        machineActions = actions,
        snapshotConfig = { "" },
        applyConfig = {},
        roster = { emptyList() },
        isSnapshotChar = { _, _ -> false },
        pairedDevices = { remembered.toList() },
        rememberPaired = { id, name, canControl -> remembered += PairedDevice(id, name, canControl) },
        onModeApplied = { role, host, port -> events += "applied:$role"; targets += "$role $host:$port" },
        firmwareHoldsLink = { flashing },
        secretBox = box,
    )

    /** Every applied mode with its target, in order. */
    private val targets = CopyOnWriteArrayList<String>()

    /** Every remote target this controller dialed, as host:port, in order:
     *  saved targets and re-find attempts alike. */
    private fun dials(): List<String> =
        log.filter { "SECONDARY →" in it }.map { it.substringAfter("ws://").substringBefore("/") }

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    /** An NSD peer. */
    private fun peer(id: String, port: Int, role: String = "primary", de1: Boolean = true, host: String = "127.0.0.1") =
        Peer(id, "Kitchen tablet", role, de1, host, port)

    /** A remote on 127.0.0.1:9 (nothing listens there: its link stays
     *  CONNECTING), restored unattended with the pin "host-1". Returns the
     *  peers flow the test drives. */
    private fun remoteOnDeadHub(vararg peers: Peer): MutableStateFlow<List<Peer>> {
        controller.pairingStore.pinHost("host-1")
        controller.remirrorUnattended("127.0.0.1", 9)
        advance(200)
        assertEquals("secondary 127.0.0.1:9", targets.last())
        val flow = MutableStateFlow(peers.toList())
        controller.overridePeers(flow)
        scheduler.runCurrent()
        return flow
    }

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

        assertEquals(listOf("teardown", "applied:normal", "connect", "teardown", "applied:normal", "connect"), events.toList())
    }

    @Test
    fun `a queued switch re-checks the firmware lock when it gets its turn`() {
        controller.switchToNormal()
        scheduler.runCurrent() // the first switch holds the lock, inside its settle delay
        controller.switchToNormal() // passes the outer check, queues on the lock
        scheduler.runCurrent()
        flashing = true // a flash takes the DE1 while the second switch waits
        scheduler.advanceUntilIdle()

        assertEquals(listOf("teardown", "applied:normal", "connect"), events.toList(), "the queued switch must not run")
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
        assertEquals(PairingDecision.Denied(DenyReason.ALREADY_PAIRED), forged.getCompleted())
    }

    @Test
    fun `a device that holds a secret can't pair again until the host forgets it`() {
        controller.openPairingWindow()
        scheduler.runCurrent()
        val first = scope.async { controller.authorizePeer(request("phone")) }
        scheduler.runCurrent()
        controller.resolvePairing(PairingChoice.ALLOW_CONTROL)
        scheduler.runCurrent()
        assertTrue(first.getCompleted() is PairingDecision.Allowed)

        scheduler.advanceTimeBy(PROMPT_COOLDOWN_MS + 1)
        controller.openPairingWindow()
        scheduler.runCurrent()
        val again = scope.async { controller.authorizePeer(request("phone")) }
        scheduler.runCurrent()
        assertEquals(PairingDecision.Denied(DenyReason.ALREADY_PAIRED), again.getCompleted())
        assertNull(controller.state.value.pendingPairing, "no prompt for an already-paired device")

        controller.forgetPaired("phone")
        val afterForget = scope.async { controller.authorizePeer(request("phone")) }
        scheduler.runCurrent()
        assertEquals(PairingPrompt("phone", "Phone"), controller.state.value.pendingPairing, "after Forget it may pair again")
        controller.resolvePairing(PairingChoice.DENY)
        scheduler.runCurrent()
        assertEquals(PairingDecision.Denied(DenyReason.NOT_AUTHORIZED), afterForget.getCompleted())
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
            machineStateName = { null }, machineActions = RecordingMachineActions(), snapshotConfig = { "" }, applyConfig = {},
            roster = { emptyList() }, isSnapshotChar = { _, _ -> false }, pairedDevices = { emptyList() },
            rememberPaired = { _, _, _ -> }, onModeApplied = { _, _, _ -> }, secretBox = box,
        )
        try {
            assertEquals(controller.deviceId, other.deviceId, "the install id is stable")
            assertTrue(other.instanceId != controller.instanceId, "the advertised id is not")
        } finally {
            other.close()
        }
    }

    @Test
    fun `a relayed control is checked before it is dispatched`() = runBlocking {
        assertIs<CommandOutcome.Refused>(controller.handleRelayedControl("machineState", "AIR_PURGE", "phone", "Phone"))
        assertIs<CommandOutcome.Refused>(controller.handleRelayedControl("setQcSteamTemp", "NaN", "phone", "Phone"))
        assertIs<CommandOutcome.Refused>(controller.handleRelayedControl("writeMmr", "1", "phone", "Phone"))
        assertTrue(actions.applied.isEmpty(), "nothing refused reaches the verbs: ${actions.applied}")

        assertEquals(CommandOutcome.Accepted, controller.handleRelayedControl("machineState", "IDLE", "phone", "Phone"))
        assertEquals(listOf("machineState(IDLE)"), actions.applied.toList())
    }

    @Test
    fun `this device's commands run locally until it mirrors`() {
        controller.machine.execute(MachineCommand.SetQc(QcField.GRIND, 14.5f))
        assertEquals(listOf("commit(${MachineCommand.SetQc(QcField.GRIND, 14.5f)})"), actions.applied.toList())

        controller.switchToSecondary("127.0.0.1", 9)
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        actions.applied.clear()
        controller.machine.execute(MachineCommand.TareScale)
        assertTrue(actions.applied.isEmpty(), "a mirror forwards machine control and applies nothing itself")

        controller.switchToNormal()
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        controller.machine.execute(MachineCommand.TareScale)
        assertEquals(listOf("tareScale"), actions.applied.toList())
    }

    @Test
    fun `only the user's pick clears the pin, an unattended re-mirror keeps it`() {
        assertEquals(ProxyController.MirrorTrust(repin = false, allowRepair = false), ProxyController.MirrorTrust.UNATTENDED)
        controller.pairingStore.pinHost("host-1")

        controller.remirrorUnattended("127.0.0.1", 9)
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        assertEquals("applied:secondary", events.last { it.startsWith("applied") })
        assertEquals("host-1", controller.pairingStore.pinnedHost, "an unattended re-mirror leaves the pin alone")

        controller.switchToSecondary("127.0.0.1", 9)
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        assertEquals(null, controller.pairingStore.pinnedHost, "the user's pick starts a new pin")
    }

    @Test
    fun `each queued secondary switch keeps its own trust`() {
        controller.pairingStore.pinHost("host-1")
        // A user pick, then an unattended re-mirror, both queued before either runs.
        controller.switchToSecondary("127.0.0.1", 9)
        controller.remirrorUnattended("127.0.0.1", 9)
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        assertEquals(listOf("applied:secondary", "applied:secondary"), events.filter { it.startsWith("applied") })
        assertNull(controller.pairingStore.pinnedHost, "the user's pick (run first) cleared the pin; the unattended one kept it cleared")
    }

    @Test
    fun `forgetting a host on this device drops its secret and its pin`() {
        controller.pairingStore.putHeld("host-1", PairingCrypto.newSecret())
        controller.pairingStore.pinHost("host-1")
        controller.forgetHost("host-1")
        assertNull(controller.pairingStore.heldSecret("host-1"))
        assertNull(controller.pairingStore.pinnedHost)
        assertNull(controller.state.value.changedHost)
    }

    @Test
    fun `denied reasons map to what the secondary shows`() {
        assertTrue(deniedMessage(DenyReason.PAIRING_CLOSED).contains("Pair a device"))
        assertTrue(deniedMessage(DenyReason.VERSION_MISMATCH).contains("Update Crema"))
        assertTrue(deniedMessage("version mismatch — update Crema on both devices").contains("Update Crema"))
        assertTrue(deniedMessage(DenyReason.REVOKED).contains("pair again"))
        assertTrue(deniedMessage(DenyReason.BUSY).contains("busy"))
        assertTrue(deniedMessage(DenyReason.ALREADY_PAIRED).contains("Forget"))
        assertTrue(deniedMessage(DenyReason.HOST_MISMATCH).contains("Other devices"))
        assertTrue(deniedMessage(DenyReason.HOST_UNVERIFIED).contains("Other devices"))
        assertTrue(deniedMessage(DenyReason.HOST_CHANGED).contains("forget it on this device"))
        assertEquals("The host hasn't allowed this device", deniedMessage(DenyReason.NOT_AUTHORIZED))
    }

    /** AC-6: the Hub mode switch applies live, both ways, with no restart. */
    @Test
    fun `hub mode switches live`() {
        controller.setHubMode(true)
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        assertEquals("primary", controller.state.value.role)
        assertEquals(listOf("teardown", "applied:primary", "connect"), events.toList(), "on: reconnect as the hub")

        events.clear()
        controller.setHubMode(false)
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        assertEquals("normal", controller.state.value.role)
        assertTrue("applied:normal" in events, "$events")
    }

    /** AC-6: turning Hub mode off frees the machine. The hub steps down with a
     *  full user disconnect (no reconnect loop, no scan left running) and
     *  never calls connect, so another device can take the DE1. A flash
     *  refuses the switch either way. */
    @Test
    fun `hub mode off leaves the machine free`() {
        controller.modeSwitchProbe = { role -> events += "probe:$role" }
        controller.setHubMode(true)
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        events.clear()

        controller.setHubMode(false)
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()
        assertEquals(listOf("probe:normal", "disconnect", "teardown", "applied:normal"), events.toList())
        assertTrue("connect" !in events, "the stepped-down hub must not reconnect: $events")

        // During a flash neither direction moves the radio.
        events.clear()
        flashing = true
        controller.setHubMode(true)
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        assertEquals("normal", controller.state.value.role)
        assertTrue(events.isEmpty(), "$events")
        assertTrue(log.any { it.contains("refused") && it.contains(FIRMWARE_BUSY_REASON) }, "$log")
    }

    /** AC-9: once its session has gone unwelcomed for refindDelayMs, a remote
     *  dials a hub NSD lists on its saved host at a new port — with or
     *  without the DE1 — unattended, so the pin stays. Itself, its own
     *  target, a normal device, a port-less advertisement and a hub at any
     *  other address are not candidates. The attempt isn't saved until a
     *  Welcome. */
    @Test
    fun `a lost hub is re-found unattended`() {
        remoteOnDeadHub(
            peer("elsewhere", 13, host = "192.168.1.66"),
            peer(controller.instanceId, 10),
            peer("same-target", 9),
            peer("a-normal-device", 11, role = "normal"),
            peer("no-relay", 0),
            peer("nsd-restarted", 12, de1 = false),
        )
        advance(REFIND_DELAY_MS - 1_000)
        assertEquals("127.0.0.1:9", dials().last(), "not before the delay")
        advance(1_500)
        assertEquals("127.0.0.1:12", dials().last())
        assertEquals("host-1", controller.pairingStore.pinnedHost, "unattended: the pin is kept")
        assertEquals("secondary", controller.state.value.role)
        assertEquals("secondary 127.0.0.1:9", targets.last(), "an attempt isn't saved before its Welcome")
    }

    /** AC-9: a held re-find (P2's frozen slice) starts nothing; a release
     *  re-checks. */
    @Test
    fun `no re-find runs while it is held`() {
        controller.holdRefind(true)
        remoteOnDeadHub(peer("nsd-restarted", 12))
        advance(REFIND_DELAY_MS * 3)
        assertEquals("127.0.0.1:9", dials().last())
        controller.holdRefind(false)
        advance(200)
        assertEquals("127.0.0.1:12", dials().last())
    }

    /**
     * AC-9 as narrowed (validation P1b): a refused attempt goes back to the
     * saved target (a refused link never redials), and the next try waits
     * 5 s, then 15 s, then 60 s. Nothing is skipped by instance id, so the
     * same port is tried again. After three refusals the remote gives up
     * ("Couldn't verify <hub>") and stays a remote, until NSD lists a new
     * port on the saved host.
     */
    @Test
    fun `an unattended refusal keeps the remote role`() {
        val peers = remoteOnDeadHub(peer("a", 12))
        advance(REFIND_DELAY_MS + 200)
        assertEquals("127.0.0.1:12", dials().last())

        controller.refuseCurrentSessionForTest(DenyReason.HOST_MISMATCH, byHost = false, hostProved = false)
        advance(200)
        assertEquals("127.0.0.1:9", dials().last(), "the saved target is dialed again")
        assertEquals("secondary", controller.state.value.role)
        advance(REFIND_DELAY_MS - 400)
        assertEquals("127.0.0.1:9", dials().last(), "5 s back-off")
        advance(600)
        assertEquals("127.0.0.1:12", dials().last())

        controller.refuseCurrentSessionForTest(DenyReason.HOST_CHANGED, byHost = false, hostProved = false)
        advance(200)
        assertEquals("127.0.0.1:9", dials().last())
        advance(14_000)
        assertEquals("127.0.0.1:9", dials().last(), "15 s back-off")
        advance(1_200)
        assertEquals("127.0.0.1:12", dials().last())

        // A host's Denied before it proved the pairing is just as unverified.
        controller.refuseCurrentSessionForTest(DenyReason.REVOKED, byHost = true, hostProved = false)
        advance(200)
        assertEquals("127.0.0.1:9", dials().last())
        assertFalse(controller.state.value.refindGaveUp, "not before the 60 s back-off")
        advance(60_000)
        assertTrue(controller.state.value.refindGaveUp, "three refusals")
        assertEquals("secondary", controller.state.value.role)
        assertEquals("host-1", controller.pairingStore.pinnedHost)
        val dialed = dials().size
        advance(120_000)
        assertEquals(dialed, dials().size, "it stops trying")
        assertEquals(listOf("secondary 127.0.0.1:9"), targets.toList(), "no attempt was saved")

        peers.value = peers.value + peer("a-again", 15)
        advance(200)
        assertFalse(controller.state.value.refindGaveUp, "a new port on the saved host resumes the re-find")
        assertEquals("127.0.0.1:12", dials().last(), "the first listed port is tried")
    }

    /** Validation P1b S1/S3: the re-find never dials another address, so an
     *  advertiser elsewhere on the LAN can't relay or hide the hub, even
     *  with the hub's own instance id and a name that sorts first. */
    @Test
    fun `the re-find dials only the saved host`() {
        remoteOnDeadHub(
            Peer("nsd-hub", "A Kitchen tablet", "primary", true, "192.168.1.66", 12),
            Peer("nsd-hub", "A Kitchen tablet", "primary", true, "localhost", 14),
            Peer("nsd-hub", "Kitchen tablet", "primary", true, "127.0.0.1", 13),
        )
        advance(REFIND_DELAY_MS + 200)
        assertEquals("127.0.0.1:13", dials().last())
        controller.refuseCurrentSessionForTest(DenyReason.HOST_MISMATCH, byHost = false, hostProved = false)
        advance(REFIND_BACKOFF_MS.sum() + REFIND_DELAY_MS * 6)
        assertTrue(dials().all { it.startsWith("127.0.0.1:") }, "${dials()}")
    }

    /** Validation P1b S6: the re-find needs a target pinned to a host this
     *  device paired with, so it never runs from a user's pick still waiting
     *  for its Welcome (the pick clears the pin). */
    @Test
    fun `no re-find from an unpinned target or an unwelcomed user pick`() {
        controller.switchToSecondary("127.0.0.1", 9)
        advance(200)
        controller.overridePeers(MutableStateFlow(listOf(peer("nsd-restarted", 12))))
        advance(REFIND_DELAY_MS * 4)
        assertEquals(listOf("127.0.0.1:9"), dials(), "the user's pick, unpinned")

        controller.pairingStore.pinHost("host-1")
        advance(REFIND_DELAY_MS * 4)
        assertEquals(listOf("127.0.0.1:9"), dials(), "a pin doesn't make an unwelcomed pick unattended")
    }

    /** Validation P1b S2 / M3, the handshake deadline: an attempt that isn't
     *  welcomed within refindDelayMs ends, whatever its socket does. It
     *  counts as a refusal, the remote goes back to the saved target, and
     *  the attempt's port is never saved. */
    @Test
    fun `an attempt not welcomed in time ends and is not saved`() {
        remoteOnDeadHub(peer("silent", 12))
        advance(REFIND_DELAY_MS + 200)
        assertEquals("127.0.0.1:12", dials().last())
        advance(REFIND_DELAY_MS - 400)
        assertEquals("127.0.0.1:12", dials().last(), "within the deadline")
        advance(600)
        assertEquals("127.0.0.1:9", dials().last(), "the deadline ended it")
        advance(REFIND_BACKOFF_MS.sum() + REFIND_DELAY_MS * 6)
        assertTrue(controller.state.value.refindGaveUp, "three unanswered attempts: ${dials()}")
        assertEquals(3, dials().count { it == "127.0.0.1:12" })
        assertEquals("127.0.0.1:9", dials().last())
        assertEquals(listOf("secondary 127.0.0.1:9"), targets.toList(), "never saved")
        assertEquals("secondary", controller.state.value.role)
    }

    /** AC-9 as narrowed (validation P1b S5, S7): only a Denied from a host
     *  that proved the pairing in this session, or a refused user pick
     *  before its Welcome, make a remote normal. Any unproven refusal of the
     *  saved target keeps the role, and the saved target is dialed again
     *  after the back-off. */
    @Test
    fun `only a proved denied or a refused user pick makes a remote normal`() {
        remoteOnDeadHub()
        controller.refuseCurrentSessionForTest(DenyReason.HOST_UNVERIFIED, byHost = false, hostProved = false)
        advance(1_000)
        assertEquals("secondary", controller.state.value.role, "the saved target refused for trust: still a remote")
        advance(REFIND_BACKOFF_MS[0])
        assertEquals(listOf("127.0.0.1:9", "127.0.0.1:9"), dials(), "dialed again after the back-off")

        controller.refuseCurrentSessionForTest(DenyReason.PAIRING_CLOSED, byHost = true, hostProved = false)
        advance(1_000)
        assertEquals("secondary", controller.state.value.role, "a bare Denied from the saved target: still a remote")
        controller.refuseCurrentSessionForTest(DenyReason.BUSY, byHost = true, hostProved = false)
        advance(1_000)
        assertEquals("secondary", controller.state.value.role)

        controller.remirrorUnattended("127.0.0.1", 9)
        advance(200)
        controller.refuseCurrentSessionForTest(DenyReason.REVOKED, byHost = true, hostProved = true)
        advance(1_000)
        assertEquals("normal", controller.state.value.role, "a Denied after the host proved the pairing")

        // A re-find attempt that proved the pairing and then refused us.
        remoteOnDeadHub(peer("a", 12))
        advance(REFIND_DELAY_MS + 200)
        assertEquals("127.0.0.1:12", dials().last())
        controller.refuseCurrentSessionForTest(DenyReason.VERSION_MISMATCH, byHost = false, hostProved = true)
        advance(1_000)
        assertEquals("normal", controller.state.value.role, "a version refusal after a proof")

        controller.switchToSecondary("127.0.0.1", 9) // the user's pick
        advance(200)
        controller.refuseCurrentSessionForTest(DenyReason.HOST_MISMATCH, byHost = false, hostProved = false)
        advance(1_000)
        assertEquals("normal", controller.state.value.role, "a refused user pick")
    }

    /** Run the controller's scope until [done] holds, for work that crosses
     *  a real socket (the relay server's bind). */
    private fun runUntil(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $what" }
            scheduler.runCurrent()
            Thread.sleep(5)
        }
    }

    /** Owner decision (validation P1b): the hub binds its last relay port
     *  again, so a remote's saved host:port reaches it after a restart. A
     *  taken port falls back to an ephemeral one, which is saved instead. */
    @Test
    fun `the hub binds its last relay port again`() {
        val portFile = java.io.File(ApplicationProvider.getApplicationContext<Application>().filesDir, "relayPort")
        val wanted = java.net.ServerSocket(0).use { it.localPort }
        portFile.writeText("$wanted")
        controller.setHubMode(true)
        advance(1_000)
        runUntil("the relay") { log.any { "LAN relay listening" in it } }
        assertTrue("LAN relay listening on :$wanted" in log, "$log")
        controller.setHubMode(false)
        advance(1_000)

        java.net.ServerSocket(wanted).use {
            log.clear()
            controller.setHubMode(true)
            advance(1_000)
            runUntil("the relay") { log.any { "LAN relay listening" in it } }
            val bound = log.first { "LAN relay listening" in it }.substringAfter(":").toInt()
            assertTrue(bound != wanted && bound > 0, "$log")
            assertEquals("$bound", portFile.readText().trim(), "the fallback port is the one saved")
            controller.setHubMode(false)
            advance(1_000)
        }
    }

    /** P2a validation: a restarting hub doesn't serve before its prefs (the
     *  paired devices) are loaded, so a paired remote that dials at once meets
     *  a closed port and redials, and is admitted once loading finishes: no
     *  Denied, so nothing for it to count or demote on. */
    @Test
    fun `a loading hub doesn't refuse a paired remote`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val port = java.net.ServerSocket(0).use { it.localPort }
        java.io.File(app.filesDir, "relayPort").writeText("$port")
        val paired = CopyOnWriteArrayList<PairedDevice>()
        val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()
        val hub = ProxyController(
            app = app, json = Json { ignoreUnknownKeys = true }, scope = scope,
            setCoreReadOnly = {}, setRecorderEnabled = {}, notify = {}, appendLog = { log += it }, connect = {}, disconnect = {},
            disconnectDevices = {}, connectScale = {}, scaleAttachWanted = { false }, de1Connected = { false },
            machineStateName = { null }, machineActions = RecordingMachineActions(), snapshotConfig = { "" }, applyConfig = {},
            roster = { emptyList() }, isSnapshotChar = { _, _ -> false }, pairedDevices = { paired.toList() },
            rememberPaired = { _, _, _ -> }, onModeApplied = { _, _, _ -> }, secretBox = box,
            prefsReady = { loaded.await() },
        )
        val secret = PairingCrypto.newSecret()
        hub.pairingStore.putIssued("phone-1", secret)
        val remoteScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val refusals = CopyOnWriteArrayList<String>()
        val welcomed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            hub.setHubMode(true)
            advance(1_000)
            // The remote dials the hub's saved port straight away.
            val link = coffee.crema.ble.proxy.ReconnectingClientLink("ws://127.0.0.1:$port${coffee.crema.ble.proxy.LanRelayServer.PATH}", remoteScope)
            coffee.crema.ble.proxy.ProxyTransport(
                link, remoteScope, "phone-1", "Phone",
                pairingSecretFor = { secret }, allowRepair = false,
                onRefused = { reason, _, _ -> refusals += reason },
                onWelcome = { _, _ -> welcomed.set(true) },
            )
            val until = System.nanoTime() + 1_500_000_000L
            while (System.nanoTime() < until) {
                scheduler.runCurrent()
                Thread.sleep(10)
            }
            assertTrue(log.none { "LAN relay listening" in it }, "not serving while loading: $log")
            assertEquals(emptyList(), refusals.toList(), "no Denied while loading")
            assertFalse(welcomed.get())

            // Loading finishes: the paired devices are known, the relay serves.
            paired += PairedDevice("phone-1", "Phone", canControl = true)
            loaded.complete(Unit)
            runUntil("the relay") { log.any { "LAN relay listening on :$port" in it } }
            runUntil("the remote's welcome") { welcomed.get() }
            assertEquals(emptyList(), refusals.toList(), "no refusal at any point")
        } finally {
            remoteScope.cancel()
            hub.close()
        }
    }

    /** A user's switch queued before the re-find's own wins: the re-find
     *  never moves a device the user just took out of the remote role. */
    @Test
    fun `a user's switch beats a queued re-find`() {
        remoteOnDeadHub(peer("a", 12))
        // The CONNECTING clock started 150 ms into remoteOnDeadHub's 200 ms:
        // it fires at 5 150 ms, inside the user's switch's settle delay.
        advance(REFIND_DELAY_MS - 100)
        controller.switchToNormal()
        advance(1_000)
        assertEquals("normal", controller.state.value.role)
        advance(REFIND_DELAY_MS * 2)
        assertEquals("normal", controller.state.value.role)
        assertTrue(dials().none { it == "127.0.0.1:12" }, "${dials()}")
        assertTrue(log.any { "a newer switch ran first" in it }, "the re-find was queued and skipped: $log")
    }

    /** T7: NSD listing the hub's address as a device that isn't a hub (it
     *  turned Hub mode off) is what "<hub> isn't a hub any more" shows. */
    @Test
    fun `NSD showing the hub's address as a normal device marks it stopped hosting`() {
        val peers = remoteOnDeadHub()
        assertFalse(controller.state.value.hubStoppedHosting)
        peers.value = listOf(peer("ex-hub", 0, role = "normal"))
        scheduler.runCurrent()
        assertTrue(controller.state.value.hubStoppedHosting)
        peers.value = listOf(peer("ex-hub", 0, role = "normal", host = "192.168.1.30"))
        scheduler.runCurrent()
        assertFalse(controller.state.value.hubStoppedHosting, "another address")
    }

    /** Validation D8: a flash refuses turning Hub mode off too: the hub keeps
     *  the machine rather than moving the radio mid-flash. */
    @Test
    fun `a flash refuses turning hub mode off`() {
        controller.setHubMode(true)
        advance(1_000)
        events.clear()
        flashing = true
        controller.setHubMode(false)
        advance(1_000)
        assertEquals("primary", controller.state.value.role)
        assertTrue(events.isEmpty(), "$events")
        assertTrue(log.any { it.contains("Mode switch to normal refused") && it.contains(FIRMWARE_BUSY_REASON) }, "$log")
    }

    /** Validation D5: whether this device holds a secret for its pinned hub
     *  is cached in the state and needs no keystore decrypt, however often
     *  the state changes. */
    @Test
    fun `the pinned-hub flag is cached and never decrypts`() {
        var opens = 0
        val countingBox = SecretBox(object : SecretCipher {
            override fun seal(plain: ByteArray) = ByteArray(12) + plain
            override fun open(sealed: ByteArray): ByteArray {
                opens++
                return sealed.copyOfRange(12, sealed.size)
            }
        })
        controller.pairingStore.putHeld("host-1", PairingCrypto.newSecret())
        controller.pairingStore.pinHost("host-1")
        val other = ProxyController(
            app = ApplicationProvider.getApplicationContext(), json = Json { ignoreUnknownKeys = true }, scope = scope,
            setCoreReadOnly = {}, setRecorderEnabled = {}, notify = {}, appendLog = {}, connect = {}, disconnect = {},
            disconnectDevices = {}, connectScale = {}, scaleAttachWanted = { false }, de1Connected = { false },
            machineStateName = { null }, machineActions = RecordingMachineActions(), snapshotConfig = { "" }, applyConfig = {},
            roster = { emptyList() }, isSnapshotChar = { _, _ -> false }, pairedDevices = { emptyList() },
            rememberPaired = { _, _, _ -> }, onModeApplied = { _, _, _ -> }, secretBox = countingBox,
        )
        try {
            assertTrue(other.state.value.pairedHubPinned)
            val peers = MutableStateFlow<List<Peer>>(emptyList())
            other.overridePeers(peers)
            repeat(50) { i ->
                peers.value = listOf(peer("p$i", 1_000 + i))
                scheduler.runCurrent()
            }
            assertEquals(0, opens, "no decrypt per state change")
            other.forgetHost("host-1")
            assertFalse(other.state.value.pairedHubPinned, "forgetting the host refreshes the cached flag")
        } finally {
            other.close()
        }
    }
}
