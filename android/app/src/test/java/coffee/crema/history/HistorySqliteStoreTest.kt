package coffee.crema.history

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import coffee.crema.core.ShotSummary
import coffee.crema.persist.KeptAsideNotices
import coffee.crema.persist.SafeFile
import coffee.crema.ui.coreJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #310 P3a, in Robolectric with real SQLite: the rows move from `shots.json`
 * into `shots.db`, every field and series intact, the old copy's bytes
 * unchanged; an interrupted move ends where an uninterrupted one does; a
 * failed one leaves the legacy backend working and backs off; a damaged or
 * missing store is lost, never re-migrated; an old build's later rows merge.
 * Each verify check has a mutation that must fail it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistorySqliteStoreTest {
    /** P3a's `shots.db` read directly: stage 1, a history that hasn't flipped (#310 A5). */
    @get:org.junit.Rule val stage2 = coffee.crema.history.Stage2Rule(enabled = false)

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val filesDir get() = app.filesDir
    private val seriesDir get() = File(filesDir, "shot-series")
    private val shotsJson get() = File(filesDir, HistoryStore.FILE_NAME)
    private val marker get() = File(filesDir, HistoryStore.MARKER_NAME)
    private val dbFile get() = app.getDatabasePath(HistoryStore.NEXT_DB_NAME)

    private fun store() = HistoryStore(app, coreJson)

    @Before fun clean() {
        SafeFile.openAllForTest()
        HistoryStore.faults.clear()
        HistoryStore.freeSpaceForTest = null
        runBlocking { store().erase() }
        seriesDir.deleteRecursively()
        KeptAsideNotices.drain()
    }

    @After fun tidy() {
        HistoryStore.faults.clear()
        HistoryStore.freeSpaceForTest = null
        SeriesFiles.beforeWrite = {}
        QuarantineHandler.renameFile = { from, to -> from.renameTo(to) }
        QuarantineHandler.deleteFile = { it.delete() }
        SafeFile.resetForTest()
    }

    /** Source rows the way a load gives them back: newest first, ties by id, ids unique. */
    private fun expected(rows: List<StoredShot>) = rows.distinctBy { it.id }.sortedWith(HistoryStore.ORDER)

    /** The old copy: `shots.json` and every `.json` series, by name and bytes. */
    private fun oldCopy(): Map<String, List<Byte>> {
        val files = HistoryFixtures.filesUnder(filesDir).filterKeys { it == HistoryStore.FILE_NAME || (it.startsWith("shot-series") && it.endsWith(".json")) }
        return files.mapValues { it.value.toList() }
    }

    /** A row's stored summary, read from the file itself. */
    private fun storedSummary(id: String): ShotSummary? {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT row_json FROM shots WHERE id = ?", arrayOf(id)).use { c ->
                if (!c.moveToFirst()) return null
                val obj = coreJson.parseToJsonElement(c.getString(0)).jsonObject
                return obj["summary"]?.let { coreJson.decodeFromJsonElement(ShotSummary.serializer(), it) }
            }
        } finally {
            db.close()
        }
    }

    private fun rowCount(): Int {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return db.rawQuery("SELECT COUNT(*) FROM shots", null).use { c -> c.moveToFirst(); c.getInt(0) }
        } finally {
            db.close()
        }
    }

    private fun markerText() = marker.takeIf { it.exists() }?.readText().orEmpty()

    // ── The move itself ──────────────────────────────────────────────────────

    @Test
    fun `a 300-row real-shaped history moves with every field and series, and the old copy is unchanged`() = runBlocking {
        val rows = HistoryFixtures.rows(300)
        val inline = setOf(rows[1].id, rows[2].id)
        val legacy = rows.filter { it.id.contains(':') && it.samples.isNotEmpty() && it.id !in inline }.take(2).map { it.id }.toSet()
        HistoryFixtures.plantOldBuild(app, rows, inline = inline, legacyName = legacy)
        val before = oldCopy()

        val s = store()
        val load = s.loadAll()
        assertEquals(HistoryHealth.OK, load.health)
        assertEquals(300, load.view.size)
        assertEquals(expected(rows), store().load(), "every row, field and series")
        assertEquals(before, oldCopy(), "the old copy's bytes are unchanged")
        assertContains(markerText(), "\"done\"")
        for (id in inline) assertTrue(SeriesFiles(seriesDir, coreJson).gzFile(id).exists(), "an inline series went to its gz file")
        // Every summary matches the series it came from.
        for (row in rows.take(30)) {
            val summary = assertNotNull(storedSummary(row.id))
            assertEquals(row.samples.size.toUInt(), summary.sampleCount)
            assertEquals(signatureOfShot(row), signatureOfRow(row.copy(samples = emptyList()), summary))
        }
    }

    @Test
    fun `a 5,000-row oversized history moves whole, and every row is in view`() = runBlocking {
        val rows = HistoryFixtures.rows(5_000, samplesPer = 12)
        HistoryFixtures.plantOldBuild(app, rows, inline = setOf(rows[0].id))
        val s = store()
        val load = s.loadAll()
        assertEquals(HistoryHealth.OK, load.health)
        // No cap from P6a (#310): the view holds every row, newest first.
        assertNull(s.viewCap)
        assertEquals(expected(rows).map { it.id }, load.view.map { it.id })
        assertEquals(0, load.hiddenCount)
        assertTrue(s.hiddenRows(load.view.mapTo(HashSet()) { it.id }).isEmpty())
        assertEquals(5_000, rowCount())
        assertEquals(expected(rows), store().load(), "every row, field and series, hidden ones too")
    }

    @Test
    fun `duplicate ids keep the first row and are counted`() = runBlocking {
        val rows = HistoryFixtures.rows(5)
        HistoryFixtures.plantOldBuild(app, rows + rows[2].copy(notes = "the second copy"))
        assertEquals(expected(rows), store().load())
        assertEquals(rows[2].notes, store().load().first { it.id == rows[2].id }.notes)
    }

    @Test
    fun `no shots json is an empty store, marked done`() = runBlocking {
        val load = store().loadAll()
        assertEquals(HistoryHealth.OK, load.health)
        assertTrue(load.view.isEmpty())
        assertContains(markerText(), "\"done\"")
        assertTrue(dbFile.exists())
    }

    @Test
    fun `a corrupt shots json with a good bak moves the backup's rows`() = runBlocking {
        val rows = HistoryFixtures.rows(4)
        HistoryFixtures.plantOldBuild(app, rows)
        shotsJson.copyTo(SafeFile.backupOf(shotsJson))
        shotsJson.writeText("{ not json")
        assertEquals(expected(rows), store().load())
        assertTrue(KeptAsideNotices.drain().any { it.olderCopy }, "said to come from its backup")
    }

    @Test
    fun `a complete tmp left by a killed save is recovered`() = runBlocking {
        val rows = HistoryFixtures.rows(3)
        HistoryFixtures.plantOldBuild(app, rows)
        shotsJson.renameTo(File(filesDir, HistoryStore.FILE_NAME + ".tmp"))
        assertEquals(expected(rows), store().load())
    }

    @Test
    fun `a corrupt shots json is kept aside and moves as nothing`() = runBlocking {
        shotsJson.writeText("{ not json")
        val load = store().loadAll()
        assertEquals(HistoryHealth.OK, load.health)
        assertTrue(load.view.isEmpty())
        assertTrue(SafeFile.keptAside(shotsJson).isNotEmpty())
    }

    @Test
    fun `a corrupt series keeps its row with an unknown sample count`() = runBlocking {
        val rows = HistoryFixtures.rows(3)
        HistoryFixtures.plantOldBuild(app, rows)
        File(seriesDir, HistoryStore.seriesFileName(rows[1].id)).writeText("[ broken")
        val s = store()
        assertEquals(HistoryHealth.OK, s.loadAll().health)
        assertEquals(3, rowCount())
        assertNull(assertNotNull(storedSummary(rows[1].id).also { assertNotNull(it) }).sampleCount, "unknown, not 0")
        assertEquals(rows[0].samples.size.toUInt(), storedSummary(rows[0].id)?.sampleCount)
        assertFalse(s.hasCurve(rows[1].id), "and not manual: brewMethod is null")
    }

    @Test
    fun `a series read error aborts the move`() = runBlocking {
        val rows = HistoryFixtures.rows(2)
        HistoryFixtures.plantOldBuild(app, rows)
        // A directory where the file goes: it exists, and can't be read.
        val f = File(seriesDir, HistoryStore.seriesFileName(rows[0].id))
        f.delete()
        f.mkdirs()
        File(f, "x").writeText("x")
        val s = store()
        assertEquals(HistoryHealth.LEGACY, s.loadAll().health)
        assertContains(s.lastError.orEmpty(), "telemetry")
    }

    @Test
    fun `the store runs with WAL and full sync`() {
        runBlocking { store().loadAll() }
        val db = HistoryDb(app, HistoryStore.NEXT_DB_NAME).writableDatabase
        db.rawQuery("PRAGMA journal_mode", null).use { c -> c.moveToFirst(); assertEquals("wal", c.getString(0).lowercase()) }
        db.rawQuery("PRAGMA synchronous", null).use { c -> c.moveToFirst(); assertEquals(2, c.getInt(0), "FULL") }
        db.close()
    }

    // ── Interrupted runs ─────────────────────────────────────────────────────

    private fun interrupted(fault: MigrationFault) = runBlocking {
        val rows = HistoryFixtures.rows(40)
        HistoryFixtures.plantOldBuild(app, rows, inline = setOf(rows[0].id, rows[5].id))
        val before = oldCopy()
        HistoryStore.faults += fault
        assertFailsWith<SimulatedCrash> { store().loadAll() }
        HistoryStore.faults.clear()
        // The next launch: the same final state as a run nothing stopped.
        val s = store()
        assertEquals(HistoryHealth.OK, s.loadAll().health, "after $fault")
        assertEquals(expected(rows), store().load(), "after $fault")
        assertEquals(before, oldCopy(), "after $fault")
        assertContains(markerText(), "\"done\"")
    }

    @Test fun `a crash inside the transaction ends as a run nothing stopped`() = interrupted(MigrationFault.CRASH_IN_TRANSACTION)

    @Test fun `a crash after the commit and before the marker ends the same, never clearing`() = interrupted(MigrationFault.CRASH_AFTER_COMMIT)

    @Test fun `a crash while writing series ends the same`() = interrupted(MigrationFault.CRASH_IN_SERIES)

    @Test
    fun `after a commit with no marker the store's own flag is trusted, and nothing is cleared`() = runBlocking {
        val rows = HistoryFixtures.rows(5)
        HistoryFixtures.plantOldBuild(app, rows)
        HistoryStore.faults += MigrationFault.CRASH_AFTER_COMMIT
        assertFailsWith<SimulatedCrash> { store().loadAll() }
        HistoryStore.faults.clear()
        assertFalse(marker.exists())
        // A row the user added on the store before a re-copy could clear it: it stays.
        val db = HistoryDb(app, HistoryStore.NEXT_DB_NAME).writableDatabase
        HistoryTables(coreJson).write(db) { HistoryTables(coreJson).putFull(this, StoredShot(id = "added", completedAtMs = 2_000_000_000_000L, durationMs = 1), summaryHere(StoredShot(id = "added", completedAtMs = 1, durationMs = 1))) }
        db.close()
        val all = store().load()
        assertTrue(all.any { it.id == "added" }, "never cleared")
        assertEquals(6, all.size)
        assertContains(markerText(), "\"done\"")
    }

    // ── Verify: each check has a mutation that must fail it ───────────────────

    private fun mutated(fault: MigrationFault, check: String) = runBlocking {
        val rows = HistoryFixtures.rows(12)
        // The last row is a machine shot with a scale and a profile, and an early one is inline.
        HistoryFixtures.plantOldBuild(app, rows, inline = setOf(rows[0].id))
        HistoryStore.faults += fault
        val s = store()
        val load = s.loadAll()
        HistoryStore.faults.clear()
        assertEquals(HistoryHealth.LEGACY, load.health, "$fault must fail verify")
        assertContains(s.lastError.orEmpty(), "Verify failed: $check", message = "$fault")
        assertFalse(dbFile.exists(), "the failed copy is cleared, holding no disk")
        assertContains(markerText(), "\"failed\"")
        assertEquals(rows.map { it.id }.toSet(), load.view.map { it.id }.toSet(), "the legacy backend shows the rows")
    }

    @Test fun `verify fails a missing row`() = mutated(MigrationFault.DROP_ROW, "ids")
    @Test fun `verify fails a row the source doesn't have`() = mutated(MigrationFault.EXTRA_ROW, "ids")
    @Test fun `verify fails a changed row`() = mutated(MigrationFault.ALTER_ROW, "row")
    @Test fun `verify fails a changed profile`() = mutated(MigrationFault.ALTER_PROFILE, "details")
    @Test fun `verify fails a changed series`() = mutated(MigrationFault.ALTER_SERIES, "series")
    @Test fun `verify fails a wrong sample count`() = mutated(MigrationFault.ALTER_COUNT, "sample count")
    @Test fun `verify fails a changed de-dup signature`() = mutated(MigrationFault.ALTER_FINAL_WEIGHT, "signature")

    // ── Failure and backoff ──────────────────────────────────────────────────

    @Test
    fun `a full disk fails the move, clears the store, and the legacy backend keeps working`() = runBlocking {
        val rows = HistoryFixtures.rows(6)
        HistoryFixtures.plantOldBuild(app, rows)
        HistoryStore.freeSpaceForTest = { 0L }
        val s = store()
        val load = s.loadAll()
        assertEquals(HistoryHealth.LEGACY, load.health)
        assertContains(s.lastError.orEmpty(), "free space")
        assertFalse(dbFile.exists())
        assertEquals(expected(rows).map { it.id }, load.view.map { it.id })
        // Legacy saves go to shots.json, as before P3a.
        s.openSaves()
        val added = StoredShot(id = "new", completedAtMs = rows.first().completedAtMs + 1, durationMs = 1)
        assertTrue(s.save(listOf(s.stage(added)) + load.view))
        assertTrue(shotsJson.readText().contains("\"new\""))
    }

    private fun gzCount() = seriesDir.listFiles().orEmpty().count { it.name.endsWith(SeriesFiles.GZ) }

    @Test
    fun `a move that fails part-way through its series takes back the gz copies it made`() = runBlocking {
        val rows = HistoryFixtures.rows(10, samplesPer = 5)
        HistoryFixtures.plantOldBuild(app, rows)
        var n = 0
        SeriesFiles.beforeWrite = { if (++n > 6) throw java.io.IOException("ENOSPC") }
        val s = store()
        assertEquals(HistoryHealth.LEGACY, s.loadAll().health)
        assertContains(s.lastError.orEmpty(), "telemetry")
        assertEquals(0, gzCount(), "a failure holds no disk (#408 F2)")
        SeriesFiles.beforeWrite = {}
        assertEquals(HistoryHealth.OK, store().loadAll().health)
        assertEquals(expected(rows), store().load(), "the next run copies every curve")
    }

    @Test
    fun `the free-space check counts the move's gz copies`() = runBlocking {
        val rows = HistoryFixtures.rows(10, samplesPer = 60)
        HistoryFixtures.plantOldBuild(app, rows)
        // Room for 2.5x shots.json, and not for the series copies too.
        val rowsOnly = shotsJson.length() * 5 / 2
        HistoryStore.freeSpaceForTest = { rowsOnly }
        val s = store()
        assertEquals(HistoryHealth.LEGACY, s.loadAll().health)
        assertContains(s.lastError.orEmpty(), "free space")
        assertEquals(0, gzCount())
    }

    @Test
    fun `a failure retries at the next two launches, then at most once a day`() = runBlocking {
        HistoryFixtures.plantOldBuild(app, HistoryFixtures.rows(3))
        HistoryStore.freeSpaceForTest = { 0L }
        repeat(3) { store().loadAll() }
        assertContains(markerText(), "\"failures\":3")
        // Space is back, but the day isn't over: no attempt.
        HistoryStore.freeSpaceForTest = null
        val s = store()
        assertEquals(HistoryHealth.LEGACY, s.loadAll().health)
        assertFalse(dbFile.exists(), "no attempt was made")
        // Settings' Try again goes now.
        assertEquals(HistoryHealth.OK, s.retry().health)
        assertEquals(3, store().loadRows().size)
    }

    @Test
    fun `the second failure still retries at the next launch`() = runBlocking {
        HistoryFixtures.plantOldBuild(app, HistoryFixtures.rows(3))
        HistoryStore.freeSpaceForTest = { 0L }
        repeat(2) { store().loadAll() }
        HistoryStore.freeSpaceForTest = null
        assertEquals(HistoryHealth.OK, store().loadAll().health)
    }

    // ── Lost and unavailable ─────────────────────────────────────────────────

    @Test
    fun `a corrupt shots db is quarantined, nothing is deleted, and it's lost, never re-migrated`() = runBlocking {
        val rows = HistoryFixtures.rows(5)
        HistoryFixtures.plantOldBuild(app, rows)
        val s = store()
        s.loadAll()
        s.save(s.loadRows())
        val before = oldCopy()
        // The file damaged at rest.
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        dbFile.writeBytes(ByteArray(8192) { (it * 31).toByte() })

        val next = store()
        val load = next.loadAll()
        assertEquals(HistoryHealth.LOST, load.health)
        assertTrue(load.view.isEmpty(), "nothing re-migrated")
        val kept = dbFile.parentFile!!.listFiles()!!.filter { it.name.startsWith(HistoryStore.NEXT_DB_NAME + ".corrupt-") }
        assertTrue(kept.isNotEmpty(), "the damaged file is kept aside, not deleted")
        assertEquals(before, oldCopy())
        assertFalse(next.save(listOf(StoredShot(id = "during", completedAtMs = 1, durationMs = 1))), "never written to")
        // Another launch: still lost, still nothing copied.
        assertEquals(HistoryHealth.LOST, store().loadAll().health)
        assertNotNull(store().also { it.loadAll() }.lostChoices?.oldCopyRows)
        // The user's choice: the old copy, and the shot made meanwhile from the journal.
        val back = store().recoverOldCopy()
        assertEquals(HistoryHealth.OK, back.health)
        val all = store().load().map { it.id }.toSet()
        assertEquals(rows.map { it.id }.toSet() + "during", all)
    }

    // ── #408 N9: a -wal that won't move never stays beside a fresh shots.db ──

    private val oldWal = ByteArray(4096) { (it * 7 + 3).toByte() }

    /** A migrated store damaged at rest, its -wal (the old db's frames) still beside it. */
    private suspend fun damagedWithWal() {
        migratedWithPostShot()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-wal").writeBytes(oldWal)
        dbFile.writeBytes(ByteArray(8192) { (it * 31).toByte() })
    }

    @Test
    fun `a -wal that won't rename is copied and removed before the main file moves`() {
        val dir = File(filesDir, "q").apply { mkdirs() }
        val main = File(dir, "x.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val wal = File(main.path + "-wal").apply { writeBytes(oldWal) }
        QuarantineHandler.renameFile = { from, to -> if (from == wal) false else from.renameTo(to) }
        val h = QuarantineHandler { 42L }
        assertTrue(h.quarantine(main))
        assertFalse(wal.exists(), "nothing of the old db stays beside a fresh one")
        assertTrue(File(main.path + ".corrupt-42-wal").readBytes().contentEquals(oldWal), "its frames kept aside")
        assertEquals(File(main.path + ".corrupt-42"), h.quarantined)
    }

    @Test
    fun `a -wal that can't be moved or removed fails the quarantine, and no fresh db opens beside it`() = runBlocking {
        damagedWithWal()
        val damaged = dbFile.readBytes()
        val wal = File(dbFile.path + "-wal")
        QuarantineHandler.renameFile = { from, to -> if (from == wal) false else from.renameTo(to) }
        QuarantineHandler.deleteFile = { f -> if (f == wal) false else f.delete() }
        val health = store().loadAll().health
        assertTrue(health == HistoryHealth.UNAVAILABLE || health == HistoryHealth.LOST, "never OK: $health")
        assertTrue(dbFile.readBytes().contentEquals(damaged), "the damaged file stays where it is: no fresh db took its place")
        assertTrue(wal.readBytes().contentEquals(oldWal), "and its -wal with it")
        assertFalse(dbFile.parentFile!!.listFiles()!!.any { it.name.startsWith(HistoryStore.NEXT_DB_NAME + ".corrupt-") }, "nothing half moved")
    }

    @Test
    fun `marker done with the store missing is lost, and no empty store appears`() = runBlocking {
        HistoryFixtures.plantOldBuild(app, HistoryFixtures.rows(3))
        store().loadAll()
        app.deleteDatabase(HistoryStore.NEXT_DB_NAME)
        val s = store()
        assertEquals(HistoryHealth.LOST, s.loadAll().health)
        assertFalse(dbFile.exists(), "no empty database")
        assertContains(markerText(), "\"lost\"")
        // Starting empty is a choice, and doesn't bring the old copy back as late rows.
        assertEquals(HistoryHealth.OK, s.startEmpty().health)
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `the daily snapshot rebuilds a lost store`() = runBlocking {
        val rows = HistoryFixtures.rows(4)
        HistoryFixtures.plantOldBuild(app, rows)
        val s = store()
        s.loadAll()
        val added = StoredShot(id = "since", completedAtMs = rows.first().completedAtMs + 10, durationMs = 5, notes = "after the move")
        s.save(listOf(added))
        assertTrue(File(filesDir, HistoryStore.SNAPSHOT_NAME).exists(), "the first save of the day writes it")
        app.deleteDatabase(HistoryStore.NEXT_DB_NAME)
        val lost = store()
        assertEquals(HistoryHealth.LOST, lost.loadAll().health)
        assertEquals(5, lost.lostChoices?.snapshotRows)
        assertEquals(HistoryHealth.OK, lost.restoreSnapshot().health)
        val back = store().load()
        assertEquals(rows.map { it.id }.toSet() + "since", back.map { it.id }.toSet())
        assertEquals(expected(rows), back.filter { it.id != "since" }, "profiles, brew series and curves")
    }

    @Test
    fun `a store a newer build wrote refuses writes, and the journal keeps new shots until it opens`() = runBlocking {
        HistoryFixtures.plantOldBuild(app, HistoryFixtures.rows(2))
        store().loadAll()
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        db.version = HistoryDb.VERSION + 1
        db.close()

        val s = store()
        assertEquals(HistoryHealth.UNAVAILABLE, s.loadAll().health)
        assertContains(s.lastError.orEmpty(), "update the app")
        val shot = StoredShot(id = "new", completedAtMs = 2_000_000_000_000L, durationMs = 1, samples = HistoryFixtures.samples(5, 1))
        assertFalse(s.save(listOf(shot)))
        assertTrue(File(filesDir, HistoryStore.PENDING_NAME).exists(), "the row went to the pending journal")
        assertTrue(SeriesFiles(seriesDir, coreJson).gzFile("new").exists(), "and its series to its file")
        assertEquals(2, rowCount(), "the store wasn't written")

        // The newer build's store comes back to this version (a fixed release): the journal merges.
        val fix = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        fix.version = HistoryDb.VERSION
        fix.close()
        assertEquals(HistoryHealth.OK, s.retry().health)
        assertFalse(File(filesDir, HistoryStore.PENDING_NAME).exists())
        assertEquals(5, store().load().first { it.id == "new" }.samples.size)
    }

    @Test
    fun `a full disk on a save sends the row to the journal, and the next launch merges it`() = runBlocking {
        store().loadAll()
        val s = store()
        s.loadAll()
        HistoryStore.faults += MigrationFault.DISK_FULL
        val shot = StoredShot(id = "full", completedAtMs = 5, durationMs = 1)
        assertFalse(s.save(listOf(shot)))
        assertEquals(setOf("full"), s.journaled)
        HistoryStore.faults.clear()
        assertEquals(listOf("full"), store().load().map { it.id })
    }

    // ── Upsert only ──────────────────────────────────────────────────────────

    @Test
    fun `save with an empty or partial list deletes nothing`() = runBlocking {
        val rows = HistoryFixtures.rows(5)
        HistoryFixtures.plantOldBuild(app, rows)
        val s = store()
        s.loadAll()
        s.save(emptyList<coffee.crema.history.StoredShot>())
        s.save(listOf(rows[2].copy(samples = emptyList(), rating = 1)))
        assertEquals(5, rowCount())
        val back = store().load().associateBy { it.id }
        assertEquals(1, back.getValue(rows[2].id).rating)
        assertEquals(rows[2].samples, back.getValue(rows[2].id).samples, "the curve stays: a row saved before its series loaded keeps it")
        assertEquals(rows[2].samples.size.toUInt(), storedSummary(rows[2].id)?.sampleCount)
    }

    // ── An old build over a migrated directory (SPEC §6, downgrade) ───────────

    @Test
    fun `an old build's saves delete no gz series and no db, and its rows merge`() = runBlocking {
        val rows = HistoryFixtures.rows(6)
        HistoryFixtures.plantOldBuild(app, rows)
        val s = store()
        s.loadAll()
        // After the move: a new shot (gz series) and a deleted one, in the new store.
        val mine = StoredShot(id = "mine", completedAtMs = rows.first().completedAtMs + 100, durationMs = 9, samples = HistoryFixtures.samples(8, 3))
        s.save(listOf(mine))
        s.delete(listOf(rows[4].id))
        val gz = SeriesFiles(seriesDir, coreJson).gzFile("mine")
        assertTrue(gz.exists())
        val dbBytes = dbFile.length()

        // The old build runs: it knows the old copy only, adds two shots, and prunes its own way.
        // At its cap it drops the oldest row from its list, and its prune deletes that row's `.json` series.
        val oldest = rows.minBy { it.completedAtMs }
        assertTrue(oldest.samples.isNotEmpty(), "the fixture's oldest row has a curve")
        val late = StoredShot(id = "late", completedAtMs = rows.first().completedAtMs + 200, durationMs = 9, samples = HistoryFixtures.samples(7, 4))
        val noSeries = StoredShot(id = "late-curve", completedAtMs = rows.first().completedAtMs + 300, durationMs = 9)
        val pulledTwin = StoredShot(id = "shot:remote:twin", completedAtMs = 7, durationMs = 9, visualizerId = rows[1].visualizerId ?: "vis-x")
        HistoryFixtures.oldBuildSave(app, rows - oldest + late + noSeries + pulledTwin)
        assertFalse(File(seriesDir, HistoryStore.seriesFileName(oldest.id)).exists(), "the old build pruned the oldest row's .json")
        assertTrue(gz.exists(), "the old build's prune leaves gz alone")
        assertTrue(dbFile.exists() && dbFile.length() >= dbBytes, "and the db")

        // Roll forward: its rows merge; the deleted one stays deleted; the pulled twin is skipped.
        val ids = store().load().map { it.id }.toSet()
        assertTrue("late" in ids && "late-curve" in ids && "mine" in ids)
        assertFalse(rows[4].id in ids, "a shot deleted in the new store doesn't come back")
        if (rows[1].visualizerId != null) assertFalse("shot:remote:twin" in ids, "a pulled copy of a row held is skipped")
        // The pruned row's curve is in its own `.series.gz`, written at the move (#408 M2).
        assertEquals(oldest.samples, store().load().first { it.id == oldest.id }.samples, "a migrated curve survives the old build's capped save")
        assertEquals(7, store().load().first { it.id == "late" }.samples.size)
        assertEquals(0u, storedSummary("late-curve")?.sampleCount)

        // The old build writes that row's series after it: it fills at a later launch.
        File(seriesDir, HistoryStore.seriesFileName("late-curve")).writeText(
            coreJson.encodeToString(ListSerializer(coffee.crema.ui.TelemetrySample.serializer()), HistoryFixtures.samples(6, 5)),
        )
        store().loadAll()
        assertEquals(6u, storedSummary("late-curve")?.sampleCount)
    }

    @Test
    fun `the move writes every series it read to its own gz, and leaves the json copies as they are`() = runBlocking {
        val rows = HistoryFixtures.rows(8)
        HistoryFixtures.plantOldBuild(app, rows)
        val before = oldCopy()
        store().loadAll()
        val files = SeriesFiles(seriesDir, coreJson)
        for (row in rows.filter { it.samples.isNotEmpty() }) {
            assertTrue(files.gzFile(row.id).exists(), "${row.id} has its gz")
            assertTrue(files.jsonFile(row.id).exists(), "and its json stays")
        }
        assertEquals(before, oldCopy())
    }

    // ── #408 review: no marker, or one that can't be read (M1) ──────────────

    /** A migrated store with a shot saved after the move, which only `shots.db` holds. */
    private suspend fun migratedWithPostShot(): List<StoredShot> {
        val rows = HistoryFixtures.rows(3, samplesPer = 5)
        HistoryFixtures.plantOldBuild(app, rows)
        val s = store()
        assertEquals(HistoryHealth.OK, s.loadAll().health)
        assertTrue(s.save(listOf(StoredShot(id = "post", completedAtMs = 2_000_000_000_000L, durationMs = 1, notes = "after the move"))))
        assertEquals(4, rowCount())
        return rows
    }

    private fun dropMarker() {
        for (suffix in listOf("", ".bak", ".tmp")) File(marker.path + suffix).delete()
    }

    private fun setDbVersion(v: Int) {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        db.version = v
        db.close()
    }

    @Test
    fun `a marker that can't be read leaves the store unavailable, and nothing is cleared`() = runBlocking {
        migratedWithPostShot()
        dropMarker()
        // A directory where the marker goes: it exists, and can't be read.
        marker.mkdirs()
        File(marker, "x").writeText("x")
        val s = store()
        assertEquals(HistoryHealth.UNAVAILABLE, s.loadAll().health)
        assertContains(s.lastError.orEmpty(), "marker")
        assertEquals(4, rowCount(), "the post-move shot is still in the store")
        marker.deleteRecursively()
        assertTrue(store().load().any { it.id == "post" }, "and it loads once the marker reads")
    }

    @Test
    fun `no marker and a store a newer build wrote is unavailable, never re-migrated`() = runBlocking {
        migratedWithPostShot()
        dropMarker()
        setDbVersion(HistoryDb.VERSION + 1)
        val s = store()
        assertEquals(HistoryHealth.UNAVAILABLE, s.loadAll().health)
        assertContains(s.lastError.orEmpty(), "update the app")
        setDbVersion(HistoryDb.VERSION)
        assertEquals(4, rowCount(), "nothing was cleared")
        // The version back: the store's own flag says done, and the marker is rewritten.
        assertTrue(store().load().any { it.id == "post" })
        assertContains(markerText(), "\"done\"")
    }

    @Test
    fun `no marker and a corrupt store is lost, kept aside, and never re-migrated`() = runBlocking {
        val rows = migratedWithPostShot()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        dbFile.writeBytes(ByteArray(8192) { (it * 31).toByte() })
        dropMarker()
        val load = store().loadAll()
        assertEquals(HistoryHealth.LOST, load.health)
        assertTrue(load.view.isEmpty(), "the old copy's ${rows.size} rows weren't copied in its place")
        assertTrue(dbFile.parentFile!!.listFiles()!!.any { it.name.startsWith(HistoryStore.NEXT_DB_NAME + ".corrupt-") })
        assertEquals(HistoryHealth.LOST, store().loadAll().health, "and stays lost")
    }

    @Test
    fun `no marker and a store that can't be opened is unavailable, and it stays`() = runBlocking {
        migratedWithPostShot()
        dropMarker()
        app.deleteDatabase(HistoryStore.NEXT_DB_NAME)
        // A directory where the database goes: it exists, and SQLite can't open it.
        dbFile.mkdirs()
        File(dbFile, "x").writeText("x")
        val s = store()
        assertEquals(HistoryHealth.UNAVAILABLE, s.loadAll().health)
        assertTrue(dbFile.isDirectory && File(dbFile, "x").exists(), "nothing deleted")
        assertFalse(marker.exists(), "and no marker says it moved")
    }

    @Test
    fun `no marker and a store with rows whose meta doesn't decode is lost, never re-migrated`() = runBlocking {
        migratedWithPostShot()
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        db.execSQL("UPDATE meta SET value = 'not json'")
        db.close()
        dropMarker()
        val s = store()
        val load = s.loadAll()
        assertEquals(HistoryHealth.LOST, load.health, "never a silent copy over it (#408 F1)")
        assertTrue(load.view.isEmpty())
        assertEquals(4, rowCount(), "shots.db as it was, the post-move shot included")
        assertFalse(dbFile.parentFile!!.listFiles()!!.any { it.name.startsWith(HistoryStore.NEXT_DB_NAME + ".corrupt-") }, "nothing moved aside yet")
        assertEquals(HistoryHealth.LOST, store().loadAll().health, "and it stays lost")
    }

    @Test
    fun `a damaged marker with no store is lost, never re-migrated`() = runBlocking {
        migratedWithPostShot()
        dropMarker()
        marker.writeText("{ not json")
        app.deleteDatabase(HistoryStore.NEXT_DB_NAME)
        val load = store().loadAll()
        assertEquals(HistoryHealth.LOST, load.health)
        assertFalse(dbFile.exists(), "no new store from the old copy")
    }

    // ── #408 review: the pending journal (M3, S1) ────────────────────────────

    @Test
    fun `a save that lands after a journaled one retires its line, so the next launch keeps the edit`() = runBlocking {
        store().loadAll()
        val s = store()
        s.loadAll()
        // The disk is full: a new shot goes to the journal.
        HistoryStore.faults += MigrationFault.DISK_FULL
        val v1 = StoredShot(id = "j", completedAtMs = 5, durationMs = 1)
        assertFalse(s.save(listOf(v1)))
        HistoryStore.faults.clear()
        // Space is back: the user rates it, and the save lands.
        val v2 = v1.copy(rating = 5, notes = "edited after space freed")
        assertTrue(s.save(listOf(v2)))
        // Another shot journaled, edited, then deleted.
        val k = StoredShot(id = "k", completedAtMs = 6, durationMs = 1)
        HistoryStore.faults += MigrationFault.DISK_FULL
        s.save(listOf(v2, k))
        HistoryStore.faults.clear()
        assertTrue(s.save(listOf(v2, k.copy(rating = 1))))
        s.delete(listOf(k.id))

        val back = store().load().associateBy { it.id }
        assertEquals(5, back["j"]?.rating, "the edit stays")
        assertEquals("edited after space freed", back["j"]?.notes)
        assertFalse(back.containsKey("k"), "a deleted shot doesn't come back")
    }

    @Test
    fun `a delete of a journaled shot retires its line, so the shot doesn't come back`() = runBlocking {
        store().loadAll()
        val s = store()
        s.loadAll()
        val k = StoredShot(id = "k", completedAtMs = 6, durationMs = 1)
        HistoryStore.faults += MigrationFault.DISK_FULL
        assertFalse(s.save(listOf(k)))
        HistoryStore.faults.clear()
        assertEquals(setOf("k"), s.journaled)
        // Space is back, and the shot is deleted with no save between (#408 F3).
        s.delete(listOf(k.id))
        assertFalse(store().load().any { it.id == k.id })
    }

    @Test
    fun `a delete while the store is unavailable waits in the journal, and the shot doesn't come back`() = runBlocking {
        HistoryFixtures.plantOldBuild(app, HistoryFixtures.rows(2, samplesPer = 5))
        store().loadAll()
        setDbVersion(HistoryDb.VERSION + 1)
        val s = store()
        assertEquals(HistoryHealth.UNAVAILABLE, s.loadAll().health)
        val shot = StoredShot(id = "made-then-deleted", completedAtMs = 2_000_000_000_000L, durationMs = 1, samples = HistoryFixtures.samples(4, 2))
        s.save(listOf(shot))
        // The user deletes it: the view's save, then the named delete.
        s.save(emptyList<coffee.crema.history.StoredShot>())
        s.delete(listOf(shot.id))
        setDbVersion(HistoryDb.VERSION)
        assertEquals(HistoryHealth.OK, s.retry().health)
        assertFalse(store().load().any { it.id == shot.id }, "the delete was applied with the journal")
        assertFalse(SeriesFiles(seriesDir, coreJson).gzFile(shot.id).exists(), "with its series")
        assertEquals(2, rowCount())
    }

    // ── #408 review: late rows (S2) ──────────────────────────────────────────

    @Test
    fun `a late row whose series write fails is imported at a later launch`() = runBlocking {
        val rows = HistoryFixtures.rows(4, samplesPer = 5)
        HistoryFixtures.plantOldBuild(app, rows)
        store().loadAll()
        // An old build wrote it with its samples inline (its own series write failed).
        val late = StoredShot(id = "late-inline", completedAtMs = rows.first().completedAtMs + 10, durationMs = 9, samples = HistoryFixtures.samples(6, 2))
        HistoryFixtures.plantOldBuild(app, rows + late, inline = setOf(late.id))
        // The roll-forward's write fails too (a full disk).
        SeriesFiles.beforeWrite = { id -> if (id == late.id) throw java.io.IOException("disk full") }
        assertFalse(store().load().any { it.id == late.id })
        SeriesFiles.beforeWrite = {}
        // Space is back at the next launch.
        assertEquals(late.samples, store().load().firstOrNull { it.id == late.id }?.samples, "it comes in, with its curve")
    }

    // ── #408 review: rows past the cap (S3) ──────────────────────────────────

    @Test
    fun `a profile filled into a hidden brew keeps its brew series`() = runBlocking {
        val base = HistoryFixtures.rows(320, samplesPer = 3).map { it.copy(pinned = false) }
        val brewSrc = base[9]
        assertTrue(brewSrc.brewSeries != null && brewSrc.profile == null, "the fixture's guided brew")
        val brew = brewSrc.copy(id = "brew-hidden", completedAtMs = 1)
        HistoryFixtures.plantOldBuild(app, base + brew)
        val s = store()
        val load = s.loadAll()
        assertTrue(load.view.any { it.id == brew.id }, "no cap from P6a: in view")
        // A stored row's change through updateHidden still lands. A pull's fill (#310 P4a): the profile alone is staged, then the row's write carries it.
        val row = assertNotNull(s.storedRow(brew.id))
        s.stage(row.withHeavy(emptyList(), HistoryFixtures.profile(2), null))
        assertTrue(s.updateHidden(brew.id) { it })
        s.flushHidden(brew.id)
        val back = store().load().first { it.id == brew.id }
        assertEquals(brew.brewSeries, back.brewSeries, "the brew series stays")
        assertEquals(HistoryFixtures.profile(2), back.profile, "and the profile is filled")
    }

    @Test
    fun `a profile filled into a hidden row on a full disk waits in the journal, brew series kept`() = runBlocking {
        val base = HistoryFixtures.rows(320, samplesPer = 3).map { it.copy(pinned = false) }
        val brew = base[9].copy(id = "brew-hidden", completedAtMs = 1)
        assertTrue(brew.brewSeries != null && brew.profile == null)
        HistoryFixtures.plantOldBuild(app, base + brew)
        val s = store()
        s.loadAll()
        s.stage(assertNotNull(s.storedRow(brew.id)).withHeavy(emptyList(), HistoryFixtures.profile(3), null))
        assertTrue(s.updateHidden(brew.id) { it })
        HistoryStore.faults += MigrationFault.DISK_FULL
        s.flushHidden(brew.id)
        HistoryStore.faults.clear()
        assertEquals(setOf(brew.id), s.journaled)
        // The next launch merges the journal (#408 N8).
        val back = store().load().first { it.id == brew.id }
        assertEquals(HistoryFixtures.profile(3), back.profile, "the pulled profile isn't dropped")
        assertEquals(brew.brewSeries, back.brewSeries, "and the brew series stays")
    }

    // ── #408 review: a curve the summary names and the file doesn't hold (S4) ─

    @Test
    fun `a corrupt or missing series isn't a curve, so a restore or a pull can put one back`() = runBlocking {
        val s0 = store()
        s0.loadAll()
        val corrupt = StoredShot(id = "gz-corrupt", completedAtMs = 5, durationMs = 9, samples = HistoryFixtures.samples(5, 1))
        val gone = StoredShot(id = "gz-gone", completedAtMs = 6, durationMs = 9, samples = HistoryFixtures.samples(5, 2))
        assertTrue(s0.save(listOf(corrupt, gone)))
        val files = SeriesFiles(seriesDir, coreJson)
        files.gzFile(corrupt.id).writeBytes(byteArrayOf(1, 2, 3))
        files.gzFile(gone.id).delete()
        val s = store()
        val view = s.loadAll().view
        // Each read whole, as a detail would (#310 P4a): no curve for either.
        for (id in listOf(corrupt.id, gone.id)) assertTrue(assertNotNull(s.loadShot(id)).samples.isEmpty(), id)
        for (row in view) assertFalse(s.hasCurve(row), "${row.id}: no curve to read")
        // The next save writes the summary as unknown.
        s.save(view)
        assertNull(storedSummary(corrupt.id)?.sampleCount)
        assertNull(storedSummary(gone.id)?.sampleCount)
    }

    @Test
    fun `a hidden row read for a backup counts a curve that's gone, and throws on a read error`() = runBlocking {
        val base = HistoryFixtures.rows(302, samplesPer = 3).map { it.copy(pinned = false, brewMethod = null, brewSeries = null) }
        HistoryFixtures.plantOldBuild(app, base)
        val s = store()
        val load = s.loadAll()
        assertTrue(s.hiddenRows(load.view.mapTo(HashSet()) { it.id }).isEmpty(), "no cap from P6a: none hidden")
        // The oldest two, read for a backup as any row is.
        val (gone, unreadable) = load.view.takeLast(2)
        assertTrue(s.hasCurve(gone), "a row whose curve is on disk")
        val files = SeriesFiles(seriesDir, coreJson)
        files.gzFile(gone.id).delete()
        File(seriesDir, HistoryStore.seriesFileName(gone.id)).delete()
        val missing = HashSet<String>()
        assertTrue(s.loadFull(gone, missing).samples.isEmpty())
        assertEquals(setOf(gone.id), missing, "counted for the report")
        assertFalse(s.hasCurve(gone), "and fillable now")
        // A directory where its gz goes: there, and it can't be read just now.
        files.gzFile(unreadable.id).delete()
        files.gzFile(unreadable.id).mkdirs()
        assertFailsWith<HistoryReadException> { s.loadFull(unreadable, missing) }
        Unit
    }

    // ── #408 review: a lost store's rebuild (S7) ─────────────────────────────

    @Test
    fun `recovering the old copy over an intact store that reads as lost keeps that store aside`() = runBlocking {
        val rows = migratedWithPostShot()
        // Its meta row won't decode: intact rows, and no finished copy to trust.
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        db.execSQL("UPDATE meta SET value = '{ not json' WHERE key = ?", arrayOf(HistoryTables.META_MIGRATION))
        db.close()
        val s = store()
        assertEquals(HistoryHealth.LOST, s.loadAll().health)
        assertEquals(HistoryHealth.OK, s.recoverOldCopy().health)
        assertEquals(rows.map { it.id }.toSet(), store().load().map { it.id }.toSet(), "the old copy is back")
        val kept = dbFile.parentFile!!.listFiles()!!.filter { it.name.startsWith(HistoryStore.NEXT_DB_NAME + ".corrupt-") && !it.name.contains("-wal") && !it.name.contains("-shm") }
        val main = kept.single()
        val aside = SQLiteDatabase.openDatabase(main.path, null, SQLiteDatabase.OPEN_READONLY)
        val ids = aside.rawQuery("SELECT id FROM shots", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        aside.close()
        assertTrue("post" in ids, "the post-move shot is in the copy kept aside")
    }
}
