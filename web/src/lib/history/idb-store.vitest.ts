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
import { clearLight } from './light-rows';
import { peaksOf, type StoredShot } from './model';
import { PENDING_KEY, readPending } from './pending';
import { writeDoneMarker } from './store-marker';
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

	it('rows past the view are light in memory, and whole on demand', async () => {
		const shots = small(320);
		await seedStore(shots);
		const store = await boot();
		const hidden = store.rawAll[315];
		expect(store.all.some((s) => s.id === hidden.id)).toBe(false);
		expect(hidden.record.samples).toEqual([]);
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
		clearLight();
		expect(signatureForShot(hidden)).not.toBe(signatureForShot(full));
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
		const plan = (rows: readonly StoredShot[]) => ({
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
});

describe('other tabs', () => {
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

	it('a row another tab writes, edits or deletes shows here, by its id', async () => {
		await seedStore(small(10).map((s) => ({ ...s, visualizerId: null })));
		const channel = bus();
		const a = await boot({ channel });
		const b = await boot({ channel });
		const made = a.recordCaptured(realShot(50, realSamples(4)) as unknown as Record<string, unknown>)!;
		await a.flushed();
		await until(() => b.get(made.id) !== undefined);
		await until(() => (b.get(made.id)?.record.samples.length ?? 0) === 4);
		a.setRating(b.all[3].id, 1);
		await a.flushed();
		await until(() => b.all[3].metadata.rating === 1);
		expect(b.all[3].record.samples.length).toBeGreaterThan(0);
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
		const files = new Map<string, { bytes: Uint8Array; at: number }>();
		const notFound = () => new DOMException('none', 'NotFoundError');
		const root = {
			async getFileHandle(name: string, opts?: { create?: boolean }) {
				if (!files.has(name) && !opts?.create) throw notFound();
				return {
					async createWritable() {
						const parts: Uint8Array[] = [];
						return {
							async write(p: BufferSource) {
								parts.push(new Uint8Array(p as ArrayBuffer));
							},
							async close() {
								const total = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
								let o = 0;
								for (const p of parts) {
									total.set(p, o);
									o += p.length;
								}
								files.set(name, { bytes: total, at: 1_234 });
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
			}
		};
		const snapshotRoot = async () => root as unknown as FileSystemDirectoryHandle;
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
		expect(await lost.snapshotInfo()).toEqual({ at: 1_234, rows: 12 });
		expect(await lost.recoverSnapshot()).toBe(true);
		expect(lost.rawAll.map((s) => s.id).sort()).toEqual(shots.map((s) => s.id).sort());
		expect(lost.get(shots[0].id)!.metadata.rating).toBe(3);
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
