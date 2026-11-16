/**
 * #310 W2: stage 1's index upkeep (SPEC §3 "Keeping the index in step, web",
 * §11 stage 1, M4, S2), over the real store in Node and the fake IndexedDB
 * the P3 store writes. The owner's Worker reads committed rows from
 * `crema-history` itself; no tab sends rows.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { summarize } from '#lib/history/summary.js';
import { boot, freshStorage } from '#lib/history/idb-test-kit.js';
import { HISTORY_CHANNEL } from '#lib/history/store.svelte.js';
import type { StoredShot } from '#lib/history/model.js';
import type { Tab } from './test-kit.js';
import { indexReady, p3Db, seedP3, stage1Tab, w2Env, type W2Env } from './w2-kit.js';
import { GAP_MS } from './index-sync.js';
import { P3_CHANNEL } from './names.js';
import type { ChangeSet } from './protocol.js';

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});

let w: W2Env;
const tabs: Tab[] = [];
beforeEach(() => {
	freshStorage();
	w = w2Env();
});
afterEach(async () => {
	for (const t of tabs.splice(0)) {
		await t.client.stop().catch(() => {});
		t.close();
	}
});

const shots = (n: number, from = 0) => Array.from({ length: n }, (_, i) => realShot(from + i, realSamples(4, (from + i) % 60)));
const ALL = { sort: { key: 'date', dir: 'desc' } };

function open(): Tab {
	const t = stage1Tab(w);
	tabs.push(t);
	return t;
}

/** `shot` written to `crema-history` as a tab's store writes it, and its seq. */
async function write(shot: StoredShot): Promise<number> {
	const db = p3Db();
	try {
		return (await db.putShots([{ shot, details: true }], summarize)).seq;
	} finally {
		db.close();
	}
}

/** What a tab's store posts after a write. */
function post(ids: string[], seq?: number, from = 'p3tab'): void {
	w.p3Channel(from).postMessage({ type: 'rows', from, ids, ...(seq === undefined ? {} : { seq }) });
}

async function rating(t: Tab, id: string): Promise<number | null> {
	const got = await t.client.read<{ id: string; rowJson: string }[]>('get', [id]);
	return got.length ? (JSON.parse(got[0].rowJson).metadata.rating ?? null) : null;
}

async function indexState(t: Tab): Promise<{ sourceSeq: number; seenSeqs: number[] }> {
	return t.client.read('indexState');
}

