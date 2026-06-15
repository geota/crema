package coffee.crema.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the compact-relative "time ago" thresholds (issue 43) against a fixed
 * clock. These must stay byte-identical to the web `relativeLastUsed`
 * (`$lib/profiles/model`) so a shot reads the same on every shell.
 */
class RelativeAgoTest {
    private val now = 10_000_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun ago(deltaMs: Long) = relativeAgo(now - deltaMs, now)

    @Test
    fun `under a minute reads just now`() {
        assertEquals("just now", ago(0L))
        assertEquals("just now", ago(59 * minute / 60)) // 59s
    }

    @Test
    fun minutes() {
        assertEquals("1m ago", ago(minute))
        assertEquals("59m ago", ago(59 * minute))
    }

    @Test
    fun hours() {
        assertEquals("1h ago", ago(hour))
        assertEquals("23h ago", ago(23 * hour))
    }

    @Test
    fun days() {
        assertEquals("1d ago", ago(day))
        assertEquals("6d ago", ago(6 * day))
    }

    @Test
    fun `weeks up to the 5-week cap`() {
        assertEquals("1w ago", ago(7 * day))
        assertEquals("4w ago", ago(28 * day))
    }

    @Test
    fun `months take over past the week cap`() {
        assertEquals("1mo ago", ago(35 * day)) // 5w → months = days/30 = 1
        assertEquals("11mo ago", ago(330 * day))
    }

    @Test
    fun years() {
        assertEquals("1y ago", ago(365 * day))
        assertEquals("2y ago", ago(730 * day))
    }

    @Test
    fun `a future timestamp clamps to just now`() {
        assertEquals("just now", relativeAgo(now + minute, now))
    }
}
