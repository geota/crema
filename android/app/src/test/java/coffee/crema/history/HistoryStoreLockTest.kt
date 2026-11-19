package coffee.crema.history

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.persist.SafeFile
import coffee.crema.ui.coreJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #310 A5, REVIEW-475 R1: every store over one `filesDir` shares one lock.
 * A store's save launched from Main must never need Main again to get or let
 * go of it, or a Main thread that then waits on another store deadlocks: the
 * hang that held CI's android job at 556a7b39, where a save took the lock on
 * Main (`lock.withLock { withContext(IO) { … } }`) and had to resume on Main
 * to release it while Main was blocked on the second store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistoryStoreLockTest {
    @get:Rule val hang = HangWatch(120)

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val stores = mutableListOf<HistoryStore>()

    private fun store() = HistoryStore(app, coreJson, 7).also { stores += it }

    @Before fun clean() {
        SafeFile.openAllForTest()
        runBlocking { store().erase() }
    }

    @After fun tidy() {
        runBlocking { stores.forEach { it.closeForTest() } }
        SafeFile.resetForTest()
    }

    private fun shots(prefix: String, n: Int) =
        HistoryFixtures.rows(n, samplesPer = 2, start = 1_800_000_000_000L).mapIndexed { i, s -> s.copy(id = "$prefix-$i") }

    @Test fun `a save launched on Main while Main then waits on another store over the same files never deadlocks`() {
        val a = store()
        val b = store()
        runBlocking {
            a.loadAll()
            b.loadAll()
        }
        repeat(20) { round ->
            // Store A's save, launched on Main as the app's are: it takes the shared lock.
            val saved = CoroutineScope(Dispatchers.Main.immediate).launch { a.save(shots("a$round", 30)) }
            // Main now blocks on store B, which needs the same lock. Before the fix A had to
            // resume on Main to let go of it, so this never returned.
            val other = runBlocking {
                withTimeoutOrNull(20_000) { CoroutineScope(Dispatchers.IO).async { b.save(shots("b$round", 3)) }.await() }
            }
            assertNotNull(other, "round $round: store B's save waited forever on the lock store A held")
            assertTrue(other, "round $round")
            // Main is free again: A's save comes back to it and finishes.
            val until = System.currentTimeMillis() + 20_000
            while (!saved.isCompleted && System.currentTimeMillis() < until) {
                shadowIdle()
                Thread.sleep(5)
            }
            assertTrue(saved.isCompleted, "round $round: store A's save finished")
        }
    }

    private fun shadowIdle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
}
