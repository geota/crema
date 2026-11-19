/**
 * #310 W5: the history store on stage 2, after a real flip (SPEC §11 stage 2,
 * M9, S13, rule 3), over the real store in Node and `crema-history` in the
 * fake IndexedDB:
 *
 * - writes go only to `history.db` and `details`; `crema-history`'s `rows`
 *   and `meta` stay byte for byte through a simulated week;
 * - a delete keeps the details of a row an export walk holds;
 * - an older build's writes after the flip come in as late rows by the id
 *   rule, at the next stage-2 open (steps 8 and 9);
 * - P3's journal imports once; a shot captured while the flip runs lands in
 *   `history.db` in the same boot (M9);
 * - a stage-1 build after the flip never touches `crema-db`.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { summarize } from '#lib/history/summary.js';
import { freshStorage, writeOldCopy } from '#lib/history/idb-test-kit.js';
import { HistoryDb, META, ROWS, shotOfRow, type RowRecord } from '#lib/history/history-db.js';
import { HistoryStore, type Stage2 } from '#lib/history/store.svelte.js';
import { HistoryDbTooNew, SqliteRows, unavailableClient } from '#lib/history/sqlite-rows.js';
import { PENDING_KEY_PREFIX } from './names.js';
import { PENDING_KEY } from '#lib/history/pending.js';
import type { StoredShot } from '#lib/history/model.js';
import { idbDetails } from '#lib/history-source/details.js';
import { historyWasmBuilt } from '#lib/history-source/sqlite-kit.js';
import { openTab, STAGE1, type Env, type Tab } from './test-kit.js';
import { flipDisk, flipEnv, stageDeps, type DoneRecordBox, type FlipDisk } from './flip-kit.js';
import { seedP3, p3Db, indexReady } from './w2-kit.js';
import { chooseStage, type StageChoice } from './stage.js';
import { canonical } from './flip.js';
import { dbFileName, parseDoneRecord } from './marker.js';
import { MARKER_KEY } from './names.js';

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});

let disk: FlipDisk;
let done: DoneRecordBox;
let env: Env;
let lastPage: string | null = null;
const tabs: Tab[] = [];

beforeEach(() => {
	freshStorage();
	lastPage = null;
	disk = flipDisk();
	done = { text: null };
	env = flipEnv(disk, done);
});
afterEach(async () => {
	for (const t of tabs.splice(0)) {
		await t.client.stop().catch(() => {});
		t.close();
	}
});

const shots = (n: number, from = 0) => Array.from({ length: n }, (_, i) => realShot(from + i, realSamples(4, (from + i) % 60)));

/** A fresh page boot (the page before it reloaded). */
function boot(): Promise<StageChoice> {
	if (lastPage) env.kill(lastPage);
	const deps = stageDeps(env, done);
	lastPage = deps.page;
	return chooseStage(deps);
}

function tabOn(choice: Pick<StageChoice, 'stage' | 'expect' | 'target'>): Tab {
	const t = openTab(env, { stage: choice.stage, expect: choice.expect, target: choice.target });
	tabs.push(t);
	return t;
}

/** Stage 2's journal storage: the env's, as the client sees it. */
const journalStorage = () => ({
	get length() {
		return env.storage.length;
	},
	key: (i: number) => env.storage.key(i),
	getItem: (k: string) => env.storage.getItem(k),
	setItem: (k: string, v: string) => env.storage.setItem(k, v),
	removeItem: (k: string) => env.storage.removeItem(k)
});

function stage2Of(t: Tab, file = 'history.db'): Stage2 {
	return { client: t.client, details: idbDetails(new HistoryDb({ expectExisting: () => true }), { bump: false }), storage: journalStorage(), file };
}

/** A store on stage 2 over `t`'s client. */
async function storeOn(t: Tab, gate?: Promise<void>): Promise<HistoryStore> {
	const s = new HistoryStore({
		stage2: async () => {
			await gate;
			return stage2Of(t);
		}
	});
	return s;
}

/** `crema-history`'s `rows` and `meta`, as text: the old copy's bytes. */
async function oldCopyBytes(): Promise<string> {
	const db = await new Promise<IDBDatabase>((resolve, reject) => {
		const r = indexedDB.open('crema-history');
		r.onsuccess = () => resolve(r.result);
		r.onerror = () => reject(r.error);
	});
	const all = async (name: string) =>
		new Promise<unknown>((resolve, reject) => {
			const tx = db.transaction(name, 'readonly');
			const st = tx.objectStore(name);
			const keys = st.getAllKeys();
			const vals = st.getAll();
			tx.oncomplete = () => resolve({ keys: keys.result, vals: vals.result });
			tx.onerror = () => reject(tx.error);
		});
	const out = canonical({ rows: await all(ROWS), meta: await all(META) });
	db.close();
	return out;
}

