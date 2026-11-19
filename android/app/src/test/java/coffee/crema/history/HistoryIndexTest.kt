package coffee.crema.history

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import coffee.crema.core.HistoryRole
import coffee.crema.persist.KeptAsideNotices
import coffee.crema.persist.SafeFile
import coffee.crema.ui.coreJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #310 A1, in Robolectric: framework SQLite for `shots.db` and the core's
 * bundled SQLite for `history-index.db`, in one process, two files. Every
 * P3a write path leaves the index equal to `shots.db` (M4); a crash at every
 * point of the hook leaves `pending` above 0 or the index exact; an index
 * that fails never fails the P3a write; the boot check misses on a new
 * `versionCode` and the full diff repairs what it finds; erase closes the
 * index first and leaves nothing behind (S11).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistoryIndexTest {
    /** P3a's `shots.db` read directly: stage 1, a history that hasn't flipped (#310 A5). */
    @get:org.junit.Rule val stage2 = coffee.crema.history.Stage2Rule(enabled = false)

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val filesDir get() = app.filesDir
    private val sqliteDir get() = File(filesDir, HistoryStore.SQLITE_DIR)
    private val indexFile get() = File(sqliteDir, HistoryIndex.FILE_NAME)
    private val dbFile get() = app.getDatabasePath(HistoryStore.NEXT_DB_NAME)
    private val stores = mutableListOf<HistoryStore>()

    private fun store(versionCode: Long = 7) = HistoryStore(app, coreJson, versionCode).also { stores += it }

    @Before fun clean() {
        SafeFile.openAllForTest()
        HistoryStore.faults.clear()
        IndexFault.entries.forEach { HistoryIndex.FAULTS.remove(it) }
        runBlocking { store().erase() }
        File(filesDir, "shot-series").deleteRecursively()
        KeptAsideNotices.drain()
    }

    @After fun tidy() {
        HistoryStore.faults.clear()
        HistoryIndex.FAULTS.clear()
        HistoryStore.afterDiffBatchForTest = null
        HistoryStore.duringEraseForTest = null
        HistoryTables.firstSeqForTest = null
        sqliteDir.setWritable(true)
        SeriesFiles.beforeWrite = {}
        runBlocking { stores.forEach { it.closeIndexForTest() } }
        SafeFile.resetForTest()
    }

    /** The index holds exactly `shots.db`'s rows, `row_json` for `row_json`, and the boot check passes. */
    private suspend fun assertExact(s: HistoryStore, what: String) {
        val c = assertNotNull(s.compareIndex(), "$what: the index is open")
        assertTrue(c.exact, "$what: $c (missing ${c.missing.take(3)}, extra ${c.extra.take(3)}, differ ${c.differ.take(3)})")
        val h = s.indexHealth()
        assertEquals(0uL, h.pending, "$what: pending")
        assertEquals(h.shotsSeq, h.sourceSeq, "$what: the index has shots.db's every write")
        assertEquals(emptyList(), h.seenSeqs, "$what: no seq past a gap")
        assertEquals(emptyList(), h.misses, "$what: the boot check passes")
    }

    /** A launch as the app runs it: the rows, then the index's boot check and any diff. */
    private suspend fun HistoryStore.boot(): HistoryLoad = loadAll().also { syncIndex() }

    private fun rowCount(): Int = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM shots", null).use { c -> c.moveToFirst(); c.getInt(0) }
    }

    /** Edit [id]'s `row_json` behind the store's back, as a build without the hook would. */
    private fun plantEdit(id: String, notes: String) {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val text = db.rawQuery("SELECT row_json FROM shots WHERE id = ?", arrayOf(id)).use { c -> c.moveToFirst(); c.getString(0) }
            val obj = coreJson.parseToJsonElement(text).jsonObject
            db.execSQL("UPDATE shots SET row_json = ? WHERE id = ?", arrayOf(JsonObject(obj + ("notes" to JsonPrimitive(notes))).toString(), id))
        }
    }

    private fun setDbVersion(v: Int) {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = v }
    }

    private fun fresh(prefix: String, count: Int, start: Long = 1_800_000_000_000L, samplesPer: Int = 4) =
        HistoryFixtures.rows(count, samplesPer = samplesPer, start = start).mapIndexed { i, s -> s.copy(id = "$prefix-$i") }

    // ── Where it lives ───────────────────────────────────────────────────────

    @Test fun `the index lives in filesDir history-sqlite, an index file, outside databases`() = runBlocking {
        val s = store()
        s.boot()
        s.save(fresh("a", 3))
        assertTrue(indexFile.isFile, "filesDir/history-sqlite/history-index.db")
        assertFalse(app.databaseList().any { it.startsWith("history") }, "nothing of it under databases/")
        s.closeIndexForTest()
        val db = HistoryDbBridge.open(indexFile, HistoryDbBridge.options(HistoryRole.INDEX))
        try {
            assertEquals(HistoryRole.INDEX, db.info().role)
            assertEquals(3u, db.count(HistoryDbBridge.ALL))
        } finally {
            db.closeAll()
        }
    }

    // ── Every P3a write path (M4) ────────────────────────────────────────────

    @Test fun `the migration leaves the index equal to shots-db`() = runBlocking {
        HistoryFixtures.plantOldBuild(app, HistoryFixtures.rows(40, samplesPer = 5))
        val s = store()
        assertEquals(HistoryHealth.OK, s.loadAll().health)
        assertEquals(40, rowCount())
        // A write that clears the table leaves the rebuild to the boot's syncIndex, after the marker (REVIEW-446 note 5).
        assertTrue(assertNotNull(s.indexHealth().pending) > 0uL, "pending stays up for the rebuild")
        assertTrue(File(filesDir, HistoryStore.MARKER_NAME).readText().contains("\"done\""), "the marker is written first")
        val report = assertNotNull(s.syncIndex())
        assertTrue(report.finished, report.outcome)
        assertEquals(40, report.upserted)
        assertExact(s, "migration")
        assertEquals(7L, s.indexHealth().diffVersionCode)
        assertNull(s.syncIndex(), "and the boot check passes")
    }

    @Test fun `saves, edits, and a partial save leave the index equal, and a partial save deletes nothing`() = runBlocking {
        val s = store()
        s.boot()
        val rows = fresh("s", 12)
        assertTrue(s.save(rows))
        assertExact(s, "first save")
        // An edit of the view's rows: only the changed row is written.
        val view = s.loadRows()
        s.save(view.map { if (it.id == "s-3") it.copy(notes = "edited", rating = 4) else it })
        assertExact(s, "edit")
        // A partial list: the store is upsert-only, and so is the index.
        s.save(listOf(view.first { it.id == "s-5" }.copy(notes = "alone")))
        assertEquals(12, rowCount())
        assertEquals(12, s.indexHealth().indexRows, "nothing left the index")
        assertExact(s, "partial save")
        s.save(emptyList<StoredShot>())
        assertExact(s, "empty save")
    }

    @Test fun `a delete leaves the index equal`() = runBlocking {
        val s = store()
        s.boot()
        s.save(fresh("d", 8))
        s.delete(listOf("d-1", "d-6", "not-there"))
        assertEquals(6, rowCount())
        assertExact(s, "delete")
    }

    @Test fun `a change through updateHidden and flushHidden leaves the index equal`() = runBlocking {
        val s = store()
        s.boot()
        s.save(fresh("h", 4))
        assertTrue(s.updateHidden("h-2") { it.copy(notes = "flushed") })
        s.flushHidden("h-2")
        assertEquals("flushed", store().loadRows().first { it.id == "h-2" }.notes)
        assertExact(s, "flushHidden")
    }

    @Test fun `the pending journal's merge leaves the index equal`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("j", 3))
        setDbVersion(HistoryDb.VERSION + 1)
        val s = store()
        assertEquals(HistoryHealth.UNAVAILABLE, s.loadAll().health)
        s.save(listOf(StoredShot(id = "journaled", completedAtMs = 1_900_000_000_000L, durationMs = 1, samples = HistoryFixtures.samples(4, 1))))
        s.delete(listOf("j-0"))
        assertTrue(File(filesDir, HistoryStore.PENDING_NAME).exists())
        setDbVersion(HistoryDb.VERSION)
        assertEquals(HistoryHealth.OK, s.retry().health)
        assertFalse(File(filesDir, HistoryStore.PENDING_NAME).exists(), "merged")
        assertEquals(setOf("journaled", "j-1", "j-2"), store().loadRows().map { it.id }.toSet())
        assertExact(s, "mergePending")
    }

    @Test fun `the late-row import leaves the index equal`() = runBlocking {
        val rows = HistoryFixtures.rows(5, samplesPer = 5)
        HistoryFixtures.plantOldBuild(app, rows)
        store().boot()
        // An old build adds a row to shots.json after the move.
        val late = StoredShot(id = "late", completedAtMs = rows.first().completedAtMs + 10, durationMs = 9, samples = HistoryFixtures.samples(6, 2))
        HistoryFixtures.plantOldBuild(app, rows + late, inline = setOf(late.id))
        val s = store()
        assertTrue(s.loadRows().any { it.id == late.id })
        assertExact(s, "late rows")
    }

    @Test fun `the summary repair leaves the index equal`() = runBlocking {
        val shot = HistoryFixtures.rows(1, samplesPer = 20).first()
        store().also { it.boot() }.save(listOf(shot))
        // A 0 stored next to a series file, written behind the hook's back (an older build).
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val text = db.rawQuery("SELECT row_json FROM shots WHERE id = ?", arrayOf(shot.id)).use { c -> c.moveToFirst(); c.getString(0) }
            val obj = coreJson.parseToJsonElement(text).jsonObject
            val summary = obj.getValue("summary").jsonObject
            db.execSQL(
                "UPDATE shots SET row_json = ? WHERE id = ?",
                arrayOf(JsonObject(obj + ("summary" to JsonObject(summary + ("sampleCount" to JsonPrimitive(0))))).toString(), shot.id),
            )
        }
        val s = store()
        val row = s.loadRows().single()
        assertEquals(listOf(shot.id), s.compareIndex()?.differ, "the planted 0 isn't in the index")
        assertNotNull(s.loadShot(shot.id))
        s.save(listOf(row))
        assertEquals(20u, store().loadRows().single().summary.sampleCount, "the save corrects the summary")
        assertExact(s, "summary repair")
    }

    @Test fun `a restore's saveAll, in several index batches, leaves the index equal`() = runBlocking {
        val s = store()
        s.boot()
        s.save(fresh("old", 5))
        val restored = fresh("r", HistoryIndex.BATCH * 2 + 37, start = 1_700_000_000_000L, samplesPer = 2)
        assertEquals(0, s.writeSeriesOf(restored))
        assertTrue(s.saveAll(restored.map { s.stage(it) }))
        assertEquals(5 + restored.size, rowCount())
        assertExact(s, "restore")
    }

    @Test fun `a lost store rebuilt from its snapshot or empty leaves the index equal`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("l", 6))
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        s0.closeIndexForTest()
        dbFile.writeBytes(ByteArray(8192) { (it * 31).toByte() })
        val s = store()
        assertEquals(HistoryHealth.LOST, s.loadAll().health)
        assertEquals(HistoryHealth.OK, s.restoreSnapshot().health)
        assertTrue(assertNotNull(s.syncIndex()).finished)
        assertExact(s, "restoreSnapshot")
        assertEquals(HistoryHealth.OK, store().loadAll().health)
    }

    @Test fun `a random run of writes leaves the index equal`() = runBlocking {
        val rnd = Random(310)
        val s = store()
        s.boot()
        var next = 0
        repeat(60) {
            val view = s.loadRows()
            when (rnd.nextInt(5)) {
                0, 1 -> s.save(fresh("x${next++}", rnd.nextInt(1, 6), start = 1_800_000_000_000L + next * 10_000L))
                2 -> if (view.isNotEmpty()) s.save(view.map { if (rnd.nextInt(3) == 0) it.copy(notes = "n${rnd.nextInt()}") else it })
                3 -> if (view.isNotEmpty()) s.delete(view.shuffled(rnd).take(rnd.nextInt(1, 3)).map { it.id })
                4 -> view.randomOrNull(rnd)?.let { r ->
                    s.updateHidden(r.id) { it.copy(rating = rnd.nextInt(1, 6)) }
                    s.flushHidden(r.id)
                }
            }
        }
        assertExact(s, "random run")
    }

    // ── Rebuilds and the boot check ──────────────────────────────────────────

    @Test fun `a deleted index is rebuilt by the next boot's diff`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("g", 30))
        s0.closeIndexForTest()
        listOf("", "-wal", "-shm").forEach { File(indexFile.path + it).delete() }
        val s = store()
        s.loadAll()
        val report = assertNotNull(s.syncIndex(), "the boot check misses")
        assertTrue(report.finished, report.outcome)
        assertEquals(30, report.upserted)
        assertExact(s, "rebuilt")
    }

    @Test fun `a damaged index is rebuilt, never repaired`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("c", 9))
        s0.closeIndexForTest()
        listOf("-wal", "-shm").forEach { File(indexFile.path + it).delete() }
        indexFile.writeBytes(ByteArray(8192) { (it * 17).toByte() })
        val s = store()
        s.loadAll()
        assertTrue(assertNotNull(s.syncIndex()).finished)
        assertExact(s, "rebuilt from damage")
    }

    @Test fun `a changed versionCode runs the full diff and catches a planted edit`() = runBlocking {
        val s1 = store(versionCode = 41)
        s1.boot()
        s1.save(fresh("v", 10))
        assertNull(s1.syncIndex(), "the hook kept it exact")
        s1.closeIndexForTest()
        // A build without the hook (a hotfix from old code) edits a row.
        plantEdit("v-4", "written without the hook")
        val same = store(versionCode = 41)
        same.loadAll()
        assertNull(same.syncIndex(), "the same versionCode's boot check can't see it")
        assertEquals(listOf("v-4"), same.compareIndex()?.differ)
        same.closeIndexForTest()

        val next = store(versionCode = 42)
        next.loadAll()
        val report = assertNotNull(next.syncIndex(), "a new versionCode misses")
        assertTrue(report.reasons.any { "versionCode" in it }, report.reasons.toString())
        assertTrue(report.finished)
        assertEquals(1, report.upserted, "only the planted row differed")
        assertExact(next, "after the diff")
        assertEquals(42L, next.indexHealth().diffVersionCode)
    }

    @Test fun `the diff removes rows shots-db doesn't hold`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("k", 6))
        s0.closeIndexForTest()
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DELETE FROM shots WHERE id = 'k-2'") }
        val s = store(versionCode = 8)
        s.loadAll()
        val report = assertNotNull(s.syncIndex())
        assertEquals(1, report.removed)
        assertExact(s, "removed by absence")
    }

    // ── Crashes and failures (M4) ────────────────────────────────────────────

    @Test fun `a crash between the P3a write and the index write leaves pending up, and the next boot repairs it`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("p", 3))
        HistoryIndex.FAULTS += IndexFault.CRASH_AFTER_SHOTS
        runCatching { s0.save(listOf(StoredShot(id = "crash", completedAtMs = 1_900_000_000_000L, durationMs = 1))) }
            .onSuccess { error("expected a crash") }
        assertEquals(4, rowCount(), "shots.db has the row")
        // The next launch.
        val s = store()
        val h = s.indexHealth()
        assertEquals(1uL, h.pending, "pending says a write wasn't mirrored")
        s.loadAll()
        assertEquals(listOf("crash"), s.compareIndex()?.missing)
        val report = assertNotNull(s.syncIndex())
        assertTrue(report.reasons.any { "pending" in it })
        assertExact(s, "repaired")
    }

    @Test fun `concurrent saves from two coroutines leave the index equal`() = runBlocking {
        val s = store()
        s.boot()
        val a = fresh("ca", 40, start = 1_800_000_000_000L)
        val b = fresh("cb", 40, start = 1_810_000_000_000L)
        listOf(a, b).map { rows ->
            async(Dispatchers.IO) { rows.chunked(3).forEach { s.save(it) } }
        }.awaitAll()
        assertEquals(80, rowCount())
        assertExact(s, "two writers")
    }

    @Test fun `a crash at every point of racing saves leaves pending above 0 or the index exact`() {
        val points = listOf(IndexFault.CRASH_AFTER_RAISE, IndexFault.CRASH_AFTER_SHOTS, IndexFault.CRASH_BETWEEN_BATCHES, IndexFault.CRASH_AFTER_INDEX)
        for (point in points) runBlocking {
            store().erase()
            val s0 = store()
            s0.boot()
            s0.save(fresh("base", 5))
            HistoryIndex.FAULTS += point
            // One save big enough for two index batches, one small, racing.
            val big = fresh("big", HistoryIndex.BATCH + 20, start = 1_700_000_000_000L, samplesPer = 1)
            val small = fresh("small", 3, start = 1_900_000_000_000L)
            val crashed = listOf(big, small).map { rows ->
                async(Dispatchers.IO) { runCatching { s0.save(rows) }.exceptionOrNull() }
            }.awaitAll().filterNotNull()
            assertTrue(crashed.all { it is SimulatedCrash }, "$point: $crashed")
            assertEquals(1, crashed.size, "$point fired once")
            // The next launch, before anything writes.
            val s = store()
            val pending = assertNotNull(s.indexHealth().pending, "$point")
            s.loadAll()
            val exact = assertNotNull(s.compareIndex()).exact
            assertTrue(pending > 0uL || exact, "$point: pending $pending and the index differs")
            // Whatever it left, the boot's diff makes it exact.
            s.syncIndex()
            assertExact(s, "$point, after the diff")
        }
    }

    @Test fun `a crash during the full diff leaves pending up, and the next one finishes`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("q", HistoryIndex.BATCH + 10, samplesPer = 1))
        s0.closeIndexForTest()
        listOf("", "-wal", "-shm").forEach { File(indexFile.path + it).delete() }
        val s1 = store()
        s1.loadAll()
        HistoryIndex.FAULTS += IndexFault.CRASH_IN_DIFF
        runCatching { s1.syncIndex() }.onSuccess { error("expected a crash") }
        val s = store()
        assertTrue(assertNotNull(s.indexHealth().pending) > 0uL)
        s.loadAll()
        assertTrue(assertNotNull(s.syncIndex()).finished)
        assertExact(s, "the second diff")
    }

    @Test fun `an index write that fails never fails the P3a write, and the next boot repairs it`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("f", 3))
        HistoryIndex.FAULTS += IndexFault.FAIL_WRITE
        assertTrue(s0.save(listOf(StoredShot(id = "unmirrored", completedAtMs = 1_900_000_000_000L, durationMs = 1))), "the save landed")
        s0.delete(listOf("f-0"))
        HistoryIndex.FAULTS.clear()
        assertEquals(setOf("f-1", "f-2", "unmirrored"), store().loadRows().map { it.id }.toSet(), "shots.db has every write")
        val h = s0.indexHealth()
        assertTrue(assertNotNull(h.pending) > 0uL)
        assertTrue(h.misses.isNotEmpty(), "the boot check misses")
        assertTrue(h.hookFailures > 0)
        val s = store()
        s.loadAll()
        assertTrue(assertNotNull(s.syncIndex()).finished)
        assertExact(s, "repaired")
    }

    @Test fun `raising pending that fails lowers nothing, the save lands, and the index still takes it`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("r", 2))
        HistoryIndex.FAULTS += IndexFault.FAIL_RAISE
        assertTrue(s0.save(listOf(StoredShot(id = "no-raise", completedAtMs = 1_900_000_000_000L, durationMs = 1))))
        HistoryIndex.FAULTS.clear()
        assertEquals(0uL, s0.indexHealth().pending, "pending wasn't lowered for a raise that didn't happen")
        assertExact(s0, "the write's seq reached the index with its rows")
    }

    /** REVIEW-446 P2: the raise and the index write both fail, and nothing in history-sqlite/ can be written. */
    @Test fun `P2 a failed raise and a failed index write leave a seq gap that shots-db itself records`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("q", 3))
        assertExact(s0, "before")
        sqliteDir.setWritable(false)
        try {
            HistoryIndex.FAULTS += IndexFault.FAIL_RAISE
            HistoryIndex.FAULTS += IndexFault.FAIL_WRITE
            val row = s0.loadRows().first { it.id == "q-0" }
            assertTrue(s0.save(listOf(row.copy(notes = "edit nobody mirrored"))), "the save landed in shots.db")
            HistoryIndex.FAULTS.clear()
        } finally {
            sqliteDir.setWritable(true)
        }
        s0.closeIndexForTest()
        val next = store()
        next.loadAll()
        val h = next.indexHealth()
        assertEquals(0uL, h.pending, "pending was never raised")
        assertEquals(listOf("q-0"), next.compareIndex()?.differ)
        assertTrue(h.misses.any { "shots.db is at" in it }, "the seqs say so: ${h.misses}")
        assertTrue(assertNotNull(next.syncIndex()).finished)
        assertExact(next, "repaired")
    }

    /** REVIEW-446 P1: the first store's late write while the second store's diff runs. */
    @Test fun `P1 a late write from another store during a diff keeps the diff from settling`() = runBlocking {
        val first = store()
        first.boot()
        first.save(fresh("p", HistoryIndex.BATCH * 2 + 5, samplesPer = 1))
        val second = store(versionCode = 8)
        second.loadAll()
        var once = true
        HistoryStore.afterDiffBatchForTest = {
            if (once) {
                once = false
                // A recreated view model's leftover edits a row the diff already passed.
                val row = first.loadRows().first { it.id == "p-0" }
                assertTrue(first.save(listOf(row.copy(notes = "late edit from the first store"))))
            }
        }
        val report = assertNotNull(second.syncIndex())
        HistoryStore.afterDiffBatchForTest = null
        assertFalse(report.finished, "it must not settle: ${report.outcome}")
        second.closeIndexForTest()
        val next = store(versionCode = 8)
        next.loadAll()
        assertTrue(next.indexHealth().misses.isNotEmpty(), "the next boot misses")
        assertTrue(assertNotNull(next.syncIndex()).finished)
        assertExact(next, "repaired")
    }

    /** REVIEW-446 note 3: device transfer copies both files, maybe at different moments. */
    @Test fun `an index copied before shots-db's later writes is caught at the next boot`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("t", 6))
        s0.closeIndexForTest()
        val aside = File(app.cacheDir, "index-copy").apply { deleteRecursively(); mkdirs() }
        for (suffix in listOf("", "-wal", "-shm")) File(indexFile.path + suffix).takeIf { it.exists() }?.copyTo(File(aside, indexFile.name + suffix))
        val s1 = store()
        s1.boot()
        // An edit and a new row, same count change undone by a delete: only the seqs can tell.
        s1.save(listOf(s1.loadRows().first { it.id == "t-2" }.copy(notes = "after the copy")))
        s1.save(listOf(StoredShot(id = "t-new", completedAtMs = 1_900_000_000_000L, durationMs = 1)))
        s1.delete(listOf("t-5"))
        s1.closeIndexForTest()
        // The new device: shots.db as now, the index as it was.
        for (suffix in listOf("", "-wal", "-shm")) File(indexFile.path + suffix).delete()
        aside.listFiles()!!.forEach { it.copyTo(File(sqliteDir, it.name)) }
        val s = store()
        s.loadAll()
        val h = s.indexHealth()
        assertEquals(0uL, h.pending)
        assertEquals(6, h.indexRows, "the counts match")
        assertTrue(h.misses.isNotEmpty(), "but the seqs don't: ${h.misses}")
        assertTrue(assertNotNull(s.syncIndex()).finished)
        assertExact(s, "after the diff")
    }

    /**
     * REVIEW-446 note 4: another store's write while this one erases makes
     * nothing in history-sqlite/. From REVIEW-475 R1 the stores share one
     * lock, so the write waits for the erase to finish.
     */
    @Test fun `a write from another store during an erase doesn't make the index again`() = runBlocking {
        val other = store()
        other.boot()
        other.save(fresh("o", 2))
        val eraser = store()
        eraser.loadAll()
        var write: kotlinx.coroutines.Job? = null
        HistoryStore.duringEraseForTest = {
            HistoryStore.duringEraseForTest = null
            // The erase has closed every index in the process (S11); the other store's write comes now.
            write = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                other.save(listOf(StoredShot(id = "during-erase", completedAtMs = 1_900_000_000_000L, durationMs = 1)))
            }
            Thread.sleep(200)
            assertFalse(write!!.isCompleted, "the write waits for the erase: the stores share one lock")
            assertFalse(sqliteDir.exists(), "nothing made history-sqlite/ again mid-erase")
        }
        assertEquals(emptyList(), eraser.erase(), "nothing stayed")
        write!!.join()
    }

    private suspend fun edit(s: HistoryStore, id: String, notes: String) {
        assertTrue(s.save(listOf(s.loadRows().first { it.id == id }.copy(notes = notes))))
    }

    private fun loseShotsDb() {
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        dbFile.writeBytes(ByteArray(8192) { (it * 31).toByte() })
    }

    /**
     * REVIEW-450 A: `shots.db` is lost and rebuilt while the index can't be
     * reached (another store took it over), so nothing raises `pending`. The
     * new file's seq must not climb back to the old file's and pass the check.
     */
    @Test fun `A a replaced shots-db never reuses the old file's seqs`() = runBlocking {
        // The old file counted from 1, as before this rule: its seqs are the ones a restart at 1 would meet.
        HistoryTables.firstSeqForTest = { 1L }
        val s0 = store()
        s0.boot()
        s0.save(fresh("a", 4))
        repeat(4) { edit(s0, "a-0", "edit $it") }
        val target = assertNotNull(s0.indexHealth().sourceSeq)
        s0.closeIndexForTest()
        loseShotsDb()
        val s1 = store()
        assertEquals(HistoryHealth.LOST, s1.loadAll().health)
        s1.indexHealth() // s1 opens the index...
        val other = store()
        other.indexHealth() // ...and another store takes it over: s1 can't raise pending.
        HistoryTables.firstSeqForTest = null
        assertEquals(HistoryHealth.OK, s1.startEmpty().health)
        s1.save(fresh("b", 4, start = 1_850_000_000_000L))
        val newSeq = assertNotNull(s1.indexHealth().shotsSeq)
        assertTrue(newSeq > target + 1_000_000uL, "the new file starts from the clock ($newSeq), far past the old one's $target")
        // Climb to the old seq, as a restart at 1 would within a session: from the clock, there's nothing to climb.
        while (assertNotNull(s1.indexHealth().shotsSeq) < target) edit(s1, "b-0", "climb ${System.nanoTime()}")
        other.closeIndexForTest()
        val s2 = store()
        s2.loadAll()
        val h = s2.indexHealth()
        assertEquals(0uL, h.pending, "nothing raised pending")
        assertEquals(4, h.indexRows, "the counts match")
        assertFalse(assertNotNull(s2.compareIndex()).exact, "the index still holds the old file's rows")
        assertTrue(h.misses.any { "shots.db is at" in it }, "the seqs miss: ${h.misses}")
        assertTrue(assertNotNull(s2.syncIndex()).finished)
        assertExact(s2, "repaired")
    }

    @Test fun `a new file's first seq is the clock, and the ones after count up from it`() = runBlocking {
        HistoryTables.firstSeqForTest = { 5_000L }
        try {
            val s = store()
            s.boot()
            assertEquals(5_000uL, s.indexHealth().shotsSeq, "the empty migration is the first write")
            s.save(fresh("c", 2))
            assertEquals(5_001uL, s.indexHealth().shotsSeq)
            assertExact(s, "clock seqs")
        } finally {
            HistoryTables.firstSeqForTest = null
        }
    }

    /**
     * REVIEW-450 B: a seq the index saw past a gap that this `shots.db` never
     * made (an index copied later than its `shots.db`, an older file) must not
     * stand in for a write during the diff whose mirror failed.
     */
    @Test fun `B a seen seq this shots-db never made doesn't cover a failed mirror during the diff`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("h", HistoryIndex.BATCH + 5, samplesPer = 1))
        val seq = assertNotNull(s0.indexHealth().shotsSeq)
        s0.closeIndexForTest()
        // The index one write behind, and holding the seq after shots.db's, past the gap.
        HistoryDbBridge.open(indexFile, HistoryDbBridge.options(HistoryRole.INDEX)).let { db ->
            try {
                db.setSourceSeq(seq - 1uL)
                db.indexWrite(coffee.crema.core.HistoryIndexWrite(seenSeqs = listOf(seq + 1uL)))
                assertEquals(listOf(seq + 1uL), db.indexState().seenSeqs)
            } finally {
                db.closeAll()
            }
        }
        val s = store()
        s.loadAll()
        var once = true
        HistoryStore.afterDiffBatchForTest = {
            if (once) {
                once = false
                // A capture during the diff, seq + 1, whose mirror fails.
                HistoryIndex.FAULTS += IndexFault.FAIL_WRITE
                edit(s, "h-0", "a capture whose mirror failed")
                HistoryIndex.FAULTS.clear()
            }
        }
        val report = assertNotNull(s.syncIndex())
        HistoryStore.afterDiffBatchForTest = null
        assertFalse(report.finished, "it must not settle: ${report.outcome}")
        assertEquals(listOf("h-0"), s.compareIndex()?.differ)
        assertTrue(s.indexHealth().misses.isNotEmpty(), "the next boot misses")
        assertTrue(assertNotNull(s.syncIndex()).finished)
        assertExact(s, "repaired")
    }

    /** REVIEW-450 C: a new versionCode's diff starts with the seqs equal; a mirrored save during it still lets it settle. */
    @Test fun `C a save during a versionCode diff, all mirrored, settles`() = runBlocking {
        val s0 = store(versionCode = 7)
        s0.boot()
        s0.save(fresh("v", HistoryIndex.BATCH * 2 + 5, samplesPer = 1))
        s0.closeIndexForTest()
        val s = store(versionCode = 8)
        s.loadAll()
        assertEquals(1, s.indexHealth().misses.size, "only the versionCode misses")
        var once = true
        HistoryStore.afterDiffBatchForTest = {
            if (once) {
                once = false
                edit(s, "v-0", "a capture during the diff")
            }
        }
        val report = assertNotNull(s.syncIndex())
        HistoryStore.afterDiffBatchForTest = null
        assertEquals(IndexDiffReport.OK, report.outcome)
        assertExact(s, "settled")
    }

    @Test fun `every write to shots raises its seq in shots-db`() = runBlocking {
        val s = store()
        s.boot()
        val start = assertNotNull(s.indexHealth().shotsSeq)
        s.save(fresh("n", 2))
        s.delete(listOf("n-0"))
        s.save(emptyList<StoredShot>())
        assertEquals(start + 2uL, s.indexHealth().shotsSeq, "a save and a delete; an empty save writes nothing")
        assertExact(s, "seqs")
    }

    @Test fun `a write that fails to mirror during a diff keeps the diff from settling pending`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("w", HistoryIndex.BATCH + 30, samplesPer = 1))
        s0.closeIndexForTest()
        listOf("", "-wal", "-shm").forEach { File(indexFile.path + it).delete() }
        val s = store()
        s.loadAll()
        var once = true
        HistoryStore.afterDiffBatchForTest = {
            if (once) {
                once = false
                // A capture lands between two batches, and its index write fails.
                HistoryIndex.FAULTS += IndexFault.FAIL_WRITE
                val row = s.loadRows().first { it.id == "w-0" }
                assertTrue(s.save(listOf(row.copy(notes = "between batches"))))
                HistoryIndex.FAULTS.clear()
            }
        }
        val report = assertNotNull(s.syncIndex())
        assertFalse(report.finished, "a failure while it ran: ${report.outcome}")
        assertTrue(assertNotNull(s.indexHealth().pending) > 0uL, "pending stays up")
        HistoryStore.afterDiffBatchForTest = null
        assertTrue(assertNotNull(s.syncIndex()).finished)
        assertExact(s, "the next diff")
    }

    @Test fun `writes between diff batches land in the index`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("b", HistoryIndex.BATCH * 2 + 5, samplesPer = 1))
        s0.closeIndexForTest()
        listOf("", "-wal", "-shm").forEach { File(indexFile.path + it).delete() }
        val s = store()
        s.loadAll()
        var batch = 0
        HistoryStore.afterDiffBatchForTest = {
            batch++
            val view = s.loadRows()
            s.save(listOf(StoredShot(id = "during-$batch", completedAtMs = 1_950_000_000_000L + batch, durationMs = 1)))
            s.save(view.filter { it.id == "b-1" || it.id == "b-900" }.map { it.copy(notes = "edit $batch") })
            s.delete(listOf("b-${batch * 3}"))
        }
        assertTrue(assertNotNull(s.syncIndex()).finished)
        assertTrue(batch >= 2)
        assertExact(s, "writes during the diff")
    }

    // ── Erase (S11) ──────────────────────────────────────────────────────────

    @Test fun `erase, then a write, then reopen, and nothing erased is there`() = runBlocking {
        val s = store()
        s.boot()
        val before = fresh("e", 12)
        s.save(before)
        assertTrue(indexFile.exists())
        assertEquals(emptyList(), s.erase())
        assertFalse(sqliteDir.exists(), "history-sqlite/ is gone")
        s.save(listOf(StoredShot(id = "after", completedAtMs = 1_900_000_000_000L, durationMs = 1)))
        s.closeIndexForTest()
        val r = store()
        r.boot()
        assertEquals(listOf("after"), r.loadRows().map { it.id })
        assertExact(r, "after erase")
        r.closeIndexForTest()
        val db = HistoryDbBridge.open(indexFile, HistoryDbBridge.options(HistoryRole.INDEX))
        try {
            assertEquals(listOf("after"), db.get(before.map { it.id } + "after").map { it.id }, "no erased row in the index")
        } finally {
            db.closeAll()
        }
    }

    @Test fun `an erase during a full diff stops it, and it writes nothing into the new index`() = runBlocking {
        val s0 = store()
        s0.boot()
        s0.save(fresh("z", HistoryIndex.BATCH * 2 + 5, samplesPer = 1))
        s0.closeIndexForTest()
        listOf("", "-wal", "-shm").forEach { File(indexFile.path + it).delete() }
        val s = store()
        s.loadAll()
        var erased = false
        HistoryStore.afterDiffBatchForTest = {
            if (!erased) {
                erased = true
                assertEquals(emptyList(), s.erase())
            }
        }
        val report = assertNotNull(s.syncIndex())
        assertFalse(report.finished, report.outcome)
        HistoryStore.afterDiffBatchForTest = null
        assertFalse(indexFile.exists(), "the diff didn't reopen the erased index")
        s.save(listOf(StoredShot(id = "new", completedAtMs = 1_900_000_000_000L, durationMs = 1)))
        assertEquals(1, s.indexHealth().indexRows)
        assertNotNull(s.syncIndex(), "the new store's clear leaves the rebuild to the boot check")
        assertExact(s, "after the erase")
    }

    // ── Two stores in one process ────────────────────────────────────────────

    @Test fun `a second store in the process takes the index over, and the first one's writes still land`() = runBlocking {
        val first = store()
        first.boot()
        first.save(fresh("one", 3))
        val second = store()
        second.boot()
        second.save(fresh("two", 3, start = 1_810_000_000_000L))
        assertExact(second, "the second store")
        // A recreated view model's leftover write: shots.db takes it, and its seq says the index missed it.
        assertTrue(first.save(listOf(StoredShot(id = "late-write", completedAtMs = 1_900_000_000_000L, durationMs = 1))))
        assertEquals(7, rowCount())
        val r = store()
        r.loadAll()
        assertTrue(r.indexHealth().misses.isNotEmpty())
        assertNotNull(r.syncIndex())
        assertExact(r, "next boot")
    }
}
