package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.LibraryStore
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An Android restore through the core's plan, on the real ViewModel
 * (restore-safety PR7a): the report before a restore that needs a yes, step
 * 7 against an edit made meanwhile, and "none" for the active bean lasting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RestoreVmTest {
    private var store = ViewModelStore()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val fixture = File("../../core/de1-domain/tests/fixtures/backup/android-backup.crema.jsonl")

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
    }

    @After fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    private fun vm(): MainViewModel = VmHarness.build(store).also { VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }

    private fun shot(id: String, at: Long) = StoredShot(id = id, completedAtMs = at, durationMs = 30_000)

    @Test
    fun aReplaceShowsItsNumbersBehindErase() {
        val vm = vm()
        vm.addManualBrew(
            LibraryController.ManualBrewInput(
                method = "espresso", completedAtMs = 1_700_000_000_000, beanId = null, doseG = 18f, waterG = null,
                yieldG = 36f, grindSetting = null, brewTempC = null, durationMs = 28_000, rating = null, notes = null, nextPlan = null,
            ),
        )
        VmHarness.idle()
        vm.startRestore(fixture.readBytes(), "fixture", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the report") { vm.ui.value.restoreReport != null }
        val report = assertNotNull(vm.ui.value.restoreReport)
        assertTrue(report.replace)
        assertEquals("Replace everything on this device?", report.title)
        assertTrue("Deletes 1 shot" in report.message, report.message)
        assertTrue(VmHarness.ownState(vm).history.none { it.id == "shot:android-1" }, "nothing ran yet")
        vm.dismissRestore()
        VmHarness.idle()
        assertNull(vm.ui.value.restoreReport)
        assertTrue(VmHarness.ownState(vm).history.none { it.id == "shot:android-1" })
    }

    @Test
    fun aCleanMergeRunsAtOnceAndSaysWhatItAdded() {
        val vm = vm()
        val before = vm.ui.value.userMessages.size
        vm.startRestore(fixture.readBytes(), "fixture", MainViewModel.RestoreMode.MERGE)
        VmHarness.idleUntil("the restore") { vm.ui.value.userMessages.size > before }
        assertNull(vm.ui.value.restoreReport)
        assertTrue(vm.ui.value.userMessages.last().startsWith("Restored 1 shot, 1 bean"), vm.ui.value.userMessages.last())
        assertTrue(VmHarness.ownState(vm).history.any { it.id == "shot:android-1" })
        assertEquals(coffee.crema.restore.RestoreState.IDLE, vm.restoreState())
    }

    @Test
    fun stepSevenKeepsAShotAndAnEditMadeWhileItsSeriesLand() {
        SafeFile.openAllForTest()
        runBlocking { HistoryStore(app, coffee.crema.ui.coreJson).save(listOf(shot("mine", 1_700_000_100_000))) }
        val vm = vm()
        vm.afterRestoreSeries = {
            // This device goes on while the series land.
            vm.updateShot("mine", 5, "edited meanwhile")
            vm.addManualBrew(
                LibraryController.ManualBrewInput(
                    method = "pourover", completedAtMs = 1_700_000_200_000, beanId = null, doseG = 15f, waterG = 250f,
                    yieldG = null, grindSetting = null, brewTempC = null, durationMs = 180_000, rating = null, notes = null, nextPlan = null,
                ),
            )
        }
        val before = vm.ui.value.userMessages.size
        vm.startRestore(fixture.readBytes(), "fixture", MainViewModel.RestoreMode.MERGE)
        VmHarness.idleUntil("the restore") { vm.ui.value.userMessages.size > before }
        val rows = VmHarness.ownState(vm).history
        assertTrue(rows.any { it.id == "shot:android-1" }, "the backup's shot")
        assertEquals(5, rows.first { it.id == "mine" }.rating)
        assertEquals("edited meanwhile", rows.first { it.id == "mine" }.notes)
        assertEquals(3, rows.size, rows.map { it.id }.toString())
    }

    /** The fixture with its first line made unreadable, or a BOM before it. */
    private fun damaged(bom: Boolean): ByteArray {
        val lines = fixture.readText().lines()
        val text = if (bom) "\uFEFF" + lines.joinToString("\n") else (listOf("{ not a header") + lines.drop(1)).joinToString("\n")
        return text.toByteArray()
    }

    @Test
    fun aDamagedHeaderOrABomStillMergesAndReplaceRefuses() {
        for (bom in listOf(false, true)) {
            store.clear(); VmHarness.idle(); store = ViewModelStore()
            SafeFile.resetForTest(); Stores.resetForTest(); SaveFloor.resetForTest()
            val vm = vm()
            val before = vm.ui.value.userMessages.size
            vm.startRestore(damaged(bom), "damaged", MainViewModel.RestoreMode.WIPE)
            VmHarness.idleUntil("the refusal") { vm.ui.value.userMessages.size > before }
            assertTrue("damaged" in vm.ui.value.userMessages.last(), "bom=$bom: ${vm.ui.value.userMessages.last()}")
            vm.startRestore(damaged(bom), "damaged", MainViewModel.RestoreMode.MERGE)
            VmHarness.idleUntil("the report") { vm.ui.value.restoreReport != null }
            vm.confirmRestore()
            VmHarness.idleUntil("the merge") { VmHarness.ownState(vm).history.any { it.id == "shot:android-1" } }
        }
    }

    @Test
    fun aReplaceStoppedBeforeTheEndLeavesTheRemovedCurveToItsMarker() {
        SafeFile.openAllForTest()
        val curve = listOf(TelemetrySample(elapsedMs = 0, pressure = 9f, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = null, weightFlow = null, dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f, setGroupFlow = 0f, frameNumber = 1))
        runBlocking { HistoryStore(app, coffee.crema.ui.coreJson).save(listOf(shot("old", 1_700_000_100_000).copy(samples = curve))) }
        val series = File(app.filesDir, "shot-series").listFiles().orEmpty().map { it.name }
        assertTrue(series.isNotEmpty(), "old's curve is on disk")
        val vm = vm()
        vm.restoreEngine.failAt = coffee.crema.restore.RestoreStep.SETTINGS
        val before = vm.ui.value.userMessages.size
        vm.startRestore(fixture.readBytes(), "fixture", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the report") { vm.ui.value.restoreReport != null }
        vm.confirmRestore()
        VmHarness.idleUntil("the stop") { vm.ui.value.userMessages.size > before }
        assertTrue(vm.ui.value.userMessages.last().startsWith("Restore stopped"), vm.ui.value.userMessages.last())
        assertTrue(VmHarness.ownState(vm).history.none { it.id == "old" }, "the rows were replaced")
        assertEquals(series, File(app.filesDir, "shot-series").listFiles().orEmpty().map { it.name }.filter { it in series }, "no save deleted the removed row's curve")
    }

    @Test
    fun aPhotoTheMarkerOwesWaitsForTheBeanLibraryToLoad() {
        File(app.filesDir, "beanLibrary.json").writeText("not json")
        val photo = coffee.crema.beans.BeanImageStore.beanImageFile(app, "b9").also { it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(1, 2)) }
        coffee.crema.restore.RestoreMarkerStore(File(app.filesDir, coffee.crema.restore.RestoreMarkerStore.FILE_NAME), coffee.crema.ui.coreJson).apply {
            assertTrue(begin(coffee.crema.restore.RestoreKind.REPLACE, "x", coffee.crema.restore.MarkerDeletes(photos = listOf("b9")), 1))
            assertTrue(finishing())
        }
        vm()
        assertTrue(photo.exists(), "a library that didn't load can't say b9 is gone")
        assertTrue(File(app.filesDir, coffee.crema.restore.RestoreMarkerStore.FILE_NAME).exists(), "the marker still owes it")
    }

    @Test
    fun aFreshInstallHasNothingToBackUp() {
        val vm = vm()
        assertNull(runBlocking { vm.buildBackupZipBytes() })
        assertTrue("Nothing to back up yet" in vm.ui.value.userMessages, vm.ui.value.userMessages.toString())
    }

    @Test
    fun noBeanIsPickedForYouAtLaunch() {
        SafeFile.openAllForTest()
        runBlocking {
            LibraryStore(app, coffee.crema.ui.coreJson).let {
                it.save(BeanLibrary(listOf(coffee.crema.core.Bean(id = "b1", name = "One", metadata = kotlinx.serialization.json.JsonNull, createdAt = 0L, updatedAt = 0L)), emptyList(), null))
            }
        }
        val vm = vm()
        assertEquals(listOf("b1"), VmHarness.ownState(vm).beans.map { it.id })
        assertNull(VmHarness.ownState(vm).activeBeanId)
    }
}