describe('the index follows the P3 store', () => {
	it('names the P3 store’s own channel', () => {
		expect(P3_CHANNEL).toBe(HISTORY_CHANNEL);
	});

	it('a boot diff builds it; until then the owner says index_fallback and refuses its rows', async () => {
		await seedP3(shots(120));
		const t = open();
		await vi.waitFor(() => expect(t.client.status.owner).not.toBeNull(), { timeout: 10_000 });
		await indexReady(t.client);
		expect(await t.client.read('count', ALL)).toBe(120);
		const st = await indexState(t);
		expect(st).toMatchObject({ sourceSeq: await p3Db().writeSeq(), seenSeqs: [] });
		// The diff said why.
		expect(w.log.some((l) => l.includes('boot check') && l.includes('a full diff'))).toBe(true);
	});

	it('waits for a first boot’s migration, and never creates the P3 store', async () => {
		const t = open();
		await vi.waitFor(() => expect(t.client.status.health).toBe('index_fallback'), { timeout: 10_000 });
		const idb = (globalThis as { indexedDB: IDBFactory }).indexedDB;
		expect((await idb.databases()).some((d) => d.name === 'crema-history')).toBe(false);
		await seedP3(shots(3));
		await indexReady(t.client, 30_000);
		expect(await t.client.read('count', ALL)).toBe(3);
	}, 40_000);

	it('a write’s notice brings its row in, read from the P3 store', async () => {
		const rows = shots(5);
		await seedP3(rows);
		const t = open();
		await indexReady(t.client);
		const changes: ChangeSet[] = [];
		t.client.onChange((c) => changes.push(c));
		const edited = { ...rows[2], metadata: { ...rows[2].metadata, rating: 5 } };
		const seq = await write(edited);
		post([edited.id], seq);
		await vi.waitFor(async () => expect(await rating(t, edited.id)).toBe(5));
		expect(changes.at(-1)?.upserted).toEqual([edited.id]);
		expect(await indexState(t)).toEqual(expect.objectContaining({ sourceSeq: seq, seenSeqs: [] }));
	});

	it('two tabs edit one row and their notices arrive in the opposite order: the index holds the newer row (M4)', async () => {
		const rows = shots(3);
		await seedP3(rows);
		const t = open();
		await indexReady(t.client);
		const a = await write({ ...rows[0], metadata: { ...rows[0].metadata, rating: 2 } });
		const b = await write({ ...rows[0], metadata: { ...rows[0].metadata, rating: 4 } });
		post([rows[0].id], b, 'tabB');
		await vi.waitFor(async () => expect((await indexState(t)).seenSeqs).toEqual([b]));
		post([rows[0].id], a, 'tabA');
		await vi.waitFor(async () => expect(await indexState(t)).toMatchObject({ sourceSeq: b, seenSeqs: [] }));
		expect(await rating(t, rows[0].id)).toBe(4);
	});

	it('a delete’s notice takes the row out', async () => {
		const rows = shots(4);
		await seedP3(rows);
		const t = open();
		await indexReady(t.client);
		const db = p3Db();
		const seq = await db.delete([rows[1].id]);
		db.close();
		post([rows[1].id], seq);
		await vi.waitFor(async () => expect(await t.client.read('count', ALL)).toBe(3));
	});

	it('a writer killed between its commit and its notice leaves a gap, which a full diff closes in 2 s', async () => {
		const rows = shots(4);
		await seedP3(rows);
		const t = open();
		await indexReady(t.client);
		const lost = realShot(100);
		await write(lost); // never announced
		const next = realShot(101);
		const seq = await write(next);
		post([next.id], seq);
		await vi.waitFor(async () => expect((await indexState(t)).seenSeqs).toEqual([seq]));
		// Still answering meanwhile: the index is behind, not wrong.
		expect(t.client.status.health).toBe('ok');
		await vi.waitFor(async () => expect(await t.client.read('count', ALL)).toBe(6), { timeout: GAP_MS + 8_000 });
		expect(await indexState(t)).toEqual(expect.objectContaining({ sourceSeq: seq, seenSeqs: [] }));
	}, 20_000);

	it('an older build’s write, with no seq in its notice, is caught at the next boot', async () => {
		const rows = shots(4);
		await seedP3(rows);
		const first = open();
		await indexReady(first.client);
		await first.client.stop();
		first.close();
		tabs.splice(tabs.indexOf(first), 1);
		// An older build writes and says nothing the index can count.
		await write(realShot(200));
		const t = open();
		await indexReady(t.client);
		expect(await t.client.read('count', ALL)).toBe(5);
	});

	it('on resume, a write it missed runs a diff while the index keeps answering', async () => {
		await seedP3(shots(5));
		const t = open();
		await indexReady(t.client);
		const healths: string[] = [];
		t.client.onStatus((s) => healths.push(s.health));
		await write(realShot(400)); // while the page was away: never announced
		t.client.acquire(); // the page back in front
		await vi.waitFor(async () => expect(await t.client.read('count', ALL)).toBe(6), { timeout: 10_000 });
		expect(healths).not.toContain('index_fallback');
		expect(w.log.some((l) => l.includes('resume check'))).toBe(true);
	});

	it('a notice with no seq is a gap too', async () => {
		const rows = shots(2);
		await seedP3(rows);
		const t = open();
		await indexReady(t.client);
		const extra = realShot(300);
		await write(extra);
		await write(realShot(301)); // and another, unannounced
		post([extra.id]);
		await vi.waitFor(async () => expect(await t.client.read('count', ALL)).toBe(4), { timeout: GAP_MS + 8_000 });
	}, 20_000);

	it('the index deleted while the page is open falls back, and is rebuilt', async () => {
		await seedP3(shots(30));
		const t = open();
		await indexReady(t.client);
		const ctx = t.client.status.owner!.tab;
		const [workerCtx, engine] = [...w.engines.entries()].find(([k]) => k.startsWith(`${ctx}/`))!;
		expect(workerCtx).toBeTruthy();
		engine.lose('index', 'history-index.db');
		await expect(t.client.read('count', ALL)).rejects.toMatchObject({ code: 'cantOpen' });
		await vi.waitFor(() => expect(t.client.status.health).toBe('index_fallback'), { timeout: 5_000 });
		await indexReady(t.client);
		expect(await t.client.read('count', ALL)).toBe(30);
	}, 30_000);
});

