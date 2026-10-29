import { describe, expect, it } from 'vitest';
import type { TelemetrySample } from '#lib/state/index.js';
import { liveChartSummary, storedChartSummary } from './chart-summary.js';

const s = (elapsed: number, pressure: number, flow: number): TelemetrySample =>
	({ elapsed, pressure, flow, temp: 93, mixTemp: 92, steamTemp: 140, weight: null }) as TelemetrySample;

describe('chart summaries', () => {
	it('describes an empty live chart', () => {
		expect(liveChartSummary([])).toBe('Shot telemetry chart, no shot plotted yet');
	});

	it('reads the latest live sample', () => {
		expect(liveChartSummary([s(1000, 2, 1), s(24_400, 9.14, 2.06)])).toBe(
			'Shot telemetry chart at 24 s: pressure 9.1 bar, flow 2.1 ml/s'
		);
	});

	it('summarises a stored shot by duration and peaks', () => {
		expect(storedChartSummary([s(0, 0, 0), s(10_000, 9.4, 1.8), s(28_600, 6, 2.6)])).toBe(
			'Shot telemetry chart, 29 s: peak pressure 9.4 bar, peak flow 2.6 ml/s'
		);
		expect(storedChartSummary([])).toBe('Shot telemetry chart, no samples recorded');
	});
});
