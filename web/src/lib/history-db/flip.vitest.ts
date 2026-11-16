/**
 * #310 W5: the flip (SPEC §11 stage 2, M2, M4, M6, M8, M9, S9, S13), over the
 * real store in Node (both pools in memory) and the P3 store in the fake
 * IndexedDB, with the W1 kit's locks, channels and Workers. `chooseStage`
 * runs as a fresh boot does; a "reboot" is another `chooseStage` over the
 * same storage, disk and IndexedDB.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { summarize } from '#lib/history/summary.js';
import { freshStorage } from '#lib/history/idb-test-kit.js';
import { HistoryDb, ROWS, META, type RowRecord } from '#lib/history/history-db.js';
import type { StoredShot } from '#lib/history/model.js';
import { historyWasmBuilt, callDb } from '#lib/history-source/sqlite-kit.js';
import { openTab, type Env, type Tab } from './test-kit.js';
import { flipDisk, flipEnv, stageDeps, type DoneRecordBox, type FlipDisk } from './flip-kit.js';
import { seedP3, p3Db, indexReady } from './w2-kit.js';
import { chooseStage, type StageChoice } from './stage.js';
import { FLIP_RETRY_MS, readMarker, parseDoneRecord } from './marker.js';
import { canonical, runFlip, type FlipArgs } from './flip.js';
import { p3Source } from './p3-source.js';
import { MARKER_KEY, STAGE_LOCK, RPC_CHANNEL } from './names.js';

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

/** Every P3 row, by id, as stored. */
async function p3Rows(): Promise<Map<string, RowRecord>> {
	const db = p3Db();
	try {
		return new Map((await db.allRows()).map((r) => [r.id, r]));
	} finally {
		db.close();
	}
}

/** Raw IndexedDB on `crema-history`, past `HistoryDb` (no `writeSeq` bump): the mutations verify must catch. */
async function rawRows(f: (rows: IDBObjectStore) => void): Promise<void> {
	const db = await new Promise<IDBDatabase>((resolve, reject) => {
		const r = indexedDB.open('crema-history');
		r.onsuccess = () => resolve(r.result);
		r.onerror = () => reject(r.error);
	});
	await new Promise<void>((resolve, reject) => {
		const tx = db.transaction([ROWS, META], 'readwrite');
		f(tx.objectStore(ROWS));
		tx.oncomplete = () => resolve();
		tx.onerror = () => reject(tx.error);
	});
	db.close();
}

let lastPage: string | null = null;
/**
 * A fresh page boot. The page before it is gone (a reload), with the stage
 * lock it held, unless `keep`: then both are open.
 */
function boot(o: Parameters<typeof stageDeps>[2] & { keep?: boolean } = {}): Promise<StageChoice> {
	if (lastPage && !o.keep) env.kill(lastPage);
	const deps = stageDeps(env, done, o);
	lastPage = deps.page;
	return chooseStage(deps);
}

/** A tab on `choice`'s stage. */
function tabOn(choice: Pick<StageChoice, 'stage' | 'expect' | 'target'>): Tab {
	const t = openTab(env, { stage: choice.stage, expect: choice.expect, target: choice.target });
	tabs.push(t);
	return t;
}

/** Every row of `history.db` as a stage-2 tab reads it. */
async function dbRows(t: Tab): Promise<Map<string, unknown>> {
	const out = new Map<string, unknown>();
	let after = 0;
	for (;;) {
		const page = await t.client.read<{ rows: { rid: number; id: string }[] }>('syncRows', { afterRid: after, limit: 500 });
		if (page.rows.length === 0) break;
		const ids = page.rows.map((r) => r.id);
		for (let i = 0; i < ids.length; i += 100) {
			for (const g of await t.client.read<{ id: string; rowJson: string }[]>('get', ids.slice(i, i + 100))) out.set(g.id, JSON.parse(g.rowJson));
		}
		after = page.rows[page.rows.length - 1].rid;
	}
	return out;
}

/** The marker as it stands. */
const marker = () => readMarker({ getItem: (k) => env.storage.getItem(k), setItem: () => {} });

