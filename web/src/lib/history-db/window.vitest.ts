/**
 * #310 W2: the History window over the stage-1 index (SPEC §7, §10, S14),
 * through the app's client and owner on the real store, with the dev oracle
 * checking every answer against the core's reference rules.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { flushSync } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import { freshStorage } from '#lib/history/idb-test-kit.js';
import { rangeCutoff, type HistoryRange } from '#lib/history/range.js';
import { historyQuery, dayEdges } from '#lib/history/index-query.js';
import type { SortField } from '#lib/history/list-filter.js';
import type { RowRecord } from '#lib/history/history-db.js';
import type { Tab } from './test-kit.js';
import { indexReady, p3Db, seedRows, stage1Tab, w2Env, type W2Env } from './w2-kit.js';
import { IndexWindow, PAGE, type HistoryQuery } from './window.svelte.js';
import { DevOracle, loadReference, type ReferenceFns } from './oracle.js';
import type { Live } from './protocol.js';

const FIXTURES = resolve(process.cwd(), '../core/de1-history/tests/fixtures');
const read = (name: string) => readFileSync(resolve(FIXTURES, name), 'utf8');
const fixtureRows: RowRecord[] = read('rows.web.jsonl')
	.trim()
	.split('\n')
	.map((l) => JSON.parse(l) as RowRecord);
const live = JSON.parse(read('live.json')) as Live;
const { now, queries } = JSON.parse(read('queries.json')) as {
	now: number;
	queries: {
		name: string;
		range: HistoryRange;
		text: string;
		profile: string | null;
		bean: string | null;
		method: string | null;
		tags: string[];
		pinnedOnly: boolean;
		sort: { key: string; dir: 'asc' | 'desc' };
	}[];
};
const FIELDS: Record<string, SortField> = { date: 'completedAt', rating: 'rating', profile: 'profileName', bean: 'beanName' };

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
let reference: ReferenceFns;
beforeAll(async () => {
	await initTestWasm();
	const r = await loadReference();
	if (!r) throw new Error('the dev oracle needs `pnpm wasm:test`');
	reference = r;
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});

let w: W2Env;
let tab: Tab;
const windows: IndexWindow<RowRecord>[] = [];
beforeEach(() => {
	freshStorage();
	w = w2Env();
});
afterEach(async () => {
	for (const win of windows.splice(0)) win.destroy();
	await tab?.client.stop().catch(() => {});
	tab?.close();
});

/**
 * The P3 store with `rows`, a tab whose index is in step with it, and the dev
 * oracle over the P3 store's rows as they are (nothing else writes them).
 */
async function start(rows: readonly RowRecord[], timeout?: number): Promise<{ oracle: DevOracle<RowRecord>; rows: () => RowRecord[] }> {
	await seedRows(rows);
	tab = stage1Tab(w);
	await indexReady(tab.client, timeout);
	await tab.client.live(live);
	let current: RowRecord[] = [...rows];
	let seq = await p3Db().writeSeq();
	const oracle = new DevOracle<RowRecord>(reference, {
		rows: () => current,
		idOf: (r) => r.id,
		completedAtOf: (r) => r.completedAt,
		live: () => live,
		inStep: () => ({ seq, quiet: true }),
		read: (name, arg) => tab.client.read(name, arg)
	});
	// Each write the tests make: the rows and seq the oracle compares with.
	refresh = async () => {
		const db = p3Db();
		const [rows, s] = [await db.allRows(), await db.writeSeq()];
		db.close();
		current = rows;
		seq = s;
	};
	return { oracle, rows: () => current };
}
let refresh: () => Promise<void> = async () => {};

/** `reads` over the tab's client, logging each read's name. */
function logged(log: string[]) {
	return {
		read: <T>(name: Parameters<Tab['client']['read']>[0], arg?: unknown) => {
			log.push(name);
			return tab.client.read<T>(name, arg);
		},
		onChange: (f: Parameters<Tab['client']['onChange']>[0]) => tab.client.onChange(f)
	};
}

function window(q: HistoryQuery, oracle: DevOracle<RowRecord> | null, extra: Partial<ConstructorParameters<typeof IndexWindow<RowRecord>>[0]> = {}) {
	const win = new IndexWindow<RowRecord>({
		query: q,
		reads: tab.client,
		decode: (j) => JSON.parse(j) as RowRecord,
		idOf: (r) => r.id,
		onAnswer: (a) => oracle?.check(a),
		...extra
	});
	windows.push(win);
	return win;
}

async function counted(win: IndexWindow<RowRecord>): Promise<number> {
	await vi.waitFor(() => expect(win.count).not.toBeNull());
	return win.count!;
}

