package coffee.crema.machine

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A primary's relayed-control entry: decode and check, run the command, and
 *  announce only accepted commands. */
class RelayedCommandDispatcherTest {

    private val actions = RecordingMachineActions()
    private val announced = CopyOnWriteArrayList<String>()
    private var machineIdle = false
    private val phone = CommandOrigin("phone-1", "Phone")

    private val dispatcher = RelayedCommandDispatcher(
        machine = LocalMachine(actions, grantTakeOver = { CommandOutcome.Accepted }, notify = {}),
        machineIdle = { machineIdle },
        announce = { text, _ -> announced += text },
        log = {},
    )

    private fun dispatch(method: String, args: String = "", origin: CommandOrigin = phone) =
        runBlocking { dispatcher.dispatch(method, args, origin) }

    @Test
    fun `a malformed control is refused before anything runs`() {
        val outcome = assertIs<CommandOutcome.Refused>(dispatch("machineState", "AIR_PURGE"))
        assertTrue("not relayable" in outcome.reason, outcome.reason)
        assertIs<CommandOutcome.Refused>(dispatch("setQcSteamTemp", "NaN"))
        assertIs<CommandOutcome.Refused>(dispatch("writeMmr", "1"))
        assertTrue(actions.applied.isEmpty() && actions.peerActivity == 0)
    }

    @Test
    fun `a start is announced`() {
        assertEquals(CommandOutcome.Accepted, dispatch("startShot"))
        assertEquals(listOf("Phone started a shot"), announced.toList())
    }

    @Test
    fun `a relayed idle reads as a wake on an idle machine and a stop otherwise`() {
        machineIdle = false
        dispatch("machineState", "IDLE")
        machineIdle = true
        dispatch("machineState", "IDLE")
        assertEquals(listOf("Phone stopped the machine", "Phone woke the machine"), announced.toList())
        assertEquals(listOf("machineState(IDLE)", "machineState(IDLE)"), actions.applied.toList())
    }

    @Test
    fun `config edits and maintenance are not announced`() {
        dispatch("setQcGrind", "14.5")
        dispatch("machineState", "DESCALE")
        dispatch("cancelMaintenance")
        assertTrue(announced.isEmpty(), "$announced")
        assertEquals(3, actions.peerActivity)
    }
}