/** Every row of `history.db`, through `t`. */
async function dbRows(t: Tab): Promise<Map<string, RowRecord>> {
	const rows = new SqliteRows(t.client, idbDetails(new HistoryDb({ expectExisting: () => true }), { bump: false }));
	return new Map((await rows.allRows()).map((r) => [r.id, r]));
}

async function detailsOf(ids: string[]) {
	const db = p3Db();
	try {
		return await db.detailsOf(ids);
	} finally {
		db.close();
	}
}

/** Flipped, with a stage-2 tab whose owner answers. */
async function flipped(n: number, seed: StoredShot[] = shots(n)): Promise<{ choice: StageChoice; t: Tab }> {
	await seedP3(seed);
	const choice = await boot();
	expect(choice.stage).toBe(2);
	const t = tabOn(choice);
	await indexReady(t.client);
	return { choice, t };
}

/** As an older build writes `crema-history` after the flip. */
async function olderBuild(f: (db: HistoryDb) => Promise<unknown>): Promise<void> {
	const db = p3Db();
	try {
		await f(db);
	} finally {
		db.close();
	}
}

/** A reboot: the stage-2 tab closes, a fresh boot, a new tab whose owner runs the late rows. */
async function reboot(old: Tab): Promise<Tab> {
	await old.client.stop().catch(() => {});
	old.close();
	tabs.splice(tabs.indexOf(old), 1);
	const choice = await boot();
	expect(choice.stage).toBe(2);
	const t = tabOn(choice);
	await indexReady(t.client);
	return t;
}