/** Loads every page of `win`, one screen at a time. */
async function loadAll(win: IndexWindow<RowRecord>): Promise<string[]> {
	const n = await counted(win);
	const ids: string[] = [];
	for (let i = 0; i < n; i += PAGE) {
		win.visible(i, Math.min(n - 1, i + PAGE - 1));
		await vi.waitFor(() => expect(win.rowAt(i)).not.toBeNull());
	}
	for (let i = 0; i < n; i++) {
		win.visible(i, i);
		await vi.waitFor(() => expect(win.rowAt(i)).not.toBeNull());
		ids.push(win.rowAt(i)!.id);
	}
	return ids;
}

describe('every filter, sort and search, through the index', () => {
	it('lists each fixture query as the reference rules do, with no oracle mismatch', async () => {
		const { oracle, rows } = await start(fixtureRows);
		let ran = 0;
		for (const fq of queries) {
			const field = FIELDS[fq.sort.key];
			if (!field) continue;
			const q = historyQuery({
				text: fq.text,
				cutoff: rangeCutoff(fq.range, now),
				profile: fq.profile ?? 'all',
				pinnedOnly: fq.pinnedOnly,
				bean: fq.bean ?? 'all',
				method: fq.method ?? 'all',
				tags: fq.tags,
				sortField: field,
				sortDir: fq.sort.dir
			});
			const win = window(q, oracle, { edges: q.sort.key === 'date' ? dayEdges(new Date(now)) : null });
			const ids = await loadAll(win);
			const want = JSON.parse(reference.historyReferenceFilterSort(JSON.stringify(rows()), JSON.stringify(q), JSON.stringify(live))) as string[];
			expect(ids, fq.name).toEqual(want);
			await vi.waitFor(() => expect(win.stats).not.toBeNull());
			ran++;
		}
		await oracle.settled();
		expect(ran).toBeGreaterThan(30);
		expect(oracle.mismatches).toEqual([]);
		expect(oracle.checked).toBeGreaterThan(ran);
	}, 120_000);
});

