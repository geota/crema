/**
 * #310 W3: the P3 spec's `ready` table, row by row, now that the readers ask
 * queries, with an index that hasn't answered (and never does). Each reader
 * either waits for the rows and skips, or, once they're in, acts on P3w's
 * answer: never on an empty one because the index stayed silent.
 *
 * | Reader | Reads now | Rows not in | Rows in, index silent |
 * | --- | --- | --- | --- |
 * | capture prune | `rowsNamed` | deletes nothing | P3w's plan |
 * | upload queue, Decent retry and backlog, Visualizer backlog, reconcile, patch | `sync-view` (P3w) | their gates, unchanged | P3w's rows, no index read |
 * | file import | P3w (`importShotFile`) | imports nothing | unchanged |
 * | backup, restore | the P3 source | refuse | P3w's rows |
 * | backup count, Sharing's counts | the P3 source, `liveCount` | 0 / disabled | P3w's count |
 * | Bean import dedup | `liveRows` | "still loading" | P3w's keys |
 * | Brew Log seeds | `liveRows` | wait | P3w's rows |
 * | dashboard, bean drawer, context card | `watch` | empty, then fills | P3w's answer |
 */
import 'fake-indexeddb/auto';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { flushSync } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import type { HistoryDbClient } from '#lib/history-db/client.js';
import { seedP3 } from '#lib/history-db/w2-kit.js';
import { getCaptureStore } from '#lib/capture/store.js';
import { capturesOverLimit } from '#lib/capture/prune.js';
import { backupContents, buildBackupJsonl } from '#lib/backup/index.js';
import { buildLocalState } from '#lib/restore/engine.js';
import { freshStorage, settled } from './idb-test-kit';
import { p3RowsOf } from './p3-rows';
import {
	bagRows,
	everyId,
	HistoryReads,
	liveCount,
	liveRows,
	newestRow,
	rowsNamed,
	setHistoryReadsForTest,
	type Query
} from './reads.svelte';
import { getHistoryStore, setHistoryStoreDefaultsForTest, type HistoryStore } from './store.svelte';
import { liveSyncRows, rowBoundTo, syncRows } from './sync-view';
import type { StoredShot } from './model';

/** An index client that says it answers, and never does. */
const silent = (reads: string[]) =>
	({
		status: { health: 'ok', reason: '', message: null, owner: null, ownerHere: true },
		onStatus: () => () => {},
		read: (name: string) => {
			reads.push(name);
			return new Promise(() => {});
		}
	}) as unknown as HistoryDbClient;

const BAG = 'bean:019e5fb5-0000-7000-8000-000000000000';

/** Every question a consumer asks. */
const questions = (): Query<unknown>[] =>
	[bagRows(BAG), liveRows(), newestRow(), liveCount(), liveCount({ pinnedOnly: true }), everyId(), rowsNamed([realShot(0).id])] as Query<unknown>[];

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
	setHistoryReadsForTest(null);
});

describe('rows not in, the index silent: every reader waits, then skips', () => {
	let asked: string[];
	beforeEach(() => {
		asked = [];
		freshStorage({ migrationFault: () => new Promise(() => {}), readyTimeoutMs: 30 });
		setHistoryReadsForTest(new HistoryReads({ store: getHistoryStore, db: () => silent(asked), waitMs: 30 }));
	});

	it('no question answers, and none answers empty', async () => {
		const history = getHistoryStore();
		expect(history.loaded).toBe(false);
		const reads = new HistoryReads({ store: () => history, db: () => silent(asked), waitMs: 30 });
		for (const q of questions()) {
			expect(reads.now(q), q.key).toBeUndefined();
			await expect(reads.answer(q), q.key).rejects.toMatchObject({ name: 'HistoryNotReady' });
		}
		// Nothing was asked of the index over rows that aren't in.
		expect(asked).toEqual([]);
	});

	it('a watched answer stays at its start, never "none"', () => {
		const history = getHistoryStore();
		const reads = new HistoryReads({ store: () => history, db: () => silent(asked), waitMs: 30 });
		let watched: { value: number; answered: boolean } | null = null;
		const stop = $effect.root(() => {
			watched = reads.watch(() => liveCount(), -1);
		});
		flushSync();
		expect(watched!).toMatchObject({ value: -1, answered: false });
		stop();
	});

	it('capture prune deletes no capture', async () => {
		await getCaptureStore().put('a-capture', []);
		expect(await capturesOverLimit(getHistoryStore(), 'off')).toEqual([]);
		expect(await getCaptureStore().keys()).toEqual(['a-capture']);
	});

	it('backups refuse and count none; a restore plans nothing', async () => {
		await expect(buildBackupJsonl('test')).rejects.toThrow(/still loading/);
		expect(backupContents().shots).toBe(0);
		await expect(buildLocalState(undefined)).rejects.toMatchObject({ name: 'HistoryNotReady' });
	});
});
