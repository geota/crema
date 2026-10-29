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
        QuitSleep.reset()
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
        QuitSleep.reset()
        assertNull(QuitSleep.fire())
        var calls = 0
        // install() re-arms anyway, but the point is fire() never latched.
        QuitSleep.install { calls++; null }
        QuitSleep.fire()
        assertEquals(1, calls)
    }

    // ── Successor VMs and the other signal (final review 2026-10-28, 04 P3-1)

    @Test
    fun aPredecessorsLateTeardownKeepsTheSuccessorsHandler() = runTest {
        var oldCalls = 0
        var newCalls = 0
        val old = QuitSleep.install { oldCalls++; null }
        // The swipe: the old VM fires, its teardown is deferred…
        assertNull(QuitSleep.fire())
        // …and the user relaunches before it runs: a new VM installs.
        QuitSleep.install { newCalls++; CompletableDeferred(true) }
        // The old VM's deferred teardown drops only its own handler.
        QuitSleep.clear(old)
        assertTrue(QuitSleep.fire()!!.await(), "sleep-on-quit still works for the new session")
        assertEquals(1, newCalls)
        assertEquals(1, oldCalls)
    }

    @Test
    fun clearDropsItsOwnHandler() {
        var calls = 0
        val mine = QuitSleep.install { calls++; null }
        QuitSleep.clear(mine)
        assertNull(QuitSleep.fire())
        assertEquals(0, calls)
        QuitSleep.clear(null) // no registration: a no-op
    }

    @Test
    fun aLatchedFireHandsOutTheWriteTheServiceStarted() = runTest {
        val write = CompletableDeferred<Boolean>()
        var calls = 0
        QuitSleep.install { calls++; write }
        // QuitSleepService.onTaskRemoved fires first and starts the write…
        val fromService = QuitSleep.fire()
        // …then the activity is destroyed: onCleared gets the same write, not
        // null, so it does not tear the link down underneath it.
        val fromOnCleared = QuitSleep.fire()
        assertTrue(fromOnCleared === fromService && fromOnCleared === write)
        assertEquals(1, calls, "still one write")
        var torn = 0
        teardownAfterQuitWrite(this, fromOnCleared, QUIT_WRITE_BUDGET_MS) { torn++ }
        runCurrent()
        assertEquals(0, torn, "the link stays up while the service's write runs")
        write.complete(true)
        advanceUntilIdle()
        assertEquals(1, torn)
        // Once it settled there is nothing left to wait for.
        assertNull(QuitSleep.fire())
    }

    @Test
    fun aPredecessorsFailedWriteDoesNotReArmTheSuccessor() = runTest {
        val oldWrite = CompletableDeferred<Boolean>()
        QuitSleep.install { oldWrite }
        assertTrue(QuitSleep.fire() === oldWrite)
        var newCalls = 0
        QuitSleep.install { newCalls++; CompletableDeferred(true) }
        assertTrue(QuitSleep.fire()!!.await())
        assertEquals(1, newCalls)
        // The old write fails late: the successor's latch stays held.
        oldWrite.complete(false)
        assertNull(QuitSleep.fire())
        assertEquals(1, newCalls)
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
