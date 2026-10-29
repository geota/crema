package coffee.crema.ble.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [awaitNsdResolve]: a resolve that never calls back must not hold the
 *  discovery's resolve lock forever, and is cancelled when it times out. */
class NsdResolveTimeoutTest {

    @Test
    fun `a resolve that never calls back gives up at the timeout and is cancelled`() = runTest {
        var cancelled = 0
        val never = CompletableDeferred<String?>()
        val result = async { awaitNsdResolve(start = {}, result = never, cancel = { cancelled++ }) }

        advanceTimeBy(NSD_RESOLVE_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(result.isCompleted, "still waiting just before the timeout")

        advanceTimeBy(2)
        runCurrent()
        assertTrue(result.isCompleted)
        assertNull(result.await())
        assertEquals(1, cancelled, "the stuck resolve is cancelled")
    }

    @Test
    fun `a resolve that calls back returns its result and is not cancelled`() = runTest {
        var cancelled = 0
        val done = CompletableDeferred<String?>()
        val result = async { awaitNsdResolve(start = { done.complete("peer") }, result = done, cancel = { cancelled++ }) }
        assertEquals("peer", result.await())
        assertEquals(0, cancelled)
    }

    @Test
    fun `a failed resolve returns null without waiting`() = runTest {
        var cancelled = 0
        val failed = CompletableDeferred<String?>()
        val result = async { awaitNsdResolve(start = { failed.complete(null) }, result = failed, cancel = { cancelled++ }) }
        assertNull(result.await())
        assertEquals(0, cancelled, "a resolve that answered (with a failure) needs no cancel")
    }

    @Test
    fun `a start that throws returns null`() = runTest {
        val result = awaitNsdResolve(start = { error("busy") }, result = CompletableDeferred<String?>(), cancel = {})
        assertNull(result)
    }
}
