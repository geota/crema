package coffee.crema.history

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.persist.SafeFile
import coffee.crema.ui.TelemetrySample
import coffee.crema.ui.coreJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.system.measureNanoTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F10 PR 3: the history keeps the FULL telemetry series (no 200-point cut),
 * which made the one history file ~14 MB at 300 shots — so each shot's
 * series lives in its own file and the history file holds the rows only.
 *
 * The measurement prints save / load time and sizes at 300 and 1000 shots,
 * for a typical 40 s shot (180 samples) and a long 60 s one (270) at the
 * DE1's ~4.5 Hz (1150 ShotSample notifications over 259 s in the session
 * fixture): first the old single file, then the split store; and the split
 * store at the design size, 5,000 rows (#310 SPEC decision 1).
 *
 * From #310 P4a the store's load reads rows only: no series, and no row holds
 * one. The design-size test checks that, and that each shot reads whole on
 * demand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ShotHistorySizeTest {
    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()


    private fun shot(i: Int, samples: Int) = StoredShot(
        id = "shot:$i",
        completedAtMs = 1_700_000_000_000L + i,
        durationMs = samples * 222L,
        yieldG = 36.4f,
        doseG = 18f,
        profileName = "Best Practice",
        samples = List(samples) { n ->
            TelemetrySample(
                elapsedMs = n * 222L,
                pressure = 8.83f + n % 7 * 0.013f,
                flow = 1.97f + n % 5 * 0.011f,
                headTemp = 92.94f,
                mixTemp = 91.71f,
                weight = n * 0.183f,
                weightFlow = 1.83f,
                dispensedVolume = n * 0.41f,
                resistance = 2.27f,
                resistanceWeight = 2.63f,
                setHeadTemp = 93f,
                setGroupPressure = 9f,
                setGroupFlow = 0f,
                frameNumber = n / 40,
            )
        },
    )

    private fun ms(ns: Long) = ns / 1_000_000

    /** The old layout: every series inline in the one history file. */
    private fun singleFile(list: List<StoredShot>): String {
        val dir = Files.createTempDirectory("shot-size").toFile()
        val file = File(dir, "shots.json")
        val save = measureNanoTime {
            SafeFile.write(file, coreJson.encodeToString(ListSerializer(StoredShot.serializer()), list), true)
        }
        val load = measureNanoTime { coreJson.decodeFromString(ListSerializer(StoredShot.serializer()), file.readText()) }
        val size = file.length()
        dir.deleteRecursively()
        return "single file: size=${"%.1f".format(size / 1e6)}MB save=${ms(save)}ms load=${ms(load)}ms"
    }

    /** The split store: rows in the history file, a series file per shot. */
    private fun split(list: List<StoredShot>): String = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = HistoryStore(app, coreJson)
        store.erase()
        val first = measureNanoTime { store.save(list) }
        // A rating edit: the rows change, no series does.
        // The app saves rows (#310 P4a): the store writes only the one that changed.
        val edited = list.rows().mapIndexed { i, s -> if (i == 0) s.copy(rating = 4) else s }
        val edit = measureNanoTime { store.save(edited) }
        // A new shot: one series file more, staged whole, then saved with the rows.
        val newShot = shot(list.size, list.first().samples.size)
        val edit2 = edited
        val add = measureNanoTime { store.save(listOf(store.stage(newShot)) + edit2) }
        val fresh = HistoryStore(app, coreJson)
        var loaded = 0
        val load = measureNanoTime { val l = fresh.loadAll(); loaded = l.view.size + l.hiddenCount }
        assertEquals(list.size + 1, loaded)
        val rows = app.getDatabasePath(HistoryStore.NEXT_DB_NAME).let { db -> db.length() + File(db.path + "-wal").length() }
        val series = File(app.filesDir, "shot-series").listFiles().orEmpty().sumOf { it.length() }
        store.erase()
        "split: rows=${"%.2f".format(rows / 1e6)}MB series=${"%.1f".format(series / 1e6)}MB " +
            "first save=${ms(first)}ms edit save=${ms(edit)}ms new-shot save=${ms(add)}ms load=${ms(load)}ms"
    }

    @Test
    fun `history file size and timing with the full series`() {
        singleFile(List(50) { shot(it, 180) }) // JIT warm-up
        split(List(50) { shot(it, 180) })
        for (shots in listOf(300, 1000)) {
            for (samples in listOf(180, 270)) {
                val list = List(shots) { shot(it, samples) }
                println("F10-MEASURE shots=$shots samples=$samples ${singleFile(list)} | ${split(list)}")
            }
        }
        // The design size: the split store only (the old single file would be ~70 MB).
        println("P4A-MEASURE shots=5000 samples=180 ${split(List(5_000) { shot(it, 180) })}")
    }

    @Test
    fun `at the design size the load reads no series, and each shot reads whole on demand`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = HistoryStore(app, coreJson)
        store.erase()
        val shots = List(5_000) { shot(it, 40) }
        assertTrue(store.save(shots))
        val reads = seriesReadsForTest.get()
        val fresh = HistoryStore(app, coreJson)
        var load: HistoryLoad? = null
        val ms = ms(measureNanoTime { load = fresh.loadAll() })
        println("P4A-MEASURE design-size rows load=${ms}ms")
        val l = assertNotNull(load)
        assertEquals(reads, seriesReadsForTest.get(), "the load read no series")
        assertEquals(HistoryStore.MAX_SHOTS, l.view.size, "the view keeps its cap until P6")
        assertEquals(5_000 - HistoryStore.MAX_SHOTS, l.hiddenCount, "and every other row stays in the store")
        assertTrue(l.view.all { it.summary.sampleCount == 40u && it.summary.spark != null }, "each row draws from its summary")
        // A shot far past the cap reads whole, with its own curve.
        val far = shots[4_321]
        assertEquals(far.samples, assertNotNull(fresh.loadShot(far.id)).samples)
        assertEquals(reads + 1, seriesReadsForTest.get(), "one shot, one read")
        // A star tap on one row writes that row only; every row and curve stays.
        val tapped = l.view.mapIndexed { i, r -> if (i == 7) r.copy(rating = 5) else r }
        val tap = ms(measureNanoTime { assertTrue(fresh.save(tapped)) })
        println("P4A-MEASURE design-size star-tap save=${tap}ms")
        val again = HistoryStore(app, coreJson)
        val l2 = again.loadAll()
        assertEquals(5_000, l2.view.size + l2.hiddenCount)
        assertEquals(5, l2.view[7].rating)
        assertEquals(shots[0].samples, assertNotNull(again.loadShot(shots[0].id)).samples)
        store.erase()
    }

    @Test
    fun `the store holds no series and load rejoins them`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = HistoryStore(app, coreJson)
        store.erase()
        val shots = List(3) { shot(it, 260) }
        store.save(shots)
        val db = app.getDatabasePath(HistoryStore.NEXT_DB_NAME)
        val rowsText = (db.readBytes() + (File(db.path + "-wal").takeIf { it.exists() }?.readBytes() ?: ByteArray(0))).toString(Charsets.ISO_8859_1)
        assertTrue("\"elapsedMs\"" !in rowsText, "the store holds the rows only")
        val back = HistoryStore(app, coreJson).load()
        assertEquals(shots.map { it.samples.size }.sorted(), back.map { it.samples.size }.sorted(), "the full series rejoined")
        // Startup reads the rows first (no series), then the series apart.
        val reader = HistoryStore(app, coreJson)
        val rows = reader.loadRows()
        // A row holds no series (#310 P4a: the type has no field); its summary counts it.
        assertTrue(rows.all { it.summary.sampleCount == 260u }, "rows come with their summaries")
        assertEquals(List(3) { 260 }, rows.map { assertNotNull(reader.loadShot(it.id)).samples.size })
        // Deleting a shot, by name, drops its series file.
        store.delete(listOf(shots[0].id))
        assertEquals(2, File(app.filesDir, "shot-series").listFiles().orEmpty().size)
        store.erase()
        // A row saved before the split, series inline in shots.json, moves out at the migration.
        File(app.filesDir, "shots.json").writeText(
            coreJson.encodeToString(ListSerializer(StoredShot.serializer()), listOf(shot(9, 260))),
        )
        val legacy = HistoryStore(app, coreJson)
        assertEquals(260, legacy.load().single().samples.size)
        assertTrue(File(app.filesDir, "shot-series").listFiles().orEmpty().any { it.name.endsWith(".series.gz") }, "moved out")
        assertEquals(260, HistoryStore(app, coreJson).load().single().samples.size)
        store.erase()
    }
}
