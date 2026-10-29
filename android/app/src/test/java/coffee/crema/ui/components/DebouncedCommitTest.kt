package coffee.crema.ui.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Person fields commit after a typing pause, not per keystroke (cohort review
 * 2026-10-25, 06 #15): each commit rewrites the whole history and schedules
 * a Visualizer PATCH and a Decent re-send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebouncedCommitTest {
    @Test
    fun typingANameCommitsOnceAfterThePause() = runTest {
        val commits = mutableListOf<String?>()
        val c = DebouncedCommit<String?>(backgroundScope, PERSON_COMMIT_DEBOUNCE_MS, null) { commits += it }
        "Alexander".indices.forEach { i ->
            c.update("Alexander".substring(0, i + 1))
            advanceTimeBy(120)
        }
        assertEquals(emptyList<String?>(), commits)
        advanceTimeBy(PERSON_COMMIT_DEBOUNCE_MS)
        runCurrent()
        assertEquals(listOf<String?>("Alexander"), commits)
    }

    @Test
    fun flushCommitsAPendingEditAtOnceAndOnlyOnce() = runTest {
        val commits = mutableListOf<String?>()
        val c = DebouncedCommit<String?>(backgroundScope, PERSON_COMMIT_DEBOUNCE_MS, "Ana") { commits += it }
        c.update(null) // cleared, then the user leaves the shot
        c.flush()
        advanceTimeBy(PERSON_COMMIT_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(listOf<String?>(null), commits)
        c.flush()
        assertEquals(1, commits.size)
    }

    @Test
    fun typingBackToTheSavedValueCommitsNothing() = runTest {
        val commits = mutableListOf<String?>()
        val c = DebouncedCommit<String?>(backgroundScope, PERSON_COMMIT_DEBOUNCE_MS, "Ana") { commits += it }
        c.update("An")
        c.update("Ana")
        advanceTimeBy(PERSON_COMMIT_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(emptyList<String?>(), commits)
    }
}
