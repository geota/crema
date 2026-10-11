package coffee.crema.firmware

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
import coffee.crema.core.DecentFirmwareChannel
import coffee.crema.core.WriteTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android I/O half of the firmware flash (web `flash-driver.vitest.ts`
 * parity): actions one at a time, every result back to the core, the exclusive
 * link + keep-alive following the core's status, ticks on the core clock. The
 * core itself is covered by the Rust tests (incl. a simulated DE1 end to end).
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
        failure = failure, verifyUnconfirmed = false, machineRestarted = false,
    )

    private fun write(target: WriteTarget, vararg b: Int) =
        FlashAction.Write(FlashActionWriteInner(target, b.map { it.toUByte() }))

    private class ScriptedCore(val script: MutableMap<String, ArrayDeque<FlashStep>>) : FlashCorePort {
        val calls = mutableListOf<String>()
        val failMessages = mutableListOf<String>()
        private fun next(name: String): FlashStep {
            calls += name
            return script[name]?.removeFirstOrNull() ?: FlashStep(actions = emptyList())
        }
        override fun blockers(conditions: FlashShellConditions) = emptyList<FlashBlocker>()
        override fun start(image: ByteArray, conditions: FlashShellConditions, nowMs: Long) = next("start")
        override fun onWriteAck(nowMs: Long) = next("ack")
        override fun onWriteFailed(message: String, nowMs: Long): FlashStep { failMessages += message; return next("failed") }
        override fun onFwMap(data: ByteArray, nowMs: Long) = next("fwMap")
        override fun onReadFailed(nowMs: Long) = next("readFailed")
        override fun tick(nowMs: Long) = next("tick")
        override fun linkRestored(nowMs: Long) = next("restored")
        override fun cancel(nowMs: Long) = next("cancel")
        override fun dismiss() = true
        override fun status(nowMs: Long) = next("status")
    }

    private class FakeIo(val failWrites: Boolean = false, val failReads: Boolean = false) : FlashIoPort {
        val writes = mutableListOf<List<Int>>()
        val exclusive = mutableListOf<Boolean>()
        var inFlight = 0
        var maxInFlight = 0
        override suspend fun write(target: WriteTarget, data: ByteArray) {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            kotlinx.coroutines.delay(5)
            inFlight--
            if (failWrites) error("GATT 133")
            writes += data.map { it.toInt() and 0xFF }
        }
        override suspend fun readFwMap(): ByteArray {
            if (failReads) error("busy")
            return byteArrayOf(0, 0, 0, 1, -1, -1, -3)
        }
        override suspend fun subscribeFwMap(onFrame: (ByteArray) -> Unit) = Unit
        override suspend fun readBuild() = Unit
        override fun setExclusive(on: Boolean) { exclusive += on }
    }

    private fun TestScope.driver(core: FlashCorePort, io: FlashIoPort, statuses: MutableList<FlashStatus?>, keep: MutableList<Boolean>) =
        FirmwareFlashDriver(core, io, backgroundScope, clock = { testScheduler.currentTime }, onStatus = { statuses += it }, keepAlive = { keep += it })

    private val ok = FlashShellConditions(connected = true, ready = true, guidedBrewActive = false, powerOk = true)

    @Test
    fun performsActionsOneAtATimeAndReportsEveryAck() = runTest {
        val core = ScriptedCore(mutableMapOf(
            "start" to ArrayDeque(listOf(FlashStep(listOf(FlashAction.SubscribeFwMap, write(WriteTarget.De1RequestedState, 0)), status(FlashPhase.Preparing)))),
            "ack" to ArrayDeque(listOf(
                FlashStep(listOf(write(WriteTarget.De1MmrWrite, 16, 0, 0, 0)), status(FlashPhase.Uploading)),
                FlashStep(listOf(write(WriteTarget.De1MmrWrite, 16, 0, 0, 16)), status(FlashPhase.Uploading)),
                FlashStep(emptyList(), status(FlashPhase.Verifying)),
            )),
        ))
        val io = FakeIo()
        val statuses = mutableListOf<FlashStatus?>()
        val keep = mutableListOf<Boolean>()
        assertNull(driver(core, io, statuses, keep).start(ByteArray(32), ok))
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf(listOf(0), listOf(16, 0, 0, 0), listOf(16, 0, 0, 16)), io.writes)
        assertEquals(1, io.maxInFlight)
        assertEquals(3, core.calls.count { it == "ack" })
        assertTrue(io.exclusive.last())
        assertTrue(keep.last())
    }

    @Test
    fun aFailedWriteIsReportedAndTheQueueDropped() = runTest {
        val core = ScriptedCore(mutableMapOf(
            "start" to ArrayDeque(listOf(FlashStep(listOf(write(WriteTarget.De1MmrWrite, 1), write(WriteTarget.De1MmrWrite, 2)), status(FlashPhase.Uploading)))),
            "failed" to ArrayDeque(listOf(FlashStep(emptyList(), status(FlashPhase.Failed, locks = false)))),
        ))
        val io = FakeIo(failWrites = true)
        val statuses = mutableListOf<FlashStatus?>()
        driver(core, io, statuses, mutableListOf()).start(ByteArray(32), ok)
        advanceTimeBy(100); runCurrent()
        assertEquals(1, core.failMessages.size)
        assertTrue("GATT 133" in core.failMessages[0])
        assertEquals(FlashPhase.Failed, statuses.last()?.phase)
        assertEquals(false, io.exclusive.last())
    }

    @Test
    fun pollReadsAndFailuresAndTicksReachTheCore() = runTest {
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
    fun aRefusedStartReturnsTheCoreError() = runTest {
        val err = FlashStartError.Blocked(FlashStartErrorBlockedInner(listOf(FlashBlocker.LowPower)))
        val core = ScriptedCore(mutableMapOf("start" to ArrayDeque(listOf(FlashStep(emptyList(), null, err)))))
        val io = FakeIo()
        assertEquals(err, driver(core, io, mutableListOf(), mutableListOf()).start(ByteArray(1), ok))
        advanceTimeBy(TICK_MS * 2); runCurrent()
        assertTrue("tick" !in core.calls, "no clock without a flash")
        assertTrue(io.writes.isEmpty())
    }
}
