/**
 * Shared uPlot scaffolding for the two telemetry charts (`brew/LiveChart`,
 * `history/StaticShotChart`) plus the `cssVar` token resolver the profile
 * editors (`ProfileCurveEditor`, `ProfilePreview`) share too.
 *
 * Only the scale + axis *contract* lives here — the ÷10 dual-axis (the left
 * axis labels the shared scale as-is in bar / ml·s, the right labels the same
 * ticks ×10 in °C / g) and the y-headroom. Each chart keeps its own `series`
 * list and `toData` column builder, since the live and stored views plot
 * different channel sets. Change the locked axis contract once, here, and both
 * telemetry charts follow.
 */
import type uPlot from 'uplot';

/** Resolve a CSS custom property to a concrete colour string. */
export function cssVar(name: string): string {
	if (typeof window === 'undefined') return '#888';
	return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';
}

/** Round-numbered second marks inside the window — design uses 10 s steps. */
export function timeSplits(max: number): number[] {
	const incr = max <= 90 ? 10 : max <= 180 ? 20 : 30;
	const out: number[] = [];
	for (let t = incr; t < max; t += incr) out.push(t);
	return out;
}

/**
 * The x-scale `range` callback: the window opens at `baseWindowSec` and grows
 * to fit the shot — a pull past the base simply extends the axis to the right
 * rather than clipping. Data-driven, like {@link yRange}.
 */
export function xRange(baseWindowSec: number): uPlot.Scale.Range {
	return (_u, _min, dataMax) => [
		0,
		Number.isFinite(dataMax) ? Math.max(baseWindowSec, Math.ceil(dataMax)) : baseWindowSec
	];
}

/**
 * The shared y-scale `range` callback — one scale for all four channels. The
 * top floats from 10 upward so a mid-shot flow / pressure spike grows both
 * axes together. A hair of headroom (+0.3) keeps a peak landing on a round
 * number off the very top edge, so its end-dot never half-clips.
 */
export const yRange: uPlot.Scale.Range = (_u, _min, dataMax) => [
	0,
	Number.isFinite(dataMax) ? Math.max(10, Math.ceil(dataMax + 0.3)) : 10
];

/**
 * The locked dual-axis definition both telemetry charts share: an x-axis with
 * the dashed vertical grid + round-second labels, a left y-axis labelling the
 * shared scale as-is (bar / ml·s) and drawing the horizontal grid, and a right
 * y-axis labelling the same ticks ×10 (°C / g). Colours are resolved by the
 * caller — canvas strokes can't read `var()`, and a theme flip rebuilds the
 * chart against fresh tokens.
 */
export function sharedAxes(opts: {
	gridColor: string;
	labelColor: string;
	baseWindowSec: number;
}): uPlot.Axis[] {
	const { gridColor, labelColor, baseWindowSec } = opts;
	const font = '11px "JetBrains Mono", monospace';
	return [
		{
			scale: 'x',
			stroke: labelColor,
			grid: { stroke: gridColor, width: 1, dash: [2, 4] },
			ticks: { show: false },
			font,
			splits: (u) => timeSplits((u.scales.x.max ?? baseWindowSec) as number),
			values: (_u, splits) => splits.map((v) => `${v}s`)
		},
		{
			// Left axis: the scale value as-is — bar (pressure) / ml·s (flow).
			scale: 'y',
			side: 3,
			stroke: labelColor,
			grid: { stroke: gridColor, width: 1 },
			ticks: { show: false },
			font,
			size: 34,
			values: (_u, splits) => splits.map((v) => `${v}`)
		},
		{
			// Right axis: the same ticks ×10 — °C (temp) / g (weight).
			scale: 'y',
			side: 1,
			stroke: labelColor,
			grid: { show: false },
			ticks: { show: false },
			font,
			size: 38,
			values: (_u, splits) => splits.map((v) => `${v * 10}`)
		}
	];
}
