package coffee.crema.history

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.persist.KeptAsideNotices
import coffee.crema.persist.SafeFile
import coffee.crema.ui.coreJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #310 A3a: what sync and upload read is the store's, never the view. The
 * store answers with every row it holds and every save handed to it, from the
 * moment of the call: an edit is seen before its write lands, a row is never
 * missing while it moves from "handed in" to "written", a row only the journal
 * holds is still here, a deleted one is gone at once, and nothing handed in
 * outlives its write to hide a later change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistorySyncReadsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val seriesDir get() = File(app.filesDir, "shot-series")

    private fun store() = HistoryStore(app, coreJson)

    @Before fun clean() {
        SafeFile.openAllForTest()
        HistoryStore.faults.clear()
        HistoryStore.legacyOnlyForTest = false
        HistoryStore.freeSpaceForTest = null
        runBlocking { store().erase() }
        seriesDir.deleteRecursively()
        File(app.filesDir, HistoryStore.FILE_NAME).delete()
        KeptAsideNotices.drain()
    }

    @After fun tidy() {
        HistoryStore.faults.clear()
        HistoryStore.legacyOnlyForTest = false
        SafeFile.resetForTest()
    }

    private fun shot(id: String, at: Long) = StoredShot(id = id, completedAtMs = at, durationMs = 28_000)

    /** A store on `shots.db` holding [shots], loaded. */
    private suspend fun loaded(vararg shots: StoredShot): Pair<HistoryStore, List<ShotRow>> {
        if (shots.isNotEmpty()) assertTrue(store().save(shots.toList()))
        val s = store()
        val view = s.loadAll().view
        assertTrue(s.holdsRows)
        return s to view
    }

    @Test
    fun `a save's rows are read from the call, before the write lands`() = runBlocking {
        val (s, view) = loaded(shot("a", 1), shot("b", 2))
        val a = view.first { it.id == "a" }
        val bound = a.copy(visualizerId = "vis-a")
        // Handed in, not written: what a launched save does at the call.
        s.accept(listOf(bound))
        assertEquals("vis-a", s.syncRow("a")?.visualizerId, "the stamp is read at once")
        assertEquals("vis-a", assertNotNull(s.syncRows()).first { it.id == "a" }.visualizerId)
        assertEquals(listOf("b", "a"), s.syncRows()?.map { it.id }, "newest first")
        assertTrue(s.save(listOf(bound), accepted = true))
        assertEquals("vis-a", s.syncRow("a")?.visualizerId, "and after it lands")
    }

    /** Another writer changes row a, and [s] reads the store again: what it reads must be that. */
    private suspend fun changedElsewhere(s: HistoryStore, row: ShotRow) {
        assertTrue(store().save(listOf(row.light().copy(notes = "elsewhere"))))
        s.loadAll()
        assertEquals("elsewhere", s.syncRow(row.id)?.notes, "what was handed in went once it landed")
    }

    @Test
    fun `a row handed in goes once it is written`() = runBlocking {
        val (s, view) = loaded(shot("a", 1))
        val v1 = view.single().copy(notes = "first")
        s.accept(listOf(v1))
        assertTrue(s.save(listOf(v1), accepted = true))
        changedElsewhere(s, v1)
    }

    @Test
    fun `a row handed in goes once its save finds it unchanged`() = runBlocking {
        val (s, view) = loaded(shot("a", 1))
        // Equal to the stored row, but a new object: a save finds it unchanged and writes nothing.
        val same = view.single().copy()
        s.accept(listOf(same))
        assertTrue(s.save(listOf(same), accepted = true))
        changedElsewhere(s, same)
    }

    @Test
    fun `a new shot the disk refused is still read, from the journal`() = runBlocking {
        val (s, _) = loaded(shot("a", 1))
        HistoryStore.faults += MigrationFault.DISK_FULL
        val k = shot("k", 5)
        assertFalse(s.save(listOf(k)))
        HistoryStore.faults.clear()
        assertEquals(setOf("k"), s.journaled)
        assertNotNull(s.syncRow("k"), "the store holds it in its journal")
        assertEquals(listOf("k", "a"), s.syncRows()?.map { it.id })
        s.delete(listOf("k"))
        assertNull(s.syncRow("k"))
        assertEquals(listOf("a"), s.syncRows()?.map { it.id })
    }

    @Test
    fun `a delete is read at its call, not when it lands`() = runBlocking {
        val (s, _) = loaded(shot("a", 1), shot("b", 2))
        s.forget(listOf("a"))
        assertNull(s.syncRow("a"))
        assertEquals(listOf("b"), s.syncRows()?.map { it.id })
        s.delete(listOf("a"))
        assertNull(s.syncRow("a"))
        assertEquals(listOf("b"), store().also { it.loadAll() }.syncRows()?.map { it.id })
    }

    @Test
    fun `a change to a row the view doesn't hold is read at once, and an older save landing after doesn't hide it`() = runBlocking {
        val (s, view) = loaded(shot("a", 1))
        val rated = view.single().copy(rating = 3)
        // A save of the rating is handed in, then the row is bound where no view reaches it.
        s.accept(listOf(rated))
        assertTrue(s.updateHidden("a") { it.copy(visualizerId = "vis-a") })
        val now = assertNotNull(s.syncRow("a"))
        assertEquals("vis-a", now.visualizerId)
        assertEquals(3, now.rating, "built on the save handed in, the newest version")
        // The older save, launched before, lands after.
        assertTrue(s.save(listOf(rated), accepted = true))
        assertEquals("vis-a", s.syncRow("a")?.visualizerId, "not hidden by the older save")
        s.flushHidden("a")
        assertEquals("vis-a", s.syncRow("a")?.visualizerId)
        val back = store().load().single()
        assertEquals("vis-a", back.visualizerId)
        assertEquals(3, back.rating)
    }

    @Test
    fun `no row is missing while its saves land`() = runBlocking {
        val shots = (1..40).map { shot("s$it", it.toLong()) }
        val (s, view) = loaded(*shots.toTypedArray())
        val ids = shots.mapTo(HashSet()) { it.id }
        val stop = AtomicBoolean(false)
        val reads = AtomicInteger()
        val missing = AtomicInteger()
        val reader = launch(Dispatchers.IO) {
            while (!stop.get()) {
                val got = s.syncRows().orEmpty().mapTo(HashSet()) { it.id }
                reads.incrementAndGet()
                if (!got.containsAll(ids)) missing.incrementAndGet()
                if (ids.any { s.syncRow(it) == null }) missing.incrementAndGet()
            }
        }
        try {
            var rows = view
            repeat(30) { round ->
                rows = rows.map { it.copy(notes = "round $round") }
                assertTrue(s.save(rows))
            }
        } finally {
            stop.set(true)
            reader.join()
        }
        assertTrue(reads.get() > 0)
        assertEquals(0, missing.get(), "a row mid-write read as absent")
        assertEquals(setOf<String?>("round 29"), s.syncRows().orEmpty().mapTo(HashSet<String?>()) { it.notes })
    }

    @Test
    fun `the legacy backend doesn't answer, its rows are the view's list`() = runBlocking {
        HistoryStore.legacyOnlyForTest = true
        val s = store()
        val load = s.loadAll()
        assertEquals(HistoryHealth.LEGACY, load.health)
        assertFalse(s.holdsRows)
        assertNull(s.syncRows())
        assertFalse(s.accept(listOf(s.rowFor(shot("x", 1)))))
        assertNull(s.syncRows(), "nothing is held for it")
    }

    @Test
    fun `a save handed in before the store opens is read once it opens`() = runBlocking {
        // REVIEW-467 N1: a retry or a rebuild leaves the store unopened while saves go on.
        assertTrue(store().save(listOf(shot("a", 1))))
        val s = store()
        val stamped = store().also { it.loadAll() }.syncRow("a")!!.copy(visualizerId = "vis-a")
        assertTrue(s.accept(listOf(stamped)), "taken note of, open or not")
        s.loadAll()
        assertTrue(s.holdsRows)
        assertEquals("vis-a", s.syncRow("a")?.visualizerId, "the stamp handed in, not the older stored row")
        assertTrue(s.save(listOf(stamped), accepted = true))
        assertEquals("vis-a", store().load().single().visualizerId)
    }

    @Test
    fun `a row deleted while the store is out of reach reads as gone`() = runBlocking {
        // REVIEW-467 N2: out of reach mid-session, rows still in memory.
        val marker = File(app.filesDir, HistoryStore.MARKER_NAME)
        try {
            assertTrue(store().save(listOf(shot("a", 1), shot("b", 2))))
            val s = store()
            s.loadAll()
            assertTrue(s.usesSql)
            marker.delete()
            marker.mkdirs(); File(marker, "x").writeText("x")
            assertEquals(HistoryHealth.UNAVAILABLE, s.retry().health)
            assertTrue(s.holdsRows, "out of reach, it answers for its rows")
            s.delete(listOf("a"))
            assertNull(s.syncRow("a"), "a deleted row is gone for sync")
            assertEquals(listOf("b"), s.syncRows()?.map { it.id })
        } finally {
            marker.deleteRecursively()
        }
    }

    @Test
    fun `before the store opens it doesn't answer`() {
        val s = store()
        assertFalse(s.holdsRows)
        assertNull(s.syncRows())
    }
}
