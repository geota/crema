package coffee.crema.ui.brewlog

import coffee.crema.core.BrewRecipe
import coffee.crema.core.StageMark

/*
 * The "planned vs poured" overlay's geometry (issue #10, spec §5 "Brew
 * charts"). Each StageMark carries the step's cumulative planned water
 * target, snapshotted at the boundary (null for timed steps and older
 * records); drawn over the weight curve as a dashed staircase it shows the
 * recipe's plan beside what was actually poured. Pure, so the canvas stays
 * a thin renderer. Twin of the web `$lib/brew/staircase`.
 */

/** One horizontal run of the staircase: level [targetG] from [t0Ms] to [t1Ms]. */
data class StairSegment(val t0Ms: Long, val t1Ms: Long, val targetG: Float)

/**
 * Build the planned-water staircase.
 *
 * - Each segment starts at a mark and runs to the next mark, or to [endMs]
 *   (the end of the series / the live session clock).
 * - Its level is the most recent non-null target at or before that mark, so
 *   a timed step (a wait, the drawdown) carries the last pour's target on.
 * - Nothing before the first mark with a target; no targets at all means an
 *   empty staircase (manual logs, old records).
 *
 * Marks need not arrive sorted. Consecutive segments share endpoints, so a
 * renderer joining them with vertical risers draws a staircase.
 */
fun plannedStaircase(marks: List<StageMark>, endMs: Long): List<StairSegment> {
    val sorted = marks.sortedBy { it.elapsedMs }
    val out = ArrayList<StairSegment>(sorted.size)
    var level: Float? = null
    sorted.forEachIndexed { i, m ->
        m.targetWaterG?.takeIf { it.isFinite() }?.let { level = it }
        val lv = level ?: return@forEachIndexed
        val hasNext = i + 1 < sorted.size
        val t1 = if (hasNext) sorted[i + 1].elapsedMs else maxOf(m.elapsedMs, endMs)
        if (hasNext && t1 <= m.elapsedMs) return@forEachIndexed // same-instant marks
        out += StairSegment(m.elapsedMs, t1, lv)
    }
    return out
}

/** The largest planned target among [marks], or 0 when none carries one. */
fun maxPlannedTarget(marks: List<StageMark>): Float =
    marks.mapNotNull { it.targetWaterG?.takeIf { t -> t.isFinite() } }.maxOrNull() ?: 0f

/**
 * A live stage mark carrying the step's planned water target — the same
 * snapshot the core stamps on the saved record, so the live chart's
 * staircase matches the saved one. A step-less recipe runs as one implicit
 * pour to its water target (as in the core).
 */
fun liveStageMark(recipe: BrewRecipe?, stepIndex: Int, atMs: Long): StageMark {
    val steps = recipe?.steps.orEmpty()
    val target = when {
        recipe == null -> null
        steps.isEmpty() -> recipe.waterG.takeIf { stepIndex == 0 && it > 0f }
        else -> steps.getOrNull(stepIndex)?.targetWaterG
    }
    return StageMark(elapsedMs = atMs, stepIndex = stepIndex.toLong(), targetWaterG = target)
}
