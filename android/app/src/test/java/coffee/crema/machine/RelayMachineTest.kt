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

/** A remote's relay policy: Control is forwarded with nothing applied here,
 *  Config is shown, forwarded, and its echo settled with the outcome,
 *  whatever it is; LocalOnly is refused here, and nothing is ever resent. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RelayMachineTest {

    private val scheduler = TestCoroutineScheduler()
    private val actions = RecordingMachineActions()
    private val sent = CopyOnWriteArrayList<MachineCommandCodec.Wire>()
    private val notices = CopyOnWriteArrayList<String>()
    private var reply: ControlReply = ControlReply.Ok
    private var machineState: String? = "Idle"
    private var viewOnly = false

    /** Config edits shown, and how each one's echo settled. */
    private val shown = CopyOnWriteArrayList<MachineCommand.Config>()
    private val settled = CopyOnWriteArrayList<Pair<MachineCommand.Config, CommandOutcome>>()

    private val relay = RelayMachine(
        send = { method, args -> sent += MachineCommandCodec.Wire(method, args); reply },
        showConfig = { cmd -> shown += cmd; ConfigEcho { outcome -> settled += cmd to outcome } },
        machineStateName = { machineState },
        scope = CoroutineScope(StandardTestDispatcher(scheduler)),
        notify = { notices += it },
        log = {},
        viewOnly = { viewOnly },
        hubName = { "Kitchen tablet" },
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
        assertTrue(actions.applied.isEmpty() && shown.isEmpty(), "applied ${actions.applied}, shown $shown")
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
    fun `a config edit is shown at once and settles when accepted`() {
        val edit = MachineCommand.SetQc(QcField.GRIND, 14.5f)
        relay.execute(edit)
        assertEquals(listOf<MachineCommand.Config>(edit), shown.toList(), "shown before the round trip")
        assertTrue(settled.isEmpty(), "not settled before the answer")
        scheduler.advanceUntilIdle()
        assertEquals(listOf(MachineCommandCodec.Wire("setQcGrind", "14.5")), sent.toList())
        assertEquals(listOf<Pair<MachineCommand.Config, CommandOutcome>>(edit to CommandOutcome.Accepted), settled.toList())
        assertTrue(actions.applied.isEmpty() && actions.shown.isEmpty(), "a remote never shows or commits own values: ${actions.applied}")
    }

    @Test
    fun `a refused config edit settles as refused`() {
        reply = ControlReply.Err("That profile isn’t on Tablet")
        val edit = MachineCommand.SetActiveProfile("p-2")
        assertEquals(CommandOutcome.Refused("That profile isn’t on Tablet"), run(edit))
        assertEquals(listOf<Pair<MachineCommand.Config, CommandOutcome>>(edit to CommandOutcome.Refused("That profile isn’t on Tablet")), settled.toList())
        assertEquals(listOf("That profile isn’t on Tablet — change reverted"), notices.toList())
    }

    @Test
    fun `a config edit that never left settles as unreachable`() {
        reply = ControlReply.NotSent("Timed out waiting for 5000 ms")
        val edit = MachineCommand.SetAutoTare(true)
        assertEquals(CommandOutcome.Refused(RelayMachine.UNREACHABLE), run(edit))
        assertEquals(listOf<Pair<MachineCommand.Config, CommandOutcome>>(edit to CommandOutcome.Refused(RelayMachine.UNREACHABLE)), settled.toList())
        assertEquals(listOf("${RelayMachine.UNREACHABLE} — change reverted"), notices.toList())
    }

    @Test
    fun `an unanswered config edit settles as unknown, is said to be uncertain, and not resent`() {
        reply = ControlReply.NoReply("Proxy session ended before a reply")
        val edit = MachineCommand.SetStopOnWeight(false)
        assertIs<CommandOutcome.Unknown>(run(edit))
        assertIs<CommandOutcome.Unknown>(settled.single().second, "the echo keeps it, as unconfirmed")
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

    /** AC-23: a view-only remote sends nothing at all, Stop included. */
    @Test
    fun `a view-only remote sends nothing`() {
        viewOnly = true
        for (cmd in listOf(MachineCommand.Stop, MachineCommand.StartShot, MachineCommand.SetQc(QcField.GRIND, 14f), MachineCommand.SetStopOnWeight(true))) {
            assertEquals(CommandOutcome.Refused(RelayMachine.VIEW_ONLY), run(cmd))
        }
        assertTrue(sent.isEmpty() && shown.isEmpty(), "sent $sent, shown $shown")
        assertTrue(notices.all { it == RelayMachine.VIEW_ONLY })
    }

    /** AC-15 / AC-35: a machine setting is read-only on a remote. */
    @Test
    fun `a quick controls machine setting is refused here and nothing is sent`() {
        for (field in QcField.entries.filter { it != QcField.GRIND }) {
            assertEquals(CommandOutcome.Refused("Change on Kitchen tablet"), run(MachineCommand.SetQc(field, 1f)), "$field")
        }
        assertTrue(sent.isEmpty() && shown.isEmpty(), "sent $sent, shown $shown")
        assertEquals(CommandOutcome.Accepted, run(MachineCommand.SetQc(QcField.GRIND, 14f)), "grind is a shot dial")
        assertEquals(listOf(MachineCommandCodec.Wire("setQcGrind", "14.0")), sent.toList())
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
