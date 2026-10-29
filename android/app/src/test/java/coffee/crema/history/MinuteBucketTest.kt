package coffee.crema.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The History screens memoize filter + sort on a minute-bucketed `now`
 * (cohort review 2026-10-25, Android P2-4), so recompositions within a minute
 * reuse the sorted list instead of re-sorting the whole log per telemetry
 * update. A raw `System.currentTimeMillis()` key would bust the cache every time.
 */
class MinuteBucketTest {
    @Test
    fun sameMinuteSameKey() {
        val t = 1_700_000_040_000L // on a minute boundary
        assertEquals(t, minuteBucket(t))
        assertEquals(t, minuteBucket(t + 59_999))
        assertNotEquals(t, minuteBucket(t + 60_000))
    }

    @Test
    fun bucketedNowFiltersLikeTheRawNowAwayFromTheEdges() {
        val now = 1_700_000_040_000L + 30_000
        val shots = listOf(
            StoredShot(id = "old", completedAtMs = now - 8L * 24 * 3600 * 1000, durationMs = 30_000),
            StoredShot(id = "new", completedAtMs = now - 3_600_000, durationMs = 30_000),
        )
        val raw = filterAndSortShots(shots, "", "7d", null, "date", true, now)
        val bucketed = filterAndSortShots(shots, "", "7d", null, "date", true, minuteBucket(now))
        assertEquals(raw.map { it.id }, bucketed.map { it.id })
        assertEquals(listOf("new"), bucketed.map { it.id })
    }
}
