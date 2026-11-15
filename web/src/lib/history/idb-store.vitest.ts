/**
 * #310 P3w: the history store on `crema-history` (SPEC §1, §2, §6, §7), over
 * an in-memory IndexedDB: the view cap that hides and never drops, upsert-only
 * saves and deletes by name, other tabs, the pending journal, lost and
 * unavailable stores, the rows snapshot, and a wipe restore cut short.
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { reconcileShots, signatureForShot, type WireShot } from '#lib/visualizer/shot-sync-signatures.js';
import { HistoryDb, META_MIGRATION, type MigrationRecord } from './history-db';
import { sampleCountOf } from './model';
import { peaksOf, type StoredShot } from './model';
import { PENDING_KEY, readPending } from './pending';
import { STORE_MARKER_KEY, readStoreMarker, writeDoneMarker } from './store-marker';
import { SNAPSHOT_LOCK, listRowsSnapshots, readRowsSnapshot, snapshotNameOf, writeRowsSnapshot } from './rows-snapshot';
import type { LockManagerLike } from '#lib/restore/tab-safety.js';
import { getCaptureStore } from '#lib/capture/store.js';
import { pruneCaptures } from '#lib/capture/prune.js';
import { HistoryStore, type ChannelLike } from './store.svelte';
import { summarize } from './summary';
import { boot, freshStorage, historyDbExists, rawDb, settled, stored, whole, writeOldCopy, LEGACY_KEY } from './idb-test-kit';

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

/** A device already on `crema-history` with `shots`, as a migration leaves it. */
async function seedStore(shots: readonly StoredShot[]): Promise<void> {
	const db = new HistoryDb({ expectExisting: () => false });
	for (let i = 0; i < shots.length; i += 500) {
		await db.putShots(
			shots.slice(i, i + 500).map((shot) => ({ shot, details: true })),
			summarize
		);
	}
	await db.setMeta(META_MIGRATION, {
		state: 'done',
		sourceHash: '0:0',
		sourceIds: shots.map((s) => s.id),
		doneAt: 1
	} satisfies MigrationRecord);
	db.close();
	writeDoneMarker(1, shots.length);
	// The old copy: absent, as after a fresh install's move.
	localStorage.removeItem(LEGACY_KEY);
}

/** Channels that deliver to each other, as `BroadcastChannel` does. */
function bus() {
	const chans = new Set<ChannelLike>();
	return () => {
		const c: ChannelLike = {
			onmessage: null,
			postMessage(m) {
				for (const o of chans) if (o !== c) queueMicrotask(() => o.onmessage?.({ data: structuredClone(m) }));
			},
			close() {
				chans.delete(c);
			}
		};
		chans.add(c);
		return c;
	};
}

/** Delete `crema-history` under any open store, as the browser might. */
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

/** The first `crema-history` write fails for quota; the row-only retry, and later ones, land. */
function firstWriteFull() {
	let failed = false;
	return (op: string) => {
		if (!failed && op === 'write') {
			failed = true;
			throw new DOMException('full', 'QuotaExceededError');
		}
	};
}

const small = (n: number, from = 0) => Array.from({ length: n }, (_, i) => realShot(from + i, realSamples(4, (from + i) % 60)));
const until = async (ok: () => boolean, ms = 2_000) => {
	const end = Date.now() + ms;
	while (!ok()) {
		if (Date.now() > end) throw new Error('timed out');
		await new Promise((r) => setTimeout(r, 2));
	}
};

describe('the view cap hides, and never drops', () => {
	it('a capped build loads 5,000 rows, saves, and all rows remain', async () => {
		const shots = small(5_000);
		await seedStore(shots);
		const store = await boot();
		expect(store.all).toHaveLength(300);
		expect(store.rawAll).toHaveLength(5_000);
		store.setRating(store.all[10].id, 1);
		store.setNotes(shots[4_000].id, 'a hidden row, edited');
		const made = store.recordCaptured(realShot(9_999, realSamples(4)) as unknown as Record<string, unknown>)!;
		await store.flushed();
		const { rows, details } = await stored();
		expect(rows.size).toBe(5_001);
		expect(details.size).toBe(5_001);
		expect(rows.get(shots[4_000].id)!.metadata.notes).toBe('a hidden row, edited');
		const again = await boot();
		expect(again.rawAll).toHaveLength(5_001);
		expect(again.get(made.id)).toBeDefined();
	}, 120_000);

	it('a stale tab saves its edit, and every row another tab added stays', async () => {
		await seedStore(small(50));
		const a = await boot();
		const b = await boot();
		const added = small(10, 100).map((s) => a.recordCaptured(s as unknown as Record<string, unknown>)!.id);
		await a.flushed();
		// B never heard of them.
		b.setRating(b.all[20].id, 2);
		await b.flushed();
		const { rows } = await stored();
		expect(rows.size).toBe(60);
		for (const id of added) expect(rows.has(id)).toBe(true);
		expect(rows.get(b.all[20].id)!.metadata.rating).toBe(2);
	});

	it('rows past the view are rows, like those in view, and whole on demand', async () => {
		const shots = small(320);
		await seedStore(shots);
		const store = await boot();
		const hidden = store.rawAll[315];
		expect(store.all.some((s) => s.id === hidden.id)).toBe(false);
		// No heavy part is on a row at all, in view or past it (P4w).
		expect('samples' in hidden.record).toBe(false);
		expect('samples' in store.all[0].record).toBe(false);
		expect(hidden.summary.sampleCount).toBe(shots.find((s) => s.id === hidden.id)!.record.samples.length);
		const full = (await store.loadShot(hidden.id))!;
		expect(full.record.samples).toEqual(shots.find((s) => s.id === hidden.id)!.record.samples);
		// Its peaks are the curve's, from its summary, not a curveless row's.
		expect(peaksOf(hidden)).toEqual(peaksOf(full));
	});

	it('a pull of a hidden shot adds no copy: it binds, by the stored curve’s signature', async () => {
		const shots = small(320).map((s) => ({ ...s, visualizerId: null }));
		await seedStore(shots);
		const store = await boot();
		const hidden = store.rawAll[316];
		const full = whole(shots.find((s) => s.id === hidden.id)!);
		const p = peaksOf(full);
		const remote: WireShot = {
			id: 'remote-1',
			clock: full.completedAt,
			duration_ms: full.record.duration,
			profile_title: full.profileName ?? null,
			final_weight_g: p.finalWeight ?? p.peakWeight,
			notes: null,
			rating: null,
			updated_at_ms: null,
			tag_list: []
		} as unknown as WireShot;
		expect(reconcileShots(store.rawAll, [remote])).toEqual([
			expect.objectContaining({ kind: 'bind', localId: hidden.id, visualizerId: 'remote-1' })
		]);
		// Its de-dup signature is the whole shot's; read as curveless, it wouldn't be.
		expect(signatureForShot(hidden)).toBe(signatureForShot(full));
		expect(signatureForShot({ ...full, record: { duration: full.record.duration, samples: [] } })).not.toBe(
			signatureForShot(full)
		);
	});

	it('a capture prune counts hidden rows: a pinned hidden row keeps its capture', async () => {
		const shots = small(320);
		await seedStore(shots);
		const store = await boot();
		const hidden = store.rawAll[315];
		expect(store.all.some((s) => s.id === hidden.id)).toBe(false);
		store.setPinned(hidden.id, true);
		await store.flushed();
		const captures = getCaptureStore();
		for (const id of [store.all[0].id, store.all[1].id, hidden.id]) await captures.put(id, []);
		expect(await pruneCaptures(store, '10')).toEqual([]);
		expect((await pruneCaptures(store, 'off')).sort()).toEqual([store.all[0].id, store.all[1].id].sort());
		expect(await captures.keys()).toEqual([hidden.id]);
		await captures.delete(hidden.id);
	});

	it('a new shot past the view is refused as before; older rows it pushes out stay stored', async () => {
		await seedStore(small(300));
		const store = await boot();
		const oldest = store.all[299].id;
		store.addManualBrew({
			method: 'pourover',
			completedAt: Date.now(),
			bean: null,
			dose: 15,
			waterG: 250,
			yieldOut: null,
			grinderSetting: null,
			brewTempC: null,
			durationMs: null,
			rating: null,
			notes: null,
			nextPlan: null
		});
		await store.flushed();
		expect(store.all.map((s) => s.id)).not.toContain(oldest);
		expect(store.get(oldest)).toBeDefined();
		expect((await stored()).rows.has(oldest)).toBe(true);
		expect(store.insertMany([{ ...realShot(7_000, realSamples(3)), completedAt: 1 }])).toMatchObject({ kept: [], dropped: 1 });
	});
});

