package coffee.crema.firmware

import coffee.crema.core.DecentFirmwareChannel
import coffee.crema.core.FirmwareArtifact
import coffee.crema.core.FirmwareImageCheck
import coffee.crema.core.FirmwareImageError
import coffee.crema.core.FlashBlocker
import coffee.crema.core.FlashFailure
import coffee.crema.core.FlashFailureVerifyFailedInner
import coffee.crema.core.FlashShellConditions
import coffee.crema.core.FlashStep
import coffee.crema.core.FirmwareImageErrorNotInManifestInner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    private class NullCore(val blockerList: List<FlashBlocker> = emptyList()) : FlashCorePort {
        var starts = 0
        override fun blockers(conditions: FlashShellConditions) = blockerList
        override fun start(image: ByteArray, conditions: FlashShellConditions, nowMs: Long): FlashStep { starts++; return FlashStep(emptyList()) }
        override fun onWriteAck(nowMs: Long) = FlashStep(emptyList())
        override fun onWriteFailed(message: String, nowMs: Long) = FlashStep(emptyList())
        override fun onFwMap(data: ByteArray, nowMs: Long) = FlashStep(emptyList())
        override fun onReadFailed(nowMs: Long) = FlashStep(emptyList())
        override fun tick(nowMs: Long) = FlashStep(emptyList())
        override fun linkRestored(nowMs: Long) = FlashStep(emptyList())
        override fun cancel(nowMs: Long) = FlashStep(emptyList())
        override fun dismiss() = true
        override fun status(nowMs: Long) = FlashStep(emptyList())
    }

    private object NoIo : FlashIoPort {
        override suspend fun write(target: coffee.crema.core.WriteTarget, data: ByteArray) = Unit
        override suspend fun readFwMap() = ByteArray(7)
        override suspend fun subscribeFwMap(onFrame: (ByteArray) -> Unit) = Unit
        override suspend fun readBuild() = Unit
        override fun setExclusive(on: Boolean) = Unit
    }

    private fun TestScope.controller(
        installed: UInt? = 1333u,
        core: NullCore = NullCore(),
        fetcher: FirmwareFetcher = FirmwareFetcher { _, _, _ -> error("unreachable") },
        check: FirmwareImageCheck = FirmwareImageCheck(ok = false, byteLength = 0u, sha256 = "", error = FirmwareImageError.BengleImage),
    ): FirmwareUpdateController {
        val driver = FirmwareFlashDriver(core, NoIo, backgroundScope, { 0L }, onStatus = {})
        return FirmwareUpdateController(
            scope = this,
            driver = driver,
            core = core,
            catalog = object : FirmwareCatalog {
                override fun manifest() = listOf(stable, nightly)
                override fun validate(image: ByteArray) = check
            },
            fetcher = fetcher,
            conditions = { FlashShellConditions(true, true, false, true) },
            installedBuild = { installed },
        )
    }

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
    }

    @Test
    fun aVerifiedDownloadGoesToConfirmAndBlockersStopTheStart() = runTest {
        val core = NullCore(listOf(FlashBlocker.LowPower))
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
    fun copyCoversFailuresBlockersAndPower() {
        assertEquals(
            "The DE1 found a damaged block at 0x004800 and kept its current firmware.",
            failureText(FlashFailure.VerifyFailed(FlashFailureVerifyFailedInner(0x4800u))),
        )
        assertContains(blockerText(FlashBlocker.UnsupportedModel(coffee.crema.core.FlashBlockerUnsupportedModelInner(128u))), "Bengle")
        assertTrue(powerOk(charging = true, levelPercent = 10))
        assertTrue(powerOk(charging = false, levelPercent = 50))
        assertFalse(powerOk(charging = false, levelPercent = 49))
        assertTrue(powerOk(charging = false, levelPercent = null))
        assertNotNull(ALPHA_POINTS.firstOrNull { "alpha" in it })
    }
}
