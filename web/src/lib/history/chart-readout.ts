/**
 * `#lib/history/chart-readout.js` — the display arithmetic behind the History
 * chart's readout bar (#298): which lines a shot draws, the sample nearest a
 * point in time, and the formatted values for that sample.
 *
 * Android has the same rules in `ui/screens/ShotChartReadout.kt`. Keep the
 * fields, their order and their formats in step between the two. The only
 * fields Android lacks are `water` and `resistance`, the lines only this
 * shell's History chart draws. Dashed goal lines are targets, not readings, so
 * neither shell reads them out.
 */

import {
	convertPressure,
	convertTemp,
	convertVolume,
	convertWeight,
	type Measurement
} from '#lib/settings/format.js';
import type { PressureUnit, TempUnit, VolumeUnit, WeightUnit } from '#lib/settings/index.js';
import type { TelemetrySample } from '#lib/state/index.js';

/** A solid line the History chart can draw, in readout order. */
export type ChartChannel =
	| 'pressure'
	| 'flow'
	| 'temp'
	| 'weight'
	| 'scaleFlow'
	| 'water'
	| 'resistance';

/** Readout order; {@link drawnChannels} filters this list. */
const CHANNEL_ORDER: readonly ChartChannel[] = [
	'pressure',
	'flow',
	'temp',
	'weight',
	'scaleFlow',
	'water',
	'resistance'
];

/** Each line's readout label and colour token. */
export const CHANNEL_META: Readonly<Record<ChartChannel, { label: string; color: string }>> = {
	pressure: { label: 'Pressure', color: 'var(--tel-pressure)' },
	flow: { label: 'Flow', color: 'var(--tel-flow)' },
	temp: { label: 'Temp', color: 'var(--tel-temp)' },
	weight: { label: 'Weight', color: 'var(--tel-weight)' },
	scaleFlow: { label: 'Scale flow', color: 'var(--tel-weight-2)' },
	water: { label: 'Water', color: 'var(--tel-flow-2)' },
	resistance: { label: 'Resistance', color: 'var(--tel-resistance)' }
};

/** The units the readout formats in, from the user's settings. */
export interface ReadoutUnits {
	weight: WeightUnit;
	temp: TempUnit;
	pressure: PressureUnit;
	volume: VolumeUnit;
}

/** One coloured value in the bar. */
export interface ReadoutField {
	channel: ChartChannel;
	label: string;
	color: string;
	value: string;
	unit: string;
}

/** The bar's content at one sample: time first, then a value per line. */
export interface ChartReadout {
	time: string;
	fields: ReadoutField[];
}

/** The placeholder for a line with no value at this sample. */
const DASH = '—';

/** The resistance a sample plots: the scale-derived one, else the DE1's. */
function resistanceOf(s: TelemetrySample): number | null {
	return s.resistanceWeight ?? s.resistance ?? null;
}

/**
 * The solid lines this shot draws, in readout order. A channel that no sample
 * carries is left out, so a shot without a scale has no weight or scale-flow
 * entry. Water reads back as 0 when it wasn't recorded, so 0 counts as absent.
 */
export function drawnChannels(
	series: readonly TelemetrySample[],
	{ showResistance }: { showResistance: boolean }
): ChartChannel[] {
	if (series.length === 0) return [];
	return CHANNEL_ORDER.filter((c) => {
		switch (c) {
			case 'weight':
				return series.some((s) => s.weight != null);
			case 'scaleFlow':
				return series.some((s) => s.weightFlow != null);
			case 'water':
				return series.some((s) => (s.dispensedVolume ?? 0) > 0);
			case 'resistance':
				return showResistance && series.some((s) => resistanceOf(s) != null);
			default:
				return true;
		}
	});
}

/**
 * Index of the sample nearest `elapsedMs`, or -1 for an empty series. `series`
 * is oldest first. A point exactly between two samples takes the earlier one.
 */
export function nearestSampleIndex(
	series: readonly TelemetrySample[],
	elapsedMs: number
): number {
	if (series.length === 0) return -1;
	let lo = 0;
	let hi = series.length - 1;
	while (lo < hi) {
		const mid = (lo + hi) >> 1;
		if (series[mid].elapsed < elapsedMs) lo = mid + 1;
		else hi = mid;
	}
	if (lo > 0 && elapsedMs - series[lo - 1].elapsed <= series[lo].elapsed - elapsedMs) return lo - 1;
	return lo;
}

/** A rate or ratio with one decimal; these have no user-selectable unit. */
function decimal(v: number | null | undefined, unit: string): Measurement {
	return { value: v != null && Number.isFinite(v) ? v.toFixed(1) : DASH, unit };
}

function measure(s: TelemetrySample, c: ChartChannel, units: ReadoutUnits): Measurement {
	switch (c) {
		case 'pressure':
			return convertPressure(s.pressure, units.pressure);
		case 'flow':
			return decimal(s.flow, 'ml/s');
		case 'temp':
			return convertTemp(s.temp, units.temp);
		case 'weight':
			return convertWeight(s.weight, units.weight);
		case 'scaleFlow':
			return decimal(s.weightFlow, 'g/s');
		case 'water':
			return convertVolume(s.dispensedVolume, units.volume);
		case 'resistance':
			return decimal(resistanceOf(s), '');
	}
}

/**
 * The bar's content at `sample`. With no sample, every value is the dash
 * placeholder, which lets the idle bar lay out the same cells as a scrubbing
 * one and keep its height.
 */
export function chartReadout(
	sample: TelemetrySample | null,
	channels: readonly ChartChannel[],
	units: ReadoutUnits
): ChartReadout {
	return {
		time: sample ? `${(sample.elapsed / 1000).toFixed(1)}s` : DASH,
		fields: channels.map((channel) => {
			const m = sample ? measure(sample, channel, units) : { value: DASH, unit: '' };
			return { channel, ...CHANNEL_META[channel], value: m.value, unit: m.unit };
		})
	};
}

/** Movement, in CSS px, before a press on the inline chart counts as a drag. */
export const DRAG_SLOP_PX = 8;

/** What a moving press on the inline chart has turned out to be. */
export type DragKind = 'pending' | 'scrub' | 'scroll';

/**
 * The inline chart's gesture rule: past the slop, a mostly horizontal drag
 * scrubs and anything else belongs to the page. Android has the same rule in
 * `classifyChartDrag`.
 */
export function classifyDrag(dx: number, dy: number, slop: number = DRAG_SLOP_PX): DragKind {
	const ax = Math.abs(dx);
	const ay = Math.abs(dy);
	if (Math.max(ax, ay) < slop) return 'pending';
	return ax > ay ? 'scrub' : 'scroll';
}
