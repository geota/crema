package coffee.crema.ble.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [awaitNsdResolve] and [shouldRetryNsdResolve]: a resolve that never calls
 *  back must not hold the discovery's resolve lock forever, is cancelled on
 *  every path that ends without a result, and a resolve NSD couldn't run is
 *  retried a few times. */
class NsdResolveTimeoutTest {

    @Test
    fun `a resolve that never calls back gives up at the timeout and is cancelled`() = runTest {
        var cancelled = 0
        val never = CompletableDeferred<NsdResolveOutcome<String>>()
        val result = async { awaitNsdResolve(start = {}, result = never, cancel = { cancelled++ }) }

        advanceTimeBy(NSD_RESOLVE_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(result.isCompleted, "still waiting just before the timeout")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(NsdResolveOutcome.TimedOut, result.await())
        assertEquals(1, cancelled, "the stuck resolve is cancelled")
    }

    @Test
    fun `a resolve interrupted by cancellation is cancelled too`() = runTest {
        var cancelled = 0
        val never = CompletableDeferred<NsdResolveOutcome<String>>()
        val job = launch { awaitNsdResolve(start = {}, result = never, cancel = { cancelled++ }) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertEquals(1, cancelled)
    }

    @Test
    fun `a resolve that calls back returns its result and is not cancelled`() = runTest {
        var cancelled = 0
        val done = CompletableDeferred<NsdResolveOutcome<String>>()
        val result = awaitNsdResolve(start = { done.complete(NsdResolveOutcome.Resolved("peer")) }, result = done, cancel = { cancelled++ })
        assertEquals(NsdResolveOutcome.Resolved("peer"), result)
        assertEquals(0, cancelled)
    }

    @Test
    fun `a failed resolve returns its failure without waiting or cancelling`() = runTest {
        var cancelled = 0
        val failed = CompletableDeferred<NsdResolveOutcome<String>>()
        val result = awaitNsdResolve(start = { failed.complete(NsdResolveOutcome.Failed(3)) }, result = failed, cancel = { cancelled++ })
        assertEquals(NsdResolveOutcome.Failed(3), result)
        assertEquals(0, cancelled)
    }

    @Test
    fun `a start that throws is a failure`() = runTest {
        val result = awaitNsdResolve(start = { error("busy") }, result = CompletableDeferred<NsdResolveOutcome<String>>(), cancel = {})
        assertEquals(NsdResolveOutcome.Failed(-1), result)
    }

    @Test
    fun `resolves NSD couldn't run are retried a few times, plain failures are not`() {
        assertTrue(shouldRetryNsdResolve(NsdResolveOutcome.Failed(3), attempt = 1, sdk = 35), "already active")
        assertTrue(shouldRetryNsdResolve(NsdResolveOutcome.TimedOut, attempt = 1, sdk = 31))
        assertTrue(shouldRetryNsdResolve(NsdResolveOutcome.Failed(0), attempt = 1, sdk = 33), "internal error behind a stuck resolve")
        assertFalse(shouldRetryNsdResolve(NsdResolveOutcome.Failed(0), attempt = 1, sdk = 34))
        assertFalse(shouldRetryNsdResolve(NsdResolveOutcome.Failed(3), attempt = 4, sdk = 31), "bounded")
        assertFalse(shouldRetryNsdResolve(NsdResolveOutcome.Resolved("x"), attempt = 1, sdk = 31))
    }
}
