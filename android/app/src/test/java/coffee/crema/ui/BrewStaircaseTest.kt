package coffee.crema.ui

import coffee.crema.brew.CoreJson
import coffee.crema.brew.stepLabel
import coffee.crema.core.BrewRecipe
import coffee.crema.core.BrewSample
import coffee.crema.core.BrewSeries
import coffee.crema.core.BrewStep
import coffee.crema.core.BrewStepKind
import coffee.crema.core.StageMark
import coffee.crema.core.StairSegment
import coffee.crema.core.StepAdvance
import coffee.crema.history.downsampleBrewSeries
import coffee.crema.ui.brewlog.liveStageMark
import coffee.crema.ui.brewlog.maxPlannedTarget
import coffee.crema.ui.brewlog.plannedStaircase
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guided-brew chart plumbing. The staircase / stage-mark rules are the
 * core's (`planned_staircase`, `stage_mark_for` — their cases are pinned by
 * de1-domain tests); these cover the shell side — what is handed to the core
 * and how its answer is read — with the native calls stood in by fakes.
 */
class BrewStaircaseTest {
    private fun mark(t: Long, i: Long, target: Float? = null) = StageMark(elapsedMs = t, stepIndex = i, targetWaterG = target)

    private val marks = listOf(mark(0, 0, 45f), mark(45_000, 1, 150f), mark(75_000, 2))

    @Test fun staircaseSendsTheMarksAndDecodesTheCoresSegments() {
        var sentMarks: List<StageMark>? = null
        var sentEnd = -1L
        val segs = plannedStaircase(marks, 90_000) { raw, end ->
            sentMarks = CoreJson.decodeFromString(ListSerializer(StageMark.serializer()), raw)
            sentEnd = end
            """[{"t0Ms":0,"t1Ms":45000,"targetG":45.0},{"t0Ms":45000,"t1Ms":90000,"targetG":150.0}]"""
        }
        assertEquals(marks, sentMarks)
        assertEquals(90_000L, sentEnd)
        assertEquals(listOf(StairSegment(0, 45_000, 45f), StairSegment(45_000, 90_000, 150f)), segs)
    }

    @Test fun noMarksNeverCallsTheCore() {
        assertTrue(plannedStaircase(emptyList(), 60_000) { _, _ -> error("no call") }.isEmpty())
        assertEquals(0f, maxPlannedTarget(emptyList()) { error("no call") })
        assertEquals(150f, maxPlannedTarget(marks) { 150f })
    }

    private val recipe = BrewRecipe(
        id = "r", name = "V60", method = "pourover", doseG = 15f, waterG = 250f, tempC = 96f,
        steps = listOf(BrewStep(kind = BrewStepKind.Bloom, targetWaterG = 45f, durationS = 45, advance = StepAdvance.Auto)),
        notes = null, favourite = false, createdAt = 1, updatedAt = 1,
    )

    @Test fun liveMarksComeFromTheCore() {
        var sent: Triple<String, UInt, Long>? = null
        val m = liveStageMark(recipe, 0, 1_500) { r, i, at ->
            sent = Triple(CoreJson.decodeFromString(BrewRecipe.serializer(), r).id, i, at)
            """{"elapsedMs":1500,"stepIndex":0,"targetWaterG":45.0}"""
        }
        assertEquals(Triple("r", 0u, 1_500L), sent)
        assertEquals(mark(1_500, 0, 45f), m)
    }

    @Test fun aLiveMarkWithoutARecipeOrWithACoreErrorKeepsTheMark() {
        assertEquals(mark(10, 2), liveStageMark(null, 2, 10) { _, _, _ -> error("no call") })
        assertEquals(mark(10, 1), liveStageMark(recipe, 1, 10) { _, _, _ -> throw IllegalStateException("bad") })
    }

    @Test fun aSavedSeriesIsThinnedWithTheCorePickerKeepingEveryMark() {
        val samples = List(4500) { BrewSample(elapsedMs = it * 50L, weightG = it / 18f) }
        val series = BrewSeries(samples = samples, stageMarks = marks)
        var asked: Pair<UInt, UInt>? = null
        val thin = downsampleBrewSeries(series) { len, cap ->
            asked = len to cap
            listOf(0u, 2250u, 4499u)
        }
        assertEquals(4500u to 200u, asked)
        assertEquals(listOf(samples[0], samples[2250], samples[4499]), thin.samples)
        assertEquals(marks, thin.stageMarks)
        val short = BrewSeries(samples = samples.take(10), stageMarks = marks)
        assertSame(short, downsampleBrewSeries(short) { _, _ -> error("no call") })
    }

    @Test fun aStepShowsItsOwnLabelElseItsKind() {
        // Drift bug 12: web shows BrewStep.label; Android now does too.
        val pour = BrewStep(kind = BrewStepKind.Pour, targetWaterG = 150f, advance = StepAdvance.Auto)
        assertEquals("Pour", stepLabel(pour))
        assertEquals("Second pour", stepLabel(pour.copy(label = " Second pour ")))
        assertEquals("Pour", stepLabel(pour.copy(label = "  ")))
        assertEquals("Step", stepLabel(BrewStep(kind = BrewStepKind.Other, advance = StepAdvance.Manual)))
        assertNull(pour.label)
    }
}
