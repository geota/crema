/**
 * #310 P3w: the move into `crema-history` (SPEC §6), on the real store over
 * an in-memory IndexedDB. The old copy is written as an older build leaves it,
 * the store boots as a page load does, and what it holds is read back from
 * storage, not from memory.
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { FakeLocks } from '#lib/testing/fake-locks.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import type { StoredShot } from './model';
import { META_MIGRATION, type MigrationRecord } from './history-db';
import { TestKill, VerifyFailed, realLegacySource, verifyCopy, sameValue } from './migrate';
import { readStoreMarker, STORE_MARKER_KEY, RETRY_AFTER_MS } from './store-marker';
import { coerceStoredShot } from './store.svelte';
import {
	LEGACY_KEY,
	boot,
	freshStorage,
	oldCopyBytes,
	plain,
	rawDb,
	stored,
	whole,
	writeOldCopy
} from './idb-test-kit';

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
let idb: IDBFactory | undefined;

beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	idb = freshStorage();
});

/** `n` real-shaped shots, each with `samples` real samples. */
function shots(n: number, samples = 180): StoredShot[] {
	return Array.from({ length: n }, (_, i) => realShot(i, samples === 180 ? undefined : realSamples(samples, i % 60)));
}

/** Every row and sample the store holds, whole, against the source. */
async function expectEveryField(source: readonly StoredShot[]): Promise<void> {
	const { rows, details } = await stored();
	expect(rows.size).toBe(source.length);
	expect(details.size).toBe(source.length);
	for (const s of source) {
		const c = whole(s);
		const row = rows.get(s.id)!;
		const det = details.get(s.id)!;
		const { summary, ...fields } = row;
		const { profile, brewSeries, record, ...rest } = c;
		expect(plain(fields), s.id).toEqual(plain({ ...rest, record: { duration: record.duration, samples: [] } }));
		expect(det.samples, s.id).toEqual(record.samples);
		expect(det.profile ?? null, s.id).toEqual(profile ?? null);
		expect(det.brewSeries ?? null, s.id).toEqual(brewSeries ?? null);
		expect(summary.sampleCount, s.id).toBe(record.samples.length);
	}
}

