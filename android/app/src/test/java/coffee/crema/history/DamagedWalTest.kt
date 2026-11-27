package coffee.crema.history

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import coffee.crema.core.HistoryRole
import coffee.crema.persist.KeptAside
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
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #578 follow-up: a history database's `-wal` that a kill left damaged. SQLite
 * reads a WAL whose header doesn't check out as empty and starts it over at its
 * next write, so the shots only in it would go without a word. Before the
 * database opens the damaged `-wal` is copied aside, never moved, and reported
 * once; and every save checkpoints, so a saved shot is in the main file anyway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DamagedWalTest {
    @get:org.junit.Rule val hang = HangWatch()

    @get:org.junit.Rule val stage2 = Stage2Rule(enabled = true)

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val historyDb get() = File(File(app.filesDir, HistoryStore.SQLITE_DIR), "history.db")
    private val shotsDb get() = app.getDatabasePath(HistoryStore.NEXT_DB_NAME)
    private val stores = mutableListOf<HistoryStore>()

    private fun store() = HistoryStore(app, coreJson, 7).also { stores += it }

    @Before fun clean() {
        SafeFile.openAllForTest()
        runBlocking { store().erase() }
        File(app.filesDir, "shot-series").deleteRecursively()
        KeptAsideNotices.drain()
    }

    @After fun tidy() {
        DamagedWal.copyFile = { from, to -> from.copyTo(to) }
        runBlocking { stores.forEach { it.closeForTest() } }
        KeptAsideNotices.drain()
        SafeFile.resetForTest()
    }

    private fun shots(prefix: String, count: Int) =
        HistoryFixtures.rows(count, samplesPer = 4, start = 1_800_000_000_000L).mapIndexed { i, s -> s.copy(id = "$prefix-$i") }

    private suspend fun HistoryStore.boot(): HistoryLoad = loadAll().also { syncIndex() }

    private fun header(f: File) = f.inputStream().use { input -> ByteArray(32).also { check(input.read(it) == 32) } }

    private fun keptAside(wal: File) = wal.parentFile!!.listFiles { f -> f.name.startsWith(wal.name + ".corrupt-") }.orEmpty().toList()

    /** [main] alone, copied where no `-wal` sits beside it: what a damaged `-wal` would leave. */
    private fun mainOnly(main: File): File {
        val dir = File(app.cacheDir, "main-only-${System.nanoTime()}").apply { mkdirs() }
        return File(dir, main.name).also { main.copyTo(it) }
    }

    /** The ids in a copy of `history.db`, read through the core. */
    private fun historyIds(file: File): Set<String> {
        val b = HistoryDbBridge.openNow(file, HistoryDbBridge.options(HistoryRole.AUTHORITY))
        try {
            return b.now { it.idsCreatedBefore(ULong.MAX_VALUE, Long.MIN_VALUE, 500u) }.mapTo(HashSet()) { it.id }
        } finally {
            b.now { it.closeAll() }
        }
    }

    private fun shotsIds(file: File): Set<String> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id FROM shots", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        }

    // ── The header check ────────────────────────────────────────────────────

    @Test fun `the -wal SQLite writes, the core's and the framework's, has a header that checks out`() = runBlocking {
        val s = store()
        s.boot()
        assertTrue(s.onHistoryDb)
        assertTrue(s.save(shots("a", 2)))
        val coreWal = File(historyDb.path + "-wal")
        assertTrue(coreWal.length() > 32, "history.db has a -wal while open")
        assertTrue(DamagedWal.headerValid(header(coreWal)))

        val path = File(app.cacheDir, "framework.db").path
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.enableWriteAheadLogging()
            db.execSQL("CREATE TABLE t(x)")
            db.execSQL("INSERT INTO t VALUES (1)")
            val h = header(File("$path-wal"))
            assertTrue(DamagedWal.headerValid(h))
            // Any byte changed in the first 24 fails the header's checksum.
            for (i in listOf(4, 12, 16, 23)) assertFalse(DamagedWal.headerValid(h.copyOf().also { it[i] = (it[i] + 1).toByte() }), "byte $i")
        }
        assertFalse(DamagedWal.headerValid(ByteArray(32)), "zero-filled")
        assertFalse(DamagedWal.headerValid(ByteArray(31)), "cut short")
    }

    @Test fun `a zero-filled history-db -wal is copied aside byte for byte, reported once, and the history opens`() = runBlocking {
        val first = store()
        first.boot()
        assertTrue(first.save(shots("z", 3)))
        first.closeForTest()
        val wal = File(historyDb.path + "-wal")
        val zeros = ByteArray(4099)
        wal.writeBytes(zeros)

        val s = store()
        val load = s.boot()

        assertEquals(HistoryHealth.OK, load.health)
        assertTrue(s.onHistoryDb)
        assertEquals(3, load.view.size)
        val kept = keptAside(wal).single()
        assertContentEquals(zeros, kept.readBytes(), "copied byte for byte")
        val notices = KeptAsideNotices.drain()
        assertEquals(listOf(KeptAside(DamagedWal.WHAT, kept)), notices)
        assertEquals("Couldn't read your latest shot history changes; the damaged file was kept aside.", KeptAsideNotices.message(notices))
        s.closeForTest()
        store().boot()
        assertTrue(KeptAsideNotices.drain().isEmpty(), "reported once")
        assertEquals(1, keptAside(wal).size)
    }

    @Test fun `a shots-db -wal cut off inside its header is copied aside on a stage-1 device`() = runBlocking {
        HistoryStore.stage2Enabled = false
        val first = store()
        first.boot()
        assertTrue(first.save(shots("c", 2)))
        first.closeForTest()
        val wal = File(shotsDb.path + "-wal")
        val cut = ByteArray(20) { 0x37 }
        wal.writeBytes(cut)

        val s = store()
        assertEquals(HistoryHealth.OK, s.boot().health)
        assertContentEquals(cut, keptAside(wal).single().readBytes())
        assertEquals(listOf(DamagedWal.WHAT), KeptAsideNotices.drain().map { it.what })
        s.erase()
        assertTrue(keptAside(wal).isEmpty(), "Erase all data takes the copy too")
    }

    @Test fun `a copy that fails is logged, leaves no partial copy, and the history opens as before`() = runBlocking {
        val db = File(app.cacheDir, "x.db")
        val wal = File(db.path + "-wal").apply { writeBytes(ByteArray(4099)) }
        DamagedWal.copyFile = { _, to -> to.writeBytes(ByteArray(10)); throw IOException("disk full") }
        val log = mutableListOf<String>()
        assertNull(DamagedWal.keepAsideIfDamaged(db, log::add))
        assertTrue(log.single().startsWith("couldn't keep the damaged x.db-wal aside (IOException)"), "$log")
        assertTrue(keptAside(wal).isEmpty(), "no partial copy")
        assertTrue(KeptAsideNotices.drain().isEmpty())

        // In the store: the open goes ahead.
        val first = store()
        first.boot()
        assertTrue(first.save(shots("f", 2)))
        first.closeForTest()
        File(historyDb.path + "-wal").writeBytes(ByteArray(4099))
        val s = store()
        assertEquals(HistoryHealth.OK, s.boot().health)
        assertTrue(s.onHistoryDb)
        assertTrue(KeptAsideNotices.drain().isEmpty())
    }

    @Test fun `a good or empty -wal is left alone, and only the newest three copies are kept`() {
        val db = File(app.cacheDir, "y.db")
        val wal = File(db.path + "-wal")
        assertNull(DamagedWal.keepAsideIfDamaged(db) {}, "no -wal")
        wal.writeBytes(ByteArray(0))
        assertNull(DamagedWal.keepAsideIfDamaged(db) {}, "an empty -wal")
        val old = (1L..4L).map { File(wal.path + ".corrupt-$it").apply { writeText("old $it") } }
        wal.writeBytes(ByteArray(64))
        val kept = DamagedWal.keepAsideIfDamaged(db) {}!!
        assertEquals(setOf(kept, old[3], old[2]), keptAside(wal).toSet())
        assertTrue(wal.exists(), "copied, never moved")
        assertNull(DamagedWal.keepAsideIfDamaged(db) {}, "the newest copy holds these bytes already")
        assertEquals(3, keptAside(wal).size)
    }

    @Test fun `the copy just made is kept even when older copies are named later (the clock went back)`() {
        val db = File(app.cacheDir, "z.db")
        val wal = File(db.path + "-wal")
        val future = System.currentTimeMillis() + 86_400_000L
        val old = (1L..3L).map { File(wal.path + ".corrupt-${future + it}").apply { writeText("old $it") } }
        wal.writeBytes(ByteArray(64))
        val kept = DamagedWal.keepAsideIfDamaged(db) {}!!
        assertEquals(setOf(kept, old[2], old[1]), keptAside(wal).toSet())
    }

    // ── The checkpoint after a save ─────────────────────────────────────────

    @Test fun `a saved shot is in history-db's main file, not only its -wal`() = runBlocking {
        val s = store()
        s.boot()
        assertTrue(s.onHistoryDb)
        val saved = shots("k", 2)
        assertTrue(s.save(saved))
        assertEquals(saved.map { it.id }.toSet(), historyIds(mainOnly(historyDb)))
    }

    @Test fun `a saved shot is in shots-db's main file on a stage-1 device`() = runBlocking {
        HistoryStore.stage2Enabled = false
        val s = store()
        s.boot()
        val saved = shots("p", 2)
        assertTrue(s.save(saved))
        assertTrue(File(shotsDb.path + "-wal").exists(), "shots.db is in WAL")
        assertEquals(saved.map { it.id }.toSet(), shotsIds(mainOnly(shotsDb)))
    }
}
