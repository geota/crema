/**
 * #310 W3: the consumers' queries. Each answers from the stage-1 index while
 * it holds exactly this tab's P3w rows, and from P3w otherwise; an index that
 * doesn't answer, lags, or moves under a query never answers as empty. Over
 * the real index in Node (the W2 kit) and the fake IndexedDB the P3 store
 * writes.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { boot, freshStorage } from './idb-test-kit';
import type { StoredShot } from './model';
import type { HistoryStore } from './store.svelte';
import { p3RowsOf } from './p3-rows';
import {
	bagRows,
	everyId,
	HistoryReads,
	liveCount,
	liveRows,
	newestRow,
	rowsNamed,
	whileFresh,
	type Query
} from './reads.svelte';
import type { HistoryDbClient } from '#lib/history-db/client.js';
import type { Tab } from '#lib/history-db/test-kit.js';
import { indexReady, seedP3, stage1Tab, w2Env, type W2Env } from '#lib/history-db/w2-kit.js';

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

const BAG = (i: number) => `bean:019e5fb5-0000-7000-8000-00000000000${i % 7}`;

/** 30 shots over seven bags: every fifth pinned, two tombstones, one Brew Log row. */
function shots(): StoredShot[] {
	return Array.from({ length: 30 }, (_, i) => {
		const s = realShot(i, realSamples(4, i % 60));
		return {
			...s,
			pinned: i % 5 === 0 ? true : undefined,
			deletedAt: i === 7 || i === 12 ? 1_767_000_000_000 : undefined,
			brewMethod: i === 3 ? 'v60' : s.brewMethod
		} as StoredShot;
	});
}

async function setup(): Promise<{ store: HistoryStore; client: HistoryDbClient; reads: HistoryReads }> {
	await seedP3(shots());
	const store = await boot({ channel: () => w.p3Channel('p3tab') });
	const t = stage1Tab(w);
	tabs.push(t);
	await indexReady(t.client);
	await fresh(store, t.client);
	const reads = new HistoryReads({ store: () => store, db: () => t.client, waitMs: 10_000 });
	return { store, client: t.client, reads };
}

/** Until the index holds what the store does. */
async function fresh(store: HistoryStore, db: HistoryDbClient): Promise<void> {
	await vi.waitFor(
		async () => expect(await whileFresh({ db, store, waitMs: 5_000 }, async () => true)).toEqual({ value: true }),
		{ timeout: 20_000, interval: 50 }
	);
}

/** Every question the consumers ask, over this history. */
function questions(store: HistoryStore): Query<unknown>[] {
	const ids = p3RowsOf(store).raw.map((s) => s.id);
	return [
		bagRows(BAG(0)),
		bagRows(BAG(3)),
		bagRows('bean:none'),
		liveRows(),
		newestRow(),
		liveCount(),
		liveCount({ pinnedOnly: true }),
		everyId(),
		rowsNamed([ids[0], ids[7], ids[20], 'shot:not-here'])
	] as Query<unknown>[];
}

/** A client whose reads `wrap` sees first. */
function wrapped(
	client: HistoryDbClient,
	wrap: (name: string, arg: unknown, next: () => Promise<unknown>) => Promise<unknown>
): HistoryDbClient {
	return {
		get status() {
			return client.status;
		},
		onStatus: (f: Parameters<HistoryDbClient['onStatus']>[0]) => client.onStatus(f),
		read: (name: Parameters<HistoryDbClient['read']>[0], arg?: unknown) => wrap(name, arg, () => client.read(name, arg))
	} as unknown as HistoryDbClient;
}

describe('each consumer’s question, through the index', () => {
	it('answers from the index, and its answer is P3w’s', async () => {
		const { store, reads } = await setup();
		for (const q of questions(store)) {
			const before = reads.compared;
			const got = await reads.answer(q);
			expect(reads.compared, `${q.key} came from the index`).toBe(before + 1);
			expect(q.differ(got, q.p3(p3RowsOf(store))), q.key).toBeNull();
		}
		expect(reads.mismatches).toEqual([]);
	}, 60_000);

	it('the counts and sets it gives', async () => {
		const { reads } = await setup();
		expect(await reads.answer(liveCount())).toBe(28);
		expect(await reads.answer(liveCount({ pinnedOnly: true }))).toBe(6);
		// Tombstones too: the Beanconqueror export's set.
		expect(await reads.answer(everyId())).toHaveLength(30);
		const bag = await reads.answer(bagRows(BAG(0)));
		expect(bag.map((s) => s.bean?.beanId)).toEqual(bag.map(() => BAG(0)));
		expect(bag.map((s) => s.completedAt)).toEqual([...bag.map((s) => s.completedAt)].sort((a, b) => b - a));
		expect((await reads.answer(newestRow()))?.id).toBe(realShot(0).id);
		expect(reads.mismatches).toEqual([]);
	}, 60_000);
});

