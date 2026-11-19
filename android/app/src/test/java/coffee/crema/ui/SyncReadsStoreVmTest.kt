package coffee.crema.ui

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.MainSources
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #310 A3a on the real ViewModel and `shots.db`: sync and upload read the
 * history's store, never the view. A row the view doesn't hold (as A3b's
 * windowed view will leave most rows) is still in the backlog, a pull still
 * finds it, and an upload still binds it, and the bind reads as "still here",
 * so no tombstone goes up for a live shot (P3 spec, #354 review). An edit is
 * what sync reads the moment it's made, before its write lands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SyncReadsStoreVmTest {
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

    /** Machine shots with no curve: each counts as "no telemetry" for Visualizer. */
    private fun plant(n: Int): List<StoredShot> {
        val rows = List(n) { i -> StoredShot(id = "s$i", completedAtMs = now - i * 60_000L, durationMs = 28_000) }
        SafeFile.openAllForTest()
        runBlocking { assertTrue(HistoryStore(app, coreJson).save(rows)) }
        return rows
    }

    private fun dbField(id: String, key: String): String? {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return db.rawQuery("SELECT row_json FROM shots WHERE id = ?", arrayOf(id)).use { c ->
                if (!c.moveToFirst()) null else coreJson.parseToJsonElement(c.getString(0)).jsonObject[key]?.jsonPrimitive?.content
            }
        } finally {
            db.close()
        }
    }

    private fun dbCount(): Int {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return db.rawQuery("SELECT COUNT(*) FROM shots", null).use { c -> c.moveToFirst(); c.getInt(0) }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a row the view doesn't hold is still synced, bound, and not gone`() {
        plant(5)
        val vm = vm()
        val library = VmHarness.library(vm)
        VmHarness.idleUntil("the counts") { vm.ui.value.sharing.noTelemetryShots == 5 }
        // From A3b no view holds any row: the UI state has no list (#310 A3b).
        assertTrue(library.syncRows().any { it.id == "s3" }, "the store does: sync's rows have it")
        assertEquals(5, vm.ui.value.sharing.noTelemetryShots, "Sharing's counts are the store's")
        // An upload came back for it: the stamp lands where no view reaches.
        library.markShotSynced("s3", "vis-3")
        assertEquals("vis-3", library.syncShot("s3")?.visualizerId, "bound, and still here: no tombstone")
        VmHarness.idleUntil("the stamp") { dbField("s3", "visualizerId") == "vis-3" }
        VmHarness.idleUntil("the count") { vm.ui.value.sharing.noTelemetryShots == 4 }
    }

    @Test
    fun `an edit is what sync reads at once, before its write lands`() {
        plant(3)
        val vm = vm()
        val library = VmHarness.library(vm)
        library.markShotSynced("s1", "vis-1")
        // No idle: the save is launched, not run.
        assertEquals("vis-1", library.syncShot("s1")?.visualizerId)
        assertEquals("vis-1", library.syncRows().first { it.id == "s1" }.visualizerId)
        VmHarness.idleUntil("the stamp") { dbField("s1", "visualizerId") == "vis-1" }
        assertEquals("vis-1", library.syncShot("s1")?.visualizerId)
    }

    @Test
    fun `a deleted row leaves sync's rows at once`() {
        plant(3)
        val vm = vm()
        val library = VmHarness.library(vm)
        vm.deleteShot("s0")
        assertEquals(null, library.syncShot("s0"))
        assertFalse(library.syncRows().any { it.id == "s0" })
        VmHarness.idleUntil("the delete") { dbCount() == 2 }
    }

    @Test
    fun `a pull of a row only the store holds adds no copy`() {
        val planted = plant(4)
        val vm = vm()
        val library = VmHarness.library(vm)
        val refused = library.insertPulledShots(listOf(planted.first { it.id == "s2" }.copy(notes = "pulled again")))
        VmHarness.idle()
        assertTrue(refused.isEmpty())
        assertEquals(4, dbCount())
        assertEquals(4, library.syncRows().size)
        assertEquals(null, dbField("s2", "notes"), "the stored row is untouched")
    }

    @Test
    fun `a Replace restore's bound rows are what sync reads before their saves land`() {
        // REVIEW-467 N3. The backup, from another device: s1 is on Visualizer there.
        val theirs = plant(3).map { if (it.id == "s1") it.copy(visualizerId = "vis-1", notes = "theirs") else it }
        SafeFile.openAllForTest()
        runBlocking { assertTrue(HistoryStore(app, coreJson).save(theirs)) }
        val source = vm()
        val sourceLib = VmHarness.library(source)
        VmHarness.idleUntil("the rows") { sourceLib.rowsReady }
        val zip = runBlocking { sourceLib.buildBackupZip(BackupKind.FILE) }!!
        val bytes = zip.file.readBytes()
        zip.release()

        // This device: the same shots, s1 not uploaded from here.
        store.clear(); VmHarness.idle(); store = ViewModelStore()
        runBlocking { HistoryStore(app, coreJson).erase() }
        plant(3)
        val vm = vm()
        val library = VmHarness.library(vm)
        assertEquals(null, library.syncShot("s1")?.visualizerId)
        var seen: String? = "unread"
        library.beforeRestoreRows = { seen = library.syncShot("s1")?.visualizerId }
        try {
            vm.startRestore(bytes, "backup", MainViewModel.RestoreMode.WIPE)
            VmHarness.idleUntil("the report") { vm.ui.value.restoreReport != null }
            vm.confirmRestore()
            VmHarness.idleUntil("the restore") { dbField("s1", "visualizerId") == "vis-1" }
        } finally {
            library.beforeRestoreRows = null
        }
        assertEquals("vis-1", seen, "bound before its batch is saved: an upload pass can't send it again")
        assertEquals("vis-1", library.syncShot("s1")?.visualizerId)
    }

    /** The sync and upload consumers name no view (#310 A3a): a new one can't slip back to it. */
    @Test
    fun `the sync and upload consumers don't read the view`() {
        val files = MainSources.kotlinFiles().toMap()
        val view = Regex("""(?<![\w.])(ui|uiFlow\.value|_ui\.value|uiState\(\)|it|s|i|st|state)\.history\b""")
        for (path in listOf(
            "coffee/crema/visualizer/VisualizerSync.kt",
            "coffee/crema/visualizer/WireShot.kt",
            "coffee/crema/decent/DecentSync.kt",
            "coffee/crema/ui/SharingController.kt",
        )) {
            val hits = files.getValue(path).lineSequence().withIndex().filter { (_, l) -> view.containsMatchIn(l) }.map { (i, l) -> "${i + 1}: ${l.trim()}" }.toList()
            assertTrue(hits.isEmpty(), "$path reads the view:\n${hits.joinToString("\n")}")
        }
        // The sync controllers are wired to the store's reads, not the view's.
        val wiring = files.getValue("coffee/crema/ui/MainViewModel.kt")
        for (bad in listOf("viewShots = { _ui.value.history }", "allShots = { library.allShots() }", "currentShot = { id -> library.findShot(id) }")) {
            assertFalse(bad in wiring, "MainViewModel wires sync to the view: $bad")
        }
        for (screen in listOf("coffee/crema/ui/screens/SettingsScreen.kt", "coffee/crema/ui/phone/PhoneSettingsScreen.kt", "coffee/crema/ui/screens/SettingsConfirmDialogs.kt")) {
            assertFalse(Regex("""visualizer\.\w+\(ui\.history""").containsMatchIn(files.getValue(screen)), "$screen hands sync the view")
        }
    }
}