describe('saves upsert, and only a delete by name removes', () => {
	it('a restore plan that leaves rows out without naming them deletes nothing', async () => {
		const shots = small(20);
		await seedStore(shots);
		const store = await boot();
		const r = await store.restoreRows((rows) => ({ next: rows.slice(0, 5), series: [], remove: [] }));
		expect(r.stored).toBe(true);
		expect((await stored()).rows.size).toBe(20);
		expect(store.rawAll).toHaveLength(20);
	});

	it('a delete removes that row and its details, and nothing else', async () => {
		const shots = small(20).map((s) => ({ ...s, visualizerId: null }));
		await seedStore(shots);
		const store = await boot();
		store.delete(shots[3].id);
		await store.flushed();
		const { rows, details } = await stored();
		expect(rows.size).toBe(19);
		expect(rows.has(shots[3].id)).toBe(false);
		expect(details.has(shots[3].id)).toBe(false);
	});

	it('a details write never thins a stored curve or profile', async () => {
		const shots = small(320);
		await seedStore(shots);
		const store = await boot();
		const hidden = store.rawAll[318];
		// A backup row for it with no curve and no profile, overwriting.
		const bare = { ...hidden, profile: null, record: { ...hidden.record, samples: [] }, metadata: { ...hidden.metadata, notes: 'from backup' } };
		await store.restoreRows((rows) => ({
			next: rows.map((s) => (s.id === hidden.id ? bare : s)),
			series: [bare],
			remove: []
		}));
		const { rows, details } = await stored();
		expect(rows.get(hidden.id)!.metadata.notes).toBe('from backup');
		expect(details.get(hidden.id)!.samples).toHaveLength(4);
		expect(details.get(hidden.id)!.profile).not.toBeNull();
	});

	it('a wipe restore cut short leaves the union, and a re-run ends with the backup exactly', async () => {
		const here = small(2);
		await seedStore(here);
		const backup = small(60, 1_000);
		const plan = (rows: readonly { id: string }[]) => ({
			next: backup,
			series: backup,
			remove: rows.filter((s) => !backup.some((b) => b.id === s.id)).map((s) => s.id)
		});
		let writes = 0;
		const cut = await boot({
			dbFault: (op) => {
				if (op === 'write' && ++writes === 2) throw new DOMException('killed', 'AbortError');
			}
		});
		expect((await cut.restoreRows(plan)).stored).toBe(false);
		const half = await stored();
		for (const s of here) expect(half.rows.has(s.id)).toBe(true);
		expect(half.rows.size).toBe(2 + 25);
		const again = await boot();
		expect((await again.restoreRows(plan)).stored).toBe(true);
		const done = await stored();
		expect([...done.rows.keys()].sort()).toEqual(backup.map((s) => s.id).sort());
		for (const s of backup) expect(done.details.get(s.id)!.samples).toEqual(s.record.samples);
	});
});