describe.runIf(historyWasmBuilt)('the flip', () => {
	it('copies, verifies, writes the done-record then the marker, and boots stage 2 on the same rows', async () => {
		const all = shots(300);
		await seedP3(all);
		const flips: unknown[] = [];
		env.bus.channel(RPC_CHANNEL, 'listener').onmessage = (e) => {
			if ((e.data as { t?: string }).t === 'flipped') flips.push(e.data);
		};
		const steps: string[] = [];
		const choice = await boot({ onFlipStep: (s) => steps.push(s) });
		expect(choice.reason).toBe('flipped');
		expect(choice.stage).toBe(2);
		expect(choice.flip).toMatchObject({ outcome: 'done', generation: 0 });
		expect(steps).toEqual(expect.arrayContaining(['diff', 'copy', 'fts', 'verify', 'done', 'doneRecord']));
		expect(steps.indexOf('done')).toBeLessThan(steps.indexOf('doneRecord'));
		expect(marker()).toMatchObject({ state: 'done', generation: 0, poolGeneration: 0 });
		expect(parseDoneRecord(done.text ?? '')).toMatchObject({ generation: 0, poolGeneration: 0 });
		await new Promise((r) => setTimeout(r, 10));
		expect(flips).toHaveLength(1);
		// Every field of every row, compared.
		const t = tabOn(choice);
		await indexReady(t.client);
		const got = await dbRows(t);
		const want = await p3Rows();
		expect(got.size).toBe(300);
		for (const [id, r] of want) expect(canonical(got.get(id))).toBe(canonical(r));
		// The P3 store wasn't written.
		const db = p3Db();
		expect(await db.writeSeq()).toBe(Math.ceil(300 / 1000));
		db.close();
		// A reboot reads the marker: stage 2, no second flip.
		const again = await boot();
		expect(again).toMatchObject({ stage: 2, reason: 'marker' });
	});

	it('the same search results and stats as the index had before the flip', async () => {
		await seedP3(shots(120));
		const s1 = tabOn((await boot({ allowFlip: false })) as StageChoice);
		await indexReady(s1.client);
		const q = { text: 'espresso', sort: { key: 'date', dir: 'desc' } };
		const all = { sort: { key: 'rating', dir: 'asc' } };
		const before = {
			count: await s1.client.read('count', q),
			stats: await s1.client.read('stats', all),
			facets: await s1.client.read('facets', all),
			page: await s1.client.read('page', { query: all, anchor: 'start', limit: 64 })
		};
		await s1.client.stop();
		s1.close();
		tabs.splice(tabs.indexOf(s1), 1);
		const choice = await boot();
		expect(choice.stage).toBe(2);
		const s2 = tabOn(choice);
		await indexReady(s2.client);
		const after = {
			count: await s2.client.read('count', q),
			stats: await s2.client.read('stats', all),
			facets: await s2.client.read('facets', all),
			page: await s2.client.read('page', { query: all, anchor: 'start', limit: 64 })
		};
		const rows = (p: unknown) => (p as { rows: { id: string }[] }).rows.map((r) => r.id);
		expect(after.count).toEqual(before.count);
		expect(after.stats).toEqual(before.stats);
		expect(after.facets).toEqual(before.facets);
		expect(rows(after.page)).toEqual(rows(before.page));
	});

	it.each([300, 5_000])('a %i-row P3 store migrates with every field equal', async (n) => {
		await seedP3(shots(n));
		const choice = await boot();
		expect(choice.flip).toMatchObject({ outcome: 'done' });
		const t = tabOn(choice);
		await indexReady(t.client, 60_000);
		const got = await dbRows(t);
		const want = await p3Rows();
		expect(got.size).toBe(n);
		for (const [id, r] of want) expect(canonical(got.get(id))).toBe(canonical(r));
	}, 120_000);
});

