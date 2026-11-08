package coffee.crema.bench

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coffee.crema.core.ShotSummary
import coffee.crema.core.shotSummary
import coffee.crema.history.StoredShot
import coffee.crema.history.brewHistoryStats
import coffee.crema.history.shotMatchesQuery
import coffee.crema.ui.TelemetrySample
import coffee.crema.visualizer.wireShotJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M, the measurement before #310 P3 (SPEC § Phases, "M"). Not a test of the
 * app: a bench, on the scratch branch only, run on the owner's reference
 * device. See `.scratch/history-storage/M-RUNBOOK.md`.
 *
 * For 5,000 and 20,000 rows of the P3a light row (a `StoredShot` without its
 * series, plus the core's `ShotSummary`), it reports the heap the list
 * holds, against `ActivityManager.memoryClass` (the rule: rows under a
 * quarter of it), the JSON decode time of the rows, and the text filter and
 * stats times over them. Results go to logcat (tag `CremaBenchM`), to the
 * instrumentation status, and to `files/bench-m.txt` in the app's external
 * files directory.
 */
@RunWith(AndroidJUnit4::class)
class HistoryRowBench {
    /** The P3a row, as the SPEC has it: everything but the heavy fields, plus the summary. */
    @Serializable
    data class BenchShotRow(val shot: StoredShot, val summary: ShotSummary)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val out = StringBuilder()

    private fun report(line: String) {
        Log.i("CremaBenchM", line)
        out.appendLine(line)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$line\n") })
    }

    /** A 40 s shot's 180 samples, from a smooth curve, with a scale. */
    private val curve: List<TelemetrySample> = List(180) { i ->
        val t = i / 180f
        TelemetrySample(
            elapsedMs = i * 227L, pressure = 9f * kotlin.math.sin(t * 3.1f), flow = 2f + t, headTemp = 92f, mixTemp = 93f,
            weight = 40f * t, weightFlow = 1.8f, dispensedVolume = 45f * t, resistance = 2f, resistanceWeight = 2.2f,
            setHeadTemp = 93f, setGroupPressure = 9f, setGroupFlow = 0f, frameNumber = 1 + i / 30,
        )
    }

    private fun shot(i: Int) = StoredShot(
        id = "shot:019e5fb5-${i.toString().padStart(6, '0')}-7000-8000-000000000001",
        completedAtMs = 1_767_323_075_678 - i * 21_600_000L,
        durationMs = 40_000,
        samples = curve,
        profileName = "Best Practice",
        notes = "Slightly sour at the end, go finer next time. #$i",
        rating = 1 + i % 5,
        doseG = 18f,
        yieldG = 36.4f,
        brewMethod = null,
    )

    /** Summaries are computed for a few shots and copied per row, so each row
     *  holds its own strings as it would after a load. */
    private fun rows(n: Int): List<BenchShotRow> {
        val summaries = List(16) { k ->
            json.decodeFromString(ShotSummary.serializer(), shotSummary(wireShotJson(shot(k)).toString()))
        }
        return List(n) { i ->
            val s = summaries[i % summaries.size]
            BenchShotRow(
                shot(i).copy(samples = emptyList(), notes = String(("Slightly sour at the end. #$i").toCharArray())),
                s.copy(spark = s.spark?.let { String(it.toCharArray()) }),
            )
        }
    }

    private fun usedHeap(): Long {
        repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(100) }
        val r = Runtime.getRuntime()
        return r.totalMemory() - r.freeMemory()
    }

    private fun <T> timed(block: () -> T): Pair<T, Double> {
        val t0 = System.nanoTime()
        val v = block()
        return v to (System.nanoTime() - t0) / 1e6
    }

    private fun median(runs: Int = 5, block: () -> Unit): Double =
        List(runs) { timed(block).second }.sorted()[runs / 2]

    @Test
    fun rows5000and20000() {
        val am = context.getSystemService(ActivityManager::class.java)
        report("device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        report("memoryClass ${am.memoryClass} MB, largeMemoryClass ${am.largeMemoryClass} MB, maxMemory ${Runtime.getRuntime().maxMemory() / 1_048_576} MB")
        for (n in listOf(5_000, 20_000)) {
            val before = usedHeap()
            var list: List<BenchShotRow>? = rows(n)
            val held = usedHeap() - before
            val perRow = held / n
            val quarter = am.memoryClass * 1_048_576L / 4
            report("n=$n: rows hold ${held / 1_048_576} MB (${perRow} B/row), a quarter of memoryClass is ${quarter / 1_048_576} MB: ${if (held < quarter) "UNDER" else "OVER"}")
            val text = json.encodeToString(ListSerializer(BenchShotRow.serializer()), list!!)
            report("n=$n: rows as JSON ${text.length / 1_048_576} MB")
            val decode = median(3) { json.decodeFromString(ListSerializer(BenchShotRow.serializer()), text) }
            report("n=$n: JSON decode ${"%.0f".format(decode)} ms (median of 3)")
            val shots = list.map { it.shot }
            val miss = median { shots.count { shotMatchesQuery(it, "zzzz") } }
            val hit = median { shots.count { shotMatchesQuery(it, "sour") } }
            report("n=$n: text filter ${"%.1f".format(miss)} ms (no match), ${"%.1f".format(hit)} ms (all match)")
            val stats = median { brewHistoryStats(shots) }
            report("n=$n: stats ${"%.1f".format(stats)} ms (projection + core brew_history_stats)")
            report("n=$n: native heap ${Debug.getNativeHeapAllocatedSize() / 1_048_576} MB allocated")
            list = null
        }
        val file = File(context.getExternalFilesDir(null), "bench-m.txt")
        file.writeText(out.toString())
        report("written to ${file.absolutePath}")
    }
}
