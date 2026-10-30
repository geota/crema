package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.ble.proxy.DeviceInfo
import coffee.crema.ble.proxy.Golden
import coffee.crema.ble.proxy.TestHubServer
import coffee.crema.ble.proxy.RelayHub
import coffee.crema.machine.sentByRemotes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The secondary's half of the relayed-verb wire: a real [MainViewModel]
 * mirroring over a real socket (OkHttp, `ReconnectingClientLink`,
 * `ProxyTransport`) to a test primary that runs a real [RelayHub] behind a
 * MockWebServer WebSocket. Each [RELAYED_VERB_SAMPLES] call on the VM must put
 * exactly its golden `control` text on the wire (the correlation id aside).
 * This is what pins the senders' method names and argument encodings
 * (`Float.toString`, `Boolean.toString`, the brew CSV, an empty bean id
 * left off the wire) across the typed-command seam and the VM breakup.
 *
 * The VM starts in the normal role and the test picks the primary the way
 * the picker does ([MainViewModel.switchToSecondary]), so the session may
 * pair; the test hub admits it with control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RelayedVerbSecondaryWireTest {

    /** One thread: the hub handles controls and queues replies in arrival order. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private lateinit var endpoint: TestHubServer
    private val store = ViewModelStore()

    /** What the primary's control handler was asked to run, in order. */
    private val handled = CopyOnWriteArrayList<Pair<String, String>>()

    /** Verbs the test primary refuses (a `ControlErr` back to the secondary). */
    private val refused = mutableSetOf<String>()

    /** The test primary's config snapshot: an accepted `setQcGrind`
     *  updates it and pushes it before the reply, as a real hub does. */
    @Volatile private var hubConfig = ""

    private val hub: RelayHub = RelayHub(
        primaryName = "Test primary",
        roster = { emptyList<DeviceInfo>() },
        readSource = { _, _, _ -> error("no reads in this test") },
        isSnapshotChar = { _, _ -> false },
        controlHandler = { method, args, _, _ ->
            handled += method to args
            if (method in refused) {
                Result.failure(IllegalStateException("refused by the test primary"))
            } else {
                if (method == "setQcGrind") {
                    hubConfig = """{"qcGrind":$args}"""
                    pushHubConfig()
                }
                Result.success(Unit)
            }
        },
        configSource = { hubConfig },
    )

    private fun pushHubConfig() = hub.pushConfig(hubConfig)

    private lateinit var vm: MainViewModel

    @Before
    fun mirrorTheTestPrimary() {
        // Paired before: each side pinned the other's relay key.
        val (relayStore, remote) = VmHarness.relayStore()
        endpoint = TestHubServer.serving(hub, remote, name = "Test primary")
        VmHarness.pinHub(relayStore, endpoint.hubKeys, endpoint.port, name = "Test primary")
        vm = VmHarness.build(store)
        VmHarness.idle()
        vm.switchToSecondary("127.0.0.1", endpoint.port)
        VmHarness.advance(MODE_SWITCH_SETTLE_MS)
        VmHarness.idleUntil("the secondary to be welcomed") { hub.remotes().isNotEmpty() }
    }

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        hubScope.cancel()
        endpoint.kill()
    }

    /** The control texts the secondary has sent so far. */
    private fun controlTexts(): List<String> = endpoint.received.filter { "\"type\":\"control\"" in it }

    @Test
    fun `every relayed verb leaves the secondary in its golden wire form`() {
        // What a remote sends (spec §2): the machine settings it doesn't are
        // the next test's.
        val senders = RELAYED_VERB_SAMPLES.filter { it.send != null && sentByRemotes(it) }
        for (sample in senders) {
            val before = controlTexts().size
            sample.send!!(vm)
            VmHarness.idleUntil("${sample.fixture} on the wire") { controlTexts().size > before }
            val sent = controlTexts()
            assertEquals(before + 1, sent.size, "${sample.fixture} sent more than one control")
            assertEquals(
                Golden.require("control/${sample.fixture}.json"),
                sent.last().replaceFirst(CORRELATION_ID, "\"id\":1"),
                "${sample.fixture}: the secondary's wire form differs from the golden fixture",
            )
        }
        // And the primary's hub decoded every one of them back to the same verb.
        VmHarness.idleUntil("the primary to handle every control") { handled.size == senders.size }
        assertEquals(senders.map { it.method to it.args }, handled.toList())
    }

    /** AC-15: the Quick Controls machine settings (steam, hot water, flush)
     *  are read-only on a remote: their setters put nothing on the wire. */
    @Test
    fun `machine settings a remote doesn't send put nothing on the wire`() {
        // Relay v4 R4: these have no wire form (and no relayed sample) at all.
        assertTrue(RELAYED_VERB_SAMPLES.all { sentByRemotes(it) }, "every relayed sample is a verb a remote sends")
        vm.setQcSteamTime(30f)
        vm.setQcSteamFlow(1.2f)
        vm.setQcSteamTemp(148f)
        vm.setQcHotWaterTemp(85f)
        vm.setQcHotWaterVolume(250f)
        vm.setQcFlushTime(5f)
        vm.setQcFlushTemp(92f)
        vm.tareScale() // a control that is sent, so the wire has caught up
        VmHarness.idleUntil("the tare on the wire") { controlTexts().isNotEmpty() }
        VmHarness.idleUntil("the primary to handle it") { handled.isNotEmpty() }
        assertEquals(listOf("tareScale" to ""), handled.toList())
        assertEquals(1, controlTexts().size)
    }

    private fun sentByRemotes(sample: RelayedVerbSample): Boolean =
        (coffee.crema.machine.MachineCommandCodec.decode(sample.method, sample.args) as coffee.crema.machine.MachineCommandCodec.Decoded.Valid)
            .command.sentByRemotes()

    /** What a secondary keeps to itself: the debug panel's raw Espresso (a
     *  shot is relayed only as startShot), and a Wake while its view of the
     *  machine isn't asleep (that IDLE would stop whatever is running). */
    @Test
    fun `raw espresso and a wake of an awake machine stay on the secondary`() {
        vm.startEspresso()
        vm.wake()
        vm.tareScale() // a control that is sent, so the wire has caught up
        VmHarness.idleUntil("the tare on the wire") { controlTexts().isNotEmpty() }
        VmHarness.idleUntil("the primary to handle it") { handled.isNotEmpty() }
        assertEquals(listOf("tareScale" to ""), handled.toList())
        val notices = vm.ui.value.userMessages
        assertTrue(notices.any { "Not available on a remote" in it }, "$notices")
    }

    /** A config verb is applied on the secondary at once, then reverted when
     *  the primary refuses it (RelayMachine's config policy). */
    @Test
    fun `a refused config verb is reverted on the secondary`() {
        refused += "setQcGrind"
        val before = vm.ui.value.qcGrind
        vm.setQcGrind(14.5f)
        assertEquals(14.5f, vm.ui.value.qcGrind, "applied optimistically, before the round trip")
        VmHarness.idleUntil("the refusal to revert the edit") { vm.ui.value.qcGrind == before }
        assertEquals(listOf("setQcGrind" to "14.5"), handled.toList())
    }

    /** An accepted config verb shows the hub's value (pushed before its
     *  reply); an authority-first verb changes nothing locally and reaches the
     *  primary as is. */
    @Test
    fun `an accepted config verb stays applied and a machine verb reaches the primary`() {
        // The hub answers in order (one thread), so once the refused drain
        // verb's failure is logged, the replies before it have landed too.
        refused += "tareScale"
        vm.setQcGrind(14.5f)
        vm.stopShot()
        vm.tareScale()
        VmHarness.idleUntil("the drain verb's refusal") { VmHarness.log(vm).any { "Relay tareScale failed" in it } }
        assertEquals(listOf("setQcGrind" to "14.5", "machineState" to "IDLE", "tareScale" to ""), handled.toList())
        assertEquals(14.5f, vm.ui.value.qcGrind, "the hub's value, pushed before its reply")
        assertEquals(null, VmHarness.ownState(vm).qcGrind, "never written to this remote's own settings")
    }

    private companion object {
        /** ProxyController.applyModeLocked's settle delay (150 ms), with margin. */
        const val MODE_SWITCH_SETTLE_MS = 200L
        val CORRELATION_ID = Regex("\"id\":\\d+")
    }
}
