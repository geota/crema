/**
 * #420 review: recovering a store lost under the tab (#310 P4w).
 *
 * - M1: a recovery in the same tab keeps the yield, peaks and chart of every
 *   row the tab held light (probes A and B): those go as rows only, never as
 *   a details write that would work a summary out from no curve.
 * - S2: a pulled curve or recipe not yet written survives the loss, the
 *   cache and the recovery, whether the row was opened first or not
 *   (probe C).
 * - A curve waiting to be written is never swapped for the store's copy:
 *   not by other loads churning the 16 kept shots, nor by a reload.
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { HistoryDb, META_MIGRATION, type MigrationRecord } from './history-db';
import type { StoredShot } from './model';
import { writeDoneMarker } from './store-marker';
import { summarize } from './summary';
import { decodeSpark } from './spark';
import { LOADED_SHOTS_KEPT } from './store.svelte';
import { boot, freshStorage, stored, writeOldCopy, LEGACY_KEY } from './idb-test-kit';
import { localShotsOf } from '#lib/restore/engine.js';

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
let idb: IDBFactory;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	idb = freshStorage();
});

const small = (n: number, from = 0) => Array.from({ length: n }, (_, i) => realShot(from + i, realSamples(4, (from + i) % 60)));
const until = async (ok: () => boolean, ms = 2_000) => {
	const end = Date.now() + ms;
	while (!ok()) {
		if (Date.now() > end) throw new Error('timed out');
		await new Promise((r) => setTimeout(r, 2));
	}
};

async function seedStore(shots: readonly StoredShot[]): Promise<void> {
	const db = new HistoryDb({ expectExisting: () => false });
	await db.putShots(
		shots.map((shot) => ({ shot, details: true })),
		summarize
	);
	await db.setMeta(META_MIGRATION, {
		state: 'done',
		sourceHash: '0:0',
		sourceIds: shots.map((s) => s.id),
		doneAt: 1
	} satisfies MigrationRecord);
	db.close();
	writeDoneMarker(1, shots.length);
	localStorage.removeItem(LEGACY_KEY);
}

function deleteHistoryDb(): Promise<void> {
	return new Promise<void>((resolve) => {
		const req = idb.deleteDatabase('crema-history');
		req.onsuccess = () => resolve();
		req.onblocked = () => resolve();
	});
}

/**
 * A private file system in memory: the snapshots' directory holds `files`.
 * As in a browser, `create` makes an empty file at once, and a writable lands
 * only on close. `failClose` makes the next close fail, as a full disk does;
 * `cutShort` makes the next close land half the bytes.
 */
function memoryRoot() {
	const files = new Map<string, { bytes: Uint8Array; at: number }>();
	let clock = 1_000;
	const control = { failClose: false, cutShort: false };
	const notFound = () => new DOMException('none', 'NotFoundError');
	const dir = {
		async getFileHandle(name: string, opts?: { create?: boolean }) {
			if (!files.has(name) && !opts?.create) throw notFound();
			if (!files.has(name)) files.set(name, { bytes: new Uint8Array(0), at: ++clock });
			return {
				async createWritable() {
					const parts: Uint8Array[] = [];
					return {
						async write(p: BufferSource) {
							parts.push(new Uint8Array(p as ArrayBuffer));
						},
						async close() {
							if (control.failClose) {
								control.failClose = false;
								throw new DOMException('full', 'QuotaExceededError');
							}
							const total = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
							let o = 0;
							for (const p of parts) {
								total.set(p, o);
								o += p.length;
							}
							const bytes = control.cutShort ? total.slice(0, total.length >> 1) : total;
							control.cutShort = false;
							files.set(name, { bytes, at: ++clock });
						},
						async abort() {}
					};
				},
				async getFile() {
					const f = files.get(name);
					if (!f) throw notFound();
					return { lastModified: f.at, arrayBuffer: async () => f.bytes.slice().buffer };
				}
			};
		},
		async removeEntry(name: string) {
			if (!files.delete(name)) throw notFound();
		},
		async *keys() {
			yield* [...files.keys()];
		}
	};
	let made = false;
	const root = {
		async getDirectoryHandle(name: string, opts?: { create?: boolean }) {
			if (name !== 'crema-history-snapshot') throw notFound();
			if (!made && !opts?.create) throw notFound();
			made = true;
			return dir;
		}
	};
	return { files, control, snapshotRoot: async () => root as unknown as FileSystemDirectoryHandle };
}


