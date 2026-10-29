package coffee.crema.machine

import coffee.crema.core.MachineRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A primary's relayed-control entry: decode and check, run on the lane in
 *  order, refuse unknown references, and announce only accepted commands. */
class RelayedCommandDispatcherTest {

    /** Stands in for Main: one named thread. */
    private val laneExecutor = Executors.newSingleThreadExecutor { Thread(it, LANE) }
    private val actions = RecordingMachineActions()
    private val announced = CopyOnWriteArrayList<String>()
    private var machineIdle = false
    private val phone = CommandOrigin("phone-1", "Phone")

    private val dispatcher = RelayedCommandDispatcher(
        machine = LocalMachine(actions, grantTakeOver = { CommandOutcome.Accepted }, notify = {}),
        hostName = { "Tablet" },
        machineIdle = { machineIdle },
        announce = { text, _ -> announced += text },
        log = {},
        lane = laneExecutor.asCoroutineDispatcher(),
    )

    @AfterTest
    fun tearDown() {
        laneExecutor.shutdownNow()
    }

    private fun dispatch(method: String, args: String = "", origin: CommandOrigin = phone) =
        runBlocking { dispatcher.dispatch(method, args, origin) }

    @Test
    fun `commands run on the lane in the order they were dispatched`() {
        runBlocking {
            // Each caller reaches the lane's queue before the next starts, as
            // the hub's per-session queue hands them over.
            val jobs = (1..20).map { i ->
                launch(start = CoroutineStart.UNDISPATCHED) { dispatcher.dispatch("setQcGrind", "$i.0", phone) }
            }
            jobs.forEach { it.join() }
        }
        assertEquals((1..20).map { "commit(${MachineCommand.SetQc(QcField.GRIND, it.toFloat())})" }, actions.applied.toList())
        assertEquals(setOf(LANE), actions.threads.toSet(), "every command ran on the lane")
    }

    @Test
    fun `a malformed control is refused before anything runs`() {
        val outcome = assertIs<CommandOutcome.Refused>(dispatch("machineState", "AIR_PURGE"))
        assertTrue("not relayable" in outcome.reason, outcome.reason)
        assertIs<CommandOutcome.Refused>(dispatch("setQcSteamTemp", "NaN"))
        assertIs<CommandOutcome.Refused>(dispatch("writeMmr", "1"))
        assertTrue(actions.applied.isEmpty() && actions.peerActivity == 0)
    }

    @Test
    fun `a profile or bean this primary lacks is refused and nothing changes`() {
        assertEquals(CommandOutcome.Refused("That profile isn’t on Tablet"), dispatch("setActiveProfile", "p-elsewhere"))
        assertEquals(CommandOutcome.Refused("That bean isn’t on Tablet"), dispatch("setActiveBean", "bean-elsewhere"))
        assertTrue(actions.applied.isEmpty() && actions.shown.isEmpty(), "nothing shown or committed: ${actions.applied}")

        assertEquals(CommandOutcome.Accepted, dispatch("setActiveProfile", "p-1"))
        assertEquals(CommandOutcome.Accepted, dispatch("setActiveBean", "bean-1"))
        assertEquals(CommandOutcome.Accepted, dispatch("setActiveBean", ""), "clearing the bean names nothing")
        assertEquals(3, actions.applied.size)
    }

    @Test
    fun `a refused start goes back to the sender and is not announced`() {
        actions.startShot = CommandOutcome.Refused("A shot is already starting")
        assertEquals(CommandOutcome.Refused("A shot is already starting"), dispatch("startShot"))
        assertTrue(announced.isEmpty(), "$announced")

        actions.startShot = CommandOutcome.Accepted
        assertEquals(CommandOutcome.Accepted, dispatch("startShot"))
        assertEquals(listOf("Phone started a shot"), announced.toList())
    }

    @Test
    fun `an older build's raw espresso runs the guarded start`() {
        assertEquals(CommandOutcome.Accepted, dispatch("machineState", "ESPRESSO"))
        assertEquals(listOf("startShot"), actions.applied.toList())
    }

    /** No relayed machine state reaches a raw Espresso write, and only the
     *  states the Android UI requests are run at all. */
    @Test
    fun `no relayed machine state is a raw espresso or a state the UI can't send`() {
        val uiStates = setOf("SLEEP", "IDLE", "ESPRESSO", "STEAM", "HOT_WATER", "FLUSH", "STEAM_RINSE", "DESCALE", "CLEAN")
        for (state in MachineRequest.entries.map { it.name }) {
            val outcome = dispatch("machineState", state)
            if (state in uiStates) assertEquals(CommandOutcome.Accepted, outcome, state)
            else assertIs<CommandOutcome.Refused>(outcome, state)
        }
        assertTrue("machineState(ESPRESSO)" !in actions.applied, "${actions.applied}")
        assertEquals(1, actions.applied.count { it == "startShot" }, "ESPRESSO ran the guarded start once")
        assertEquals(uiStates.size, actions.applied.size, "${actions.applied}")
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

    private companion object {
        const val LANE = "main-lane"
    }
}