describe.runIf(historyWasmBuilt)('the store on stage 2', () => {
	it('loads history.db, writes only history.db and details, and leaves the old copy byte for byte through a week', async () => {
		const { t } = await flipped(30);
		const before = await oldCopyBytes();
		const store = await storeOn(t);
		await store.ready;
		expect(store.health).toBe('ok');
		const ids = [...(await dbRows(t)).keys()];
		for (const id of ids) expect(store.get(id)).toBeDefined();
		// A simulated week: captures, edits, stars, pins, deletes.
		const captured: string[] = [];
		const deleted: string[] = [];
		for (let day = 0; day < 7; day++) {
			const shot = { ...realShot(500 + day, realSamples(40, day)), id: `week-${day}` };
			const got = store.recordCaptured(shot as unknown as Record<string, unknown>);
			expect(got).not.toBeNull();
			captured.push(shot.id);
			store.setNotes(ids[day], `day ${day}`);
			store.setRating(ids[day + 7], (day % 5) + 1);
			store.setPinned(ids[day + 14], day % 2 === 0);
			store.purgeTombstone(ids[day + 21]);
			deleted.push(ids[day + 21]);
			await store.flushed();
		}
		const rows = await dbRows(t);
		for (const id of captured) expect(rows.has(id), id).toBe(true);
		for (const id of deleted) expect(rows.has(id), id).toBe(false);
		for (let day = 0; day < 7; day++) expect(rows.get(ids[day])?.metadata.notes).toBe(`day ${day}`);
		// Curves in details, the deleted ones' gone.
		const d = await detailsOf([...captured, ...deleted]);
		for (const id of captured) expect(d.get(id)?.samples.length, id).toBe(40);
		for (const id of deleted) expect(d.has(id), id).toBe(false);
		// The old copy: not a byte.
		expect(await oldCopyBytes()).toBe(before);
		// A reboot: the same rows, and still the same old copy.
		const t2 = await reboot(t);
		const after = await dbRows(t2);
		expect([...after.keys()].sort()).toEqual([...rows.keys()].sort());
		expect(await oldCopyBytes()).toBe(before);
	});

	it('a delete while an export walk holds the row keeps its details until the release (S6)', async () => {
		const { t } = await flipped(6);
		const store = await storeOn(t);
		await store.ready;
		const id = [...(await dbRows(t)).keys()][0];
		const walk = await t.client.once<{ token: string }>('exportBegin');
		store.purgeTombstone(id);
		await store.flushed();
		expect(store.get(id)).toBeUndefined();
		expect((await detailsOf([id])).has(id)).toBe(true);
		await t.client.once('exportEnd', walk.token);
	});

	it('P3’s journal is read once as late rows, and never written (S13)', async () => {
		const { t } = await flipped(4);
		const p1 = { ...realShot(900), id: 'p3-journal-1' };
		const entry = [{ ...p1, record: { duration: p1.record.duration, samples: [] } }];
		localStorage.setItem(PENDING_KEY, JSON.stringify(entry));
		const text = localStorage.getItem(PENDING_KEY);
		const store = await storeOn(t);
		await store.ready;
		expect(store.get('p3-journal-1')).toBeDefined();
		expect((await dbRows(t)).has('p3-journal-1')).toBe(true);
		expect(localStorage.getItem(PENDING_KEY)).toBe(text);
		// Deleted here: the entry, still in P3's key, never brings it back.
		store.purgeTombstone('p3-journal-1');
		await store.flushed();
		const again = await storeOn(t);
		await again.ready;
		expect(again.get('p3-journal-1')).toBeUndefined();
		expect((await dbRows(t)).has('p3-journal-1')).toBe(false);
	});

	it('a shot captured while the flip runs lands in history.db, with its curve, in the same boot (M9)', async () => {
		await seedP3(shots(8));
		let open: () => void = () => {};
		const gate = new Promise<void>((r) => (open = r));
		let t: Tab | null = null;
		const store = new HistoryStore({
			stage2: async () => {
				await gate;
				return t ? stage2Of(t) : null;
			}
		});
		// Captured before the stage is known: P3's journal holds its row at once.
		const shot = { ...realShot(77, realSamples(30, 3)), id: 'mid-flip' };
		expect(store.recordCaptured(shot as unknown as Record<string, unknown>)).not.toBeNull();
		expect(localStorage.getItem(PENDING_KEY) ?? '').toContain('mid-flip');
		const choice = await boot();
		expect(choice.stage).toBe(2);
		t = tabOn(choice);
		await indexReady(t.client);
		open();
		await store.ready;
		await store.flushed();
		expect((await dbRows(t)).has('mid-flip')).toBe(true);
		expect((await detailsOf(['mid-flip'])).get('mid-flip')?.samples.length).toBe(30);
	});

	it('the pre-P3 late-row step goes on in history.db: its record there, the P3 store untouched (S13)', async () => {
		const seed = shots(6);
		const { t } = await flipped(6, seed);
		const before = await oldCopyBytes();
		// A pre-P3 build's tab wrote the old blob after the flip.
		await writeOldCopy([...seed, { ...realShot(700, realSamples(12, 4)), id: 'v2-late' }]);
		const store = await storeOn(t);
		await store.ready;
		await store.flushed();
		expect((await dbRows(t)).has('v2-late')).toBe(true);
		expect((await detailsOf(['v2-late'])).get('v2-late')?.samples.length).toBe(12);
		const record = JSON.parse(String(await t.client.read('metaValue', 'p3_migration')));
		expect(record.sourceIds).toContain('v2-late');
		expect(await oldCopyBytes()).toBe(before);
	});

	it('a shot captured while the flip runs, then the tab killed: the next boot holds it (M9)', async () => {
		await seedP3(shots(5));
		const never = new HistoryStore({ stage2: () => new Promise<Stage2 | null>(() => {}) });
		const shot = { ...realShot(78, realSamples(10, 1)), id: 'killed-mid-flip' };
		expect(never.recordCaptured(shot as unknown as Record<string, unknown>)).not.toBeNull();
		// The tab dies with the flip half-way; the next boot flips and opens.
		const choice = await boot();
		const t = tabOn(choice);
		await indexReady(t.client);
		const store = await storeOn(t);
		await store.ready;
		expect(store.get('killed-mid-flip')).toBeDefined();
		expect((await dbRows(t)).has('killed-mid-flip')).toBe(true);
	});

	it('the ready gate: not loaded until the stage is known; an owner that never answers is unavailable, never empty', async () => {
		const { t } = await flipped(3);
		let open: () => void = () => {};
		const gate = new Promise<void>((r) => (open = r));
		const store = new HistoryStore({ readyTimeoutMs: 50, stage2: async () => (await gate, stage2Of(t)) });
		expect(store.loaded).toBe(false);
		await expect(store.whenReady(30)).rejects.toThrow();
		expect(await store.readyWithin(30)).toBe(false);
		open();
		await store.ready;
		expect(store.loaded).toBe(true);
		// The owner gone for good: the next store is unavailable, and says so.
		t.freeze();
		const stuck = { ...stage2Of(t), client: { ...t.client, status: { ...t.client.status, health: 'unavailable' as const }, onStatus: () => () => {} } };
		const blocked = new HistoryStore({ stage2: async () => stuck as unknown as Stage2 });
		await expect(blocked.whenReady(20_000)).rejects.toThrow();
		expect(blocked.health).toBe('unavailable');
		t.thaw();
	}, 30_000);

	it('a boot that couldn’t tell the truth file: unavailable at once, a new shot journaled for the owner, nothing else written (REVIEW-473)', async () => {
		await seedP3(shots(3));
		const before = await oldCopyBytes();
		const store = new HistoryStore({
			stage2: async () => ({ client: unavailableClient('scan:ownerLock'), details: idbDetails(new HistoryDb({ expectExisting: () => true }), { bump: false }), storage: journalStorage() })
		});
		await expect.poll(() => store.health, { timeout: 2_000 }).toBe('unavailable');
		await expect(store.whenReady(100)).rejects.toThrow();
		const shot = { ...realShot(91, realSamples(8, 2)), id: 'while-unknown' };
		store.recordCaptured(shot as unknown as Record<string, unknown>);
		await store.flushed();
		expect(env.storage.getItem(PENDING_KEY_PREFIX + 'while-unknown')).toContain('while-unknown');
		// Never a fall back to the P3 store: not its rows, its meta, or its journal.
		expect(await oldCopyBytes()).toBe(before);
		expect(localStorage.getItem(PENDING_KEY)).toBeNull();
	});

	it('an owner that goes unavailable mid-session: read-only, shots to stage 2’s journal, never a P3 write', async () => {
		const { t } = await flipped(4);
		const store = await storeOn(t);
		await store.ready;
		const before = await oldCopyBytes();
		// Another build's tab takes the database: this tab's client refuses it (M1).
		t.freeze();
		const shot = { ...realShot(92, realSamples(8, 3)), id: 'owner-gone' };
		t.client.write = async () => {
			throw Object.assign(new Error('History is open in another window'), { code: 'readOnly' });
		};
		store.recordCaptured(shot as unknown as Record<string, unknown>);
		await store.flushed();
		expect(env.storage.getItem(PENDING_KEY_PREFIX + 'owner-gone')).toContain('owner-gone');
		expect(store.unsavedShots).toContain('owner-gone');
		expect(await oldCopyBytes()).toBe(before);
		expect(localStorage.getItem(PENDING_KEY)).toBeNull();
		t.thaw();
	});

	it('too new: the store is unavailable with "update the app", never lost', async () => {
		const fake = {
			status: { health: 'too_new', reason: 'owner:too_new:tooNew', message: null, owner: null, ownerHere: false },
			onStatus: () => () => {},
			read: async () => null,
			write: async () => ({ outcome: 'refused', reason: 'x' }),
			onChange: () => () => {},
			importLate: async () => ({ imported: [], known: [], duplicates: [] })
		};
		const rows = new SqliteRows(fake as never, idbDetails(new HistoryDb({ expectExisting: () => false })));
		await expect(rows.open(10)).rejects.toBeInstanceOf(HistoryDbTooNew);
	});
});

