package coffee.crema.ui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
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
 * Final review 05 N-1 / N-2 and 04 P3-3: "Erase all data" deletes every store
 * file AND its copies (`.bak`, `.tmp`, `.corrupt-*`), the Visualizer / Decent /
 * Drive stores with their logs and settings, the bag photos, the last-shot
 * diagnostic and the BLE session captures. Builds the real ViewModel (as
 * [MainViewModelSmokeTest] does), seeds old data on disk, erases, and waits
 * for the erase's own completion notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EraseAllDataTest {

    private val old = "OLD-USER-DATA"

    /** Every store file Erase must remove (a store may re-create a fresh one). */
    private val stores = listOf(
        "beanLibrary.json", "shots.json", "customProfiles.json", "pinnedProfiles.json",
        "hiddenProfiles.json", "brew-recipes.json", "maintenance.json", "prefs.json",
        "sawModel.json", "sawModel.corrupt.json", "visualizer.json", "decent.json", "drive.json",
        "last-shot.txt",
    )

    @Test
    fun eraseLeavesNoCopyOfTheOldData() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MainViewModel::class.java]
        shadowOf(Looper.getMainLooper()).idle()

        val files = app.filesDir
        for (name in stores) {
            File(files, name).writeText(old)
            File(files, "$name.bak").writeText(old)
            File(files, "$name.tmp").writeText(old)
            File(files, "$name.corrupt-1700000000000").writeText(old)
        }
        File(files, "bean-images").mkdirs()
        File(files, "bean-images/bean_1").writeText(old)
        val captures = File(app.getExternalFilesDir(null), "captures").apply { mkdirs() }
        File(captures, "session-20261001-120000.jsonl").writeText(old)

        vm.eraseAll()
        val done = "Erased all Crema data on this device"
        val deadline = System.currentTimeMillis() + 20_000
        while (done !in vm.ui.value.userMessages) {
            if (System.currentTimeMillis() > deadline) fail("erase did not finish")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()

        val leftovers = files.walkTopDown().filter { it.isFile && it.readText().contains(old) }.map { it.name }.toList()
        assertEquals(emptyList(), leftovers, "files still holding pre-erase data")
        val copies = files.listFiles()!!.map { it.name }
            .filter { it.endsWith(".bak") || it.endsWith(".tmp") || ".corrupt-" in it }
        assertEquals(emptyList(), copies, "store copies left behind")
        assertTrue(captures.listFiles().isNullOrEmpty(), "BLE captures left behind")
        assertTrue(vm.ui.value.history.isEmpty())
        assertTrue(vm.ui.value.beans.isEmpty())

        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
