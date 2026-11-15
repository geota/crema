package coffee.crema.history

import coffee.crema.ui.rowsForTest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A logged brew's date (#300): future dates are refused, and the history stays
 * newest first however a row was dated. Mirrors the web `brew-date.vitest.ts`.
 */
class BrewDateTest {
    private val zone = ZoneId.of("America/New_York")
    private fun shot(id: String, at: Long) = StoredShot(id = id, completedAtMs = at, durationMs = 1_000, brewMethod = "pourover")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test fun nowAndThePastAreFineAndTheFutureIsNot() {
        val now = 1_700_000_000_000
        assertNull(brewDateError(now, now))
        assertNull(brewDateError(now - 1, now))
        assertEquals(BREW_DATE_FUTURE, brewDateError(now + 60_000, now))
    }

    @Test fun aDateAStoredRowCantHoldIsRefused() {
        assertNotNull(brewDateError(0, 1_000))
        assertNotNull(brewDateError(-1, 1_000))
    }

    @Test fun rowsWithTheSameDateKeepTheirOrder() {
        val rows = listOf(shot("a", 1), shot("b", 2), shot("c", 1))
        assertEquals(listOf("b", "a", "c"), rows.newestFirst().map { it.id })
    }

    @Test fun aBackdatedRowGoesToItsPlaceAndANewOneLeadsItsTie() {
        val history = listOf(shot("new", 30), shot("old", 10))
        assertEquals(listOf("new", "mid", "old"), historyWith(listOf(shot("mid", 20)).rows(), rowsForTest(history.rows()), cap = HistoryStore.LEGACY_MAX_SHOTS).map { it.id })
        assertEquals(listOf("tie", "new", "old"), historyWith(listOf(shot("tie", 30)).rows(), rowsForTest(history.rows()), cap = HistoryStore.LEGACY_MAX_SHOTS).map { it.id })
    }

    @Test fun pull_never_evicts_at_cap() {
        val brew = shot("brew-log", 1)
        val full = List(HistoryStore.LEGACY_MAX_SHOTS - 1) { i -> shot("r$i", 1_000_000L - i) } + brew
        val merge = mergePulled(listOf(shot("new", 2_000_000)), full, cap = HistoryStore.LEGACY_MAX_SHOTS)
        assertEquals(full, merge.rows)
        assertEquals(emptyList(), merge.kept)
        assertEquals(0, merge.dropped)
    }

    @Test fun theFullNoticeNamesReSync() {
        assertEquals(
            "History is full at 300 shots, so 2 shots from Visualizer weren’t added. After freeing up space, use Re-sync shots to fetch them",
            historyFullNotice(2, 300),
        )
        assertTrue("to fetch it" in historyFullNotice(1, 300))
    }

    @Test fun aPullFillsTheFreeSlotsAndRefusesTheRest() {
        val rows = List(HistoryStore.LEGACY_MAX_SHOTS - 2) { i -> shot("r$i", 1_000_000L - i) }
        val merge = mergePulled(listOf(shot("a", 5), shot("b", 2_000_000), shot("c", 6)), rows, cap = HistoryStore.LEGACY_MAX_SHOTS)
        assertEquals(listOf("a", "b"), merge.kept.map { it.id })
        assertEquals(HistoryStore.LEGACY_MAX_SHOTS, merge.rows.size)
        assertEquals("b", merge.rows.first().id)
        assertEquals("a", merge.rows.last().id)
        assertTrue(rows.all { it in merge.rows })
    }

    @Test fun aListOverTheCapStaysAsItIsThroughAPull() {
        val over = List(HistoryStore.LEGACY_MAX_SHOTS + 5) { i -> shot("r$i", 1_000_000L - i) }
        assertEquals(over, mergePulled(listOf(shot("new", 2_000_000)), over, cap = HistoryStore.LEGACY_MAX_SHOTS).rows)
    }

    @Test fun aPulledRowAlreadyHereIsLeftOut() {
        val rows = listOf(shot("a", 2))
        assertEquals(rows, mergePulled(listOf(shot("a", 9)), rows, cap = HistoryStore.LEGACY_MAX_SHOTS).rows)
    }

    @Test fun theCapDropsTheOldestNotTheNewest() {
        val full = List(HistoryStore.LEGACY_MAX_SHOTS) { i -> shot("r$i", 1_000_000L - i) }
        val next = historyWith(listOf(shot("mid", 999_950)).rows(), rowsForTest(full.rows()), cap = HistoryStore.LEGACY_MAX_SHOTS)
        assertEquals(HistoryStore.LEGACY_MAX_SHOTS, next.size)
        assertEquals("r0", next.first().id)
        assertTrue(next.any { it.id == "mid" })
        assertFalse(next.any { it.id == full.last().id })
    }

    @Test fun onlyAFullHistoryLimitsHowOldABrewCanBe() {
        val rows = listOf(shot("a", 3), shot("b", 2))
        assertTrue(fitsHistory(rows, 1, cap = 3))
        assertTrue(fitsHistory(rows, 2, cap = 2))
        assertFalse(fitsHistory(rows, 1, cap = 2))
    }

