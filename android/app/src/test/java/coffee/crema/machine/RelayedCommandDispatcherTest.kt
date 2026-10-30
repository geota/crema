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
    private var hostNameThrows = false
    private var machineIdleThrows = false
    private var announceThrows = false
    private val logged = CopyOnWriteArrayList<String>()
    private val phone = CommandOrigin("phone-1", "Phone")

    private val dispatcher = RelayedCommandDispatcher(
        machine = LocalMachine(actions, notify = {}, hostName = { "Tablet" }),
        hostName = { check(!hostNameThrows) { "hostName failed" }; "Tablet" },
        machineIdle = { check(!machineIdleThrows) { "machineIdle failed" }; machineIdle },
        announce = { text, _ -> check(!announceThrows) { "announce failed" }; announced += text },
        log = { logged += it },
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

    /** AC-41: `handoff` is refused as an unknown control, with or without
     *  arguments, before anything runs or is announced. */
    @Test
    fun `a handoff is refused as unknown and runs nothing`() {
        val reason = "unknown relayed control: handoff"
        assertEquals(CommandOutcome.Refused(reason), dispatch("handoff"))
        assertEquals(CommandOutcome.Refused(reason), dispatch("handoff", "x"))
        assertTrue(actions.applied.isEmpty() && actions.shown.isEmpty(), "${actions.applied}")
        assertEquals(0, actions.peerActivity, "a refused control isn't presence")
        assertTrue(announced.isEmpty(), "$announced")
    }

    /** AC-5 (verifier P3-3): a throw from any step around the command, not
     *  only the command, comes back as a refusal, and the next control runs. */
    @Test
    fun `a throw outside the command becomes a refusal`() {
        actions.throwing += "notePeerActivity"
        assertEquals(CommandOutcome.Refused("notePeerActivity failed"), dispatch("startShot"))
        actions.throwing.clear()

        actions.throwing += "hasProfile" // unknownReference
        assertEquals(CommandOutcome.Refused("hasProfile failed"), dispatch("setActiveProfile", "p-1"))
        actions.throwing.clear()

        hostNameThrows = true
        assertEquals(CommandOutcome.Refused("hostName failed"), dispatch("tareScale"))
        hostNameThrows = false

        machineIdleThrows = true // phrase() words an IDLE with it
        assertEquals(CommandOutcome.Refused("machineIdle failed"), dispatch("machineState", "IDLE"))
        machineIdleThrows = false

        assertTrue(actions.applied.isEmpty(), "nothing ran: ${actions.applied}")
        assertTrue(announced.isEmpty(), "$announced")
        assertEquals(CommandOutcome.Accepted, dispatch("tareScale"), "the next control still runs")
        assertEquals(listOf("tareScale"), actions.applied.toList())
    }

    /** Validation D6: once the command ran, a failed announcement is logged
     *  and the outcome stays Accepted, so the remote keeps the change. */
    @Test
    fun `a failed announcement after the command ran is still accepted`() {
        announceThrows = true
        assertEquals(CommandOutcome.Accepted, dispatch("startShot"))
        assertEquals(listOf("startShot"), actions.applied.toList(), "the command ran once")
        assertTrue(logged.any { "announcing it failed" in it }, "$logged")
        announceThrows = false
        assertEquals(CommandOutcome.Accepted, dispatch("tareScale"))
        assertEquals(listOf("Phone tared the scale"), announced.toList())
    }

    /** AC-27 (review 17): before the hub loaded its config, a relayed config
     *  edit is refused (the load would overwrite it after the reply); machine
     *  control still runs. */
    @Test
    fun `a config edit before the hub loaded its config is refused`() {
        actions.configLoaded = false
        assertEquals(CommandOutcome.Refused("Tablet is starting up"), dispatch("setQcGrind", "14.5"))
        assertTrue(actions.applied.isEmpty() && actions.shown.isEmpty())
        assertEquals(CommandOutcome.Accepted, dispatch("tareScale"))
        actions.configLoaded = true
        assertEquals(CommandOutcome.Accepted, dispatch("setQcGrind", "14.5"))
    }

    /** AC-43 (relay v4 R4): each of the seven read-only Quick Controls
     *  methods is refused as unknown, and nothing is applied or announced. */
    @Test
    fun readOnlyQcRefused() {
        for (method in listOf(
            "setQcSteamTime", "setQcSteamFlow", "setQcSteamTemp", "setQcHotWaterTemp",
            "setQcHotWaterVolume", "setQcFlushTime", "setQcFlushTemp",
        )) {
            assertEquals(CommandOutcome.Refused("unknown relayed control: $method"), dispatch(method, "30.0"))
        }
        assertTrue(actions.applied.isEmpty() && actions.shown.isEmpty(), "${actions.applied}")
        assertEquals(0, actions.peerActivity, "a refused control isn't presence")
        assertTrue(announced.isEmpty(), "$announced")
        assertEquals(CommandOutcome.Accepted, dispatch("setQcGrind", "14.5"), "grind is still relayed")
    }

    private companion object {
        const val LANE = "main-lane"
    }
}
