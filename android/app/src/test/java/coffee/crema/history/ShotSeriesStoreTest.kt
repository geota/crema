package coffee.crema.history

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.persist.KeptAsideNotices
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * F10 validation: the per-shot series store never loses a shot's telemetry.
 * A failed series write keeps the samples inline (P1-1); two ids never share
 * a file, and a file under the old name is read and moved over; a corrupt file
 * is kept aside and reported, not read as "no curve" (P3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ShotSeriesStoreTest {
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
        runBlocking { store().erase() }
        seriesDir.deleteRecursively()
        KeptAsideNotices.drain()
    }

    @After fun tidy() {
        seriesDir.deleteRecursively()
        File(app.filesDir, "shot-series").takeIf { it.isFile }?.delete()
    }

    /** The rows as written to the history file, with their inline samples. */
    private fun rowsOnDisk() = coreJson.decodeFromString(ListSerializer(StoredShot.serializer()), historyFile.readText())

    @Test
    fun `a failed series write keeps the samples inline until a later save writes them`() = runBlocking {
        // A plain file where the series directory goes: every series write fails.
        seriesDir.deleteRecursively()
        seriesDir.writeText("not a directory")
        store().save(listOf(shot("s1", 5)))
        assertEquals(5, rowsOnDisk().single().samples.size, "kept inline")
        assertEquals(5, store().load().single().samples.size, "loads back whole")

        // The directory is back: the next save moves the series out.
        seriesDir.delete()
        val s = store()
        val loaded = s.load()
        s.save(loaded)
        assertTrue(rowsOnDisk().single().samples.isEmpty(), "the row goes without its samples once the file is written")
        assertEquals(5, store().load().single().samples.size)
    }

    @Test
    fun `two ids never share a series file`() = runBlocking {
        assertNotEquals(HistoryStore.seriesFileName("shot:1"), HistoryStore.seriesFileName("shot_1"))
        assertEquals("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b.json", HistoryStore.seriesFileName("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"))
        store().save(listOf(shot("shot:1", 3, pressure = 1f), shot("shot_1", 4, pressure = 2f)))
        val back = store().load().associateBy { it.id }
        assertEquals(listOf(1f, 1f, 1f), back.getValue("shot:1").samples.map { it.pressure })
        assertEquals(listOf(2f, 2f, 2f, 2f), back.getValue("shot_1").samples.map { it.pressure })
    }

    @Test
    fun `a series under the old name is read, kept until rewritten, then moved over`() = runBlocking {
        // As the previous release left it: the row without samples, the series
        // under the `_`-replaced name.
        val legacy = File(seriesDir, "shot_7.json")
        seriesDir.mkdirs()
        legacy.writeText(coreJson.encodeToString(ListSerializer(TelemetrySample.serializer()), shot("x", 6).samples))
        store().save(listOf(shot("shot:7", 0)))
        assertTrue(legacy.exists(), "a save before the series load keeps the old file")

        val s = store()
        val loaded = s.load()
        assertEquals(6, loaded.single().samples.size)
        s.save(loaded)
        assertFalse(legacy.exists(), "the old file goes once the new one is written")
        assertTrue(File(seriesDir, HistoryStore.seriesFileName("shot:7")).exists())
        assertEquals(6, store().load().single().samples.size)
    }

    @Test
    fun `a corrupt series file is kept aside and reported, not read as no curve`() = runBlocking {
        store().save(listOf(shot("s1", 4)))
        val f = File(seriesDir, HistoryStore.seriesFileName("s1"))
        f.writeText("{ not json")
        val s = store()
        assertTrue(s.load().single().samples.isEmpty())
        assertEquals(setOf("s1"), s.unreadableSeries)
        assertTrue(seriesDir.listFiles()!!.any { it.name.startsWith(f.name + ".corrupt-") }, "kept aside")
        assertEquals(listOf("shot telemetry"), KeptAsideNotices.drain().map { it.what })
    }
}
