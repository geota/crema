package coffee.crema.ui

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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #310 P3a's fallbacks on the real ViewModel (#408 review S8): a move that
 * failed runs on the legacy backend until Try again succeeds mid-session, and
 * a lost store comes back by each of the banner's choices. Each time every
 * row and curve is there, once, the shots made meanwhile included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistoryFallbackVmTest {
    private var store = ViewModelStore()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val shotsJson get() = File(app.filesDir, HistoryStore.FILE_NAME)

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
        HistoryStore.freeSpaceForTest = null
        runBlocking { HistoryStore(app, coreJson).erase() }
        File(app.filesDir, "shot-series").deleteRecursively()
    }

    @After fun tearDown() {
        HistoryStore.freeSpaceForTest = null
        store.clear()
        VmHarness.idle()
    }

    private fun vm(): MainViewModel = VmHarness.build(store).also { VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }

    /** A new launch: the old ViewModel goes, a new one loads. */
    private fun relaunch(): MainViewModel {
        store.clear()
        VmHarness.idle()
        store = ViewModelStore()
        SafeFile.resetForTest()
        return vm()
    }

    private fun planted(n: Int) = HistoryFixtures.rows(n, samplesPer = 5).map { it.copy(pinned = false) }

    private fun made(id: String, at: Long, seed: Int) =
        StoredShot(id = id, completedAtMs = at, durationMs = 30_000, profileName = "P", samples = HistoryFixtures.samples(6, seed))

    /** Every row and curve [vm] holds is [expected]'s, each once. */
    private fun assertHolds(vm: MainViewModel, expected: List<StoredShot>, what: String) {
        val library = VmHarness.library(vm)
        VmHarness.idleUntil("$what: the rows") { library.rowsReady }
        val ids = library.allShots().map { it.id }
        assertEquals(ids.distinct().size, ids.size, "$what: no row twice")
        assertEquals(expected.map { it.id }.toSet(), ids.toSet(), what)
        // Each curve, read whole as a screen would (#310 P4a: a row holds none).
        for (row in VmHarness.rows(vm)) {
            assertEquals(expected.first { it.id == row.id }.samples, VmHarness.whole(vm, row.id).samples, "$what: ${row.id}'s curve")
        }
    }

    @Test
    fun aFailedMoveRunsOnLegacyAndTryAgainMidSessionBringsEveryRowAndCurveOnce() {
        val rows = planted(6)
        HistoryFixtures.plantOldBuild(app, rows)
        // No space: the move fails, and the legacy backend runs.
        HistoryStore.freeSpaceForTest = { 0L }
        val vm = vm()
        assertEquals(HistoryHealth.LEGACY, vm.ui.value.historyHealth)
        val library = VmHarness.library(vm)
        val legacyShot = made("made-on-legacy", rows.first().completedAtMs + 1_000, 3)
        library.insertPulledShots(listOf(legacyShot))
        VmHarness.idleUntil("the legacy save") { shotsJson.readText().contains(legacyShot.id) }

        // Space is back: Settings ▸ Try again, mid-session.
        HistoryStore.freeSpaceForTest = null
        vm.retryHistory()
        VmHarness.idleUntil("the move") { vm.ui.value.historyHealth == HistoryHealth.OK }
        val expected = rows + legacyShot
        assertHolds(vm, expected, "after Try again")
        assertHolds(relaunch(), expected, "after a relaunch")
    }

    /** A migrated store with a shot saved after the move (so the day's snapshot holds it), then lost. */
    private fun lostStore(): Pair<List<StoredShot>, StoredShot> {
        val rows = planted(4)
        HistoryFixtures.plantOldBuild(app, rows)
        val vm = vm()
        assertEquals(HistoryHealth.OK, vm.ui.value.historyHealth)
        val since = made("since-the-move", rows.first().completedAtMs + 1_000, 4)
        VmHarness.library(vm).insertPulledShots(listOf(since))
        VmHarness.idleUntil("the save and its snapshot") { File(app.filesDir, HistoryStore.SNAPSHOT_NAME).exists() }
        store.clear()
        VmHarness.idle()
        app.deleteDatabase(HistoryStore.NEXT_DB_NAME)
        return rows to since
    }

    private fun lostThen(choice: LibraryController.LostChoice, expected: (rows: List<StoredShot>, since: StoredShot) -> List<StoredShot>) {
        val (rows, since) = lostStore()
        val vm = relaunch()
        assertEquals(HistoryHealth.LOST, vm.ui.value.historyHealth)
        // A shot made while the store is lost: in memory, the journal and its series file.
        val whileLost = made("made-while-lost", rows.first().completedAtMs + 2_000, 5)
        VmHarness.library(vm).insertPulledShots(listOf(whileLost))
        VmHarness.idleUntil("the journal") { File(app.filesDir, HistoryStore.PENDING_NAME).exists() }

        vm.resolveLostHistory(choice)
        VmHarness.idleUntil("$choice") { vm.ui.value.historyHealth == HistoryHealth.OK }
        val want = expected(rows, since) + whileLost
        assertHolds(vm, want, "$choice")
        assertTrue(!File(app.filesDir, HistoryStore.PENDING_NAME).exists(), "$choice: the journal merged")
        assertHolds(relaunch(), want, "$choice, after a relaunch")
    }

    @Test
    fun aLostStoreComesBackFromTheSnapshotWithTheShotMadeMeanwhile() =
        lostThen(LibraryController.LostChoice.SNAPSHOT) { rows, since -> rows + since }

    @Test
    fun aLostStoreComesBackFromTheOldCopyWithTheShotMadeMeanwhile() =
        // The old copy is the history as it was at the move: a shot since then isn't in it.
        lostThen(LibraryController.LostChoice.OLD_COPY) { rows, _ -> rows }

    @Test
    fun aLostStoreStartsEmptyWithTheShotMadeMeanwhile() =
        lostThen(LibraryController.LostChoice.START_EMPTY) { _, _ -> emptyList() }
}