describe.runIf(historyWasmBuilt)('older builds after the flip (SPEC §11 "Downgrade")', () => {
	it('their rows merge at the next open by the id rule; edits and deletes of migrated rows don’t come back', async () => {
		const seed = shots(10).map((s, i) => (i === 0 ? { ...s, visualizerId: 'viz-held' } : s));
		const { t } = await flipped(10, seed);
		const ids = seed.map((s) => s.id);
		// Deleted here after the flip.
		const store = await storeOn(t);
		await store.ready;
		store.purgeTombstone(ids[2]);
		await store.flushed();
		// An older build writes the old copy.
		await olderBuild(async (db) => {
			await db.putShots([{ shot: { ...realShot(300, realSamples(20, 1)), id: 'older-new' }, details: true }], summarize);
			const [r0] = (await db.rowsOf([ids[1]])).values();
			const { shot, summary } = shotOfRow(r0);
			await db.putShots([{ shot: { ...shot, metadata: { ...shot.metadata, notes: 'older build edit' } }, details: false, summary }], summarize);
			await db.delete([ids[3]]);
			await db.putShots([{ shot: { ...realShot(301), id: 'pulled-dup', visualizerId: 'viz-held' }, details: true }], summarize);
			const noCurve = { ...realShot(302, []), id: 'late-no-curve' };
			await db.putShots([{ shot: noCurve, details: false, summary: summarize(noCurve) }], summarize);
		});
		const t2 = await reboot(t);
		const rows = await dbRows(t2);
		expect(rows.has('older-new')).toBe(true);
		expect(rows.get(ids[1])?.metadata.notes).not.toBe('older build edit');
		expect(rows.has(ids[2])).toBe(false);
		// The older build deleted it, and its curve: back without one ("curve missing").
		expect(rows.has(ids[3])).toBe(true);
		expect((await detailsOf([ids[3]])).has(ids[3])).toBe(false);
		expect(rows.get(ids[3])?.summary.sampleCount).toBeGreaterThan(0);
		expect(rows.has('pulled-dup')).toBe(false);
		expect(rows.get('late-no-curve')?.summary.sampleCount).toBe(0);
		expect(await t2.client.read('lateIds')).toEqual(['late-no-curve']);
		// Once only: another open imports nothing new.
		const t3 = await reboot(t2);
		expect((await dbRows(t3)).size).toBe(rows.size);
		// The older build writes the series after the row (step 9).
		await olderBuild(async (db) => {
			await db.putShots([{ shot: { ...realShot(302, realSamples(25, 2)), id: 'late-no-curve' }, details: true }], summarize);
		});
		const t4 = await reboot(t3);
		expect((await dbRows(t4)).get('late-no-curve')?.summary.sampleCount).toBe(25);
		expect(await t4.client.read('lateIds')).toEqual([]);
	});

	it('a stage-1 build after the flip never installs crema-db and never opens history.db', async () => {
		const { t } = await flipped(5);
		await t.client.stop();
		t.close();
		tabs.splice(tabs.indexOf(t), 1);
		const from = disk.calls.length;
		const s1 = openTab(env, STAGE1);
		tabs.push(s1);
		await indexReady(s1.client);
		const mine = disk.calls.slice(from);
		expect(mine.some((c) => c.startsWith('index/'))).toBe(true);
		expect(mine.filter((c) => c.startsWith('db'))).toEqual([]);
	});
});