describe('when the index isn’t fresh, P3w answers', () => {
	it('an edit this tab hasn’t written yet: P3w’s answer, with the edit', async () => {
		const { store, client, reads } = await setup();
		const id = realShot(1).id;
		store.setPinned(id, true);
		const before = reads.compared;
		expect(await reads.answer(liveCount({ pinnedOnly: true }))).toBe(7);
		expect(reads.compared).toBe(before);
		await store.flushed();
		await fresh(store, client);
		expect(await reads.answer(liveCount({ pinnedOnly: true }))).toBe(7);
		expect(reads.compared).toBe(before + 1);
	}, 60_000);

	it('an index behind this tab’s seq: P3w answers', async () => {
		const { store, client } = await setup();
		const behind = wrapped(client, async (name, _arg, next) => {
			const v = await next();
			return name === 'indexState' ? { ...(v as object), sourceSeq: store.inStep.seq - 1 } : v;
		});
		const reads = new HistoryReads({ store: () => store, db: () => behind, waitMs: 10_000 });
		expect(await reads.answer(liveCount())).toBe(28);
		expect(reads.compared).toBe(0);
	}, 60_000);

	it('a write between the query and its check: P3w answers, with that write', async () => {
		const { store, client } = await setup();
		const id = realShot(2).id;
		let wrote = false;
		const moving = wrapped(client, async (name, arg, next) => {
			const v = await next();
			if (name === 'count' && !wrote) {
				wrote = true;
				store.setPinned(id, true);
				await store.flushed();
			}
			return v;
		});
		const reads = new HistoryReads({ store: () => store, db: () => moving, waitMs: 10_000 });
		expect(await reads.answer(liveCount({ pinnedOnly: true }))).toBe(7);
		expect(reads.compared).toBe(0);
	}, 60_000);

	it('the index moving between a walk’s pages: P3w answers', async () => {
		const { store, client } = await setup();
		let pages = 0;
		const moving = wrapped(client, async (name, arg, next) => {
			const v = (await next()) as { seq?: number };
			return name === 'page' && ++pages === 2 ? { ...v, seq: Number(v.seq) + 1 } : v;
		});
		const reads = new HistoryReads({ store: () => store, db: () => moving, waitMs: 10_000 });
		const q = liveRows();
		const got = await reads.answer({ ...q, index: (read) => q.index((n, a) => (n === 'page' ? read(n, { ...(a as object), limit: 5 }) : read(n, a))) });
		expect(got).toHaveLength(28);
		expect(reads.compared).toBe(0);
	}, 60_000);

	it('an index that never answers: P3w answers after the wait, never an empty one', async () => {
		const { store, client } = await setup();
		const silent = wrapped(client, () => new Promise(() => {}));
		const reads = new HistoryReads({ store: () => store, db: () => silent, waitMs: 50 });
		for (const q of questions(store)) {
			const got = await reads.answer(q);
			expect(q.differ(got, q.p3(p3RowsOf(store))), q.key).toBeNull();
		}
		expect(await reads.answer(liveRows())).toHaveLength(28);
		expect(reads.compared).toBe(0);
	}, 60_000);

	it('an index that fails: P3w answers', async () => {
		const { store, client } = await setup();
		const failing = wrapped(client, async () => {
			throw { code: 'cantOpen', message: 'gone' };
		});
		const reads = new HistoryReads({ store: () => store, db: () => failing, waitMs: 10_000 });
		expect(await reads.answer(liveCount())).toBe(28);
	}, 60_000);

	it('index_fallback, or no Worker at all: P3w’s answer, at once', async () => {
		const { store, client } = await setup();
		const fallback = {
			status: { ...client.status, health: 'index_fallback' },
			onStatus: () => () => {},
			read: () => new Promise(() => {})
		} as unknown as HistoryDbClient;
		for (const db of [fallback, null]) {
			const reads = new HistoryReads({ store: () => store, db: () => db });
			expect(reads.now(liveCount())).toEqual({ value: 28 });
			expect(await reads.answer(liveCount())).toBe(28);
		}
	}, 60_000);
});

describe('the dev comparison', () => {
	it('an index answer P3w doesn’t give is a mismatch', async () => {
		const { store, client } = await setup();
		const wrong = wrapped(client, async (name, _arg, next) => {
			const v = await next();
			return name === 'count' ? Number(v) + 1 : v;
		});
		const seen: string[] = [];
		const reads = new HistoryReads({
			store: () => store,
			db: () => wrong,
			waitMs: 10_000,
			throwMismatch: false,
			onMismatch: (m) => seen.push(m.query)
		});
		expect(await reads.answer(liveCount())).toBe(29);
		expect(seen).toEqual(['liveCount:false']);
	}, 60_000);
});

describe('a watched answer', () => {
	it('comes from the index, and is asked again when the rows move', async () => {
		const { store, reads } = await setup();
		let watched: { value: number; answered: boolean } | null = null;
		const stop = $effect.root(() => {
			watched = reads.watch(() => liveCount({ pinnedOnly: true }), -1);
		});
		try {
			flushSync();
			// The index answers: not at once.
			expect(watched!.answered).toBe(false);
			expect(watched!.value).toBe(-1);
			await vi.waitFor(() => expect(watched!.value).toBe(6), { timeout: 10_000 });
			const asked = reads.compared;
			expect(asked).toBeGreaterThan(0);
			store.setPinned(realShot(4).id, true);
			flushSync();
			await vi.waitFor(() => expect(watched!.value).toBe(7), { timeout: 10_000 });
		} finally {
			stop();
		}
	}, 60_000);

	it('with no index to ask, is P3w’s answer at once, as before W3', async () => {
		const { store } = await setup();
		const reads = new HistoryReads({ store: () => store, db: () => null });
		let watched: { value: number; answered: boolean } | null = null;
		const stop = $effect.root(() => {
			watched = reads.watch(() => liveCount(), -1);
		});
		try {
			expect(watched!.value).toBe(28);
			expect(watched!.answered).toBe(true);
			store.setPinned(realShot(4).id, true);
			store.delete(realShot(5).id, { deleteRemote: false });
			flushSync();
			expect(watched!.value).toBe(27);
		} finally {
			stop();
		}
	}, 60_000);
});
