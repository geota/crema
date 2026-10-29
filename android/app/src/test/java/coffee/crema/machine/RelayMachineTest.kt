package coffee.crema.machine

import coffee.crema.ble.proxy.ControlReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A mirror's relay policy: Control is forwarded with nothing applied here,
 *  Config is shown, forwarded, and undone only when it surely didn't apply,
 *  LocalOnly is refused here, and nothing is ever resent. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RelayMachineTest {

    private val scheduler = TestCoroutineScheduler()
    private val actions = RecordingMachineActions()
    private val sent = CopyOnWriteArrayList<MachineCommandCodec.Wire>()
    private val notices = CopyOnWriteArrayList<String>()
    private var reply: ControlReply = ControlReply.Ok
    private var machineState: String? = "Idle"

    private val relay = RelayMachine(
        send = { method, args -> sent += MachineCommandCodec.Wire(method, args); reply },
        showConfig = actions::showConfig,
        machineStateName = { machineState },
        scope = CoroutineScope(StandardTestDispatcher(scheduler)),
        notify = { notices += it },
        log = {},
    )

    /** Run [cmd] through the relay to completion and return its outcome. */
    private fun run(cmd: MachineCommand): CommandOutcome {
        var outcome: CommandOutcome? = null
        relay.execute(cmd) { outcome = it }
        scheduler.advanceUntilIdle()
        return checkNotNull(outcome) { "no outcome for $cmd" }
    }

    @Test
    fun `a control is forwarded and nothing is applied here`() {
        assertEquals(CommandOutcome.Accepted, run(MachineCommand.StartShot))
        assertEquals(listOf(MachineCommandCodec.Wire("startShot", "")), sent.toList())
        assertTrue(actions.applied.isEmpty() && actions.shown.isEmpty(), "applied ${actions.applied}, shown ${actions.shown}")
        assertTrue(notices.isEmpty(), "$notices")
    }

    @Test
    fun `a refused control shows the primary's reason`() {
        reply = ControlReply.Err("A shot is already starting")
        assertEquals(CommandOutcome.Refused("A shot is already starting"), run(MachineCommand.StartShot))
        assertEquals(listOf("A shot is already starting"), notices.toList())

        reply = ControlReply.Err("not authorized")
        run(MachineCommand.TareScale)
        assertTrue("View-only" in notices.last(), notices.last())
    }

    @Test
    fun `a config edit is shown at once and stays when accepted`() {
        val edit = MachineCommand.SetQc(QcField.GRIND, 14.5f)
        relay.execute(edit)
        assertEquals(listOf<MachineCommand.Config>(edit), actions.shown.toList(), "shown before the round trip")
        scheduler.advanceUntilIdle()
        assertEquals(listOf(MachineCommandCodec.Wire("setQcGrind", "14.5")), sent.toList())
        assertTrue(actions.undone.isEmpty())
        assertTrue(actions.applied.isEmpty(), "a mirror never commits: ${actions.applied}")
    }

    @Test
    fun `a refused config edit is undone`() {
        reply = ControlReply.Err("That profile isn’t on Tablet")
        val edit = MachineCommand.SetActiveProfile("p-2")
        assertEquals(CommandOutcome.Refused("That profile isn’t on Tablet"), run(edit))
        assertEquals(listOf<MachineCommand.Config>(edit), actions.undone.toList())
        assertEquals(listOf("That profile isn’t on Tablet — change reverted"), notices.toList())
    }

    @Test
    fun `a config edit that never left is undone`() {
        reply = ControlReply.NotSent("Timed out waiting for 5000 ms")
        val edit = MachineCommand.SetAutoTare(true)
        assertEquals(CommandOutcome.Refused(RelayMachine.UNREACHABLE), run(edit))
        assertEquals(listOf<MachineCommand.Config>(edit), actions.undone.toList())
        assertEquals(listOf("${RelayMachine.UNREACHABLE} — change reverted"), notices.toList())
    }

    @Test
    fun `an unanswered config edit is kept, said to be uncertain, and not resent`() {
        reply = ControlReply.NoReply("Proxy session ended before a reply")
        val edit = MachineCommand.SetStopOnWeight(false)
        assertIs<CommandOutcome.Unknown>(run(edit))
        assertTrue(actions.undone.isEmpty(), "no blind revert: the primary may have applied it")
        assertEquals(listOf(RelayMachine.UNKNOWN_CONFIG), notices.toList())
        assertEquals(1, sent.size, "never resent")
    }

    @Test
    fun `an unanswered control may have run and is not resent`() {
        reply = ControlReply.NoReply("Timed out waiting for 5000 ms")
        assertIs<CommandOutcome.Unknown>(run(MachineCommand.StartShot))
        assertEquals(listOf(RelayMachine.UNKNOWN_CONTROL), notices.toList())
        assertTrue("may not have been applied" in notices.single())
        assertEquals(1, sent.size, "never resent")
    }

    @Test
    fun `a local-only command is refused on a mirror`() {
        assertEquals(CommandOutcome.Refused(RelayMachine.NOT_ON_MIRROR), run(MachineCommand.RawEspresso))
        assertTrue(sent.isEmpty() && actions.applied.isEmpty())
        assertEquals(listOf(RelayMachine.NOT_ON_MIRROR), notices.toList())
    }

    @Test
    fun `wake is sent only while this mirror shows the machine asleep`() {
        machineState = "Idle"
        assertIs<CommandOutcome.Refused>(run(MachineCommand.Wake))
        assertTrue(sent.isEmpty(), "an awake machine's IDLE would stop whatever runs")

        machineState = "Sleep"
        assertEquals(CommandOutcome.Accepted, run(MachineCommand.Wake))
        assertEquals(listOf(MachineCommandCodec.Wire("machineState", "IDLE")), sent.toList())
    }

    @Test
    fun `stop is always sent`() {
        for (state in listOf("Sleep", "Idle", "Espresso", null)) {
            machineState = state
            assertEquals(CommandOutcome.Accepted, run(MachineCommand.Stop))
        }
        assertEquals(4, sent.size)
    }
}
