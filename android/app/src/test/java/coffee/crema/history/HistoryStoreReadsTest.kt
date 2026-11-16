package coffee.crema.history

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import coffee.crema.persist.KeptAsideNotices
import coffee.crema.persist.SafeFile
import coffee.crema.ui.ShotLoad
import coffee.crema.ui.coreJson
import coffee.crema.ui.shotLoadOf
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #419 review: reading a shot whole never gives a row without its curve
 * because of when it ran. A reload swaps the store's memory with no gap (M1);
 * a journal line whose series won't read keeps the curve its row had (S1); a
 * read that straddles a write doesn't cache the old copy (S2). Plus the SPEC's
 * P4w tests that apply here (S3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistoryStoreReadsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val seriesDir get() = File(app.filesDir, "shot-series")
    private val dbFile get() = app.getDatabasePath(HistoryStore.NEXT_DB_NAME)

    private fun store() = HistoryStore(app, coreJson)

    @Before fun clean() {
        // P3a's `shots.db` (stage 1): a store that hasn't flipped, or an older build (#310 A5).
        HistoryStore.stage2Enabled = false
        SafeFile.openAllForTest()
        // Every process-wide seam, as another class may have left it.
        HistoryStore.faults.clear()
        HistoryStore.legacyOnlyForTest = false
        HistoryStore.freeSpaceForTest = null
        HistoryStore.holdOpen = null
        HistoryStore.beforeCacheForTest = null
        runBlocking { store().erase() }
        seriesDir.deleteRecursively()
        File(app.filesDir, HistoryStore.FILE_NAME).delete()
        KeptAsideNotices.drain()
    }

    @After fun tidy() {
        HistoryStore.stage2Enabled = HistoryStore.STAGE2_IN_BUILD
        HistoryStore.faults.clear()
        HistoryStore.legacyOnlyForTest = false
        HistoryStore.beforeCacheForTest = null
        SafeFile.resetForTest()
    }

    /** A reader reads [target] whole in a loop while [reload] runs; how many reads came back without the curve or profile. */
    private fun raceReads(s: HistoryStore, target: ShotRow, wantProfile: Boolean, reload: suspend () -> Unit): Pair<Int, Int> = runBlocking {
        val stop = AtomicBoolean(false)
        val reads = AtomicInteger()
        val bad = AtomicInteger()
        val reader = launch(Dispatchers.IO) {
            while (!stop.get()) {
                try {
                    val w = s.loadFull(target)
                    reads.incrementAndGet()
                    if (w.samples.isEmpty() || (wantProfile && w.profile == null) || !s.hasCurve(target.id) || (wantProfile && !s.hasProfile(target.id))) bad.incrementAndGet()
                } catch (e: HistoryReadException) {
                    // "Can't read now" is allowed: the walk retries. A light row is not.
                }
            }
        }
        try {
            reload()
        } finally {
            stop.set(true)
            reader.join()
        }
        reads.get() to bad.get()
    }

    @Test
    fun `a legacy reload never shows a reader a shot without its curve or profile`() {
        HistoryStore.legacyOnlyForTest = true
        val rows = HistoryFixtures.rows(300, samplesPer = 60)
        HistoryFixtures.plantOldBuild(app, rows)
        val s = store()
        val view = runBlocking { s.loadAll() }.view
        val target = view.last { it.summarisedCurve && rows.first { r -> r.id == it.id }.profile != null }
        val (reads, bad) = raceReads(s, target, wantProfile = true) { repeat(5) { s.loadAll() } }
        assertTrue(reads > 0, "the reader ran")
        assertEquals(0, bad, "of $reads reads")
    }

    @Test
    fun `a shots-db reload never shows a reader a shot without its curve or profile`() {
        val rows = HistoryFixtures.rows(3_000, samplesPer = 4)
        HistoryFixtures.plantOldBuild(app, rows)
        val s = store()
        val view = runBlocking { s.loadAll() }.view
        val target = view.first { it.summarisedCurve && rows.first { r -> r.id == it.id }.profile != null }
        val (reads, bad) = raceReads(s, target, wantProfile = true) { repeat(30) { s.loadAll() } }
        assertTrue(reads > 0, "the reader ran")
        assertEquals(0, bad, "of $reads reads")
    }

    @Test
    fun `a row the store doesn't hold reads as can't-read-now for a backup, a deleted one comes light`() = runBlocking {
        val shot = HistoryFixtures.rows(2, samplesPer = 5)
        store().save(shot)
        val s = store()
        val view = s.loadAll().view
        val stranger = view.first().copy(id = "never-here")
        assertFailsWith<HistoryReadException> { s.loadFull(stranger) }
        s.delete(listOf(view.first().id))
        assertTrue(s.loadFull(view.first()).samples.isEmpty(), "deleted while the walk held it: light, not a failure")
    }

    /** Review q1: an edit journaled on a full disk; at the merge its series won't read; the curve must stay. */
    @Test
    fun `a journaled edit whose series won't read at the merge keeps its curve`() = runBlocking {
        val s = store()
        s.loadAll()
        val shot = HistoryFixtures.rows(1, samplesPer = 20).first()
        s.save(listOf(shot))
        val row = s.loadRows().single()
        HistoryStore.faults += MigrationFault.DISK_FULL
        s.save(listOf(row.copy(rating = 5)))
        HistoryStore.faults.clear()
        assertEquals(setOf(shot.id), s.journaled)
        val gz = SeriesFiles(seriesDir, coreJson).gzFile(shot.id)
        val bytes = gz.readBytes()
        gz.delete()
        gz.mkdirs() // a directory where the file goes: the read is an I/O error
        val merged = store().loadRows().single()
        assertEquals(5, merged.rating, "the edit merged")
        assertEquals(20u, merged.summary.sampleCount, "and the curve's count stayed")
        gz.deleteRecursively()
        gz.writeBytes(bytes)
        val s3 = store()
        val r3 = s3.loadRows().single()
        val missing = HashSet<String>()
        assertEquals(shot.samples, s3.loadFull(r3, missing).samples)
        assertTrue(s3.hasCurve(r3))
        assertTrue(missing.isEmpty())
    }

    /** A "no curve" summary next to a series file (a 0 stored by an older build): the file is read, and the summary corrected. */
    @Test
    fun `a zero count next to a series file reads the file and corrects the summary`() = runBlocking {
        val shot = HistoryFixtures.rows(1, samplesPer = 20).first()
        store().save(listOf(shot))
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        db.rawQuery("SELECT row_json FROM shots WHERE id = ?", arrayOf(shot.id)).use { c ->
            c.moveToFirst()
            val obj = coreJson.parseToJsonElement(c.getString(0)).jsonObject
            val summary = obj.getValue("summary").jsonObject
            val zeroed = JsonObject(obj + ("summary" to JsonObject(summary + ("sampleCount" to JsonPrimitive(0)))))
            db.execSQL("UPDATE shots SET row_json = ? WHERE id = ?", arrayOf(zeroed.toString(), shot.id))
        }
        db.close()
        val s = store()
        val row = s.loadRows().single()
        assertEquals(0u, row.summary.sampleCount)
        assertEquals(shot.samples, assertNotNull(s.loadShot(shot.id)).samples, "the file is read all the same")
        s.save(listOf(row))
        assertEquals(20u, store().loadRows().single().summary.sampleCount, "and the next save corrects the summary")
    }

    /** S2: a read that straddles a write mustn't leave the old copy in the cache. */
    @Test
    fun `a read that straddles a backfill doesn't cache the copy from before it`() = runBlocking {
        val base = HistoryFixtures.rows(1, samplesPer = 0).first().copy(brewMethod = null, brewSeries = null)
        val s = store()
        s.loadAll()
        s.save(listOf(base))
        s.loadAll()
        val pulled = HistoryFixtures.samples(7, 3)
        // While the detail reads the shot (no curve yet), a pull backfills it.
        HistoryStore.beforeCacheForTest = { id ->
            HistoryStore.beforeCacheForTest = null
            runBlocking {
                val row = assertNotNull(s.storedRow(id))
                s.save(listOf(s.stage(row.withHeavy(pulled, null, null))))
            }
        }
        assertTrue(assertNotNull(s.loadShot(base.id)).samples.isEmpty(), "the read from before the write")
        assertEquals(pulled, assertNotNull(s.loadShot(base.id)).samples, "the next read sees the write, not a stale cache")
    }

    /** SPEC P4w (applies to P4a): a legacy-mode edit keeps profile, brew series and samples, through a reload and a third launch. */
    @Test
    fun `a legacy-mode edit keeps profile, brew series and samples`() = runBlocking {
        HistoryStore.legacyOnlyForTest = true
        val rows = HistoryFixtures.rows(23, samplesPer = 10)
        val machine = rows[0]
        val inline = rows[1]
        val brew = rows[9]
        assertTrue(machine.profile != null && machine.samples.isNotEmpty() && brew.brewSeries != null)
        HistoryFixtures.plantOldBuild(app, rows, inline = setOf(inline.id))
        val ids = setOf(machine.id, inline.id, brew.id)
        val s = store()
        val view = s.loadAll().view
        s.openSaves()
        assertTrue(s.save(view.map { if (it.id in ids) it.copy(rating = 1, notes = "edit 1") else it }))
        val s2 = store()
        val view2 = s2.loadAll().view
        s2.openSaves()
        assertTrue(s2.save(view2.map { if (it.id in ids) it.copy(rating = 2) else it }))
        val s3 = store()
        s3.loadAll()
        for (orig in listOf(machine, inline, brew)) {
            val w = assertNotNull(s3.loadShot(orig.id))
            assertEquals(2, w.rating, orig.id)
            assertEquals("edit 1", w.notes, orig.id)
            assertEquals(orig.samples, w.samples, "${orig.id}'s curve")
            assertEquals(orig.profile, w.profile, "${orig.id}'s profile")
            assertEquals(orig.brewSeries, w.brewSeries, "${orig.id}'s brew series")
        }
    }

    /** SPEC P4w: rapid selection shows the right shot: a result for a shot no longer selected is dropped. */
    @Test
    fun `a load for a shot no longer selected is dropped, and the selection shows loading`() {
        val a = HistoryFixtures.rows(2, samplesPer = 5)[0]
        val b = HistoryFixtures.rows(2, samplesPer = 5)[1]
        val rowB = b.row()
        assertEquals(ShotLoad.Loading, shotLoadOf(rowB, a.id to a), "A's result never shows under B")
        assertEquals(ShotLoad.Loading, shotLoadOf(rowB, null))
        assertEquals(ShotLoad.Unreadable, shotLoadOf(rowB, b.id to null))
        val ready = shotLoadOf(rowB.copy(rating = 5), b.id to b) as ShotLoad.Ready
        assertEquals(b.samples, ready.shot.samples)
        assertEquals(5, ready.shot.rating, "the row's own fields win over the loaded copy's")
    }

    /** #419 F3: on legacy, a reload that reads fewer rows than the view holds keeps their heavy parts for the next save. */
    @Test
    fun `a legacy reload that lost a row the view holds keeps its profile and brew series`() = runBlocking {
        HistoryStore.legacyOnlyForTest = true
        val rows = HistoryFixtures.rows(23, samplesPer = 4)
        val machine = rows[0]
        val brew = rows[9]
        assertTrue(machine.profile != null && brew.brewSeries != null)
        HistoryFixtures.plantOldBuild(app, rows)
        val s = store()
        val view = s.loadAll().view
        s.openSaves()
        // shots.json loses the two rows (written by another session, say); this store reloads it.
        HistoryFixtures.plantOldBuild(app, rows.filterNot { it.id == machine.id || it.id == brew.id })
        s.loadAll()
        s.openSaves()
        // The view still holds them, and saves.
        assertTrue(s.save(view))
        val back = store()
        back.loadAll()
        assertEquals(machine.profile, assertNotNull(back.loadShot(machine.id)).profile, "the profile isn't written bare")
        assertEquals(brew.brewSeries, assertNotNull(back.loadShot(brew.id)).brewSeries, "nor the brew series")
    }

    /** #419 F1: a row written again after a delete, then missing from a read, is "can't read now", not a light row; erase forgets the deletes. */
    @Test
    fun `a revived row that goes missing throws, and erase forgets this session's deletes`() = runBlocking {
        val shots = HistoryFixtures.rows(3, samplesPer = 5)
        val s = store()
        assertEquals(HistoryHealth.OK, s.loadAll().health)
        s.save(shots)
        val row = s.loadRows().first { it.id == shots[0].id }
        s.delete(listOf(row.id))
        assertTrue(s.loadFull(row).samples.isEmpty(), "deleted: light")
        s.save(listOf(shots[0]))
        // Gone from the file behind the store's back, then a reload: not a delete of ours.
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DELETE FROM shots WHERE id = ?", arrayOf(row.id)) }
        s.loadAll()
        assertFailsWith<HistoryReadException>("revived, so not deleted") { s.loadFull(row) }
        // Erase forgets the deletes too.
        val other = s.loadRows().first()
        s.delete(listOf(other.id))
        s.erase()
        assertEquals(HistoryHealth.OK, s.loadAll().health)
        s.save(listOf(shots[2]))
        assertFailsWith<HistoryReadException>("after erase, an old delete says nothing") { s.loadFull(other) }
        Unit
    }
}