describe('the pending journal', () => {
	it('a full disk keeps a new shot in the journal; it merges at the next boot', async () => {
		await seedStore(small(5));
		let full = true;
		const store = await boot({
			dbFault: (op) => {
				if (full && op === 'write') throw new DOMException('full', 'QuotaExceededError');
			}
		});
		const made = store.recordCaptured(realShot(77, realSamples(4)) as unknown as Record<string, unknown>)!;
		await store.flushed();
		expect(readPending().map((r) => r.id)).toEqual([made.id]);
		expect(store.unsavedShots).toEqual([made.id]);
		expect(store.hasUnsaved).toBe(true);
		full = false;
		const next = await boot();
		await next.flushed();
		expect(next.get(made.id)).toBeDefined();
		expect((await stored()).rows.has(made.id)).toBe(true);
		expect(localStorage.getItem(PENDING_KEY)).toBeNull();
	});

	it('the row alone fits: it is stored, its curve waits in memory and goes with the next save', async () => {
		await seedStore(small(5));
		let failFull = true;
		const store = await boot({
			dbFault: (op) => {
				if (failFull && op === 'write') {
					failFull = false;
					throw new DOMException('full', 'QuotaExceededError');
				}
			}
		});
		const made = store.recordCaptured(realShot(78, realSamples(4)) as unknown as Record<string, unknown>)!;
		await store.flushed();
		let s = await stored();
		expect(s.rows.has(made.id)).toBe(true);
		expect(s.details.has(made.id)).toBe(false);
		expect(store.unsavedShots).toEqual([made.id]);
		store.setRating(made.id, 5);
		await store.flushed();
		s = await stored();
		expect(s.details.get(made.id)!.samples).toHaveLength(4);
		expect(store.unsavedShots).toEqual([]);
	});

	it('an unsaved curve goes with a later save of any other row (M1)', async () => {
		await seedStore(small(5));
		const store = await boot({ dbFault: firstWriteFull() });
		const made = store.recordCaptured(realShot(79, realSamples(4)) as unknown as Record<string, unknown>)!;
		await store.flushed();
		expect((await stored()).details.has(made.id)).toBe(false);
		expect(store.unsavedShots).toEqual([made.id]);
		// Space freed; the user rates a different shot.
		const other = store.all.find((s) => s.id !== made.id)!;
		store.setRating(other.id, 4);
		await store.flushed();
		const s = await stored();
		expect(s.rows.get(other.id)!.metadata.rating).toBe(4);
		expect(s.details.get(made.id)!.samples).toHaveLength(4);
		expect(s.rows.get(made.id)!.summary.sampleCount).toBe(4);
		expect(store.unsavedShots).toEqual([]);
		expect(store.hasUnsaved).toBe(false);
	});

	it('a row stored without its curve claims none, so after a reload a backfill fills it (M2)', async () => {
		await seedStore(small(5));
		const store = await boot({ dbFault: firstWriteFull() });
		const made = store.recordCaptured(realShot(80, realSamples(4)) as unknown as Record<string, unknown>)!;
		await store.flushed();
		const row = (await stored()).rows.get(made.id)!;
		expect(row.summary.sampleCount ?? 0).toBe(0);
		expect(row.summary.brewSampleCount).toBe(0);
		// The tab is discarded before space comes back: the curve is gone.
		const next = await boot();
		const back = next.get(made.id)!;
		expect(sampleCountOf(back)).toBe(0);
		expect((await next.loadShot(made.id))!.record.samples).toEqual([]);
		// The curve Visualizer holds comes back by the backfill.
		const curve = realSamples(4, 7);
		next.backfillTelemetry(made.id, curve, 30_000);
		await next.flushed();
		const s = await stored();
		expect(s.details.get(made.id)!.samples).toHaveLength(4);
		expect(s.rows.get(made.id)!.summary.sampleCount).toBe(4);
	});

	it('a shot made while the boot waits for the lock is journalled, and closing the tab warns (S1)', async () => {
		await seedStore(small(3));
		let grant = () => {};
		const gate = new Promise<void>((r) => (grant = r));
		const locks: LockManagerLike = {
			async request(_name, _opts, run) {
				await gate;
				return run(null);
			}
		};
		const store = new HistoryStore({ locks });
		await new Promise((r) => setTimeout(r, 10));
		expect(store.health).toBe('loading');
		const made = store.recordCaptured(realShot(81, realSamples(4)) as unknown as Record<string, unknown>)!;
		expect(made).toBeTruthy();
		expect(store.hasUnsaved).toBe(true);
		expect(readPending().map((r) => r.id)).toEqual([made.id]);
		grant();
		await settled(store);
		await store.flushed();
		expect((await stored()).details.get(made.id)!.samples).toHaveLength(4);
		expect(readPending()).toEqual([]);
		expect(store.hasUnsaved).toBe(false);
	});

	it('a shot journalled during a boot that ends lost is listed as unsaved', async () => {
		await seedStore(small(3));
		await deleteHistoryDb();
		let grant = () => {};
		const gate = new Promise<void>((r) => (grant = r));
		const locks: LockManagerLike = {
			async request(_name, _opts, run) {
				await gate;
				return run(null);
			}
		};
		const store = new HistoryStore({ locks });
		const made = store.recordCaptured(realShot(82, realSamples(4)) as unknown as Record<string, unknown>)!;
		grant();
		await settled(store);
		expect(store.health).toBe('lost');
		expect(store.unsavedShots).toEqual([made.id]);
		expect(store.hasUnsaved).toBe(true);
		expect(readPending().map((r) => r.id)).toEqual([made.id]);
	});
});

