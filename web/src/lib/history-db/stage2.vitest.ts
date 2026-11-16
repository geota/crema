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

function stage2Of(t: Tab): Stage2 {
	return { client: t.client, details: idbDetails(new HistoryDb({ expectExisting: () => true }), { bump: false }), storage: journalStorage() };
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
