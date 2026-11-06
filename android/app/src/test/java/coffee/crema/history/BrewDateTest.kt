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
}