describe.runIf(historyWasmBuilt)('W5c: recovery files, the rows snapshot, pulled duplicates', () => {
	const STAGE2_OF = (g: number) => {
		const file = dbFileName(g);
		return {
			stage: 2 as const,
			expect: { role: 'authority' as const, file, generation: 0 },
			target: { pool: 'db' as const, generation: 0, file, role: 'authority' as const, create: false, journal: 'exclusiveWal' as const }
		};
	};
	/** As `start.ts`'s `restageHistoryDb`: the marker, then a client of the new file. */
	function restageFrom(old: Tab): (g: number) => Promise<Stage2> {
		return async (g) => {
			const m = JSON.parse(env.storage.getItem(MARKER_KEY) ?? 'null');
			if (m?.generation > g) throw new Error('the marker names a newer file');
			env.storage.setItem(MARKER_KEY, JSON.stringify({ state: 'done', generation: g, poolGeneration: 0, at: Date.now() }));
			await old.client.stop();
			const t = openTab(env, STAGE2_OF(g));
			tabs.push(t);
			return { ...stage2Of(t, dbFileName(g)), restage: restageFrom(t) };
		};
	}
	/** Flipped, then `history.db` gone: a stage-2 store that is `lost`. */
	async function lostStore(n = 5): Promise<{ store: HistoryStore; t: Tab }> {
		const { t } = await flipped(n);
		await t.client.snapshot();
		disk.files.db.delete('history.db');
		await t.client.stop();
		t.close();
		tabs.splice(tabs.indexOf(t), 1);
		const choice = await boot();
		const t2 = tabOn(choice);
		const store = new HistoryStore({ stage2: async () => ({ ...stage2Of(t2), restage: restageFrom(t2) }) });
		await expect.poll(() => store.health, { timeout: 20_000 }).toBe('lost');
		return { store, t: t2 };
	}
	const currentTab = () => tabs[tabs.length - 1];

	it('start empty: a new history-1.db, the damaged file left, and a reboot stays on it and empty (S9)', async () => {
		const { store } = await lostStore();
		const before = await oldCopyBytes();
		expect(await store.startEmpty()).toBe(true);
		expect(store.health).toBe('ok');
		expect(JSON.parse(env.storage.getItem(MARKER_KEY)!)).toMatchObject({ state: 'done', generation: 1 });
		expect(disk.files.db.has('history-1.db')).toBe(true);
		expect((await dbRows(currentTab())).size).toBe(0);
		// A reboot: the marker names history-1.db, and late rows import nothing.
		const t = await reboot(currentTab());
		expect(t.client.status.owner?.file).toBe('history-1.db');
		expect((await dbRows(t)).size).toBe(0);
		expect(await oldCopyBytes()).toBe(before);
	});

	it('the old copy: history-1.db holds the P3 store’s rows', async () => {
		const { store } = await lostStore(7);
		expect(await store.recoverOldCopy()).toBe(true);
		expect((await dbRows(currentTab())).size).toBe(7);
		const t = await reboot(currentTab());
		expect((await dbRows(t)).size).toBe(7);
	});

	it('the rows snapshot: daily, skipped when nothing moved, one previous copy kept; a recovery from it is generation n', async () => {
		const { t } = await flipped(4);
		expect(await t.client.snapshot()).toEqual({ copied: true });
		expect(await t.client.snapshot()).toEqual({ copied: false });
		const store = await storeOn(t);
		await store.ready;
		store.recordCaptured({ ...realShot(600, realSamples(9, 1)), id: 'after-snap' } as unknown as Record<string, unknown>);
		await store.flushed();
		// The store takes the day's snapshot after its first write; the old one is kept.
		await expect.poll(() => disk.files.db.has('history-snapshot-prev.db'), { timeout: 5_000 }).toBe(true);
		expect(await t.client.snapshot()).toEqual({ copied: false });
		expect(disk.files.db.has('history-snapshot.db')).toBe(true);
		const info = await t.client.snapshotInfo();
		expect(info.map((s) => [s.name, s.rows])).toEqual([
			['history-snapshot.db', 5],
			['history-snapshot-prev.db', 4]
		]);
		// Lost, then the snapshot: history-1.db from it, its generation set.
		disk.files.db.delete('history.db');
		await store.flushed();
		await t.client.stop();
		t.close();
		tabs.splice(tabs.indexOf(t), 1);
		const t2 = tabOn(await boot());
		const lost = new HistoryStore({ stage2: async () => ({ ...stage2Of(t2), restage: restageFrom(t2) }) });
		await expect.poll(() => lost.health, { timeout: 20_000 }).toBe('lost');
		expect(await lost.recoverSnapshot('history-snapshot.db')).toBe(true);
		const now = currentTab();
		expect((await dbRows(now)).has('after-snap')).toBe(true);
		expect(((await now.client.read('info')) as { generation: number }).generation).toBe(1);
	}, 60_000);

	/** Two tabs, both `lost` on `history.db`. */
	async function twoLost(n = 5): Promise<{ A: HistoryStore; B: HistoryStore }> {
		const { t } = await flipped(n);
		await t.client.snapshot();
		disk.files.db.delete('history.db');
		await t.client.stop();
		t.close();
		tabs.splice(tabs.indexOf(t), 1);
		const tA = tabOn(await boot());
		const tB = tabOn(STAGE2_OF(0));
		const A = new HistoryStore({ stage2: async () => ({ ...stage2Of(tA), restage: restageFrom(tA) }) });
		const B = new HistoryStore({ stage2: async () => ({ ...stage2Of(tB), restage: restageFrom(tB) }) });
		await expect.poll(() => A.health, { timeout: 20_000 }).toBe('lost');
		await expect.poll(() => B.health, { timeout: 20_000 }).toBe('lost');
		return { A, B };
	}
	const recoveryFiles = () => [...disk.files.db].filter((f) => /^history-\d+\.db$/.test(f)).sort();

	/** Both tabs on `history-1.db`, each with a shot of its own, both there after a reboot, with the marker and without. */
	async function bothSurvive(A: HistoryStore, B: HistoryStore): Promise<void> {
		await expect.poll(() => [A.health, B.health], { timeout: 20_000 }).toEqual(['ok', 'ok']);
		expect(recoveryFiles()).toEqual(['history-1.db']);
		expect(JSON.parse(env.storage.getItem(MARKER_KEY)!)).toMatchObject({ generation: 1 });
		expect(parseDoneRecord(done.text ?? '')).toMatchObject({ generation: 1 });
		A.recordCaptured({ ...realShot(701, realSamples(9, 1)), id: 'shot-A' } as unknown as Record<string, unknown>);
		B.recordCaptured({ ...realShot(702, realSamples(9, 2)), id: 'shot-B' } as unknown as Record<string, unknown>);
		await A.flushed();
		await B.flushed();
		for (const x of tabs.splice(0)) {
			await x.client.stop().catch(() => {});
			x.close();
		}
		const t2 = tabOn(await boot());
		await indexReady(t2.client);
		expect(t2.client.status.owner?.file).toBe('history-1.db');
		const rows = await dbRows(t2);
		expect(rows.has('shot-A')).toBe(true);
		expect(rows.has('shot-B')).toBe(true);
		// The marker lost too: the done-record names the same file.
		await t2.client.stop();
		t2.close();
		tabs.splice(tabs.indexOf(t2), 1);
		env.storage.removeItem(MARKER_KEY);
		expect((await boot()).target.file).toBe('history-1.db');
	}

	it('two lost tabs choosing in turn: one recovery file, the other tab follows it, and both tabs’ shots survive (REVIEW-476 B1)', async () => {
		const { A, B } = await twoLost();
		expect(await A.startEmpty()).toBe(true);
		// B follows the marker to history-1.db; a choice there makes no second file.
		await expect.poll(() => B.health, { timeout: 20_000 }).toBe('ok');
		expect(await B.recoverOldCopy()).toBe('reopened');
		await bothSurvive(A, B);
	}, 120_000);

	it('two lost tabs choosing at once: one recovery file, both on it, both tabs’ shots survive (REVIEW-476 B1)', async () => {
		const { A, B } = await twoLost();
		const [ra, rb] = await Promise.allSettled([A.startEmpty(), B.recoverOldCopy()]);
		expect([ra.status, rb.status]).toEqual(['fulfilled', 'fulfilled']);
		await bothSurvive(A, B);
	}, 120_000);

	it('the lost generation’s last daily copy is kept through later snapshots (REVIEW-476 N4)', async () => {
		const { t } = await flipped(6);
		expect(await t.client.snapshot()).toEqual({ copied: true });
		disk.files.db.delete('history.db');
		await t.client.stop();
		t.close();
		tabs.splice(tabs.indexOf(t), 1);
		const t2 = tabOn(await boot());
		const S = new HistoryStore({ stage2: async () => ({ ...stage2Of(t2), restage: restageFrom(t2) }) });
		await expect.poll(() => S.health, { timeout: 20_000 }).toBe('lost');
		expect(await S.startEmpty()).toBe(true);
		const now = tabs[tabs.length - 1];
		for (let day = 0; day < 3; day++) {
			S.recordCaptured({ ...realShot(800 + day, realSamples(9, 1)), id: `d${day}` } as unknown as Record<string, unknown>);
			await S.flushed();
			await now.client.snapshot();
		}
		const info = await now.client.snapshotInfo();
		expect(info.find((x) => x.name === 'history-snapshot-prev.db')?.rows).toBe(6);
		expect(info.find((x) => x.name === 'history-snapshot.db')?.rows).toBe(3);
	}, 60_000);

	it('start empty stays empty with an entry in P3’s journal key that stage 2 deleted (REVIEW-476 N1)', async () => {
		const { t } = await flipped(4);
		const p1 = { ...realShot(900), id: 'p3-journal-1' };
		localStorage.setItem(PENDING_KEY, JSON.stringify([{ ...p1, record: { duration: p1.record.duration, samples: [] } }]));
		const s0 = await storeOn(t);
		await s0.ready;
		s0.purgeTombstone('p3-journal-1');
		await s0.flushed();
		await t.client.stop();
		t.close();
		tabs.splice(tabs.indexOf(t), 1);
		disk.files.db.delete('history.db');
		const t2 = tabOn(await boot());
		const S = new HistoryStore({ stage2: async () => ({ ...stage2Of(t2), restage: restageFrom(t2) }) });
		await expect.poll(() => S.health, { timeout: 20_000 }).toBe('lost');
		expect(await S.startEmpty()).toBe(true);
		expect((await dbRows(tabs[tabs.length - 1])).size).toBe(0);
	}, 60_000);

	it('N8: an edit to a pulled copy before its first write keeps it as this tab’s own (REVIEW-476 N5)', async () => {
		const { t } = await flipped(3);
		const t2 = tabOn(STAGE2_OF(0));
		await indexReady(t2.client);
		const a = await storeOn(t);
		const b = await storeOn(t2);
		await Promise.all([a.ready, b.ready]);
		expect(b.insertPulled({ ...realShot(902, realSamples(6, 4)), id: 'pulled-10b', visualizerId: 'viz-10' } as StoredShot)).toBe(true);
		await b.flushed();
		expect(a.insertPulled({ ...realShot(902, realSamples(6, 4)), id: 'pulled-10', visualizerId: 'viz-10' } as StoredShot)).toBe(true);
		a.setNotes('pulled-10', 'my notes on the pulled shot');
		await a.flushed();
		const rows = await dbRows(t);
		expect(rows.get('pulled-10')?.metadata.notes).toBe('my notes on the pulled shot');
	}, 60_000);

	it('N8: two tabs pulling one Visualizer shot store it once, and the other tab drops its copy', async () => {
		const { t } = await flipped(3);
		const t2 = tabOn(STAGE2_OF(0));
		await indexReady(t2.client);
		const a = await storeOn(t);
		const b = await storeOn(t2);
		await Promise.all([a.ready, b.ready]);
		const pull = (id: string) => ({ ...realShot(800, realSamples(6, 2)), id, visualizerId: 'viz-remote-1' }) as StoredShot;
		expect(a.insertPulled(pull('pulled-a'))).toBe(true);
		await a.flushed();
		expect(b.insertPulled(pull('pulled-b'))).toBe(true);
		await b.flushed();
		const rows = await dbRows(t);
		const holders = [...rows.values()].filter((r) => r.visualizerId === 'viz-remote-1').map((r) => r.id);
		expect(holders).toEqual(['pulled-a']);
		expect(b.get('pulled-b')).toBeUndefined();
		expect((await detailsOf(['pulled-b'])).has('pulled-b')).toBe(false);
		await expect.poll(() => b.get('pulled-a')?.id, { timeout: 5_000 }).toBe('pulled-a');
	});
});

