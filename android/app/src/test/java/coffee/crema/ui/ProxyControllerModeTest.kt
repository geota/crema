package coffee.crema.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.ble.proxy.PairingState
import coffee.crema.ble.proxy.Peer
import coffee.crema.ble.proxy.ProxyTransport
import coffee.crema.ble.proxy.ReconnectingClientLink
import coffee.crema.ble.proxy.LanRelayServer
import coffee.crema.ble.proxy.RelayHubPin
import coffee.crema.ble.proxy.RelayKeyLoad
import coffee.crema.ble.proxy.RelayKeyStore
import coffee.crema.ble.proxy.RelayRemotePin
import coffee.crema.ble.proxy.SCOPE_CONTROL
import coffee.crema.core.RelayKeys
import coffee.crema.machine.CommandOutcome
import coffee.crema.machine.MachineCommand
import coffee.crema.machine.QcField
import coffee.crema.machine.RecordingMachineActions
import coffee.crema.security.FakeKeystore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ProxyController] mode switching, the relay v4 pins and keys, refusal
 * handling and the re-find, on virtual time. Built in the normal role (no
 * prefs file), with every VM callback recorded and the relay key store over a
 * fake keystore (Robolectric has no AndroidKeyStore).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ProxyControllerModeTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val events = CopyOnWriteArrayList<String>()
    private val log = CopyOnWriteArrayList<String>()
    private val notices = CopyOnWriteArrayList<String>()
    private val actions = RecordingMachineActions()
    @Volatile private var flashing = false

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val keystore = FakeKeystore()

    /** The store this test's controller loads (the same file and keystore). */
    private fun relayStore() = RelayKeyStore(app.filesDir, RelayKeyStore.deviceBox(keystore))

    /** A remote this hub paired before this launch, pinned in the store the controller loads. */
    private val pairedRemote: RelayKeys = RelayKeys.generate()

    init {
        relayStore().apply {
            load()
            pinRemote(RelayRemotePin(pairedRemote.deviceId(), RelayKeyStore.b64(pairedRemote.publicKey()), "Phone", SCOPE_CONTROL, pairedAtMs = 1))
        }
    }

    private fun newController(
        appendLog: (String) -> Unit = { log += it },
        prefsReady: suspend () -> Unit = {},
    ) = ProxyController(
        app = app,
        json = Json { ignoreUnknownKeys = true },
        scope = scope,
        setCoreReadOnly = {},
        setRecorderEnabled = {},
        notify = { notices += it },
        appendLog = appendLog,
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
        onModeApplied = { role, host, port -> events += "applied:$role"; targets += "$role $host:$port" },
        firmwareHoldsLink = { flashing },
        relayStore = relayStore(),
        prefsReady = prefsReady,
    )

    /** Every applied mode with its target, in order. */
    private val targets = CopyOnWriteArrayList<String>()

    private val controller = newController()

    /** Every remote target this controller dialed, as host:port, in order:
     *  saved targets and re-find attempts alike. */
    private fun dials(): List<String> =
        log.filter { "SECONDARY →" in it && "Not dialing" !in it }.map { it.substringAfter("ws://").substringBefore("/") }
            .filterIndexed { i, _ -> !notDialed(i) }

    /** Whether the [i]th SECONDARY switch didn't dial (no pin or no keys). */
    private fun notDialed(i: Int): Boolean {
        val switches = log.withIndex().filter { "SECONDARY →" in it.value }
        val at = switches.getOrNull(i)?.index ?: return false
        return log.getOrNull(at + 1)?.startsWith("Not dialing") == true
    }

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    /** An NSD peer. */
    private fun peer(id: String, port: Int, role: String = "primary", de1: Boolean = true, host: String = "127.0.0.1") =
        Peer(id, "Kitchen tablet", role, de1, host, port)

    private val hubKey: ByteArray = RelayKeys.generate().publicKey()

    /** Pin the hub "hub-1" at 127.0.0.1:[port] as this device's saved hub (what pairing leaves). */
    private fun pinHub(port: Int = 9) {
        controller.relayStore.pinHub(RelayHubPin("hub-1", RelayKeyStore.b64(hubKey), "Kitchen tablet", "127.0.0.1", port, pinnedAtMs = 1))
        controller.relayStore.setSavedHub("hub-1")
    }

    /** A remote on 127.0.0.1:9 (nothing listens there: its link stays
     *  CONNECTING), restored unattended with the hub pinned. Returns the
     *  peers flow the test drives. */
    private fun remoteOnDeadHub(vararg peers: Peer): MutableStateFlow<List<Peer>> {
        pinHub()
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

    // ── Relay v4 keys and pins ─────────────────────────────────────────────

    @Test
    fun `the store's pins and key load at construction`() {
        assertFalse(controller.state.value.relayKeysUnavailable)
        assertEquals(listOf(pairedRemote.deviceId()), controller.state.value.pairedRemotes.map { it.id })
    }

    @Test
    fun `the pairing window opens and closes through the core gate`() {
        controller.openPairingWindow()
        scheduler.runCurrent()
        assertTrue(controller.state.value.pairingSecondsLeft in (PAIRING_WINDOW_S - 1)..PAIRING_WINDOW_S, "${controller.state.value}")
        controller.closePairingWindow()
        scheduler.runCurrent()
        assertEquals(0, controller.state.value.pairingSecondsLeft)
    }

    @Test
    fun `a mode switch closes the pairing window`() {
        controller.openPairingWindow()
        scheduler.runCurrent()
        controller.switchToNormal()
        advance(1_000)
        assertEquals(0, controller.state.value.pairingSecondsLeft)
    }

    @Test
    fun `forgetting a remote drops its pin`() {
        controller.forgetRemote(pairedRemote.deviceId())
        assertTrue(controller.state.value.pairedRemotes.isEmpty())
        assertTrue(relayStore().also { it.load() }.remotes.isEmpty(), "the store forgot it too")
    }

    @Test
    fun `resetting the pairing key clears every pin and makes a remote normal`() {
        remoteOnDeadHub()
        val before = (relayStore().load() as RelayKeyLoad.Ready).keys.deviceId()
        controller.resetPairingKey()
        advance(1_000)
        assertEquals("normal", controller.state.value.role)
        assertTrue(controller.state.value.pairedRemotes.isEmpty())
        assertTrue(controller.state.value.pairedHubs.isEmpty())
        val after = (relayStore().load() as RelayKeyLoad.Ready).keys.deviceId()
        assertNotEquals(before, after, "a new key")
    }

    @Test
    fun `a keystore that isn't ready keeps the relay off and resetting is the way out`() {
        keystore.notReady = true
        val unavailable = newController()
        try {
            assertTrue(unavailable.state.value.relayKeysUnavailable)
            unavailable.setHubMode(true)
            advance(1_000)
            assertTrue(log.any { "LAN relay not started" in it }, "$log")
            assertTrue(log.none { "LAN relay listening" in it })
            keystore.notReady = false
            unavailable.resetPairingKey()
            advance(1_000)
            assertFalse(unavailable.state.value.relayKeysUnavailable, "a new key after the user's reset")
            assertTrue(java.io.File(app.filesDir, RelayKeyStore.LOST_DIR).listFiles()!!.isNotEmpty(), "the unreadable file kept aside")
        } finally {
            unavailable.close()
        }
    }

    @Test
    fun `the NSD instance id is random per launch`() {
        val other = newController(appendLog = {})
        try {
            assertTrue(other.instanceId != controller.instanceId)
        } finally {
            other.close()
        }
    }

    /** Security review (PR-5): an older remote's name comes from an
     *  unauthenticated hello. 1000 distinct names in a minute give one notice
     *  and one log line, with nothing kept per name; the next minute's gives
     *  one more, counting the rest. The name shown is sanitized. */
    @Test
    fun `older-remote notices are one a minute in all, whatever the names`() {
        val controller = newController()
        var now = 1_000_000L
        controller.outdatedNoticeClock = { now }
        repeat(1_000) { controller.legacyPeerForTest("Phone $it") }
        assertEquals(listOf("Phone 0 runs an older Crema — update it to connect."), notices.filter { "older Crema" in it })
        assertEquals(1, log.count { "older Crema version" in it })
        now += 60_000
        controller.legacyPeerForTest("Tablet")
        assertEquals(2, notices.count { "older Crema" in it })
        assertTrue(log.any { it == "Refused Tablet: older Crema version (999 more refused in the last minute)" }, "$log")
        // No per-name state: nothing in the controller grows with the names.
        assertTrue(ProxyController::class.java.declaredFields.none { java.util.Map::class.java.isAssignableFrom(it.type) && "outdated" in it.name.lowercase() })
        controller.close()
    }

    @Test
    fun `an older remote's name is sanitized before it is shown`() {
        val controller = newController()
        controller.legacyPeerForTest("\u202Eevil\u0000 phone ${"x".repeat(100)}")
        val shown = notices.single { "older Crema" in it }
        assertTrue('\u202E' !in shown && '\u0000' !in shown, shown)
        assertTrue(shown.startsWith("evil phone "), shown)
        assertTrue(shown.removeSuffix(" runs an older Crema — update it to connect.").length <= 32, shown)
        controller.close()
    }

    @Test
    fun `refusals map to what the remote shows`() {
        assertTrue(refusalMessage("Revoked", "Hub").contains("removed this device"))
        assertTrue(refusalMessage("NotPaired", "Hub").contains("Pair again"))
        assertTrue(refusalMessage("HubStopped", "Hub").contains("turned off Hub mode"))
        assertTrue(refusalMessage("HubUnverified", "Hub").startsWith("Couldn't verify Hub"))
        assertTrue(refusalMessage("HostMismatch", "Hub").startsWith("Couldn't verify Hub"))
        assertTrue(refusalMessage("HubOutdated", "Hub").contains("older Crema"))
        assertTrue(refusalMessage("Version", "Hub").contains("Update Crema"))
        assertTrue(refusalMessage("PairingClosed", "Hub").contains("Pair a device"))
        assertTrue(refusalMessage("Busy", "Hub").contains("pairing another device"))
        assertTrue(refusalMessage("LockedOut", "Hub").contains("too many wrong codes"))
        assertTrue(refusalMessage("Cancelled", "Hub").contains("cancelled"))
        assertTrue(refusalMessage("Timeout", "Hub").contains("expired"))
    }

    // ── Commands and roles ─────────────────────────────────────────────────

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

        pinHub()
        controller.switchToSecondary("127.0.0.1", 9)
        advance(1_000)
        actions.applied.clear()
        controller.machine.execute(MachineCommand.TareScale)
        assertTrue(actions.applied.isEmpty(), "a mirror forwards machine control and applies nothing itself")

        controller.switchToNormal()
        advance(1_000)
        controller.machine.execute(MachineCommand.TareScale)
        assertEquals(listOf("tareScale"), actions.applied.toList())
    }

    @Test
    fun `a picked hub this device pinned resumes, an unpinned one pairs first`() {
        controller.switchToSecondary("127.0.0.1", 9)
        advance(200)
        assertTrue(dials().isEmpty(), "an unpinned hub is never dialed for a resume")
        assertTrue(controller.state.value.pairing !is PairingState.Idle, "the pick started pairing")
        assertTrue(events.none { it == "applied:secondary" }, "no role change before pairing")
        controller.cancelRemotePairing()

        pinHub()
        controller.switchToSecondary("127.0.0.1", 9)
        advance(1_000)
        assertEquals(listOf("127.0.0.1:9"), dials(), "a pinned hub resumes")
        assertEquals("applied:secondary", events.last { it.startsWith("applied") })
    }

    @Test
    fun `a remote without a hub pin doesn't dial`() {
        controller.remirrorUnattended("127.0.0.1", 9)
        advance(1_000)
        assertEquals("secondary", controller.state.value.role)
        assertTrue(dials().isEmpty())
        assertTrue(log.any { "has to pair with the hub again" in it }, "$log")
    }

    @Test
    fun `forgetting the saved hub on a remote makes it normal`() {
        remoteOnDeadHub()
        assertTrue(controller.state.value.let { it.pairedHubs.isNotEmpty() || controller.relayStore.hubs.isNotEmpty() })
        controller.forgetHub("hub-1")
        advance(1_000)
        assertEquals("normal", controller.state.value.role)
        assertTrue(controller.relayStore.hubs.isEmpty())
        assertFalse(controller.state.value.pairedHubPinned)
    }

    /** AC-6: the Hub mode switch applies live, both ways, with no restart. */
    @Test
    fun `hub mode switches live`() {
        controller.setHubMode(true)
        advance(1_000)
        assertEquals("primary", controller.state.value.role)
        assertEquals(listOf("teardown", "applied:primary", "connect"), events.toList(), "on: reconnect as the hub")

        events.clear()
        controller.setHubMode(false)
        advance(1_000)
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
        advance(1_000)
        events.clear()

        controller.setHubMode(false)
        advance(10_000)
        assertEquals(listOf("probe:normal", "disconnect", "teardown", "applied:normal"), events.toList())
        assertTrue("connect" !in events, "the stepped-down hub must not reconnect: $events")

        // During a flash neither direction moves the radio.
        events.clear()
        flashing = true
        controller.setHubMode(true)
        advance(1_000)
        assertEquals("normal", controller.state.value.role)
        assertTrue(events.isEmpty(), "$events")
        assertTrue(log.any { it.contains("refused") && it.contains(FIRMWARE_BUSY_REASON) }, "$log")
    }

    // ── The re-find (spec §3.3, narrowed by validation P1b) ─────────────────

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
        assertEquals("hub-1", controller.relayStore.savedHub, "unattended: the pin is kept")
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

    /** #207 validation P2-1: while the re-find is held (the leave-remote
     *  prompt is open), an attempt in flight that ends, by a refusal or its
     *  deadline, switches nothing; the release goes back to the saved target. */
    @Test
    fun `a held re-find doesn't switch when an attempt ends`() {
        remoteOnDeadHub(peer("a", 12))
        advance(REFIND_DELAY_MS + 200)
        assertEquals("127.0.0.1:12", dials().last())
        val switches = events.count { it == "teardown" }

        controller.holdRefind(true)
        controller.refuseCurrentSessionForTest("HostMismatch", authenticated = false)
        advance(1_000)
        assertEquals("127.0.0.1:12", dials().last(), "no go-back while held")
        assertEquals(switches, events.count { it == "teardown" }, "no switch at all while held")
        assertEquals("secondary", controller.state.value.role)

        // Released before the attempt's deadline: the refusal itself ends it.
        controller.holdRefind(false)
        advance(200)
        assertEquals("127.0.0.1:9", dials().last(), "the release goes back to the saved target")
        // Counted once: the first back-off (5 s), then the port again.
        advance(REFIND_BACKOFF_MS[0] + 200)
        assertEquals("127.0.0.1:12", dials().last(), "one refusal, the first back-off: ${dials()}")
    }

    /** The same for an attempt that only times out, and for a re-find switch
     *  already queued when the hold is set: it is dropped at the mode lock. */
    @Test
    fun `a held re-find drops a queued switch and an unanswered attempt's go-back`() {
        remoteOnDeadHub(peer("a", 12))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        controller.modeSwitchProbe = { gate.await() }
        advance(REFIND_DELAY_MS + 200) // the attempt's switch is queued, waiting at the lock
        controller.holdRefind(true)
        gate.complete(Unit)
        advance(1_000)
        assertEquals("127.0.0.1:9", dials().last(), "the queued switch was dropped")
        assertTrue(log.any { "dropping a queued switch" in it }, "$log")
        controller.modeSwitchProbe = null

        controller.holdRefind(false)
        advance(200)
        assertEquals("127.0.0.1:12", dials().last(), "the release re-checks")
        controller.holdRefind(true)
        advance(REFIND_DELAY_MS * 4)
        assertEquals("127.0.0.1:12", dials().last(), "the deadline passed while held: no go-back")
        controller.holdRefind(false)
        advance(200)
        assertEquals("127.0.0.1:9", dials().last())
    }

    /**
     * AC-9 and AC-27 (controller half): an advisory refusal of a re-find
     * attempt goes back to the saved target (a refused link never redials),
     * and the next try waits 5 s, then 15 s, then 60 s. After three in a row
     * the remote gives up ("Couldn't verify <hub>") and stays a remote, with
     * its pin, until NSD lists a new port on the saved host.
     */
    @Test
    fun `an advisory refusal keeps the remote role`() {
        val peers = remoteOnDeadHub(peer("a", 12))
        advance(REFIND_DELAY_MS + 200)
        assertEquals("127.0.0.1:12", dials().last())

        controller.refuseCurrentSessionForTest("HostMismatch", authenticated = false)
        advance(200)
        assertEquals("127.0.0.1:9", dials().last(), "the saved target is dialed again")
        assertEquals("secondary", controller.state.value.role)
        advance(REFIND_DELAY_MS - 400)
        assertEquals("127.0.0.1:9", dials().last(), "5 s back-off")
        advance(600)
        assertEquals("127.0.0.1:12", dials().last())

        controller.refuseCurrentSessionForTest("HubUnverified", authenticated = false)
        advance(200)
        assertEquals("127.0.0.1:9", dials().last())
        advance(14_000)
        assertEquals("127.0.0.1:9", dials().last(), "15 s back-off")
        advance(1_200)
        assertEquals("127.0.0.1:12", dials().last())

        // An ABORT is just as advisory.
        controller.refuseCurrentSessionForTest("BUSY", authenticated = false)
        advance(200)
        assertEquals("127.0.0.1:9", dials().last())
        assertFalse(controller.state.value.refindGaveUp, "not before the 60 s back-off")
        advance(60_000)
        assertTrue(controller.state.value.refindGaveUp, "three refusals")
        assertEquals("secondary", controller.state.value.role)
        assertEquals("hub-1", controller.relayStore.savedHub, "the pin is kept")
        val dialed = dials().size
        advance(120_000)
        assertEquals(dialed, dials().size, "it stops trying")
        assertEquals(listOf("secondary 127.0.0.1:9"), targets.toList(), "no attempt was saved")

        peers.value = peers.value + peer("a-again", 15)
        advance(200)
        assertFalse(controller.state.value.refindGaveUp, "a new port on the saved host resumes the re-find")
        assertEquals("127.0.0.1:15", dials().last(), "the port not tried yet goes first")
    }

    /** Validation P1b R2: the least recently tried port on the saved host
     *  goes first, so an advertisement that sorts first can't take every
     *  attempt. */
    @Test
    fun `the least recently tried port goes first`() {
        remoteOnDeadHub(peer("a-bad", 12), peer("b-hub", 13))
        advance(REFIND_DELAY_MS + 200)
        assertEquals("127.0.0.1:12", dials().last(), "NSD's order for ports never tried")
        controller.refuseCurrentSessionForTest("HostMismatch", authenticated = false)
        advance(REFIND_BACKOFF_MS[0] + REFIND_DELAY_MS)
        assertEquals("127.0.0.1:13", dials().last(), "the other port next")
        controller.refuseCurrentSessionForTest("HostMismatch", authenticated = false)
        advance(REFIND_BACKOFF_MS[1] + REFIND_DELAY_MS)
        assertEquals("127.0.0.1:12", dials().last(), "then the one tried longest ago")
    }

    /** Validation P1b R3: after a give-up with the saved target refused,
     *  the hub back on its saved port under a new instance resumes the
     *  re-find, and the saved target is dialed again. */
    @Test
    fun `a hub back on its saved port resumes after a give-up`() {
        val peers = remoteOnDeadHub(peer("hub-before", 9))
        repeat(3) {
            controller.refuseCurrentSessionForTest("BUSY", authenticated = false)
            advance(REFIND_BACKOFF_MS[it] + 200)
        }
        assertTrue(controller.state.value.refindGaveUp, "three advisory refusals: ${dials()}")
        val dialed = dials().size
        advance(120_000)
        assertEquals(dialed, dials().size, "stopped")

        peers.value = listOf(peer("hub-restarted", 9))
        advance(200)
        assertFalse(controller.state.value.refindGaveUp, "a new instance on the saved port resumes")
        assertEquals(dialed + 1, dials().size)
        assertEquals("127.0.0.1:9", dials().last())
    }

    /** Validation P1b S1/S3 (kept until the owner's follow-up widens it): the
     *  re-find never dials another address, even an advertiser with the hub's
     *  own instance id and a name that sorts first. */
    @Test
    fun `the re-find dials only the saved host`() {
        remoteOnDeadHub(
            Peer("nsd-hub", "A Kitchen tablet", "primary", true, "192.168.1.66", 12),
            Peer("nsd-hub", "A Kitchen tablet", "primary", true, "localhost", 14),
            Peer("nsd-hub", "Kitchen tablet", "primary", true, "127.0.0.1", 13),
        )
        advance(REFIND_DELAY_MS + 200)
        assertEquals("127.0.0.1:13", dials().last())
        controller.refuseCurrentSessionForTest("HostMismatch", authenticated = false)
        advance(REFIND_BACKOFF_MS.sum() + REFIND_DELAY_MS * 6)
        assertTrue(dials().all { it.startsWith("127.0.0.1:") }, "${dials()}")
    }

    /** Validation P1b S6: the re-find never runs from a user's pick still
     *  waiting for its Welcome. */
    @Test
    fun `no re-find from an unwelcomed user pick`() {
        pinHub()
        controller.switchToSecondary("127.0.0.1", 9)
        advance(200)
        controller.overridePeers(MutableStateFlow(listOf(peer("nsd-restarted", 12))))
        advance(REFIND_DELAY_MS * 4)
        assertEquals(listOf("127.0.0.1:9"), dials(), "the user's pick, not welcomed")
    }

    /** Validation P1b S2 / M3, the deadline: an attempt that isn't welcomed
     *  within refindDelayMs ends, whatever its socket does. It counts as a
     *  refusal, the remote goes back to the saved target, and the attempt's
     *  port is never saved. */
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

    /**
     * AC-27 (controller half), spec §2.9: only an authenticated `Revoked` /
     * `NotPaired` makes a remote normal (and drops its pin). Advisory
     * refusals keep the role and pin, even on the user's own pick; a
     * non-terminal `HubStopped` keeps everything and counts nothing.
     */
    @Test
    fun `only an authenticated revoke makes a remote normal`() {
        remoteOnDeadHub()
        controller.refuseCurrentSessionForTest("HubUnverified", authenticated = false)
        advance(1_000)
        assertEquals("secondary", controller.state.value.role, "couldn't verify the hub: still a remote")
        advance(REFIND_BACKOFF_MS[0])
        assertEquals(listOf("127.0.0.1:9", "127.0.0.1:9"), dials(), "dialed again after the back-off")

        controller.refuseCurrentSessionForTest("PAIRING_CLOSED", authenticated = false)
        advance(1_000)
        assertEquals("secondary", controller.state.value.role, "an ABORT: still a remote")
        controller.refuseCurrentSessionForTest("HubStopped", authenticated = true, terminal = false)
        advance(1_000)
        assertEquals("secondary", controller.state.value.role, "Hub mode off on the hub: still a remote")
        assertTrue(notices.any { "turned off Hub mode" in it }, "$notices")

        controller.remirrorUnattended("127.0.0.1", 9)
        advance(200)
        controller.refuseCurrentSessionForTest("Revoked", authenticated = true)
        advance(1_000)
        assertEquals("normal", controller.state.value.role, "an authenticated revoke")
        assertNull(controller.relayStore.savedHub, "the hub's pin is dropped")
        assertTrue(controller.relayStore.hubs.isEmpty())

        // A user's pick refused by an advisory signal keeps the role too (§2.9).
        pinHub()
        controller.switchToSecondary("127.0.0.1", 9)
        advance(200)
        controller.refuseCurrentSessionForTest("HostMismatch", authenticated = false)
        advance(1_000)
        assertEquals("secondary", controller.state.value.role, "advisory: wording only")
    }

    @Test
    fun `an unauthenticated revoke changes nothing`() {
        remoteOnDeadHub()
        controller.refuseCurrentSessionForTest("Revoked", authenticated = false)
        advance(1_000)
        assertEquals("secondary", controller.state.value.role, "only a decrypted CLOSE may change trust")
        assertEquals("hub-1", controller.relayStore.savedHub)
    }

    @Test
    fun `the v3 migration runs before the role starts`() {
        // The first v4 start: no relay store yet (this class's own controller made one).
        java.io.File(app.filesDir, RelayKeyStore.FILE_NAME).delete()
        java.io.File(app.filesDir, "proxy-pairing.json").writeText("""{"hostId":"h","held":{"x":"sb1:AAAA"}}""")
        java.io.File(app.filesDir, "deviceId").writeText("old-id")
        java.io.File(app.filesDir, "prefs.json").writeText("""{"proxyRole":"secondary","proxyPrimaryHost":"127.0.0.1","proxyPrimaryPort":9}""")
        val upgraded = newController()
        try {
            assertFalse(java.io.File(app.filesDir, "proxy-pairing.json").exists())
            assertFalse(java.io.File(app.filesDir, "deviceId").exists())
            assertTrue(upgraded.state.value.needsRepair, "a v3 remote must pair again")
            upgraded.start()
            advance(1_000)
            assertEquals("secondary", upgraded.state.value.role, "the role is kept")
            assertTrue(log.any { "has to pair with the hub again" in it }, "and it doesn't dial: $log")
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun `a definitive key loss makes a remote normal at start`() {
        java.io.File(app.filesDir, "prefs.json").writeText("""{"proxyRole":"secondary","proxyPrimaryHost":"127.0.0.1","proxyPrimaryPort":9}""")
        keystore.keys.clear() // the relay key's alias is gone: a definitive loss
        val reset = newController()
        try {
            reset.start()
            advance(1_000)
            assertEquals("normal", reset.state.value.role)
            assertTrue(targets.contains("normal :0"), "persisted once the prefs load: $targets")
            assertTrue(reset.state.value.pairedRemotes.isEmpty(), "every pin cleared")
        } finally {
            reset.close()
        }
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
        val portFile = java.io.File(app.filesDir, "relayPort")
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

    /** P2a validation: a restarting hub doesn't serve before its prefs load,
     *  so a paired remote that dials at once meets a closed port and redials,
     *  then resumes once the relay serves: no refusal at any point. */
    @Test
    fun `a loading hub doesn't refuse a paired remote`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        java.io.File(app.filesDir, "relayPort").writeText("$port")
        val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()
        val hub = newController(prefsReady = { loaded.await() })
        val hubKeys = (relayStore().load() as RelayKeyLoad.Ready).keys
        val remoteScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val refusals = CopyOnWriteArrayList<String>()
        val welcomed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            hub.setHubMode(true)
            advance(1_000)
            // The remote dials the hub's saved port straight away.
            val link = ReconnectingClientLink("ws://127.0.0.1:$port${LanRelayServer.PATH}", remoteScope, pairedRemote, hubKeys.publicKey())
            ProxyTransport(
                link, remoteScope,
                onRefused = { reason, _, _ -> refusals += reason },
                onWelcome = { _, _ -> welcomed.set(true) },
            )
            val until = System.nanoTime() + 1_500_000_000L
            while (System.nanoTime() < until) {
                scheduler.runCurrent()
                Thread.sleep(10)
            }
            assertTrue(log.none { "LAN relay listening" in it }, "not serving while loading: $log")
            assertEquals(emptyList(), refusals.toList(), "no refusal while loading")
            assertFalse(welcomed.get())

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

    /** The pinned-hub flag follows the store's saved hub. */
    @Test
    fun `the pinned-hub flag follows the saved hub's pin`() {
        assertFalse(controller.state.value.pairedHubPinned)
        pinHub()
        val other = newController(appendLog = {})
        try {
            assertTrue(other.state.value.pairedHubPinned)
            other.forgetHub("hub-1")
            assertFalse(other.state.value.pairedHubPinned, "forgetting the hub clears it")
        } finally {
            other.close()
        }
    }
}
