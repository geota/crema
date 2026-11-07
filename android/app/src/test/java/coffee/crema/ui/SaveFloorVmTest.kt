package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.StoreHealth
import coffee.crema.persist.Stores
import coffee.crema.persist.saveStore
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The load floor on the real ViewModel, whose saves all run under strict mode. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SaveFloorVmTest {
    private val stores = mutableListOf<ViewModelStore>()
    private fun build() = VmHarness.build(ViewModelStore().also(stores::add))
    private fun files(name: String) = File(VmHarness.app().filesDir, name)

    @Before fun reset() {
        SafeFile.resetForTest()
        SaveFloor.resetForTest()
    }

    @After fun tearDown() {
        stores.forEach { it.clear() }
        VmHarness.idle()
        SafeFile.resetForTest()
        SaveFloor.resetForTest()
    }

    @Test
    fun aSecondViewModelCannotSaveBeforeItHasLoaded() {
        val first = build()
        VmHarness.awaitStartupLoaded(first)
        val prefs = files("prefs.json")
        assertTrue(SafeFile.savesOpen(prefs))
        stores.forEach { it.clear() }
        stores.clear()
        VmHarness.idle()

        // A process that outlives its Activity: the gate must not still be open.
        VmHarness.holdingStartupLoad {
            val second = build()
            VmHarness.idle()
            assertFalse(SafeFile.savesOpen(prefs))
            assertFalse(SafeFile.savesOpen(files("beanLibrary.json")))
            SaveFloor.throwOnRefusal = false
            assertFalse(saveStore(prefs, "settings", "{}"))
            VmHarness.idleUntil("the notice") { SaveFloor.message("settings") in second.ui.value.userMessages }
        }
    }

    @Test
    fun aLeftoverViewModelLeavesTheNoticeToTheNewestOne() {
        val leftover = build() // never cleared, as a suite can leave one
        VmHarness.awaitStartupLoaded(leftover)
        VmHarness.holdingStartupLoad {
            val newest = build()
            VmHarness.idle()
            SaveFloor.throwOnRefusal = false
            assertFalse(saveStore(files("prefs.json"), "settings", "{}"))
            VmHarness.idleUntil("the notice") { SaveFloor.message("settings") in newest.ui.value.userMessages }
            VmHarness.idle()
            assertFalse(SaveFloor.message("settings") in leftover.ui.value.userMessages)
        }
    }

    @Test
    fun prefsSavesWorkOnANormalInstallWithNoHotWaterLoad() {
        // A stored mode: the load never reads the marker, and every save writes it.
        SafeFile.openAllForTest()
        runBlocking { SettingsStore(VmHarness.app(), coreJson).save(AppPrefs(qcHotWaterMode = coffee.crema.core.HotWaterMode.Volume, idleSleepMin = 30)) }
        assertTrue(files("hot-water.json").delete())
        val vm = build()
        VmHarness.awaitStartupLoaded(vm)
        assertFalse(SafeFile.savesOpen(files("hot-water.json")))
        vm.setBaristaName("Ana")
        VmHarness.idleUntil("the marker") { files("hot-water.json").exists() }
    }

    @Test
    fun theProxyReadDoesNotOpenPrefsBeforeTheirLoad() {
        VmHarness.persistRole("normal")
        VmHarness.holdingStartupLoad {
            build()
            VmHarness.idle()
            assertFalse(SafeFile.savesOpen(files("prefs.json")))
        }
    }

    @Test
    fun theProxyReadLeavesADamagedFileForTheLoadToReport() {
        val prefs = files("prefs.json").apply { writeText("{not json") }
        VmHarness.holdingStartupLoad {
            build()
            VmHarness.idle()
            assertEquals("{not json", prefs.readText())
            assertEquals(StoreHealth.LOADING, Stores.health(prefs))
        }
        // The hold's over: the load runs, and it's the one that finds the damage.
        VmHarness.idleUntil("the load") { Stores.health(prefs) == StoreHealth.DAMAGED }
    }

    @Test
    fun theProxyReadSeesAFileBetweenASavesTwoRenames() {
        VmHarness.persistRole("primary")
        val prefs = files("prefs.json")
        assertTrue(prefs.renameTo(File(prefs.path + ".tmp")))
        val vm = build()
        VmHarness.awaitStartupLoaded(vm)
        assertEquals("primary", vm.ui.value.proxyRole)
    }

    @Test
    fun theProxyReadLeavesAFileItsOwnLoadCanSanitize() {
        val prefs = files("prefs.json")
        prefs.writeText("""{"proxyRole":"normal","themeMode":7,"baristaName":"Ana"}""")
        val vm = build()
        VmHarness.awaitStartupLoaded(vm)
        assertEquals(emptyList(), SafeFile.keptAside(prefs))
        assertEquals("Ana", vm.ui.value.baristaName)
    }
}