describe('change notices (SPEC §10)', () => {
	const DATE: HistoryQuery = historyQuery({
		text: '',
		cutoff: null,
		profile: 'all',
		pinnedOnly: false,
		bean: 'all',
		method: 'all',
		tags: [],
		sortField: 'completedAt',
		sortDir: 'desc'
	});

	async function rate(row: RowRecord, rating: number): Promise<number> {
		const db = p3Db();
		const { shot, summary } = { shot: { ...row, metadata: { ...row.metadata, rating } }, summary: row.summary };
		const { summary: _s, ...fields } = shot;
		const seq = (await db.putShots([{ shot: fields as never, details: false, summary }], () => summary)).seq;
		db.close();
		await refresh();
		w.p3Channel('p3tab').postMessage({ type: 'rows', from: 'p3tab', ids: [row.id], seq });
		return seq;
	}

	it('a star tap refetches nothing when not sorted by rating: only the row', async () => {
		const { oracle } = await start(fixtureRows.slice(0, 150));
		const refetch: string[] = [];
		const reads: string[] = [];
		const win = window(DATE, oracle, { onRefetch: (why) => refetch.push(why), reads: logged(reads) });
		win.visible(0, 20);
		await vi.waitFor(() => expect(win.rowAt(0)).not.toBeNull());
		await vi.waitFor(() => expect(win.stats).not.toBeNull());
		reads.length = 0;
		const target = win.rowAt(3)!;
		await rate(target, target.metadata.rating === 5 ? 1 : 5);
		await vi.waitFor(() => expect(refetch).toContain('patch'));
		await vi.waitFor(() => expect(win.rowAt(3)!.metadata.rating).not.toBe(target.metadata.rating));
		expect(refetch).not.toContain('relist');
		expect(reads.filter((r) => r === 'count' || r === 'page')).toEqual([]);
		expect(reads).toContain('get');
	});

	it('sorted by rating, a star tap relists, and no id shows twice', async () => {
		const { oracle } = await start(fixtureRows);
		const q = { ...DATE, sort: { key: 'rating' as const, dir: 'desc' as const } };
		const refetch: string[] = [];
		const win = window(q, oracle, { onRefetch: (why) => refetch.push(why) });
		win.visible(0, 3 * PAGE);
		await vi.waitFor(() => expect(win.rowAt(3 * PAGE)).not.toBeNull());
		// The last row of the third page jumps to the top.
		const mover = win.rowAt(3 * PAGE - 1)!;
		await rate(mover, 5);
		await vi.waitFor(() => expect(refetch).toContain('relist'));
		await vi.waitFor(() => {
			const ids = new Set<string>();
			for (let i = 0; i < 4 * PAGE; i++) {
				const r = win.rowAt(i);
				if (!r) continue;
				expect(ids.has(r.id)).toBe(false);
				ids.add(r.id);
			}
			expect(win.loadedIndexOf(mover.id)).not.toBe(3 * PAGE - 1);
		});
	});

	it('a page reply delivered after a newer notice is dropped and read again (S14)', async () => {
		const { oracle } = await start(fixtureRows);
		const refetch: string[] = [];
		let hold: Promise<void> | null = null;
		const reads = {
			read: async <T>(name: Parameters<Tab['client']['read']>[0], arg?: unknown) => {
				const wait = name === 'page' ? hold : null;
				const out = await tab.client.read<T>(name, arg);
				if (wait) await wait;
				return out;
			},
			onChange: (f: Parameters<Tab['client']['onChange']>[0]) => tab.client.onChange(f)
		};
		const win = window(DATE, oracle, { onRefetch: (why) => refetch.push(why), reads });
		await counted(win);
		win.visible(0, 10);
		await vi.waitFor(() => expect(win.rowAt(0)).not.toBeNull());
		// Hold the next page read's reply until a write's notice has arrived.
		let release!: () => void;
		const held = new Promise<void>((r) => (release = r));
		hold = held;
		// Pages 0 and 1 are in (a page of lead); page 3 isn't.
		const target = [...fixtureRows].sort((a, b) => b.completedAt - a.completedAt)[3 * PAGE + 2];
		let noticed = false;
		const stop = tab.client.onChange(() => (noticed = true));
		win.visible(3 * PAGE, 3 * PAGE + 10);
		await new Promise((r) => setTimeout(r, 50));
		await rate(target, ((target.metadata.rating ?? 0) % 5) + 1);
		await vi.waitFor(() => expect(noticed).toBe(true));
		stop();
		release();
		await vi.waitFor(() => expect(refetch).toContain('stale'));
		hold = null;
		await vi.waitFor(() => expect(win.rowById(target.id)?.metadata.rating).not.toBe(target.metadata.rating));
	});

	it('a missed notice refetches the count and the visible pages', async () => {
		const { oracle } = await start(fixtureRows.slice(0, 80));
		const refetch: string[] = [];
		const win = window(DATE, oracle, { onRefetch: (why) => refetch.push(why) });
		win.visible(0, 10);
		await vi.waitFor(() => expect(win.rowAt(0)).not.toBeNull());
		// The next notice is lost on its way to this tab.
		const route = w.env.bus.route;
		let dropped = 0;
		w.env.bus.route = (m, from, to, name) =>
			name === 'crema-history-changes' && to === tab.ctx && dropped++ === 0 ? 'drop' : route(m, from, to, name);
		const sorted = fixtureRows.slice(0, 80).sort((a, b) => b.completedAt - a.completedAt);
		const other = (r: RowRecord) => ((r.metadata.rating ?? 0) % 5) + 1;
		await rate(sorted[1], other(sorted[1]));
		await vi.waitFor(() => expect(dropped).toBe(1));
		await rate(sorted[2], other(sorted[2]));
		await vi.waitFor(() => expect(refetch).toContain('gap'));
		// The lost notice's row too: everything was read again.
		await vi.waitFor(() => expect(win.rowAt(1)?.metadata.rating).toBe(other(sorted[1])));
	});
});

describe('position (SPEC §7)', () => {
	it('a deep link to row 15,000 of 20,000: indexOf and an offset jump, the page holds it', async () => {
		const many: RowRecord[] = [];
		const base = fixtureRows[0];
		for (let i = 0; i < 20_000; i++) {
			many.push({ ...base, id: `shot:${String(i).padStart(6, '0')}`, completedAt: 1_700_000_000_000 + i * 60_000, deletedAt: null } as RowRecord);
		}
		await start(many, 90_000);
		const win = window(
			historyQuery({ text: '', cutoff: null, profile: 'all', pinnedOnly: false, bean: 'all', method: 'all', tags: [], sortField: 'completedAt', sortDir: 'desc' }),
			null
		);
		expect(await counted(win)).toBe(20_000);
		// Newest first: row 15,000 is the shot made 15,001st from the end.
		const id = `shot:${String(20_000 - 1 - 15_000).padStart(6, '0')}`;
		expect(await win.indexOf(id)).toBe(15_000);
		win.visible(15_000, 15_010);
		await vi.waitFor(() => expect(win.rowAt(15_000)?.id).toBe(id), { timeout: 10_000 });
		expect(win.loadedPages.length).toBeLessThanOrEqual(8);
		// Not listed: null, never a guess.
		expect(await win.indexOf('shot:nope')).toBeNull();
		flushSync();
	}, 150_000);
});