    @Test fun changingTheDateKeepsTheTimeOfDay() {
        val moved = withLocalDate(at(2026, 11, 6, 7, 5), LocalDate.of(2026, 11, 2), zone)
        assertEquals(at(2026, 11, 2, 7, 5), moved)
    }

    @Test fun changingTheTimeKeepsTheDateAndDropsTheSeconds() {
        val moved = withLocalTime(at(2026, 11, 6, 7, 5) + 42_000, 18, 30, zone)
        assertEquals(at(2026, 11, 6, 18, 30), moved)
    }

    @Test fun aTimeInTheSpringGapMovesPastItRatherThanFailing() {
        // 02:30 doesn't exist on 2026-03-08 in New York.
        val moved = withLocalTime(at(2026, 3, 8, 12, 0), 2, 30, zone)
        assertEquals(at(2026, 3, 8, 3, 30), moved)
    }

    @Test fun theCheckIsAtTheCutNotAtTheEndOfAListOverTheCap() {
        val rows = List(350) { i -> shot("r$i", 1_000_000L - i * 10) }
        // Between ranks 320 and 321: newer than the last row, but cut at 300.
        assertFalse(fitsHistory(rows, 1_000_000L - 3_195, cap = HistoryStore.LEGACY_MAX_SHOTS))
        assertTrue(fitsHistory(rows, 1_000_000L - 2_990, cap = HistoryStore.LEGACY_MAX_SHOTS))
    }

    @Test fun aRowJustMadeStaysWhenTheClockIsBehindAFullHistory() {
        val full = List(HistoryStore.LEGACY_MAX_SHOTS) { i -> shot("r$i", 1_000_000L - i) }
        val made = shot("made", 10)
        val next = historyWith(listOf(made).rows(), rowsForTest(full.rows()), keepId = made.id, cap = HistoryStore.LEGACY_MAX_SHOTS)
        assertEquals(HistoryStore.LEGACY_MAX_SHOTS, next.size)
        assertEquals("made", next.last().id)
        assertFalse(next.any { it.id == full.last().id }, "the oldest other row goes")
    }

    /** #311: a full history whose oldest row is pinned, then a brew logged here. */
    @Test fun aPinnedRowStaysWhenABrewIsLoggedOnAFullHistory() {
        val full = List(HistoryStore.LEGACY_MAX_SHOTS) { i -> shot("r$i", 1_000_000L - i) }
            .mapIndexed { i, s -> if (i == HistoryStore.LEGACY_MAX_SHOTS - 1) s.copy(pinned = true) else s }
        val first = historyWith(listOf(shot("made", 2_000_000)).rows(), rowsForTest(full.rows()), keepId = "made", cap = HistoryStore.LEGACY_MAX_SHOTS)
        assertTrue(first.any { it.id == "r299" }, "the pinned row stays")
        assertEquals(HistoryStore.LEGACY_MAX_SHOTS + 1, first.size, "the pinned row doesn't count")
        val second = historyWith(listOf(shot("next", 2_000_001)).rows(), rowsForTest(first.toList()), keepId = "next", cap = HistoryStore.LEGACY_MAX_SHOTS)
        assertTrue(second.any { it.id == "r299" }, "the pinned row stays")
        assertFalse(second.any { it.id == "r298" }, "the oldest unpinned row goes instead")
    }

    @Test fun aPinnedRowStaysWhenAnImportOverfillsTheHistory() {
        val full = List(HistoryStore.LEGACY_MAX_SHOTS) { i -> shot("r$i", 1_000_000L - i) }
            .map { if (it.id == "r299") it.copy(pinned = true) else it }
        val merged = mergeHistory(listOf(shot("new", 2_000_000), shot("next", 2_000_001)), full, cap = HistoryStore.LEGACY_MAX_SHOTS)
        assertTrue(merged.rows.any { it.id == "r299" })
        assertEquals(1, merged.dropped)
    }

    @Test fun aNewerPinnedRowLeavesTheCutAtTheLastUnpinnedOneKept() {
        val full = List(HistoryStore.LEGACY_MAX_SHOTS) { i -> shot("r$i", 1_000_000L - i * 10) }
            .map { if (it.id == "r0") it.copy(pinned = true) else it }
        val filled = mergeHistory(listOf(shot("new", 2_000_000)), full, cap = HistoryStore.LEGACY_MAX_SHOTS).rows
        assertTrue(fitsHistory(filled, 1_000_000L - 2_990, cap = HistoryStore.LEGACY_MAX_SHOTS), "the 300th unpinned row is the cut")
        assertFalse(fitsHistory(filled, 1_000_000L - 2_991, cap = HistoryStore.LEGACY_MAX_SHOTS))
    }

