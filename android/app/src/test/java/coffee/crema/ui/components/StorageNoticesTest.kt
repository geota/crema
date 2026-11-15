package coffee.crema.ui.components

import coffee.crema.history.FreeUpPlan
import coffee.crema.history.StorageLevel
import coffee.crema.history.StorageSpace
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The storage notices' levels and words, and Free up space's preview (#310 P6a). */
class StorageNoticesTest {
    @Test fun theLevelsFollowTheSpec() {
        assertEquals(StorageLevel.OK, StorageSpace.levelOf(500 * StorageSpace.MB))
        assertEquals(StorageLevel.GETTING_FULL, StorageSpace.levelOf(500 * StorageSpace.MB - 1))
        assertEquals(StorageLevel.GETTING_FULL, StorageSpace.levelOf(100 * StorageSpace.MB))
        assertEquals(StorageLevel.NEARLY_FULL, StorageSpace.levelOf(100 * StorageSpace.MB - 1))
        assertEquals(StorageLevel.NEARLY_FULL, StorageSpace.levelOf(0))
    }

    @Test fun gettingFullIsOneNoticeAndNearlyFullLasts() {
        assertNull(storageBannerText(StorageLevel.OK, dismissed = false))
        assertNotNull(storageBannerText(StorageLevel.GETTING_FULL, dismissed = false))
        assertNull(storageBannerText(StorageLevel.GETTING_FULL, dismissed = true))
        assertNotNull(storageBannerText(StorageLevel.NEARLY_FULL, dismissed = true))
        assertTrue("never deletes" in storageBannerText(StorageLevel.NEARLY_FULL, dismissed = false)!!)
    }

    @Test fun theCutoffIsTheStartOfTheDayThatManyMonthsBack() {
        val zone = ZoneId.of("America/Los_Angeles")
        val cut = freeUpCutoff(12, LocalDate.of(2026, 11, 15), zone)
        assertEquals(LocalDate.of(2025, 11, 15).atStartOfDay(zone).toInstant().toEpochMilli(), cut)
    }

    @Test fun thePreviewSaysWhatGoesWhatStaysAndThatBagsDontChange() {
        val plan = FreeUpPlan(1_700_000_000_000, listOf("a", "b", "c"), pinnedKept = 2, bytes = 3 * StorageSpace.MB)
        val body = freeUpBody(plan, counting = false, beforeMs = plan.beforeMs)
        assertTrue(body.startsWith("Deletes 3 shots from before "), body)
        assertTrue("3.0 MB" in body, body)
        assertTrue("2 pinned shots from then are kept" in body, body)
        assertTrue("Bag weights don't change" in body, body)
        assertTrue("back up first" in body, body)
        assertTrue(freeUpBody(plan.copy(ids = emptyList()), false, plan.beforeMs).startsWith("No unpinned shots"))
        assertTrue("fully read" in freeUpBody(null, counting = false, beforeMs = plan.beforeMs))
        assertTrue(freeUpBody(null, counting = true, beforeMs = plan.beforeMs).startsWith("Counting"))
    }
}
