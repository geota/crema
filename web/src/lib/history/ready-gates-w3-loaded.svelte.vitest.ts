/**
 * #310 W3, rows in (the other half is `ready-gates-w3.svelte.vitest.ts`):
 * the P3 spec's `ready` table, row by row, now that the readers ask
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

describe('rows in, the index silent: every reader acts on P3w’s answer', () => {
	let asked: string[];
	let history: HistoryStore;
	let reads: HistoryReads;
	const shots = (): StoredShot[] =>
		Array.from({ length: 12 }, (_, i) => ({ ...realShot(i, realSamples(4, i)), pinned: i === 1 ? true : undefined }) as StoredShot);

	beforeEach(async () => {
		asked = [];
		freshStorage();
		setHistoryStoreDefaultsForTest({ locks: null, channel: null, visibility: false, estimate: async () => undefined });
		await seedP3(shots());
		history = getHistoryStore();
		await settled(history);
		reads = new HistoryReads({ store: () => history, db: () => silent(asked), waitMs: 30 });
		setHistoryReadsForTest(reads);
	});

	it('each question: P3w’s answer, after the wait', async () => {
		for (const q of questions()) {
			const got = await reads.answer(q);
			expect(q.differ(got, q.p3(p3RowsOf(history))), q.key).toBeNull();
		}
		expect(await reads.answer(liveRows())).toHaveLength(12);
		expect(await reads.answer(liveCount({ pinnedOnly: true }))).toBe(1);
		expect(asked.length).toBeGreaterThan(0);
	});

	it('capture prune: P3w’s plan, the pinned shot’s capture spared', async () => {
		const store = getCaptureStore();
		for (const i of [0, 1, 2]) await store.put(realShot(i).id, []);
		await store.put('shot:not-in-history', []);
		const doomed = await capturesOverLimit(history, 'off');
		const plain = await capturesOverLimit(history, 'off', new HistoryReads({ store: () => history, db: () => null }));
		expect(doomed.sort()).toEqual(plain.sort());
		expect(doomed.sort()).toEqual([realShot(0).id, realShot(2).id].sort());
	});

	it('sync and the upload passes read P3w and never ask the index', () => {
		asked.length = 0;
		expect(syncRows(history)).toHaveLength(12);
		expect(liveSyncRows(history)).toHaveLength(12);
		expect(rowBoundTo(history, 'vis-none')).toBeUndefined();
		expect(asked).toEqual([]);
	});

	it('a backup holds every row, and a restore’s local state sees them', async () => {
		const built = await buildBackupJsonl('test');
		expect(built?.contents.shots).toBe(12);
		expect(backupContents().shots).toBe(12);
		const local = await buildLocalState(undefined);
		expect(local.shots.length).toBe(12);
	});

	it('a watched answer is P3w’s, once the wait is over', async () => {
		let watched: { value: number; answered: boolean } | null = null;
		const stop = $effect.root(() => {
			watched = reads.watch(() => liveCount(), -1);
		});
		try {
			flushSync();
			for (let i = 0; i < 100 && !watched!.answered; i++) await new Promise((r) => setTimeout(r, 10));
			expect(watched!).toMatchObject({ value: 12, answered: true });
		} finally {
			stop();
		}
	});
});
