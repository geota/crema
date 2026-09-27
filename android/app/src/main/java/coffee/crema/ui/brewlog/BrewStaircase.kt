package coffee.crema.ui.brewlog

import coffee.crema.brew.CoreJson
import coffee.crema.core.BrewRecipe
import coffee.crema.core.StageMark
import coffee.crema.core.StairSegment
import coffee.crema.core.maxPlannedTargetJson
import coffee.crema.core.plannedStaircaseJson
import coffee.crema.core.stageMarkForJson
import kotlinx.serialization.builtins.ListSerializer

/*
 * The "planned vs poured" overlay's geometry (issue #10, spec §5 "Brew
 * charts"). Each StageMark carries the step's cumulative planned water
 * target, snapshotted at the boundary (null for timed steps and older
 * records); drawn over the weight curve as a dashed staircase it shows the
 * recipe's plan beside what was actually poured. The segment rule is the
 * core's (`de1_domain::planned_staircase`, shared with the web); the canvas
 * only draws the segments. The FFI calls are injectable so JVM unit tests
 * (no native lib) can stand in for the core.
 */

private val MarksSerializer = ListSerializer(StageMark.serializer())
private val SegmentsSerializer = ListSerializer(StairSegment.serializer())

/**
 * The planned-water staircase for [marks], ending at [endMs] (the end of the
 * series / the live session clock) — the core's `planned_staircase`: each run
 * spans a mark to the next at the latest target so far; nothing before the
 * first target; empty when no mark carries one.
 */
fun plannedStaircase(
    marks: List<StageMark>,
    endMs: Long,
    core: (String, Long) -> String = ::plannedStaircaseJson,
): List<StairSegment> {
    if (marks.isEmpty()) return emptyList()
    return CoreJson.decodeFromString(SegmentsSerializer, core(CoreJson.encodeToString(MarksSerializer, marks), endMs))
}

/** The largest planned target among [marks], or 0 — core `max_planned_target`. */
fun maxPlannedTarget(marks: List<StageMark>, core: (String) -> Float = ::maxPlannedTargetJson): Float {
    if (marks.isEmpty()) return 0f
    return core(CoreJson.encodeToString(MarksSerializer, marks))
}

/**
 * A live stage mark carrying the step's planned water target — the core's
 * `stage_mark_for`, the same rule the session engine stamps on the saved
 * record, so the live chart's staircase matches the saved one. A null or
 * unreadable recipe only loses the target, never the mark.
 */
fun liveStageMark(
    recipe: BrewRecipe?,
    stepIndex: Int,
    atMs: Long,
    core: (String, UInt, Long) -> String = ::stageMarkForJson,
): StageMark {
    val bare = StageMark(elapsedMs = atMs, stepIndex = stepIndex.toLong())
    if (recipe == null || stepIndex < 0) return bare
    return runCatching {
        CoreJson.decodeFromString(
            StageMark.serializer(),
            core(CoreJson.encodeToString(BrewRecipe.serializer(), recipe), stepIndex.toUInt(), atMs),
        )
    }.getOrDefault(bare)
}
