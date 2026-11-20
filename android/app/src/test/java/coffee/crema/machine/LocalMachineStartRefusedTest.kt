package coffee.crema.machine

import kotlin.test.Test
import kotlin.test.assertEquals

/** This device's refused start goes to the Brew status card, not a message
 *  (brew messages PRD §1); other refusals stay messages. */
class LocalMachineStartRefusedTest {

    @Test
    fun `a refused start reaches the card and not the message queue`() {
        val actions = RecordingMachineActions(startShot = CommandOutcome.Refused("Can’t start shot — no profile selected"))
        val notified = mutableListOf<String>()
        val card = mutableListOf<String>()
        val machine = LocalMachine(actions, notify = { notified += it }, startRefused = { card += it })
        var outcome: CommandOutcome? = null
        machine.execute(MachineCommand.StartShot) { outcome = it }
        assertEquals(listOf("Can’t start shot — no profile selected"), card)
        assertEquals(emptyList(), notified)
        assertEquals(CommandOutcome.Refused("Can’t start shot — no profile selected"), outcome)
    }

    @Test
    fun `without a card a refused start is a message, as before`() {
        val actions = RecordingMachineActions(startShot = CommandOutcome.Refused("Shot not started"))
        val notified = mutableListOf<String>()
        LocalMachine(actions, notify = { notified += it }).execute(MachineCommand.StartShot)
        assertEquals(listOf("Shot not started"), notified)
    }

    @Test
    fun `a peer's refused start is not shown on this device`() {
        val actions = RecordingMachineActions(startShot = CommandOutcome.Refused("Shot not started"))
        val shown = mutableListOf<String>()
        val machine = LocalMachine(actions, notify = { shown += it }, startRefused = { shown += it })
        machine.run(MachineCommand.StartShot, CommandOrigin("peer", "Phone"))
        assertEquals(emptyList(), shown)
    }
}
