package coffee.crema.ui

import coffee.crema.ble.De1WriteFailedException
import coffee.crema.ble.UrgentWriteGate
import coffee.crema.ble.asDe1WriteFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A DE1 write batch survives its own writes' timeouts and preemptions (cohort
 * review 2026-10-28, Android P2-1 / #6 #4). Before, a real
 * `TimeoutCancellationException` escaped `deliverDe1Write` as a cancellation:
 * the rest of the batch was dropped and a timed-out stop logged nothing; and a
 * preempting stop cancelled the whole batch job. These run the real catch
 * structures ([asDe1WriteFailure], [deliverOrLog], [UrgentWriteGate]).
 */
class De1WriteBatchTest {

    @Test
    fun `a write that times out is logged and the batch goes on`() = runTest {
        val landed = mutableListOf<Int>()
        val logged = mutableListOf<String>()
        var timeouts = 0
        executeInOrder(listOf(0, 1, 2)) { frame ->
            deliverOrLog(
                write = {
                    asDe1WriteFailure("frame $frame write", onTimeout = { timeouts += 1 }, onPreempted = {}) {
                        if (frame == 1) withTimeout(5_000) { awaitCancellation() }
                    }
                    landed += frame
                    true
                },
                log = { e -> logged += "${e::class.simpleName}: ${e.message}" },
            )
        }
        assertEquals(listOf(0, 2), landed, "the write after the timeout still goes out")
        assertEquals(1, timeouts)
        assertEquals(listOf("De1WriteFailedException: frame 1 write timed out"), logged)
    }

    @Test
    fun `a stop preempts only the in-flight write, not the writes queued behind it`() = runTest {
        val landed = mutableListOf<Int>()
        val logged = mutableListOf<String>()
        val inFlight = CompletableDeferred<Unit>()
        val batch = launch {
            executeInOrder(listOf(0, 1, 2)) { frame ->
                deliverOrLog(
                    write = {
                        asDe1WriteFailure("frame $frame write", onTimeout = {}, onPreempted = {}) {
                            UrgentWriteGate.preemptible(currentCoroutineContext()[Job], "frame $frame") {
                                if (frame == 0) {
                                    inFlight.complete(Unit)
                                    awaitCancellation() // a wedged GATT op
                                }
                            }
                        }
                        landed += frame
                        true
                    },
                    log = { e -> logged += "${e::class.simpleName}" },
                )
            }
        }
        inFlight.await()
        runCurrent()
        val dropped = UrgentWriteGate.preempt("a stop")
        advanceUntilIdle()
        assertEquals(listOf("frame 0"), dropped)
        assertEquals(listOf(1, 2), landed, "the rest of the batch still goes out")
        assertEquals(listOf("De1WriteFailedException"), logged)
        assertTrue(batch.isCompleted && !batch.isCancelled, "the batch itself was not cancelled")
    }

    @Test
    fun `a cancelled batch still stops`() = runTest {
        val landed = mutableListOf<Int>()
        val started = CompletableDeferred<Unit>()
        val batch = launch {
            executeInOrder(listOf(0, 1)) { frame ->
                deliverOrLog(
                    write = {
                        asDe1WriteFailure("frame $frame write", onTimeout = {}, onPreempted = {}) {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                        landed += frame
                        true
                    },
                    log = {},
                )
            }
        }
        started.await()
        batch.cancel()
        advanceUntilIdle()
        assertTrue(batch.isCancelled)
        assertEquals(emptyList(), landed)
        assertTrue(runCatching { throw De1WriteFailedException("x", RuntimeException()) }.exceptionOrNull() is java.io.IOException)
    }
}
