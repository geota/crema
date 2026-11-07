/**
 * A delete records intent, and a binding can arrive later (#331). A shot deleted
 * during its upload used to go outright: the upload then finished, the id had no
 * row to land on, and the next pull brought the shot back.
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { enqueueEntry, readQueue } from '#lib/services/queue-store.js';
import { reconcileShots, type WireShot } from '#lib/visualizer/shot-sync-signatures.js';
import {
	markUploadAttempted,
	remoteKeptOut,
	settleMarksForAccount,
	uploadAttempted,
	uploadReturned
} from '#lib/visualizer/upload-marks.js';
import type { StoredShot } from './model';

vi.mock('./series-store', () => ({
	putSeries: async () => {},
	getAllSeries: async () => new Map(),
	deleteSeries: async () => {},
	pruneSeriesTo: async () => {},
	clearAllSeries: async () => {}
}));

import { HistoryStore, uploadPending } from './store.svelte';

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

/** The row Visualizer holds for {@link row}: its start second, a settled duration. */
const remote = (): WireShot =>
	({
		id: 'v-1',
		clock: 1_699_999_970_000,
		duration_ms: 30_400,
		profile_title: null,
		final_weight_g: null,
		tag_list: []
	}) as unknown as WireShot;

const store = () => {
	localStorage.setItem('crema.history.v2', JSON.stringify([row()]));
	return new HistoryStore();
};

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	localStorage.clear();
	uploadReturned('s1');
});

describe('delete_during_upload_stays_deleted', () => {
	it('upload first: the id lands on the tombstone, and no remote delete was asked for', () => {
		const history = store();
		markUploadAttempted('s1');
		history.delete('s1');
		expect(history.all).toEqual([]);

		history.bindVisualizerId('s1', 'v-1');

		expect(history.get('s1')).toMatchObject({ visualizerId: 'v-1' });
		expect(history.get('s1')?.deletedAt).not.toBeNull();
		expect(history.all).toEqual([]);
		expect(readQueue().entries).toEqual([]);
		expect(uploadAttempted('s1')).toBe(false);
	});

	it('upload first, with the delete offered: the remote DELETE is queued', () => {
		const history = store();
		markUploadAttempted('s1');
		history.delete('s1', { deleteRemote: true });
		history.bindVisualizerId('s1', 'v-1');
		expect(readQueue().entries).toMatchObject([{ id: 's1', op: 'delete', visualizerId: 'v-1' }]);
	});

	it('pull first: the row binds to the tombstone instead of being added', () => {
		const history = store();
		markUploadAttempted('s1');
		history.delete('s1');

		const [action] = reconcileShots(history.rawAll, [remote()]);

		expect(action).toMatchObject({ kind: 'bind', localId: 's1', visualizerId: 'v-1' });
	});

	it('kill mid-POST: the mark outlives the tab', () => {
		const before = store();
		markUploadAttempted('s1');
		uploadReturned('s1'); // a new tab holds nothing in memory
		before.delete('s1');
		expect(reconcileShots(new HistoryStore().rawAll, [remote()])[0]).toMatchObject({ kind: 'bind' });
	});

	it('a parked tombstone carries its parking onto the remote delete', () => {
		const history = store();
		markUploadAttempted('s1');
		history.delete('s1', { deleteRemote: true });
		settleMarksForAccount('u1', 'u2');
		history.bindVisualizerId('s1', 'v-1');
		expect(readQueue().entries).toMatchObject([{ id: 's1', op: 'delete', parkedFor: 'u1' }]);
	});

	it('an id for a row already gone is kept out of the next pull', () => {
		const history = store();
		history.purgeTombstone('s1');
		history.bindVisualizerId('s1', 'v-1');
		expect(remoteKeptOut().has('v-1')).toBe(true);
	});


	it('a queued create counts as offered', () => {
		const history = store();
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });
		expect(uploadPending('s1')).toBe(true);
		history.delete('s1');
		expect(history.get('s1')?.deletedAt).not.toBeNull();
	});

	it('a shot never offered to Visualizer just goes', () => {
		const history = store();
		expect(uploadPending('s1')).toBe(false);
		history.delete('s1');
		expect(history.get('s1')).toBeUndefined();
	});
});