describe('migration', () => {
	it('moves a 300-row real-shaped history, every field and sample, and leaves the old copy as it was', async () => {
		const source = shots(300);
		await writeOldCopy(source);
		const before = await oldCopyBytes();

		const store = await boot();
		expect(store.health).toBe('ok');
		expect(readStoreMarker()).toMatchObject({ kind: 'marker', marker: { state: 'done', rows: 300 } });
		await expectEveryField(source);
		// In memory too: the view's rows whole, as the source.
		const mem = await store.loadMany(source.map((s) => s.id));
		for (const [i, s] of source.entries()) expect(sameValue(mem[i], whole(s)), s.id).toBe(true);
		expect(await oldCopyBytes()).toEqual(before);

		// After edits and deletes in the new store, the old copy is still untouched.
		store.setRating(source[0].id, 1);
		store.delete(source[1].id);
		await store.flushed();
		expect(await oldCopyBytes()).toEqual(before);
	}, 60_000);

	it('moves a 5,000-row history: every row kept, 300 shown, every field and sample compared', async () => {
		const source = shots(5_000, 6);
		await writeOldCopy(source);
		const store = await boot();
		expect(store.health).toBe('ok');
		expect(store.all).toHaveLength(300);
		expect(store.rawAll).toHaveLength(5_000);
		await expectEveryField(source);
	}, 240_000);

	it('copies a row with its samples inline in the blob, and the inline ones win', async () => {
		const a = realShot(1, realSamples(8));
		const b = realShot(2, realSamples(8, 5));
		await writeOldCopy([a, b], new Set([a.id]));
		// An older curve for `a` in the series database too: the blob's wins.
		const { putSeries } = await import('./series-store');
		await putSeries(a.id, realSamples(3, 40));
		await boot();
		const { details } = await stored();
		expect(details.get(a.id)!.samples).toEqual(a.record.samples);
		expect(details.get(b.id)!.samples).toEqual(b.record.samples);
	});

	it('keeps the first of a repeated id, and a row with no curve has none', async () => {
		const a = realShot(1, realSamples(4));
		const again = { ...realShot(1, realSamples(4, 9)), metadata: { ...a.metadata, notes: 'second' } };
		const bare = realShot(2, []);
		await writeOldCopy([a, again, bare]);
		await boot();
		const { rows, details } = await stored();
		expect([...rows.keys()].sort()).toEqual([a.id, bare.id].sort());
		expect(rows.get(a.id)!.metadata.notes).toBe(a.metadata.notes);
		expect(details.get(bare.id)!.samples).toEqual([]);
		expect(rows.get(bare.id)!.summary.sampleCount).toBe(0);
	});

	it('keeps a row with no id aside and moves the rest', async () => {
		const a = realShot(1, realSamples(4));
		localStorage.setItem(LEGACY_KEY, JSON.stringify([{ ...a, record: { ...a.record, samples: [] } }, { completedAt: 5 }, 7]));
		const { putSeries } = await import('./series-store');
		await putSeries(a.id, a.record.samples);
		await boot();
		const { rows } = await stored();
		expect([...rows.keys()]).toEqual([a.id]);
		expect(JSON.parse(localStorage.getItem(`${LEGACY_KEY}.skipped`)!)).toHaveLength(2);
	});

	it.each([
		['corrupt', '{not json'],
		['non-array', '{"a":1}']
	])('a %s blob is kept aside, the store starts done and empty, the old series are left alone', async (_k, blob) => {
		const { putSeries } = await import('./series-store');
		await putSeries('lost-row', realSamples(4));
		localStorage.setItem(LEGACY_KEY, blob);
		const series = (await oldCopyBytes()).series;
		const store = await boot();
		expect(store.health).toBe('ok');
		expect(store.rawAll).toEqual([]);
		expect(localStorage.getItem(`${LEGACY_KEY}.corrupt`)).not.toBeNull();
		expect((await oldCopyBytes()).series).toBe(series);
		expect((await stored()).meta).toMatchObject({ state: 'done', sourceIds: [] });
	});

	it('a run that finds the store done never clears it, and a second boot changes nothing', async () => {
		const source = shots(30, 4);
		await writeOldCopy(source);
		const first = await boot();
		first.setRating(source[2].id, 2);
		await first.flushed();
		const after = await stored();
		await boot();
		expect(await stored()).toEqual(after);
		// The marker went missing; `meta` says done: the marker comes back, nothing is cleared.
		localStorage.removeItem(STORE_MARKER_KEY);
		const third = await boot();
		expect(third.health).toBe('ok');
		expect(readStoreMarker().kind).toBe('marker');
		expect(await stored()).toEqual(after);
	});

	it('two tabs on the lock: one copies, the other opens what it copied', async () => {
		const source = shots(60, 4);
		await writeOldCopy(source);
		const locks = new FakeLocks();
		const clears: string[] = [];
		const fault = (op: string) => {
			if (op === 'clear') clears.push(op);
		};
		const [a, b] = await Promise.all([boot({ locks, dbFault: fault }), boot({ locks, dbFault: fault })]);
		expect(a.health).toBe('ok');
		expect(b.health).toBe('ok');
		expect(clears).toHaveLength(1);
		expect(b.rawAll.map((s) => s.id).sort()).toEqual(a.rawAll.map((s) => s.id).sort());
	});
});