describe('other tabs', () => {
	it('a row another tab writes, edits or deletes shows here, by its id', async () => {
		await seedStore(small(10).map((s) => ({ ...s, visualizerId: null })));
		const channel = bus();
		const a = await boot({ channel });
		const b = await boot({ channel });
		const made = a.recordCaptured(realShot(50, realSamples(4)) as unknown as Record<string, unknown>)!;
		await a.flushed();
		await until(() => b.get(made.id) !== undefined);
		await until(() => b.get(made.id)?.summary.sampleCount === 4);
		expect((await b.loadShot(made.id))!.record.samples).toHaveLength(4);
		a.setRating(b.all[3].id, 1);
		await a.flushed();
		await until(() => b.all[3].metadata.rating === 1);
		expect(b.all[3].summary.sampleCount).toBeGreaterThan(0);
		a.delete(made.id);
		await a.flushed();
		await until(() => b.get(made.id) === undefined);
	});

	it('ids heard during the load are taken once it ends', async () => {
		await seedStore(small(10));
		const channel = bus();
		const a = await boot({ channel });
		let release = () => {};
		const held = new Promise<void>((r) => (release = r));
		const b = new HistoryStore({
			channel,
			migrationFault: async (p) => {
				if (p === 'loaded-rows') await held;
			}
		});
		await new Promise((r) => setTimeout(r, 20));
		const made = a.recordCaptured(realShot(51, realSamples(4)) as unknown as Record<string, unknown>)!;
		await a.flushed();
		release();
		await settled(b);
		await until(() => b.get(made.id) !== undefined);
	});

	it('a tab on the old copy stops writing it once another tab moves the history (S4)', async () => {
		const shots = small(5);
		await writeOldCopy(shots);
		// A failed move waits for its next try: this tab runs on the old copy.
		localStorage.setItem(
			STORE_MARKER_KEY,
			JSON.stringify({ state: 'failed', failures: 3, lastError: 'full', lastTryAt: Date.now(), nextTryAt: Date.now() + 86_400_000 })
		);
		const a = await boot();
		expect(a.health).toBe('legacy');
		const blob = localStorage.getItem(LEGACY_KEY);
		// Another tab moves it.
		writeDoneMarker(Date.now(), shots.length);
		window.dispatchEvent(new StorageEvent('storage', { key: STORE_MARKER_KEY }));
		expect(a.movedElsewhere).toBe(true);
		expect(a.rowsHealth()).toBe('unavailable');
		a.setRating(shots[1].id, 2);
		const made = a.recordCaptured(realShot(53, realSamples(4)) as unknown as Record<string, unknown>)!;
		await a.flushed();
		expect(localStorage.getItem(LEGACY_KEY)).toBe(blob);
		// Only the new shot waits in the journal, for the moved store to merge.
		expect(readPending().map((r) => r.id)).toEqual([made.id]);
		expect(a.unsavedShots).toEqual([made.id]);
		expect((await a.restoreRows((rows) => ({ next: [...rows], series: [], remove: [] }))).stored).toBe(false);
	});

	it('a tab that missed the news reloads when it comes back into view', async () => {
		await seedStore(small(10));
		const a = await boot({ channel: null });
		const b = await boot({ channel: null, visibility: true });
		const made = a.recordCaptured(realShot(52, realSamples(4)) as unknown as Record<string, unknown>)!;
		await a.flushed();
		expect(b.get(made.id)).toBeUndefined();
		document.dispatchEvent(new Event('visibilitychange'));
		await until(() => b.get(made.id) !== undefined);
	});
});

