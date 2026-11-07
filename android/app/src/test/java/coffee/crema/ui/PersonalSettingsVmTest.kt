package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SafeLoad
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The personal preference setters (MainViewModel breakup step 6) change the
 * UI state, save the prefs file, and come back on the next start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PersonalSettingsVmTest {
    private val stores = mutableListOf<ViewModelStore>()

    /** What a load would see, through the same lock as a save. */
    private fun savedPrefs(f: File): String =
        (SafeFile.load(f, open = false) { it } as? SafeLoad.Loaded)?.value.orEmpty()

    @After fun tearDown() { stores.forEach { it.clear() }; VmHarness.idle() }

    private fun build(): MainViewModel {
        val store = ViewModelStore().also { stores += it }
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        return vm
    }

    @Test
    fun `personal setters persist and survive a restart`() {
        val first = build()
        first.setThemeMode("dark")
        first.setTempUnit("F")
        first.setScreensaverAfterMin(500)
        first.setBaristaName("Ana")
        first.setBrewDefaults(17f, 2.5f, 91f, 6f)
        first.toggleChartChannel("pressure", false)
        first.setProxyPrimaryHost("  10.0.0.2  ")
        val s = first.ui.value
        assertEquals("dark", s.themeMode)
        assertEquals(120, s.screensaverAfterMin, "clamped to two hours")
        assertEquals("10.0.0.2", s.proxyPrimaryHost)
        val prefs = File(VmHarness.app().filesDir, "prefs.json")
        VmHarness.idleUntil("the last save") { savedPrefs(prefs).contains("10.0.0.2") }
        stores.forEach { it.clear() }
        VmHarness.idle()

        val next = build().ui.value
        assertEquals("dark", next.themeMode)
        assertEquals("F", next.tempUnit)
        assertEquals(120, next.screensaverAfterMin)
        assertEquals("Ana", next.baristaName)
        assertEquals(17f, next.defaultDoseG)
        assertEquals(91f, next.defaultBrewTempC)
        assertFalse("pressure" in next.chartChannels)
        assertTrue(next.proxyPrimaryHost == "10.0.0.2")
    }
}
