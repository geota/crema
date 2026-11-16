package coffee.crema.history

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import coffee.crema.core.HistoryRole
import coffee.crema.persist.KeptAsideNotices
import coffee.crema.persist.SafeFile
import coffee.crema.ui.coreJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #310 A5, in Robolectric: the flip from `shots.db` to `history.db` (SQLite
 * SPEC §11 stage 2), and the store on `history.db` after it. Framework
 * SQLite for `shots.db`, the core's for the index and `history.db`, in one
 * process, separate files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistoryFlipTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val filesDir get() = app.filesDir
    private val sqliteDir get() = File(filesDir, HistoryStore.SQLITE_DIR)
    private val historyDb get() = File(sqliteDir, "history.db")
    private val marker get() = File(filesDir, HistoryStore.SQLITE_MARKER_NAME)
    private val doneRecord get() = File(sqliteDir, HistoryStore.SQLITE_DONE_NAME)
    private val shotsDb get() = app.getDatabasePath(HistoryStore.NEXT_DB_NAME)
    private val stores = mutableListOf<HistoryStore>()

    private fun store() = HistoryStore(app, coreJson, 7).also { stores += it }

    @Before fun clean() {
        SafeFile.openAllForTest()
        HistoryStore.stage2Enabled = false
        HistoryStore.faults.clear()
        HistoryFlip.FAULTS.clear()
        HistoryIndex.FAULTS.clear()
        runBlocking { store().erase() }
        File(filesDir, "shot-series").deleteRecursively()
        KeptAsideNotices.drain()
    }

    @After fun tidy() {
        HistoryStore.stage2Enabled = false
        HistoryStore.faults.clear()
        HistoryFlip.FAULTS.clear()
        HistoryIndex.FAULTS.clear()
        HistoryFlip.betweenBatchesForTest = null
        HistoryLate.TEST_ALWAYS_READ = false
        HistoryStore.freeSpaceForTest = null
        runBlocking { stores.forEach { it.closeForTest() } }
        SafeFile.resetForTest()
    }

    private fun shots(prefix: String, count: Int, start: Long = 1_800_000_000_000L, samplesPer: Int = 4) =
        HistoryFixtures.rows(count, samplesPer = samplesPer, start = start).mapIndexed { i, s -> s.copy(id = "$prefix-$i") }

    /** A launch as the app runs it: the rows, then the index's boot check. */
    private suspend fun HistoryStore.boot(): HistoryLoad = loadAll().also { syncIndex() }

    /** A stage-1 install holding [rows] in `shots.db`, its index in step, closed as a process exit leaves it. */
    private suspend fun stage1(rows: List<StoredShot>): HistoryStore {
        HistoryStore.stage2Enabled = false
        val s = store()
        s.boot()
        if (rows.isNotEmpty()) for (chunk in rows.chunked(500)) assertTrue(s.save(chunk))
        s.syncIndex()
        s.closeForTest()
        return s
    }

    /** `shots.db`'s rows as stored: id to (row_json, profile_json, brew_series_json). */
    private fun shotsRows(): Map<String, Triple<String, String?, String?>> =
        SQLiteDatabase.openDatabase(shotsDb.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id, row_json, profile_json, brew_series_json FROM shots", null).use { c ->
                val out = HashMap<String, Triple<String, String?, String?>>()
                while (c.moveToNext()) out[c.getString(0)] = Triple(c.getString(1), if (c.isNull(2)) null else c.getString(2), if (c.isNull(3)) null else c.getString(3))
                out
            }
        }

    /** `history.db`'s rows as stored, the same shape, read through the core. */
    private fun historyRows(file: File = historyDb): Map<String, Triple<String, String?, String?>> {
        val b = HistoryDbBridge.openNow(file, HistoryDbBridge.options(HistoryRole.AUTHORITY))
        try {
            val out = HashMap<String, Triple<String, String?, String?>>()
            var after = Long.MIN_VALUE
            while (true) {
                val page = b.now { it.idsCreatedBefore(ULong.MAX_VALUE, after, 500u) }
                if (page.isEmpty()) break
                val ids = page.map { it.id }
                val heavy = b.now { it.heavy(ids) }.associateBy { it.id }
                for (r in b.now { it.get(ids) }) out[r.id] = Triple(r.rowJson, heavy[r.id]?.profileJson, heavy[r.id]?.brewSeriesJson)
                after = page.last().rid
                if (page.size < 500) break
            }
            return out
        } finally {
            b.now { it.closeAll() }
        }
    }

    private fun bytesOfShotsDb(): Map<String, ByteArray> =
        listOf("", "-wal", "-shm").mapNotNull { s -> File(shotsDb.path + s).takeIf { it.exists() }?.let { s to it.readBytes() } }.toMap()

    private fun assertSameBytes(before: Map<String, ByteArray>, after: Map<String, ByteArray>, what: String) {
        for ((k, v) in before) assertContentEquals(v, after[k], "$what: shots.db$k unchanged")
    }

    private fun markerText(): String? = marker.takeIf { it.exists() }?.readText()

    // ── The flip ─────────────────────────────────────────────────────────────

    @Test fun `the flip copies every row and heavy part, then the store reads and writes history-db only`() = runBlocking {
        val rows = shots("f", 60)
        stage1(rows)
        val before = shotsRows()
        val p3Rows = run { val s = store(); s.boot().view.also { s.closeForTest() } }
        HistoryStore.stage2Enabled = true
        val flipped = store()
        val load = flipped.boot()
        assertEquals(HistoryHealth.OK, load.health)
        assertTrue(flipped.onHistoryDb, "the rows are history.db's now")
        flipped.closeForTest()
        assertEquals(before, historyRows(), "every row_json, profile and brew series, byte for byte")
        assertEquals(p3Rows, load.view, "the store shows the same rows, field for field")
        assertTrue(markerText()!!.contains("\"done\""))
        assertTrue(doneRecord.readText().contains("\"done\""))
        assertFalse(app.databaseList().any { it.startsWith("history") }, "nothing under databases/ (N3)")
        // From here every write goes to history.db, and shots.db is the old copy.
        val old = bytesOfShotsDb()
        val s = store()
        s.boot()
        val fresh = shots("new", 2, start = 1_900_000_000_000L)
        assertTrue(s.save(fresh))
        assertNotNull(s.update(rows[0].id) { it.copy(rating = 5) })
        assertTrue(s.flush())
        s.delete(listOf(rows[1].id))
        s.closeForTest()
        assertSameBytes(old, bytesOfShotsDb(), "after a session on history.db")
        val after = historyRows()
        assertTrue(fresh.all { it.id in after })
        assertFalse(rows[1].id in after)
        assertTrue(after.getValue(rows[0].id).first.contains("\"rating\":5"))
        // The next launch opens history.db straight away.
        val next = store()
        val again = next.boot()
        assertTrue(next.onHistoryDb)
        assertEquals(after.keys, again.view.map { it.id }.toSet())
        // Heavy parts come from shot_heavy.
        val whole = assertNotNull(next.loadShot(rows[2].id))
        assertEquals(rows[2].profile, whole.profile)
        assertEquals(rows[2].samples, whole.samples)
    }

    @Test fun `a fresh install flips an empty store and records later shots in history-db`() = runBlocking {
        HistoryStore.stage2Enabled = true
        val s = store()
        assertEquals(HistoryHealth.OK, s.boot().health)
        assertTrue(s.onHistoryDb)
        assertTrue(s.save(shots("a", 3)))
        s.closeForTest()
        assertEquals(3, historyRows().size)
    }

    @Test fun `the flip passes with every row size the history holds - 300, 5,000 and 20,000 rows`() = runBlocking {
        for (n in listOf(300, 5_000, 20_000)) {
            clean()
            val rows = shots("m$n", n, samplesPer = 1)
            stage1(rows)
            val before = shotsRows()
            val p3 = run { val s = store(); s.boot().view.also { s.closeForTest() } }
            HistoryStore.stage2Enabled = true
            val s = store()
            val load = s.boot()
            assertTrue(s.onHistoryDb, "$n rows: flipped (${s.flipError})")
            s.closeForTest()
            assertEquals(before, historyRows(), "$n rows: every field")
            assertEquals(p3, load.view, "$n rows: the same rows shown")
        }
    }
}