describe('an interrupted run', () => {
	/** What a run that was never stopped leaves. */
	async function cleanRun(source: readonly StoredShot[]) {
		await writeOldCopy(source);
		await boot();
		return comparable(await stored());
	}
	const comparable = (s: Awaited<ReturnType<typeof stored>>) => ({
		rows: [...s.rows.values()].sort((a, b) => a.id.localeCompare(b.id)),
		details: [...s.details.values()].sort((a, b) => a.id.localeCompare(b.id)),
		meta: s.meta && { ...s.meta, doneAt: undefined }
	});
	const source = Array.from({ length: 60 }, (_, i) => realShot(i, realSamples(4, i % 50)));
	const points = ['read', 'estimate', 'clear', 'copying', 'batch:0', 'batch:1', 'batch:2', 'verify', 'done', 'marker'];

	it.each(points)('killed at %s: the next boot ends where a clean run ends', async (point) => {
		const want = await cleanRun(source);
		idb = freshStorage();
		await writeOldCopy(source);
		const before = await oldCopyBytes();
		let killedAt = () => {};
		const reached = new Promise<void>((r) => (killedAt = r));
		const killed = new (await import('./store.svelte')).HistoryStore({
			migrationFault: (p) => {
				if (p === point) {
					killedAt();
					throw new TestKill(p);
				}
			}
		});
		// The tab is gone at that point: its store never loads.
		await reached;
		await new Promise((r) => setTimeout(r, 10));
		expect(killed.loaded).toBe(false);
		const next = await boot();
		expect(next.health).toBe('ok');
		expect(comparable(await stored())).toEqual(want);
		expect(await oldCopyBytes()).toEqual(before);
	}, 30_000);

	it.each([1, 2, 3])('killed inside write transaction %i: nothing half-written, and the next boot ends the same', async (n) => {
		const want = await cleanRun(source);
		idb = freshStorage();
		await writeOldCopy(source);
		let writes = 0;
		let killedAt = () => {};
		const reached = new Promise<void>((r) => (killedAt = r));
		new (await import('./store.svelte')).HistoryStore({
			dbFault: (op) => {
				if (op === 'write' && ++writes === n) {
					killedAt();
					throw new TestKill(`write ${n}`);
				}
			}
		});
		await reached;
		await new Promise((r) => setTimeout(r, 10));
		const next = await boot();
		expect(next.health).toBe('ok');
		expect(comparable(await stored())).toEqual(want);
	}, 30_000);
});

describe('a failed run', () => {
	it('a series read error aborts: the store is clear, the legacy backend runs, the marker counts it', async () => {
		const source = shots(10, 4);
		await writeOldCopy(source);
		const real = realLegacySource();
		const store = await boot({
			source: { ...real, series: async () => Promise.reject(new Error('read failed')) }
		});
		expect(store.health).toBe('legacy');
		expect(store.rawAll).toHaveLength(10);
		const { rows, details, meta } = await stored();
		expect([rows.size, details.size, meta]).toEqual([0, 0, undefined]);
		expect(readStoreMarker()).toMatchObject({ kind: 'marker', marker: { state: 'failed', failures: 1 } });
	});

	it('quota on batch N leaves the store clear and the legacy backend working', async () => {
		const source = shots(60, 4);
		await writeOldCopy(source);
		let writes = 0;
		const store = await boot({
			dbFault: (op) => {
				if (op === 'write' && ++writes === 2) throw new DOMException('full', 'QuotaExceededError');
			}
		});
		expect(store.health).toBe('legacy');
		const { rows, details, meta } = await stored();
		expect([rows.size, details.size, meta]).toEqual([0, 0, undefined]);
		// Legacy works: a new shot lands in the old copy, as before.
		const made = store.recordCaptured(realShot(900, realSamples(4)) as unknown as Record<string, unknown>);
		expect(made).not.toBeNull();
		await new Promise((r) => setTimeout(r, 20));
		expect(JSON.parse(localStorage.getItem(LEGACY_KEY)!).map((r: StoredShot) => r.id)).toContain(made!.id);
		const { getSeriesOf } = await import('./series-store');
		expect((await getSeriesOf([made!.id])).size).toBe(1);
	});

	it('not enough free space: refused before anything is written', async () => {
		await writeOldCopy(shots(10, 4));
		const store = await boot({ estimate: async () => ({ quota: 100_000, usage: 99_000 }) });
		expect(store.health).toBe('legacy');
		expect(readStoreMarker()).toMatchObject({ marker: { state: 'failed' } });
		expect((await stored()).rows.size).toBe(0);
	});

	it('retries at the next two boots, then once a day', async () => {
		await writeOldCopy(shots(5, 4));
		let now = 1_800_000_000_000;
		let tries = 0;
		const failing = () =>
			boot({
				now: () => now,
				migrationFault: (p) => {
					if (p === 'read') tries += 1;
					if (p === 'verify') throw new Error('no');
				}
			});
		await failing();
		await failing();
		await failing();
		expect(tries).toBe(3);
		expect(readStoreMarker()).toMatchObject({ marker: { failures: 3, nextTryAt: now + RETRY_AFTER_MS } });
		// Waiting: the legacy backend, no try.
		now += RETRY_AFTER_MS - 1;
		expect((await failing()).health).toBe('legacy');
		expect(tries).toBe(3);
		// A day on, it tries again, and this time it works.
		now += 1;
		const store = await boot({ now: () => now });
		expect(store.health).toBe('ok');
		expect(readStoreMarker()).toMatchObject({ marker: { state: 'done' } });
	});
});

