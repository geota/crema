/**
 * `$lib/profiles/curve` — the pressure-curve geometry for the editor SVG.
 *
 * Ported from `curvePath` / `boundaryDots` in `profile-edit-page.jsx`, plus
 * the **inverse** the design only mocked: {@link svgToSegment} maps a dragged
 * boundary-dot position back to a segment's `time` / `target`, so dragging a
 * dot genuinely edits the segment model.
 *
 * All functions work in the SVG's user-space coordinates: the curve is drawn
 * in a `width × height` viewBox with `padX` / `padY` insets, the X axis is
 * cumulative shot time and the Y axis is pressure / flow over a fixed 0–12
 * scale (12 bar / 12 mL/s — matches the design's grid).
 */

import type { ProfileSegment } from './model';

/** The fixed top of the Y scale — 12 bar / 12 mL/s, the design's grid max. */
export const Y_MAX = 12;

/** SVG inset on the X axis, px. */
export const PAD_X = 18;

/** SVG inset on the Y axis, px. */
export const PAD_Y = 14;

/** Drawing geometry shared by every curve function. */
export interface CurveGeometry {
	/** Viewbox width, px. */
	width: number;
	/** Viewbox height, px. */
	height: number;
	/** Total shot time across the segments, seconds. */
	total: number;
}

/** Build the geometry for a segment list at a given viewbox size. */
export function geometry(
	segments: readonly ProfileSegment[],
	width: number,
	height: number
): CurveGeometry {
	const total = segments.reduce((a, s) => a + s.time, 0);
	return { width, height, total: total || 1 };
}

/** Map a time (seconds) to an SVG X coordinate. */
export function xFor(g: CurveGeometry, t: number): number {
	const innerW = g.width - PAD_X * 2;
	return PAD_X + (t / g.total) * innerW;
}

/** Map a pressure / flow value to an SVG Y coordinate. */
export function yFor(g: CurveGeometry, v: number): number {
	const innerH = g.height - PAD_Y * 2;
	const clamped = Math.min(Y_MAX, Math.max(0, v));
	return PAD_Y + innerH - (clamped / Y_MAX) * innerH;
}

/** Inverse of {@link xFor}: an SVG X back to a time (seconds), clamped ≥ 0. */
export function timeForX(g: CurveGeometry, x: number): number {
	const innerW = g.width - PAD_X * 2;
	return Math.max(0, ((x - PAD_X) / innerW) * g.total);
}

/** Inverse of {@link yFor}: an SVG Y back to a value, clamped to `[0, Y_MAX]`. */
export function valueForY(g: CurveGeometry, y: number): number {
	const innerH = g.height - PAD_Y * 2;
	const v = ((PAD_Y + innerH - y) / innerH) * Y_MAX;
	return Math.min(Y_MAX, Math.max(0, v));
}

/**
 * Build the SVG `path` `d` string for the pressure curve.
 *
 * A `fast` ramp jumps to the target (`L` to the target Y, then across); a
 * `smooth` ramp eases with a cubic Bézier — exactly the design's `curvePath`.
 */
export function curvePath(segments: readonly ProfileSegment[], g: CurveGeometry): string {
	let t = 0;
	let prev = 0;
	let d = `M ${xFor(g, 0)} ${yFor(g, prev)}`;
	for (const s of segments) {
		const x0 = xFor(g, t);
		const x1 = xFor(g, t + s.time);
		const y0 = yFor(g, prev);
		const y1 = yFor(g, s.target);
		if (s.ramp === 'fast') {
			d += ` L ${x0} ${y1} L ${x1} ${y1}`;
		} else {
			const cx0 = x0 + (x1 - x0) * 0.4;
			const cx1 = x0 + (x1 - x0) * 0.6;
			d += ` C ${cx0} ${y0}, ${cx1} ${y1}, ${x1} ${y1}`;
		}
		t += s.time;
		prev = s.target;
	}
	return d;
}

/** One draggable boundary dot — at a segment's end, at its target value. */
export interface BoundaryDot {
	/** The segment this dot terminates. */
	id: string;
	/** SVG X coordinate. */
	x: number;
	/** SVG Y coordinate. */
	y: number;
}

/** The X / Y of every segment-boundary dot — the design's `boundaryDots`. */
export function boundaryDots(
	segments: readonly ProfileSegment[],
	g: CurveGeometry
): BoundaryDot[] {
	let t = 0;
	return segments.map((s) => {
		t += s.time;
		return { id: s.id, x: xFor(g, t), y: yFor(g, s.target) };
	});
}

/**
 * The active-segment highlight band — the `[x, x + w]` span of the segment
 * with `id`, or `null` if `id` matches no segment.
 */
export function activeBand(
	segments: readonly ProfileSegment[],
	g: CurveGeometry,
	id: string | null
): { x: number; w: number } | null {
	if (id == null) return null;
	let t = 0;
	for (const s of segments) {
		if (s.id === id) {
			return { x: xFor(g, t), w: xFor(g, t + s.time) - xFor(g, t) };
		}
		t += s.time;
	}
	return null;
}

/**
 * The ghost flow curve — the design's stylised "estimated flow" companion
 * line. Derived from the segments by damping each target, drawn dashed.
 */
export function flowGhostPath(
	segments: readonly ProfileSegment[],
	g: CurveGeometry
): string {
	const flow = segments.map((s) => ({
		...s,
		target: Math.min(4, s.target * 0.35 + 0.5)
	}));
	return curvePath(flow, g);
}

/**
 * Map a dragged boundary-dot position back onto a segment edit.
 *
 * Dragging the dot for segment `index` changes two things, exactly as a DE1
 * profile editor would:
 *
 *  - **Y → `target`** — the dot's height is the segment's target pressure /
 *    flow, clamped to `[0, Y_MAX]` and rounded to 0.1.
 *  - **X → `time`** — the dot sits at the segment's *end*, so its X is the
 *    cumulative time. The new segment `time` is that cumulative time minus the
 *    starting time of all preceding segments, clamped to a sane `[1, 60]` s.
 *
 * Returns the `{ time, target }` patch for segment `index`; the caller applies
 * it to the model. Only that one segment changes — later segments keep their
 * own durations, so the total simply shifts.
 */
export function svgToSegment(
	segments: readonly ProfileSegment[],
	g: CurveGeometry,
	index: number,
	svgX: number,
	svgY: number
): { time: number; target: number } {
	const startTime = segments
		.slice(0, index)
		.reduce((a, s) => a + s.time, 0);
	const cumTime = timeForX(g, svgX);
	const time = Math.min(60, Math.max(1, Math.round((cumTime - startTime) * 2) / 2));
	const target = Math.round(valueForY(g, svgY) * 10) / 10;
	return { time, target };
}
