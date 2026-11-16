package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #310 A5b on the real ViewModel: the app's launch flips a stage-1 history to
 * `history.db`, and from then on every write the app makes (an upload's
 * stamp, an edit, a delete, a new shot) lands there, survives a relaunch,
 * and never touches `shots.db`, the old copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class Stage2VmTest {
    private var store = ViewModelStore()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val shotsDb get() = app.getDatabasePath(HistoryStore.NEXT_DB_NAME)
    private val now = 1_790_000_000_000L

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
        runBlocking { HistoryStore(app, coreJson).erase() }
    }

    @After fun tearDown() {
        store.clear()
        VmHarness.idle()
        HistoryStore.stage2Enabled = HistoryStore.STAGE2_IN_BUILD
    }

    private fun vm(): MainViewModel = VmHarness.build(store).also { VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }

    /** A launch of the app again: the view model and its store made anew. */
    private fun relaunch(): MainViewModel {
        store.clear()
        VmHarness.idle()
        store = ViewModelStore()
        return vm()
    }

    /** A stage-1 install with [n] shots in `shots.db`, as an update to this build finds it. */
    private fun stage1(n: Int): List<StoredShot> {
        HistoryStore.stage2Enabled = false
        val rows = List(n) { i -> StoredShot(id = "s$i", completedAtMs = now - i * 60_000L, durationMs = 28_000, notes = "shot $i") }
        SafeFile.openAllForTest()
        val s = HistoryStore(app, coreJson)
        runBlocking {
            s.loadAll()
            assertTrue(s.save(rows))
            s.closeForTest()
        }
        HistoryStore.stage2Enabled = true
        return rows
    }

    private fun shotsDbBytes(): List<ByteArray?> = listOf("", "-wal").map { File(shotsDb.path + it).takeIf(File::exists)?.readBytes() }

    @Test
    fun `the launch flips to history-db, and every write after lands there and lasts`() {
        stage1(6)
        val vm = vm()
        val library = VmHarness.library(vm)
        val history = library.historyStoreForTest
        assertTrue(history.onHistoryDb, "flipped at launch: ${history.flipError}")
        assertEquals(HistoryHealth.OK, vm.ui.value.historyHealth)
        assertEquals(6, VmHarness.rows(vm).size)
        val old = shotsDbBytes()
        // An upload's stamp, an edit and a delete, as the app makes them.
        library.markShotSynced("s2", "vis-2")
        VmHarness.idleUntil("the stamp in history.db") { history.truthRowJsonForTest("s2")?.contains("vis-2") == true }
        library.deleteShot("s4")
        VmHarness.idleUntil("the delete in history.db") { history.truthRowJsonForTest("s4") == null && VmHarness.rows(vm).none { it.id == "s4" } }
        assertEquals(old.map { it?.toList() }, shotsDbBytes().map { it?.toList() }, "shots.db is the old copy: never written")
        // Relaunched: history.db straight away, with each write.
        val again = relaunch()
        val h2 = VmHarness.library(again).historyStoreForTest
        assertTrue(h2.onHistoryDb)
        assertNull(h2.flipError)
        val rows = VmHarness.rows(again)
        assertEquals(listOf("s0", "s1", "s2", "s3", "s5"), rows.map { it.id })
        assertEquals("vis-2", rows.first { it.id == "s2" }.visualizerId)
        assertEquals(old.map { it?.toList() }, shotsDbBytes().map { it?.toList() })
    }

    @Test
    fun `a backup to a file walks history-db, and a restore of it on a fresh install flips back the same rows`() {
        stage1(5)
        val vm = vm()
        val library = VmHarness.library(vm)
        assertTrue(library.historyStoreForTest.onHistoryDb)
        val zip = assertNotNull(runBlocking { library.buildBackupZipFile() }, "a backup")
        val ids = VmHarness.rows(vm).map { it.id }.toSet()
        // Erase all data, then restore the backup: every row back, on history.db.
        runBlocking { library.historyStoreForTest.erase() }
        val fresh = relaunch()
        assertTrue(VmHarness.library(fresh).historyStoreForTest.onHistoryDb, "a fresh install flips an empty store")
        val before = fresh.ui.value.userMessages.size
        fresh.startRestore(zip.readBytes(), "backup", MainViewModel.RestoreMode.MERGE)
        VmHarness.idleUntil("the report") { fresh.ui.value.restoreReport != null || fresh.ui.value.userMessages.size > before }
        fresh.confirmRestore()
        VmHarness.idleUntil("the restore") { VmHarness.rows(fresh).map { it.id }.toSet() == ids }
        val h = VmHarness.library(fresh).historyStoreForTest
        VmHarness.idleUntil("written to history.db") { ids.all { h.truthRowJsonForTest(it) != null } }
        assertFalse(VmHarness.rows(relaunch()).map { it.id }.toSet() != ids, "and they last")
    }
}
