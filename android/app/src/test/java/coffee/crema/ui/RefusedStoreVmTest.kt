package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.persist.RefusedStore
import coffee.crema.persist.SafeFile
import coffee.crema.persist.StoreHealth
import coffee.crema.persist.Stores
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.saveStore
import coffee.crema.ui.components.refusedStoreText
import kotlinx.coroutines.CompletableDeferred
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
        SaveFloor.resetForTest()
    }

    @After fun tearDown() {
        library.setReadable(true)
        shots.setReadable(true)
        HistoryStore.holdSeriesLoad = null
        MainViewModel.holdStoreReload = null
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

        assertFalse(saveStore(library, "bean library", """{"beans":[],"roasters":[]}"""))
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
    fun aSaveQueuedBeforeTheRealLoadCannotReplaceTheFile() {
        val raw = "[${row("shot-1")},${row("shot-2")},${row("shot-3")}]"
        shots.writeText(raw)
        shots.setReadable(false)
        val vm = vm()
        val made = vm.logBrew()
        shots.setReadable(true)
        val gate = CompletableDeferred<Unit>()
        var read = false
        MainViewModel.holdStoreReload = {
            read = true
            gate.await()
        }
        val retry = vm.retryStore(vm.ui.value.refusedStores.single())
        VmHarness.idleUntil("the read") { read }

        // Held after the read: a save now holds only what this session made.
        runBlocking { HistoryStore(VmHarness.app(), coreJson).save(vm.ui.value.history) }
        assertEquals(raw, shots.readText(), "reading it must not lift the refusal")

        gate.complete(Unit)
        VmHarness.idleUntil("the reload") { retry.isCompleted }
        VmHarness.idleUntil("the save") { made.id in shots.readText() }
        assertEquals(setOf("shot-1", "shot-2", "shot-3", made.id), vm.ui.value.history.map { it.id }.toSet())
    }

    @Test
    fun aSaveCaughtBetweenTheLoadAndItsStateCannotReplaceTheFile() {
        val raw = "[${row("shot-1")}]"
        shots.writeText(raw)
        shots.setReadable(false)
        val vm = vm()
        shots.setReadable(true)
        var landed: Boolean? = null
        SafeFile.afterLoad = { file ->
            // The refusal has just lifted, and the rows aren't in memory yet.
            if (file == shots && landed == null) landed = saveStore(shots, "shot history", "[]")
        }
        val retry = vm.retryStore(vm.ui.value.refusedStores.single())
        VmHarness.idleUntil("the reload") { retry.isCompleted }
        assertEquals(false, landed)
        assertEquals(raw, shots.readText())
        assertEquals(listOf("shot-1"), vm.ui.value.history.map { it.id })
    }

    @Test
    fun theBannerSaysWhatTryAgainDoes() {
        assertEquals(
            "Couldn't read your shot history, so changes aren't being saved. " +
                "Try again reloads what's saved and discards changes made since.",
            refusedStoreText(RefusedStore("shot history", shots)),
        )
    }

    @Test
    fun aPullIsRefusedWhileTheHistoryIs() {
        shots.writeText("[${row("shot-1")}]")
        shots.setReadable(false)
        val vm = vm()
        vm.visualizer.resyncAllShots(emptyList())
        VmHarness.idleUntil("the refusal") { "Shot data is still loading — try again in a moment" in vm.ui.value.userMessages }
    }

    @Test
    fun aShotThatFallsOffTheCapSaysSo() {
        val rows = (0 until HistoryStore.MAX_SHOTS).joinToString(",") { row("shot-$it") }
        shots.writeText("[$rows]")
        shots.setReadable(false)
        val vm = vm()
        vm.logBrew()
        shots.setReadable(true)
        vm.retryStore(vm.ui.value.refusedStores.single())
        VmHarness.idleUntil("the notice") { vm.ui.value.userMessages.any { "1 older shot was dropped" in it } }
    }

    @Test
    fun seriesReadersWaitForTheReloadedSeries() {
        SafeFile.openAllForTest()
        val curved = StoredShot(
            id = "s1", completedAtMs = 1_700_000_000_000L, durationMs = 30_000, profileName = "P",
            samples = List(5) { n -> TelemetrySample(elapsedMs = n * 222L, pressure = 7.25f, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = null, weightFlow = null, dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f, setGroupFlow = 0f, frameNumber = 1) },
        )
        runBlocking { HistoryStore(VmHarness.app(), coreJson).save(listOf(curved)) }
        shots.setReadable(false)
        val vm = vm()
        shots.setReadable(true)
        val gate = CompletableDeferred<Unit>()
        HistoryStore.holdSeriesLoad = gate
        val retry = vm.retryStore(vm.ui.value.refusedStores.single())
        VmHarness.idleUntil("the rows") { retry.isCompleted }

        assertEquals(null, vm.shotsJson(null), "the series reload is still held")
        gate.complete(Unit)
        VmHarness.idleUntil("the series") { vm.ui.value.history.single().samples.isNotEmpty() }
    }

    @Test
    fun aShotInBothListsUnderAnotherIdIsNotMadeTwice() {
        val stored = listOf(StoredShot(id = "local", completedAtMs = 1, durationMs = 1, visualizerId = "v1"))
        val stub = StoredShot(id = "stub", completedAtMs = 1, durationMs = 1, visualizerId = "v1")
        val made = StoredShot(id = "new", completedAtMs = 1, durationMs = 1)
        assertEquals(listOf(made), madeWhileRefused(listOf(stub, made, stored.single()), stored))
    }

    @Test
    fun theSetAsideFileCanBeSharedFromTheApp() {
        library.writeText(aBean)
        library.setReadable(false)
        val vm = vm()
        val aside = vm.putStoreAside(vm.ui.value.refusedStores.single())
        VmHarness.idleUntil("the set-aside") { aside.isCompleted }
        VmHarness.idleUntil("the notice") { vm.ui.value.keptAsideNotice?.contains("set aside") == true }
    }

    @Test
    fun aDoubleTapOnSetAsideLeavesAGoodBackup() {
        library.writeText(aBean)
        library.setReadable(false)
        File(library.path + ".bak").writeText(aBean)
        val vm = vm()
        val store = vm.ui.value.refusedStores.single()
        val first = vm.putStoreAside(store)
        val second = vm.putStoreAside(store)
        VmHarness.idleUntil("both taps") { first.isCompleted && second.isCompleted }
        assertEquals(aBean, library.readText(), "the backup came back, and stayed")
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

        val aside = vm.putStoreAside(vm.ui.value.refusedStores.single())
        VmHarness.idleUntil("the set-aside") { aside.isCompleted }
        assertEquals(emptyList(), vm.refused())
        assertFalse(library.exists())
        assertEquals(StoreHealth.ABSENT, Stores.health(library))
        assertTrue(saveStore(library, "bean library", """{"beans":[],"roasters":[]}"""))
        val kept = SafeFile.keptAside(library).single()
        kept.setReadable(true)
        assertEquals(aBean, kept.readText())
    }
}