describe.runIf(historyWasmBuilt)('when the flip may run (M2)', () => {
	it('not with another tab open: it holds the stage lock shared', async () => {
		await seedP3(shots(5));
		const other = tabOn(await boot({ allowFlip: false }));
		await indexReady(other.client);
		const choice = await boot();
		expect(choice).toMatchObject({ stage: 1, reason: 'waiting:otherTab' });
		expect(disk.files.db.size).toBe(0);
		expect(marker()).toMatchObject({ state: 'pending', failures: 0, waiting: 'another window is open' });
	});

	it('not when the service worker sees another window (a tab from before W1), or none', async () => {
		await seedP3(shots(5));
		expect(await boot({ windows: 2 })).toMatchObject({ stage: 1, reason: 'waiting:windows' });
		expect(await boot({ windows: null })).toMatchObject({ stage: 1, reason: 'waiting:noServiceWorker' });
		expect(disk.files.db.size).toBe(0);
	});

	it('never in a handover: a stage-1 client never flips, whoever owns', async () => {
		await seedP3(shots(5));
		const a = tabOn(await boot({ allowFlip: false }));
		await indexReady(a.client);
		const b = tabOn(await boot({ allowFlip: false }));
		a.close();
		tabs.splice(tabs.indexOf(a), 1);
		await indexReady(b.client);
		expect(disk.calls.some((c) => c.includes('flipCopyTo'))).toBe(false);
		expect(disk.files.db.size).toBe(0);
	});

	it('a tab opened during the flip waits for it, then reads done', async () => {
		await seedP3(shots(200));
		const first = boot();
		// The second boot starts while the first holds the stage lock exclusive.
		await new Promise((r) => setTimeout(r, 5));
		expect(env.locks.held.some((h) => h.name === STAGE_LOCK && h.mode === 'exclusive')).toBe(true);
		const second = boot({ keep: true });
		const [a, b] = await Promise.all([first, second]);
		expect(a).toMatchObject({ stage: 2, reason: 'flipped' });
		expect(b).toMatchObject({ stage: 2, reason: 'marker' });
	});

	it('the backoff: the next two boots try again, then at most daily', async () => {
		await seedP3(shots(5));
		disk.fault = (at) => {
			if (at.endsWith(':flipCopyTo')) throw { code: 'io', message: 'boom' };
		};
		const r1 = await boot();
		expect(r1).toMatchObject({ stage: 1 });
		expect(marker()).toMatchObject({ state: 'pending', failures: 1, nextTryAt: 0 });
		await boot();
		expect(marker()).toMatchObject({ failures: 2, nextTryAt: 0 });
		const t0 = Date.now();
		await boot();
		const m = marker();
		expect(m).toMatchObject({ failures: 3 });
		expect(m?.state === 'pending' && m.nextTryAt).toBeGreaterThanOrEqual(t0 + FLIP_RETRY_MS - 1000);
		// Within the day: no try at all.
		const calls = disk.calls.length;
		expect(await boot()).toMatchObject({ stage: 1, reason: 'backoff' });
		expect(disk.calls.length).toBe(calls);
	});
});

