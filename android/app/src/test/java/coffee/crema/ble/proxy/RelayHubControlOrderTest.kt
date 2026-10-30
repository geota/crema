package coffee.crema.ble.proxy

import coffee.crema.machine.CommandOrigin
import coffee.crema.machine.CommandOutcome
import coffee.crema.machine.LocalMachine
import coffee.crema.machine.MachineCommand
import coffee.crema.machine.QcField
import coffee.crema.machine.RecordingMachineActions
import coffee.crema.machine.RelayedCommandDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A session's controls reach the primary's handler one at a time, in the
 * order the secondary sent them, even when the hub runs on a thread pool (as
 * on Ktor's IO threads) and a handler takes a while; and through the machine
 * seam they apply on the lane in that order.
 */
class RelayHubControlOrderTest {

    private fun hub(handler: suspend (String, String, String?, String?) -> Result<Unit>) = RelayHub(
        primaryName = "Tablet",
        roster = { emptyList() },
        readSource = { _, _, _ -> error("unused") },
        isSnapshotChar = { _, _ -> false },
        controlHandler = handler,
    )

    /** Pair a client with [hub] on a thread pool, send [count] controls back to
     *  back, and wait for every reply. */
    private fun sendInOrder(hub: RelayHub, count: Int, control: (Int) -> Frame.Control) = runBlocking {
        val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            io.launch { hub.serve(server, TEST_PEER) }
            val rx = CopyOnWriteArrayList<Frame>()
            io.launch { client.incoming().collect { rx += it } }
            awaitWelcome(rx)
            for (i in 1..count) client.send(control(i))
            withTimeout(10_000) { while (rx.count { it is Frame.ControlOk || it is Frame.ControlErr } < count) delay(5) }
            // One reply per control, in order.
            assertEquals((1..count).map { it.toLong() }, rx.mapNotNull { (it as? Frame.ControlOk)?.id })
        } finally {
            io.cancel()
        }
    }

    @Test
    fun `controls from one session are handled one at a time in arrival order`() {
        val handled = CopyOnWriteArrayList<String>()
        var running = 0
        var overlapped = false
        val hub = hub { _, args, _, _ ->
            synchronized(this) { if (running++ > 0) overlapped = true }
            // A later control that finishes faster must still wait its turn.
            delay(Random.nextLong(0, 5))
            handled += args
            synchronized(this) { running-- }
            Result.success(Unit)
        }
        sendInOrder(hub, 40) { i -> Frame.Control(i.toLong(), "setQcGrind", "$i.0") }
        assertEquals((1..40).map { "$it.0" }, handled.toList())
        assertEquals(false, overlapped, "two controls of one session ran at once")
    }

    /** AC-21, the ordering case (spec §7.3): a hub that applies a relayed
     *  edit pushes its new snapshot before it answers, so a remote never sees
     *  its edit accepted while it still holds the old value. */
    @Test
    fun `an accepted edit's config push reaches the remote before the reply`() = runBlocking {
        var config = """{"qcGrind":10.0}"""
        lateinit var hub: RelayHub
        hub = RelayHub(
            primaryName = "Tablet",
            roster = { emptyList() },
            readSource = { _, _, _ -> error("unused") },
            isSnapshotChar = { _, _ -> false },
            controlHandler = { _, args, _, _ ->
                config = """{"qcGrind":$args}"""
                hub.pushConfig(config)
                Result.success(Unit)
            },
            configSource = { config },
        )
        val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            io.launch { hub.serve(server, TEST_PEER) }
            val rx = CopyOnWriteArrayList<Frame>()
            io.launch { client.incoming().collect { rx += it } }
            awaitWelcome(rx)
            for (i in 1..20) client.send(Frame.Control(i.toLong(), "setQcGrind", "$i.5"))
            withTimeout(10_000) { while (rx.count { it is Frame.ControlOk } < 20) delay(5) }
            for (i in 1..20) {
                val push = rx.indexOfFirst { it is Frame.Config && it.json == """{"qcGrind":$i.5}""" }
                val reply = rx.indexOfFirst { it is Frame.ControlOk && it.id == i.toLong() }
                assertTrue(push in 0 until reply, "edit $i: push at $push, reply at $reply")
            }
        } finally {
            io.cancel()
        }
    }

    @Test
    fun `through the machine seam they apply on the lane in that order`() {
        val laneExecutor = Executors.newSingleThreadExecutor { Thread(it, "main-lane") }
        try {
            val actions = RecordingMachineActions()
            val dispatcher = RelayedCommandDispatcher(
                machine = LocalMachine(actions, notify = {}),
                hostName = { "Tablet" },
                machineIdle = { false },
                announce = { _, _ -> },
                log = {},
                lane = laneExecutor.asCoroutineDispatcher(),
            )
            val hub = hub { method, args, id, name ->
                when (val outcome = dispatcher.dispatch(method, args, CommandOrigin(id, name))) {
                    CommandOutcome.Accepted -> Result.success(Unit)
                    else -> Result.failure(IllegalStateException(outcome.toString()))
                }
            }
            // A setting then a start, repeatedly: a swap would start on the old value.
            sendInOrder(hub, 40) { i ->
                if (i % 2 == 1) Frame.Control(i.toLong(), "setQcGrind", "$i.0") else Frame.Control(i.toLong(), "startShot", "")
            }
            val expected = (1..40).map { i ->
                if (i % 2 == 1) "commit(${MachineCommand.SetQc(QcField.GRIND, i.toFloat())})" else "startShot"
            }
            assertEquals(expected, actions.applied.toList())
            assertEquals(setOf("main-lane"), actions.threads.toSet())
        } finally {
            laneExecutor.shutdownNow()
        }
    }

    /** AC-5, the session half (verifier P3-3): a throw around a relayed
     *  command (here the host name) comes back as that control's
     *  `ControlErr`, and the same session serves the next control. */
    @Test
    fun `a throw outside the command refuses that control and the session keeps serving`() = runBlocking {
        val actions = RecordingMachineActions()
        var hostNameCalls = 0
        val dispatcher = RelayedCommandDispatcher(
            machine = LocalMachine(actions, notify = {}),
            hostName = { check(hostNameCalls++ > 0) { "hostName failed" }; "Tablet" },
            machineIdle = { false },
            announce = { _, _ -> },
            log = {},
            lane = Dispatchers.Default,
        )
        // The mapping ProxyController wires into its hub.
        val hub = hub { method, args, id, name ->
            when (val outcome = dispatcher.dispatch(method, args, CommandOrigin(id, name))) {
                CommandOutcome.Accepted -> Result.success(Unit)
                is CommandOutcome.Refused -> Result.failure(IllegalStateException(outcome.reason))
                is CommandOutcome.Unknown -> Result.failure(IllegalStateException(outcome.detail))
            }
        }
        val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            io.launch { hub.serve(server, TEST_PEER) }
            val rx = CopyOnWriteArrayList<Frame>()
            io.launch { client.incoming().collect { rx += it } }
            awaitWelcome(rx)
            client.send(Frame.Control(1, "tareScale", ""))
            client.send(Frame.Control(2, "tareScale", ""))
            withTimeout(5_000) { while (rx.count { it is Frame.ControlOk || it is Frame.ControlErr } < 2) delay(5) }
            assertEquals(
                listOf<Frame>(Frame.ControlErr(1, "hostName failed"), Frame.ControlOk(2)),
                rx.filter { it is Frame.ControlOk || it is Frame.ControlErr },
            )
            assertEquals(listOf("tareScale"), actions.applied.toList())
        } finally {
            io.cancel()
        }
    }

    /** Validation D6, the session half: a failed announcement after the
     *  command ran still answers `ControlOk`, so the remote doesn't revert. */
    @Test
    fun `a failed announcement after the command ran still answers ControlOk`() = runBlocking {
        val actions = RecordingMachineActions()
        val dispatcher = RelayedCommandDispatcher(
            machine = LocalMachine(actions, notify = {}),
            hostName = { "Tablet" },
            machineIdle = { false },
            announce = { _, _ -> error("announce failed") },
            log = {},
            lane = Dispatchers.Default,
        )
        val hub = hub { method, args, id, name ->
            when (val outcome = dispatcher.dispatch(method, args, CommandOrigin(id, name))) {
                CommandOutcome.Accepted -> Result.success(Unit)
                is CommandOutcome.Refused -> Result.failure(IllegalStateException(outcome.reason))
                is CommandOutcome.Unknown -> Result.failure(IllegalStateException(outcome.detail))
            }
        }
        val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            io.launch { hub.serve(server, TEST_PEER) }
            val rx = CopyOnWriteArrayList<Frame>()
            io.launch { client.incoming().collect { rx += it } }
            awaitWelcome(rx)
            client.send(Frame.Control(1, "startShot", ""))
            withTimeout(5_000) { while (rx.none { it is Frame.ControlOk || it is Frame.ControlErr }) delay(5) }
            assertEquals(listOf<Frame>(Frame.ControlOk(1)), rx.filter { it is Frame.ControlOk || it is Frame.ControlErr })
            assertEquals(listOf("startShot"), actions.applied.toList())
        } finally {
            io.cancel()
        }
    }
}
