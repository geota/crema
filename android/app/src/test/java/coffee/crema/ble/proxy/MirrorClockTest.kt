package coffee.crema.ble.proxy

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/** #319: a mirror's notification times are on its own clock. */
class MirrorClockTest {
    private var local = 0L
    private val clock = MirrorClock { local }

    @Test
    fun `a primary up hours longer than the mirror lands on the mirror's clock, spacing kept`() {
        local = 1_000L
        val remote = 6 * 3_600_000L // the primary booted six hours earlier
        val out = (0 until 10).map { i ->
            local = 1_000L + i * 100L + 3 // 3 ms on the wire
            clock.rebase(remote + i * 100L)
        }
        assertEquals((0 until 10).map { 1_003L + it * 100L }, out)
    }

    @Test
    fun `a primary booted later than the mirror lands on the mirror's clock too`() {
        local = 9 * 3_600_000L
        assertEquals(local, clock.rebase(5_000L))
        local += 100
        assertEquals(local, clock.rebase(5_100L))
    }

    @Test
    fun `a stale snapshot first doesn't push live frames into the future`() {
        local = 50_000L
        // The attach snapshot replays a value cached 30 s ago on the primary.
        val snap = clock.rebase(10_000L)
        assertTrue(snap <= local)
        // Then the live stream, stamped now on the primary.
        val live = (1..5).map { i ->
            local = 50_000L + i * 100L
            clock.rebase(40_000L + i * 100L)
        }
        live.forEachIndexed { i, t -> assertTrue(t <= 50_000L + (i + 1) * 100L, "frame $i at $t is after it arrived") }
        assertEquals(listOf(100L, 100L, 100L, 100L), live.zipWithNext { a, b -> b - a }, "live spacing kept")
    }

    @Test
    fun `a faster frame lowers the offset, and time never runs back`() {
        local = 1_000L
        val slow = clock.rebase(500L) // 50 ms of wire delay folded into the offset
        local = 1_010L
        val fast = clock.rebase(560L) // this one took 0 ms: the offset drops by 50
        assertTrue(fast >= slow, "monotonic: $slow then $fast")
        local = 1_110L
        assertEquals(1_110L, clock.rebase(660L), "later frames use the lower offset")
    }

    @Test
    fun `after a reset a restarted primary's clock is learnt again`() {
        local = 100_000L
        clock.rebase(7_200_000L)
        clock.reset() // the primary restarted: its uptime starts from boot
        local = 100_500L
        assertEquals(100_500L, clock.rebase(2_000L))
        local = 100_600L
        assertEquals(100_600L, clock.rebase(2_100L))
    }
}
