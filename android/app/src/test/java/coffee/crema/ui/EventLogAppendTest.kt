package coffee.crema.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * appendLog builds the new log INSIDE the atomic update (cohort review
 * 2026-10-25, Android P3-1). It is called from Main, the core lane and the
 * firmware scope at once; reading the log outside the update dropped lines.
 */
class EventLogAppendTest {

    @Test
    fun prependsNewestFirstAndCaps() {
        assertEquals(listOf("c", "b"), prependLogLine(listOf("b", "a"), "c", max = 2))
        assertEquals(listOf("a"), prependLogLine(emptyList(), "a", max = 200))
    }

    @Test
    fun concurrentAppendsInsideTheUpdateLoseNothing() {
        val state = MutableStateFlow(MainUiState())
        val threads = 8
        val perThread = 20 // 160 lines, under MAX_LOG_LINES (200)
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i ->
                    state.update { it.copy(eventLog = prependLogLine(it.eventLog, "t$t-$i", 200)) }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
        assertEquals(threads * perThread, state.value.eventLog.toSet().size)
    }
}
