package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.ble.proxy.CodecFrameLink
import coffee.crema.ble.proxy.Frame
import coffee.crema.ble.proxy.FrameCodec
import coffee.crema.ble.proxy.Golden
import coffee.crema.ble.proxy.PROXY_PROTOCOL_VERSION
import coffee.crema.ble.proxy.RelayHub
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The primary's half of the relayed-verb wire: a real [MainViewModel] built in
 * the persisted primary role, its own [RelayHub] (the one ProxyController
 * built), its pairing prompt, the machine command codec and the VM's verb
 * bodies, fed each golden `control` text over an in-memory codec link. Every
 * fixture must be answered as expected (`ControlOk`, or a `ControlErr` with
 * the primary's reason), and every accepted config verb must land in the
 * primary's UI state. That pins the dispatcher side of the wire.
 *
 * Everything runs on the Robolectric main looper, so the test is
 * deterministic: no socket, no sleeps. (In production the hub runs on the
 * relay server's threads and the dispatcher moves each command onto Main.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RelayedVerbPrimaryDispatchTest {

    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var vm: MainViewModel
    private lateinit var client: CodecFrameLink
    private val replies = CopyOnWriteArrayList<Frame>()

    /** How a config verb shows on the primary: [prime] first moves the state
     *  away from the sample's value (so the check can't pass vacuously), then
     *  [applied] must hold once the relayed verb ran. */
    private class Effect(val prime: (MainViewModel) -> Unit = {}, val applied: (MainUiState) -> Boolean)

    private val effects: Map<String, Effect> = mapOf(
        "setActiveProfile" to Effect(prime = { vm -> vm.setActiveProfile(vm.ui.value.profiles.first { it.id != SAMPLE_PROFILE_ID }.id) }) {
            it.activeProfileId == SAMPLE_PROFILE_ID
        },
        "setActiveBean.none" to Effect(prime = { it.setActiveBean("bean-2") }) { it.activeBeanId == null },
        "setStopOnWeight" to Effect(prime = { it.setStopOnWeight(false) }) { it.stopOnWeight },
        "setAutoTare" to Effect(prime = { it.setAutoTare(true) }) { !it.autoTare },
        "setQcSteamTime" to Effect { it.qcSteamTimeS == 30f },
        "setQcSteamFlow" to Effect(prime = { it.setQcSteamFlow(2f) }) { it.qcSteamFlowMlS == 1.2f },
        "setQcSteamTemp" to Effect(prime = { it.setQcSteamTemp(140f) }) { it.qcSteamTempC == 148f },
        "setQcSteamTemp.off" to Effect { it.qcSteamTempC == 0f },
        "setQcHotWaterTemp" to Effect { it.qcHotWaterTempC == 85f },
        "setQcHotWaterVolume" to Effect { it.qcHotWaterVolumeMl == 250f },
        "setQcFlushTime" to Effect { it.qcFlushTimeS == 5f },
        "setQcFlushTemp" to Effect { it.qcFlushTempC == 92f },
        "setQcGrind" to Effect { it.qcGrind == 14.5f },
        "quickAdjustBrew" to Effect { it.brewParams == BrewParams(18.0, 36.0, 93.0, 8.0) },
        "quickAdjustBrew.noPreinf" to Effect { it.brewParams == BrewParams(18.0, 36.0, 93.0, null) },
        "resetBrew" to Effect(prime = { it.quickAdjustBrew(18.0, 36.0, 93.0) }) { it.brewParams == null },
    )

    /** Fixtures the primary refuses in this setup, with the reason's text.
     *  `handoff` passes the allowlist but is granted only from a positively
     *  idle machine, and no DE1 is connected here. `bean-1` is a bean from the
     *  secondary's own library, which this primary doesn't have. */
    private val refusals = mapOf(
        "handoff" to "handoff is idle-only",
        "setActiveBean" to "That bean isn\u2019t on",
    )

    /** What a refused fixture must leave as it was. */
    private val unchanged: Map<String, (MainUiState) -> Any?> = mapOf("setActiveBean" to { it.activeBeanId })

    @Before
    fun pairWithThePrimary() {
        VmHarness.persistRole("primary")
        vm = VmHarness.build(store)
        VmHarness.idle()
        val hub: RelayHub = assertNotNull(VmHarness.proxyController(vm).relayHub, "the primary built its relay")
        val (clientEnd, serverEnd) = CodecFrameLink.pair(scope)
        client = clientEnd
        scope.launch { hub.serve(serverEnd) }
        scope.launch { client.incoming().collect { replies += it } }

        // Pair the way a new secondary does: the host opens its pairing
        // window, the secondary asks to pair, the host allows control.
        vm.openPairingWindow()
        scope.launch { client.send(Frame.Hello(PROXY_PROTOCOL_VERSION, "secondary", "phone-1", "Phone", nonce = "bm9uY2U")) }
        VmHarness.idle()
        assertIs<Frame.Challenge>(replies.single())
        scope.launch { client.send(Frame.Auth(proof = null)) }
        VmHarness.idle()
        assertEquals(PairingPrompt("phone-1", "Phone"), vm.ui.value.pendingPairing)
        vm.resolvePairing(PairingChoice.ALLOW_CONTROL)
        VmHarness.idle()
        val welcome = assertIs<Frame.Welcome>(replies.last())
        assertEquals("control", welcome.scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        store.clear()
        VmHarness.idle()
    }

    /** Send [text] as the secondary and return the primary's one reply. */
    private fun relay(text: String): Frame {
        val before = replies.size
        scope.launch { client.sendRaw(text) }
        VmHarness.idle()
        val reply = replies.drop(before).filter { it is Frame.ControlOk || it is Frame.ControlErr }
        assertEquals(1, reply.size, "one reply to $text, got ${replies.drop(before)}")
        return reply.single()
    }

    @Test
    fun `the primary runs every golden control`() {
        assertTrue(vm.ui.value.profiles.any { it.id == SAMPLE_PROFILE_ID }, "the sample profile is a built-in")
        for (sample in RELAYED_VERB_SAMPLES) {
            val effect = effects[sample.fixture]
            effect?.let {
                it.prime(vm)
                VmHarness.idle()
                assertFalse(it.applied(vm.ui.value), "${sample.fixture}: prime the state away from the sample first")
            }
            val kept = unchanged[sample.fixture]?.invoke(vm.ui.value)
            val reply = relay(Golden.require("control/${sample.fixture}.json"))
            val refusal = refusals[sample.fixture]
            if (refusal == null) {
                assertEquals(Frame.ControlOk(1), reply, "${sample.fixture} was not accepted")
            } else {
                val err = assertIs<Frame.ControlErr>(reply, "${sample.fixture} should be refused here")
                assertTrue(refusal in err.reason, "${sample.fixture}: ${err.reason}")
                unchanged[sample.fixture]?.let { assertEquals(kept, it(vm.ui.value), "${sample.fixture} changed the primary") }
            }
            if (effect != null) assertTrue(effect.applied(vm.ui.value), "${sample.fixture} did not reach the primary's state")
        }
    }

    /** 05 F1: a profile from the secondary's own library is refused with a
     *  reason, and the primary keeps its selection. */
    @Test
    fun `a profile this primary lacks is refused and its selection kept`() {
        val before = vm.ui.value.activeProfileId
        val err = assertIs<Frame.ControlErr>(relay(FrameCodec.encode(Frame.Control(1, "setActiveProfile", "custom-on-the-phone"))))
        assertTrue("That profile isn\u2019t on" in err.reason, err.reason)
        assertEquals(before, vm.ui.value.activeProfileId)

        val accepted = relay(FrameCodec.encode(Frame.Control(2, "setActiveProfile", SAMPLE_PROFILE_ID)))
        assertEquals(Frame.ControlOk(2), accepted)
    }

    /** An accepted start is announced to this primary's user. */
    @Test
    fun `an accepted start is announced`() {
        vm.setRequireScale(false)
        VmHarness.idle()
        assertEquals(Frame.ControlOk(1), relay(Golden.require("control/startShot.json")))
        assertTrue(vm.ui.value.userMessages.any { it == "Phone started a shot" }, "${vm.ui.value.userMessages}")
    }
}
