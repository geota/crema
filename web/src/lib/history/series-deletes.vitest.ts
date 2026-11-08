/**
 * Explicit deletes (#325): a row's series and capture go by name, after the
 * write that removes the row, and never because a listing doesn't name them.
 * Runs the real stores over an in-memory IndexedDB.
 */
import { readMarker } from '#lib/restore/marker.js';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { IDBFactory } from 'fake-indexeddb';
import type { TimedSample } from '#lib/core/index.js';
import { getCaptureStore } from '#lib/capture/store.js';
import { buildBackupJsonl, restoreBackup } from '#lib/backup/index.js';
import type { StoredShot } from './model';
import { clearAllSeries, getAllSeries, putSeries } from './series-store';
import { getHistoryStore, HistoryStore } from './store.svelte';

const HISTORY_KEY = 'crema.history.v2';

const samples: TimedSample[] = [
	{
		elapsed: 100,
		sample: {
			sampleTime: 10,
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
	}
];

function row(id: string, at = 1_700_000_000_000, curve: TimedSample[] = samples): StoredShot {
	return {
		formatVersion: 3,
		id,
		completedAt: at,
		profileName: 'Test Profile',
		profile: null,
		stopReason: null,
		metadata: {},
		record: { duration: 30_000, samples: curve }
	};
}

/** Let fire-and-forget IndexedDB work and a boot's hydrate finish. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 30));

/** What the next page load sees: a fresh store over the same storage. */
async function boot(): Promise<HistoryStore> {
	const store = new HistoryStore();
	await settle();
	return store;
}

/** A device with `ids` stored: rows, series and captures. */
async function stored(...ids: string[]): Promise<HistoryStore> {
	const store = new HistoryStore();
	for (const id of ids) {
		store.insertPulled(row(id));
		await getCaptureStore().put(id, []);
	}
	await settle();
	return boot();
}

const seriesIds = async () => [...(await getAllSeries()).keys()].sort();
const captureIds = async () => (await getCaptureStore().keys()).sort();

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
afterEach(() => {
	vi.restoreAllMocks();
});

describe('a corrupt history (#325)', () => {
	it('keeps every series and capture through a save and a clean-looking launch', async () => {
		await stored('a', 'b', 'c');
		localStorage.setItem(HISTORY_KEY, '{ not json');

		const first = await boot();
		expect(first.rawAll).toEqual([]);
		first.insertPulled(row('new'));
		await settle();

		// A clean load of one row: nothing here shows the damage.
		const second = await boot();
		expect(second.rawAll.map((s) => s.id)).toEqual(['new']);
		second.insertPulled(row('newer', 1_700_000_000_001));
		await settle();
		await boot();

		expect(await seriesIds()).toEqual(['a', 'b', 'c', 'new', 'newer']);
		expect(await captureIds()).toEqual(['a', 'b', 'c']);
		expect(localStorage.getItem(`${HISTORY_KEY}.corrupt`)).toBe('{ not json');
	});

	it('gives a row that comes back its curve', async () => {
		await stored('a');
		localStorage.setItem(HISTORY_KEY, '{ not json');
		const store = await boot();
		store.insertPulled(row('a', 1_700_000_000_000, []));
		await settle();
		expect((await boot()).get('a')?.record.samples).toEqual(samples);
	});
});

describe('deletes by name', () => {
	it('removing a row deletes its series and capture, and nothing else', async () => {
		const store = await stored('gone', 'stays');
		store.delete('gone');
		await settle();
		expect(await seriesIds()).toEqual(['stays']);
		expect(await captureIds()).toEqual(['stays']);
	});

	it('a row the cap drops loses its series and capture', async () => {
		localStorage.setItem(
			HISTORY_KEY,
			JSON.stringify(Array.from({ length: 300 }, (_, i) => row(`r${i}`, 1_000_000 - i, [])))
		);
		await putSeries('r299', samples);
		await getCaptureStore().put('r299', []);
		await putSeries('r0', samples);
		const store = await boot();
		store.insertMany([row('newest', 2_000_000)]);
		await settle();
		expect(store.rawAll).toHaveLength(300);
		expect(await seriesIds()).toEqual(['newest', 'r0']);
		expect(await captureIds()).toEqual([]);
	});

	it('a row a quota eviction drops loses its series, once the retry stored', async () => {
		const store = await stored('r1', 'r2', 'r3', 'r4', 'r5', 'r6', 'r7', 'r8', 'r9', 'r10');
		const oldest = store.rawAll.at(-1)!.id;
		const realSet = localStorage.setItem.bind(localStorage);
		let fail = true;
		vi.spyOn(localStorage, 'setItem').mockImplementation((k: string, v: string) => {
			if (fail) {
				fail = false;
				throw new DOMException('full', 'QuotaExceededError');
			}
			realSet(k, v);
		});
		store.setRating(store.rawAll[0].id, 3);
		await settle();
		expect(store.rawAll).toHaveLength(9);
		expect(await seriesIds()).not.toContain(oldest);
	});

	it('a failed save deletes nothing, and the next save does', async () => {
		const store = await stored('gone', 'stays');
		const spy = vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
			throw new DOMException('full', 'QuotaExceededError');
		});
		store.delete('gone');
		await settle();
		expect(await seriesIds()).toEqual(['gone', 'stays']);
		expect(await captureIds()).toEqual(['gone', 'stays']);

		spy.mockRestore();
		store.setRating('stays', 4);
		await settle();
		expect(await seriesIds()).toEqual(['stays']);
		expect(await captureIds()).toEqual(['stays']);
	});

	it('a row added back after its delete keeps the new series', async () => {
		const store = await stored('back');
		store.delete('back');
		store.insertPulled(row('back'));
		await settle();
		expect(await seriesIds()).toEqual(['back']);
	});

	it('a tombstone keeps its series until purged, and loses its capture', async () => {
		const store = await stored('remote');
		store.bindVisualizerId('remote', 'v1');
		store.delete('remote');
		await settle();
		expect(await seriesIds()).toEqual(['remote']);
		expect(await captureIds()).toEqual([]);
		store.purgeTombstone('remote');
		await settle();
		expect(await seriesIds()).toEqual([]);
	});
});

