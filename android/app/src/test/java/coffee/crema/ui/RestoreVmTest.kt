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
