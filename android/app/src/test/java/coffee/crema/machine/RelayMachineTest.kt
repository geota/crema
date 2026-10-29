package coffee.crema.machine

import coffee.crema.ble.proxy.ControlReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A mirror's relay policy: Control is forwarded with nothing applied here,
 *  and Config is shown, forwarded, and undone when it fails. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RelayMachineTest {

    private val scheduler = TestCoroutineScheduler()
    private val actions = RecordingMachineActions()
    private val sent = CopyOnWriteArrayList<MachineCommandCodec.Wire>()
    private val notices = CopyOnWriteArrayList<String>()
    private var reply: ControlReply = ControlReply.Ok

    private val relay = RelayMachine(
        send = { method, args -> sent += MachineCommandCodec.Wire(method, args); reply },
        showConfig = actions::showConfig,
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
    fun `a refused control is reported`() {
        reply = ControlReply.Err("A shot is already starting")
        assertEquals(CommandOutcome.Refused("A shot is already starting"), run(MachineCommand.StartShot))
        assertEquals(listOf("${RelayMachine.UNREACHABLE} — change not applied"), notices.toList())

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
        assertEquals(listOf("${RelayMachine.UNREACHABLE} — change reverted"), notices.toList())
    }

    @Test
    fun `a config edit with no answer is undone`() {
        reply = ControlReply.Failed("Timed out waiting for 5000 ms")
        val edit = MachineCommand.SetAutoTare(true)
        assertEquals(CommandOutcome.Refused(RelayMachine.UNREACHABLE), run(edit))
        assertEquals(listOf<MachineCommand.Config>(edit), actions.undone.toList())
        assertEquals(listOf("${RelayMachine.UNREACHABLE} — change reverted"), notices.toList())
    }
}