    @Test fun aPinnedRowDoesntTakeRoomFromAPullOrADate() {
        val full = List(HistoryStore.LEGACY_MAX_SHOTS) { i -> shot("r$i", 1_000_000L - i * 10) }
            .map { if (it.id == "r299") it.copy(pinned = true) else it }
        assertEquals(1, mergePulled(listOf(shot("a", 2_000_000), shot("b", 2_000_001)), full, cap = HistoryStore.LEGACY_MAX_SHOTS).kept.size)
        val filled = mergeHistory(listOf(shot("new", 2_000_000)), full, cap = HistoryStore.LEGACY_MAX_SHOTS).rows
        assertTrue(fitsHistory(filled, 1_000_000L - 2_980, cap = HistoryStore.LEGACY_MAX_SHOTS), "the oldest unpinned row is the cut")
        assertFalse(fitsHistory(filled, 1_000_000L - 2_990, cap = HistoryStore.LEGACY_MAX_SHOTS), "not the pinned row behind it")
    }

    @Test fun anImportedRowOlderThanAFullHistoryIsTheOneThatGoes() {
        val full = List(HistoryStore.LEGACY_MAX_SHOTS) { i -> shot("r$i", 1_000_000L - i) }
        val merged = mergeHistory(listOf(shot("old", 10)), full, cap = HistoryStore.LEGACY_MAX_SHOTS)
        assertEquals(full, merged.rows)
        assertEquals(emptyList(), merged.kept)
        assertEquals(1, merged.dropped)
    }

    @Test fun aMergeReportsTheRowsKeptAndAnyRowsDropped() {
        val nearlyFull = List(HistoryStore.LEGACY_MAX_SHOTS - 1) { i -> shot("r$i", 1_000_000L - i) }
        val merged = mergeHistory(listOf(shot("new", 2_000_000), shot("next", 2_000_001)), nearlyFull, cap = HistoryStore.LEGACY_MAX_SHOTS)
        assertEquals(listOf("next", "new"), merged.kept.map { it.id })
        assertEquals(1, merged.dropped, "an existing row went")
        assertEquals(HistoryStore.LEGACY_MAX_SHOTS, merged.rows.size)
    }

    @Test fun nothingNewLeavesAListOverTheCapAsItIs() {
        val rows = List(350) { i -> shot("r$i", 1_000_000L - i) }
        assertEquals(350, mergeHistory(emptyList(), rows, cap = HistoryStore.LEGACY_MAX_SHOTS).rows.size)
        assertEquals(0, mergeHistory(emptyList(), rows, cap = HistoryStore.LEGACY_MAX_SHOTS).dropped)
    }

    // ── No cap (#310 P6a): every store but the legacy backend ───────────────

    @Test fun withNoCapAnInsertKeepsEveryRowInOrder() {
        val many = List(HistoryStore.LEGACY_MAX_SHOTS * 3) { i -> shot("r$i", 1_000_000L - i) }
        val merged = mergeHistory(listOf(shot("old", 10), shot("new", 2_000_000)), many, cap = null)
        assertEquals(many.size + 2, merged.rows.size)
        assertEquals(0, merged.dropped)
        assertEquals(setOf("old", "new"), merged.kept.map { it.id }.toSet())
        assertEquals("new", merged.rows.first().id)
        assertEquals("old", merged.rows.last().id, "a backdated row goes to its place, not out")
        assertEquals(merged.rows.sortedByDescending { it.completedAtMs }, merged.rows)
    }

    @Test fun withNoCapAPullAddsEveryNewRow() {
        val many = List(HistoryStore.LEGACY_MAX_SHOTS) { i -> shot("r$i", 1_000_000L - i) }
        val merge = mergePulled(listOf(shot("a", 5), shot("r3", 9), shot("b", 2_000_000)), many, cap = null)
        assertEquals(listOf("a", "b"), merge.kept.map { it.id })
        assertEquals(many.size + 2, merge.rows.size)
    }

    @Test fun withNoCapAnyPastDateFits() {
        val many = List(HistoryStore.LEGACY_MAX_SHOTS * 2) { i -> shot("r$i", 1_000_000L - i) }
        assertTrue(fitsHistory(many, 1, cap = null))
    }

    @Test fun theSameDateOrTimeKeepsTheValueExactly() {
        val at = at(2026, 11, 6, 7, 5) + 42_123
        assertEquals(at, withLocalDate(at, LocalDate.of(2026, 11, 6), zone))
        assertEquals(at, withLocalTime(at, 7, 5, zone))
    }

    @Test fun anEditInTheRepeatedHourStaysOnTheSamePass() {
        val first = LocalDateTime.of(2026, 11, 1, 1, 30).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val second = first + 3_600_000
        assertEquals(second, withLocalDate(second, LocalDate.of(2026, 11, 1), zone), "OK on the same date")
        assertEquals(second + 600_000, withLocalTime(second, 1, 40, zone))
        assertEquals(first + 600_000, withLocalTime(first, 1, 40, zone))
    }
}
