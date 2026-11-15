package coffee.crema.history

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import coffee.crema.core.BrewHistoryStats
import coffee.crema.core.HistoryAnchor
import coffee.crema.core.HistoryDbException
import coffee.crema.core.HistoryDestination
import coffee.crema.core.HistoryFtsStatus
import coffee.crema.core.HistoryQuery
import coffee.crema.core.HistoryRole
import coffee.crema.core.HistoryRowIn
import coffee.crema.core.historyChangeMovesList
import coffee.crema.ui.coreJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #310 C3, in Robolectric through the host-built core (`cargoBuildHostCore`):
 * the SQLite history store behind [HistoryDbBridge], on real files. Open,
 * write, page, search, stats, the export walk and restore, errors as typed
 * exceptions, a [StoredShot] through `row_json` unchanged, coroutines calling
 * at once, and erase after [HistoryDbBridge.closeAll] (S11).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistoryDbBridgeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val dir get() = File(app.filesDir, "history-sqlite")
    private val indexFile get() = File(dir, "history-index.db")
    private val tables = HistoryTables(coreJson)
    private val open = mutableListOf<HistoryDbBridge>()

    @Before fun clean() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @After fun tidy() = runBlocking<Unit> {
        open.forEach { it.closeAll() }
        dir.deleteRecursively()
        Unit
    }

    private suspend fun openIndex(create: Boolean = true, file: File = indexFile) =
        HistoryDbBridge.open(file, HistoryDbBridge.options(HistoryRole.INDEX, create = create)).also { open += it }

    private fun rowIn(shot: StoredShot) = HistoryRowIn(rowJsonOf(shot))

    private fun rowJsonOf(shot: StoredShot) = tables.rowJson(shot, summaryOf(shot, coreJson))

    /** [count] rows whose ids no other fixture row has. */
    private fun fresh(prefix: String, count: Int, start: Long) =
        HistoryFixtures.rows(count, samplesPer = 3, start = start).mapIndexed { i, s -> s.copy(id = "$prefix-$i") }

    private suspend fun HistoryDbBridge.allIds(query: HistoryQuery = HistoryDbBridge.ALL, pageSize: UInt = 7u): List<String> {
        val ids = mutableListOf<String>()
        var page = page(query, HistoryAnchor.Start, pageSize)
        while (true) {
            ids += page.rows.map { it.id }
            if (page.atEnd) return ids
            page = page(query, HistoryAnchor.After(page.last!!), pageSize)
        }
    }

    @Test fun openWritePageSearchAndStats() = runBlocking<Unit> {
        val shots = HistoryFixtures.rows(40, samplesPer = 20)
        val db = openIndex()
        assertEquals(HistoryRole.INDEX, db.info().role)
        val written = db.upsert(shots.map(::rowIn))
        assertEquals(shots.map { it.id }, written.change.upserted)
        assertEquals(1uL, written.change.seq)
        assertTrue(historyChangeMovesList(written.change.kinds, HistoryDbBridge.ALL))

        assertEquals(40u, db.count(HistoryDbBridge.ALL))
        // The fixture is newest first with distinct times: the list's order.
        assertEquals(shots.map { it.id }, db.allIds())
        val page = db.page(HistoryDbBridge.ALL, HistoryAnchor.Offset(10u), 5u)
        assertEquals(10u, page.startIndex)
        assertEquals(shots.drop(10).take(5).map { it.id }, page.rows.map { it.id })
        assertEquals(written.change.seq, page.seq)

        // Notes on every third row hold “quotes”; search folds and finds them.
        val search = HistoryDbBridge.ALL.copy(text = "QUOTES")
        val noted = shots.filter { it.notes?.contains("quotes") == true }.map { it.id }
        assertTrue(noted.isNotEmpty())
        assertEquals(noted.size.toUInt(), db.count(search))
        assertEquals(noted, db.allIds(search, pageSize = 3u))
        assertEquals(HistoryFtsStatus.Ready, db.health().fts)

        val stats = coreJson.decodeFromString(BrewHistoryStats.serializer(), db.statsJson(HistoryDbBridge.ALL))
        assertEquals(shots.size.toUInt(), stats.count)
        assertTrue(stats.avgRating != null)
    }

    @Test fun aStoredShotRoundTripsThroughRowJsonUnchanged() = runBlocking<Unit> {
        val db = openIndex()
        val shots = HistoryFixtures.rows(12, samplesPer = 30)
        db.upsert(shots.map(::rowIn))
        val back = db.get(shots.map { it.id }).associateBy { it.id }
        for (shot in shots) {
            val stored = back.getValue(shot.id)
            assertEquals(rowJsonOf(shot), stored.rowJson)
            val light = shot.copy(samples = emptyList(), profile = null, brewSeries = null)
            assertEquals(light, tables.decodeRow(stored.rowJson).shot)
        }
    }

    @Test fun anExportWalkHoldsDeletesAndRunsFromAnyThread() = runBlocking<Unit> {
        val db = openIndex()
        val shots = HistoryFixtures.rows(25, samplesPer = 5)
        db.upsert(shots.map(::rowIn))
        val walk = db.exportBegin()
        assertEquals(25u, walk.count)

        // A delete during the walk holds the row: hidden, still walked.
        val gone = shots[3].id
        db.delete(listOf(gone))
        assertTrue(db.get(listOf(gone)).isEmpty())
        assertEquals(24u, db.count(HistoryDbBridge.ALL))

        // Each step on another thread: one connection, so every call finds
        // the walk's set (M3).
        val pools = List(3) { Executors.newSingleThreadExecutor() }
        val lines = mutableListOf<String>()
        try {
            var i = 0
            while (true) {
                val step = withContext(pools[i++ % pools.size].asCoroutineDispatcher()) {
                    db.exportNext(walk.token, 4u)
                }
                if (step.isEmpty()) break
                lines += step.map { it.id }
            }
        } finally {
            pools.forEach { it.shutdown() }
        }
        assertEquals(walk.count.toInt(), lines.size)
        assertEquals(shots.map { it.id }.toSet(), lines.toSet())
        assertTrue(db.releaseHolds().isEmpty(), "the walk still needs the held row")
        db.exportEnd(walk.token)
        assertEquals(listOf(gone), db.releaseHolds())
        db.delete(listOf(gone))
        assertTrue(db.releaseHolds().isEmpty())
        assertFailsWith<HistoryDbException.UnknownWalk> { db.exportNext(walk.token, 1u) }
    }

    @Test fun restoreMergesThenRemovesOnlyWhatTheBackupLacks() = runBlocking<Unit> {
        val db = openIndex()
        val shots = HistoryFixtures.rows(10, samplesPer = 5)
        db.upsert(shots.map(::rowIn))
        val backup = shots.take(6) + fresh("backup", 3, start = 1_800_000_000_000L)
        val begin = db.restoreBegin()
        backup.chunked(4).forEach { db.restoreBatch(it.map(::rowIn)) }
        // A capture during the restore is never in the wipe.
        val captured = fresh("captured", 1, start = 1_900_000_000_000L).single()
        db.upsert(listOf(rowIn(captured)))

        val before = mutableListOf<String>()
        var after = 0L
        while (true) {
            val step = db.idsCreatedBefore(begin, after, 4u)
            if (step.isEmpty()) break
            before += step.map { it.id }
            after = step.last().rid
        }
        assertFalse(captured.id in before)
        val wipe = before - backup.map { it.id }.toSet()
        assertEquals(shots.drop(6).map { it.id }.toSet(), wipe.toSet())
        db.delete(wipe)
        assertEquals((backup.map { it.id } + captured.id).toSet(), db.allIds().toSet())
    }

    @Test fun errorsAreTypedKotlinExceptions() = runBlocking<Unit> {
        val history = File(dir, "history.db")
        // An authority file is never created by an open (REVIEW-439 note 10).
        assertFailsWith<HistoryDbException.Refused> {
            HistoryDbBridge.open(history, HistoryDbBridge.options(HistoryRole.AUTHORITY, create = true))
        }
        assertFailsWith<HistoryDbException.CantOpen> {
            HistoryDbBridge.open(history, HistoryDbBridge.options(HistoryRole.AUTHORITY))
        }
        assertFalse(history.exists())

        val db = openIndex()
        db.upsert(listOf(rowIn(HistoryFixtures.rows(1, samplesPer = 3).single())))
        // One connection per file (S4).
        assertFailsWith<HistoryDbException.AlreadyOpen> { openIndex(create = false) }
        assertFailsWith<HistoryDbException.Refused> { db.upsert(listOf(HistoryRowIn("[1, 2]"))) }
        assertFailsWith<HistoryDbException.Refused> { db.page(HistoryDbBridge.ALL, HistoryAnchor.After("not a cursor"), 5u) }
        assertFailsWith<HistoryDbException.NotListed> { db.page(HistoryDbBridge.ALL, HistoryAnchor.Around("nobody", 2u), 5u) }
        assertFailsWith<HistoryDbException.UnknownWalk> { db.exportNext(12345uL, 1u) }
        db.closeAll()

        // The index file is not the truth.
        val wrong = assertFailsWith<HistoryDbException.WrongRole> {
            HistoryDbBridge.open(indexFile, HistoryDbBridge.options(HistoryRole.AUTHORITY))
        }
        assertEquals(HistoryRole.INDEX, wrong.found)

        // A newer build's file.
        SQLiteDatabase.openDatabase(indexFile.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE meta SET value = '999' WHERE key = 'min_reader_v'")
        }
        val tooNew = assertFailsWith<HistoryDbException.TooNew> { openIndex(create = false) }
        assertEquals(999u, tooNew.minReaderV)

        // Another app's database.
        val foreign = File(dir, "foreign.db")
        SQLiteDatabase.openOrCreateDatabase(foreign, null).use {
            it.execSQL("PRAGMA application_id = 7")
            it.execSQL("CREATE TABLE t (x)")
        }
        assertFailsWith<HistoryDbException.Foreign> { openIndex(create = false, file = foreign) }

        // A header overwritten: corrupt, and the bytes stay as they were.
        val junk = File(dir, "junk.db").apply { writeBytes(ByteArray(8192) { 0x5a }) }
        val bytes = junk.readBytes()
        assertFailsWith<HistoryDbException.Corrupt> { openIndex(create = true, file = junk) }
        assertContentEquals(bytes, junk.readBytes())
    }

    @Test fun twoCoroutinesWritingAndReadingAtOnceSerialise() = runBlocking<Unit> {
        val db = openIndex()
        val shots = HistoryFixtures.rows(120, samplesPer = 3)
        val base = db.writeSeq()
        val writer = async(Dispatchers.IO) {
            shots.map { db.upsert(listOf(rowIn(it))).change.seq }
        }
        val reader = async(Dispatchers.IO) {
            val seen = mutableListOf<Pair<ULong, Int>>()
            do {
                val page = db.page(HistoryDbBridge.ALL, HistoryAnchor.Start, 500u)
                seen += page.seq to page.rows.size
            } while (!writer.isCompleted)
            seen
        }
        val seqs = writer.await()
        val reads = reader.await()
        // Every write committed alone, one seq each, in call order.
        assertEquals((1..shots.size).map { base + it.toULong() }, seqs)
        // Every read saw a whole committed state: as many rows as writes at
        // its seq, never a half-written one, and never going back.
        assertTrue(reads.isNotEmpty())
        for ((seq, rows) in reads) assertEquals((seq - base).toInt(), rows)
        assertEquals(reads.map { it.first }.sorted(), reads.map { it.first })
        assertEquals(shots.size.toUInt(), db.count(HistoryDbBridge.ALL))

        // Many at once, mixed: each change set has its own seq.
        val more = fresh("more", 30, start = 1_700_000_000_000L)
        val mixed = more.map { shot ->
            async(Dispatchers.IO) {
                db.count(HistoryDbBridge.ALL)
                db.upsert(listOf(rowIn(shot))).change.seq
            }
        }.awaitAll()
        assertEquals(mixed.size, mixed.toSet().size)
        assertEquals(150u, db.count(HistoryDbBridge.ALL))
        assertTrue(db.unsent(HistoryDestination.VISUALIZER, 0L, 1000u).size <= 150)
    }

    @Test fun eraseAfterCloseAllThenAWriteThenReopenFindsNothing() = runBlocking<Unit> {
        val db = openIndex()
        db.upsert(HistoryFixtures.rows(5, samplesPer = 3).map(::rowIn))
        db.closeAll()
        assertTrue(db.isClosed)
        // Erase: every file of the directory, as H0's erase removes it.
        assertTrue(dir.deleteRecursively())
        // A write after the close lands nowhere: it fails.
        assertFailsWith<HistoryDbException.Closed> {
            db.upsert(listOf(rowIn(HistoryFixtures.rows(1, samplesPer = 3).single())))
        }
        assertFalse(indexFile.exists())
        dir.mkdirs()
        val reopened = openIndex()
        assertEquals(0u, reopened.count(HistoryDbBridge.ALL))
        assertTrue(reopened.allIds().isEmpty())
    }
}
