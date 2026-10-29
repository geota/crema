package coffee.crema.ble

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The DE1 dead-link rule [De1BleManager]'s liveness loop runs once a second
 * (geota/crema#65; same rule as the web `De1Liveness`). Round-2 verification
 * V2 P3-3: Android did not pause it during a firmware flash, so a DE1 quiet
 * through the erase hold was torn down mid-flash about 10 s in.
 */
class De1LivenessCheckTest {

    /** A loop like [De1BleManager.livenessLoop]: one check per second until DEAD. */
    private suspend fun TestScope.runLoop(check: De1LivenessCheck, seconds: Int): List<De1LivenessCheck.Verdict> {
        val verdicts = mutableListOf<De1LivenessCheck.Verdict>()
        repeat(seconds) {
            advanceTimeBy(De1LivenessCheck.CHECK_INTERVAL_MS)
            val v = check.check()
            verdicts += v
            if (v == De1LivenessCheck.Verdict.DEAD) return verdicts
        }
        return verdicts
    }

    @Test
    fun `recent traffic never probes`() = runTest {
        var probes = 0
        val check = De1LivenessCheck(
            now = { testScheduler.currentTime },
            lastInboundMs = { testScheduler.currentTime }, // notifications keep arriving
            paused = { false },
            probe = { probes += 1 },
        )
        val verdicts = runLoop(check, 60)
        assertEquals(0, probes)
        assertEquals(setOf(De1LivenessCheck.Verdict.RECENT), verdicts.toSet())
    }

    @Test
    fun `a quiet link whose probes hang is dead after two probes`() = runTest {
        var probes = 0
        val check = De1LivenessCheck(
            now = { testScheduler.currentTime },
            lastInboundMs = { 0L },
            paused = { false },
            probe = { probes += 1; awaitCancellation() },
        )
        val verdicts = runLoop(check, 60)
        assertEquals(De1LivenessCheck.Verdict.DEAD, verdicts.last())
        assertEquals(De1LivenessCheck.MAX_FAILURES, probes)
        // The quiet window, two probe timeouts and the tick between them (the
        // last notification here is aligned with a tick, so no tick phase).
        assertEquals(10_000L, testScheduler.currentTime)
    }

    @Test
    fun `a firmware flash pauses the check however long the link is quiet`() = runTest {
        var flashing = true
        var probes = 0
        val check = De1LivenessCheck(
            now = { testScheduler.currentTime },
            lastInboundMs = { 0L }, // the erase hold: nothing inbound at all
            paused = { flashing },
            probe = { probes += 1; awaitCancellation() },
        )
        val during = runLoop(check, 120)
        assertEquals(setOf(De1LivenessCheck.Verdict.PAUSED), during.toSet(), "two quiet minutes mid-flash")
        assertEquals(0, probes, "no StateInfo read interleaved with the flash")
        // The flash ends with the link still silent: the ordinary rule resumes.
        flashing = false
        val after = runLoop(check, 60)
        assertEquals(De1LivenessCheck.Verdict.DEAD, after.last())
    }

    @Test
    fun `a flash that takes the link while a probe is out does not count the probe`() = runTest {
        var flashing = false
        val check = De1LivenessCheck(
            now = { testScheduler.currentTime },
            lastInboundMs = { 0L },
            paused = { flashing },
            probe = { flashing = true; awaitCancellation() },
        )
        val verdicts = runLoop(check, 30)
        assertEquals(false, De1LivenessCheck.Verdict.PROBE_FAILED in verdicts)
        assertEquals(false, De1LivenessCheck.Verdict.DEAD in verdicts)
        assertEquals(0, check.failures)
        assertEquals(De1LivenessCheck.Verdict.PAUSED, verdicts.last())
    }

    @Test
    fun `a successful probe counts as traffic`() = runTest {
        var probes = 0
        val check = De1LivenessCheck(
            now = { testScheduler.currentTime },
            lastInboundMs = { 0L }, // asleep and silent, but answers reads
            paused = { false },
            probe = { probes += 1 },
        )
        val verdicts = runLoop(check, 30)
        assertEquals(false, De1LivenessCheck.Verdict.DEAD in verdicts)
        // One read per quiet window, not one per second.
        assertEquals(30 / 5, probes)
    }
}
