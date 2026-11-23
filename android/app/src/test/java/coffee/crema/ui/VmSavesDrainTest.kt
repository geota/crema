package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A test that ends with prefs saves still queued doesn't fail a later one
 * (FLAKY.md, RailSplitTest's UncaughtExceptionsBeforeTest): [VmHarness.build]
 * lets the earlier ViewModel's saves land before the new one starts its save
 * session, which would refuse them (and, under strict saves, throw from the
 * old ViewModel's coroutine). A refusal that does happen names the tests that
 * built the last ViewModels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VmSavesDrainTest {
    private val stores = mutableListOf<ViewModelStore>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @After fun tearDown() {
        SafeFile.beforeRename = {}
        stores.forEach { it.clear() }
        VmHarness.idle()
    }

    @Test
    fun `an ended test's queued prefs saves land before the next ViewModel starts`() {
        val first = VmHarness.build(ViewModelStore().also(stores::add))
        VmHarness.awaitStartupLoaded(first)
        // Slow each prefs write, so the later saves queue behind the first.
        SafeFile.beforeRename = { tmp -> if (tmp.name.startsWith(SettingsStore.FILE_NAME)) Thread.sleep(150) }
        for (mode in listOf("light", "dark", "system")) first.setThemeMode(mode)
        stores.removeAt(0).clear()
        VmHarness.idle()

        // The next test's ViewModel.
        VmHarness.build(ViewModelStore().also(stores::add))
        SafeFile.beforeRename = {}
        assertEquals("system", runBlocking { SettingsStore(app, coreJson).load() }.themeMode, "the last save landed")
    }

    @Test
    fun `a refused save names the tests that built the last ViewModels`() {
        VmHarness.build(ViewModelStore().also(stores::add))
        val refused = runCatching { SaveFloor.admit(java.io.File(app.filesDir, "never-loaded.json"), "x") }.exceptionOrNull()
        val message = refused?.message.orEmpty()
        assertTrue("VmSavesDrainTest.a refused save names the tests that built the last ViewModels" in message, message)
    }
}
