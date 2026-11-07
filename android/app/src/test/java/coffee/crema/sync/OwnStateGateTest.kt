package coffee.crema.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnStateGateTest {
    private val notices = mutableListOf<String>()
    private val gate = OwnStateGate("Visualizer", { notices += it })

    @Test
    fun `a waiter goes through once the load opens the gate`() = runTest {
        val waiter = async { gate.await() }
        runCurrent()
        assertFalse(waiter.isCompleted)
        gate.opened()
        assertTrue(waiter.await())
        assertEquals(emptyList<String>(), notices)
    }

    @Test
    fun `a failed load refuses, and says so once`() = runTest {
        gate.failed()
        assertFalse(gate.await())
        assertFalse(gate.await())
        assertEquals(1, notices.size)
        assertTrue(notices.single(), "Restart" in notices.single())
    }

    @Test
    fun `failed after opened changes nothing`() = runTest {
        gate.opened()
        gate.failed()
        assertTrue(gate.await())
    }

    @Test
    fun `a load that never ends stops the waiters at the timeout with one notice`() = runTest {
        val first = async { gate.await() }
        val second = async { gate.await() }
        advanceTimeBy(OwnStateGate.TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(first.isCompleted)
        assertEquals(emptyList<String>(), notices)
        advanceTimeBy(2)
        assertFalse(first.await())
        assertFalse(second.await())
        assertEquals(1, notices.size)
    }

    @Test
    fun `a load that ends after the timeout still opens the gate`() = runTest {
        val late = async { gate.await() }
        advanceTimeBy(OwnStateGate.TIMEOUT_MS + 1)
        assertFalse(late.await())
        gate.opened()
        assertTrue(gate.await())
    }

    @Test
    fun `settling says nothing, for a caller that goes ahead either way`() = runTest {
        val erase = async { gate.settle() }
        advanceTimeBy(OwnStateGate.TIMEOUT_MS + 1)
        erase.await()
        assertEquals(emptyList<String>(), notices)
    }
}
