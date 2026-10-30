package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F15: every whole-bundle settings write reaches the core — restore, Restore
 * defaults and erase-all each hand the core the new settings at once (through
 * `pushCoreInputs` → the core's `apply_common_settings`). Observed through the
 * core's own stop-target projection, whose max-time leg is the max shot
 * duration the core latched. A real VM on the host-built core.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SettingsPushTest {
    private val store = ViewModelStore()

    @After fun clear() {
        store.clear()
        VmHarness.idle()
    }

    private fun built(): MainViewModel = VmHarness.build(store).also {
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(it)
    }

    private fun MainViewModel.coreMaxTime(): Float? = ui.value.stopTargets?.maxTimeS

    @Test
    fun `restore, reset and erase each push the new settings to the core`() {
        val vm = built()
        vm.setMaxShotDuration(30f)
        VmHarness.idle()
        assertEquals(30f, vm.coreMaxTime())

        // Restore a backup whose settings say 45 s.
        val bundle = listOf(
            """{"kind":"crema-backup/v1","createdAt":1}""",
            """{"kind":"settings","_shell":"android","common":{"maxShotDurationS":45,"weightUnit":"oz"}}""",
        ).joinToString("\n")
        vm.restoreBackupFromText(bundle, MainViewModel.RestoreMode.MERGE)
        VmHarness.idleUntil("restore applied") { vm.ui.value.maxShotDurationS == 45f }
        assertEquals(45f, vm.coreMaxTime())

        // Restore defaults: the cap goes (0 = none) but the weight unit stays.
        vm.resetPreferences()
        VmHarness.idle()
        assertEquals(0f, vm.ui.value.maxShotDurationS)
        assertNull(vm.coreMaxTime())
        assertEquals("oz", vm.ui.value.weightUnit)

        // Erase-all resets everything, the weight unit included.
        vm.setMaxShotDuration(20f)
        VmHarness.idle()
        assertEquals(20f, vm.coreMaxTime())
        vm.eraseAll()
        VmHarness.idle()
        assertNull(vm.coreMaxTime())
        assertEquals("g", vm.ui.value.weightUnit)
    }

    @Test
    fun `reset keeps the line frequency`() {
        val vm = built()
        vm.setLineFrequency(50f)
        VmHarness.idle()
        vm.resetPreferences()
        VmHarness.idle()
        assertEquals(50f, vm.ui.value.lineFreqOverride)
    }
}