describe('rebasing', () => {
	it('adopting rows from another tab deletes nothing', async () => {
		const store = await stored('a', 'theirs');
		store.adoptRows(store.rawAll.filter((s) => s.id === 'a'));
		store.setRating('a', 5);
		await settle();
		expect(await seriesIds()).toEqual(['a', 'theirs']);
		expect(await captureIds()).toEqual(['a', 'theirs']);
	});

	it('saves during a pause delete nothing and rebase known', async () => {
		const store = await stored('old', 'kept');
		const resume = store.pauseDeletes();
		store.delete('old');
		store.insertPulled(row('restored', 1_700_000_000_002));
		resume();
		resume(); // resuming twice can't turn deletes back on early for another pause
		await settle();
		expect(await seriesIds()).toEqual(['kept', 'old', 'restored']);

		store.setRating('kept', 1);
		await settle();
		expect(await seriesIds()).toEqual(['kept', 'old', 'restored']);

		store.delete('restored');
		await settle();
		expect(await seriesIds()).toEqual(['kept', 'old']);
	});

	it('a pin moves with a backup, and an unpinned row comes back unpinned', async () => {
		const history = getHistoryStore();
		history.clearAllShots();
		history.insertPulled(row('pinned'));
		history.insertPulled(row('plain', 1_700_000_000_001));
		history.setPinned('pinned', true);
		await settle();
		const backup = (await buildBackupJsonl())!;
		expect(backup.jsonl).toContain('"pinned":true');
		history.setPinned('pinned', false);
		await restoreBackup(backup.jsonl, 'wipe');
		await settle();
		expect(history.get('pinned')?.pinned).toBe(true);
		expect(history.get('plain')?.pinned).toBeUndefined();
	});

	it('a Replace restore keeps a kept row captures and leaves removal to its marker', async () => {
		const history = getHistoryStore();
		history.clearAllShots();
		history.insertPulled(row('kept'));
		await getCaptureStore().put('kept', []);
		await settle();
		const backup = (await buildBackupJsonl())!;
		history.insertPulled(row('later', 1_700_000_000_003));
		await getCaptureStore().put('later', []);
		await settle();

		await restoreBackup(backup.jsonl, 'wipe');
		await settle();
		expect(history.rawAll.map((s) => s.id)).toEqual(['kept']);
		// The removed row's curve and capture wait for the marker's hour.
		expect(await captureIds()).toEqual(['kept', 'later']);
		expect(await seriesIds()).toEqual(['kept', 'later']);
		expect(readMarker()?.deletes.shots).toEqual(['later']);
	});
});
