package coffee.crema.ui

import coffee.crema.core.DecentFirmwareChannel
import coffee.crema.core.FirmwareBuildReport
import coffee.crema.core.FirmwareBuildStatus
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Settings firmware card copy (web `build-copy.ts` parity). The status
 * comes from the core classifier; these reports mirror what it returns for
 * each build against the 2026-10-11 table (stable/beta 1333, nightly 1358).
 */
class FirmwareBuildCopyTest {
    private fun report(
        status: FirmwareBuildStatus,
        installed: Int?,
        channels: List<DecentFirmwareChannel> = emptyList(),
    ) = FirmwareBuildReport(
        status = status,
        installed = installed?.toUInt(),
        newestBuild = 1358u,
        stableBuild = 1333u,
        betaBuild = 1333u,
        nightlyBuild = 1358u,
        channels = channels,
        tableRevision = 1u,
        tableVerified = "2026-10-11",
    )

    @Test
    fun build1358IsTheNewestReleaseNoUpdateOffered() {
        val c = firmwareCardCopy(report(FirmwareBuildStatus.Newest, 1358, listOf(DecentFirmwareChannel.Nightly)), connected = true)
        assertEquals("Build 1358 · newest Decent release", c.title)
        assertEquals("Decent has no newer build. Its stable channel is on build 1333.", c.note)
        assertFalse(c.offerUpdate)
    }

    @Test
    fun build1333IsDecentStableNotUpToDateAndOffers1358() {
        val c = firmwareCardCopy(report(FirmwareBuildStatus.Stable, 1333), connected = true)
        assertEquals("Build 1333 · Decent stable", c.title)
        assertEquals("Newer: build 1358 (nightly).", c.note)
        assertTrue(c.offerUpdate)
        assertFalse("up to date" in (c.title + c.note).lowercase())
    }

    @Test
    fun noCopySaysCremaCannotInstallFirmware() {
        for (s in FirmwareBuildStatus.entries) {
            val c = firmwareCardCopy(report(s, 1333), connected = true)
            assertFalse("can’t install" in c.title + c.note)
        }
    }

    @Test
    fun betweenStableAndNewest() {
        val c = firmwareCardCopy(report(FirmwareBuildStatus.BetweenStableAndNewest, 1352), connected = true)
        assertEquals("Build 1352 · newer than Decent stable", c.title)
        assertTrue(c.offerUpdate)
    }

    @Test
    fun anOldBuild() {
        val c = firmwareCardCopy(report(FirmwareBuildStatus.OlderThanStable, 1320), connected = true)
        assertEquals("Build 1320 · older than Decent stable", c.title)
        assertContains(c.note, "1333")
    }

    @Test
    fun build1363IsRolledBackNotNewer() {
        val c = firmwareCardCopy(report(FirmwareBuildStatus.RolledBack, 1363), connected = true)
        assertEquals("Build 1363 · rolled-back build", c.title)
        assertContains(c.note, "isn’t newer than build 1358")
        assertTrue(c.offerUpdate)
    }

    @Test
    fun aBuildAboveTheNewestKnown() {
        val c = firmwareCardCopy(report(FirmwareBuildStatus.NewerThanKnown, 1370), connected = true)
        assertEquals("Build 1370 · newer than Crema knows about", c.title)
        assertContains(c.note, "2026-10-11")
        assertFalse(c.offerUpdate)
    }

    @Test
    fun connectedBeforeTheBuildIsRead() {
        assertEquals("Build not read yet", firmwareCardCopy(report(FirmwareBuildStatus.Unknown, null), connected = true).title)
    }

    @Test
    fun disconnected() {
        val c = firmwareCardCopy(report(FirmwareBuildStatus.Newest, 1358), connected = false)
        assertEquals("No build info", c.title)
        assertFalse(c.offerUpdate)
    }
}
