/**
 * Captured shots carry the DE1's profile frame number per sample, like
 * Android's capture (core/shell review 2026-10-30 §2.3-2). Web stored
 * `frameNumber: 0` on every sample, so its shots had no Visualizer step bars
 * and their quality phases degraded to Start/End.
 */
import { describe, expect, it, vi } from 'vitest';
import type { Event } from '#lib/core/index.js';
import { SubState } from '#lib/core/crema-core.js';
import { applyEvent, INITIAL_SNAPSHOT, type UiSnapshot } from '#lib/state/ui-state.svelte.js';
import { fromWire, toWire } from './telemetry-wire';

vi.mock('./series-store', () => ({
	putSeries: async () => {},
	getAllSeries: async () => new Map(),
	deleteSeries: async () => {},
	pruneSeriesTo: async () => {},
	clearAllSeries: async () => {}
}));

import { HistoryStore } from './store.svelte';

function telemetry(elapsed: number): Event {
	return {
		type: 'Telemetry',
		content: {
			elapsed,
			group_pressure: 9,
			group_flow: 2,
			head_temp: 93,
			mix_temp: 92,
			steam_temp: 140,
			dispensed_volume: elapsed / 500,
			set_head_temp: 93,
			set_group_pressure: 9,
			set_group_flow: 0
		}
	} as Event;
}

/** A pour that moves through frames 0 → 2 → 3, one telemetry sample each. */
function capturedPour(): UiSnapshot {
	let s: UiSnapshot = { ...INITIAL_SNAPSHOT, shotInProgress: true, machineSubstate: SubState.Pouring };
	const events: Event[] = [
		{ type: 'ShotFrameChanged', content: { frame: 0 } },
		telemetry(250),
		{ type: 'ShotFrameChanged', content: { frame: 2 } },
		telemetry(500),
		telemetry(750),
		{ type: 'ShotFrameChanged', content: { frame: 3 } },
		telemetry(1000)
	];
	for (const e of events) s = applyEvent(s, e);
	return s;
}

describe('shot frame numbers', () => {
	it('AC-F18b each captured sample carries the frame the DE1 was running', () => {
		expect(capturedPour().shotTelemetry.map((t) => t.frameNumber)).toEqual([0, 2, 2, 3]);
	});

	it('AC-F18b frame numbers round-trip through the stored shot and back', () => {
		localStorage.clear();
		const series = capturedPour().shotTelemetry;
		const shot = new HistoryStore().record({
			duration: 1000,
			profileName: 'Test',
			dose: 18,
			series,
			bean: null,
			grinderModel: null,
			peakPressure: 9,
			peakTemp: 93,
			peakWeight: null,
			finalWeight: null
		});
		expect(shot?.record.samples.map((t) => t.sample.frameNumber)).toEqual([0, 2, 2, 3]);
		// The persisted wire JSON reads back to the same frames.
		const back = JSON.parse(JSON.stringify(shot!.record.samples)).map(fromWire);
		expect(back.map((t: { frameNumber?: number }) => t.frameNumber)).toEqual([0, 2, 2, 3]);
	});

	it('a sample captured before the field existed still writes frame 0', () => {
		const legacy = { ...capturedPour().shotTelemetry[0], frameNumber: undefined };
		expect(toWire(legacy).sample.frameNumber).toBe(0);
	});
});
