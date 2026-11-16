/**
 * #310 P3w: a backup holds every row `crema-history` keeps, each with its
 * curve, from a cold start where no row holds one in memory.
 */
import { afterAll, beforeAll, beforeEach, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { HistoryDb, META_MIGRATION } from './history-db';
import { writeDoneMarker } from './store-marker';
import { summarize } from './summary';
import { freshStorage } from './idb-test-kit';
import { getHistoryStore } from './store.svelte';
import { buildBackupJsonl, backupContents } from '#lib/backup/index.js';
import { p3RowsOf } from '#lib/history/p3-rows.js';

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	freshStorage();
});

it('a backup from a cold start includes every row, whole', async () => {
	const shots = Array.from({ length: 310 }, (_, i) => realShot(i, realSamples(5, i % 60)));
	const db = new HistoryDb({ expectExisting: () => false });
	await db.putShots(
		shots.map((shot) => ({ shot, details: true })),
		summarize
	);
	await db.setMeta(META_MIGRATION, { state: 'done', sourceHash: '0:0', sourceIds: shots.map((s) => s.id) });
	db.close();
	writeDoneMarker(1, shots.length);

	const history = getHistoryStore();
	await history.whenReady();
	expect(p3RowsOf(history).live).toHaveLength(310);
	// From a cold start no row holds a curve (P4w): the
	// backup loads each one whole.
	for (const row of p3RowsOf(history).raw) expect('samples' in row.record).toBe(false);
	expect(backupContents().shots).toBe(310);

	const built = (await buildBackupJsonl('test'))!;
	const lines = built.jsonl
		.split('\n')
		.filter(Boolean)
		.map((l) => JSON.parse(l) as { kind: string; id?: string; record?: { samples: unknown[] } });
	const shotLines = lines.filter((l) => l.kind === 'shot');
	expect(shotLines).toHaveLength(310);
	expect(built.contents.shots).toBe(310);
	expect(built.contents.noCurves).toBe(0);
	for (const l of shotLines) expect(l.record!.samples, l.id).toHaveLength(5);
}, 60_000);
