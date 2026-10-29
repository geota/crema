package coffee.crema.firmware

import coffee.crema.core.DecentFirmwareChannel
import coffee.crema.core.FirmwareArtifact
import coffee.crema.core.FlashAction
import coffee.crema.core.FlashActionWriteInner
import coffee.crema.core.FlashBlocker
import coffee.crema.core.FlashFailure
import coffee.crema.core.FlashPhase
import coffee.crema.core.FlashShellConditions
import coffee.crema.core.FlashStartError
import coffee.crema.core.FlashStartErrorBlockedInner
import coffee.crema.core.FlashStatus
import coffee.crema.core.FlashStep
import coffee.crema.core.WriteTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android I/O half of the firmware flash (web `flash-driver.vitest.ts`
 * parity): the exact bytes the core emits (its golden fixture), actions one
 * at a time, every result back to the core, one attempt per write (audit M2),
 * a drain of ordinary writes before the first flash write (M1), ticks during
 * an in-flight action (M4), nothing queued before a cancel written after it
 * (L1). The core itself is covered by the Rust tests (incl. a simulated DE1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareFlashDriverTest {
    private val artifact = FirmwareArtifact(
        id = "de1-1358", build = 1358u, channel = DecentFirmwareChannel.Nightly,
        channels = listOf(DecentFirmwareChannel.Nightly), urls = listOf("https://x"), byteLength = 32u,
        sha256 = "0".repeat(64), boardMarker = 0xDE100001u, bodyByteCount = 1u, cpuByteCount = 1u,
        chunkCount = 2u, releaseNotes = "", provenance = "",
    )

    private fun status(phase: FlashPhase, locks: Boolean = true, failure: FlashFailure? = null) = FlashStatus(
        phase = phase, artifact = artifact, targetBuild = 1358u, chunksTotal = 2u, chunksAcked = 0u,
        percent = 0f, etaIsEstimate = true, elapsedSeconds = 0u, canCancel = true, locksWrites = locks,
        eraseSettling = false, canExit = false, failure = failure, verifyUnconfirmed = false, machineRestarted = false,
        linkUp = true,
        identityUnknown = false,
    )

    private fun write(target: WriteTarget, vararg b: Int) =
        FlashAction.Write(FlashActionWriteInner(target, b.map { it.toUByte() }))

    /** Answers each call from its script; unscripted calls keep the last status. */
    private class ScriptedCore(val script: MutableMap<String, ArrayDeque<FlashStep>>) : FlashCorePort {
        val calls = mutableListOf<String>()
        val failMessages = mutableListOf<String>()
        private var last: FlashStatus? = null
        private fun next(name: String): FlashStep {
            calls += name
            maybeThrow(name)
            val step = script[name]?.removeFirstOrNull() ?: FlashStep(actions = emptyList(), status = last)
            if (step.status != null) last = step.status
            return step
        }
        override fun blockers(conditions: FlashShellConditions) = emptyList<FlashBlocker>()
        override fun start(image: ByteArray, conditions: FlashShellConditions, nowMs: Long) = next("start")
        override fun onWriteAck(nowMs: Long) = next("ack")
        override fun onWriteFailed(message: String, nowMs: Long): FlashStep { failMessages += message; return next("failed") }
        override fun onFwMap(data: ByteArray, nowMs: Long) = next("fwMap")
        override fun onReadFailed(nowMs: Long) = next("readFailed")
        override fun tick(nowMs: Long) = next("tick")
        override fun linkRestored(nowMs: Long, machine: String) = next("restored")
        override fun bindMachine(serial: UInt, linkId: String) { calls += "bind:$serial:$linkId" }
        override fun cancel(nowMs: Long) = next("cancel")
        override fun dismiss() = true
        override fun exit() = true
        override fun status(nowMs: Long) = next("status")
        override fun restoreAwaiting(build: UInt, unconfirmed: Boolean, nowMs: Long) = next("restore")
        override fun restoreEraseHold(build: UInt, holdMsLeft: Long, nowMs: Long) = next("restoreHold")
        override fun restoreVerifying(build: UInt, holdMsLeft: Long, nowMs: Long) = next("restoreVerify")

        /** Throw from these call names (a driver / core bug). */
        val throwOn = mutableSetOf<String>()
        fun maybeThrow(name: String) { if (name in throwOn) error("boom in $name") }
    }

    private class FakeIo(val failWrites: Boolean = false, val failReads: Boolean = false) : FlashIoPort {
        val writes = mutableListOf<Pair<WriteTarget, List<Int>>>()
        val events = mutableListOf<String>()
        val exclusive = mutableListOf<Boolean>()
        var attempts = 0
        var inFlight = 0
        var maxInFlight = 0
        var frames: ((ByteArray) -> Unit)? = null

        /** When set, the next write waits for it (a hung GATT write). */
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun write(target: WriteTarget, data: ByteArray) {
            attempts++
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            hold?.let {
                hold = null
                it.await()
            }
            kotlinx.coroutines.delay(5)
            inFlight--
            if (failWrites) error("GATT 133")
            writes += target to data.map { it.toInt() and 0xFF }
            events += "write"
        }
        override suspend fun readFwMap(): ByteArray {
            if (failReads) error("busy")
            return byteArrayOf(0, 0, 0, 1, -1, -1, -3)
        }
        override suspend fun subscribeFwMap(onFrame: (ByteArray) -> Unit) { frames = onFrame }
        override suspend fun awaitQuiet(): Boolean { events += "drain"; return true }
        override fun setExclusive(on: Boolean) { exclusive += on }
    }

    private fun TestScope.driver(core: FlashCorePort, io: FlashIoPort, statuses: MutableList<FlashStatus?>, keep: MutableList<Boolean>) =
        FirmwareFlashDriver(core, io, backgroundScope, clock = { testScheduler.currentTime }, onStatus = { statuses += it }, keepAlive = { keep += it })

    private val ok = FlashShellConditions(connected = true, ready = true, guidedBrewActive = false, powerOk = true)

    @Test
    fun writesExactlyTheBytesTheCoreEmits() = runTest {
        // Relative to the module (android/app), like the web test's `../core`.
        val file = File("../../core/de1-app/tests/fixtures/flash-steps.json")
        assertTrue(file.isFile, "golden fixture missing at ${file.absolutePath}")
        val fixture = flashJson.decodeFromString(MapSerializer(String.serializer(), FlashStep.serializer()), file.readText())
        fun f(key: String): FlashStep = fixture.getValue(key)
        val held = f("erase").status
        fun ack(key: String) = FlashStep(f(key).actions, f(key).status ?: held)
        val core = ScriptedCore(mutableMapOf(
            "start" to ArrayDeque(listOf(f("start"))),
            "ack" to ArrayDeque(listOf(
                FlashStep(emptyList(), f("start").status),
                FlashStep(emptyList(), held),
                ack("chunk0x1234"),
                ack("verify"),
                ack("readBuild"),
            )),
            "tick" to ArrayDeque(listOf(f("erase"))),
            "fwMap" to ArrayDeque(listOf(f("chunk0"))),
            // After the build read the driver re-reads the status: the serial read.
            "status" to ArrayDeque(listOf(ack("readSerial"))),
        ))
        val io = FakeIo()
        driver(core, io, mutableListOf(), mutableListOf()).start(ByteArray(16), ok)
        runCurrent(); advanceTimeBy(20); runCurrent()
        advanceTimeBy(TICK_MS); runCurrent() // → erase
        advanceTimeBy(20); runCurrent()
        io.frames!!(byteArrayOf(0, 0, 0, 1, -1, -1, -1)) // → chunk 0, then the scripted rest
        runCurrent(); advanceTimeBy(100); runCurrent()

        val sent = io.writes.map { (t, d) -> t to d.take(7) }
        assertEquals(WriteTarget.De1RequestedState to listOf(0x00), sent[0])
        assertEquals(WriteTarget.De1FwMapRequest to listOf(0, 0, 1, 1, 0xFF, 0xFF, 0xFF), sent[1])
        assertEquals(WriteTarget.De1MmrWrite to listOf(0x10, 0, 0, 0, 0, 1, 2), sent[2])
        // Chunk 0x1234: offset 0x12340, big-endian, length byte 0x10, 20 bytes.
        assertEquals(WriteTarget.De1MmrWrite, sent[3].first)
        assertEquals(listOf(0x10, 0x01, 0x23, 0x40), sent[3].second.take(4))
        assertEquals(20, io.writes[3].second.size)
        assertEquals(WriteTarget.De1FwMapRequest to listOf(0, 0, 0, 1, 0xFF, 0xFF, 0xFF), sent[4])
        // The build read: an MMR read request for 0x800010, through the lock.
        assertEquals(WriteTarget.De1MmrRequest, io.writes[5].first)
        assertEquals(listOf(0x00, 0x80, 0x00, 0x10), io.writes[5].second.take(4))
        // The serial read (the DE1's identity): MMR 0x803830, through the lock.
        assertEquals(WriteTarget.De1MmrRequest, io.writes[6].first)
        assertEquals(listOf(0x00, 0x80, 0x38, 0x30), io.writes[6].second.take(4))
        // Cancel's Sleep.
        assertEquals(listOf<FlashAction>(write(WriteTarget.De1RequestedState, 0)), f("cancel").actions)
    }

    @Test
    fun performsActionsOneAtATimeAfterDrainingOrdinaryWrites() = runTest {
        val core = ScriptedCore(mutableMapOf(
            "start" to ArrayDeque(listOf(FlashStep(listOf(FlashAction.SubscribeFwMap, write(WriteTarget.De1RequestedState, 0)), status(FlashPhase.Preparing)))),
            "ack" to ArrayDeque(listOf(
                FlashStep(listOf(write(WriteTarget.De1MmrWrite, 16, 0, 0, 0)), status(FlashPhase.Uploading)),
                FlashStep(listOf(write(WriteTarget.De1MmrWrite, 16, 0, 0, 16)), status(FlashPhase.Uploading)),
                FlashStep(emptyList(), status(FlashPhase.Verifying)),
            )),
        ))
        val io = FakeIo()
        val keep = mutableListOf<Boolean>()
        assertNull(driver(core, io, mutableListOf(), keep).start(ByteArray(32), ok))
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf(listOf(0), listOf(16, 0, 0, 0), listOf(16, 0, 0, 16)), io.writes.map { it.second })
        assertEquals("drain", io.events.first(), "ordinary writes drain before the first flash write")
        assertEquals(1, io.maxInFlight)
        assertEquals(3, core.calls.count { it == "ack" })
        assertTrue(io.exclusive.last())
        assertEquals(listOf(true), keep, "keep-alive set once, not per status")
    }

    @Test
    fun aFailedWriteIsTriedOnceReportedAndTheQueueDropped() = runTest {
        val core = ScriptedCore(mutableMapOf(
            "start" to ArrayDeque(listOf(FlashStep(listOf(write(WriteTarget.De1MmrWrite, 1), write(WriteTarget.De1MmrWrite, 2)), status(FlashPhase.Uploading)))),
            "failed" to ArrayDeque(listOf(FlashStep(emptyList(), status(FlashPhase.Failed, locks = false)))),
        ))
        val io = FakeIo(failWrites = true)
        val statuses = mutableListOf<FlashStatus?>()
        driver(core, io, statuses, mutableListOf()).start(ByteArray(32), ok)
        advanceTimeBy(100); runCurrent()
        assertEquals(1, io.attempts, "no per-chunk retry (audit M2)")
        assertEquals(1, core.failMessages.size)
        assertTrue("GATT 133" in core.failMessages[0])
        assertEquals(FlashPhase.Failed, statuses.last()?.phase)
        assertEquals(false, io.exclusive.last())
    }

    @Test
    fun ticksReachTheCoreWhileAWriteHangs() = runTest {
        val core = ScriptedCore(mutableMapOf(
            "start" to ArrayDeque(listOf(FlashStep(listOf(write(WriteTarget.De1FwMapRequest, 0)), status(FlashPhase.Erasing)))),
        ))
        val io = FakeIo().apply { hold = CompletableDeferred() }
        driver(core, io, mutableListOf(), mutableListOf()).start(ByteArray(32), ok)
        advanceTimeBy(TICK_MS * 4 + 1); runCurrent()
        assertEquals(0, io.writes.size)
        assertTrue(core.calls.count { it == "tick" } >= 3, "the core's timeouts run during an in-flight write (audit M4)")
    }

    @Test
    fun nothingQueuedBeforeACancelIsWrittenAfterIt() = runTest {
        val core = ScriptedCore(mutableMapOf(
            "start" to ArrayDeque(listOf(FlashStep(
                listOf(write(WriteTarget.De1MmrWrite, 1), write(WriteTarget.De1MmrWrite, 2), write(WriteTarget.De1MmrWrite, 3)),
                status(FlashPhase.Uploading),
            ))),
            "cancel" to ArrayDeque(listOf(FlashStep(listOf(write(WriteTarget.De1RequestedState, 0)), status(FlashPhase.Cancelled, locks = false)))),
        ))
        val gate = CompletableDeferred<Unit>()
        val io = FakeIo().apply { hold = gate }
        val d = driver(core, io, mutableListOf(), mutableListOf())
        d.start(ByteArray(32), ok)
        runCurrent() // chunk 1 in flight
        d.cancel()
        gate.complete(Unit)
        advanceTimeBy(100); runCurrent()
        assertEquals(
            listOf(WriteTarget.De1MmrWrite to listOf(1), WriteTarget.De1RequestedState to listOf(0)),
            io.writes,
            "the in-flight chunk lands, the queued ones don't, Sleep follows",
        )
    }

    @Test
    fun pollReadsFailuresAndLinkEventsReachTheCore() = runTest {
        val core = ScriptedCore(mutableMapOf(
            "start" to ArrayDeque(listOf(FlashStep(listOf(FlashAction.ReadFwMap), status(FlashPhase.Erasing)))),
        ))
        val d = driver(core, FakeIo(), mutableListOf(), mutableListOf())
        d.start(ByteArray(32), ok)
        advanceTimeBy(TICK_MS * 3); runCurrent()
        assertTrue("fwMap" in core.calls)
        assertTrue("tick" in core.calls)
        d.onLinkRestored()
        runCurrent()
        assertTrue("restored" in core.calls)

        val failing = ScriptedCore(mutableMapOf(
            "start" to ArrayDeque(listOf(FlashStep(listOf(FlashAction.ReadFwMap), status(FlashPhase.Verifying)))),
        ))
        driver(failing, FakeIo(failReads = true), mutableListOf(), mutableListOf()).start(ByteArray(32), ok)
        runCurrent()
        assertTrue("readFailed" in failing.calls)
    }

    @Test
    fun exitReleasesTheLinkAndStopsTheClock() = runTest {
        val core = ScriptedCore(mutableMapOf(
            "restore" to ArrayDeque(listOf(FlashStep(emptyList(), status(FlashPhase.AwaitingPowerCycle)))),
            "status" to ArrayDeque(listOf(FlashStep(emptyList(), status(FlashPhase.AwaitingPowerCycle)))),
        ))
        val io = FakeIo()
        val statuses = mutableListOf<FlashStatus?>()
        val d = driver(core, io, statuses, mutableListOf())
        assertEquals(FlashPhase.AwaitingPowerCycle, d.restoreAwaiting(1358u, false)?.phase)
        assertTrue(io.exclusive.last(), "a restored power-cycle step holds the link")
        assertTrue(d.exit())
        assertFalse(io.exclusive.last())
        assertNull(statuses.last())
        val ticks = core.calls.count { it == "tick" }
        advanceTimeBy(TICK_MS * 4); runCurrent()
        assertEquals(ticks, core.calls.count { it == "tick" })
    }

    @Test
    fun aRefusedStartReturnsTheCoreError() = runTest {
        val err = FlashStartError.Blocked(FlashStartErrorBlockedInner(listOf(FlashBlocker.LowPower)))
        val core = ScriptedCore(mutableMapOf("start" to ArrayDeque(listOf(FlashStep(emptyList(), null, err)))))
        val io = FakeIo()
        assertEquals(err, driver(core, io, mutableListOf(), mutableListOf()).start(ByteArray(1), ok))
        advanceTimeBy(TICK_MS * 2); runCurrent()
        assertTrue("tick" !in core.calls, "no clock without a flash")
        assertTrue(io.writes.isEmpty())
    }

    @Test
    fun aThrowingTickNeverStopsTheClock() = runTest {
        val core = ScriptedCore(mutableMapOf(
            "start" to ArrayDeque(listOf(FlashStep(emptyList(), status(FlashPhase.Erasing)))),
        ))
        val logs = mutableListOf<String>()
        val d = FirmwareFlashDriver(core, FakeIo(), backgroundScope, clock = { testScheduler.currentTime }, onStatus = {}, onLog = { logs += it })
        d.start(ByteArray(32), ok)
        core.throwOn += "tick"
        advanceTimeBy(TICK_MS * 3 + 1); runCurrent()
        core.throwOn.clear()
        val before = core.calls.count { it == "tick" }
        advanceTimeBy(TICK_MS * 3 + 1); runCurrent()
        assertTrue(core.calls.count { it == "tick" } > before, "ticks resume after errors (audit r2 LOW 5)")
        assertTrue(logs.any { "tick error" in it })
    }

    @Test
    fun aDriverErrorInThePumpFailsTheFlashThroughTheCore() = runTest {
        val core = ScriptedCore(mutableMapOf(
            "start" to ArrayDeque(listOf(FlashStep(listOf(write(WriteTarget.De1MmrWrite, 1)), status(FlashPhase.Uploading)))),
            "failed" to ArrayDeque(listOf(FlashStep(emptyList(), status(FlashPhase.Failed, locks = false)))),
        ))
        core.throwOn += "ack"
        val statuses = mutableListOf<FlashStatus?>()
        driver(core, FakeIo(), statuses, mutableListOf()).start(ByteArray(32), ok)
        advanceTimeBy(100); runCurrent()
        assertTrue(core.failMessages.single().startsWith("driver error"))
        assertEquals(FlashPhase.Failed, statuses.last()?.phase)
    }

    @Test
    fun theCoresDiagnosticLinesReachTheLog() = runTest {
        val core = ScriptedCore(mutableMapOf(
            "start" to ArrayDeque(listOf(FlashStep(emptyList(), status(FlashPhase.Preparing), log = listOf("[+00:00] start: build 1358")))),
        ))
        val logs = mutableListOf<String>()
        FirmwareFlashDriver(
            core, FakeIo(), backgroundScope, clock = { 0L }, onStatus = {}, onLog = { logs += it },
            transportLabel = { "Nordic BLE, DE1 AA:BB" },
        ).start(ByteArray(32), ok)
        assertTrue("Firmware: [+00:00] start: build 1358" in logs, "$logs")
        assertTrue(logs.any { "over Nordic BLE, DE1 AA:BB" in it }, "$logs")
    }
}
