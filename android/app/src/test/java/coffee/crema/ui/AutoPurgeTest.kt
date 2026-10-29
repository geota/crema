package coffee.crema.ui

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The post-steam auto-purge: deferred, primary-only, and it yields to a
 *  pending shot start (PR #197 review #1). */
class AutoPurgeTest {

    @Test
    fun `flushes once after the defer on the device holding the DE1`() = runTest {
        var flushes = 0
        AutoPurge(backgroundScope, authoritative = { true }, startPending = { false }, flush = { flushes++ }).schedule()
        advanceTimeBy(AUTO_PURGE_DELAY_MS - 1)
        runCurrent()
        assertEquals(0, flushes)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, flushes)
    }

    @Test
    fun `a mirror never purges, so nothing is relayed to the primary`() = runTest {
        var flushes = 0
        AutoPurge(backgroundScope, authoritative = { false }, startPending = { false }, flush = { flushes++ }).schedule()
        advanceTimeBy(AUTO_PURGE_DELAY_MS * 4)
        runCurrent()
        assertEquals(0, flushes)
    }

    @Test
    fun `becoming a mirror during the defer cancels the purge`() = runTest {
        var flushes = 0
        var primary = true
        AutoPurge(backgroundScope, authoritative = { primary }, startPending = { false }, flush = { flushes++ }).schedule()
        primary = false
        advanceTimeBy(AUTO_PURGE_DELAY_MS * 2)
        runCurrent()
        assertEquals(0, flushes)
    }

    @Test
    fun `a shot start during the defer wins the group`() = runTest {
        var flushes = 0
        var starting = false
        AutoPurge(backgroundScope, authoritative = { true }, startPending = { starting }, flush = { flushes++ }).schedule()
        starting = true
        advanceTimeBy(AUTO_PURGE_DELAY_MS * 2)
        runCurrent()
        assertEquals(0, flushes)
    }
}
