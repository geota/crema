package coffee.crema.ui

import coffee.crema.ble.proxy.Frame
import coffee.crema.ble.proxy.FrameCodec
import coffee.crema.ble.proxy.Golden
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Golden `control` frames for every relayed verb (`proxy-golden/v3/control/`):
 * the method name and argument encoding a secondary sends and a primary
 * accepts today. A typed-command seam must keep these bytes, or secondaries
 * already in the field stop working against an updated primary.
 *
 * The verb list comes from [RelayedControlPolicy.VERBS], the primary's
 * allowlist: a verb added there without a sample here fails
 * [every relayed verb has a golden sample]. The sender side (that the VM
 * produces these bytes) is [RelayedVerbSecondaryWireTest]; the dispatcher
 * side (that the primary's VM runs them) is [RelayedVerbPrimaryDispatchTest].
 */
class ControlVerbGoldenTest {

    @Test
    fun `every relayed verb has a golden sample`() {
        assertEquals(
            RelayedControlPolicy.VERBS,
            RELAYED_VERB_SAMPLES.map { it.method }.toSet(),
            "verbs in RelayedControlPolicy.VERBS without a sample in RELAYED_VERB_SAMPLES (or samples for no verb)",
        )
    }

    /**
     * The senders, read from the source: every verb passed to a relay guard
     * (`relayIfSecondary` / `relayConfigOptimistic`) or straight to
     * `ProxyTransport.control` anywhere under `src/main/java`, so a file move
     * in the MainViewModel breakup doesn't matter. A verb a sender relays but
     * the primary's allowlist doesn't know would be refused at runtime. The
     * typed-command seam replaces these call shapes: replace this scan with
     * its exhaustive command list then.
     */
    @Test
    fun `every verb a sender relays is in the allowlist`() {
        val call = Regex("""(?:relayIfSecondary|relayConfigOptimistic|\.control)\("([A-Za-z]+)"""")
        val sent = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> call.findAll(f.readText()).map { it.groupValues[1] } }
            .toSet()
        assertEquals(RelayedControlPolicy.VERBS, sent, "verbs the senders relay vs the primary's allowlist")
    }

    @Test
    fun `every machine state the UI relays has a sample`() {
        val sampled = RELAYED_VERB_SAMPLES.filter { it.method == "machineState" }.map { it.args }.toSet()
        assertEquals(RelayedControlPolicy.RELAYABLE_MACHINE_STATES, sampled)
    }

    @Test
    fun `every sample encodes to its fixture`() {
        for (s in RELAYED_VERB_SAMPLES) {
            Golden.check("control/${s.fixture}.json", FrameCodec.encode(Frame.Control(1, s.method, s.args)))
        }
    }

    @Test
    fun `every fixture decodes to its sample`() {
        for (s in RELAYED_VERB_SAMPLES) {
            val text = Golden.require("control/${s.fixture}.json")
            assertEquals(Frame.Control(1, s.method, s.args), FrameCodec.decode(text), "control/${s.fixture}.json")
        }
    }

    @Test
    fun `the primary's allowlist accepts every sample`() {
        for (s in RELAYED_VERB_SAMPLES) {
            assertNull(RelayedControlPolicy.refusal(s.method, s.args), "${s.fixture}: ${s.method}(${s.args}) is refused")
        }
    }

    @Test
    fun `no fixture is left without a sample`() {
        assertEquals(emptySet(), Golden.list("control") - RELAYED_VERB_SAMPLES.map { it.fixture }.toSet())
    }
}