describe('a store that is lost or can’t open', () => {
	it('marker done and the database gone: lost, no empty database appears, nothing is migrated', async () => {
		const shots = small(5);
		await writeOldCopy(shots);
		await boot();
		await rawDb().close();
		await new Promise<void>((resolve) => {
			const req = idb.deleteDatabase('crema-history');
			req.onsuccess = () => resolve();
		});
		const store = await boot();
		expect(store.health).toBe('lost');
		expect(store.loaded).toBe(false);
		expect(await historyDbExists(idb)).toBe(false);
		await expect(store.whenReady()).rejects.toThrow();
		expect(store.oldCopy).toMatchObject({ rows: 5 });
		// A shot made meanwhile waits in the journal.
		const made = store.recordCaptured(realShot(60, realSamples(4)) as unknown as Record<string, unknown>)!;
		expect(readPending().map((r) => r.id)).toEqual([made.id]);
		// Recover the old copy, by choice.
		expect(await store.recoverOldCopy()).toBe(true);
		expect(store.health).toBe('ok');
		await store.flushed();
		expect(store.rawAll.map((s) => s.id).sort()).toEqual([...shots.map((s) => s.id), made.id].sort());
	});

	it('start empty is a choice, and keeps the old copy out of the late-row check', async () => {
		await writeOldCopy(small(5));
		await boot();
		await new Promise<void>((resolve) => {
			const req = idb.deleteDatabase('crema-history');
			req.onsuccess = () => resolve();
			req.onblocked = () => resolve();
		});
		const store = await boot();
		expect(store.health).toBe('lost');
		await store.startEmpty();
		expect(store.health).toBe('ok');
		expect(store.rawAll).toEqual([]);
		expect((await boot()).rawAll).toEqual([]);
	});

	it('the rows snapshot brings the rows back', async () => {
		const { files, snapshotRoot } = memoryRoot();
		const shots = small(12);
		await seedStore(shots);
		const store = await boot({ snapshotRoot });
		store.setRating(shots[0].id, 3);
		await store.flushed();
		await until(() => files.size === 1);
		await new Promise<void>((resolve) => {
			const req = idb.deleteDatabase('crema-history');
			req.onsuccess = () => resolve();
			req.onblocked = () => resolve();
		});
		const lost = await boot({ snapshotRoot });
		expect(lost.health).toBe('lost');
		expect(await lost.snapshotInfo()).toEqual([{ name: snapshotNameOf(Date.now()), at: expect.any(Number), rows: 12 }]);
		expect(await lost.recoverSnapshot()).toBe(true);
		expect(lost.rawAll.map((s) => s.id).sort()).toEqual(shots.map((s) => s.id).sort());
		expect(lost.get(shots[0].id)!.metadata.rating).toBe(3);
	});

	it('a stale lost banner in a second tab can’t clear the store the first tab recovered (M3)', async () => {
		const shots = small(5);
		await writeOldCopy(shots);
		await boot();
		await deleteHistoryDb();
		const a = await boot();
		const b = await boot();
		expect(a.health).toBe('lost');
		expect(b.health).toBe('lost');
		expect(await a.recoverOldCopy()).toBe(true);
		const made = a.recordCaptured(realShot(62, realSamples(4)) as unknown as Record<string, unknown>)!;
		await a.flushed();
		// B never heard; its banner still offers to start empty.
		expect(b.health).toBe('lost');
		const result = await b.startEmpty();
		const { rows, details } = await stored();
		expect(rows.size).toBe(6);
		expect(details.get(made.id)!.samples).toHaveLength(4);
		expect(result).toBe('reopened');
		expect(b.health).toBe('ok');
		expect(b.get(made.id)).toBeDefined();
		await a.reloadRows();
		expect(a.get(made.id)).toBeDefined();
	});

	it('a store lost under a running tab: its shots count as unsaved, and its own recovery keeps them (F1)', async () => {
		const shots = small(5);
		await writeOldCopy(shots);
		const a = await boot();
		const made = a.recordCaptured(realShot(64, realSamples(4)) as unknown as Record<string, unknown>)!;
		a.setNotes(shots[1].id, 'edited after the move');
		await a.flushed();
		await deleteHistoryDb();
		a.setRating(shots[2].id, 5);
		await a.flushed();
		expect(a.health).toBe('lost');
		// Memory is their only copy now.
		expect(a.hasUnsaved).toBe(true);
		expect(a.unsavedShots).toContain(made.id);
		expect(await a.recoverOldCopy()).toBe(true);
		await a.flushed();
		const { rows, details } = await stored();
		expect(rows.size).toBe(6);
		expect(details.get(made.id)!.samples).toHaveLength(4);
		expect(rows.get(shots[1].id)!.metadata.notes).toBe('edited after the move');
		expect(rows.get(shots[2].id)!.metadata.rating).toBe(5);
		expect(a.get(made.id)).toBeDefined();
		expect(a.unsavedShots).toEqual([]);
		expect(a.hasUnsaved).toBe(false);
	});

	it('a store lost under a running tab: another tab’s recovery keeps that tab’s shots too (F1)', async () => {
		const shots = small(5);
		await writeOldCopy(shots);
		const channel = bus();
		const a = await boot({ channel });
		const made = a.recordCaptured(realShot(65, realSamples(4)) as unknown as Record<string, unknown>)!;
		await a.flushed();
		await deleteHistoryDb();
		a.setRating(shots[2].id, 5);
		await a.flushed();
		expect(a.health).toBe('lost');
		const b = await boot({ channel });
		expect(b.health).toBe('lost');
		expect(await b.recoverOldCopy()).toBe(true);
		await until(() => a.health === 'ok');
		await a.flushed();
		const { rows, details } = await stored();
		expect(rows.size).toBe(6);
		expect(details.get(made.id)!.samples).toHaveLength(4);
		expect(a.get(made.id)).toBeDefined();
		await until(() => b.get(made.id) !== undefined);
	});

	it('a stale lost tab that finds another tab’s recovery adds only its own new shots: no edit undone, no delete undone (F6)', async () => {
		const shots = small(5).map((s) => ({ ...s, visualizerId: null }));
		await writeOldCopy(shots);
		const a = await boot();
		a.setRating(shots[0].id, 1);
		const made = a.recordCaptured(realShot(67, realSamples(4)) as unknown as Record<string, unknown>)!;
		await a.flushed();
		await deleteHistoryDb();
		a.setRating(shots[3].id, 2);
		await a.flushed();
		expect(a.health).toBe('lost');
		// B recovers, edits and deletes; A never hears of it.
		const b = await boot();
		expect(await b.recoverOldCopy()).toBe(true);
		b.setRating(shots[0].id, 5);
		b.delete(shots[2].id);
		await b.flushed();
		expect(await a.startEmpty()).toBe('reopened');
		await a.flushed();
		const { rows, details } = await stored();
		expect(rows.get(shots[0].id)!.metadata.rating).toBe(5);
		expect(rows.has(shots[2].id)).toBe(false);
		// A's shot the old copy never had comes in, with its curve.
		expect(details.get(made.id)!.samples).toHaveLength(4);
		expect(rows.size).toBe(5);
		expect(a.get(shots[0].id)!.metadata.rating).toBe(5);
		expect(a.get(shots[2].id)).toBeUndefined();
		expect(a.unsavedShots).toEqual([]);
	});

	it('a stale lost tab that finds another tab’s daily-copy recovery fills in the curves it holds, and nothing else (F7)', async () => {
		const { files, snapshotRoot } = memoryRoot();
		const shots = small(5).map((s) => ({ ...s, visualizerId: null }));
		await writeOldCopy(shots);
		const a = await boot({ snapshotRoot });
		const made = a.recordCaptured(realShot(68, realSamples(4)) as unknown as Record<string, unknown>)!;
		await a.flushed();
		// A opened an old shot: memory keeps what it loaded last (P4w).
		await a.loadShot(shots[0].id);
		await until(() => files.size === 1);
		await deleteHistoryDb();
		a.setRating(shots[3].id, 2);
		await a.flushed();
		expect(a.health).toBe('lost');
		// B recovers the daily copy (no curves) and edits a row.
		const b = await boot({ snapshotRoot });
		expect(await b.recoverSnapshot()).toBe(true);
		b.setNotes(made.id, 'noted in B');
		await b.flushed();
		expect((await stored()).details.get(made.id)!.samples).toEqual([]);
		// A, with a stale banner, finds it.
		expect(await a.startEmpty()).toBe('reopened');
		expect(a.hasUnsaved).toBe(true);
		await a.flushed();
		const { rows, details } = await stored();
		expect(details.get(made.id)!.samples).toHaveLength(4);
		expect(rows.get(made.id)!.summary.sampleCount).toBe(4);
		expect(details.get(shots[0].id)!.samples).toHaveLength(4);
		// B's edit stays.
		expect(rows.get(made.id)!.metadata.notes).toBe('noted in B');
		expect((await a.loadShot(made.id))!.record.samples).toHaveLength(4);
		expect(a.hasUnsaved).toBe(false);
		expect(a.unsavedShots).toEqual([]);
	});

	it('a daily-copy recovery’s ids count as its source: a shot deleted after it doesn’t come back from a stale tab (N6)', async () => {
		const { files, snapshotRoot } = memoryRoot();
		const shots = small(3).map((s) => ({ ...s, visualizerId: null }));
		await writeOldCopy(shots);
		const a = await boot({ snapshotRoot });
		const made = a.recordCaptured({ ...realShot(69, realSamples(4)), visualizerId: null } as unknown as Record<string, unknown>)!;
		await a.flushed();
		await until(() => files.size === 1);
		await deleteHistoryDb();
		a.setRating(shots[1].id, 2);
		await a.flushed();
		expect(a.health).toBe('lost');
		const b = await boot({ snapshotRoot });
		expect(await b.recoverSnapshot()).toBe(true);
		expect(b.get(made.id)).toBeDefined();
		b.delete(made.id);
		await b.flushed();
		expect(await a.startEmpty()).toBe('reopened');
		await a.flushed();
		expect((await stored()).rows.has(made.id)).toBe(false);
		expect(a.get(made.id)).toBeUndefined();
	});

	it('two lost tabs recovering at once take turns on the lock: one fills, the other loads it (N1)', async () => {
		const shots = small(5);
		await writeOldCopy(shots);
		await boot();
		await deleteHistoryDb();
		// Exclusive per name, as Web Locks are.
		const tails = new Map<string, Promise<unknown>>();
		const locks: LockManagerLike = {
			request(name, _opts, run) {
				const next = (tails.get(name) ?? Promise.resolve()).then(() => run(null));
				tails.set(
					name,
					next.catch(() => {})
				);
				return next;
			}
		};
		const a = await boot({ locks });
		const b = await boot({ locks });
		const made = b.recordCaptured(realShot(66, realSamples(4)) as unknown as Record<string, unknown>)!;
		expect(await Promise.all([a.recoverOldCopy(), b.startEmpty()])).toEqual([true, 'reopened']);
		await b.flushed();
		const { rows } = await stored();
		expect(rows.size).toBe(6);
		expect(rows.has(made.id)).toBe(true);
	});

	it('a lost tab that hears another tab while the store is still missing doesn’t make it (N2)', async () => {
		await writeOldCopy(small(3));
		await boot();
		await deleteHistoryDb();
		const channel = bus();
		const lost = await boot({ channel });
		expect(lost.health).toBe('lost');
		channel().postMessage({ type: 'rows', from: 'another-tab', ids: ['x'], seq: 1 });
		await new Promise((r) => setTimeout(r, 30));
		expect(lost.health).toBe('lost');
		expect(await historyDbExists(idb)).toBe(false);
	});

	it('another tab’s recovery takes this tab out of lost', async () => {
		const shots = small(5);
		await writeOldCopy(shots);
		await boot();
		await deleteHistoryDb();
		const channel = bus();
		const a = await boot({ channel });
		const b = await boot({ channel });
		expect(b.health).toBe('lost');
		expect(await a.recoverOldCopy()).toBe(true);
		await until(() => b.health === 'ok');
		expect(b.rawAll.map((s) => s.id).sort()).toEqual(shots.map((s) => s.id).sort());
	});

	it.each([
		['empty, as a recovery killed before its record leaves it', 0],
		['with rows', 2]
	])('marker done and a store with no migration record, %s: lost (S2)', async (_what, n) => {
		const db = new HistoryDb({ expectExisting: () => false });
		await db.open();
		if (n > 0) await db.putShots(small(n).map((shot) => ({ shot, details: true })), summarize);
		db.close();
		writeDoneMarker(1, n);
		const store = await boot();
		expect(store.health).toBe('lost');
		expect(store.loaded).toBe(false);
	});

	it('a store deleted under a running tab is lost, and is not made again (S6)', async () => {
		await seedStore(small(5));
		const store = await boot();
		expect(store.health).toBe('ok');
		await deleteHistoryDb();
		store.setRating(store.all[1].id, 2);
		await store.flushed();
		expect(store.health).toBe('lost');
		expect(await historyDbExists(idb)).toBe(false);
	});

	it('the daily copy keeps profiles, yields, peaks and charts; its rows claim no curves, so a backfill fills them (S3, F2)', async () => {
		const { files, snapshotRoot } = memoryRoot();
		const shots = small(4);
		await seedStore(shots);
		const store = await boot({ snapshotRoot });
		store.setRating(shots[0].id, 3);
		await store.flushed();
		await until(() => files.size === 1);
		const before = (await stored()).rows;
		await deleteHistoryDb();
		const lost = await boot({ snapshotRoot });
		expect(await lost.recoverSnapshot()).toBe(true);
		const { rows, details } = await stored();
		for (const s of shots) {
			expect(details.get(s.id)!.profile).toEqual(whole(s).profile);
			const was = before.get(s.id)!.summary;
			const now = rows.get(s.id)!.summary;
			expect(now.sampleCount ?? 0).toBe(0);
			expect(now.brewSampleCount).toBe(0);
			expect(was.finalWeight).toBeGreaterThan(0);
			expect(now.finalWeight).toBe(was.finalWeight);
			expect(now.peakPressure).toBe(was.peakPressure);
			expect(now.spark).toEqual(was.spark);
			expect(peaksOf(lost.get(s.id)!).finalWeight).toBe(was.finalWeight);
		}
		lost.backfillTelemetry(shots[1].id, realSamples(4, 3), 30_000);
		await lost.flushed();
		expect((await stored()).details.get(shots[1].id)!.samples).toHaveLength(4);
	});

	/** A store on `day`: whose saves write that day's copy. */
	const dayClock = () => {
		const c = { day: 0, now: () => Date.now() + c.day * 86_400_000 };
		return c;
	};

	it('each day has its own copy; recovering an older one never touches a newer one (F4)', async () => {
		const { files, snapshotRoot } = memoryRoot();
		const shots = small(6);
		await seedStore(shots);
		const clock = dayClock();
		const store = await boot({ snapshotRoot, now: clock.now });
		store.setRating(shots[0].id, 3);
		await store.flushed();
		await until(() => files.size === 1);
		// Lost, started empty; the next day one new shot is copied.
		await deleteHistoryDb();
		const empty = await boot({ snapshotRoot, now: clock.now });
		expect(await empty.startEmpty()).toBe(true);
		clock.day = 1;
		const made = empty.recordCaptured(realShot(63, realSamples(4)) as unknown as Record<string, unknown>)!;
		await empty.flushed();
		await until(() => files.size === 2);
		expect((await empty.snapshotInfo()).map((s) => s.rows)).toEqual([1, 6]);
		// Lost again; the user picks the older copy, of 6.
		await deleteHistoryDb();
		const again = await boot({ snapshotRoot, now: clock.now });
		const [newer, older] = await again.snapshotInfo();
		expect(await again.recoverSnapshot(older.name)).toBe(true);
		expect(again.rawAll).toHaveLength(6);
		// The next day's save is a new copy: the one with the new shot stays.
		clock.day = 2;
		again.setRating(shots[1].id, 2);
		await again.flushed();
		await until(() => files.size === 3);
		const info = await again.snapshotInfo();
		expect(info.map((s) => s.rows)).toEqual([6, 1, 6]);
		expect(info[1].name).toBe(newer.name);
		expect((await readRowsSnapshot(snapshotRoot, newer.name))!.rows.map((r) => r.id)).toEqual([made.id]);
		// Lost once more: all copies merged by id, the newest winning a clash.
		await deleteHistoryDb();
		const all = await boot({ snapshotRoot, now: clock.now });
		expect(all.health).toBe('lost');
		expect(await all.recoverSnapshot('all')).toBe(true);
		expect(all.rawAll.map((s) => s.id).sort()).toEqual([...shots.map((s) => s.id), made.id].sort());
		expect(all.get(shots[1].id)!.metadata.rating).toBe(2);
		expect(all.get(shots[0].id)!.metadata.rating).toBe(3);
	});

	it('a recovery the same day never overwrites that day’s copy, with localStorage refusing every write (F5)', async () => {
		const { files, snapshotRoot } = memoryRoot();
		const shots = small(6);
		await seedStore(shots);
		const clock = dayClock();
		const spy = vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
			throw new DOMException('full', 'QuotaExceededError');
		});
		try {
			const store = await boot({ snapshotRoot, now: clock.now });
			store.setRating(shots[0].id, 3);
			await store.flushed();
			await until(() => files.size === 1);
			await deleteHistoryDb();
			const empty = await boot({ snapshotRoot, now: clock.now });
			expect(await empty.startEmpty()).toBe(true);
			empty.recordCaptured(realShot(64, realSamples(4)) as unknown as Record<string, unknown>);
			await empty.flushed();
			await new Promise((r) => setTimeout(r, 30));
			// Today's copy of 6 stands.
			expect((await empty.snapshotInfo()).map((s) => s.rows)).toEqual([6]);
			clock.day = 1;
			empty.setRating(empty.all[0].id, 1);
			await empty.flushed();
			await until(() => files.size === 2);
			expect((await empty.snapshotInfo()).map((s) => s.rows)).toEqual([1, 6]);
		} finally {
			spy.mockRestore();
		}
	});

	it('the newest 3 copies are kept, and the oldest goes only once a new one is written whole', async () => {
		const { files, control, snapshotRoot } = memoryRoot();
		const shots = small(3);
		await seedStore(shots);
		const clock = dayClock();
		const store = await boot({ snapshotRoot, now: clock.now });
		for (const day of [0, 1, 2]) {
			clock.day = day;
			store.setRating(shots[0].id, day + 1);
			await store.flushed();
			await until(() => files.size === day + 1);
		}
		const three = [...files.keys()].sort();
		// Day 3's write fails: nothing is pruned, and its empty file isn't a copy.
		clock.day = 3;
		control.failClose = true;
		store.setRating(shots[0].id, 4);
		await store.flushed();
		await new Promise((r) => setTimeout(r, 30));
		for (const name of three) expect(files.has(name)).toBe(true);
		expect((await store.snapshotInfo()).map((c) => c.name)).toEqual([...three].reverse());
		// Day 4's lands: the oldest goes, and so does day 3's empty file.
		clock.day = 4;
		store.setRating(shots[0].id, 5);
		await store.flushed();
		await until(() => !files.has(three[0]));
		expect([...files.keys()].sort()).toEqual([...three.slice(1), snapshotNameOf(clock.now())]);
	});

	it('a copy that doesn’t read back whole is removed, and nothing is pruned for it (N7)', async () => {
		const { files, control, snapshotRoot } = memoryRoot();
		const shots = small(3);
		await seedStore(shots);
		const clock = dayClock();
		const store = await boot({ snapshotRoot, now: clock.now });
		for (const day of [0, 1, 2]) {
			clock.day = day;
			store.setRating(shots[0].id, day + 1);
			await store.flushed();
			await until(() => files.size === day + 1);
		}
		const three = [...files.keys()].sort();
		clock.day = 3;
		control.cutShort = true;
		store.setRating(shots[0].id, 4);
		await store.flushed();
		await new Promise((r) => setTimeout(r, 30));
		expect([...files.keys()].sort()).toEqual(three);
		expect((await store.snapshotInfo()).map((c) => c.rows)).toEqual([3, 3, 3]);
	});

	it('a clock set back: the copy just written is kept, and copies go by the order written, not by name', async () => {
		const { files, snapshotRoot } = memoryRoot();
		const shots = small(3);
		await seedStore(shots);
		const clock = dayClock();
		const store = await boot({ snapshotRoot, now: clock.now });
		for (const day of [0, 1, 2]) {
			clock.day = day;
			store.setRating(shots[0].id, day + 1);
			await store.flushed();
			await until(() => files.size === day + 1);
		}
		const three = [...files.keys()].sort();
		clock.day = -10_000;
		store.setRating(shots[0].id, 5);
		await store.flushed();
		const back = snapshotNameOf(clock.now());
		await until(() => !files.has(three[0]));
		expect([...files.keys()].sort()).toEqual([back, ...three.slice(1)].sort());
		const info = await store.snapshotInfo();
		expect(info.map((c) => c.name)).toEqual([back, three[2], three[1]]);
		await deleteHistoryDb();
		const lost = await boot({ snapshotRoot, now: clock.now });
		expect(await lost.recoverSnapshot()).toBe(true);
		expect(lost.get(shots[0].id)!.metadata.rating).toBe(5);
	});

	it('two tabs writing the same day’s copy take turns on the lock: one writes it, and every copy stays whole', async () => {
		const { files, snapshotRoot } = memoryRoot();
		const at = (d: string) => new Date(`${d}T12:00:00`).getTime();
		const rows = small(3).map((s) => ({ id: s.id }) as never);
		for (const d of ['2026-11-11', '2026-11-12', '2026-11-13']) await writeRowsSnapshot(rows, at(d), snapshotRoot, null);
		let held = 0;
		let most = 0;
		let asked = 0;
		const tails = new Map<string, Promise<unknown>>();
		const locks: LockManagerLike = {
			request(name, _opts, run) {
				if (name === SNAPSHOT_LOCK) asked++;
				const next = (tails.get(name) ?? Promise.resolve()).then(async () => {
					most = Math.max(most, ++held);
					try {
						return await run(null);
					} finally {
						held--;
					}
				});
				tails.set(
					name,
					next.catch(() => {})
				);
				return next;
			}
		};
		const r = await Promise.all([
			writeRowsSnapshot(rows, at('2026-11-14'), snapshotRoot, locks),
			writeRowsSnapshot([...rows, { id: 'more' } as never], at('2026-11-14'), snapshotRoot, locks)
		]);
		expect(asked).toBe(2);
		expect(most).toBe(1);
		expect(r).toEqual([true, false]);
		const list = await listRowsSnapshots(snapshotRoot);
		expect(list.map((c) => [c.name, c.rows])).toEqual([
			[snapshotNameOf(at('2026-11-14')), 3],
			[snapshotNameOf(at('2026-11-13')), 3],
			[snapshotNameOf(at('2026-11-12')), 3]
		]);
		expect(files.size).toBe(3);
	});

	it('Recover from all copies takes the copy written last on a clash, never the one with the latest date', async () => {
		const { files, snapshotRoot } = memoryRoot();
		const shots = small(3);
		await seedStore(shots);
		const clock = dayClock();
		const store = await boot({ snapshotRoot, now: clock.now });
		store.setRating(shots[0].id, 1);
		await store.flushed();
		await until(() => files.size === 1);
		// The clock set forward: a copy dated far ahead.
		clock.day = 1_000;
		store.setRating(shots[0].id, 2);
		await store.flushed();
		await until(() => files.size === 2);
		// Back to the right day: the copy written last.
		clock.day = 1;
		store.setRating(shots[0].id, 3);
		await store.flushed();
		await until(() => files.size === 3);
		await deleteHistoryDb();
		const lost = await boot({ snapshotRoot, now: clock.now });
		expect(await lost.recoverSnapshot('all')).toBe(true);
		expect(lost.get(shots[0].id)!.metadata.rating).toBe(3);
	});

	it('an empty file from a tab closed mid-write neither blocks its day nor counts as a copy', async () => {
		const { files, snapshotRoot } = memoryRoot();
		const shots = small(3);
		await seedStore(shots);
		const clock = dayClock();
		const store = await boot({ snapshotRoot, now: clock.now });
		store.setRating(shots[0].id, 1);
		await store.flushed();
		await until(() => files.size === 1);
		// Day 1's write was cut off by a closed tab: an empty file stands.
		clock.day = 1;
		files.set(snapshotNameOf(clock.now()), { bytes: new Uint8Array(0), at: 1 });
		const next = await boot({ snapshotRoot, now: clock.now });
		next.setRating(shots[0].id, 2);
		await next.flushed();
		await until(() => (files.get(snapshotNameOf(clock.now()))?.bytes.length ?? 0) > 0);
		expect((await next.snapshotInfo()).map((c) => c.rows)).toEqual([3, 3]);
	});

	it('a marker the full storage refused is said, and written by a later save (S5)', async () => {
		await writeOldCopy(small(3));
		const setItem = localStorage.setItem.bind(localStorage);
		let refuse = true;
		const spy = vi.spyOn(localStorage, 'setItem').mockImplementation((key: string, value: string) => {
			if (refuse && key === STORE_MARKER_KEY) throw new DOMException('full', 'QuotaExceededError');
			return setItem(key, value);
		});
		try {
			const store = await boot();
			expect(store.health).toBe('ok');
			expect(readStoreMarker().kind).toBe('none');
			expect(store.storageError).toMatch(/marker/);
			refuse = false;
			store.setRating(store.all[0].id, 1);
			await store.flushed();
			expect(readStoreMarker()).toMatchObject({ kind: 'marker', marker: { state: 'done', rows: 3 } });
		} finally {
			spy.mockRestore();
		}
	});

	it('a store that won’t open is unavailable: new shots wait, and Try again brings it back', async () => {
		await seedStore(small(5));
		let broken = true;
		const failing = { open: () => { throw new Error('IndexedDB refused'); } } as unknown as IDBFactory;
		const store = await boot({ idb: () => (broken ? failing : idb) });
		expect(store.health).toBe('unavailable');
		await expect(store.whenReady()).rejects.toThrow();
		const made = store.recordCaptured(realShot(61, realSamples(4)) as unknown as Record<string, unknown>)!;
		expect(readPending().map((r) => r.id)).toEqual([made.id]);
		broken = false;
		expect(await store.retryOpen()).toBe(true);
		await store.flushed();
		expect(store.rawAll).toHaveLength(6);
		expect((await stored()).rows.has(made.id)).toBe(true);
	});

	it('a connection the browser closes is opened again on the next use', async () => {
		await seedStore(small(2));
		const db = new HistoryDb({ expectExisting: () => true });
		const first = await db.open();
		first.onclose?.call(first, new Event('close'));
		const second = await db.open();
		expect(second).not.toBe(first);
		expect((await db.allRows()).length).toBe(2);
		db.close();
	});
});

describe('not loaded is not empty', () => {
	it('reading the rows before they load throws in tests', async () => {
		await seedStore(small(2));
		const store = new HistoryStore();
		expect(() => store.all).toThrow(/before the rows loaded/);
		expect(() => store.rawAll).toThrow(/before the rows loaded/);
		expect(() => store.get('x')).toThrow(/before the rows loaded/);
		await settled(store);
		expect(store.all).toHaveLength(2);
	});

	it('the skeleton says still loading after 5 s, and gated callers give up after 30 s', async () => {
		vi.useFakeTimers();
		try {
			const store = new HistoryStore({ migrationFault: () => new Promise(() => {}) });
			const ready = store.whenReady();
			const gave = ready.catch((e: Error) => e.name);
			expect(store.loadingSlowly).toBe(false);
			await vi.advanceTimersByTimeAsync(5_001);
			expect(store.loadingSlowly).toBe(true);
			await vi.advanceTimersByTimeAsync(25_000);
			expect(await gave).toBe('HistoryNotReady');
			expect(store.loaded).toBe(false);
		} finally {
			vi.useRealTimers();
		}
	});
});

void LEGACY_KEY;
