package coffee.crema.history

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.core.HistoryRole
import coffee.crema.persist.KeptAsideNotices
import coffee.crema.persist.SafeFile
import coffee.crema.ui.coreJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #310 A5: the update every Android user makes, from 0.10.0. That build is
 * before P3a: the history is `shots.json` (capped at 300 rows) and a `.json`
 * series file per shot. On Android the whole path runs in the first launch on
 * this build: P3a's migration into `shots.db`, then, in the same open, the
 * flip into `history.db` (web takes two launches; Android's store opens once
 * per process and flips at its first open). The old copies, `shots.json` with
 * its `.bak` and series and the new `shots.db`, are left as they are, a shot
 * only the `.bak` holds doesn't come back, and a second launch changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistoryUpgradeTest {
    @get:Rule val stage2 = Stage2Rule(enabled = true)

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val filesDir get() = app.filesDir
    private val sqliteDir get() = File(filesDir, HistoryStore.SQLITE_DIR)
    private val shotsDb get() = app.getDatabasePath(HistoryStore.NEXT_DB_NAME)
    private val stores = mutableListOf<HistoryStore>()

    private fun store() = HistoryStore(app, coreJson, 7).also { stores += it }

    @Before fun clean() {
        SafeFile.openAllForTest()
        HistoryStore.faults.clear()
        HistoryFlip.FAULTS.clear()
        runBlocking { store().erase() }
        File(filesDir, "shot-series").deleteRecursively()
        KeptAsideNotices.drain()
    }

    @After fun tidy() {
        HistoryStore.faults.clear()
        HistoryFlip.FAULTS.clear()
        HistoryFlip.betweenBatchesForTest = null
        HistoryStore.holdOpen = null
        runBlocking { stores.forEach { it.closeForTest() } }
        SafeFile.resetForTest()
    }

    /** A launch as the app runs it: the rows, then the index's boot check. */
    private suspend fun HistoryStore.boot(): HistoryLoad = loadAll().also { syncIndex() }

    /**
     * 0.10.0's files: `shots.json` with only the fields that build had, a few
     * rows' series inline, the rest in `.json` files, and the `.bak` its last
     * save kept, from before a shot was deleted (that shot's series pruned).
     */
    private fun plant(n: Int): List<StoredShot> {
        val rows = HistoryFixtures.as0100(HistoryFixtures.rows(n, samplesPer = 6))
        val deleted = HistoryFixtures.as0100(HistoryFixtures.rows(1, samplesPer = 6, start = 1_700_000_000_000L)).single().copy(id = "deleted-in-0_10_0")
        HistoryFixtures.plantOldBuild(app, rows, inline = setOf(rows[1].id, rows[2].id), previous = rows + deleted)
        val text = File(filesDir, HistoryStore.FILE_NAME).readText()
        for (key in HistoryFixtures.NOT_IN_0100) assertFalse("\"$key\"" in text, "0.10.0 never wrote $key")
        assertTrue(File(filesDir, HistoryStore.FILE_NAME + ".bak").exists())
        return rows
    }

    /** `shots.json`, its `.bak` and every `.json` series: 0.10.0's old copy, by name and bytes. */
    private fun oldCopy(): Map<String, List<Byte>> =
        HistoryFixtures.filesUnder(filesDir)
            .filterKeys { it == HistoryStore.FILE_NAME || it == HistoryStore.FILE_NAME + ".bak" || (it.startsWith("shot-series") && it.endsWith(".json")) }
            .mapValues { it.value.toList() }

    private fun shotsDbBytes(): Map<String, List<Byte>> =
        listOf("", "-wal").mapNotNull { s -> File(shotsDb.path + s).takeIf { it.exists() }?.let { s to it.readBytes().toList() } }.toMap()

    /** `history.db`'s every row and heavy part as stored. */
    private fun historyFile(): Map<String, Triple<String, String?, String?>> {
        val m = coreJson.decodeFromString(Stage2Marker.serializer(), File(filesDir, HistoryStore.SQLITE_MARKER_NAME).readText())
        val f = Stage2Files(filesDir, coreJson).db(m.generation)
        val b = HistoryDbBridge.openNow(f, HistoryDbBridge.options(HistoryRole.AUTHORITY))
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

    /** Every shot whole, read through a store on `history.db`: every field and series as 0.10.0 had it. */
    private suspend fun assertWholeOnHistoryDb(rows: List<StoredShot>, what: String) {
        val s = store()
        val load = s.boot()
        assertTrue(s.onHistoryDb, "$what: on history.db (${s.flipError ?: s.lastError})")
        assertEquals(rows.map { it.id }.toSet(), load.view.map { it.id }.toSet(), "$what: every shot")
        val whole = load.view.map { it.joined(assertNotNullShot(s.loadShot(it.id), it.id)) }.sortedWith(HistoryStore.ORDER)
        assertEquals(rows.distinctBy { it.id }.sortedWith(HistoryStore.ORDER), whole, "$what: every field, profile and series")
        s.closeForTest()
    }

    private fun assertNotNullShot(s: StoredShot?, id: String): StoredShot = s ?: error("shot $id doesn't read whole")

    private fun upgrade(n: Int) = runBlocking {
        val rows = plant(n)
        val old = oldCopy()
        // The first launch on this build: shots.json → shots.db → history.db, in one open.
        val first = store()
        first.boot()
        assertTrue(first.onHistoryDb, "$n rows: one launch flips on Android (${first.flipError})")
        first.closeForTest()
        assertWholeOnHistoryDb(rows, "$n rows")
        assertEquals(old, oldCopy(), "$n rows: shots.json and its series left as they were")
        // shots.db holds the migrated copy, untouched since.
        val db = shotsDbBytes()
        val truth = historyFile()
        // A second launch changes nothing.
        val second = store()
        second.boot()
        assertTrue(second.onHistoryDb)
        assertEquals(false, second.lastLate?.read, "nothing moved, so no late-row read")
        second.closeForTest()
        assertEquals(truth, historyFile(), "$n rows: the second launch wrote nothing")
        assertEquals(db, shotsDbBytes(), "$n rows: shots.db left as it was")
        assertEquals(old, oldCopy())
    }

    @Test fun `from 0_10_0 with 300 shots, the first launch puts every shot on history-db and a second changes nothing`() = upgrade(300)

    @Test fun `from 0_10_0 with 5,000 shots (a stress case past its cap), the same`() = upgrade(5_000)

    @Test fun `a shot captured during the first launch's migration lands on history-db too`() = runBlocking {
        val rows = plant(300)
        val gate = CompletableDeferred<Unit>()
        HistoryStore.holdOpen = gate
        val s = store()
        val boot = async(Dispatchers.IO) { s.boot() }
        // The capture arrives before the open: it waits for the store, which migrates, then lands in shots.db.
        val capture = HistoryFixtures.rows(1, samplesPer = 9, start = 1_900_000_000_000L).single().copy(id = "captured")
        assertTrue(s.save(listOf(capture)))
        gate.complete(Unit)
        boot.await()
        assertTrue(s.onHistoryDb, s.flipError)
        assertTrue(s.rows().any { it.id == capture.id })
        s.closeForTest()
        assertWholeOnHistoryDb(rows + capture, "with a capture")
    }

    @Test fun `a shot captured during the first launch's flip lands on history-db in the same launch`() = runBlocking {
        val rows = plant(1_200)
        val s = store()
        val capture = HistoryFixtures.rows(1, samplesPer = 9, start = 1_900_000_000_000L).single().copy(id = "mid-flip")
        HistoryFlip.betweenBatchesForTest = {
            HistoryFlip.betweenBatchesForTest = null
            assertTrue(s.save(listOf(capture)))
        }
        s.boot()
        assertTrue(s.onHistoryDb, s.flipError)
        s.closeForTest()
        assertWholeOnHistoryDb(rows + capture, "a capture mid-flip")
    }

    @Test fun `a crash anywhere in the first launch, then a relaunch, ends with every shot on history-db`() = runBlocking {
        val crashes: List<() -> Unit> = listOf(
            { HistoryStore.faults += MigrationFault.CRASH_IN_SERIES },
            { HistoryStore.faults += MigrationFault.CRASH_IN_TRANSACTION },
            { HistoryStore.faults += MigrationFault.CRASH_AFTER_COMMIT },
            { HistoryFlip.FAULTS += FlipFault.AFTER_COPY },
            { HistoryFlip.FAULTS += FlipFault.AFTER_HEAVY },
            { HistoryFlip.FAULTS += FlipFault.AFTER_DONE },
        )
        for ((i, arm) in crashes.withIndex()) {
            clean()
            val rows = plant(300)
            val old = oldCopy()
            arm()
            val s = store()
            assertFailsWith<SimulatedCrash>("crash $i") { s.boot() }
            HistoryStore.faults.clear()
            HistoryFlip.FAULTS.clear()
            s.closeForTest()
            assertEquals(old, oldCopy(), "crash $i: shots.json and its series untouched")
            assertWholeOnHistoryDb(rows, "after crash $i")
            assertEquals(old, oldCopy())
        }
    }
}
