package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.history.HistoryStore
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Cancel tapped while the shots are still loading isn't lost (#391
 * review): the cancel exists from the moment the backup is asked for, so the
 * build stops once the load ends, and keeps nothing. On the real ViewModel.
 *
 * The history's load can end after the startup load has completed (#417): it
 * reads `shots.db` on IO, then finishes on Main. From #310 P4a it reads rows
 * only, so the load the backup waits for is the rows'. These tests hold it
 * until the startup load is done, so that order is the one tested on every
 * run, and they wait for the backup by running the main looper until it
 * completes. Blocking the main thread on the backup (`runBlocking`) would stop
 * the load from finishing, and the backup with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BackupCancelWhileLoadingTest {
    private val stores = mutableListOf<ViewModelStore>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    /** Holds the history's load (the rows) until a test releases it. */
    private val seriesGate = CompletableDeferred<Unit>()

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
        HistoryStore.holdOpen = seriesGate
        // One shot, so a backup that isn't cancelled has something to write.
        java.io.File(app.filesDir, "shots.json").writeText(
            """[{"id":"s1","completedAtMs":1700000000000,"durationMs":1000,"brewMethod":"pourover"}]""",
        )
    }

    @After fun tearDown() {
        HistoryStore.holdOpen = null
        seriesGate.complete(Unit)
        java.io.File(app.filesDir, "shots.json").delete()
        stores.forEach { it.clear() }
        VmHarness.idle()
        SafeFile.resetForTest()
        Stores.resetForTest()
    }

    /** The startup load, all but the history's, which the gate holds. */
    private fun awaitStartupBesidesHistory(vm: MainViewModel) =
        VmHarness.idleUntil("the startup load") { vm.startupLoad.isCompleted && vm.ownStateLoads.all { it.isCompleted } }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun startBackup(vm: MainViewModel): Deferred<java.io.File?> =
        GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { vm.buildBackupZipFile() }

    /** Release the history's load, then run the main looper until [build] has finished. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun finish(build: Deferred<java.io.File?>): java.io.File? {
        assertFalse(build.isCompleted, "the backup waits for the rows")
        seriesGate.complete(Unit)
        VmHarness.idleUntil("the backup") { build.isCompleted }
        return build.getCompleted()
    }

    /** The same backup, not cancelled, is written: the test below would pass vacuously otherwise. */
    @Test
    fun theSameBackupNotCancelledIsWritten() {
        val vm = VmHarness.build(ViewModelStore().also(stores::add))
        awaitStartupBesidesHistory(vm)
        val file = finish(startBackup(vm))
        assertTrue(file != null && file.length() > 0, "a backup was written")
        releaseBackupFile(file)
    }

    @Test
    fun aCancelTappedWhileTheSeriesLoadStopsTheBackup() {
        VmHarness.holdingStartupLoad { release ->
            val vm = VmHarness.build(ViewModelStore().also(stores::add))
            val build = startBackup(vm)
            assertTrue(build.isActive, "the backup waits for the rows")
            vm.cancelBackup(BackupKind.FILE)
            release.complete(Unit)
            awaitStartupBesidesHistory(vm)
            val file = finish(build)
            assertNull(file, "a cancelled backup keeps nothing")
            assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith(BACKUP_TEMP_PREFIX) }, "no temp file left")
        }
    }
}
