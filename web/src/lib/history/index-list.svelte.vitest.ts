/**
 * #310 W2: the History list over the stage-1 index, as the page wires it
 * (SPEC §7): `count` and `rowAt(i)` into the windowed list, skeletons while a
 * page loads, a deep link far down, scroll memory through `index_of`, the day
 * headings by `group_counts`. The real store in Node, through the app's client
 * and owner, with the dev oracle on every answer.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import IndexListHost from '#lib/testing/IndexListHost.svelte';
import { installListLayout } from '#lib/testing/list-layout.js';
import { initTestWasm } from '#lib/testing/test-init.js';
import { resetViewMemory, writeScroll } from '#lib/state/view-memory.js';
import { freshStorage } from './idb-test-kit';
import type { RowRecord } from './history-db';
import type { ShotRow } from './model';
import { historyQuery, dayEdges } from './index-query';
import { DayLayout, type IndexList } from './index-list.svelte';
import type { Tab } from '#lib/history-db/test-kit.js';
import { indexReady, p3Db, seedRows, stage1Tab, w2Env, type W2Env } from '#lib/history-db/w2-kit.js';
import { DevOracle, loadReference } from '#lib/history-db/oracle.js';
import type { HistoryQuery, WindowAnswer } from '#lib/history-db/window.svelte.js';

const VIEW = 600;
const base = JSON.parse(
	readFileSync(resolve(process.cwd(), '../core/de1-history/tests/fixtures/rows.web.jsonl'), 'utf8').split('\n')[0]
) as RowRecord;

/** `n` rows a minute apart, the newest last; every third has a note. */
function rows(n: number): RowRecord[] {
	return Array.from({ length: n }, (_, i) => ({
		...base,
		id: `shot:${String(i).padStart(6, '0')}`,
		completedAt: 1_700_000_000_000 + i * 60_000,
		profileName: i % 2 ? 'Turbo' : 'Classic',
		metadata: { ...base.metadata, notes: i % 3 === 0 ? 'a note' : null, nextPlan: null },
		deletedAt: null
	}));
}
/** Row `i`'s id. */
const id = (i: number) => `shot:${String(i).padStart(6, '0')}`;

const Q = (over: Partial<Parameters<typeof historyQuery>[0]> = {}): HistoryQuery =>
	historyQuery({
		text: '',
		cutoff: null,
		profile: 'all',
		pinnedOnly: false,
		bean: 'all',
		method: 'all',
		tags: [],
		sortField: 'completedAt',
		sortDir: 'desc',
		...over
	});

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
let undoLayout: () => void;
let oracle: DevOracle<ShotRow>;
let seeded: RowRecord[] = [];
let w: W2Env;
let tab: Tab;
let app: Record<string, unknown> | null = null;
beforeAll(async () => {
	await initTestWasm();
	undoLayout = installListLayout(VIEW);
});
afterAll(() => {
	undoLayout();
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	resetViewMemory();
	freshStorage();
	w = w2Env();
});
afterEach(async () => {
	if (app) unmount(app);
	app = null;
	document.body.innerHTML = '';
	await tab?.client.stop().catch(() => {});
	tab?.close();
});

async function start(n: number): Promise<void> {
	seeded = rows(n);
	await seedRows(seeded);
	tab = stage1Tab(w);
	await indexReady(tab.client, 60_000);
	await tab.client.live({ beans: [], methods: [] });
	const fns = await loadReference();
	if (!fns) throw new Error('the dev oracle needs `pnpm wasm:test`');
	const seq = await p3Db().writeSeq();
	oracle = new DevOracle<ShotRow>(fns, {
		rows: () => seeded as unknown as ShotRow[],
		idOf: (r) => r.id,
		completedAtOf: (r) => r.completedAt,
		live: () => ({ beans: [], methods: [] }),
		inStep: () => ({ seq, quiet: true }),
		read: (name, arg) => tab.client.read(name, arg)
	});
}

type VL = { scrollTopForKey(k: string): number | null | 'pending'; reveal(k: string): boolean };

function host(props: { query: HistoryQuery; memoryKey: string; edges?: number[] | null }) {
	let vl: VL | null = null;
	let index: IndexList | null = null;
	let state = $state.raw({ ...props });
	app = mount(IndexListHost, {
		target: document.body,
		props: {
			reads: tab.client,
			get query() {
				return state.query;
			},
			get memoryKey() {
				return state.memoryKey;
			},
			get edges() {
				return state.edges ?? null;
			},
			onlist: (l: VL, i: IndexList) => {
				vl = l;
				index = i;
			},
			onanswer: (a: WindowAnswer<ShotRow>) => oracle.check(a)
		}
	});
	flushSync();
	return {
		scroller: () => document.querySelector<HTMLElement>('[data-test-scroller]')!,
		vl: () => vl!,
		index: () => index!,
		set: (next: Partial<typeof props>) => {
			state = { ...state, ...next };
			flushSync();
		}
	};
}

const rendered = () => [...document.querySelectorAll<HTMLElement>('[data-scroll-key]')].map((b) => b.dataset.scrollKey!);
const skeletons = () => document.querySelectorAll('[data-skeleton]').length;
const placeOf = (key: string) =>
	document.querySelector<HTMLElement>(`[data-scroll-key="${key}"]`)?.getBoundingClientRect().top ?? null;

async function settle(f: () => void, timeout = 10_000): Promise<void> {
	await vi.waitFor(
		() => {
			flushSync();
			f();
		},
		{ timeout, interval: 20 }
	);
}