describe.runIf(historyWasmBuilt)('REVIEW-474: step 9 and the held journal never undo stage 2', () => {
	it('P1: a stage-2 edit of a late brew log (0 samples forever) survives the next open', async () => {
		const { t } = await flipped(4);
		await olderBuild(async (db) => {
			const brew = { ...realShot(310, []), id: 'older-brew', brewMethod: 'v60' } as StoredShot;
			await db.putShots([{ shot: brew, details: false, summary: summarize(brew) }], summarize);
		});
		const t2 = await reboot(t);
		// A brew log never has samples: it doesn't wait for any (B1).
		expect(await t2.client.read('lateIds')).toEqual([]);
		const store = await storeOn(t2);
		await store.ready;
		expect(store.get('older-brew')).toBeDefined();
		store.setNotes('older-brew', 'my stage-2 note');
		await store.flushed();
		expect((await dbRows(t2)).get('older-brew')?.metadata.notes).toBe('my stage-2 note');
		const t3 = await reboot(t2);
		expect(await t3.client.read('lateIds')).toEqual([]);
		expect((await dbRows(t3)).get('older-brew')?.metadata.notes).toBe('my stage-2 note');
	});

	it('P2: a late 0-sample row deleted on stage 2 stays deleted at the next open', async () => {
		const { t } = await flipped(4);
		await olderBuild(async (db) => {
			const s = { ...realShot(311, []), id: 'older-zero' };
			await db.putShots([{ shot: s, details: false, summary: summarize(s) }], summarize);
		});
		const t2 = await reboot(t);
		expect(await t2.client.read('lateIds')).toEqual(['older-zero']);
		const store = await storeOn(t2);
		await store.ready;
		store.purgeTombstone('older-zero');
		await store.flushed();
		expect((await dbRows(t2)).has('older-zero')).toBe(false);
		const t3 = await reboot(t2);
		expect((await dbRows(t3)).has('older-zero')).toBe(false);
	});

	it('P3: a stage-2 edit of a late row survives step 9 filling its series', async () => {
		const { t } = await flipped(4);
		await olderBuild(async (db) => {
			const s = { ...realShot(312, []), id: 'older-fill' };
			await db.putShots([{ shot: s, details: false, summary: summarize(s) }], summarize);
		});
		const t2 = await reboot(t);
		const store = await storeOn(t2);
		await store.ready;
		store.setNotes('older-fill', 'my stage-2 note');
		await store.flushed();
		await olderBuild(async (db) => {
			await db.putShots([{ shot: { ...realShot(312, realSamples(25, 2)), id: 'older-fill' }, details: true }], summarize);
		});
		const t3 = await reboot(t2);
		const row = (await dbRows(t3)).get('older-fill');
		expect(row?.summary.sampleCount).toBe(25);
		expect(row?.metadata.notes).toBe('my stage-2 note');
	});

	it('P4: a shot captured while the flip runs, then deleted in that session, stays deleted at the next open', async () => {
		await seedP3(shots(8));
		let open: () => void = () => {};
		const gate = new Promise<void>((r) => (open = r));
		let t: Tab | null = null;
		const store = new HistoryStore({
			stage2: async () => {
				await gate;
				return t ? stage2Of(t) : null;
			}
		});
		const shot = { ...realShot(77, realSamples(30, 3)), id: 'mid-flip' };
		expect(store.recordCaptured(shot as unknown as Record<string, unknown>)).not.toBeNull();
		const choice = await boot();
		t = tabOn(choice);
		await indexReady(t.client);
		open();
		await store.ready;
		await store.flushed();
		expect((await dbRows(t)).has('mid-flip')).toBe(true);
		store.purgeTombstone('mid-flip');
		await store.flushed();
		expect((await dbRows(t)).has('mid-flip')).toBe(false);
		// P3's key still holds it (never written on stage 2).
		expect(localStorage.getItem(PENDING_KEY) ?? '').toContain('mid-flip');
		const again = await storeOn(t);
		await again.ready;
		expect(again.get('mid-flip')).toBeUndefined();
		expect((await dbRows(t)).has('mid-flip')).toBe(false);
	});
});
