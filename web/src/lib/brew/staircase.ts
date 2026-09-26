/**
 * `$lib/brew/staircase` — the "planned vs poured" overlay's geometry
 * (issue #10, spec §5 "Brew charts").
 *
 * Each {@link StageMark} carries the step's cumulative planned water
 * target, snapshotted at the boundary (`None` for timed steps and older
 * records). Drawn over the weight curve as a dashed staircase, it shows
 * the recipe's plan beside what was actually poured.
 *
 * Pure and framework-free so the chart stays a thin renderer.
 */

import type { StageMark } from '$lib/core/crema-core';

/** One horizontal run of the staircase: level `targetG` from `t0Ms` to `t1Ms`. */
export interface StairSegment {
	t0Ms: number;
	t1Ms: number;
	targetG: number;
}

/**
 * Build the planned-water staircase.
 *
 * - Each segment starts at a mark and runs to the next mark, or to
 *   `endMs` (the end of the series / the live session clock).
 * - Its level is the most recent non-null target at or before that mark,
 *   so a timed step (a wait, the drawdown) carries the last pour's target
 *   forward.
 * - Nothing is emitted before the first mark with a target, and no
 *   targets at all means an empty staircase (manual logs, old records).
 *
 * Marks need not arrive sorted. Consecutive segments share endpoints, so
 * a renderer joining them with vertical risers draws a staircase.
 */
export function plannedStaircase(marks: readonly StageMark[], endMs: number): StairSegment[] {
	const sorted = [...marks].sort((a, b) => a.elapsedMs - b.elapsedMs);
	const out: StairSegment[] = [];
	let level: number | null = null;
	for (let i = 0; i < sorted.length; i++) {
		const m = sorted[i];
		const t = m.targetWaterG;
		if (t != null && Number.isFinite(t)) level = t;
		if (level == null) continue;
		const t0Ms = m.elapsedMs;
		const t1Ms = i + 1 < sorted.length ? sorted[i + 1].elapsedMs : Math.max(t0Ms, endMs);
		if (t1Ms <= t0Ms && i + 1 < sorted.length) continue; // zero-width (same-instant marks)
		out.push({ t0Ms, t1Ms, targetG: level });
	}
	return out;
}

/** The largest planned target among `marks`, or 0 when none carries one. */
export function maxPlannedTarget(marks: readonly StageMark[]): number {
	let m = 0;
	for (const mk of marks) {
		const t = mk.targetWaterG;
		if (t != null && Number.isFinite(t) && t > m) m = t;
	}
	return m;
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
