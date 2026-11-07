package coffee.crema.history

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
        assertEquals(listOf("new", "mid", "old"), historyWith(listOf(shot("mid", 20)), history).map { it.id })
        assertEquals(listOf("tie", "new", "old"), historyWith(listOf(shot("tie", 30)), history).map { it.id })
    }

    @Test fun pull_never_evicts_at_cap() {
        val brew = shot("brew-log", 1)
        val full = List(HistoryStore.MAX_SHOTS - 1) { i -> shot("r$i", 1_000_000L - i) } + brew
        val merge = mergePulled(listOf(shot("new", 2_000_000)), full)
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
        val rows = List(HistoryStore.MAX_SHOTS - 2) { i -> shot("r$i", 1_000_000L - i) }
        val merge = mergePulled(listOf(shot("a", 5), shot("b", 2_000_000), shot("c", 6)), rows)
        assertEquals(listOf("a", "b"), merge.kept.map { it.id })
        assertEquals(HistoryStore.MAX_SHOTS, merge.rows.size)
        assertEquals("b", merge.rows.first().id)
        assertEquals("a", merge.rows.last().id)
        assertTrue(rows.all { it in merge.rows })
    }

    @Test fun aListOverTheCapStaysAsItIsThroughAPull() {
        val over = List(HistoryStore.MAX_SHOTS + 5) { i -> shot("r$i", 1_000_000L - i) }
        assertEquals(over, mergePulled(listOf(shot("new", 2_000_000)), over).rows)
    }

    @Test fun aPulledRowAlreadyHereIsLeftOut() {
        val rows = listOf(shot("a", 2))
        assertEquals(rows, mergePulled(listOf(shot("a", 9)), rows).rows)
    }

    @Test fun theCapDropsTheOldestNotTheNewest() {
        val full = List(HistoryStore.MAX_SHOTS) { i -> shot("r$i", 1_000_000L - i) }
        val next = historyWith(listOf(shot("mid", 999_950)), full)
        assertEquals(HistoryStore.MAX_SHOTS, next.size)
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
        assertFalse(fitsHistory(rows, 1_000_000L - 3_195))
        assertTrue(fitsHistory(rows, 1_000_000L - 2_990))
    }

    @Test fun aRowJustMadeStaysWhenTheClockIsBehindAFullHistory() {
        val full = List(HistoryStore.MAX_SHOTS) { i -> shot("r$i", 1_000_000L - i) }
        val made = shot("made", 10)
        val next = historyWith(listOf(made), full, keepId = made.id)
        assertEquals(HistoryStore.MAX_SHOTS, next.size)
        assertEquals("made", next.last().id)
        assertFalse(next.any { it.id == full.last().id }, "the oldest other row goes")
    }

    @Test fun anImportedRowOlderThanAFullHistoryIsTheOneThatGoes() {
        val full = List(HistoryStore.MAX_SHOTS) { i -> shot("r$i", 1_000_000L - i) }
        val merged = mergeHistory(listOf(shot("old", 10)), full)
        assertEquals(full, merged.rows)
        assertEquals(emptyList(), merged.kept)
        assertEquals(1, merged.dropped)
    }

    @Test fun aMergeReportsTheRowsKeptAndAnyRowsDropped() {
        val nearlyFull = List(HistoryStore.MAX_SHOTS - 1) { i -> shot("r$i", 1_000_000L - i) }
        val merged = mergeHistory(listOf(shot("new", 2_000_000), shot("next", 2_000_001)), nearlyFull)
        assertEquals(listOf("next", "new"), merged.kept.map { it.id })
        assertEquals(1, merged.dropped, "an existing row went")
        assertEquals(HistoryStore.MAX_SHOTS, merged.rows.size)
    }

    @Test fun nothingNewLeavesAListOverTheCapAsItIs() {
        val rows = List(350) { i -> shot("r$i", 1_000_000L - i) }
        assertEquals(350, mergeHistory(emptyList(), rows).rows.size)
        assertEquals(0, mergeHistory(emptyList(), rows).dropped)
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
