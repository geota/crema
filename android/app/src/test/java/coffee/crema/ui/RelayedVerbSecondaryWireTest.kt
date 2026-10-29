package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.ble.proxy.DeviceInfo
import coffee.crema.ble.proxy.Golden
import coffee.crema.ble.proxy.MockWsServerLink
import coffee.crema.ble.proxy.RelayHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
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
    private val server = MockWebServer()
    private val primary = MockWsServerLink()
    private val store = ViewModelStore()

    /** What the primary's control handler was asked to run, in order. */
    private val handled = CopyOnWriteArrayList<Pair<String, String>>()

    /** Verbs the test primary refuses (a `ControlErr` back to the secondary). */
    private val refused = mutableSetOf<String>()

    private val hub = RelayHub(
        primaryId = "host-1",
        primaryName = "Test primary",
        roster = { emptyList<DeviceInfo>() },
        readSource = { _, _, _ -> error("no reads in this test") },
        isSnapshotChar = { _, _ -> false },
        controlHandler = { method, args, _, _ ->
            handled += method to args
            if (method in refused) Result.failure(IllegalStateException("refused by the test primary")) else Result.success(Unit)
        },
    )

    private lateinit var vm: MainViewModel

    @Before
    fun mirrorTheTestPrimary() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(primary).build())
        server.start(InetAddress.getLoopbackAddress(), 0)
        hubScope.launch { hub.serve(primary) }
        vm = VmHarness.build(store)
        VmHarness.idle()
        vm.switchToSecondary("127.0.0.1", server.port)
        VmHarness.advance(MODE_SWITCH_SETTLE_MS)
        VmHarness.idleUntil("the secondary to be welcomed") { hub.remotes().isNotEmpty() }
    }

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        hubScope.cancel()
        server.close()
    }

    /** The control texts the secondary has sent so far. */
    private fun controlTexts(): List<String> = primary.receivedText.filter { "\"type\":\"control\"" in it }

    @Test
    fun `every relayed verb leaves the secondary in its golden wire form`() {
        val senders = RELAYED_VERB_SAMPLES.filter { it.send != null }
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
        assertTrue(notices.any { "Not available while mirroring" in it }, "$notices")
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

    /** An accepted config verb stays applied; an authority-first verb changes
     *  nothing locally and reaches the primary as is. */
    @Test
    fun `an accepted config verb stays applied and a machine verb reaches the primary`() {
        // The hub answers in order (one thread), so once the refused drain
        // verb's failure is logged, the replies before it have landed too.
        refused += "tareScale"
        vm.setQcSteamTime(30f)
        vm.stopShot()
        vm.tareScale()
        VmHarness.idleUntil("the drain verb's refusal") { VmHarness.log(vm).any { "Relay tareScale failed" in it } }
        assertEquals(listOf("setQcSteamTime" to "30.0", "machineState" to "IDLE", "tareScale" to ""), handled.toList())
        assertEquals(30f, vm.ui.value.qcSteamTimeS, "an accepted edit is not reverted")
    }

    private companion object {
        /** ProxyController.applyModeLocked's settle delay (150 ms), with margin. */
        const val MODE_SWITCH_SETTLE_MS = 200L
        val CORRELATION_ID = Regex("\"id\":\\d+")
    }
}
