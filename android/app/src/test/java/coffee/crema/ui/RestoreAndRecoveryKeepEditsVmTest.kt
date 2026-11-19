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
 * REVIEW-470 on the real ViewModel and `shots.db` (#310 A3b). A restore's
 * merge batches write each row as memory holds it when the batch runs, so an
 * upload stamp, an edit or a delete made after the settle stands (B1; a lost
 * stamp would be a second upload). And after a mid-session loss, a snapshot
 * or the old copy doesn't win over memory's newer version of a row (B2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RestoreAndRecoveryKeepEditsVmTest {
    /** P3a's `shots.db` read directly: stage 1, a history that hasn't flipped (#310 A5). */
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


    // ── P1: an edit or a stamp made during a restore's merge batches ─────────

    private fun backupOf(n: Int): ByteArray {
        val rows = plant(n).map { if (it.id == "s1") it.copy(notes = "theirs") else it } +
            StoredShot(id = "b-new", completedAtMs = now + 60_000L, durationMs = 28_000)
        SafeFile.openAllForTest()
        runBlocking { assertTrue(HistoryStore(app, coreJson).save(rows)) }
        val source = vm()
        val lib = VmHarness.library(source)
        VmHarness.idleUntil("the rows") { lib.rowsReady }
        val zip = runBlocking { lib.buildBackupZip(BackupKind.FILE) }!!
        val bytes = zip.file.readBytes()
        zip.release()
        store.clear(); VmHarness.idle(); store = ViewModelStore()
        runBlocking { HistoryStore(app, coreJson).erase() }
        return bytes
    }

    private fun stampDuringRestore(mode: MainViewModel.RestoreMode) {
        val bytes = backupOf(3)
        plant(3)
        val vm = vm()
        val library = VmHarness.library(vm)
        // An upload pass binds s2 (or the user rates it) after step 7's settle, before its batch lands.
        var ran = false
        library.beforeRestoreRows = {
            ran = true
            library.markShotSynced("s2", "vis-2")
            library.updateShot("s0", 4, "rated during the restore")
        }
        try {
            val before = vm.ui.value.userMessages.count { it.startsWith("Restored ") }
            vm.startRestore(bytes, "backup", mode)
            if (mode == MainViewModel.RestoreMode.WIPE) {
                VmHarness.idleUntil("the report") { vm.ui.value.restoreReport != null }
                vm.confirmRestore()
            }
            VmHarness.idleUntil("the restore") { vm.ui.value.userMessages.count { it.startsWith("Restored ") } > before }
            VmHarness.idle()
            VmHarness.advance(2_000)
            VmHarness.idle()
        } finally {
            library.beforeRestoreRows = null
        }
        assertTrue(ran, "the seam ran: step 7 settled")
        assertEquals("b-new" in library.allShots().map { it.id }, true, "the restore landed")
        val memVid = library.findShot("s2")?.visualizerId
        val memRating = library.findShot("s0")?.rating
        val dbVid = dbField("s2", "visualizerId")
        val dbRating = dbField("s0", "rating")
        assertEquals("vis-2", memVid, "the stamp stands in memory: else the next upload pass sends s2 again")
        assertEquals(4, memRating, "the rating stands in memory")
        assertEquals("vis-2", dbVid, "the stamp is in shots.db")
        assertEquals("4", dbRating, "the rating is in shots.db")
    }

    @Test fun `a merge restore keeps a stamp and an edit made during its batches`() = stampDuringRestore(MainViewModel.RestoreMode.MERGE)

    @Test fun `a replace restore keeps a stamp and an edit made during its batches`() = stampDuringRestore(MainViewModel.RestoreMode.WIPE)


    private fun deleteDuringRestore(mode: MainViewModel.RestoreMode) {
        val bytes = backupOf(3)
        plant(3)
        val vm = vm()
        val library = VmHarness.library(vm)
        var ran = false
        // The user deletes s2 after step 7's settle, and the delete lands before s2's batch.
        library.beforeRestoreRows = {
            ran = true
            vm.deleteShot("s2")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                var waited = 0
                while (dbField("s2", "id") != null || library.findShot("s2") != null) {
                    Thread.sleep(20); waited += 20
                    if (waited > 5_000) break
                }
            }
        }
        try {
            val before = vm.ui.value.userMessages.count { it.startsWith("Restored ") }
            vm.startRestore(bytes, "backup", mode)
            if (mode == MainViewModel.RestoreMode.WIPE) {
                VmHarness.idleUntil("the report") { vm.ui.value.restoreReport != null }
                vm.confirmRestore()
            }
            VmHarness.idleUntil("the restore") { vm.ui.value.userMessages.count { it.startsWith("Restored ") } > before }
            VmHarness.idle()
        } finally {
            library.beforeRestoreRows = null
        }
        assertTrue(ran, "the seam ran")
        val inDb = dbField("s2", "id") != null
        val inMem = library.findShot("s2") != null
        assertEquals(false, inMem, "a deleted row stays deleted in memory")
        assertEquals(false, inDb, "a deleted row stays deleted in shots.db")
    }

    @Test fun `a merge restore doesn't bring back a row deleted during its batches`() = deleteDuringRestore(MainViewModel.RestoreMode.MERGE)

    // ── P2: a mid-session loss, then the banner's choices ────────────────────

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
