package coffee.crema.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The DE1's connect-time seed rule, which now runs inside the session's
 * establish step (cohort review 2026-10-25: report 04 P2-1, report 06 #12):
 * a hollow link throws so the supervisor counts a failed attempt, and a
 * session cancelled mid-read never reaches the hollow verdict.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeedReadsTest {
    private val seeds = listOf(
        SeedSpec("version", "firmware version", required = false),
        SeedSpec("state", "machine state", required = true),
        SeedSpec("settings", "shot-settings", required = false),
    )

    @Test
    fun aHollowLinkIsTornDownAndThrows() = runTest {
        val reads = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        var teardowns = 0
        try {
            runSeedReads(seeds, { reads += it.key; Result.failure(IllegalStateException("timed out")) }, { statuses += it }) {
                teardowns++
            }
            fail("a hollow link must fail the establish step")
        } catch (e: HollowLinkException) {
            assertEquals(HOLLOW_LINK_STATUS, e.message)
        }
        assertEquals(listOf("version", "state", "state"), reads)
        assertEquals(1, teardowns)
        assertTrue(statuses.contains(HOLLOW_LINK_STATUS))
        assertTrue(statuses.any { it.startsWith("DE1 firmware version seed read skipped") })
    }

    @Test
    fun optionalFailuresAreSkippedAndOneRetrySavesTheRequiredRead() = runTest {
        var stateTries = 0
        val statuses = mutableListOf<String>()
        var teardowns = 0
        runSeedReads(
            seeds,
            { s ->
                when (s.key) {
                    "state" -> if (++stateTries == 1) Result.failure(IllegalStateException("slow")) else Result.success(Unit)
                    else -> Result.failure(IllegalStateException("notify-only"))
                }
            },
            { statuses += it },
        ) { teardowns++ }
        assertEquals(2, stateTries)
        assertEquals(0, teardowns)
        assertFalse(statuses.contains(HOLLOW_LINK_STATUS))
    }

    @Test
    fun aSessionCancelledMidReadNeverJudgesTheLink() = runTest {
        val statuses = mutableListOf<String>()
        var teardowns = 0
        var cancelled = false
        val job = launch {
            try {
                runSeedReads(seeds, { s -> if (s.key == "state") awaitCancellation() else Result.success(Unit) }, { statuses += it }) {
                    teardowns++
                }
            } catch (c: CancellationException) {
                cancelled = true
                throw c
            }
        }
        advanceTimeBy(100)
        runCurrent()
        job.cancel() // the user tapped Disconnect, or a new connect replaced the session
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(0, teardowns)
        assertFalse(statuses.contains(HOLLOW_LINK_STATUS))
    }

    @Test
    fun aHollowEstablishIsAFailedAttemptTheSupervisorBacksOffFrom() = runTest {
        // How De1BleManager.establish now ends over a hollow link: linked,
        // subscribed, then the seed rule throws. READY (onConnected) must never
        // be published, and the ladder must grow instead of restarting.
        val attemptsAt = mutableListOf<Long>()
        var readies = 0
        val job = launch {
            reconnectingSession(
                label = "DE1",
                logTag = "test",
                status = {},
                isUserInitiated = { false },
                isAutoReconnectEnabled = { true },
                establish = {
                    attemptsAt += currentTime
                    runSeedReads(seeds, { Result.failure(IllegalStateException("timed out")) }, {}) {}
                },
                awaitDrop = { awaitCancellation() },
                onConnected = { readies++ },
                onConnecting = {},
                teardown = {},
                kicker = ReconnectKicker(nowMs = { currentTime }),
            )
        }
        advanceTimeBy(10_000)
        runCurrent()
        job.cancel()
        assertEquals(0, readies)
        // Each attempt spends the 500 ms retry pause, then waits 0.5 s, 1 s, 2 s, 4 s …
        val r = SEED_READ_RETRY_DELAY_MS
        assertEquals(
            listOf(0L, r + backoffMs(1), 2 * r + backoffMs(1) + backoffMs(2), 3 * r + backoffMs(1) + backoffMs(2) + backoffMs(3)),
            attemptsAt.take(4),
        )
    }
}