describe('an owner that doesn’t answer (S2)', () => {
	it('a frozen owner leaves the other tab on its in-memory list, with P3 writes working: nothing goes read-only', async () => {
		await seedP3(shots(6));
		const owner = open();
		await indexReady(owner.client);
		const other = open();
		await indexReady(other.client);
		expect(other.client.status.ownerHere).toBe(false);
		owner.freeze();
		const store = await boot({ channel: () => w.p3Channel('p3tab') });
		// A read the frozen owner can't answer: the other tab falls back.
		void other.client.read('count', ALL).catch(() => {});
		await vi.waitFor(() => expect(other.client.status.health).toBe('index_fallback'), { timeout: 10_000 });
		expect(other.client.status.message).toBeNull();
		// The P3 store takes writes as usual.
		const id = store.all[0].id;
		store.setRating(id, 3);
		await store.flushed();
		expect((await p3Db().rowsOf([id])).get(id)?.metadata.rating).toBe(3);
		// Thawed, the owner catches up from the notice it was holding.
		owner.thaw();
		await indexReady(other.client, 20_000);
		await vi.waitFor(async () => expect(await rating(other, id)).toBe(3), { timeout: 10_000 });
	}, 40_000);
});

describe('every P3w write path bumps writeSeq (M4)', () => {
	it('putShots, delete and clearAll each commit a new seq, and clearAll never resets it', async () => {
		await seedP3(shots(2));
		const db = p3Db();
		const s0 = await db.writeSeq();
		const s1 = (await db.putShots([{ shot: realShot(9), details: true }], summarize)).seq;
		const s2 = await db.delete([realShot(9).id]);
		const s3 = await db.clearAll();
		expect([s1, s2, s3]).toEqual([s0 + 1, s0 + 2, s0 + 3]);
		expect(await db.writeSeq()).toBe(s3);
		db.close();
	});

	it('every notice the store posts carries the seq its write committed', async () => {
		await seedP3(shots(3));
		const heard: { ids: string[]; seq: number }[] = [];
		const listen = w.p3Channel('listener');
		listen.onmessage = (e) => heard.push(e.data as { ids: string[]; seq: number });
		const store = await boot({ channel: () => w.p3Channel('p3tab') });
		const [a, b] = store.all;
		store.setRating(a.id, 4);
		await store.flushed();
		store.setNotes(b.id, 'sweet');
		await store.flushed();
		store.delete(a.id, { deleteRemote: false });
		await store.flushed();
		await vi.waitFor(() => expect(heard.length).toBeGreaterThanOrEqual(3));
		const seqs = heard.map((m) => m.seq);
		expect(seqs.every((s) => typeof s === 'number')).toBe(true);
		expect([...seqs].sort((x, y) => x - y)).toEqual(seqs);
		expect(new Set(seqs).size).toBe(seqs.length);
		expect(seqs.at(-1)).toBe(await p3Db().writeSeq());
	});
});