describe('the list over the index', () => {
	it('draws skeletons until a page lands, keeps the DOM bounded, and says where each row sits (aria-setsize)', async () => {
		await start(20_000);
		const h = host({ query: Q(), memoryKey: 'k' });
		await settle(() => expect(h.index().count).toBe(20_000));
		await settle(() => expect(rendered().length).toBeGreaterThan(5));
		const row = document.querySelector(`[data-scroll-key="${id(19_999)}"]`)!.closest('[role=listitem]')!;
		expect(row.getAttribute('aria-setsize')).toBe('20000');
		expect(row.getAttribute('aria-posinset')).toBe('1');
		// A jump far down: skeletons first, then the rows, never more than a screen and a bit.
		h.scroller().scrollTop = 12_000 * 66;
		flushSync();
		expect(skeletons() + rendered().length).toBeLessThan(40);
		await settle(() => expect(skeletons()).toBe(0));
		expect(rendered().length).toBeLessThan(40);
		expect(h.index().window.loadedPages.length).toBeLessThanOrEqual(8);
		await oracle.settled();
		expect(oracle.mismatches).toEqual([]);
	}, 90_000);

	it('a deep link to row 15,000 brings it into view once its place is known', async () => {
		await start(20_000);
		const h = host({ query: Q(), memoryKey: 'k' });
		await settle(() => expect(h.index().count).toBe(20_000));
		const target = id(20_000 - 1 - 15_000);
		expect(await h.index().indexOf(target)).toBe(15_000);
		await tick();
		expect(h.vl().reveal(target)).toBe(true);
		await settle(() => expect(placeOf(target)).not.toBeNull());
		const at = placeOf(target)!;
		expect(at).toBeGreaterThan(0);
		expect(at).toBeLessThan(VIEW);
		await oracle.settled();
		expect(oracle.mismatches).toEqual([]);
	}, 90_000);

	it('scroll memory restores a row through index_of, across a filter change and back', async () => {
		await start(3_000);
		// Remembered earlier: row 1,000 of the date list, 12 px below the top.
		writeScroll('all', { top: 1, anchors: [{ key: id(3_000 - 1 - 1_000), offset: 12 }] });
		const h = host({ query: Q(), memoryKey: 'all' });
		await settle(() => expect(placeOf(id(1_999))).toBe(12));
		// Another filter: its own place, the top for one never visited.
		h.set({ query: Q({ profile: 'Turbo' }), memoryKey: 'turbo' });
		await settle(() => expect(h.scroller().scrollTop).toBe(0));
		// Back: the same row again, found by `index_of` though it isn't rendered.
		writeScroll('all', { top: 1, anchors: [{ key: id(1_999), offset: 12 }] });
		h.set({ query: Q(), memoryKey: 'all' });
		await settle(() => expect(placeOf(id(1_999))).toBe(12));
		await oracle.settled();
		expect(oracle.mismatches).toEqual([]);
	}, 60_000);

	it('scroll memory falls through a deleted anchor to the next one', async () => {
		await start(3_000);
		const db = p3Db();
		const seq = await db.delete([id(1_999)]);
		db.close();
		seeded = seeded.filter((r) => r.id !== id(1_999));
		w.p3Channel('p3tab').postMessage({ type: 'rows', from: 'p3tab', ids: [id(1_999)], seq });
		await vi.waitFor(async () => expect(await tab.client.read('count', Q())).toBe(2_999));
		writeScroll('all', {
			top: 5,
			anchors: [
				{ key: id(1_999), offset: 0 },
				{ key: id(1_998), offset: 80 }
			]
		});
		host({ query: Q(), memoryKey: 'all' });
		await settle(() => expect(placeOf(id(1_998))).toBe(80));
	}, 60_000);

	it('day headings go by group_counts, and the rows keep their places', async () => {
		await start(200);
		const now = new Date(1_700_000_000_000 + 199 * 60_000 + 3_600_000);
		const h = host({ query: Q(), memoryKey: 'k', edges: dayEdges(now) });
		await settle(() => expect(h.index().window.groups).not.toBeNull());
		await settle(() => expect(document.querySelectorAll('h2').length).toBeGreaterThan(0));
		const layout = new DayLayout(h.index().window.groups, 200, true);
		expect(layout.size).toBe(200 + h.index().window.groups!.filter((n) => n > 0).length);
		expect(layout.itemAt(0)).toEqual({ head: expect.any(String) });
		expect(layout.itemOfRow(0)).toBe(1);
		await oracle.settled();
		expect(oracle.mismatches).toEqual([]);
	});
});

describe('DayLayout', () => {
	it('puts a heading before each non-empty group, in list order', () => {
		const l = new DayLayout([2, 0, 1, 3], 6, true);
		expect(l.size).toBe(9);
		expect([0, 1, 2, 3, 4, 5, 6, 7, 8].map((i) => l.itemAt(i))).toEqual([
			{ head: 'Today' },
			{ row: 0 },
			{ row: 1 },
			{ head: 'This week' },
			{ row: 2 },
			{ head: 'Earlier' },
			{ row: 3 },
			{ row: 4 },
			{ row: 5 }
		]);
		expect(l.itemOfRow(3)).toBe(6);
		// Ascending: the oldest group first.
		const asc = new DayLayout([2, 0, 1, 3], 6, false);
		expect(asc.itemAt(0)).toEqual({ head: 'Earlier' });
		expect(asc.itemAt(4)).toEqual({ head: 'This week' });
	});

	it('draws no headings while the counts disagree with the list', () => {
		const l = new DayLayout([2, 1], 5, true);
		expect(l.size).toBe(5);
		expect(l.itemAt(0)).toEqual({ row: 0 });
	});
});
