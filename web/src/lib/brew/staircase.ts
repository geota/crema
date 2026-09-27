/**
 * `$lib/brew/staircase` — the "planned vs poured" overlay's geometry
 * (issue #10, spec §5 "Brew charts").
 *
 * Each {@link StageMark} carries the step's cumulative planned water
 * target, snapshotted at the boundary (`None` for timed steps and older
 * records). Drawn over the weight curve as a dashed staircase, it shows
 * the recipe's plan beside what was actually poured.
 *
 * The segment geometry is the core's (`planned_staircase`); this module
 * only turns it into SVG path data, so the chart stays a thin renderer.
 */

import type { StageMark, StairSegment } from '$lib/core/crema-core';
import { maxPlannedTargetJson, plannedStaircaseJson } from '$lib/wasm/de1_wasm';

export type { StairSegment };

/**
 * Build the planned-water staircase — the core's `planned_staircase`
 * (one rule for both shells): each segment runs from a mark to the next
 * (or `endMs`) at the latest target so far; nothing before the first
 * target; empty when no mark carries one. Marks need not arrive sorted.
 */
export function plannedStaircase(marks: readonly StageMark[], endMs: number): StairSegment[] {
	if (marks.length === 0) return [];
	return JSON.parse(plannedStaircaseJson(JSON.stringify(marks), endMs)) as StairSegment[];
}

/** The largest planned target among `marks`, or 0 — core `max_planned_target`. */
export function maxPlannedTarget(marks: readonly StageMark[]): number {
	if (marks.length === 0) return 0;
	return maxPlannedTargetJson(JSON.stringify(marks));
}

/**
 * The staircase as SVG path data: horizontal runs joined by vertical
 * risers. `x` / `y` map time (ms) and grams to plot coordinates.
 */
export function staircasePath(
	segs: readonly StairSegment[],
	x: (tMs: number) => number,
	y: (g: number) => number
): string {
	const parts: string[] = [];
	segs.forEach((s, i) => {
		const x0 = x(s.t0Ms).toFixed(1);
		const x1 = x(s.t1Ms).toFixed(1);
		const yy = y(s.targetG).toFixed(1);
		// Continue from the previous run's end with a riser; else move.
		const joined = i > 0 && segs[i - 1].t1Ms === s.t0Ms;
		parts.push(`${joined ? 'L' : 'M'} ${x0} ${yy}`, `L ${x1} ${yy}`);
	});
	return parts.join(' ');
}
