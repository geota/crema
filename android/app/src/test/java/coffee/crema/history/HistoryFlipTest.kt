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

    // ── Kills and failures (M8) ──────────────────────────────────────────────

    /** Flip on the next boot, and check it holds [rows] exactly and reads them back. */
    private suspend fun assertFlipsTo(rows: Map<String, Triple<String, String?, String?>>, what: String) {
        HistoryStore.stage2Enabled = true
        val s = store()
        val load = s.boot()
        assertEquals(HistoryHealth.OK, load.health, "$what: ${s.lastError}")
        assertTrue(s.onHistoryDb, "$what: on history.db (${s.flipError})")
        assertEquals(rows.keys, load.view.map { it.id }.toSet(), "$what: every row shown")
        s.closeForTest()
        val file = File(sqliteDir, markerGeneration().let { if (it == 0) "history.db" else "history-$it.db" })
        assertEquals(rows, historyRows(file), "$what: every row and heavy part")
    }

    private fun markerGeneration(): Int = coreJson.decodeFromString(Stage2Marker.serializer(), marker.readText()).generation

    @Test fun `a kill at every step and batch boundary of the flip ends, at the next boot, in the same flipped history`() = runBlocking {
        val kills = listOf(
            FlipFault.AFTER_DIFF, FlipFault.AFTER_COPY, FlipFault.AFTER_LATE_IDS, FlipFault.AFTER_FTS, FlipFault.IN_HEAVY,
            FlipFault.AFTER_HEAVY, FlipFault.IN_VERIFY, FlipFault.AFTER_DONE, FlipFault.AFTER_DONE_RECORD, FlipFault.AFTER_MARKER,
        )
        for (kill in kills) {
            clean()
            // Over a batch, so the kills inside a step's loop land between batches.
            stage1(shots("k", HistoryFlip.BATCH + 20, samplesPer = 1))
            val rows = shotsRows()
            HistoryStore.stage2Enabled = true
            HistoryFlip.FAULTS += kill
            val s = store()
            assertFailsWith<SimulatedCrash>("$kill") { s.boot() }
            assertFalse(kill in HistoryFlip.FAULTS, "$kill fired")
            s.closeForTest()
            // Whatever the kill left, shots.db is untouched and nothing reads as lost.
            assertEquals(rows, shotsRows(), "$kill: shots.db as it was")
            assertFlipsTo(rows, "after $kill")
        }
    }

    @Test fun `a copy left with role index, a crash right after the backup copy, is overwritten in place`() = runBlocking {
        val s = stage1(shots("i", 30))
        val rows = shotsRows()
        // As flip_copy_to leaves it when the process dies between its copy and its promote.
        sqliteDir.mkdirs()
        val index = HistoryDbBridge.openNow(File(sqliteDir, HistoryIndex.FILE_NAME), HistoryDbBridge.options(HistoryRole.INDEX))
        index.now { it.copyTo(historyDb.path, coffee.crema.core.HistoryJournalMode.WAL) }
        index.now { it.closeAll() }
        assertEquals(FileProbe.Leftover, Stage2Files(filesDir, coreJson).probe(historyDb))
        s.closeForTest()
        assertFlipsTo(rows, "over an index copy")
        assertEquals(0, markerGeneration(), "in place")
    }

    @Test fun `a leftover that doesn't open is left byte for byte, and the flip goes to the next generation`() = runBlocking {
        stage1(shots("u", 20))
        val rows = shotsRows()
        sqliteDir.mkdirs()
        // As a kill during the very first copy can leave it: some pages, not a database that opens.
        val junk = ByteArray(8192) { (it * 31).toByte() }
        historyDb.writeBytes(junk)
        assertFlipsTo(rows, "around a leftover")
        assertEquals(1, markerGeneration())
        assertContentEquals(junk, historyDb.readBytes(), "the leftover stays exactly as it was")
    }

    @Test fun `a flip that fails half-way leaves history-db copying, unread, and the next try overwrites it and passes`() = runBlocking {
        stage1(shots("h", 40))
        val rows = shotsRows()
        HistoryStore.stage2Enabled = true
        HistoryFlip.FAULTS += FlipFault.DISK_FULL
        val s = store()
        val load = s.boot()
        assertEquals(HistoryHealth.OK, load.health, "stage 1 goes on")
        assertFalse(s.onHistoryDb)
        assertTrue(s.flipError!!.contains("full"), s.flipError)
        assertTrue(markerText()!!.contains("\"failed\""))
        assertFalse(doneRecord.exists())
        // Stage 1 still works, on shots.db.
        assertTrue(s.save(shots("after", 1, start = 1_900_000_000_000L)))
        s.closeForTest()
        assertTrue("after-0" in shotsRows())
        // history.db says copying: no build reads it as the truth.
        assertEquals(FileProbe.Leftover, Stage2Files(filesDir, coreJson).probe(historyDb))
        HistoryFlip.FAULTS.clear()
        assertFlipsTo(shotsRows(), "the next try")
        assertEquals(0, markerGeneration())
        assertTrue(rows.keys.all { it in historyRows() })
    }

    @Test fun `not enough free space fails the flip before it writes anything`() = runBlocking {
        stage1(shots("s", 10))
        HistoryStore.freeSpaceForTest = { 10L }
        HistoryStore.stage2Enabled = true
        val s = store()
        s.boot()
        assertFalse(s.onHistoryDb)
        assertTrue(s.flipError!!.contains("free space"), s.flipError)
        assertFalse(historyDb.exists())
        s.closeForTest()
    }

    @Test fun `each verify check fails on its own mutation, and a failed verify rebuilds the index before the next try`() = runBlocking {
        val mutations = listOf(
            FlipFault.DROP_ROW to "ids", FlipFault.EXTRA_ROW to "ids", FlipFault.DROP_SOURCE_ID to "migration_source",
            FlipFault.ALTER_ROW to "differs", FlipFault.ALTER_HEAVY to "heavy parts", FlipFault.DERIVED to "derived",
            FlipFault.FTS_NOT_READY to "search index", FlipFault.FOREIGN_WRITE to "outside this flip",
        )
        for ((m, says) in mutations) {
            clean()
            stage1(shots("v", 25))
            val rows = shotsRows()
            HistoryStore.stage2Enabled = true
            HistoryFlip.FAULTS += m
            val s = store()
            assertEquals(HistoryHealth.OK, s.boot().health, "$m")
            assertFalse(s.onHistoryDb, "$m must fail verify")
            assertTrue(s.flipError!!.contains(says), "$m: ${s.flipError}")
            assertFalse(doneRecord.exists(), "$m")
            assertFalse(markerText()!!.contains("\"done\""), "$m")
            assertEquals(rows, shotsRows(), "$m: shots.db untouched")
            // NotReady is never a pass (REVIEW-448 note 5), and the file stays a leftover.
            assertTrue(Stage2Files(filesDir, coreJson).probe(historyDb) is FileProbe.Leftover, "$m")
            if (m != FlipFault.FOREIGN_WRITE) {
                // Cleared and built again from shots.db by the boot's index sync (M4): every row written anew.
                assertEquals(25, s.indexHealth().lastDiff?.upserted, "$m: the index was rebuilt")
            }
            s.closeForTest()
            HistoryFlip.FAULTS.clear()
            assertFlipsTo(rows, "after $m")
        }
    }

    @Test fun `a failed flip backs off - the next two boots, then at most daily`() = runBlocking {
        stage1(shots("b", 5))
        HistoryStore.stage2Enabled = true
        HistoryFlip.FAULTS += FlipFault.DISK_FULL
        repeat(3) { i ->
            val s = store()
            s.boot()
            assertNotNull(s.flipError, "try ${i + 1} ran and failed")
            s.closeForTest()
        }
        val m = coreJson.decodeFromString(Stage2Marker.serializer(), marker.readText())
        assertEquals(3, m.failures)
        assertTrue(m.nextTryAtMs > System.currentTimeMillis() + 23 * 3_600_000L)
        HistoryFlip.FAULTS.clear()
        val s = store()
        s.boot()
        assertFalse(s.onHistoryDb, "waits for its day")
        assertNull(s.flipError)
        s.closeForTest()
    }

    // ── Lost, foreign, damaged, recovered (SPEC §11, S9) ─────────────────────

    private suspend fun flipped(rows: List<StoredShot>): Map<String, Triple<String, String?, String?>> {
        stage1(rows)
        HistoryStore.stage2Enabled = true
        val s = store()
        s.boot()
        assertTrue(s.onHistoryDb, s.flipError)
        s.closeForTest()
        return historyRows()
    }

    @Test fun `marker done with history-db gone is lost, makes no file and never migrates again`() = runBlocking {
        flipped(shots("g", 10))
        for (suffix in listOf("", "-wal", "-shm")) File(historyDb.path + suffix).delete()
        val s = store()
        assertEquals(HistoryHealth.LOST, s.boot().health)
        assertFalse(historyDb.exists(), "no new empty file")
        assertNotNull(s.lostChoices?.oldCopyRows, "the old copy is offered")
        s.closeForTest()
    }

    @Test fun `the marker removed with the done-record left is lost, not a migration again`() = runBlocking {
        flipped(shots("d", 10))
        SafeFile.erase(marker)
        for (suffix in listOf("", "-wal", "-shm")) File(historyDb.path + suffix).delete()
        val s = store()
        assertEquals(HistoryHealth.LOST, s.boot().health)
        assertFalse(historyDb.exists())
        assertTrue(markerText()!!.contains("\"done\""), "rebuilt from the done-record")
        s.closeForTest()
    }

    @Test fun `the marker and done-record lost, the finished file rebuilds both`() = runBlocking {
        val rows = flipped(shots("r", 10))
        SafeFile.erase(marker)
        SafeFile.erase(doneRecord)
        val s = store()
        assertEquals(HistoryHealth.OK, s.boot().health)
        assertTrue(s.onHistoryDb)
        s.closeForTest()
        assertEquals(rows, historyRows(), "nothing copied again")
        assertTrue(markerText()!!.contains("\"done\"") && doneRecord.exists())
    }

    @Test fun `a foreign or damaged history-db is lost and renamed aside byte for byte, never deleted`() = runBlocking {
        for (kind in listOf("damaged", "foreign")) {
            clean()
            flipped(shots("c", 10))
            val planted = if (kind == "damaged") {
                historyDb.readBytes().also { b -> for (i in 100 until 4096) b[i] = 0x5A }
            } else {
                // Another SQLite file: no application_id of ours.
                for (suffix in listOf("", "-wal", "-shm")) File(historyDb.path + suffix).delete()
                SQLiteDatabase.openOrCreateDatabase(File(filesDir, "other.db"), null).use { it.execSQL("CREATE TABLE t(x)") }
                File(filesDir, "other.db").readBytes()
            }
            for (suffix in listOf("-wal", "-shm")) File(historyDb.path + suffix).delete()
            historyDb.writeBytes(planted)
            val s = store()
            assertEquals(HistoryHealth.LOST, s.boot().health, kind)
            s.closeForTest()
            val aside = sqliteDir.listFiles()!!.single { it.name.startsWith("history.db.corrupt-") && !it.name.endsWith("-wal") && !it.name.endsWith("-shm") }
            assertContentEquals(planted, aside.readBytes(), "$kind: kept exactly as it was")
        }
    }

    @Test fun `a recovery choice writes history-n-db, start empty stays empty, and a marker rebuild picks it`() = runBlocking {
        val rows = shots("e", 8)
        flipped(rows)
        for (suffix in listOf("", "-wal", "-shm")) File(historyDb.path + suffix).delete()
        val s = store()
        assertEquals(HistoryHealth.LOST, s.boot().health)
        val load = s.startEmpty()
        assertEquals(HistoryHealth.OK, load.health)
        assertTrue(s.onHistoryDb)
        assertEquals(emptyList(), load.view)
        s.closeForTest()
        assertEquals(1, markerGeneration())
        assertTrue(File(sqliteDir, "history-1.db").exists())
        // Rebooted: still empty. The old copy's rows aren't late rows (S9).
        val again = store()
        assertEquals(emptyList(), again.boot().view)
        again.closeForTest()
        // The marker and done-record lost: the highest finished generation is the recovery file.
        SafeFile.erase(marker)
        SafeFile.erase(doneRecord)
        val third = store()
        assertEquals(HistoryHealth.OK, third.boot().health)
        third.closeForTest()
        assertEquals(1, markerGeneration())
        // And the old copy, when chosen, comes back whole.
        for (suffix in listOf("", "-wal", "-shm")) File(sqliteDir, "history-1.db$suffix").delete()
        val fourth = store()
        assertEquals(HistoryHealth.LOST, fourth.boot().health)
        val old = fourth.recoverOldCopy()
        assertEquals(rows.map { it.id }.toSet(), old.view.map { it.id }.toSet())
        fourth.closeForTest()
        assertEquals(2, markerGeneration())
    }

    @Test fun `history-db written by a newer build is too new - update the app, nothing written to it`() = runBlocking {
        flipped(shots("n", 5))
        SQLiteDatabase.openDatabase(historyDb.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE meta SET value = '999' WHERE key = 'min_reader_v'")
        }
        val bytes = historyDb.readBytes()
        val s = store()
        val load = s.boot()
        assertTrue(s.tooNew)
        assertEquals(HistoryHealth.UNAVAILABLE, load.health)
        assertTrue(s.lastError!!.contains("update the app"))
        // A shot made now waits in stage 2's journal, never P3a's or shots.db.
        val shotsBefore = shotsRows()
        assertFalse(s.save(shots("later", 1, start = 1_900_000_000_000L)))
        s.closeForTest()
        assertContentEquals(bytes, historyDb.readBytes())
        assertEquals(shotsBefore, shotsRows())
        assertTrue(File(sqliteDir, Stage2Files.PENDING_NAME).readText().contains("later-0"))
        assertFalse(File(filesDir, HistoryStore.PENDING_NAME).exists(), "P3a's journal is never written after the flip")
    }

    // ── A shot during the flip (M9) ──────────────────────────────────────────

    @Test fun `a shot captured mid-flip lands in shots-db, then in history-db in the same boot`() = runBlocking {
        stage1(shots("p", HistoryFlip.BATCH * 2 + 10, samplesPer = 1))
        HistoryStore.stage2Enabled = true
        val s = store()
        val capture = shots("cap", 1, start = 1_900_000_000_000L, samplesPer = 6).single()
        HistoryFlip.betweenBatchesForTest = {
            HistoryFlip.betweenBatchesForTest = null
            // The lock isn't held between batches: the capture's save gets through to shots.db.
            assertTrue(s.save(listOf(capture)))
        }
        val load = s.boot()
        assertTrue(s.onHistoryDb, s.flipError)
        assertTrue(load.view.any { it.id == capture.id }, "shown")
        assertEquals(capture.samples, assertNotNull(s.loadShot(capture.id)).samples)
        s.closeForTest()
        assertTrue(capture.id in shotsRows(), "it went to shots.db, the truth then")
        val h = historyRows()
        assertTrue(capture.id in h, "and into history.db in the same boot")
        assertEquals(shotsRows().getValue(capture.id), h.getValue(capture.id), "with its heavy parts")
    }

    @Test fun `a shot captured mid-flip, then a kill, is in history-db after the next boot`() = runBlocking {
        stage1(shots("q", HistoryFlip.BATCH * 2 + 10, samplesPer = 1))
        HistoryStore.stage2Enabled = true
        val s = store()
        val capture = shots("kill", 1, start = 1_900_000_000_000L).single()
        HistoryFlip.betweenBatchesForTest = {
            HistoryFlip.betweenBatchesForTest = null
            assertTrue(s.save(listOf(capture)))
            HistoryFlip.FAULTS += FlipFault.AFTER_HEAVY
        }
        assertFailsWith<SimulatedCrash> { s.boot() }
        s.closeForTest()
        assertFlipsTo(shotsRows(), "after the kill")
        assertTrue(capture.id in historyRows())
    }

    @Test fun `an edit of an older row during the flip fails this try, and the next passes with it`() = runBlocking {
        val rows = shots("x", HistoryFlip.BATCH * 2 + 10, samplesPer = 1)
        stage1(rows)
        HistoryStore.stage2Enabled = true
        val s = store()
        HistoryFlip.betweenBatchesForTest = {
            HistoryFlip.betweenBatchesForTest = null
            assertTrue(s.save(listOf(rows[3].copy(notes = "edited during the flip"))))
        }
        s.boot()
        assertFalse(s.onHistoryDb, "only new captures may land during a flip (M2)")
        assertTrue(s.flipError!!.startsWith("Verify failed"), s.flipError)
        s.closeForTest()
        assertFlipsTo(shotsRows(), "the next boot")
        assertTrue(historyRows().getValue(rows[3].id).first.contains("edited during the flip"))
    }

    // ── After the flip: an older build, late rows (steps 8 and 9, S13, N9) ───

    /** An older build's session: stage 1 on shots.db, whatever the stage-2 marker says. */
    private suspend fun olderBuild(block: suspend (HistoryStore) -> Unit) {
        HistoryStore.stage2Enabled = false
        val old = store()
        old.boot()
        block(old)
        old.closeForTest()
        HistoryStore.stage2Enabled = true
    }

    @Test fun `an older build's writes after the flip come in as late rows, by id, never by MAX(rowid)`() = runBlocking {
        val rows = shots("o", 12)
        flipped(rows)
        val newest = rows.maxBy { it.completedAtMs }
        val recorded = shots("old", 1, start = 1_700_000_000_000L).single()
        // Deletes the newest row, then records one: the count and the max rowid stay put.
        olderBuild { old ->
            old.delete(listOf(newest.id))
            assertTrue(old.save(listOf(recorded)))
        }
        val s = store()
        val load = s.boot()
        assertTrue(s.onHistoryDb)
        assertTrue(assertNotNull(s.lastLate).read)
        assertTrue(load.view.any { it.id == recorded.id }, "the older build's shot is a late row")
        // Its delete is lost at roll-forward: the row comes back from history.db, its curve gone with its details.
        val back = assertNotNull(load.view.firstOrNull { it.id == newest.id }, "rule 1: never deleted by absence")
        assertFalse(s.hasCurve(back), "curve missing")
        assertTrue(back.summary.sampleCount != 0u, "never a manual log")
        s.closeForTest()
    }

    @Test fun `a row deleted in history-db doesn't come back, and a pulled duplicate is skipped by Visualizer id`() = runBlocking {
        val rows = shots("z", 10)
        flipped(rows)
        val s = store()
        s.boot()
        s.delete(listOf(rows[2].id))
        val bound = s.rows().first { it.visualizerId != null }
        s.closeForTest()
        val dup = shots("dup", 1, start = 1_700_000_000_000L).single().copy(visualizerId = bound.visualizerId)
        olderBuild { old -> assertTrue(old.save(listOf(dup, rows[0].copy(notes = "older build's edit")))) }
        val next = store()
        val load = next.boot()
        assertFalse(load.view.any { it.id == rows[2].id }, "deleted on stage 2: stays deleted")
        assertFalse(load.view.any { it.id == dup.id }, "pulled twice, stored once (N8)")
        assertFalse(load.view.first { it.id == rows[0].id }.notes.orEmpty().contains("older build"), "history.db wins over an older build's edit")
        next.closeForTest()
    }

    @Test fun `a late row with no series fills when its series lands, and the old journal imports once`() = runBlocking {
        flipped(shots("l", 4))
        val bare = shots("bare", 1, start = 1_700_000_000_000L, samplesPer = 0).single()
        olderBuild { old -> assertTrue(old.save(listOf(bare))) }
        val s = store()
        s.boot()
        assertEquals(0u, s.row(bare.id)?.summary?.sampleCount)
        s.closeForTest()
        // The older build writes the series after the row.
        val samples = HistoryFixtures.samples(9, 3)
        SeriesFiles(File(filesDir, "shot-series"), coreJson).write(bare.id, samples)
        val next = store()
        next.boot()
        assertEquals(listOf(bare.id), next.lastLate?.filled)
        assertEquals(9u, next.row(bare.id)?.summary?.sampleCount)
        next.closeForTest()
        // P3a's journal: an older build's line, read as late rows, never written, imported once.
        val journaled = shots("jr", 1, start = 1_700_000_100_000L).single()
        val line = coreJson.encodeToString(JournalEntry.serializer(), JournalEntry(put = journaled.copy(samples = emptyList()))) + "\n"
        val journal = File(filesDir, HistoryStore.PENDING_NAME).apply { writeText(line) }
        repeat(2) {
            val t = store()
            assertEquals(1, t.boot().view.count { it.id == journaled.id })
            t.closeForTest()
        }
        assertEquals(line, journal.readText(), "P3a's journal stays as the older build left it")
    }

    @Test fun `the prefilter skips the id read when shots-db is unchanged, and runs it when it moved (N9)`() = runBlocking {
        flipped(shots("pf", 5))
        val s = store()
        s.boot()
        assertEquals(false, s.lastLate?.read, "nothing moved: no id read")
        s.closeForTest()
        olderBuild { old -> assertTrue(old.save(shots("moved", 1, start = 1_700_000_000_000L))) }
        val t = store()
        t.boot()
        assertEquals(true, t.lastLate?.read)
        assertEquals(listOf("moved-0"), t.lastLate?.imported)
        t.closeForTest()
        val u = store()
        u.boot()
        assertEquals(false, u.lastLate?.read, "the baseline moved with the read")
        u.closeForTest()
    }

    @Test fun `a stage-1 build after the flip rebuilds its index, and never opens or changes history-db`() = runBlocking {
        flipped(shots("t", 6))
        val before = historyDb.readBytes()
        olderBuild { old ->
            assertFalse(old.onHistoryDb)
            assertTrue(old.save(shots("s1", 2, start = 1_700_000_000_000L)))
            assertNotNull(old.compareIndex())
        }
        assertContentEquals(before, historyDb.readBytes(), "an older build's session leaves history.db alone")
        assertTrue(File(sqliteDir, Stage2Files.SNAPSHOT_NAME).let { !it.exists() || it.length() > 0 })
    }

    @Test fun `a read-only open of a WAL shots-db, with and without -shm, works and changes no byte (S12)`() = runBlocking {
        stage1(shots("ro", 20))
        // A connection that holds the WAL open: shots.db, -wal and -shm all present.
        val writer = SQLiteDatabase.openDatabase(shotsDb.path, null, SQLiteDatabase.OPEN_READWRITE)
        writer.enableWriteAheadLogging()
        writer.execSQL("UPDATE shots SET completed_at = completed_at WHERE id = 'ro-0'")
        for (withShm in listOf(true, false)) {
            if (!withShm) writer.close()
            val before = bytesOfShotsDb()
            assertEquals(withShm, "-shm" in before, "the case under test")
            val ids = runCatching { ReadOnlyShots.open(shotsDb)!!.use { it.ids() } }
            // Either it reads every row, or it fails cleanly; never a partial answer.
            ids.onSuccess { assertEquals(20, it.size, "shm=$withShm") }
            val after = bytesOfShotsDb()
            // The data files, byte for byte. In -shm a reader takes a read mark (its bytes 100 to 119,
            // SQLite's lock bookkeeping, rebuilt from the -wal by any connection); the WAL-index
            // headers before them, which say what the -wal holds, stay as they were.
            assertSameBytes(before - "-shm", after, "shm=$withShm")
            before["-shm"]?.let { assertContentEquals(it.copyOfRange(0, 96), after.getValue("-shm").copyOfRange(0, 96), "the WAL-index headers") }
        }
    }

    @Test fun `a write that fails on history-db goes to stage 2's journal and lands at the next boot`() = runBlocking {
        flipped(shots("w", 3))
        val s = store()
        s.boot()
        HistoryStore.faults += MigrationFault.DISK_FULL
        val shot = shots("full", 1, start = 1_900_000_000_000L).single()
        assertFalse(s.save(listOf(shot)))
        HistoryStore.faults.clear()
        assertTrue(shot.id in s.journaled)
        s.closeForTest()
        assertTrue(File(sqliteDir, Stage2Files.PENDING_NAME).readText().contains(shot.id))
        assertFalse(File(filesDir, HistoryStore.PENDING_NAME).exists())
        val next = store()
        assertTrue(next.boot().view.any { it.id == shot.id })
        next.closeForTest()
        assertFalse(File(sqliteDir, Stage2Files.PENDING_NAME).exists(), "merged, then gone")
        assertTrue(shot.id in historyRows())
    }

    // ── A week on history.db: shot_heavy (M10), the old copy (S13) ───────────

    @Test fun `shot_heavy stays equal to what was written over a simulated week, and shots-db never changes`() = runBlocking {
        val rows = shots("wk", 30)
        flipped(rows)
        val oldCopy = bytesOfShotsDb()
        // The model: each row's profile and brew series as they should stand.
        val expected = rows.associate { it.id to (it.profile to it.brewSeries) }.toMutableMap()
        for (day in 0 until 7) {
            val s = store()
            s.boot()
            // A light edit: no heavy part carried, both kept.
            assertNotNull(s.update(rows[day].id) { it.copy(rating = day % 5 + 1, notes = "day $day") })
            // A restore-like write of a row without a profile: the stored one stays.
            assertTrue(s.save(listOf(rows[day + 7].copy(profile = null, notes = "restored $day"))))
            // A backfill with a new profile.
            val p = HistoryFixtures.profile(day + 40)
            assertTrue(s.save(listOf(rows[day + 14].copy(profile = p))))
            expected[rows[day + 14].id] = p to expected.getValue(rows[day + 14].id).second
            // A new capture, whole.
            val cap = shots("cap$day", 1, start = 1_900_000_000_000L + day).single()
            assertTrue(s.save(listOf(cap)))
            expected[cap.id] = cap.profile to cap.brewSeries
            assertTrue(s.flush())
            s.closeForTest()
        }
        val h = historyRows()
        for ((id, want) in expected) {
            val got = h.getValue(id)
            assertEquals(want.first?.toString(), got.second, "$id: profile")
            assertEquals(want.second?.let { coreJson.encodeToString(coffee.crema.core.BrewSeries.serializer(), it) }, got.third, "$id: brew series")
        }
        assertSameBytes(oldCopy, bytesOfShotsDb(), "a week on history.db")
    }

    // ── Backups, restore and erase on history.db (R1a, S6, M3) ───────────────

    @Test fun `on history-db a backup walks the export walk, holds a delete, and releases it rows first`() = runBlocking {
        val rows = shots("bk", 12)
        flipped(rows)
        val s = store()
        s.boot()
        val missing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val src = assertNotNull(s.backupSource { s.loadFull(it, missing) }, "the seam is the SQLite store")
        val mark = assertNotNull(src.writeMark())
        val walk = src.openWalk()
        assertEquals(12, walk.count)
        val gone = rows[5].id
        assertTrue(SeriesFiles(File(filesDir, "shot-series"), coreJson).gzFile(gone).exists(), "its curve is there before")
        s.delete(listOf(gone))
        assertTrue(SeriesFiles(File(filesDir, "shot-series"), coreJson).gzFile(gone).exists(), "held: its curve stays for the walk")
        val read = generateSequence { walk.next(5).takeIf { it.isNotEmpty() } }.flatten().toList()
        assertEquals(12, read.size, "the header's count is the lines written")
        assertEquals(rows.first { it.id == gone }.samples, walk.full(read.first { it.id == gone }.row!!).samples, "read whole")
        walk.close()
        assertFalse(SeriesFiles(File(filesDir, "shot-series"), coreJson).gzFile(gone).exists(), "released: row, then curve")
        assertTrue(mark != src.writeMark(), "every write moves the mark")
        // A curve written with no row write moves it too (REVIEW-466 note 1).
        val before = src.writeMark()
        s.writeSeriesOf(listOf(rows[6]))
        assertTrue(before != src.writeMark())
        s.closeForTest()
    }

    @Test fun `on history-db a restore's wipe takes only rows made before it began (M3)`() = runBlocking {
        val rows = shots("rw", 6)
        flipped(rows)
        val s = store()
        s.boot()
        val begin = assertNotNull(s.restoreBegin())
        // A shot captured during the restore, and a row deleted and made again.
        val cap = shots("during", 1, start = 1_900_000_000_000L).single()
        assertTrue(s.save(listOf(cap)))
        s.delete(listOf(rows[1].id))
        assertTrue(s.save(listOf(rows[1])))
        s.restoreRemove(listOf(cap.id, rows[1].id, rows[2].id, rows[3].id), backupIds = setOf(rows[3].id), beginSeq = begin)
        val ids = s.rows().map { it.id }.toSet()
        assertTrue(cap.id in ids && rows[1].id in ids, "made after the restore began: kept")
        assertTrue(rows[3].id in ids, "the backup holds it: kept")
        assertFalse(rows[2].id in ids, "named, old, not in the backup: removed")
        s.closeForTest()
    }

    @Test fun `erase after the flip closes history-db first and leaves no file of either stage`() = runBlocking {
        flipped(shots("er", 5))
        val s = store()
        s.boot()
        assertEquals(emptyList(), s.erase())
        assertFalse(sqliteDir.exists())
        assertFalse(marker.exists())
        assertFalse(shotsDb.exists())
        val again = store()
        assertEquals(emptyList(), again.boot().view)
        again.closeForTest()
    }
}
