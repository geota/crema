/**
 * Raw captures past the limit (#311): the newest N shots keep theirs, pinned
 * shots keep theirs whatever the limit, and the preview is exactly what goes.
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { IDBFactory } from 'fake-indexeddb';
import type { StoredShot } from '#lib/history/model.js';
import { clearAllSeries } from '#lib/history/series-store.js';
import { HistoryStore } from '#lib/history/store.svelte.js';
import { getCaptureStore } from './store.ts';
import { CAPTURE_KEEP_KEY, readCaptureKeep } from './limit.ts';
import { capturesOverLimit, pruneCaptures } from './prune.ts';

function row(id: string, at: number): StoredShot {
	return {
		formatVersion: 3,
		id,
		completedAt: at,
		profileName: 'P',
		profile: null,
		stopReason: null,
		metadata: {},
		record: { duration: 30_000, samples: [] }
	};
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 30));
const captureIds = async () => (await getCaptureStore().keys()).sort();

/** A device with `n` captured shots, `s0` the oldest, and the history reloaded. */
async function device(n: number): Promise<HistoryStore> {
	const first = new HistoryStore();
	for (let i = 0; i < n; i++) {
		first.insertPulled(row(`s${i}`, 1_700_000_000_000 + i * 1000));
		await getCaptureStore().put(`s${i}`, []);
	}
	await settle();
	const store = new HistoryStore();
	await settle();
	return store;
}

const realIdb = (globalThis as { indexedDB?: IDBFactory }).indexedDB;
beforeAll(() => {
	(globalThis as { indexedDB?: IDBFactory }).indexedDB = new IDBFactory();
});
afterAll(() => {
	(globalThis as { indexedDB?: IDBFactory }).indexedDB = realIdb;
});
beforeEach(async () => {
	localStorage.clear();
	await clearAllSeries();
	const captures = getCaptureStore();
	for (const id of await captures.keys()) await captures.delete(id);
});

describe('raw captures past the limit', () => {
	it('keeps the newest, and a pinned capture survives Off, 10 and a lowered limit', async () => {
		const history = await device(12);
		history.setPinned('s0', true);
		expect(await pruneCaptures(history, '10')).toEqual(['s1']);
		expect(await captureIds()).toContain('s0');
		await pruneCaptures(history, 'off');
		expect(await captureIds()).toEqual(['s0']);
	});

	it('the preview lists exactly what goes, and deletes nothing', async () => {
		const history = await device(4);
		history.setPinned('s1', true);
		const preview = await capturesOverLimit(history, 'off');
		expect(preview.sort()).toEqual(['s0', 's2', 's3']);
		expect(await captureIds()).toHaveLength(4);
		expect((await pruneCaptures(history, 'off')).sort()).toEqual(preview.sort());
	});

	it('unpinning makes a capture eligible at the next prune', async () => {
		const history = await device(3);
		history.setPinned('s0', true);
		expect(await pruneCaptures(history, '10')).toEqual([]);
		await pruneCaptures(history, 'off');
		expect(await captureIds()).toEqual(['s0']);
		history.setPinned('s0', false);
		expect(await pruneCaptures(history, 'off')).toEqual(['s0']);
	});

	it('All deletes nothing, and a capture the history does not name is left', async () => {
		const history = await device(3);
		await getCaptureStore().put('stray', []);
		expect(await pruneCaptures(history, 'all')).toEqual([]);
		await pruneCaptures(history, 'off');
		expect(await captureIds()).toEqual(['stray']);
	});

	it('never prunes while the history load is not ok', async () => {
		await device(3);
		// A row that can't be read: the rest load, but the store is damaged, and
		// the unreadable row may be a pinned one.
		const rows = JSON.parse(localStorage.getItem('crema.history.v2')!) as unknown[];
		localStorage.setItem('crema.history.v2', JSON.stringify([...rows, 'not a row']));
		const damaged = new HistoryStore();
		await settle();
		expect(damaged.rawAll).toHaveLength(3);
		expect(await pruneCaptures(damaged, 'off')).toEqual([]);
		expect(await captureIds()).toHaveLength(3);
	});
});

describe('the capture limit', () => {
	it('a new install starts at 50, an existing one keeps 300', () => {
		expect(readCaptureKeep(() => false)).toBe('50');
		localStorage.removeItem(CAPTURE_KEEP_KEY);
		expect(readCaptureKeep(() => true)).toBe('300');
		expect(readCaptureKeep(() => false), 'the first read is stored').toBe('300');
	});

	it('is fixed at the first history load, before anything new is recorded', async () => {
		await device(2);
		localStorage.removeItem(CAPTURE_KEEP_KEY);
		new HistoryStore();
		expect(localStorage.getItem(CAPTURE_KEEP_KEY)).toBe('"300"');
	});
});
