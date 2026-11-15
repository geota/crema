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
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val filesDir get() = app.filesDir
    private val sqliteDir get() = File(filesDir, HistoryStore.SQLITE_DIR)
    private val indexFile get() = File(sqliteDir, HistoryIndex.FILE_NAME)
    private val staleMark get() = File(sqliteDir, HistoryIndex.STALE_NAME)
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
        assertFalse(h.staleMarked, "$what: no stale mark")
        assertEquals(emptyList(), h.misses, "$what: the boot check passes")
    }

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
        s.loadAll()
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
        assertExact(s, "migration")
        assertEquals(7L, s.indexHealth().diffVersionCode, "a write that clears the table settles the index as a full diff")
        assertNull(s.syncIndex(), "and the boot check passes")
    }

    @Test fun `saves, edits, and a partial save leave the index equal, and a partial save deletes nothing`() = runBlocking {
        val s = store()
        s.loadAll()
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
        s.loadAll()
        s.save(fresh("d", 8))
        s.delete(listOf("d-1", "d-6", "not-there"))
        assertEquals(6, rowCount())
        assertExact(s, "delete")
    }

    @Test fun `a change through updateHidden and flushHidden leaves the index equal`() = runBlocking {
        val s = store()
        s.loadAll()
        s.save(fresh("h", 4))
        assertTrue(s.updateHidden("h-2") { it.copy(notes = "flushed") })
        s.flushHidden("h-2")
        assertEquals("flushed", store().loadRows().first { it.id == "h-2" }.notes)
        assertExact(s, "flushHidden")
    }

    @Test fun `the pending journal's merge leaves the index equal`() = runBlocking {
        val s0 = store()
        s0.loadAll()
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
        store().loadAll()
        // An old build adds a row to shots.json after the move.
        val late = StoredShot(id = "late", completedAtMs = rows.first().completedAtMs + 10, durationMs = 9, samples = HistoryFixtures.samples(6, 2))
        HistoryFixtures.plantOldBuild(app, rows + late, inline = setOf(late.id))
        val s = store()
        assertTrue(s.loadRows().any { it.id == late.id })
        assertExact(s, "late rows")
    }

    @Test fun `the summary repair leaves the index equal`() = runBlocking {
        val shot = HistoryFixtures.rows(1, samplesPer = 20).first()
        store().save(listOf(shot))
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
        s.loadAll()
        s.save(fresh("old", 5))
        val restored = fresh("r", HistoryIndex.BATCH * 2 + 37, start = 1_700_000_000_000L, samplesPer = 2)
        assertEquals(0, s.writeSeriesOf(restored))
        assertTrue(s.saveAll(restored.map { s.stage(it) }))
        assertEquals(5 + restored.size, rowCount())
        assertExact(s, "restore")
    }

    @Test fun `a lost store rebuilt from its snapshot or empty leaves the index equal`() = runBlocking {
        val s0 = store()
        s0.loadAll()
        s0.save(fresh("l", 6))
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        s0.closeIndexForTest()
        dbFile.writeBytes(ByteArray(8192) { (it * 31).toByte() })
        val s = store()
        assertEquals(HistoryHealth.LOST, s.loadAll().health)
        assertEquals(HistoryHealth.OK, s.restoreSnapshot().health)
        assertExact(s, "restoreSnapshot")
        assertEquals(HistoryHealth.OK, store().loadAll().health)
    }

    @Test fun `a random run of writes leaves the index equal`() = runBlocking {
        val rnd = Random(310)
        val s = store()
        s.loadAll()
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
        s0.loadAll()
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
        s0.loadAll()
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
        s1.loadAll()
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
        s0.loadAll()
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
        s0.loadAll()
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
        s.loadAll()
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
            s0.loadAll()
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
        s0.loadAll()
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
        s0.loadAll()
        s0.save(fresh("f", 3))
        HistoryIndex.FAULTS += IndexFault.FAIL_WRITE
        assertTrue(s0.save(listOf(StoredShot(id = "unmirrored", completedAtMs = 1_900_000_000_000L, durationMs = 1))), "the save landed")
        s0.delete(listOf("f-0"))
        HistoryIndex.FAULTS.clear()
        assertEquals(setOf("f-1", "f-2", "unmirrored"), store().loadRows().map { it.id }.toSet(), "shots.db has every write")
        val h = s0.indexHealth()
        assertTrue(assertNotNull(h.pending) > 0uL)
        assertTrue(h.staleMarked)
        assertTrue(h.hookFailures > 0)
        val s = store()
        s.loadAll()
        assertTrue(assertNotNull(s.syncIndex()).finished)
        assertExact(s, "repaired")
    }

    @Test fun `raising pending that fails marks the index stale, and the save still lands`() = runBlocking {
        val s0 = store()
        s0.loadAll()
        s0.save(fresh("r", 2))
        HistoryIndex.FAULTS += IndexFault.FAIL_RAISE
        assertTrue(s0.save(listOf(StoredShot(id = "no-raise", completedAtMs = 1_900_000_000_000L, durationMs = 1))))
        HistoryIndex.FAULTS.clear()
        assertTrue(staleMark.exists(), "the durable miss when pending couldn't be raised")
        assertEquals(0uL, s0.indexHealth().pending, "pending wasn't lowered for a raise that didn't happen")
        val s = store()
        s.loadAll()
        val report = assertNotNull(s.syncIndex())
        assertTrue(report.reasons.any { "didn't reach" in it }, report.reasons.toString())
        assertExact(s, "repaired")
        assertFalse(staleMark.exists())
    }

    @Test fun `a write that fails to mirror during a diff keeps the diff from settling pending`() = runBlocking {
        val s0 = store()
        s0.loadAll()
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
        s0.loadAll()
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
        s.loadAll()
        val before = fresh("e", 12)
        s.save(before)
        assertTrue(indexFile.exists())
        assertEquals(emptyList(), s.erase())
        assertFalse(sqliteDir.exists(), "history-sqlite/ is gone")
        s.save(listOf(StoredShot(id = "after", completedAtMs = 1_900_000_000_000L, durationMs = 1)))
        s.closeIndexForTest()
        val r = store()
        r.loadAll()
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
        s0.loadAll()
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
        assertExact(s, "after the erase")
    }

    // ── Two stores in one process ────────────────────────────────────────────

    @Test fun `a second store in the process takes the index over, and the first one's writes still land`() = runBlocking {
        val first = store()
        first.loadAll()
        first.save(fresh("one", 3))
        val second = store()
        second.loadAll()
        second.save(fresh("two", 3, start = 1_810_000_000_000L))
        assertExact(second, "the second store")
        // A recreated view model's leftover write: shots.db takes it, the index is marked for the next boot.
        assertTrue(first.save(listOf(StoredShot(id = "late-write", completedAtMs = 1_900_000_000_000L, durationMs = 1))))
        assertEquals(7, rowCount())
        assertTrue(staleMark.exists())
        val r = store()
        r.loadAll()
        assertNotNull(r.syncIndex())
        assertExact(r, "next boot")
    }
}
