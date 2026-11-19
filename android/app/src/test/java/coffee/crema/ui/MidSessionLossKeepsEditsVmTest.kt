package coffee.crema.ui

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.history.HistoryHealth
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * REVIEW-470 B2 on the real ViewModel and `shots.db` (#310 A3b): after a
 * mid-session loss, a snapshot or the old copy doesn't win over memory's
 * newer version of a row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MidSessionLossKeepsEditsVmTest {
    /** Pinned to stage 1: these quarantine `shots.db` mid-session, a P3a-only loss stage 2 does not have (#482). */
    @get:org.junit.Rule val stage2 = coffee.crema.history.Stage2Rule(enabled = false)

    private var store = ViewModelStore()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val dbFile get() = app.getDatabasePath(HistoryStore.NEXT_DB_NAME)
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
    }

    private fun vm(): MainViewModel = VmHarness.build(store).also { VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }

    private fun plant(n: Int): List<StoredShot> {
        val rows = List(n) { i -> StoredShot(id = "s$i", completedAtMs = now - i * 60_000L, durationMs = 28_000) }
        SafeFile.openAllForTest()
        runBlocking { assertTrue(HistoryStore(app, coreJson).save(rows)) }
        return rows
    }

    private fun dbField(id: String, key: String): String? {
        if (!dbFile.exists()) return "no db"
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return db.rawQuery("SELECT row_json FROM shots WHERE id = ?", arrayOf(id)).use { c ->
                if (!c.moveToFirst()) null else coreJson.parseToJsonElement(c.getString(0)).jsonObject[key]?.jsonPrimitive?.content
            }
        } finally {
            db.close()
        }
    }

    private fun lostMidSessionThen(choice: LibraryController.LostChoice) {
        // Planting writes today's rows snapshot: s0..s2 as planted.
        plant(3)
        val vm = vm()
        val library = VmHarness.library(vm)
        // Today, after the snapshot: s1 uploaded, s0 rated. Both written.
        library.markShotSynced("s1", "vis-1")
        library.updateShot("s0", 4, "rated today")
        VmHarness.idleUntil("the edits written") { dbField("s1", "visualizerId") == "vis-1" && dbField("s0", "rating") == "4" }
        // The store goes mid-session: the next write finds the file kept aside.
        library.quarantineHistoryForTest(File(dbFile.path + ".corrupt-1"))
        library.setShotPinned("s2", true)
        VmHarness.idleUntil("lost") { library.historyHealthForTest == HistoryHealth.LOST }
        vm.resolveLostHistory(choice)
        VmHarness.idleUntil("$choice") { library.historyHealthForTest == HistoryHealth.OK }
        VmHarness.idle()
        val s0 = library.findShot("s0")
        val s1 = library.findShot("s1")
        val s2 = library.findShot("s2")
        assertEquals(4, s0?.rating, "$choice: today's rating, held in memory, stands")
        assertEquals("vis-1", s1?.visualizerId, "$choice: today's upload stamp, held in memory, stands (else a second upload)")
        assertEquals(true, s2?.pinned, "$choice: the pin stands")
        // Written, too: a relaunch reads them back.
        VmHarness.idleUntil("written") { dbField("s0", "rating") == "4" && dbField("s1", "visualizerId") == "vis-1" }
    }

    @Test fun `the snapshot after a mid-session loss keeps memory's newer rows`() = lostMidSessionThen(LibraryController.LostChoice.SNAPSHOT)

    @Test fun `start empty after a mid-session loss keeps memory's rows`() = lostMidSessionThen(LibraryController.LostChoice.START_EMPTY)
}
