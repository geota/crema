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
import kotlin.test.assertTrue

/**
 * F10 PR 3: the history keeps the FULL telemetry series (no 200-point cut),
 * which made the one history file ~14 MB at 300 shots — so each shot's
 * series lives in its own file and the history file holds the rows only.
 *
 * The measurement prints save / load time and sizes at 300 and 1000 shots,
 * for a typical 40 s shot (180 samples) and a long 60 s one (270) at the
 * DE1's ~4.5 Hz (1150 ShotSample notifications over 259 s in the session
 * fixture): first the old single file, then the split store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ShotHistorySizeTest {

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
        val edited = list.mapIndexed { i, s -> if (i == 0) s.copy(rating = 4) else s }
        val edit = measureNanoTime { store.save(edited) }
        // A new shot: one series file more.
        val added = listOf(shot(list.size, list.first().samples.size)) + edited
        val add = measureNanoTime { store.save(added) }
        val fresh = HistoryStore(app, coreJson)
        var back: List<StoredShot> = emptyList()
        val load = measureNanoTime { back = fresh.load() }
        assertEquals(added.size, back.size)
        val rows = File(app.filesDir, "shots.json").length()
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
    }

    @Test
    fun `the history file holds no series and load rejoins them`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = HistoryStore(app, coreJson)
        store.erase()
        val shots = List(3) { shot(it, 260) }
        store.save(shots)
        val rowsText = File(app.filesDir, "shots.json").readText()
        assertTrue("\"elapsedMs\"" !in rowsText, "the history file holds the rows only")
        val back = HistoryStore(app, coreJson).load()
        assertEquals(shots.map { it.samples.size }, back.map { it.samples.size }, "the full series rejoined")
        // Removing a shot drops its series file.
        store.save(shots.drop(1))
        assertEquals(2, File(app.filesDir, "shot-series").listFiles().orEmpty().size)
        // A row saved before the split, series inline, moves out on the next save.
        File(app.filesDir, "shots.json").writeText(
            coreJson.encodeToString(ListSerializer(StoredShot.serializer()), listOf(shot(9, 260))),
        )
        val legacy = HistoryStore(app, coreJson)
        val loaded = legacy.load()
        assertEquals(260, loaded.single().samples.size)
        legacy.save(loaded)
        assertTrue("\"elapsedMs\"" !in File(app.filesDir, "shots.json").readText(), "moved out")
        assertEquals(260, HistoryStore(app, coreJson).load().single().samples.size)
        store.erase()
    }
}
