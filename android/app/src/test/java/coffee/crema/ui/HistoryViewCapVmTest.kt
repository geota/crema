package coffee.crema.ui

import coffee.crema.history.withHeavy
import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.history.HistoryFixtures
import coffee.crema.history.HistoryHealth
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #310 P3a on the real ViewModel, and from P6a with no cap: the store keeps
 * every row, the view now shows them all, and nothing reads "not in view" as
 * "gone". 5,000 rows save and delete none; a pull of a stored shot adds no
 * copy; a backup holds every row; a shot made before the rows land survives;
 * a wipe restore merges, then removes by name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistoryViewCapVmTest {
    /** The ViewModel the test runs now: the helpers read its store's truth file. */
    private var current: MainViewModel? = null

    private var store = ViewModelStore()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
        runBlocking { HistoryStore(app, coreJson).erase() }
    }

    @After fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    private fun vm(): MainViewModel = VmHarness.build(store).also { current = it; VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }

    /** A new launch: the old ViewModel goes, a new one loads. */
    private fun relaunch(): MainViewModel {
        store.clear()
        VmHarness.idle()
        store = ViewModelStore()
        SafeFile.resetForTest()
        return vm()
    }

    /** Rows planted in the store as a previous launch left them. */
    private fun plant(rows: List<StoredShot>) {
        SafeFile.openAllForTest()
        runBlocking { assertTrue(HistoryStore(app, coreJson).save(rows)) }
    }

    private fun dbIds(): Set<String> = VmHarness.truthIds(checkNotNull(current))

    private fun dbField(id: String, key: String): String? = VmHarness.truthField(checkNotNull(current), id, key)

    private fun rows(n: Int, samplesPer: Int = 0) = HistoryFixtures.rows(n, samplesPer).map { it.copy(pinned = false) }

    @Test
    fun fiveThousandRowsLoadInViewSaveAndAllRowsRemain() {
        val planted = rows(5_000)
        plant(planted)
        val vm = vm()
        val library = VmHarness.library(vm)
        assertEquals(5_000, VmHarness.rows(vm).size, "no cap from P6a: every row is in view")
        assertEquals(5_000, library.allShots().size, "the store keeps every row")
        assertEquals(HistoryHealth.OK, vm.ui.value.historyHealth)
        // A star tap and a new shot: the save is a diff, never a list that replaces the store.
        val top = VmHarness.rows(vm).first().id
        vm.updateShot(top, 4, "tapped")
        vm.addManualBrew(
            LibraryController.ManualBrewInput(
                method = "espresso", completedAtMs = planted.first().completedAtMs + 1, beanId = null, doseG = 18f, waterG = null,
                yieldG = 36f, grind = null, brewTempC = null, durationMs = 28_000, rating = null, notes = null, nextPlan = null,
            ),
        )
        VmHarness.idleUntil("the saves") { dbIds().size == 5_001 && dbField(top, "rating") == "4" }
        val next = relaunch()
        assertEquals(5_001, VmHarness.library(next).allShots().size, "all rows remain after a reload")
        assertEquals(5_001, VmHarness.rows(next).size)
    }

    @Test
    fun aPullOfAStoredShotAddsNoCopy() {
        plant(rows(320))
        val vm = vm()
        val library = VmHarness.library(vm)
        val hidden = library.allShots().last()
        assertTrue(VmHarness.rows(vm).any { it.id == hidden.id }, "row 320, in view from P6a")
        val refused = library.insertPulledShots(listOf(hidden.withHeavy(emptyList(), null, null).copy(notes = "pulled again")))
        VmHarness.idle()
        assertTrue(refused.isEmpty(), "it's here, so not refused either")
        assertEquals(320, library.allShots().size)
        assertEquals(320, dbIds().size)
        assertEquals(hidden.notes, library.findShot(hidden.id)?.notes, "the stored row is untouched")
    }

    @Test
    fun markingAnOldShotSyncedStoresItsVisualizerId() {
        plant(rows(305).map { it.copy(visualizerId = null) })
        val vm = vm()
        val library = VmHarness.library(vm)
        val hidden = library.allShots().last()
        library.markShotSynced(hidden.id, "vis-new")
        // At once, as bindUploaded reads it right after: not "gone", so no tombstone.
        assertEquals("vis-new", library.findShot(hidden.id)?.visualizerId, "not gone: bindUploaded finds it bound")
        VmHarness.idleUntil("the stamp") { dbField(hidden.id, "visualizerId") == "vis-new" }
        // Its profile, loaded light, stays in the store.
        val back = runBlocking { HistoryStore(app, coreJson).load() }.first { it.id == hidden.id }
        assertEquals(rows(305).first { it.id == hidden.id }.profile, back.profile)
    }

    /** The backfill guard (SPEC §4) on an old row: a light row's curve is on disk, not "missing". */
    @Test
    fun aPullOntoAnOldShotKeepsItsCurveAndProfile() {
        val planted = rows(305, samplesPer = 8)
        plant(planted)
        val vm = vm()
        val library = VmHarness.library(vm)
        VmHarness.idleUntil("the series") { library.rowsReady }
        val hidden = library.allShots().drop(HistoryStore.LEGACY_MAX_SHOTS).first { id -> planted.first { it.id == id.id }.samples.isNotEmpty() && planted.first { it.id == id.id }.profile != null }
        val original = planted.first { it.id == hidden.id }
        library.applyRemoteShot(
            hidden.id,
            coffee.crema.core.ShotPatch(tags = listOf("from-visualizer"), backfillSamples = true, fillProfile = true, remoteVersion = 1_800_000_000L),
            coffee.crema.visualizer.PulledParts(samples = HistoryFixtures.samples(3, 99), durationMs = 1, profile = HistoryFixtures.profile(99)),
        )
        VmHarness.idleUntil("the pull's version") { dbField(hidden.id, "visualizerUpdatedAt") == "1800000000" }
        val back = runBlocking { HistoryStore(app, coreJson).load() }.first { it.id == hidden.id }
        assertEquals(listOf("from-visualizer"), back.tags)
        assertEquals(original.samples, back.samples, "its own curve, not the pulled one")
        assertEquals(original.profile, back.profile, "its own profile: fill only")
    }

    @Test
    fun aBackupHoldsEveryRowWithItsCurve() {
        val planted = rows(320, samplesPer = 20)
        plant(planted)
        val vm = vm()
        val library = VmHarness.library(vm)
        VmHarness.idleUntil("the series") { library.rowsReady }
        val zip = assertNotNull(VmHarness.onMain("the backup") { library.buildBackupZip(BackupKind.FILE) })
        try {
            assertEquals(320u, zip.counts.shots, "every row, past the cap too")
            assertEquals(planted.count { it.samples.isNotEmpty() }.toUInt(), zip.counts.curves, "with its curve")
            assertTrue(planted.drop(HistoryStore.LEGACY_MAX_SHOTS).any { it.samples.isNotEmpty() }, "rows past 300 have curves")
        } finally {
            zip.release()
        }
    }

    @Test
    fun aShotMadeBeforeTheRowsLandSurvivesTheLoad() {
        val planted = rows(3)
        plant(planted)
        var vm: MainViewModel? = null
        VmHarness.holdingStartupLoad { release ->
            vm = VmHarness.build(store).also { current = it }
            VmHarness.idle()
            assertFalse(vm!!.ui.value.historyLoaded, "History shows a skeleton, not the empty state")
            vm!!.addManualBrew(
                LibraryController.ManualBrewInput(
                    method = "pourover", completedAtMs = planted.first().completedAtMs + 10, beanId = null, doseG = 15f, waterG = 250f,
                    yieldG = null, grind = null, brewTempC = null, durationMs = 180_000, rating = null, notes = "made early", nextPlan = null,
                ),
            )
            VmHarness.idle()
            release.complete(Unit)
        }
        VmHarness.awaitStartupLoaded(vm!!)
        val history = VmHarness.rows(vm!!)
        assertTrue(vm!!.ui.value.historyLoaded)
        assertEquals(4, history.size, history.map { it.id }.toString())
        assertTrue(history.any { it.notes == "made early" })
        VmHarness.idleUntil("the save") { dbIds().size == 4 }
    }

    @Test
    fun theRowsLoadOffTheStartupChainAndHistoryWaitsForThem() {
        plant(rows(3))
        val gate = CompletableDeferred<Unit>()
        HistoryStore.holdOpen = gate
        try {
            val vm = VmHarness.build(store)
            // A slow move of the rows doesn't hold up the prefs.
            VmHarness.idleUntil("the prefs") { VmHarness.prefsLoaded(vm) }
            assertFalse(vm.historyLoad.isCompleted)
            assertFalse(vm.ui.value.historyLoaded, "History shows its skeleton")
            assertTrue(VmHarness.rows(vm).isEmpty())
            gate.complete(Unit)
            VmHarness.idleUntil("the rows") { vm.ui.value.historyLoaded }
            assertEquals(3, VmHarness.rows(vm).size)
        } finally {
            HistoryStore.holdOpen = null
            gate.complete(Unit)
        }
    }

    @Test
    fun aWipeRestoreMergesThenRemovesByNameAndAKillBetweenLeavesTheUnion() {
        // The backup: 40 shots, 5 of them also on this device.
        val theirs = rows(40, samplesPer = 6)
        plant(theirs)
        val source = vm()
        val sourceLib = VmHarness.library(source)
        VmHarness.idleUntil("the series") { sourceLib.rowsReady }
        val zip = assertNotNull(VmHarness.onMain("the backup") { sourceLib.buildBackupZip(BackupKind.FILE) })
        val bytes = zip.file.readBytes()
        zip.release()

        // This device: 310 rows (10 past the old cap), 5 shared with the backup.
        store.clear(); VmHarness.idle(); store = ViewModelStore()
        runBlocking { HistoryStore(app, coreJson).erase() }
        val mine = HistoryFixtures.rows(305, 0, start = 1_500_000_000_000L).map { it.copy(id = "mine-${it.id}", pinned = false) } + theirs.take(5)
        plant(mine)
        val vm = vm()
        assertEquals(310, VmHarness.library(vm).allShots().size)

        // A kill between the merge and the remove: the process stops there.
        vm.beforeRestoreRemove = { kotlinx.coroutines.awaitCancellation() }
        vm.startRestore(bytes, "backup", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the report") { vm.ui.value.restoreReport != null }
        vm.confirmRestore()
        VmHarness.idleUntil("the merge") { dbIds().size == 345 }
        assertEquals(mine.map { it.id }.toSet() + theirs.map { it.id }, dbIds(), "the union, nothing lost")

        // The next launch: the marker says it didn't finish; a re-run completes it.
        val next = relaunch()
        assertNotNull(next.ui.value.interruptedRestore)
        assertEquals(345, VmHarness.library(next).allShots().size, "the union loads")
        next.startRestore(bytes, "backup", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the report") { next.ui.value.restoreReport != null }
        val restored = VmHarness.restoredCount(next)
        next.confirmRestore()
        VmHarness.idleUntil("the remove") { dbIds() == theirs.map { it.id }.toSet() }
        VmHarness.awaitRestored(next, restored)
        val back = VmHarness.library(next).allShots()
        assertEquals(theirs.map { it.id }.toSet(), back.map { it.id }.toSet(), "exactly the backup's rows")
    }
}
