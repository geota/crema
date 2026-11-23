package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.history.HistoryStore
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [VmHarness.onMain]: a suspend call a test waits for runs with the main
 * looper going, so work it waits on that is posted to Main still runs. A
 * `runBlocking` on the Robolectric main thread deadlocks in both cases here
 * (FLAKY.md: the #545 CI hang in RestoreResweepTest, and #417):
 * - a backup asked for before the history's rows have landed, which they do
 *   on Main after the IO read;
 * - a restore whose run hops to Main.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MainThreadWaitsTest {
    private val store = ViewModelStore()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
        java.io.File(app.filesDir, "shots.json").writeText(
            """[{"id":"s1","completedAtMs":1700000000000,"durationMs":1000,"brewMethod":"pourover"}]""",
        )
    }

    @After fun tearDown() {
        HistoryStore.holdOpen = null
        java.io.File(app.filesDir, "shots.json").delete()
        store.clear()
        VmHarness.idle()
        SafeFile.resetForTest()
        Stores.resetForTest()
    }

    @Test
    fun `a backup asked for before the rows land completes`() {
        val rows = CompletableDeferred<Unit>()
        HistoryStore.holdOpen = rows
        val vm = VmHarness.build(store)
        VmHarness.idleUntil("the startup load") { vm.startupLoad.isCompleted && vm.ownStateLoads.all { it.isCompleted } }
        assertTrue(!VmHarness.library(vm).rowsReady, "the rows haven't landed yet")
        // The IO read is let go from another thread once the backup waits; its
        // landing then needs the main looper.
        Thread {
            Thread.sleep(200)
            rows.complete(Unit)
        }.start()
        val backup = assertNotNull(VmHarness.backupBytes(vm), "a backup")
        assertTrue(backup.isNotEmpty())
    }

    @Test
    fun `a restore whose run waits on Main completes`() {
        val vm = VmHarness.build(store)
        VmHarness.awaitStartupLoaded(vm)
        val backup = assertNotNull(VmHarness.backupBytes(vm))
        vm.beforeRestoreRun = { withContext(Dispatchers.Main) { } }
        VmHarness.restore(vm, backup, MainViewModel.RestoreMode.WIPE)
    }
}
