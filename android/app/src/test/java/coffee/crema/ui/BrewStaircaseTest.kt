package coffee.crema.ui

import coffee.crema.core.BrewRecipe
import coffee.crema.core.BrewStep
import coffee.crema.core.BrewStepKind
import coffee.crema.core.StageMark
import coffee.crema.core.StepAdvance
import coffee.crema.ui.brewlog.StairSegment
import coffee.crema.ui.brewlog.liveStageMark
import coffee.crema.ui.brewlog.maxPlannedTarget
import coffee.crema.ui.brewlog.plannedStaircase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrewStaircaseTest {
    private fun mark(t: Long, i: Long, target: Float? = null) = StageMark(elapsedMs = t, stepIndex = i, targetWaterG = target)

    /** V60: bloom 45 → pour 150 → wait → pour 250 → drawdown. */
    private val v60 = listOf(
        mark(0, 0, 45f),
        mark(45_000, 1, 150f),
        mark(75_000, 2),
        mark(100_000, 3, 250f),
        mark(130_000, 4),
    )

    @Test
    fun v60StaircaseCarriesTargetsThroughTimedSteps() {
        assertEquals(
            listOf(
                StairSegment(0, 45_000, 45f),
                StairSegment(45_000, 75_000, 150f),
                StairSegment(75_000, 100_000, 150f),
                StairSegment(100_000, 130_000, 250f),
                StairSegment(130_000, 180_000, 250f),
            ),
            plannedStaircase(v60, 180_000),
        )
        assertEquals(250f, maxPlannedTarget(v60))
    }

    @Test
    fun aSkipFollowsTheSkippedToTargetInAnyOrder() {
        val marks = listOf(mark(20_000, 1, 250f), mark(0, 0, 45f), mark(60_000, 3))
        assertEquals(
            listOf(
                StairSegment(0, 20_000, 45f),
                StairSegment(20_000, 60_000, 250f),
                StairSegment(60_000, 90_000, 250f),
            ),
            plannedStaircase(marks, 90_000),
        )
    }

    @Test
    fun noTargetsMeansNoStaircase() {
        val old = listOf(mark(0, 0), mark(30_000, 1))
        assertTrue(plannedStaircase(old, 60_000).isEmpty())
        assertTrue(plannedStaircase(emptyList(), 60_000).isEmpty())
        assertEquals(0f, maxPlannedTarget(old))
    }

    @Test
    fun startsAtTheFirstTarget() {
        val marks = listOf(mark(0, 0), mark(10_000, 1, 60f))
        assertEquals(listOf(StairSegment(10_000, 30_000, 60f)), plannedStaircase(marks, 30_000))
    }

    @Test
    fun aLiveSeriesEndsAtTheClockMidStep() {
        assertEquals(
            listOf(StairSegment(0, 45_000, 45f), StairSegment(45_000, 52_000, 150f)),
            plannedStaircase(v60.take(2), 52_000),
        )
    }

    @Test
    fun liveMarksSnapshotTheRecipeTargets() {
        val recipe = BrewRecipe(
            id = "r", name = "V60", method = "pourover", doseG = 15f, waterG = 250f, tempC = 96f,
            steps = listOf(
                BrewStep(kind = BrewStepKind.Bloom, targetWaterG = 45f, durationS = 45, advance = StepAdvance.Auto),
                BrewStep(kind = BrewStepKind.Wait, durationS = 30, advance = StepAdvance.Auto),
            ),
            notes = null, favourite = false, createdAt = 1, updatedAt = 1,
        )
        assertEquals(45f, liveStageMark(recipe, 0, 0).targetWaterG)
        assertNull(liveStageMark(recipe, 1, 45_000).targetWaterG)
        assertEquals(250f, liveStageMark(recipe.copy(steps = emptyList()), 0, 0).targetWaterG)
        assertNull(liveStageMark(null, 0, 0).targetWaterG)
    }
}