describe.runIf(historyWasmBuilt)('verify (SPEC §11 step 7): each check has a mutation that fails it', () => {
	async function flipWith(tamper: (step: string) => Promise<void> | void, args: Partial<FlipArgs> = {}) {
		return runFlip(
			{
				engine: disk.engine('direct'),
				source: p3Source(),
				writeDoneRecord: async (t) => void (done.text = t),
				captured: () => captures,
				now: () => Date.now(),
				at: tamper
			},
			{ indexGeneration: 0, poolGeneration: 0, journal: 'exclusiveWal', rebuildIndex: false, ...args }
		);
	}
	let captures: { id: string; seq: number }[] = [];
	beforeEach(async () => {
		captures = [];
		await seedP3(shots(30));
	});

	it('passes untouched', async () => {
		expect(await flipWith(() => {})).toMatchObject({ outcome: 'done' });
	});

	it('a row edited past the write counter: the row check', async () => {
		const out = await flipWith(async (s) => {
			if (s !== 'verify') return;
			const rows = await p3Rows();
			const r = structuredClone([...rows.values()][3]);
			r.metadata = { ...r.metadata, notes: 'changed behind the counter' };
			await rawRows((st) => st.put(r));
		});
		expect(out).toMatchObject({ outcome: 'failed', rebuildIndex: true });
		expect((out as { reason: string }).reason).toMatch(/row differs/);
		expect(done.text).toBeNull();
	});

	it('a row added or removed past the counter: the id check, both ways', async () => {
		const added = await flipWith(async (s) => {
			if (s !== 'verify') return;
			const extra = { ...[...(await p3Rows()).values()][0], id: 'extra-row' };
			await rawRows((st) => st.put(extra));
		});
		expect((added as { reason: string }).reason).toMatch(/missing from the copy/);
		await rawRows((st) => st.delete('extra-row'));
		const removed = await flipWith(async (s) => {
			if (s !== 'verify') return;
			const first = [...(await p3Rows()).keys()][0];
			await rawRows((st) => st.delete(first));
		});
		expect((removed as { reason: string }).reason).toMatch(/isn't in the P3 store/);
	});

	it('another writer moved the P3 store: the writeSeq check (M2)', async () => {
		// The same row written again: every row still equal, only the counter moved.
		const out = await flipWith(async (s) => {
			if (s !== 'verify') return;
			const db = p3Db();
			const [r] = (await db.allRows()).slice(0, 1);
			const { shotOfRow } = await import('#lib/history/history-db.js');
			const { shot, summary } = shotOfRow(r);
			await db.putShots([{ shot, details: false, summary }], summarize);
			db.close();
		});
		expect(out).toMatchObject({ outcome: 'failed', rebuildIndex: false });
		expect((out as { reason: string }).reason).toMatch(/the P3 store moved/);
	});

	it('this tab’s own capture during the flip passes, and is named for step 8 (M9)', async () => {
		const out = await flipWith(async (s) => {
			if (s !== 'verify') return;
			const db = p3Db();
			const { seq } = await db.putShots([{ shot: { ...realShot(998), id: 'captured' }, details: true }], summarize);
			db.close();
			captures.push({ id: 'captured', seq });
		});
		expect(out).toMatchObject({ outcome: 'done', captured: ['captured'] });
	});

	it('a "capture" that names an existing row fails', async () => {
		const id = [...(await p3Rows()).keys()][0];
		const out = await flipWith(async (s) => {
			if (s !== 'verify') return;
			const db = p3Db();
			const { seq } = await db.putShots([{ shot: { ...realShot(997), id }, details: true }], summarize);
			db.close();
			captures.push({ id, seq });
		});
		expect((out as { reason: string }).reason).toMatch(/names an existing row/);
	});

	it.each([
		['derivedMismatches', ['r1'], /derived columns are stale/],
		['integrityCheck', ['row 3 missing from index'], /integrity_check/],
		['ftsIntegrityCheck', 'damaged', /search index check: damaged/],
		['ftsIntegrityCheck', 'notReady', /search index check: notReady/],
		['migrationSourceIds', ['only-one'], /migration_source differs/]
	])('%s answering %j fails it', async (method, answer, why) => {
		const engine = disk.engine('direct');
		const open = engine.open.bind(engine);
		engine.open = async (o) => {
			const db = await open(o);
			if (o.pool !== 'db') return db;
			return { ...db, call: async (m, a) => (m === method ? answer : db.call(m, a)), close: () => db.close() };
		};
		const out = await runFlip(
			{ engine, source: p3Source(), writeDoneRecord: async (t) => void (done.text = t), captured: () => [], now: () => Date.now() },
			{ indexGeneration: 0, poolGeneration: 0, journal: 'exclusiveWal', rebuildIndex: false }
		);
		expect(out.outcome).toBe('failed');
		expect((out as { reason: string }).reason).toMatch(why);
		// `notReady` isn't a pass, and nothing is `done` (REVIEW-448 note 5).
		const look = await disk.look('history.db');
		expect(await callDb(look, 'migrationState')).toBe('copying');
		look.close();
		look.free();
		expect(done.text).toBeNull();
	});

	it('a failed verify rebuilds the index whole before the next try (M4)', async () => {
		await flipWith(async (s) => {
			if (s !== 'verify') return;
			const r = structuredClone([...(await p3Rows()).values()][2]);
			r.metadata = { ...r.metadata, notes: 'behind the counter' };
			await rawRows((st) => st.put(r));
		});
		disk.calls.length = 0;
		// Put it back the same way, so P3 and the old index agree again only by a rebuild.
		const out = await flipWith(() => {}, { rebuildIndex: true });
		expect(out).toMatchObject({ outcome: 'done' });
		expect(disk.calls.filter((c) => c.endsWith(':removeAbsentBegin')).length).toBeGreaterThanOrEqual(2);
	});
});

describe.runIf(historyWasmBuilt)('crashes and leftovers (M8)', () => {
	/** Every call a clean flip makes, by position. */
	async function cleanCalls(): Promise<number> {
		freshStorage();
		const d = flipDisk();
		const e = flipEnv(d, { text: null });
		await seedP3(shots(12));
		const c = await chooseStage(stageDeps(e, { text: null }));
		expect(c.stage).toBe(2);
		return d.calls.length;
	}

	it('a fault before every call of the flip: never lost, never done half-way, and the next boot passes', async () => {
		const n = await cleanCalls();
		expect(n).toBeGreaterThan(10);
		for (let k = 1; k <= n; k++) {
			freshStorage();
			disk = flipDisk();
			done = { text: null };
			env = flipEnv(disk, done);
			await seedP3(shots(12));
			disk.fault = (at, i) => {
				if (i === k) throw { code: 'io', message: `fault at ${at}` };
			};
			const first = await boot();
			disk.fault = null;
			// A fault anywhere before the done-record: stage 1, the P3 store the truth.
			if (first.stage === 2) {
				expect(done.text, `call ${k}`).not.toBeNull();
			} else {
				expect(marker()?.state, `call ${k}`).not.toBe('done');
				expect(done.text, `call ${k}`).toBeNull();
			}
			const next = await boot();
			expect(next.stage, `call ${k}: ${next.reason}`).toBe(2);
			const t = tabOn(next);
			await indexReady(t.client);
			expect((await dbRows(t)).size, `call ${k}`).toBe(12);
			await t.client.stop();
			t.close();
			tabs.splice(tabs.indexOf(t), 1);
		}
	}, 300_000);

	it('the flip Worker killed at every step: the next boot passes', async () => {
		const steps = ['install', 'scan', 'p3', 'space', 'diff', 'copy', 'copied', 'fts', 'verify', 'done', 'doneRecord'];
		for (const step of steps) {
			freshStorage();
			disk = flipDisk();
			done = { text: null };
			env = flipEnv(disk, done);
			await seedP3(shots(8));
			const deps = stageDeps(env, done, {
				onFlipStep: (s) => {
					if (s === step) for (const w of deps.workers()) w.crash();
				}
			});
			lastPage = deps.page;
			const first = await chooseStage(deps);
			expect(first.stage, step).toBe(1);
			expect(marker(), step).toMatchObject({ state: 'pending', failures: 1, nextTryAt: 0 });
			const next = await boot();
			expect(next.stage, `${step}: ${next.reason}`).toBe(2);
		}
	}, 120_000);

	it('a half-done flip leaves history.db copying, which the next try overwrites in place', async () => {
		await seedP3(shots(10));
		disk.fault = (at) => {
			if (at.endsWith(':ftsIntegrityCheck')) throw { code: 'io', message: 'boom' };
		};
		expect((await boot()).stage).toBe(1);
		const look = await disk.look('history.db');
		expect(await callDb(look, 'migrationState')).toBe('copying');
		look.close();
		look.free();
		disk.fault = null;
		const next = await boot();
		expect(next).toMatchObject({ stage: 2, target: { file: 'history.db' } });
	});

	it('an unreadable leftover with no marker: the flip goes to history-1.db and never touches it', async () => {
		await seedP3(shots(6));
		disk.files.db.add('history.db');
		disk.unusable.set('db/history.db', 'corrupt');
		const choice = await boot();
		expect(choice).toMatchObject({ stage: 2, target: { file: 'history-1.db' } });
		expect(marker()).toMatchObject({ state: 'done', generation: 1 });
		// Nothing wrote to it: no successful open, no copy landed.
		expect(disk.unusable.get('db/history.db')).toBe('corrupt');
	});

	it('marker gone, done-record there: stage 2 from the done-record, never a re-migration', async () => {
		await seedP3(shots(6));
		await boot();
		env.storage.removeItem(MARKER_KEY);
		const calls = disk.calls.length;
		const again = await boot();
		expect(again).toMatchObject({ stage: 2, reason: 'doneRecord' });
		expect(disk.calls.length).toBe(calls);
		expect(marker()).toMatchObject({ state: 'done', rebuilt: 'doneRecord' });
	});

	it('marker and done-record gone: the finished file rebuilds both, never a new copy (S9)', async () => {
		await seedP3(shots(6));
		await boot();
		env.storage.removeItem(MARKER_KEY);
		done.text = null;
		const again = await boot();
		expect(again).toMatchObject({ stage: 2, reason: 'found', flip: { outcome: 'found', generation: 0 } });
		expect(disk.calls.filter((c) => c.endsWith(':flipCopyTo'))).toHaveLength(1);
		expect(done.text).not.toBeNull();
	});

	it('the marker rebuild picks the highest finished generation (a recovery file)', async () => {
		await seedP3(shots(4));
		await boot();
		// A recovery file, as the `lost` banner's choice makes it.
		const mod = await import('#lib/history-source/sqlite-kit.js').then((m) => m.historyWasm());
		const rec = await mod.HistoryDb.createRecovery(
			JSON.stringify({
				place: 'memory',
				file: disk.nameOf('db', 'history-2.db'),
				generation: 2,
				journal: 'truncate',
				recovery: { migrationSource: [], lateBaseline: '{}' }
			})
		);
		rec.close();
		rec.free();
		disk.files.db.add('history-2.db');
		env.storage.removeItem(MARKER_KEY);
		done.text = null;
		const again = await boot();
		expect(again).toMatchObject({ stage: 2, target: { file: 'history-2.db' } });
	});

	it('a done-record that doesn’t read never allows a flip', async () => {
		await seedP3(shots(4));
		done.text = '{ not json';
		const choice = await boot();
		expect(choice.stage).toBe(2);
		expect(disk.calls.some((c) => c.endsWith(':flipCopyTo'))).toBe(false);
	});

	it('marker done, the file gone: lost, no new empty file, no re-migration', async () => {
		await seedP3(shots(4));
		const choice = await boot();
		disk.files.db.clear();
		const again = await boot();
		expect(again).toMatchObject({ stage: 2, reason: 'marker' });
		const t = tabOn(again);
		await expect.poll(() => t.client.status.health, { timeout: 10_000 }).toBe('lost');
		expect(disk.files.db.size).toBe(0);
		expect(choice.stage).toBe(2);
	});

	it('marker done, the file foreign or corrupt: lost, and the file untouched', async () => {
		for (const code of ['foreign', 'corrupt']) {
			freshStorage();
			disk = flipDisk();
			done = { text: null };
			env = flipEnv(disk, done);
			await seedP3(shots(4));
			await boot();
			disk.unusable.set('db/history.db', code);
			const t = tabOn(await boot());
			await expect.poll(() => t.client.status.health, { timeout: 10_000 }).toBe('lost');
			expect(disk.unusable.get('db/history.db')).toBe(code);
			await t.client.stop();
			t.close();
			tabs.splice(tabs.indexOf(t), 1);
		}
	});

	it('marker done, the pool paused (a resume that didn’t take): unavailable, never lost (REVIEW-456 N3)', async () => {
		await seedP3(shots(4));
		await boot();
		disk.paused.add('db');
		disk.stuck.add('db');
		const t = tabOn(await boot());
		await expect.poll(() => t.client.status.health, { timeout: 10_000 }).toBe('unavailable');
		expect(t.client.status.reason).toMatch(/paused/);
		expect(disk.files.db.has('history.db')).toBe(true);
	});

});

describe.runIf(historyWasmBuilt)('quota (SPEC §11 step 3, Failure)', () => {
	it('too little room for the copy: no flip, nothing written to crema-db', async () => {
		await seedP3(shots(20));
		env = flipEnv(disk, done, { quota: () => ({ quota: 1_000, usage: 999 }) });
		const choice = await boot();
		expect(choice).toMatchObject({ stage: 1, reason: 'failed:quota' });
		expect(disk.files.db.size).toBe(0);
	});

	it('a full disk on a flip batch: stage 1 works, history.db stays copying, unread', async () => {
		await seedP3(shots(20));
		disk.fault = (at) => {
			if (at.endsWith(':maintain')) throw { code: 'full', message: 'SQLITE_FULL' };
		};
		const choice = await boot();
		expect(choice).toMatchObject({ stage: 1, reason: 'failed:quota' });
		disk.fault = null;
		const t = tabOn(choice);
		await indexReady(t.client);
		expect(await t.client.read('count', { sort: { key: 'date', dir: 'desc' } })).toBe(20);
		const look = await disk.look('history.db');
		expect(await callDb(look, 'migrationState')).toBe('copying');
		look.close();
		look.free();
	});
});

// The marker module's own rules.
describe('the marker', () => {
	it('a done marker is never replaced by a pending one', async () => {
		const { writeMarker } = await import('./marker.js');
		const store = new Map<string, string>();
		const s = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v) };
		writeMarker(s, { state: 'done', generation: 0, poolGeneration: 0, at: 1 });
		writeMarker(s, { state: 'pending', failures: 1, nextTryAt: 0, lastError: 'x', rebuildIndex: false });
		expect(readMarker(s)).toMatchObject({ state: 'done' });
	});

	it('generations name their files', async () => {
		const { dbFileName, generationOf } = await import('./marker.js');
		expect(dbFileName(0)).toBe('history.db');
		expect(dbFileName(3)).toBe('history-3.db');
		expect(generationOf('/history.db')).toBe(0);
		expect(generationOf('/history-12.db')).toBe(12);
		expect(generationOf('/history-index.db')).toBeNull();
		expect(generationOf('/history-snapshot.db')).toBeNull();
		expect(generationOf('/history-0.db')).toBeNull();
	});
});

void HistoryDb;
void (null as unknown as StoredShot);
