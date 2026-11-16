/**
 * #310 P5w: the version-keyed derivations over the real store. Edits made
 * the way the History page makes them (`setRating`, `setNotes`, `setPinned`)
 * move only their own version, and the row the page draws (`store.get`) is
 * the edited one at once, while the list the derivations hold stays put.
 */
import { afterAll, beforeEach, expect, it, vi } from 'vitest';
import * as filterModule from './list-filter';

const predicate = vi.hoisted(() => ({ calls: 0 }));
vi.mock('./list-filter', async (importOriginal) => {
	const real = await importOriginal<typeof filterModule>();
	return {
		...real,
		shotPasses: (...args: Parameters<typeof real.shotPasses>) => {
			predicate.calls += 1;
			return real.shotPasses(...args);
		}
	};
});

import { flushSync } from 'svelte';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { HistoryDb, META_MIGRATION } from './history-db';
import { writeDoneMarker } from './store-marker';
import { summarize } from './summary';
import { boot, freshStorage } from './idb-test-kit';
import { HistoryListModel } from './list-model.svelte';
import type { StoredShot } from './model';
import { p3RowsOf } from '#lib/history/p3-rows.js';

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	freshStorage();
});

it('a star tap, a note and a pin through the store move only their own version', async () => {
	const shots: StoredShot[] = Array.from({ length: 300 }, (_, i) => realShot(i, realSamples(20, i % 60)));
	const db = new HistoryDb({ expectExisting: () => false });
	await db.putShots(shots.map((shot) => ({ shot, details: true })), summarize);
	await db.setMeta(META_MIGRATION, { state: 'done', sourceHash: '0:0', sourceIds: shots.map((s) => s.id) });
	db.close();
	writeDoneMarker(1, shots.length);
	const store = await boot();

	let model!: HistoryListModel;
	const stop = $effect.root(() => {
		model = new HistoryListModel({
			rows: () => p3RowsOf(store).live,
			get: (id) => store.get(id),
			query: () => '',
			filters: () => ({
				cutoff: null,
				profile: 'all',
				pinnedOnly: false,
				bean: 'all',
				method: 'all',
				tags: [],
				sortField: 'completedAt',
				sortDir: 'desc'
			}),
			tagsOf: (s) => s.tags ?? [],
			liveCoffee: () => null
		});
	});
	flushSync();
	const filtered = model.filtered;
	const facets = model.tagFacets;
	expect(filtered).toHaveLength(300);
	const versions = () => ({ list: model.versions.list, rating: model.versions.rating, text: model.versions.text });
	const start = versions();
	const id = filtered[42].id;

	predicate.calls = 0;
	store.setRating(id, 1);
	flushSync();
	expect(model.filtered).toBe(filtered);
	expect(model.tagFacets).toBe(facets);
	expect(predicate.calls).toBe(0);
	expect(versions()).toEqual({ ...start, rating: start.rating + 1 });
	expect(store.get(id)?.metadata.rating).toBe(1);

	store.setNotes(id, 'chocolate');
	flushSync();
	expect(model.filtered).toBe(filtered);
	expect(predicate.calls).toBe(0);
	expect(versions()).toEqual({ ...start, rating: start.rating + 1, text: start.text + 1 });
	expect(store.get(id)?.metadata.notes).toBe('chocolate');

	// Pinned is a facet field (#311): the list reruns.
	store.setPinned(id, true);
	flushSync();
	expect(model.filtered).not.toBe(filtered);
	expect(predicate.calls).toBe(300);
	expect(model.pinnedCount).toBe(1);
	stop();
}, 60_000);
