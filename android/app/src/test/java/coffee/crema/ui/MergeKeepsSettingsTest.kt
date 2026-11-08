package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Merge keeps this device's settings, maintenance counters and Visualizer
 * prefs, as the owner decided; Replace takes the backup's. A Drive picker makes
 * merging an older copy the normal case, so this mustn't wind settings back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MergeKeepsSettingsTest {
    private val store = ViewModelStore()

    @After fun clear() {
        store.clear()
        VmHarness.idle()
    }

    private val bundle = listOf(
        """{"kind":"crema-backup/v1","createdAt":1,"beanCount":0}""",
        """{"kind":"settings","_shell":"android","common":{"maxShotDurationS":45,"weightUnit":"oz"}}""",
        """{"cleanAtMs":1,"cleanIntervalHours":48.0,"descaleAtMs":1,"descaleBaselineLitres":0.0,"descaleIntervalLitres":120.0,""" +
            """"filterAtMs":1,"filterBaselineLitres":0.0,"filterCapacityLitres":50.0,"kind":"maintenance","totalLitres":99.0}""",
        """{"kind":"visualizerPrefs","_shell":"android","shotsDirection":"off"}""",
    ).joinToString("\n")

    private fun restored(mode: MainViewModel.RestoreMode): MainViewModel {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        vm.setMaxShotDuration(30f)
        VmHarness.idle()
        val before = vm.ui.value.userMessages.count { it.startsWith("Restored ") }
        vm.restoreBackupFromText(bundle, mode)
        VmHarness.idleUntil("the restore") { vm.ui.value.userMessages.count { it.startsWith("Restored ") } > before }
        VmHarness.idle()
        return vm
    }

    @Test
    fun mergeKeepsThisDevicesSettingsCountersAndVisualizerPrefs() {
        val vm = restored(MainViewModel.RestoreMode.MERGE)
        val s = vm.ui.value
        assertEquals(30f, s.maxShotDurationS)
        assertEquals("g", s.weightUnit)
        assertEquals(0.0, s.maintenance.totalLitres)
        assertEquals("backup", kotlinx.coroutines.runBlocking { vm.visualizer.backupPrefs() }.shotsDirection)
    }

    @Test
    fun replaceTakesTheBackups() {
        val vm = restored(MainViewModel.RestoreMode.WIPE)
        val s = vm.ui.value
        assertEquals(45f, s.maxShotDurationS)
        assertEquals("oz", s.weightUnit)
        assertEquals(99.0, s.maintenance.totalLitres)
        assertEquals("off", kotlinx.coroutines.runBlocking { vm.visualizer.backupPrefs() }.shotsDirection)
    }
}
