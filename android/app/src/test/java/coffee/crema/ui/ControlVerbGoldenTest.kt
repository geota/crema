package coffee.crema.ui

import coffee.crema.ble.proxy.Frame
import coffee.crema.ble.proxy.FrameCodec
import coffee.crema.ble.proxy.Golden
import coffee.crema.machine.MachineCommand
import coffee.crema.machine.MachineCommandCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Golden `control` frames for every relayed verb (`proxy-golden/v3/control/`):
 * the method name and argument encoding a secondary sends and a primary
 * accepts today. A typed-command seam must keep these bytes, or secondaries
 * already in the field stop working against an updated primary.
 *
 * The verb list comes from [MachineCommandCodec.METHODS], what a primary
 * accepts: a method added there without a sample here fails
 * [every relayed verb has a golden sample]. The sender side (that the VM
 * produces these bytes) is [RelayedVerbSecondaryWireTest]; the dispatcher
 * side (that the primary's VM runs them) is [RelayedVerbPrimaryDispatchTest].
 */
class ControlVerbGoldenTest {

    @Test
    fun `every relayed verb has a golden sample`() {
        assertEquals(
            MachineCommandCodec.METHODS,
            RELAYED_VERB_SAMPLES.map { it.method }.toSet(),
            "methods in MachineCommandCodec.METHODS without a sample in RELAYED_VERB_SAMPLES (or samples for no method)",
        )
    }

    /**
     * The senders: every sample a secondary sends decodes to a command that
     * encodes back to exactly its wire form, and every relayable command has
     * such a sample. Wake has none of its own: it shares Stop's IDLE.
     */
    @Test
    fun `every relayable command has a sample that round-trips`() {
        val decoded = RELAYED_VERB_SAMPLES.filter { it.send != null }.map { s ->
            val cmd = assertIs<MachineCommandCodec.Decoded.Valid>(MachineCommandCodec.decode(s.method, s.args), s.fixture).command
            assertEquals(MachineCommandCodec.Wire(s.method, s.args), MachineCommandCodec.encode(cmd), "${s.fixture} re-encodes")
            cmd::class
        }.toSet()
        val relayable = MachineCommand.Relayable::class.leaves()
        assertEquals(relayable - MachineCommand.Wake::class, decoded, "relayable commands vs the samples a secondary sends")
    }

    @Test
    fun `every machine state the UI relays has a sample`() {
        val sampled = RELAYED_VERB_SAMPLES.filter { it.method == "machineState" }.map { it.args }.toSet()
        assertEquals(MachineCommandCodec.RELAYABLE_STATES, sampled)
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
            assertIs<MachineCommandCodec.Decoded.Valid>(MachineCommandCodec.decode(s.method, s.args), "${s.fixture}: ${s.method}(${s.args}) is refused")
        }
    }

    @Test
    fun `no fixture is left without a sample`() {
        assertEquals(emptySet(), Golden.list("control") - RELAYED_VERB_SAMPLES.map { it.fixture }.toSet())
    }
}

/** The concrete commands under a sealed command type. */
internal fun kotlin.reflect.KClass<*>.leaves(): Set<kotlin.reflect.KClass<*>> =
    if (isSealed) sealedSubclasses.flatMap { it.leaves() }.toSet() else setOf(this)
