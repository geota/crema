/**
 * Rows load at once and their telemetry hydrates from IndexedDB afterwards
 * (#330). Until it lands a machine shot looks like one with no curve, so
 * anything that sends or saves a whole shot waits for it, and a shot that still
 * has none stays home.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import type { TimedSample } from '#lib/core/index.js';
import { refusedNoSamples, type StoredShot } from './model';

const series = vi.hoisted(() => ({
	load: (): Promise<Map<string, unknown[]>> => Promise.resolve(new Map())
}));

vi.mock('./series-store', () => ({
	putSeries: async () => {},
	getAllSeries: () => series.load(),
	deleteSeries: async () => {},
	pruneSeriesTo: async () => {},
	clearAllSeries: async () => {}
}));

import { HistoryStore } from './store.svelte';

const sample = { t: 1, pressure: 9 } as unknown as TimedSample;

const row = (over: Partial<StoredShot> = {}): StoredShot =>
	({
		formatVersion: 3,
		id: 's1',
		completedAt: 1_700_000_000_000,
		profileName: 'P',
		metadata: {},
		record: { duration: 30_000, samples: [] },
		tags: [],
		visualizerId: null,
		...over
	}) as StoredShot;

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	localStorage.clear();
	localStorage.setItem('crema.history.v2', JSON.stringify([row()]));
	vi.stubGlobal('indexedDB', {});
});

afterEach(() => {
	vi.unstubAllGlobals();
	vi.useRealTimers();
	series.load = () => Promise.resolve(new Map());
});

describe('HistoryStore.seriesReady', () => {
	it('settles once the series have landed in the rows', async () => {
		let land = (_: Map<string, unknown[]>): void => {};
		series.load = () => new Promise((resolve) => (land = resolve));
		const store = new HistoryStore();
		const ready = store.seriesReady();
		await Promise.resolve();
		expect(store.get('s1')?.record.samples).toHaveLength(0);

		land(new Map([['s1', [sample]]]));

		expect(await ready).toBe(true);
		expect(store.get('s1')?.record.samples).toHaveLength(1);
	});

	it('a read that fails once is tried again, and nothing has failed', async () => {
		let reads = 0;
		series.load = () => {
			reads += 1;
			return reads === 1
				? Promise.reject(new Error('blocked'))
				: Promise.resolve(new Map([['s1', [sample]]]));
		};
		const store = new HistoryStore();

		expect(await store.seriesReady()).toBe(true);

		expect(reads).toBe(2);
		expect(store.seriesFailed).toBe(false);
		expect(store.get('s1')?.record.samples).toHaveLength(1);
	});

	it('a read that fails twice settles as failed', async () => {
		let reads = 0;
		series.load = () => {
			reads += 1;
			return Promise.reject(new Error('blocked'));
		};
		const store = new HistoryStore();

		expect(await store.seriesReady()).toBe(true);

		expect(reads).toBe(2);
		expect(store.seriesFailed).toBe(true);
	});

	it('answers false when the load outlasts the wait', async () => {
		vi.useFakeTimers();
		series.load = () => new Promise(() => {});
		const ready = new HistoryStore().seriesReady(1_000);
		await vi.advanceTimersByTimeAsync(1_001);
		expect(await ready).toBe(false);
	});

	it('has nothing to wait for without IndexedDB', async () => {
		vi.unstubAllGlobals();
		const original = globalThis.indexedDB;
		vi.stubGlobal('indexedDB', undefined);
		expect(await new HistoryStore().seriesReady()).toBe(true);
		vi.stubGlobal('indexedDB', original);
	});
});

describe('the Visualizer backlog', () => {
	const withCurve = row({ id: 's2', record: { duration: 1, samples: [sample] } as never });
	const uploaded = row({ id: 's3', visualizerId: 'v3' });
	const brew = row({ id: 's4', brewMethod: 'pourover' });

	beforeEach(() => {
		localStorage.setItem('crema.history.v2', JSON.stringify([row(), withCurve, uploaded, brew]));
	});

	it('keeps a row while its curve may still be on its way', async () => {
		let land: ((rows: Map<string, unknown[]>) => void) | undefined;
		series.load = () => new Promise((resolve) => (land = resolve));
		const store = new HistoryStore();
		await vi.waitFor(() => expect(land).toBeDefined());

		expect(store.visualizerBacklog.map((s) => s.id)).toEqual(['s1', 's2']);
		expect(store.withoutTelemetry).toEqual([]);

		land?.(new Map());
		await store.seriesReady();
		await new Promise((resolve) => setTimeout(resolve));

		expect(store.visualizerBacklog.map((s) => s.id)).toEqual(['s2']);
		expect(store.withoutTelemetry.map((s) => s.id)).toEqual(['s1']);
	});
});

describe('refusedNoSamples', () => {
	it('refuses a machine shot with no samples', () => {
		expect(refusedNoSamples(row())).toBe(true);
	});

	it('passes a shot with samples, and a count the caller holds', () => {
		expect(refusedNoSamples(row({ record: { duration: 1, samples: [sample] } as never }))).toBe(false);
		expect(refusedNoSamples(row(), 12)).toBe(false);
	});

	it('leaves a brew log to its own rule', () => {
		expect(refusedNoSamples(row({ brewMethod: 'pourover' }))).toBe(false);
	});
});