describe('verify', () => {
	const source = Array.from({ length: 30 }, (_, i) => realShot(i, realSamples(6, i)));
	const series = realLegacySource().series;

	/** A clean copy, then `tamper` on storage, then verify again. */
	async function verifyAfter(tamper: () => Promise<void>): Promise<VerifyFailed | null> {
		await writeOldCopy(source);
		await boot();
		await tamper();
		const db = rawDb();
		try {
			await verifyCopy(db, source.map((s) => coerceStoredShot(s as unknown as Record<string, unknown>)!).map((s) => ({ ...s, record: { ...s.record, samples: [] } })), series);
			return null;
		} catch (e) {
			if (e instanceof VerifyFailed) return e;
			throw e;
		} finally {
			db.close();
		}
	}
	/** Rewrite one stored record in `store`. */
	async function rewrite(store: 'rows' | 'details', id: string, edit: (r: Record<string, any>) => void): Promise<void> {
		const db = await rawDb().open();
		await new Promise<void>((resolve, reject) => {
			const tx = db.transaction(store, 'readwrite');
			const os = tx.objectStore(store);
			const get = os.get(id);
			get.onsuccess = () => {
				const r = get.result as Record<string, any>;
				edit(r);
				os.put(r);
			};
			tx.oncomplete = () => resolve();
			tx.onerror = () => reject(tx.error);
		});
		db.close();
	}
	const id = () => source[7].id;

	it('passes a clean copy', async () => {
		expect(await verifyAfter(async () => {})).toBeNull();
	});
	it('fails on a missing details record (ids)', async () => {
		expect(
			(
				await verifyAfter(async () => {
					const db = await rawDb().open();
					await new Promise<void>((resolve) => {
						const tx = db.transaction('details', 'readwrite');
						tx.objectStore('details').delete(id());
						tx.oncomplete = () => resolve();
					});
					db.close();
				})
			)?.check
		).toBe('ids');
	});
	it('fails on a row the source never had: the id sets are compared both ways (ids)', async () => {
		expect(
			(
				await verifyAfter(async () => {
					const extra = realShot(999, realSamples(3));
					const db = rawDb();
					await db.putShots([{ shot: extra, details: true }], (await import('./summary')).summarize);
					db.close();
				})
			)?.check
		).toBe('ids');
	});
	it('fails on a changed row field (row)', async () => {
		expect((await verifyAfter(() => rewrite('rows', id(), (r) => (r.metadata.notes = 'changed'))))?.check).toBe('row');
	});
	it('fails on a changed sample (details)', async () => {
		expect(
			(await verifyAfter(() => rewrite('details', id(), (r) => (r.samples[2].sample.groupPressure += 1))))?.check
		).toBe('details');
	});
	it('fails on a wrong sample count (sampleCount)', async () => {
		expect((await verifyAfter(() => rewrite('rows', id(), (r) => (r.summary.sampleCount = 5))))?.check).toBe('sampleCount');
	});
	it('fails on a summary whose weight gives another de-dup signature (signature)', async () => {
		expect(
			(await verifyAfter(() => rewrite('rows', id(), (r) => {
				r.summary.finalWeight = 99;
				r.summary.peakWeight = 99;
			})))?.check
		).toBe('signature');
	});

	it.each(['ids', 'row', 'details', 'sampleCount', 'signature'] as const)(
		'a copy that fails %s during the run is cleared, and the legacy backend runs',
		async (check) => {
			await writeOldCopy(source);
			const tamper: Record<typeof check, () => Promise<void>> = {
				ids: async () => {
					const db = await rawDb().open();
					await new Promise<void>((resolve) => {
						const tx = db.transaction('details', 'readwrite');
						tx.objectStore('details').delete(id());
						tx.oncomplete = () => resolve();
					});
					db.close();
				},
				row: () => rewrite('rows', id(), (r) => (r.tags = ['other'])),
				details: () => rewrite('details', id(), (r) => r.samples.pop()),
				sampleCount: () => rewrite('rows', id(), (r) => (r.summary.sampleCount = 0)),
				signature: () => rewrite('rows', id(), (r) => {
					r.summary.finalWeight = 1;
					r.summary.peakWeight = 1;
				})
			};
			const store = await boot({
				migrationFault: async (p) => {
					if (p === 'verify') await tamper[check]();
				}
			});
			expect(store.health).toBe('legacy');
			expect(store.storageError).toContain(check);
			const left = await stored();
			expect([left.rows.size, left.details.size, left.meta]).toEqual([0, 0, undefined]);
		}
	);
});

