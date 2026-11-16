package coffee.crema.history

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.persist.KeptAsideNotices
import coffee.crema.persist.SafeFile
import coffee.crema.ui.TelemetrySample
import coffee.crema.ui.coreJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * F10 validation, on `shots.db` since #310 P3a: the per-shot series store
 * never loses a shot's telemetry. A failed series write keeps the curve in
 * memory and the next save writes it; two ids never share a file; a file a
 * build before P3a wrote is read and never written or deleted; a corrupt file
 * is kept aside and reported, not read as "no curve". A save never deletes a
 * series: only [HistoryStore.delete] does, and only the `.series.gz` it wrote.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ShotSeriesStoreTest {
    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val seriesDir = File(app.filesDir, "shot-series")
    private val historyFile = File(app.filesDir, "shots.json")

    private fun shot(id: String, samples: Int, pressure: Float = 9f) = StoredShot(
        id = id,
        completedAtMs = 1_700_000_000_000L,
        durationMs = samples * 222L,
        samples = List(samples) { n -> TelemetrySample(elapsedMs = n * 222L, pressure = pressure, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = null, weightFlow = null, dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f, setGroupFlow = 0f, frameNumber = 1) },
    )

    private fun store() = HistoryStore(app, coreJson)

    @Before fun clean() {
        // P3a's `shots.db` (stage 1): a store that hasn't flipped, or an older build (#310 A5).
        HistoryStore.stage2Enabled = false
        runBlocking { store().erase() }
        seriesDir.deleteRecursively()
        KeptAsideNotices.drain()
    }

    @After fun tidy() {
        HistoryStore.stage2Enabled = HistoryStore.STAGE2_IN_BUILD
        SafeFile.resetForTest()
        seriesDir.deleteRecursively()
        File(app.filesDir, "shot-series").takeIf { it.isFile }?.delete()
    }

    private fun gz(id: String) = File(seriesDir, HistoryStore.seriesFileName(id).removeSuffix(".json") + ".series.gz")
    private fun json(id: String) = File(seriesDir, HistoryStore.seriesFileName(id))
    private fun writeJsonSeries(f: File, n: Int) {
        seriesDir.mkdirs()
        f.writeText(coreJson.encodeToString(ListSerializer(TelemetrySample.serializer()), shot("x", n).samples))
    }

    @Test
    fun `a failed series write keeps the curve in memory until a later save writes it`() = runBlocking {
        // A plain file where the series directory goes: every series write fails.
        seriesDir.deleteRecursively()
        seriesDir.writeText("not a directory")
        val s = store()
        val row = shot("s1", 5)
        s.save(listOf(row))
        assertEquals(setOf("s1"), s.unsavedCurves, "the shot is reported as not fully saved")
        assertEquals(1, store().loadRows().size, "the row landed without it")

        // The directory is back: the next save writes the series it still holds.
        seriesDir.delete()
        s.save(listOf(row))
        assertTrue(s.unsavedCurves.isEmpty())
        assertTrue(gz("s1").exists())
        assertEquals(5, store().load().single().samples.size)
    }

    @Test
    fun `two ids never share a series file`() = runBlocking {
        assertNotEquals(HistoryStore.seriesFileName("shot:1"), HistoryStore.seriesFileName("shot_1"))
        assertEquals("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b.json", HistoryStore.seriesFileName("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"))
        store().save(listOf(shot("shot:1", 3, pressure = 1f), shot("shot_1", 4, pressure = 2f)))
        assertNotEquals(gz("shot:1").name, gz("shot_1").name)
        val back = store().load().associateBy { it.id }
        assertEquals(listOf(1f, 1f, 1f), back.getValue("shot:1").samples.map { it.pressure })
        assertEquals(listOf(2f, 2f, 2f, 2f), back.getValue("shot_1").samples.map { it.pressure })
    }

    @Test
    fun `a series a build before P3a wrote is read in place and never written or deleted`() = runBlocking {
        // As the previous releases left them: the F10 name, and the one before.
        val legacy = File(seriesDir, "shot_7.json")
        writeJsonSeries(legacy, 6)
        writeJsonSeries(json("s2"), 4)
        val before = legacy.readBytes() to json("s2").readBytes()
        store().save(listOf(shot("shot:7", 0), shot("s2", 0)))

        val s = store()
        val loaded = s.load()
        assertEquals(6, loaded.first { it.id == "shot:7" }.samples.size)
        assertEquals(4, loaded.first { it.id == "s2" }.samples.size)
        s.save(loaded)
        s.delete(listOf("shot:7", "s2"))
        assertTrue(legacy.exists() && json("s2").exists(), "the old copy's files stay, even for a deleted shot")
        assertTrue(before.first.contentEquals(legacy.readBytes()) && before.second.contentEquals(json("s2").readBytes()), "unchanged")
        assertFalse(gz("shot:7").exists(), "nothing was written over them")
    }

    @Test
    fun `a corrupt series file is kept aside and reported, not read as no curve`() = runBlocking {
        writeJsonSeries(json("s1"), 4)
        store().save(listOf(shot("s1", 0)))
        json("s1").writeText("{ not json")
        val s = store()
        assertTrue(s.load().single().samples.isEmpty())
        assertEquals(setOf("s1"), s.unreadableSeries)
        assertTrue(seriesDir.listFiles()!!.any { it.name.startsWith(json("s1").name + ".corrupt-") }, "kept aside")
        assertEquals(listOf("shot telemetry"), KeptAsideNotices.drain().map { it.what })
    }

    @Test
    fun `a corrupt gz series is kept aside too`() = runBlocking {
        store().save(listOf(shot("s1", 4)))
        gz("s1").writeBytes(byteArrayOf(1, 2, 3))
        val s = store()
        assertTrue(s.load().single().samples.isEmpty())
        assertEquals(setOf("s1"), s.unreadableSeries)
        assertTrue(seriesDir.listFiles()!!.any { it.name.startsWith(gz("s1").name + ".corrupt-") }, "kept aside, not deleted")
    }

    @Test
    fun `a save never deletes a series, whatever the list leaves out`() = runBlocking {
        val s = store()
        s.save(listOf(shot("a", 3), shot("b", 3)))
        s.save(listOf(shot("a", 3)))
        s.save(emptyList<coffee.crema.history.StoredShot>())
        assertTrue(gz("a").exists() && gz("b").exists())
        assertEquals(setOf("a", "b"), store().load().map { it.id }.toSet(), "and no row either")
    }

    @Test
    fun `delete takes the row and its gz series, by name`() = runBlocking {
        val s = store()
        s.save(listOf(shot("a", 3), shot("b", 3)))
        s.delete(listOf("b"))
        assertFalse(gz("b").exists())
        assertTrue(gz("a").exists())
        assertEquals(listOf("a"), store().load().map { it.id })
    }

    /** #325's scenario, at the migration: a damaged `shots.json` is kept aside
     *  and copies as nothing, and every series stays for a row that comes back. */
    @Test
    fun `corrupt rows at the migration keep every series, and a row back finds its curve`() = runBlocking {
        for (id in listOf("a", "b", "c")) writeJsonSeries(json(id), 3)
        historyFile.writeText("{ not json")
        val s = store()
        assertTrue(s.loadRows().isEmpty(), "the corrupt file copies as nothing")
        s.save(listOf(shot("new", 3)))
        for (id in listOf("a", "b", "c")) assertTrue(json(id).exists(), "$id's curve is still there")
        assertTrue(SafeFile.keptAside(historyFile).isNotEmpty(), "the damaged rows are kept aside")
        s.save(listOf(shot("a", 0)))
        assertEquals(3, store().load().first { it.id == "a" }.samples.size)
    }

    // ── #310 P4a: rows hold no series; a shot reads whole on demand ──────────

    @Test
    fun `a row's curve is read when its shot is, and the last few stay in memory`() = runBlocking {
        val shots = List(12) { i -> shot("s$i", 5, pressure = i.toFloat()) }
        store().save(shots)
        val s = store()
        val rows = s.loadRows()
        assertTrue(rows.all { it.summary.sampleCount == 5u }, "each row counts its curve without holding it")
        val reads = seriesReadsForTest.get()
        assertEquals(List(5) { 3f }, assertNotNull(s.loadShot("s3")).samples.map { it.pressure })
        assertEquals(reads + 1, seriesReadsForTest.get())
        s.loadShot("s3")
        assertEquals(reads + 1, seriesReadsForTest.get(), "the second read is from memory")
        for (i in 0 until HistoryStore.RECENT_SHOTS) s.loadShot("s${i + 4}")
        s.loadShot("s3")
        assertEquals(reads + 2 + HistoryStore.RECENT_SHOTS, seriesReadsForTest.get(), "only the last ${HistoryStore.RECENT_SHOTS} are kept")
        assertNull(s.loadShot("nope"), "no row, no shot")
    }

    @Test
    fun `a shot written whole without samples keeps the curve the store holds`() = runBlocking {
        val mine = shot("s1", 5)
        store().save(listOf(mine))
        // A restore's overwrite from a backup row with no curve (SPEC §4, the 2026-11-08 decision).
        val s = store()
        s.loadAll()
        assertTrue(s.save(listOf(mine.copy(samples = emptyList(), notes = "theirs"))))
        val back = assertNotNull(store().load().single())
        assertEquals("theirs", back.notes)
        assertEquals(mine.samples, back.samples, "the device's curve stays")
        assertTrue(gz("s1").exists())
    }

    @Test
    fun `a shot that can't be read just now throws, and is never read as having no curve`() = runBlocking {
        store().save(listOf(shot("s1", 5)))
        val s = store()
        s.loadAll()
        // A directory where the gz goes: the file is there, and can't be read now.
        gz("s1").delete()
        gz("s1").mkdirs()
        assertFailsWith<HistoryReadException> { s.loadShot("s1") }
        assertTrue(s.hasCurve("s1"), "still a curve: a read error says nothing about it")
        gz("s1").delete()
        Unit
    }
}
