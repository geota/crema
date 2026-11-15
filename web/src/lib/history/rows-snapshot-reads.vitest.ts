/**
 * #421: the daily copy reads the store in yielding batches, and its profiles
 * without the curves on the main thread. The copy holds the same rows, field
 * by field, as the one read whole before; a write that lands between batches
 * is in the copy, or in the next day's.
 */
import { beforeAll, beforeEach, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import type { StoredShot } from './model';
import { HistoryDb, META_MIGRATION, type MigrationRecord, type RowRecord } from './history-db';
import { summarize } from './summary';
import { SNAPSHOT_CHUNK, readAllRowsSnapshots, readRowsSnapshot, snapshotLines, writeRowsSnapshot, type SnapshotRow } from './rows-snapshot';
import { answerProfiles, readProfiles, workerProfileReader, type ProfileAsk, type WorkerLike } from './snapshot-profiles';
import { memoryFs } from './archive-test-kit';
import { boot, freshStorage, rawDb } from './idb-test-kit';
import { writeDoneMarker } from './store-marker';

beforeAll(async () => {
	await initTestWasm();
});
beforeEach(() => {
	freshStorage();
});

const DAY = 24 * 3_600_000;
const NOW = Date.UTC(2026, 10, 15, 12);

/** Real-shaped shots, with a few profiles, and some with none. */
function shots(n: number, from = 0): StoredShot[] {
	return Array.from({ length: n }, (_, k) => {
		const i = from + k;
		const s = realShot(i, realSamples(4, i % 60));
		const profile = i % 11 === 0 ? null : { ...(s.profile as object), title: `Profile ${i % 5}` };
		return { ...s, profile };
	});
}

async function seed(list: readonly StoredShot[]): Promise<void> {
	const db = new HistoryDb({ expectExisting: () => false });
	for (let i = 0; i < list.length; i += 500) {
		await db.putShots(
			list.slice(i, i + 500).map((shot) => ({ shot, details: true })),
			summarize
		);
	}
	db.close();
}

/** Drop the details of `ids`, as a row-only write leaves a row. */
async function dropDetails(ids: readonly string[]): Promise<void> {
	const db = rawDb();
	const tx = (await db.open()).transaction('details', 'readwrite');
	for (const id of ids) tx.objectStore('details').delete(id);
	await new Promise<void>((resolve, reject) => {
		tx.oncomplete = () => resolve();
		tx.onerror = () => reject(tx.error);
	});
	db.close();
}

/** The copy's lines as the store read them before #421: every row at once, then details by 100. */
async function linesBefore(db: HistoryDb): Promise<SnapshotRow[]> {
	const rows = await db.allRows();
	const lines: SnapshotRow[] = [];
	for (let i = 0; i < rows.length; i += SNAPSHOT_CHUNK) {
		const part = rows.slice(i, i + SNAPSHOT_CHUNK);
		const details = await db.detailsOf(part.map((r) => r.id));
		for (const r of part) lines.push({ ...r, profile: details.get(r.id)?.profile ?? null });
	}
	return lines;
}

/** A worker over its own connection, answering through a structured clone as `postMessage` does. */
function fakeWorker(opts: { failOn?: number; silentOn?: number; crashOn?: number } = {}) {
	const made: WorkerLike[] = [];
	const asks: number[] = [];
	let terminated = 0;
	const make = (): WorkerLike => {
		const own = rawDb();
		const w: WorkerLike = {
			onmessage: null,
			onerror: null,
			onmessageerror: null,
			postMessage(m: ProfileAsk) {
				const ask = structuredClone(m);
				asks.push(ask.ids.length);
				const k = asks.length;
				if (k === opts.silentOn) return;
				if (k === opts.crashOn) {
					queueMicrotask(() => w.onerror?.({ preventDefault() {} }));
					return;
				}
				void (k === opts.failOn ? Promise.resolve({ n: ask.n, error: 'read failed' }) : answerProfiles(own, ask)).then((a) =>
					setTimeout(() => w.onmessage?.({ data: structuredClone(a) }), 0)
				);
			},
			terminate() {
				terminated += 1;
				own.close();
			}
		};
		made.push(w);
		return w;
	};
	return { make, asks, made, terminated: () => terminated };
}

it('a copy read in batches equals the copy read before, field by field, at 5,000 rows', async () => {
	const list = shots(5_000);
	await seed(list);
	await dropDetails([list[7].id, list[4_321].id]);
	const db = rawDb();
	const before = await linesBefore(db);
	expect(before).toHaveLength(5_000);
	expect(before.filter((l) => l.profile === null).length).toBeGreaterThan(400);

	const allRows = vi.spyOn(db, 'allRows');
	const rowsOf = vi.spyOn(db, 'rowsOf');
	const detailsOf = vi.spyOn(db, 'detailsOf');
	// Read here, from the details.
	const here = await snapshotLines(db);
	expect(here).toStrictEqual(before);
	expect(allRows).not.toHaveBeenCalled();
	expect(Math.max(...rowsOf.mock.calls.map(([ids]) => ids.length))).toBeLessThanOrEqual(SNAPSHOT_CHUNK);
	expect(rowsOf).toHaveBeenCalledTimes(5_000 / SNAPSHOT_CHUNK);

	// And from a worker: the main thread reads no details, so no curves.
	detailsOf.mockClear();
	const w = fakeWorker();
	const reader = workerProfileReader(w.make, (ids) => readProfiles(db, ids));
	const off = await snapshotLines(db, reader.profilesOf);
	reader.close();
	expect(off).toStrictEqual(before);
	expect(detailsOf).not.toHaveBeenCalled();
	expect(w.made).toHaveLength(1);
	expect(Math.max(...w.asks)).toBeLessThanOrEqual(SNAPSHOT_CHUNK);
	expect(w.terminated()).toBe(1);

	// The files written from each are the same bytes, but for the gzip header's time.
	const files = [memoryFs(), memoryFs()];
	expect(await writeRowsSnapshot(before, NOW, files[0].fsRoot, null)).toBe(true);
	expect(await writeRowsSnapshot(off, NOW, files[1].fsRoot, null)).toBe(true);
	const bytes = async (fs: ReturnType<typeof memoryFs>) => {
		const dir = await (await fs.fsRoot())!.getDirectoryHandle('crema-history-snapshot');
		const names: string[] = [];
		for await (const n of (dir as unknown as { keys(): AsyncIterable<string> }).keys()) names.push(n);
		const b = new Uint8Array(await (await (await dir.getFileHandle(names[0])).getFile()).arrayBuffer());
		b.fill(0, 4, 8);
		return b;
	};
	expect(await bytes(files[1])).toEqual(await bytes(files[0]));
	expect((await readRowsSnapshot(files[1].fsRoot))!.rows).toStrictEqual(JSON.parse(JSON.stringify(before)));
	db.close();
}, 180_000);

it('a write between batches is in the copy, or in the next day’s', async () => {
	const list = shots(350);
	await seed(list);
	const db = rawDb();
	const other = new HistoryDb({ expectExisting: () => true });
	const ids = list.map((s) => s.id);
	const [readEarly, readLate, goneLate] = [ids[10], ids[300], ids[310]];
	const added = shots(1, 9_000)[0];
	let batch = 0;
	const source = {
		rowIds: () => db.rowIds(),
		detailsOf: (x: readonly string[]) => db.detailsOf(x),
		async rowsOf(x: readonly string[]) {
			batch += 1;
			if (batch === 3) {
				// Another tab, between the second and the third batch.
				const edit = (id: string, notes: string) => ({ shot: { ...list[ids.indexOf(id)], metadata: { ...list[0].metadata, notes } }, details: true });
				await other.putShots([edit(readEarly, 'edited after its batch'), edit(readLate, 'edited before its batch'), { shot: added, details: true }], summarize);
				await other.delete([goneLate]);
			}
			return db.rowsOf(x);
		}
	};
	const today = await snapshotLines(source);
	const byId = new Map(today.map((l) => [l.id, l]));
	// Every row stored throughout is there.
	for (const id of ids) if (id !== goneLate) expect(byId.has(id), id).toBe(true);
	expect(byId.get(readEarly)!.metadata.notes).not.toBe('edited after its batch');
	expect(byId.get(readLate)!.metadata.notes).toBe('edited before its batch');
	expect(byId.has(goneLate)).toBe(false);
	expect(byId.has(added.id)).toBe(false);

	// The next day's copy picks up what today's missed.
	const tomorrow = await snapshotLines(db);
	const next = new Map(tomorrow.map((l) => [l.id, l]));
	expect(next.get(readEarly)!.metadata.notes).toBe('edited after its batch');
	expect(next.get(added.id)!.profile).toStrictEqual(added.profile);
	expect(tomorrow).toStrictEqual(await linesBefore(db));

	// And "Recover from all copies" takes the newest written.
	const fs = memoryFs();
	expect(await writeRowsSnapshot(today, NOW, fs.fsRoot, null)).toBe(true);
	expect(await writeRowsSnapshot(tomorrow, NOW + DAY, fs.fsRoot, null)).toBe(true);
	const all = (await readAllRowsSnapshots(fs.fsRoot))!.rows;
	const merged = new Map(all.map((r) => [r.id, r as RowRecord]));
	expect(merged.get(readEarly)!.metadata.notes).toBe('edited after its batch');
	expect(merged.has(added.id)).toBe(true);
	// A row deleted before its turn is in neither: the delete landed first.
	expect(merged.has(goneLate)).toBe(false);
	other.close();
	db.close();
});

it('the worker’s failures read the rest here, and the copy is whole', async () => {
	const list = shots(450);
	await seed(list);
	const db = rawDb();
	const before = await linesBefore(db);
	for (const opts of [{ failOn: 2 }, { crashOn: 3 }, { silentOn: 1 }]) {
		const w = fakeWorker(opts);
		const here = vi.fn((ids: readonly string[]) => readProfiles(db, ids));
		const reader = workerProfileReader(w.make, here, 50);
		expect(await snapshotLines(db, reader.profilesOf), JSON.stringify(opts)).toStrictEqual(before);
		reader.close();
		const at = (opts as { failOn?: number }).failOn ?? (opts as { crashOn?: number }).crashOn ?? (opts as { silentOn?: number }).silentOn!;
		// From the batch that failed on, every batch is read here; the worker is stopped once.
		expect(here).toHaveBeenCalledTimes(5 - at + 1);
		expect(w.asks).toHaveLength(at);
		expect(w.terminated()).toBe(1);
	}
	// No worker to be had: all read here.
	for (const make of [() => null, () => { throw new Error('no workers'); }]) {
		const here = vi.fn((ids: readonly string[]) => readProfiles(db, ids));
		const reader = workerProfileReader(make, here);
		expect(await snapshotLines(db, reader.profilesOf)).toStrictEqual(before);
		expect(here).toHaveBeenCalledTimes(5);
	}
	db.close();
});

it('a worker that finds the database gone answers with an error, and creates none', async () => {
	const idb = freshStorage();
	const db = rawDb();
	const a = await answerProfiles(db, { n: 1, ids: ['shot:x'] });
	expect(a).toMatchObject({ n: 1, error: expect.any(String) });
	expect((await idb.databases()).map((d) => d.name)).not.toContain('crema-history');
});

it('the store’s daily copy reads its profiles in the worker, and holds what it held before', async () => {
	const list = shots(260);
	await seed(list);
	const setup = new HistoryDb({ expectExisting: () => true });
	await setup.setMeta(META_MIGRATION, { state: 'done', sourceHash: '0:0', sourceIds: list.map((s) => s.id), doneAt: 1 } satisfies MigrationRecord);
	setup.close();
	writeDoneMarker(1, list.length);
	const fs = memoryFs();
	const w = fakeWorker();
	const store = await boot({ ...fs.opts, snapshotWorker: w.make, now: () => NOW });
	store.setRating(list[3].id, 5);
	await store.flushed();
	const end = Date.now() + 10_000;
	while (fs.copies() === 0 && Date.now() < end) await new Promise((r) => setTimeout(r, 5));
	expect(fs.copies()).toBe(1);
	const db = rawDb();
	const copy = (await readRowsSnapshot(fs.fsRoot))!.rows;
	expect(copy).toStrictEqual(JSON.parse(JSON.stringify(await linesBefore(db))));
	expect(copy.find((r) => r.id === list[3].id)!.metadata.rating).toBe(5);
	expect(w.made).toHaveLength(1);
	expect(w.asks).toHaveLength(3);
	expect(w.terminated()).toBe(1);
	db.close();
});
