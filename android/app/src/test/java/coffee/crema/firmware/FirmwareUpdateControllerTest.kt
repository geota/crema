package coffee.crema.firmware

import coffee.crema.core.DecentFirmwareChannel
import coffee.crema.core.FirmwareArtifact
import coffee.crema.core.FirmwareImageCheck
import coffee.crema.core.FirmwareImageError
import coffee.crema.core.FirmwareImageErrorNotInManifestInner
import coffee.crema.core.FlashBlocker
import coffee.crema.core.FlashFailure
import coffee.crema.core.FlashFailureVerifyFailedInner
import coffee.crema.core.FlashPhase
import coffee.crema.core.FlashShellConditions
import coffee.crema.core.FlashStatus
import coffee.crema.core.FlashStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The flow's gating (web `firmware-update.vitest.ts` parity) + its copy. */
@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareUpdateControllerTest {
    private fun artifact(build: UInt, channel: DecentFirmwareChannel) = FirmwareArtifact(
        id = "de1-$build", build = build, channel = channel, channels = listOf(channel),
        urls = listOf("https://fast.decentespresso.com/x/$build"), byteLength = 16u, sha256 = "a".repeat(64),
        boardMarker = 0xDE100001u, bodyByteCount = 1u, cpuByteCount = 1u, chunkCount = 1u,
        releaseNotes = "", provenance = "",
    )
    private val stable = artifact(1333u, DecentFirmwareChannel.Stable)
    private val nightly = artifact(1358u, DecentFirmwareChannel.Nightly)

    private fun status(
        phase: FlashPhase,
        canCancel: Boolean = true,
        locks: Boolean = true,
        canExit: Boolean = false,
        eraseSettling: Boolean = false,
    ) = FlashStatus(
        phase = phase, artifact = nightly, targetBuild = 1358u, chunksTotal = 10u, chunksAcked = 3u,
        percent = 30f, etaIsEstimate = false, elapsedSeconds = 60u, canCancel = canCancel, locksWrites = locks,
        eraseSettling = eraseSettling, canExit = canExit, verifyUnconfirmed = false, machineRestarted = false,
    )

    /** A core whose status / cancel / restore answers are set per test. */
    private class StubCore(
        val blockerList: List<FlashBlocker> = emptyList(),
        var current: FlashStatus? = null,
        val cancelTo: FlashStatus? = null,
    ) : FlashCorePort {
        var starts = 0
        var cancels = 0
        var exits = 0
        val restores = mutableListOf<Pair<UInt, Boolean>>()
        private fun now() = FlashStep(emptyList(), current)
        override fun blockers(conditions: FlashShellConditions) = blockerList
        override fun start(image: ByteArray, conditions: FlashShellConditions, nowMs: Long): FlashStep { starts++; return now() }
        override fun onWriteAck(nowMs: Long) = now()
        override fun onWriteFailed(message: String, nowMs: Long) = now()
        override fun onFwMap(data: ByteArray, nowMs: Long) = now()
        override fun onReadFailed(nowMs: Long) = now()
        override fun tick(nowMs: Long) = now()
        override fun linkRestored(nowMs: Long) = now()
        override fun cancel(nowMs: Long): FlashStep {
            cancels++
            current = cancelTo
            return now()
        }
        override fun dismiss(): Boolean = (current?.locksWrites != true).also { if (it) current = null }
        override fun exit(): Boolean {
            exits++
            current = null
            return true
        }
        override fun status(nowMs: Long) = now()
        override fun restoreAwaiting(build: UInt, unconfirmed: Boolean, nowMs: Long): FlashStep {
            restores += build to unconfirmed
            return now()
        }
    }

    private object NoIo : FlashIoPort {
        override suspend fun write(target: coffee.crema.core.WriteTarget, data: ByteArray) = Unit
        override suspend fun readFwMap() = ByteArray(7)
        override suspend fun subscribeFwMap(onFrame: (ByteArray) -> Unit) = Unit
        override suspend fun awaitQuiet() = true
        override fun setExclusive(on: Boolean) = Unit
    }

    private class MemoryMarkers(var value: Pair<UInt, Boolean>? = null) : AwaitingMarkerStore {
        override fun read() = value
        override fun write(build: UInt, unconfirmed: Boolean) { value = build to unconfirmed }
        override fun clear() { value = null }
    }

    private fun TestScope.controller(
        installed: UInt? = 1333u,
        core: StubCore = StubCore(),
        fetcher: FirmwareFetcher = FirmwareFetcher { _, _, _ -> error("unreachable") },
        check: FirmwareImageCheck = FirmwareImageCheck(ok = false, byteLength = 0u, sha256 = "", error = FirmwareImageError.BengleImage),
        markers: AwaitingMarkerStore? = null,
        onRelease: () -> Unit = {},
    ) = FirmwareUpdateController(
        scope = this,
        core = core,
        catalog = object : FirmwareCatalog {
            override fun manifest() = listOf(stable, nightly)
            override fun validate(image: ByteArray) = check
        },
        fetcher = fetcher,
        conditions = { FlashShellConditions(true, true, false, true) },
        installedBuild = { installed },
        markers = markers,
        onRelease = onRelease,
        driverFactory = { onStatus -> FirmwareFlashDriver(core, NoIo, backgroundScope, { 0L }, onStatus = onStatus) },
    )

    @Test
    fun opensOnTheAlphaWarningAndNeedsIUnderstand() = runTest {
        val c = controller()
        c.open(); runCurrent()
        assertEquals(FlowStep.Warning, c.ui.value.step)
        c.acceptWarning()
        assertEquals(FlowStep.Warning, c.ui.value.step)
        c.setAcknowledged(true)
        c.acceptWarning()
        assertEquals(FlowStep.Choose, c.ui.value.step)
    }

    @Test
    fun defaultsToTheNewestBuildAboveTheInstalledOne() = runTest {
        val a = controller(installed = 1333u); a.open(); runCurrent()
        assertEquals(1358u, a.ui.value.selected?.build)
        val b = controller(installed = 1358u); b.open(); runCurrent()
        assertEquals(1358u, b.ui.value.selected?.build)
    }

    @Test
    fun aFailedOrUnverifiedDownloadNeverReachesConfirm() = runTest {
        val offline = controller(fetcher = { _, _, _ -> error("unreachable host") })
        offline.open(); runCurrent()
        offline.download(); runCurrent()
        assertEquals(FlowStep.Source, offline.ui.value.step)
        assertContains(offline.ui.value.sourceProblem ?: "", "Couldn’t download")
        assertFalse(offline.ui.value.imageReady)

        val unpinned = controller(
            fetcher = { _, _, _ -> ByteArray(16) },
            check = FirmwareImageCheck(ok = false, byteLength = 16u, sha256 = "b", error = FirmwareImageError.NotInManifest(FirmwareImageErrorNotInManifestInner(1360u, "b"))),
        )
        unpinned.open(); runCurrent()
        unpinned.download(); runCurrent()
        assertEquals(FlowStep.Source, unpinned.ui.value.step)
        assertContains(unpinned.ui.value.sourceProblem ?: "", "build 1360")

        val huge = controller(fetcher = { _, _, _ -> ByteArray(64) })
        huge.open(); runCurrent()
        huge.download(); runCurrent()
        assertContains(huge.ui.value.sourceProblem ?: "", "far too large")
    }

    @Test
    fun backDuringADownloadWinsOverTheLateResult() = runTest {
        val gate = CompletableDeferred<ByteArray>()
        val c = controller(
            fetcher = { _, _, _ -> gate.await() },
            check = FirmwareImageCheck(ok = true, artifact = nightly, byteLength = 16u, sha256 = "a"),
        )
        c.open(); runCurrent()
        c.download(); runCurrent()
        c.back()
        gate.complete(ByteArray(16))
        runCurrent()
        assertEquals(FlowStep.Choose, c.ui.value.step)
        assertNull(c.ui.value.sourceProblem)
    }

    @Test
    fun aVerifiedDownloadGoesToConfirmAndBlockersStopTheStart() = runTest {
        val core = StubCore(listOf(FlashBlocker.LowPower))
        val c = controller(
            core = core,
            fetcher = { _, _, _ -> ByteArray(16) },
            check = FirmwareImageCheck(ok = true, artifact = nightly, byteLength = 16u, sha256 = "a"),
        )
        c.open(); runCurrent()
        c.download(); runCurrent()
        assertEquals(FlowStep.Confirm, c.ui.value.step)
        assertEquals(listOf<FlashBlocker>(FlashBlocker.LowPower), c.ui.value.blockers)
        c.start(); runCurrent()
        assertEquals(FlowStep.Confirm, c.ui.value.step)
        assertEquals(0, core.starts)
    }

    @Test
    fun cancelAsksFirstKeepUpdatingBacksOutStopCancelsAndReleases() = runTest {
        val core = StubCore(current = status(FlashPhase.Uploading), cancelTo = status(FlashPhase.Cancelled, locks = false))
        var released = 0
        val c = controller(core = core, onRelease = { released++ })
        c.open(); runCurrent()
        assertEquals(FlowStep.Flash, c.ui.value.step)
        assertTrue(c.ui.value.running)
        c.close(); runCurrent()
        assertEquals(FlowStep.Flash, c.ui.value.step, "no closing while it runs")
        c.requestCancel()
        assertTrue(c.ui.value.confirmingCancel)
        c.keepUpdating()
        assertFalse(c.ui.value.confirmingCancel)
        assertEquals(0, core.cancels)
        c.requestCancel()
        c.confirmCancel(); runCurrent()
        assertEquals(1, core.cancels)
        assertEquals(FlashPhase.Cancelled, c.ui.value.status?.phase)
        assertEquals(1, released, "the shell hears when the flash lets go of the link")
    }

    @Test
    fun noCancelOnceVerifyStarted() = runTest {
        val c = controller(core = StubCore(current = status(FlashPhase.Verifying, canCancel = false)))
        c.open(); runCurrent()
        c.requestCancel()
        assertFalse(c.ui.value.confirmingCancel)
    }

    @Test
    fun thePowerCycleStepHidesPersistsAndExitAsksFirst() = runTest {
        val markers = MemoryMarkers()
        val core = StubCore(current = status(FlashPhase.AwaitingPowerCycle, canCancel = false, canExit = true))
        val c = controller(core = core, markers = markers)
        c.open(); runCurrent()
        assertTrue(c.ui.value.holdsLink)
        assertEquals(1358u to false, markers.value)
        c.close(); runCurrent()
        assertEquals(FlowStep.Closed, c.ui.value.step)
        assertEquals(FlashPhase.AwaitingPowerCycle, c.ui.value.status?.phase, "hidden, still held")
        c.open(); runCurrent()
        assertEquals(FlowStep.Flash, c.ui.value.step)
        c.requestExit()
        assertTrue(c.ui.value.confirmingExit)
        c.keepWaiting()
        assertFalse(c.ui.value.confirmingExit)
        assertEquals(0, core.exits)
        c.requestExit()
        c.confirmExit(); runCurrent()
        assertEquals(1, core.exits)
        assertEquals(FlowStep.Closed, c.ui.value.step)
        assertNull(markers.value)
    }

    @Test
    fun aPersistedPowerCycleMarkerIsRestoredIntoTheCore() = runTest {
        val core = StubCore(current = status(FlashPhase.AwaitingPowerCycle, canCancel = false, canExit = true))
        val c = controller(core = core, markers = MemoryMarkers(1358u to true))
        c.restore(); runCurrent()
        assertEquals(listOf(1358u to true), core.restores)
        assertTrue(c.ui.value.holdsLink)
    }

    @Test
    fun retryWaitsForAnInterruptedEraseToSettle() = runTest {
        val core = StubCore(current = status(FlashPhase.Failed, eraseSettling = true, locks = true))
        val c = controller(core = core)
        c.open(); runCurrent()
        c.retry(); runCurrent()
        assertEquals(FlowStep.Flash, c.ui.value.step)
        c.close(); runCurrent()
        assertEquals(FlashPhase.Failed, c.ui.value.status?.phase, "hiding keeps the held flash")
    }

    @Test
    fun copyCoversFailuresBlockersCancelAndPower() {
        assertEquals(
            "The DE1 found a damaged block at 0x004800 and kept its current firmware.",
            failureText(FlashFailure.VerifyFailed(FlashFailureVerifyFailedInner(0x4800u))),
        )
        assertContains(blockerText(FlashBlocker.UnsupportedModel(coffee.crema.core.FlashBlockerUnsupportedModelInner(128u))), "Bengle")
        assertContains(cancelQuestion(1333u), "build 1333")
        assertTrue(powerOk(charging = true, levelPercent = 10))
        assertTrue(powerOk(charging = false, levelPercent = 50))
        assertFalse(powerOk(charging = false, levelPercent = 49))
        assertTrue(powerOk(charging = false, levelPercent = null))
        assertNotNull(ALPHA_POINTS.firstOrNull { "alpha" in it })
    }
}