describe('late rows from an old build', () => {
	it('merges what an old tab added, fills a curve that came later, skips a pulled copy, and never brings back a deleted shot', async () => {
		const source = Array.from({ length: 5 }, (_, i) => realShot(i, realSamples(4, i)));
		await writeOldCopy(source);
		const first = await boot();
		// Deleted here after the move.
		first.delete(source[1].id);
		await first.flushed();
		const newDb = await stored();

		// An old tab: its own rows into the old copy, which it reads and rewrites whole.
		const r1 = realShot(100, realSamples(4, 20));
		const r2 = realShot(101, realSamples(4, 30));
		const bound = source.find((s) => s.visualizerId)!;
		const pulled = { ...realShot(102, []), id: 'shot:remote:dup', visualizerId: bound.visualizerId };
		const blob = JSON.parse(localStorage.getItem(LEGACY_KEY)!) as StoredShot[];
		blob[0] = { ...blob[0], metadata: { ...blob[0].metadata, notes: 'edited by the old tab' } };
		const { putSeries } = await import('./series-store');
		localStorage.setItem(
			LEGACY_KEY,
			JSON.stringify([...blob, r1, r2, pulled].map((s) => ({ ...s, record: { ...s.record, samples: [] } })))
		);
		await putSeries(r1.id, r1.record.samples);
		// The old build never opens `crema-history`.
		expect(await stored()).toEqual(newDb);

		const second = await boot();
		const ids = second.rawAll.map((s) => s.id);
		expect(ids).toContain(r1.id);
		expect(ids).toContain(r2.id);
		expect(ids).not.toContain(pulled.id);
		expect(ids).not.toContain(source[1].id);
		expect(second.get(source[0].id)!.metadata.notes).toBe(source[0].metadata.notes);
		const { details, meta } = await stored();
		expect(details.get(r1.id)!.samples).toEqual(r1.record.samples);
		expect(details.get(r2.id)!.samples).toEqual([]);
		expect((meta as MigrationRecord).lateIds).toEqual([r2.id]);

		// The old tab writes r2's series after its row: a later boot finds it.
		await putSeries(r2.id, r2.record.samples);
		await boot();
		const again = await stored();
		expect(again.details.get(r2.id)!.samples).toEqual(r2.record.samples);
		expect(again.rows.get(r2.id)!.summary.sampleCount).toBe(4);
		expect(again.meta!.lateIds).toEqual([]);
		expect(again.rows.has(source[1].id)).toBe(false);
	});

	it('a late row is taken once: deleting it here doesn’t bring it back', async () => {
		await writeOldCopy([realShot(1, realSamples(4))]);
		await boot();
		const late = realShot(2, realSamples(4));
		const blob = JSON.parse(localStorage.getItem(LEGACY_KEY)!) as StoredShot[];
		localStorage.setItem(LEGACY_KEY, JSON.stringify([...blob, { ...late, record: { ...late.record, samples: [] } }]));
		const store = await boot();
		store.delete(late.id);
		await store.flushed();
		const after = await boot();
		expect(after.rawAll.map((s) => s.id)).not.toContain(late.id);
		const meta = (await stored()).meta!;
		expect(meta.sourceIds).toContain(late.id);
		expect(META_MIGRATION).toBe('migration');
	});
});

describe('a shot made while the move runs', () => {
	it('is kept and written once the store is ready', async () => {
		await writeOldCopy(shots(30, 4));
		let release = () => {};
		const held = new Promise<void>((r) => (release = r));
		const { HistoryStore } = await import('./store.svelte');
		const store = new HistoryStore({
			migrationFault: async (p) => {
				if (p === 'verify') await held;
			}
		});
		const made = store.recordCaptured(realShot(500, realSamples(4)) as unknown as Record<string, unknown>)!;
		release();
		while (!store.loaded) await new Promise((r) => setTimeout(r, 2));
		await store.flushed();
		expect(store.get(made.id)).toBeDefined();
		const { details } = await stored();
		expect(details.get(made.id)!.samples).toHaveLength(4);
	});
});


