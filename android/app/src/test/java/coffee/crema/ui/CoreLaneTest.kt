package coffee.crema.ui

import coffee.crema.core.Command
import coffee.crema.core.CommandWriteCharacteristicInner
import coffee.crema.core.CommandWriteScaleInner
import coffee.crema.core.CoreOutput
import coffee.crema.core.Event
import coffee.crema.core.EventDecodeErrorInner
import coffee.crema.core.WriteTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The core lane (MainViewModel breakup step 1): events reach the handlers
 * registered for them in order, everything sent to the lane runs FIFO on it,
 * a bad payload never kills it, and an output's writes keep the DE1 rules
 * (one ordered batch; `Command.Stop` urgent, one at a time, and dropped by
 * `Command.CancelStop`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreLaneTest {

    private val log = mutableListOf<String>()
    private val de1 = mutableListOf<Pair<WriteTarget, Boolean>>()
    private val scaleWrites = mutableListOf<List<Byte>>()

    private fun TestScope.lane(
        writeDe1: suspend (WriteTarget, Boolean) -> Boolean = { target, urgent -> de1 += target to urgent; true },
    ) = CoreLane(
        scope = this,
        json = coreJson,
        log = { log += it },
        writeDe1 = { target, _, urgent, _ -> writeDe1(target, urgent) },
        writeScale = { scaleWrites += it.toList() },
        profileWriteFailed = { EMPTY },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun write(target: WriteTarget, vararg bytes: Int) =
        Command.WriteCharacteristic(CommandWriteCharacteristicInner(target, bytes.map { it.toUByte() }))

    @Test
    fun `handlers run in registration order, each only for its own event`() = runTest {
        val lane = lane()
        val seen = mutableListOf<String>()
        lane.on<Event.ShotStarted> { seen += "first" }
        lane.on<Event.ScaleStale> { seen += "stale" }
        lane.on<Event.ShotStarted> { seen += "second" }
        lane.applyOutput(
            CoreOutput(listOf(Event.ShotStarted, Event.DecodeError(EventDecodeErrorInner("x")), Event.ScaleStale), emptyList()),
        )
        assertEquals(listOf("first", "second", "stale"), seen, "an unregistered event is ignored")
    }

    @Test
    fun `posted work and delivered output run FIFO on the lane`() = runTest {
        val lane = lane()
        val order = mutableListOf<String>()
        lane.on<Event.ShotStarted> { order += "event" }
        lane.post { order += "before" }
        lane.deliver("""{"events":[{"type":"ShotStarted"}],"commands":[]}""")
        lane.post { order += "after" }
        assertEquals(emptyList(), order, "nothing runs on the caller")
        advanceUntilIdle()
        assertEquals(listOf("before", "event", "after"), order)
    }

    @Test
    fun `a bad payload or a failing handler is logged and the lane goes on`() = runTest {
        val lane = lane()
        var applied = 0
        lane.on<Event.ShotStarted> { if (applied++ == 0) error("boom") }
        lane.deliver("not json")
        lane.deliver("""{"events":[{"type":"ShotStarted"}],"commands":[]}""")
        lane.deliver("""{"events":[{"type":"ShotStarted"}],"commands":[]}""")
        advanceUntilIdle()
        assertTrue(log.any { it.startsWith("JSON parse error") })
        assertTrue("Core output application failed: boom" in log)
        assertEquals(2, applied)
    }

    @Test
    fun `a handler registered after output began to apply fails fast`() = runTest {
        val lane = lane()
        lane.applyOutput(CoreOutput(emptyList(), emptyList()))
        assertFailsWith<IllegalStateException> { lane.on<Event.ShotStarted> {} }
    }

    @Test
    fun `scale writes go out on their own, DE1 writes in order`() = runTest {
        val lane = lane()
        lane.applyOutput(
            CoreOutput(
                emptyList(),
                listOf(
                    write(WriteTarget.De1MmrRequest, 1),
                    Command.WriteScale(CommandWriteScaleInner(listOf(7u))),
                    write(WriteTarget.De1RequestedState, 2),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(listOf(listOf<Byte>(7)), scaleWrites)
        assertEquals(listOf(WriteTarget.De1MmrRequest to false, WriteTarget.De1RequestedState to false), de1)
    }

    @Test
    fun `a stop command is urgent, one at a time, and cancelled by a cancel`() = runTest {
        val wedged = CompletableDeferred<Boolean>()
        val bytes = mutableListOf<List<Byte>>()
        val lane = CoreLane(
            scope = this,
            json = coreJson,
            log = { log += it },
            writeDe1 = { target, data, urgent, _ -> de1 += target to urgent; bytes += data.toList(); wedged.await() },
            writeScale = {},
            profileWriteFailed = { EMPTY },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val stop = CoreOutput(emptyList(), listOf(Command.Stop))
        lane.applyOutput(stop)
        runCurrent()
        lane.applyOutput(stop)
        runCurrent()
        assertEquals(listOf(WriteTarget.De1RequestedState to true), de1, "one urgent stop, not two")
        assertEquals(listOf(listOf<Byte>(0x02)), bytes, "the Idle byte")
        assertTrue("Stop write already in flight — not stacking another" in log)
        lane.applyOutput(CoreOutput(emptyList(), listOf(Command.CancelStop)))
        assertTrue("Stale stop write cancelled — machine already stopped" in log)
        runCurrent()
        lane.applyOutput(stop)
        runCurrent()
        assertEquals(2, de1.size, "the next stop is not held back by the cancelled one")
        wedged.complete(true)
        advanceUntilIdle()
    }

    @Test
    fun `a plain machine-state write is never the stop, whatever the shell thinks is running`() = runTest {
        val lane = lane()
        lane.applyOutput(CoreOutput(emptyList(), listOf(write(WriteTarget.De1RequestedState, 2), write(WriteTarget.De1RequestedState, 14))))
        advanceUntilIdle()
        assertEquals(
            listOf(WriteTarget.De1RequestedState to false, WriteTarget.De1RequestedState to false),
            de1,
            "a skip or an Idle bounce rides the ordered batch",
        )
    }

    @Test
    fun `the stop byte is the core's Idle request`() {
        val out = coreJson.decodeFromString(
            CoreOutput.serializer(),
            coffee.crema.core.CremaBridge().requestMachineState(coffee.crema.core.MachineRequest.IDLE),
        )
        assertEquals(listOf(write(WriteTarget.De1RequestedState, 2)), out.commands)
    }

    private companion object {
        const val EMPTY = """{"events":[],"commands":[]}"""
    }
}
