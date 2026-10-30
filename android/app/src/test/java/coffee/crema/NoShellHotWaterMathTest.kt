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

    /** A scanned line: its file, its text, and the Svelte element it sits in
     *  (from the `<Tag` line to its `/>`), or the line itself in other files. */
    private class Line(val file: File, val text: String, val element: String)

    private fun hits(rule: (Line) -> Boolean): List<String> {
        assertTrue("$android must exist (run from android/app)", android.isDirectory)
        assertTrue("$web must exist", web.isDirectory)
        return listOf(android, web).flatMap { root ->
            root.walkTopDown().filter(::scanned).flatMap { f ->
                val lines = f.readLines()
                lines.indices.mapNotNull { i ->
                    val element = if (f.extension != "svelte") lines[i] else {
                        val from = (i downTo 0).firstOrNull { "<" in lines[it] } ?: i
                        val to = (i until lines.size).firstOrNull { "/>" in lines[it] } ?: i
                        lines.subList(from, to + 1).joinToString("\n")
                    }
                    if (rule(Line(f, lines[i], element))) "${f.path}:${i + 1}: ${lines[i].trim()}" else null
                }
            }.toList()
        }
    }

    private val hotWater = Regex("(?i)hot.?water")
    private val temperature = Regex("(?i)temp|°")

    /** A named time, volume or ratio bound: `WATER_VOL_MAX`, `hotWaterRatioMin`. */
    @Test
    fun `no hot-water bound constants`() {
        val bound = Regex("(?i)\\b\\w*water_?(vol|volume|time|ratio)\\w*?_?(min|max|step)\\w*\\b")
        val found = hits { bound.containsMatchIn(it.text) && !temperature.containsMatchIn(it.text) }
        assertTrue("hot-water bounds belong to the core (hotWaterBounds):\n${found.joinToString("\n")}", found.isEmpty())
    }

    /** A literal range or clamp on a hot-water line, or a numeric stepper bound in the card. */
    @Test
    fun `no hot-water range literals`() {
        val clamp = Regex("coerce(In|AtMost|AtLeast)\\(\\s*\\d|Math\\.(min|max)\\(\\s*\\d|\\bclamp\\w*\\([^)]*\\d{2}|\\d+(\\.\\d+)?f?\\s*\\.\\.\\s*\\d")
        // A numeric bound or preset list; layout sizes (`min = 220.dp`) aren't.
        val stepper = Regex("\\b(min|max|step)\\s*=\\s*\\{?\\s*\\d++(?!(\\.\\d+)?\\.(dp|sp)\\b)|\\b(options|chips)\\s*=\\s*(\\{\\s*\\[|listOf\\(|\\[)\\s*\\d")
        val found = hits {
            (hotWater.containsMatchIn(it.text) && clamp.containsMatchIn(it.text) && !temperature.containsMatchIn(it.text)) ||
                (cardFile(it.file) && stepper.containsMatchIn(it.text) && !temperature.containsMatchIn(it.element))
        }
        assertTrue("hot-water ranges belong to the core (hotWaterBounds):\n${found.joinToString("\n")}", found.isEmpty())
    }

    /** The ratio's amount (ratio × yield) is the core's `resolve_hot_water`. */
    @Test
    fun `no hot-water ratio math`() {
        val ratio = Regex("(?i)(hot_?water_?ratio|qcHotWaterRatio)")
        val product = Regex("(?i)(hot_?water_?ratio|qcHotWaterRatio|\\bratio\\b)[\\w.()?!]*\\s*\\*|\\*\\s*[\\w.()?!]*(hot_?water_?ratio|qcHotWaterRatio|\\bratio\\b)")
        val found = hits { (ratio.containsMatchIn(it.text) || cardFile(it.file)) && product.containsMatchIn(it.text) }
        assertTrue("the hot-water amount is the core's (resolve_hot_water):\n${found.joinToString("\n")}", found.isEmpty())
    }
}