describe('a recovery in the same tab keeps what memory knew of each row (M1)', () => {
	it('probe A: from the daily copy, every row keeps its yield, peaks and chart', async () => {
		const { files, snapshotRoot } = memoryRoot();
		const shots = small(6);
		await seedStore(shots);
		const store = await boot({ snapshotRoot });
		store.setRating(shots[0].id, 3);
		await store.flushed();
		await until(() => files.size === 1);
		const before = (await stored()).rows;
		await deleteHistoryDb();
		store.setRating(shots[1].id, 2);
		await store.flushed();
		expect(store.health).toBe('lost');
		expect(await store.recoverSnapshot()).toBe(true);
		await store.flushed();
		const { rows } = await stored();
		for (const s of shots) {
			const was = before.get(s.id)!.summary;
			const now = rows.get(s.id)!.summary;
			expect(now.finalWeight ?? null, s.id).toBe(was.finalWeight ?? null);
			expect(now.peakPressure, s.id).toBe(was.peakPressure);
			expect(now.spark, s.id).toBe(was.spark);
			// No curve is stored: a backfill or a merge restore can still fill it.
			expect(now.sampleCount, s.id).toBe(0);
			expect(store.get(s.id)!.summary.peakPressure).toBe(was.peakPressure);
		}
		// Memory's edit made after the loss went in too.
		expect(rows.get(shots[1].id)!.metadata.rating).toBe(2);
	});

	it('probe B: Start empty keeps every made shot’s yield and chart; the last 16 keep their curve', async () => {
		await writeOldCopy(small(5));
		const a = await boot();
		const made: string[] = [];
		for (let i = 0; i < 20; i++) {
			const s = a.recordCaptured({ ...realShot(100 + i, realSamples(4, i)), visualizerId: null } as unknown as Record<string, unknown>)!;
			made.push(s.id);
			await a.flushed();
		}
		const yields = new Map(made.map((id) => [id, a.get(id)!.summary.finalWeight ?? null]));
		await deleteHistoryDb();
		a.setRating(made[0], 4);
		await a.flushed();
		expect(a.health).toBe('lost');
		expect(await a.startEmpty()).toBe(true);
		await a.flushed();
		const { rows, details } = await stored();
		for (const id of made) {
			expect(rows.get(id)!.summary.finalWeight ?? null, id).toBe(yields.get(id));
			expect(decodeSpark(rows.get(id)!.summary.spark)?.machine, id).toBe(true);
		}
		const curves = made.filter((id) => (details.get(id)?.samples.length ?? 0) > 0);
		expect(curves).toEqual(made.slice(-LOADED_SHOTS_KEPT));
		// A shot without its curve says so, so a backfill can fill it.
		const bare = rows.get(made[0])!;
		expect(bare.summary.sampleCount).toBe(0);
		const back = await boot();
		back.backfillTelemetry(made[0], realSamples(5, 9), 40_000);
		await back.flushed();
		expect((await stored()).details.get(made[0])!.samples).toHaveLength(5);
		expect(back.get(made[0])!.summary.sampleCount).toBe(5);
	});
});

describe('a pulled curve not yet written survives the loss (S2)', () => {
	for (const opened of [false, true]) {
		it(`probe C: a backfill pending when the store goes is stored by the recovery (opened first: ${opened})`, async () => {
			const stub = { ...realShot(7, []), id: 'shot:stub-7', visualizerId: 'v-7' } as StoredShot;
			await seedStore([...small(3).map((s) => ({ ...s, visualizerId: null })), stub]);
			const a = await boot();
			if (opened) await a.loadShot(stub.id);
			await deleteHistoryDb();
			a.applyRemote(stub.id, { backfillSamples: true, remoteVersion: 99 } as never, { samples: realSamples(6, 3), durationMs: 30_000 });
			await a.flushed();
			expect(a.health).toBe('lost');
			expect(a.unsavedRows().find((s) => s.id === stub.id)!.record.samples).toHaveLength(6);
			expect(await a.startEmpty()).toBe(true);
			await a.flushed();
			const { rows, details } = await stored();
			expect(details.get(stub.id)!.samples).toHaveLength(6);
			expect(rows.get(stub.id)!.visualizerUpdatedAt).toBe(99);
			expect(rows.get(stub.id)!.summary.sampleCount).toBe(6);
		});
	}

	it('a pulled curve waiting on a full disk outlasts other loads churning the kept shots, and a reload', async () => {
		const stub = { ...realShot(7, []), id: 'shot:stub-8', visualizerId: 'v-8' } as StoredShot;
		const others = small(LOADED_SHOTS_KEPT + 4).map((s) => ({ ...s, visualizerId: null }));
		await seedStore([...others, stub]);
		let full = false;
		const a = await boot({
			dbFault: (op) => {
				if (full && op === 'write') throw new DOMException('full', 'QuotaExceededError');
			}
		});
		await a.loadShot(stub.id);
		full = true;
		a.backfillTelemetry(stub.id, realSamples(6, 3), 30_000);
		await a.flushed();
		expect(a.unsavedShots).toContain(stub.id);
		// Every kept shot is pushed out, and the rows read again.
		for (const s of others) await a.loadShot(s.id);
		await a.reloadRows();
		expect((await a.loadShot(stub.id))!.record.samples).toHaveLength(6);
		// Space comes back; the next save takes it.
		full = false;
		a.setRating(others[0].id, 1);
		await a.flushed();
		expect((await stored()).details.get(stub.id)!.samples).toHaveLength(6);
		expect(a.unsavedShots).not.toContain(stub.id);
	});
});

describe('the restore plan over a row whose count is unknown (N4)', () => {
	it('counts it as holding a curve, so a merge never fills over it', () => {
		const [row] = localShotsOf([{ id: 'r', summary: { v: 1, brewSampleCount: 0, peakPressure: 0, peakTemp: 0 }, record: { duration: 1 }, metadata: {} } as never]);
		expect(row.hasSamples).toBe(true);
		expect(localShotsOf([{ id: 'z', summary: { v: 1, sampleCount: 0, brewSampleCount: 0, peakPressure: 0, peakTemp: 0 }, record: { duration: 1 }, metadata: {} } as never])[0].hasSamples).toBe(false);
	});
});
