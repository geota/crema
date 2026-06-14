package coffee.crema.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure-JVM tests for the History summary metrics (issue 48). */
class HistoryStatsTest {
    private fun shot(yieldG: Float? = null, doseG: Float? = null, durMs: Long = 0, rating: Int? = null) =
        StoredShot(id = "x", completedAtMs = 0, durationMs = durMs, yieldG = yieldG, doseG = doseG, rating = rating)

    @Test
    fun `aggregates only over present fields`() {
        val s = historyStats(
            listOf(
                shot(yieldG = 36f, doseG = 18f, durMs = 28_000, rating = 5),
                shot(yieldG = 40f, doseG = 20f, durMs = 30_000, rating = 3),
                shot(durMs = 25_000), // no yield / dose / rating
            ),
        )
        assertEquals(3, s.shots)
        assertEquals(76.0, s.totalWeightG!!, 1e-6)        // 36 + 40
        assertEquals(38.0, s.avgWeightG!!, 1e-6)          // (36 + 40) / 2
        assertEquals(2.0, s.avgRatio!!, 1e-6)             // (36/18 + 40/20) / 2
        assertEquals(83.0 / 3.0, s.avgTimeS!!, 1e-6)      // (28 + 30 + 25) / 3
        assertEquals(4.0, s.avgRating!!, 1e-6)            // (5 + 3) / 2, unrated excluded
    }

    @Test
    fun `a zero rating is treated as unrated`() {
        val s = historyStats(listOf(shot(rating = 0), shot(rating = 4)))
        assertEquals(4.0, s.avgRating!!, 1e-6)
    }

    @Test
    fun `empty list is zero shots and null aggregates`() {
        val s = historyStats(emptyList())
        assertEquals(0, s.shots)
        assertNull(s.totalWeightG)
        assertNull(s.avgWeightG)
        assertNull(s.avgRatio)
        assertNull(s.avgTimeS)
        assertNull(s.avgRating)
    }
}
