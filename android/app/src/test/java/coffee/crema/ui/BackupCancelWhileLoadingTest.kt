package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Cancel tapped while the shots' series are still loading isn't lost (#391
 * review): the cancel exists from the moment the backup is asked for, so the
 * build stops once the load ends, and keeps nothing. On the real ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BackupCancelWhileLoadingTest {
    private val stores = mutableListOf<ViewModelStore>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
        // One shot, so a backup that isn't cancelled has something to write.
        java.io.File(app.filesDir, "shots.json").writeText(
            """[{"id":"s1","completedAtMs":1700000000000,"durationMs":1000,"brewMethod":"pourover"}]""",
        )
    }

    /** The same backup, not cancelled, is written: the test above would pass vacuously otherwise. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun theSameBackupNotCancelledIsWritten() {
        val vm = VmHarness.build(ViewModelStore().also(stores::add))
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        val file = runBlocking { withTimeout(10_000) { vm.buildBackupZipFile() } }
        assertTrue(file != null && file.length() > 0, "a backup was written")
        releaseBackupFile(file)
    }

    @After fun tearDown() {
        java.io.File(app.filesDir, "shots.json").delete()
        stores.forEach { it.clear() }
        VmHarness.idle()
        SafeFile.resetForTest()
        Stores.resetForTest()
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun aCancelTappedWhileTheSeriesLoadStopsTheBackup() {
        VmHarness.holdingStartupLoad { release ->
            val vm = VmHarness.build(ViewModelStore().also(stores::add))
            val build = GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { vm.buildBackupZipFile() }
            assertTrue(build.isActive, "the backup waits for the series")
            vm.cancelBackup(BackupKind.FILE)
            release.complete(Unit)
            VmHarness.idle()
            VmHarness.awaitStartupLoaded(vm)
            val file = runBlocking { withTimeout(10_000) { build.await() } }
            assertNull(file, "a cancelled backup keeps nothing")
            assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith(BACKUP_TEMP_PREFIX) }, "no temp file left")
        }
    }
}
