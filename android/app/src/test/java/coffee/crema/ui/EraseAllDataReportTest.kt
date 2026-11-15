package coffee.crema.ui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #428: Erase all data says what it couldn't remove, as the web's factory
 * reset does with `databasesFailed`, instead of always "Erased all".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EraseAllDataReportTest {

    private val old = "OLD-USER-DATA"

    /** A file that stays is told, not passed off as erased. */
    @Test
    fun eraseSaysWhenSomethingStays() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val vm = VmHarness.build(store)
        shadowOf(Looper.getMainLooper()).idle()
        VmHarness.awaitStartupLoaded(vm)

        // A read-only directory in a newer build's SQLite history: its file can't be unlinked.
        val sqliteDir = File(app.filesDir, coffee.crema.history.HistoryStore.SQLITE_DIR)
        val held = File(sqliteDir, "held").apply { mkdirs() }
        val probe = File(held, "probe").apply { writeText(old) }
        File(held, "history.db").writeText(old)
        held.setWritable(false)
        try {
            org.junit.Assume.assumeFalse("a read-only directory's files can't be deleted here (not root)", probe.delete())
            vm.eraseAll()
            val deadline = System.currentTimeMillis() + 20_000
            while (vm.ui.value.userMessages.none { it.startsWith("Erase all data couldn't delete") }) {
                if (System.currentTimeMillis() > deadline) fail("erase did not finish: ${vm.ui.value.userMessages}")
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(MainViewModel.ERASED !in vm.ui.value.userMessages, "not passed off as done")
        } finally {
            held.setWritable(true)
            sqliteDir.deleteRecursively()
            store.clear()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun eraseNoticeCountsWhatStayed() {
        assertEquals(MainViewModel.ERASED, MainViewModel.eraseNotice(emptyList()))
        assertTrue(MainViewModel.eraseNotice(listOf("shots.json")).contains("couldn't delete 1 file,"))
        assertTrue(MainViewModel.eraseNotice(listOf("shots.json", "history-sqlite")).contains("couldn't delete 2 files,"))
    }
}
