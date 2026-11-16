package coffee.crema.history

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import coffee.crema.core.BrewSeries
import coffee.crema.core.HistoryHeavy
import coffee.crema.core.HistoryRole
import coffee.crema.core.HistoryRowIn
import coffee.crema.core.LocalHealth
import coffee.crema.core.LocalShot
import coffee.crema.core.LocalState
import coffee.crema.core.LocalStoreHealth
import coffee.crema.core.ProfileMeta
import coffee.crema.core.RecipeMeta
import coffee.crema.core.RestoreMode
import coffee.crema.core.RestorePlan
import coffee.crema.persist.SafeFile
import coffee.crema.restore.MarkerDeletes
import coffee.crema.restore.PreparedBackup
import coffee.crema.restore.RestoreEngine
import coffee.crema.restore.RestoreKind
import coffee.crema.restore.RestoreMarkerStore
import coffee.crema.restore.RestoreOutcome
import coffee.crema.restore.RestoreRecords
import coffee.crema.restore.RestoreSink
import coffee.crema.restore.prepareBackupFile
import coffee.crema.restore.restoreShots
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.toCommonSettings
import coffee.crema.ui.BackupSnapshot
import coffee.crema.ui.MainUiState
import coffee.crema.ui.StreamedBackup
import coffee.crema.ui.backupEnvelope
import coffee.crema.ui.backupMark
import coffee.crema.ui.backupTempFile
import coffee.crema.ui.coreJson
import coffee.crema.ui.parseBackupBytes
import coffee.crema.ui.rowViewOf
import coffee.crema.ui.streamBackupZip
import coffee.crema.visualizer.DEFAULT_VISUALIZER_SYNC_PREFS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #310 R1a, in Robolectric with P3a's `shots.db` and the core's SQLite store
 * side by side: the streamed backup and step 7 through the [HistorySource]
 * seam. The same history backs up to the same bytes from either store; on
 * the SQLite store a delete during the walk is held and released details
 * first (S6), and a restore removes only rows made before it began (M3),
 * whatever stops it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistorySourceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val json = coreJson
    private val tables = HistoryTables(coreJson)
    private val root get() = File(app.filesDir, "r1a")
    private val bridges = mutableListOf<HistoryDbBridge>()

    @Before fun clean() {
        SafeFile.openAllForTest()
        HistoryStore.faults.clear()
        runBlocking { HistoryStore(app, json).erase() }
        File(app.filesDir, "shot-series").deleteRecursively()
        root.deleteRecursively()
        root.mkdirs()
        SqliteHistorySource.faultHook = null
        RestoreEngine.resetForTest()
    }

    @After fun tidy() {
        SqliteHistorySource.faultHook = null
        runBlocking { bridges.forEach { it.closeAll() } }
        root.deleteRecursively()
        SafeFile.resetForTest()
        RestoreEngine.resetForTest()
    }

    // ── Stores ───────────────────────────────────────────────────────────────

    private fun bridge(name: String): HistoryDbBridge = runBlocking {
        HistoryDbBridge.open(File(root, name), HistoryDbBridge.options(HistoryRole.INDEX, create = true))
    }.also { bridges += it }

    /** The SQLite store's details for these tests: `.series.gz` files, and the heavy parts kept beside them. */
    private class FileDetails(dir: File, private val json: kotlinx.serialization.json.Json) : ShotDetails {
        val series = SeriesFiles(dir.apply { mkdirs() }, json)
        val heavy = ConcurrentHashMap<String, Pair<JsonObject?, BrewSeries?>>()

        override fun full(row: ShotRow): StoredShot {
            val samples = when (val r = series.read(row.id)) {
                is SeriesRead.Found -> r.samples
                is SeriesRead.Unavailable -> throw HistoryReadException(r.error)
                else -> emptyList()
            }
            val (profile, brew) = heavy[row.id] ?: (null to null)
            return row.withHeavy(samples, profile, brew)
        }

        override fun hasCurve(row: ShotRow): Boolean = series.gzFile(row.id).exists()

        override fun rowFor(shot: StoredShot): ShotRow = shot.toRow(summaryOf(shot, json))

        override fun write(shots: List<StoredShot>): Int {
            var failed = 0
            for (s in shots) {
                if (s.samples.isNotEmpty() && !series.write(s.id, s.samples)) failed++
                if (s.profile != null || s.brewSeries != null) {
                    val was = heavy[s.id]
                    heavy[s.id] = (s.profile ?: was?.first) to (s.brewSeries ?: was?.second)
                }
            }
            return failed
        }

        override fun delete(ids: Collection<String>) {
            for (id in ids) {
                series.deleteGz(id)
                heavy.remove(id)
            }
        }
    }

    /** P3a's store as the SQLite store's details, for the byte comparison: the same curves and heavy parts. */
    private class StoreDetails(private val store: HistoryStore) : ShotDetails {
        override fun full(row: ShotRow): StoredShot = store.loadFull(row)
        override fun hasCurve(row: ShotRow): Boolean = store.hasCurve(row)
        override fun rowFor(shot: StoredShot): ShotRow = store.rowFor(shot)
        override fun write(shots: List<StoredShot>): Int = runBlocking { store.writeSeriesOf(shots) }
        override fun delete(ids: Collection<String>) = Unit
    }

    private fun rowIn(shot: StoredShot): HistoryRowIn =
        HistoryRowIn(tables.rowJson(shot.copy(samples = emptyList(), profile = null, brewSeries = null), summaryOf(shot, json)))

    /** [shots] into [db] and [details] as the SQLite store keeps them: details first, then the rows. */
    private fun fill(db: HistoryDbBridge, details: FileDetails, shots: List<StoredShot>) = runBlocking {
        details.write(shots)
        for (chunk in shots.chunked(500)) db.upsert(chunk.map(::rowIn))
    }

    /** `shots.db`'s rows, as stored, copied into a new SQLite store: the same history in both. */
    private fun sqliteCopyOfShotsDb(name: String): HistoryDbBridge {
        val db = bridge(name)
        val rows = ArrayList<HistoryRowIn>()
        SQLiteDatabase.openDatabase(app.getDatabasePath(HistoryStore.NEXT_DB_NAME).path, null, SQLiteDatabase.OPEN_READONLY).use { shots ->
            shots.rawQuery("SELECT row_json, profile_json, brew_series_json FROM shots", null).use { c ->
                while (c.moveToNext()) {
                    rows += HistoryRowIn(c.getString(0), HistoryHeavy(c.getString(1), c.getString(2)))
                }
            }
        }
        runBlocking { for (chunk in rows.chunked(500)) db.upsert(chunk) }
        return db
    }

    private fun shots(n: Int, prefix: String, start: Long = 1_790_000_000_000, samples: Int = 4): List<StoredShot> = List(n) { i ->
        StoredShot(
            id = "$prefix${i.toString().padStart(5, '0')}",
            completedAtMs = start - i * 60_000L,
            durationMs = 28_000,
            doseG = 18f,
            yieldG = 36f + i % 3,
            notes = "$prefix $i",
            samples = HistoryFixtures.samples(samples, i),
        )
    }

    // ── Backups ──────────────────────────────────────────────────────────────

    private fun snapshot(rows: List<ShotRow>, full: (ShotRow) -> StoredShot, source: HistorySource? = null) = BackupSnapshot(
        customs = emptyList(),
        state = MainUiState(),
        shots = rows,
        pinned = emptySet(),
        hidden = emptySet(),
        prefs = AppPrefs(),
        visualizerPrefs = DEFAULT_VISUALIZER_SYNC_PREFS,
        userRecipes = emptyList(),
        full = full,
        source = source,
    )

    private fun sqliteSnapshot(source: HistorySource) = snapshot(emptyList(), { error("the walk reads through its source") }, source)

    private fun build(snap: BackupSnapshot, batch: Int = 25, stop: () -> Unit = {}): StreamedBackup =
        streamBackupZip(snap, json, 1_790_000_000_000, "test", "R1a", emptyList(), backupTempFile(app.cacheDir), batch = batch, stop = stop)

    private fun linesOf(file: File): List<String> = ZipInputStream(file.inputStream()).use { zip ->
        while (true) {
            val e = zip.nextEntry ?: error("no backup.jsonl")
            if (e.name == "backup.jsonl") return@use zip.readBytes().toString(Charsets.UTF_8).split('\n').filter { it.isNotBlank() }
        }
        @Suppress("UNREACHABLE_CODE")
        emptyList()
    }

    private fun shotLines(lines: List<String>) = lines.filter { "\"kind\":\"shot\"" in it }.map { json.parseToJsonElement(it).jsonObject }

    private fun headerCount(lines: List<String>) = json.parseToJsonElement(lines.first()).jsonObject["shotCount"]!!.jsonPrimitive.int

    /** The header's count is the number of shot lines (S6). */
    private fun assertHeaderCountsLines(file: File): List<JsonObject> {
        val lines = linesOf(file)
        val shots = shotLines(lines)
        assertEquals(shots.size, headerCount(lines), "header count against lines")
        return shots
    }

    @Test
    fun `the same history backs up byte for byte from either store`() = runBlocking<Unit> {
        // Every shape the history holds, with ties on the date so the order's tie rule shows.
        val shots = HistoryFixtures.rows(300, samplesPer = 12).let { rows ->
            rows.mapIndexed { i, s -> if (i % 10 == 1) s.copy(completedAtMs = rows[i - 1].completedAtMs) else s }
        }
        val store = HistoryStore(app, json)
        store.loadAll()
        assertTrue(store.save(shots))
        val view = store.loadAll().view
        val source = SqliteHistorySource.open(sqliteCopyOfShotsDb("copy.db"), json, StoreDetails(store))

        val p3a = build(snapshot(view, { store.loadFull(it) }))
        val sqlite = build(snapshot(view, { store.loadFull(it) }, source))
        assertEquals(view.map { it.id }, p3a.shotIds)
        assertEquals(p3a.shotIds, sqlite.shotIds, "the walk's order is P3a's list order")
        assertContentEquals(p3a.file.readBytes(), sqlite.file.readBytes())
        assertEquals(p3a.contentHash, sqlite.contentHash)
        assertEquals(300, assertHeaderCountsLines(sqlite.file).size)
        assertNull(p3a.mark, "P3a's store keeps no write counter")
        assertNotNull(sqlite.mark)
    }

    @Test
    fun `a delete during the walk is held, written whole, then released rows first`() = runBlocking<Unit> {
        val details = FileDetails(File(root, "series"), json)
        val db = bridge("walk.db")
        val rows = shots(40, "s")
        fill(db, details, rows)
        val source = SqliteHistorySource.open(db, json, details)
        val walked = rows[2].id
        val ahead = rows[35].id
        val late = shots(1, "late", start = 1_790_100_000_000).single()
        var calls = 0
        val built = build(sqliteSnapshot(source), batch = 10, stop = {
            // After the first batch of the first read: one row already read, one not yet, and a new shot.
            if (++calls == 2) runBlocking {
                source.delete(listOf(walked, ahead))
                fill(db, details, listOf(late))
                assertTrue(details.series.gzFile(walked).exists(), "a held row's details stay for the walk")
            }
        })
        val lines = assertHeaderCountsLines(built.file)
        assertEquals(40, lines.size)
        val ids = lines.map { it["id"]!!.jsonPrimitive.content }
        assertTrue(walked in ids && ahead in ids, "a row deleted during the walk is still in it")
        assertFalse(late.id in ids, "a shot saved during the walk is in the next backup")
        for (id in listOf(walked, ahead)) {
            val line = lines.single { it["id"]!!.jsonPrimitive.content == id }
            assertEquals(4, line["record"]!!.jsonObject["samples"]!!.jsonArray.size, "$id is written whole")
        }
        // The walk ended: the held rows went, then their details, and nothing else.
        val left = source.allRows().map { it.id }.toSet()
        assertEquals(rows.map { it.id }.toSet() - walked - ahead + late.id, left)
        assertFalse(details.series.gzFile(walked).exists())
        assertFalse(details.series.gzFile(ahead).exists())
        assertTrue(details.series.gzFile(late.id).exists())
        assertEquals(emptyList(), db.releaseHolds())
    }

    @Test
    fun `on P3a's store too, the header counts the lines when a shot goes during the walk`() = runBlocking<Unit> {
        val store = HistoryStore(app, json)
        store.loadAll()
        val rows = shots(30, "p")
        assertTrue(store.save(rows))
        val view = store.loadAll().view
        var calls = 0
        val built = build(snapshot(view, { store.loadFull(it) }), batch = 10, stop = {
            if (++calls == 2) runBlocking { store.delete(listOf(view[3].id, view[25].id)) }
        })
        assertEquals(30, assertHeaderCountsLines(built.file).size)
    }

    @Test
    fun `a kill between a hold's row and its details leaves only details with no row, and takes nothing more`() = runBlocking<Unit> {
        val details = FileDetails(File(root, "series"), json)
        val name = "crash.db"
        val db = bridge(name)
        val rows = shots(10, "c")
        fill(db, details, rows)
        val source = SqliteHistorySource.open(db, json, details)
        val walk = source.openWalk()
        val x = rows[4].id
        source.delete(listOf(x))
        assertTrue(details.series.gzFile(x).exists(), "held: its details wait")
        SqliteHistorySource.faultHook = { if (it == SourceFault.RELEASE_AFTER_ROWS) throw SimulatedCrash(it.name) }
        assertFailsWith<SimulatedCrash> { walk.close() }
        SqliteHistorySource.faultHook = null
        // Rows first (SPEC "From R1w"): the row is gone, its curve stays, which is harmless (SPEC §2).
        assertFalse(db.idsCreatedBefore(ULong.MAX_VALUE, Long.MIN_VALUE, 100u).any { it.id == x }, "the row went first")
        assertTrue(details.series.gzFile(x).exists(), "its details wait, with no row: never a row without its curve")
        db.closeAll()
        val again = bridge(name)
        val reopened = SqliteHistorySource.open(again, json, details)
        assertEquals(rows.map { it.id }.toSet() - x, reopened.allRows().map { it.id }.toSet())
        assertEquals(10, details.series.dir.listFiles().orEmpty().count { it.name.endsWith(SeriesFiles.GZ) }, "nothing else was taken")
        // The id made again: its details are written before its row, over the old ones.
        fill(again, details, listOf(rows[4]))
        assertEquals(rows.map { it.id }.toSet(), reopened.allRows().map { it.id }.toSet())
    }

    @Test
    fun `a held row an upsert brings back before its release keeps its curve`() = runBlocking<Unit> {
        val details = FileDetails(File(root, "series"), json)
        val db = bridge("back.db")
        val rows = shots(6, "b")
        fill(db, details, rows)
        val source = SqliteHistorySource.open(db, json, details)
        val walk = source.openWalk()
        val x = rows[2].id
        source.delete(listOf(x))
        // A restore or a pull writes it again while the walk is still open.
        fill(db, details, listOf(rows[2]))
        walk.close()
        assertTrue(x in source.allRows().map { it.id }, "the row stays")
        assertTrue(details.series.gzFile(x).exists(), "and so does its curve (REVIEW-468 W5-1)")
    }

    @Test
    fun `the write mark moves with every write, and only then`() = runBlocking<Unit> {
        val details = FileDetails(File(root, "series"), json)
        val db = bridge("mark.db")
        fill(db, details, shots(3, "m"))
        val source = SqliteHistorySource.open(db, json, details)
        val first = assertNotNull(source.writeMark())
        assertEquals(first, source.writeMark())
        val snap = sqliteSnapshot(source)
        val built = build(snap)
        assertEquals(backupMark(first, backupEnvelope(snap, json, withShots = false), emptyList()), built.mark, "read before the walk")
        source.delete(listOf("m00001"))
        assertTrue(first != source.writeMark())
    }

    // ── Restore (M3) ─────────────────────────────────────────────────────────

    /** A restore sink with only the shots, on the SQLite store, as A5 will wire it. */
    private inner class SqliteSink(
        private val source: SqliteHistorySource,
        private val details: FileDetails,
        private val beforeRemove: suspend () -> Unit = {},
    ) : RestoreSink {
        private var target: ShotsRestore? = null

        override suspend fun restoreStarts() {
            target = source.beginRestore()
        }

        override suspend fun localState(): LocalState = LocalState(
            shell = "android",
            machineBusy = false,
            health = listOf(LocalStoreHealth("shots", LocalHealth.Ok)),
            beans = emptyList(),
            roasters = emptyList(),
            profiles = emptyList(),
            recipes = emptyList(),
            customMethods = emptyList(),
            shots = withContext(Dispatchers.IO) { source.allRows() }.map { LocalShot(it.id, hasSamples = details.hasCurve(it), pinned = it.pinned) },
            photos = emptyList(),
            beansMissingPhoto = emptyList(),
            profileMeta = ProfileMeta(emptyList(), emptyList()),
            recipeMeta = RecipeMeta(HashMap(), emptyList()),
            common = AppPrefs().toCommonSettings(),
            visualizerPrefs = DEFAULT_VISUALIZER_SYNC_PREFS,
            totalLitres = 0.0,
            addedBeans = emptyList(),
        )

        override fun machineBusy() = false
        override suspend fun cancelTombstones(ids: List<String>) = true
        override suspend fun putPhotos(plan: RestorePlan, staged: Map<String, File>) = true
        override suspend fun applyRecipes(plan: RestorePlan, records: RestoreRecords) = true
        override suspend fun applyLibrary(plan: RestorePlan, records: RestoreRecords) = true
        override suspend fun applyProfiles(plan: RestorePlan, records: RestoreRecords) = true

        override suspend fun applyShots(plan: RestorePlan, records: RestoreRecords): Int? =
            restoreShots(checkNotNull(target), plan, records, plan.mode == RestoreMode.Replace, beforeRemove = beforeRemove)

        override suspend fun applySingletons(plan: RestorePlan, records: RestoreRecords) = true

        override suspend fun runDeletes(deletes: MarkerDeletes): Boolean {
            val listed = withContext(Dispatchers.IO) { source.allRows() }.mapTo(HashSet()) { it.id }
            details.delete(deletes.shots.filterNot { it in listed })
            return true
        }

        override fun pauseDeletes() = Unit
        override fun resumeDeletes() = Unit
        override fun changedDirs(): List<File> = emptyList()
    }

    private fun engine(sink: RestoreSink) = RestoreEngine(sink, RestoreMarkerStore(File(root, "restore-pending.json"), json), json)

    /** Plan, read and run, as the confirm does. */
    private suspend fun restore(engine: RestoreEngine, prepared: PreparedBackup, kind: RestoreKind): RestoreOutcome {
        val plan = engine.plan(prepared, kind)
        val records = RestoreRecords.read(prepared, json, 0L, RestoreRecords.shotsTheRunWrites(plan))
        return engine.run(prepared, kind, records, expected = plan)
    }

    /** A backup of [shots], from another device's SQLite store. */
    private fun backupOf(shots: List<StoredShot>, name: String): PreparedBackup {
        val details = FileDetails(File(root, "$name-series"), json)
        val db = bridge("$name.db")
        fill(db, details, shots)
        val built = build(sqliteSnapshot(SqliteHistorySource.open(db, json, details)))
        return prepareBackupFile(built.file, name, File(root, "$name-stage"))
    }

    private class Device(val source: SqliteHistorySource, val details: FileDetails, val db: HistoryDbBridge) {
        fun ids() = source.allRows().map { it.id }.toSet()
    }

    private fun device(name: String, shots: List<StoredShot>): Device {
        val details = FileDetails(File(root, "$name-series"), json)
        val db = bridge("$name.db")
        fill(db, details, shots)
        return Device(SqliteHistorySource.open(db, json, details), details, db)
    }

    @Test
    fun `a restore killed at every batch leaves the union, and a re-run finishes it`() = runBlocking<Unit> {
        // 700 here; the backup holds 300 of them, edited, and 300 new: three merge batches and two wipe batches.
        val local = shots(700, "L")
        val backupShots = local.take(300).map { it.copy(notes = "from the backup", samples = HistoryFixtures.samples(6, 1)) } +
            shots(300, "B", start = 1_780_000_000_000, samples = 5)
        val backupIds = backupShots.map { it.id }.toSet()
        val prepared = backupOf(backupShots, "backup")
        val points = List(3) { SourceFault.BEFORE_MERGE_BATCH to it } + List(2) { SourceFault.BEFORE_WIPE_BATCH to it }
        for ((n, point) in points.withIndex()) {
            val (fault, at) = point
            val here = device("here-$n", local)
            File(root, "restore-pending.json").delete()
            var hits = 0
            SqliteHistorySource.faultHook = { if (it == fault && hits++ == at) throw SimulatedCrash("$fault $at") }
            assertFailsWith<SimulatedCrash>("$point") { restore(engine(SqliteSink(here.source, here.details)), prepared, RestoreKind.REPLACE) }
            SqliteHistorySource.faultHook = null
            RestoreEngine.resetForTest()
            val ids = here.ids()
            if (fault == SourceFault.BEFORE_MERGE_BATCH) {
                assertTrue(ids.containsAll(local.map { it.id }), "$point: nothing local removed before the merge is in")
            } else {
                assertTrue(ids.containsAll(backupIds), "$point: every backup row is in")
                assertTrue((ids - backupIds).all { it.startsWith("L") }, "$point")
            }
            // The re-run finishes it: exactly the backup, each row with its curve.
            assertIs<RestoreOutcome.Done>(restore(engine(SqliteSink(here.source, here.details)), prepared, RestoreKind.REPLACE), "$point")
            assertEquals(backupIds, here.ids(), "$point")
            for (row in here.source.allRows()) {
                val want = if (row.id.startsWith("B")) 5 else 6
                assertEquals(want, row.summary.sampleCount?.toInt(), "$point ${row.id}")
                assertEquals(want, (here.details.series.read(row.id) as SeriesRead.Found).samples.size, "$point ${row.id}")
            }
        }
    }

    @Test
    fun `a shot captured during a restore survives its wipe`() = runBlocking<Unit> {
        val here = device("here", shots(20, "L"))
        val prepared = backupOf(shots(10, "B", start = 1_780_000_000_000), "backup")
        val captured = shots(1, "new", start = 1_790_500_000_000).single()
        val sink = SqliteSink(here.source, here.details, beforeRemove = { fill(here.db, here.details, listOf(captured)) })
        assertIs<RestoreOutcome.Done>(restore(engine(sink), prepared, RestoreKind.REPLACE))
        assertEquals(List(10) { "B${it.toString().padStart(5, '0')}" }.toSet() + captured.id, here.ids())
        assertTrue(here.details.series.gzFile(captured.id).exists())
    }

    @Test
    fun `the wipe takes only rows made before the restore began, by their seq`() = runBlocking<Unit> {
        val here = device("here", shots(5, "L"))
        val target = here.source.beginRestore()
        // Written after the restore began, under an id the plan named (a pull bringing back a removed row).
        here.source.delete(listOf("L00001"))
        fill(here.db, here.details, shots(2, "L").drop(1))
        val late = shots(1, "late").single()
        fill(here.db, here.details, listOf(late))
        target.remove(listOf("L00000", "L00001", "L00002", late.id), backupIds = setOf("L00002"))
        assertEquals(setOf("L00001", "L00002", "L00003", "L00004", late.id), here.ids())
    }

    @Test
    fun `a 5,000-shot web backup restores with every row and sample count, and backs up again whole`() = runBlocking<Unit> {
        val fixtures = File("../../core/de1-domain/tests/fixtures/backup")
        val lines = File(fixtures, "web-backup.crema.jsonl").readText().split('\n').filter { it.isNotBlank() }
        val template = json.parseToJsonElement(lines.first { "\"kind\":\"shot\"" in it }).jsonObject
        val record = template["record"]!!.jsonObject
        val base = record["samples"]!!.jsonArray
        val completedAt = template["completedAt"]!!.jsonPrimitive.content.toLong()
        val counts = HashMap<String, Int>()
        val shotLines = List(5_000) { i ->
            val n = 1 + i % 9
            val id = "shot:web-${i.toString().padStart(5, '0')}"
            counts[id] = n
            val samples = JsonArray(List(n) { k -> JsonObject(base[k % base.size].jsonObject + ("elapsed" to JsonPrimitive(k * 250))) })
            JsonObject(
                template + mapOf(
                    "id" to JsonPrimitive(id),
                    "completedAt" to JsonPrimitive(completedAt - i * 3_600_000L),
                    "record" to JsonObject(record + ("samples" to samples)),
                ),
            ).toString()
        }
        val header = lines[0].replace(Regex("\"shotCount\":\\d+"), "\"shotCount\":5000")
        val others = lines.drop(1).filterNot { "\"kind\":\"shot\"" in it }
        val file = File(root, "web-5000.crema.jsonl").apply { writeText((listOf(header) + others + shotLines).joinToString("\n") + "\n") }
        val prepared = prepareBackupFile(file, "web", File(root, "web-stage"))

        val here = device("here", emptyList())
        assertIs<RestoreOutcome.Done>(restore(engine(SqliteSink(here.source, here.details)), prepared, RestoreKind.MERGE))
        val rows = here.source.allRows()
        assertEquals(5_000, rows.size)
        for (row in rows) {
            assertEquals(counts[row.id], row.summary.sampleCount?.toInt(), row.id)
            assertEquals(counts[row.id], (here.details.series.read(row.id) as SeriesRead.Found).samples.size, row.id)
        }

        // And back out, through the walk: every shot with its curve, read back by the core's parser, web's own.
        val built = build(sqliteSnapshot(here.source), batch = 250)
        val out = assertHeaderCountsLines(built.file)
        assertEquals(5_000, out.size)
        for (line in out) {
            val id = line["id"]!!.jsonPrimitive.content
            assertEquals(counts[id], line["record"]!!.jsonObject["samples"]!!.jsonArray.size, id)
        }
        val parsed = parseBackupBytes(linesOf(built.file).joinToString("\n").toByteArray(), json, 0L)
        assertEquals(5_000, parsed.shots.size)
        for (s in parsed.shots) assertEquals(counts[s.id], s.samples.size, s.id)
    }
}
