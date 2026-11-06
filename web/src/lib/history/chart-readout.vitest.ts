import { describe, expect, it } from 'vitest';
import type { TelemetrySample } from '#lib/state/index.js';
import {
	chartReadout,
	classifyDrag,
	drawnChannels,
	nearestSampleIndex,
	type ChartChannel,
	type ReadoutUnits
} from './chart-readout';

function sample(elapsed: number, over: Partial<TelemetrySample> = {}): TelemetrySample {
	return {
		elapsed,
		pressure: 9,
		flow: 2.14,
		temp: 92.34,
		mixTemp: 91,
		steamTemp: 0,
		weight: null,
		weightFlow: null,
		dispensedVolume: 0,
		resistance: null,
		resistanceWeight: null,
		...over
	} as TelemetrySample;
}

const METRIC: ReadoutUnits = { weight: 'g', temp: 'C', pressure: 'bar', volume: 'ml' };
const IMPERIAL: ReadoutUnits = { weight: 'oz', temp: 'F', pressure: 'psi', volume: 'floz' };
const SCALE_LINES: ChartChannel[] = ['pressure', 'flow', 'temp', 'weight', 'scaleFlow'];

describe('nearestSampleIndex', () => {
	const series = [sample(0), sample(250), sample(500), sample(1000)];

	it('has no sample in an empty series', () => {
		expect(nearestSampleIndex([], 100)).toBe(-1);
	});

	it('clamps to the ends', () => {
		expect(nearestSampleIndex(series, -50)).toBe(0);
		expect(nearestSampleIndex(series, 9000)).toBe(3);
	});

	it('takes the closer neighbour', () => {
		expect(nearestSampleIndex(series, 100)).toBe(0);
		expect(nearestSampleIndex(series, 200)).toBe(1);
		expect(nearestSampleIndex(series, 800)).toBe(3);
	});

	it('finds an exact hit', () => {
		expect(nearestSampleIndex(series, 500)).toBe(2);
	});

	it('takes the earlier sample at the midpoint', () => {
		expect(nearestSampleIndex(series, 750)).toBe(2);
	});
});

describe('drawnChannels', () => {
	it('leaves the scale lines out of a shot with no scale', () => {
		expect(drawnChannels([sample(0)], { showResistance: false })).toEqual([
			'pressure',
			'flow',
			'temp'
		]);
	});

	it('adds them when any sample carries them', () => {
		const series = [sample(0), sample(250, { weight: 4, weightFlow: 1.2 })];
		expect(drawnChannels(series, { showResistance: false })).toEqual(SCALE_LINES);
	});

	it('adds water only when some was recorded', () => {
		expect(
			drawnChannels([sample(0, { dispensedVolume: 12 })], { showResistance: false })
		).toContain('water');
	});

	it('follows the resistance toggle', () => {
		const series = [sample(0, { resistance: 2 })];
		expect(drawnChannels(series, { showResistance: true })).toContain('resistance');
		expect(drawnChannels(series, { showResistance: false })).not.toContain('resistance');
	});

	it('draws nothing for an empty shot', () => {
		expect(drawnChannels([], { showResistance: true })).toEqual([]);
	});
});

describe('chartReadout', () => {
	const scaled = sample(24_300, { weight: 18.44, weightFlow: 1.87 });

	it('formats time and one value per line, in order', () => {
		const r = chartReadout(scaled, SCALE_LINES, METRIC);
		expect(r.time).toBe('24.3s');
		expect(r.fields.map((f) => [f.label, f.value, f.unit])).toEqual([
			['Pressure', '9.0', 'bar'],
			['Flow', '2.1', 'ml/s'],
			['Temp', '92.3', '°C'],
			['Weight', '18.4', 'g'],
			['Scale flow', '1.9', 'g/s']
		]);
	});

	it('uses the scale-flow colour token', () => {
		const r = chartReadout(scaled, ['scaleFlow'], METRIC);
		expect(r.fields[0].color).toBe('var(--tel-weight-2)');
	});

	it('respects the unit settings', () => {
		const r = chartReadout(scaled, ['pressure', 'temp', 'weight'], IMPERIAL);
		expect(r.fields.map((f) => `${f.value} ${f.unit}`)).toEqual([
			'131 psi',
			'198.2 °F',
			'0.65 oz'
		]);
	});

	it('shows a dash where a drawn line has a gap at this sample', () => {
		const r = chartReadout(sample(1000, { weight: 3 }), ['weight', 'scaleFlow'], METRIC);
		expect(r.fields.map((f) => f.value)).toEqual(['3.0', '—']);
	});

	it('prefers scale-derived resistance', () => {
		const s = sample(0, { resistance: 2, resistanceWeight: 3 });
		expect(chartReadout(s, ['resistance'], METRIC).fields[0].value).toBe('3.0');
	});

	it('lays out the same cells with no sample, so the idle bar holds its height', () => {
		const idle = chartReadout(null, SCALE_LINES, METRIC);
		expect(idle.time).toBe('—');
		expect(idle.fields.map((f) => f.channel)).toEqual(SCALE_LINES);
		expect(idle.fields.every((f) => f.value === '—')).toBe(true);
	});
});

describe('classifyDrag', () => {
	it('waits inside the slop', () => {
		expect(classifyDrag(3, 3)).toBe('pending');
	});

	it('scrubs on a mostly horizontal drag, either way', () => {
		expect(classifyDrag(12, 4)).toBe('scrub');
		expect(classifyDrag(-12, 4)).toBe('scrub');
	});

	it('leaves a mostly vertical drag to the page', () => {
		expect(classifyDrag(3, 12)).toBe('scroll');
		expect(classifyDrag(3, -12)).toBe('scroll');
	});

	it('leaves a true diagonal to the page', () => {
		expect(classifyDrag(10, 10)).toBe('scroll');
	});
});
