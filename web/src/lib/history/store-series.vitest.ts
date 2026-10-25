/**
 * `insertPulled` must write the telemetry series to the series store: the
 * localStorage row is persisted stripped (review #27), so a restored backup,
 * a Visualizer pull or a Beanconqueror import that only called `persist()`
 * lost its chart on the next load.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { StoredShot } from './model';
import type { TimedSample } from '$lib/core';

const putSeries = vi.fn(async () => {});
vi.mock('./series-store', () => ({
	putSeries: (...args: unknown[]) => putSeries(...(args as [])),
	getAllSeries: async () => new Map(),
	deleteSeries: async () => {},
	pruneSeriesTo: async () => {},
	clearAllSeries: async () => {}
}));

import { HistoryStore } from './store.svelte';

function sample(elapsed: number): TimedSample {
	return {
		elapsed,
		sample: {
			sampleTime: elapsed / 10,
			groupPressure: 9,
			groupFlow: 2,
			headTemp: 92,
			mixTemp: 93,
			setMixTemp: 93,
			setHeadTemp: 92,
			setGroupPressure: 9,
			setGroupFlow: 0,
			frameNumber: 1,
			steamTemp: 140
		}
	};
}

function makeShot(id: string, samples: TimedSample[]): StoredShot {
	return {
		formatVersion: 3,
		id,
		completedAt: 1_700_000_000_000,
		profileName: 'Test Profile',
		profile: null,
		stopReason: null,
		metadata: {},
		record: { duration: 30_000, samples }
	};
}

describe('HistoryStore.insertPulled', () => {
	beforeEach(() => {
		localStorage.clear();
		putSeries.mockClear();
	});

	it('writes the series alongside the stripped row', () => {
		const store = new HistoryStore();
		const samples = [sample(0), sample(250), sample(500)];
		store.insertPulled(makeShot('shot:restored', samples));
		expect(putSeries).toHaveBeenCalledTimes(1);
		expect(putSeries).toHaveBeenCalledWith('shot:restored', samples);
		expect(store.get('shot:restored')?.record.samples).toHaveLength(3);
	});

	it('skips the series store for a metadata-only stub', () => {
		const store = new HistoryStore();
		store.insertPulled(makeShot('shot:stub', []));
		expect(putSeries).not.toHaveBeenCalled();
	});
});
