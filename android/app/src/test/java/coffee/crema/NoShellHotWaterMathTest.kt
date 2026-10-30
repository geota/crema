package coffee.crema

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No shell computes a hot-water amount or holds the dose's bounds (hot-water
 * modes spec §2.4, AC-HW27): the core owns the time, volume and ratio ranges
 * (`hotWaterBounds`) and the resolved target. A scan of `web/src` and
 * `android/app/src/main`, so a range or a ratio product put back in either
 * shell fails here. The temperature bounds are out of scope until F8.
 */
class NoShellHotWaterMathTest {

    private val android = File("src/main")
    private val web = File("../../web/src")

    /** Generated bindings and the wasm build hold the core's own names. */
    private fun scanned(f: File): Boolean {
        val p = f.invariantSeparatorsPath
        return f.isFile && f.extension in setOf("kt", "ts", "svelte") &&
            f.name != "CremaCoreTypes.kt" && f.name != "crema-core.ts" && "/lib/wasm/" !in p &&
            "/testing/" !in p && !f.name.endsWith(".test.ts") && !f.name.endsWith(".vitest.ts")
    }

    /** The hot-water card's own files: any numeric stepper bound in them is a copy of the core's. */
    private fun cardFile(f: File) = Regex("(?i)hot-?water|WaterStepper").containsMatchIn(f.name)

    private fun hits(rule: (File, String) -> Boolean): List<String> {
        assertTrue("$android must exist (run from android/app)", android.isDirectory)
        assertTrue("$web must exist", web.isDirectory)
        return listOf(android, web).flatMap { root ->
            root.walkTopDown().filter(::scanned).flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line -> if (rule(f, line)) "${f.path}:${i + 1}: ${line.trim()}" else null }
            }.toList()
        }
    }

    private val hotWater = Regex("(?i)hot.?water")
    private val temperature = Regex("(?i)temp")

    /** A named time, volume or ratio bound: `WATER_VOL_MAX`, `hotWaterRatioMin`. */
    @Test
    fun `no hot-water bound constants`() {
        val bound = Regex("(?i)\\b\\w*water_?(vol|volume|time|ratio)\\w*?_?(min|max|step)\\w*\\b")
        val found = hits { _, line -> bound.containsMatchIn(line) && !temperature.containsMatchIn(line) }
        assertTrue("hot-water bounds belong to the core (hotWaterBounds):\n${found.joinToString("\n")}", found.isEmpty())
    }

    /** A literal range or clamp on a hot-water line, or a numeric stepper bound in the card. */
    @Test
    fun `no hot-water range literals`() {
        val clamp = Regex("coerce(In|AtMost|AtLeast)\\(\\s*\\d|Math\\.(min|max)\\(\\s*\\d|\\bclamp\\w*\\([^)]*\\d{2}|\\d+(\\.\\d+)?f?\\s*\\.\\.\\s*\\d")
        val stepper = Regex("\\b(min|max|step)\\s*=\\s*\\{?\\s*\\d|\\b(options|chips)\\s*=\\s*(\\{\\s*\\[|listOf\\(|\\[)\\s*\\d")
        val found = hits { f, line ->
            !temperature.containsMatchIn(line) &&
                ((hotWater.containsMatchIn(line) && clamp.containsMatchIn(line)) || (cardFile(f) && stepper.containsMatchIn(line)))
        }
        assertTrue("hot-water ranges belong to the core (hotWaterBounds):\n${found.joinToString("\n")}", found.isEmpty())
    }

    /** The ratio's amount (ratio × yield) is the core's `resolve_hot_water`. */
    @Test
    fun `no hot-water ratio math`() {
        val ratio = Regex("(?i)(hot_?water_?ratio|qcHotWaterRatio)")
        val product = Regex("(?i)(hot_?water_?ratio|qcHotWaterRatio|\\bratio\\b)[\\w.()?!]*\\s*\\*|\\*\\s*[\\w.()?!]*(hot_?water_?ratio|qcHotWaterRatio|\\bratio\\b)")
        val found = hits { f, line -> (ratio.containsMatchIn(line) || cardFile(f)) && product.containsMatchIn(line) }
        assertTrue("the hot-water amount is the core's (resolve_hot_water):\n${found.joinToString("\n")}", found.isEmpty())
    }
}
