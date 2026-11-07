package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.persist.SafeFile
import coffee.crema.persist.StoreHealth
import coffee.crema.persist.Stores
import coffee.crema.persist.saveStore
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

/**
 * The exits from a refused store, on the real ViewModel: an unreadable file
 * isn't overwritten, and the banner's two buttons always lead somewhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RefusedStoreVmTest {
    private val stores = mutableListOf<ViewModelStore>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val library get() = File(app.filesDir, "beanLibrary.json")
    private val shots get() = File(app.filesDir, "shots.json")

    private val aBean = """{"beans":[],"roasters":[],"activeBeanId":"bean-1"}"""

    private fun row(id: String) = """{"id":"$id","completedAtMs":1700000000000,"durationMs":1000}"""

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
    }

    @After fun tearDown() {
        library.setReadable(true)
        shots.setReadable(true)
        stores.forEach { it.clear() }
        VmHarness.idle()
        SafeFile.resetForTest()
        Stores.resetForTest()
    }

    private fun vm(): MainViewModel {
        val s = ViewModelStore().also(stores::add)
        return VmHarness.build(s).also { VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }
    }

    private fun MainViewModel.refused() = ui.value.refusedStores.map { it.what }

    private fun MainViewModel.logBrew() = addManualBrew(
        LibraryController.ManualBrewInput(
            method = "espresso", completedAtMs = 1_700_000_000_000, beanId = null, doseG = 18f, waterG = null,
            yieldG = 36f, grindSetting = null, brewTempC = null, durationMs = 28_000, rating = null, notes = null, nextPlan = null,
        ),
    )

    @Test
    fun anUnreadableLibraryShowsTheBannerAndIsNotOverwritten() {
        library.writeText(aBean)
        library.setReadable(false)
        val vm = vm()
        assertEquals(listOf("bean library"), vm.refused())
        assertEquals(StoreHealth.UNAVAILABLE, Stores.health(library))
        assertEquals(null, vm.ui.value.activeBeanId)

        assertFalse(saveStore(library, """{"beans":[],"roasters":[]}"""))
        library.setReadable(true)
        assertEquals(aBean, library.readText())
        assertEquals(emptyList(), SafeFile.keptAside(library))
    }

    @Test
    fun tryAgainReadsTheStoreAndClearsTheBanner() {
        library.writeText(aBean)
        library.setReadable(false)
        val vm = vm()
        library.setReadable(true)

        vm.retryStore(vm.ui.value.refusedStores.single())
        VmHarness.idleUntil("the reload") { vm.ui.value.activeBeanId == "bean-1" }
        assertEquals(emptyList(), vm.refused())
        assertEquals(StoreHealth.OK, Stores.health(library))
    }

    @Test
    fun tryAgainThatFailsKeepsTheBannerAndWhatIsInMemory() {
        shots.writeText("[${row("shot-1")}]")
        shots.setReadable(false)
        val vm = vm()
        val made = vm.logBrew()

        val retry = vm.retryStore(vm.ui.value.refusedStores.single())
        VmHarness.idleUntil("the retry") { retry.isCompleted }
        assertEquals(listOf("shot history"), vm.refused())
        assertEquals(listOf(made.id), vm.ui.value.history.map { it.id })
    }

    @Test
    fun tryAgainThatFailsDoesNotEmptyAStoreInMemory() {
        library.writeText(aBean)
        library.setReadable(false)
        val vm = vm()
        vm.setActiveBean("bean-2")
        VmHarness.idle()

        val retry = vm.retryStore(vm.ui.value.refusedStores.single())
        VmHarness.idleUntil("the retry") { retry.isCompleted }
        assertEquals(listOf("bean library"), vm.refused())
        assertEquals("bean-2", vm.ui.value.activeBeanId)
    }

    @Test
    fun aShotMadeWhileRefusedSurvivesTheReload() {
        shots.writeText("[${row("shot-1")}]")
        shots.setReadable(false)
        val vm = vm()
        val made = vm.logBrew()
        shots.setReadable(true)

        vm.retryStore(vm.ui.value.refusedStores.single())
        VmHarness.idleUntil("the reload") { vm.ui.value.history.size == 2 }
        VmHarness.idleUntil("the save") { made.id in shots.readText() }
        assertEquals(setOf("shot-1", made.id), vm.ui.value.history.map { it.id }.toSet())
    }

    @Test
    fun setThisAsideStartsEmptyAndKeepsTheFile() {
        library.writeText(aBean)
        library.setReadable(false)
        val vm = vm()

        vm.putStoreAside(vm.ui.value.refusedStores.single())
        VmHarness.idleUntil("the set-aside") { vm.refused().isEmpty() }
        assertFalse(library.exists())
        assertEquals(StoreHealth.ABSENT, Stores.health(library))
        assertTrue(saveStore(library, """{"beans":[],"roasters":[]}"""))
        val kept = SafeFile.keptAside(library).single()
        kept.setReadable(true)
        assertEquals(aBean, kept.readText())
    }
}
