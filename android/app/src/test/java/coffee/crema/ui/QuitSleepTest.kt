package coffee.crema.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sleep-on-quit (cohort review 2026-10-25, Android P1-1). The primary quit
 * hook is `MainViewModel.onCleared`, where `viewModelScope` is already
 * cancelled: the old path launched the Sleep write there and it never ran,
 * while the latch made the service fallback a no-op. These pin the fixed
 * contract: the write runs on a scope that outlives the VM, the BLE teardown
 * waits (bounded) for it, and the latch only holds when the write went out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuitSleepTest {

    @After
    fun reset() {
        QuitSleep.clear()
    }

    // ── The regression: a write fired from teardown runs to completion ────

    @Test
    fun quitWriteRunsAfterTheViewModelScopeIsCancelled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Stand-ins: the VM's scope (cancelled BEFORE onCleared, as lifecycle
        // 2.x does) and the process scope that outlives it.
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        val processScope = CoroutineScope(SupervisorJob() + dispatcher)
        vmScope.cancel()

        val written = mutableListOf<String>()
        val torn = mutableListOf<String>()
        QuitSleep.install {
            processScope.async {
                sendQuitWrites(
                    listOf(suspend { delay(40); written += "sleep" }),
                    QUIT_WRITE_BUDGET_MS,
                )
            }
        }
        // What onCleared does.
        val pending = QuitSleep.fire()
        teardownAfterQuitWrite(processScope, pending, QUIT_WRITE_BUDGET_MS + 250) { torn += "link down" }

        assertTrue(torn.isEmpty(), "the link must stay up while the quit write is in flight")
        advanceUntilIdle()
        assertEquals(listOf("sleep"), written)
        assertEquals(listOf("link down"), torn)
        assertEquals(true, pending?.await())

        // The old shape — launching on the cancelled VM scope — never writes.
        val old = vmScope.launch { written += "never" }
        advanceUntilIdle()
        assertTrue(old.isCancelled)
        assertEquals(listOf("sleep"), written)
    }

    // ── The latch ─────────────────────────────────────────────────────────

    @Test
    fun aSentWriteHoldsTheLatch() = runTest {
        var calls = 0
        QuitSleep.install { calls++; CompletableDeferred(true) }
        assertTrue(QuitSleep.fire()!!.await())
        assertNull(QuitSleep.fire(), "one quit = one fire")
        assertEquals(1, calls)
    }

    @Test
    fun aWriteThatDidNotGoOutReArmsTheLatchForTheFallback() = runTest {
        var calls = 0
        QuitSleep.install { calls++; CompletableDeferred(calls > 1) }
        assertFalse(QuitSleep.fire()!!.await())
        // The service's onTaskRemoved gets a second try.
        assertTrue(QuitSleep.fire()!!.await())
        assertNull(QuitSleep.fire())
        assertEquals(2, calls)
    }

    @Test
    fun aFailedWriteReArmsTheLatch() = runTest {
        var calls = 0
        QuitSleep.install {
            calls++
            CompletableDeferred<Boolean>().apply { completeExceptionally(IllegalStateException("gatt")) }
        }
        QuitSleep.fire()
        QuitSleep.fire()
        assertEquals(2, calls)
    }

    @Test
    fun aDeclinedGateHoldsTheLatch() {
        var calls = 0
        QuitSleep.install { calls++; null }
        assertNull(QuitSleep.fire())
        assertNull(QuitSleep.fire())
        assertEquals(1, calls, "a deliberate 'don't sleep' is final for this quit")
    }

    @Test
    fun firingWithNoHandlerDoesNotLatch() {
        QuitSleep.clear()
        assertNull(QuitSleep.fire())
        var calls = 0
        // install() re-arms anyway, but the point is fire() never latched.
        QuitSleep.install { calls++; null }
        QuitSleep.fire()
        assertEquals(1, calls)
    }

    // ── Bounded, best-effort ──────────────────────────────────────────────

    @Test
    fun aHungWriteTimesOutAsNotSent() = runTest {
        val result = async { sendQuitWrites(listOf(suspend { awaitCancellation() }), budgetMs = 1_000) }
        advanceTimeBy(1_001)
        runCurrent()
        assertFalse(result.await())
    }

    @Test
    fun aThrowingOrEmptyWriteIsNotSent() = runTest {
        assertFalse(sendQuitWrites(listOf(suspend { error("link gone") }), 1_000))
        assertFalse(sendQuitWrites(emptyList(), 1_000))
    }

    @Test
    fun teardownRunsAtTheBudgetWhenTheWriteHangs() = runTest {
        val scope = TestScope(testScheduler)
        val hung: Deferred<Boolean> = CompletableDeferred()
        var torn = 0
        teardownAfterQuitWrite(scope, hung, budgetMs = 500) { torn++ }
        advanceTimeBy(499)
        scope.testScheduler.runCurrent()
        assertEquals(0, torn)
        advanceTimeBy(2)
        scope.testScheduler.runCurrent()
        assertEquals(1, torn)
    }

    @Test
    fun teardownIsImmediateWithNothingPending() {
        var torn = 0
        teardownAfterQuitWrite(CoroutineScope(Job()), null, 500) { torn++ }
        assertEquals(1, torn)
        teardownAfterQuitWrite(CoroutineScope(Job()), CompletableDeferred(true), 500) { torn++ }
        assertEquals(2, torn)
    }
}
