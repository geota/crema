/**
 * PLAN B1 in the browser (#310): the history store on seeded, real-shaped
 * databases of 5,000, 20,000 and 100,000 rows, measured through the Worker
 * on real OPFS (`sahpool`), against B1's budget table. The native half is
 * `core/de1-history/benches/history`; this page is its browser twin, and W0's
 * size and first-page checks read its numbers (`tests/browser/bench.spec.ts`).
 *
 * Every latency is a round trip from the main thread through `postMessage`,
 * as the app's calls will be (W1). Latency rows give the median of seven runs
 * after a warm-up. "Open to first page painted" starts a new Worker each run,
 * so it includes loading and compiling the wasm, the pool's install, the open,
 * `count` and the first page, and the paint of its rows.
 *
 *   /bench/history-db.html?rows=5000,20000&journal=exclusiveWal&auto=1
 *
 * Each run uses a pool generation of its own, so runs never share files.
 * Nothing here deletes: old generations stay until the origin's data is
 * cleared.
 */
import { W0 } from '../tests/browser/harness/client.ts';
import live from '../../core/de1-history/tests/fixtures/live.json';
import { HISTORY_JOURNAL } from '../src/lib/history-db/journal.ts';

const REPS = 7;
const PAGE = 64;
const BATCH = 500;
const TIERS = [5_000, 20_000, 100_000];
const SEARCHES = ['sour', 'guji', 'café', 'niche', 'hoffmann', '苦いが', 'fruity', 'zzqx'];
const KEYS = ['date', 'rating', 'profile', 'bean', 'yield', 'time'] as const;

/** B1's budgets in ms, at 5,000, 20,000 and 100,000 rows. */
export const BUDGETS = {
	open: [300, 400, 800],
	first: [30, 60, 150],
	next: [10, 10, 20],
	offset: [30, 80, 300],
	search: [30, 60, 150],
	scan: [50, 150, 600],
	stats: [20, 60, 250],
	facets: [30, 80, 300],
	insert: [10, 10, 15],
	edit: [10, 10, 15],
	bulk: [2_000, 6_000, 30_000],
	tx: [50, 50, 100]
} as const;
export type Measure = keyof typeof BUDGETS;

export type Line = { measure: string; key?: Measure; budgetMs?: number; ms?: number; bytes?: number; detail: string };
export type Result = { rows: number; journal: string; lines: Line[]; env: string };

const tierOf = (n: number): number | undefined => {
	const t = TIERS.indexOf(n);
	return t < 0 ? undefined : t;
};

function median(xs: number[]): number {
	const s = [...xs].sort((a, b) => a - b);
	return s[Math.floor(s.length / 2)];
}

/** One warm-up, then `REPS` runs: `[median, worst]`. */
async function repeat(f: () => Promise<unknown>): Promise<[number, number]> {
	await f();
	const runs: number[] = [];
	for (let i = 0; i < REPS; i++) {
		const t = performance.now();
		await f();
		runs.push(performance.now() - t);
	}
	return [median(runs), Math.max(...runs)];
}

const fmt = (ms: number): string =>
	ms >= 1000 ? `${(ms / 1000).toFixed(2)} s` : ms >= 10 ? `${ms.toFixed(1)} ms` : `${ms.toFixed(2)} ms`;
const mib = (b: number): string =>
	b >= 1024 * 1024 ? `${(b / 1024 / 1024).toFixed(1)} MiB` : `${(b / 1024).toFixed(1)} KiB`;

const nextFrame = () =>
	new Promise<void>((r) => requestAnimationFrame(() => setTimeout(r, 0)));

type Page = { rows: { id: string; rowJson: string }[]; last: unknown; startIndex?: number };

/** Draws a page's rows, as the list would: one line each. */
function paint(list: HTMLElement, page: Page) {
	list.replaceChildren(
		...page.rows.map((r) => {
			const row = JSON.parse(r.rowJson);
			const li = document.createElement('li');
			li.textContent = `${new Date(row.completedAt).toISOString().slice(0, 16)} ${row.profileName ?? row.brewMethod ?? ''} ${row.metadata?.rating ?? ''}`;
			return li;
		})
	);
}

/** Runs B1 at `n` rows in `journal` mode; `log` gets progress. */
export async function bench(
	n: number,
	journal: 'truncate' | 'exclusiveWal',
	list: HTMLElement,
	log: (s: string) => void = () => {}
): Promise<Result> {
	const t = tierOf(n);
	const lines: Line[] = [];
	const line = (measure: string, key: Measure | undefined, ms: number | undefined, detail = '') =>
		lines.push({ measure, key, ms, detail, budgetMs: key && t !== undefined ? BUDGETS[key][t] : undefined });

	// A generation of this run's own: runs never share a directory.
	const generation = 1000 + Math.floor(Math.random() * 1_000_000_000);
	const file = 'history-index.db';
	const opts = { place: 'index', file, role: 'index', journal };

	// ── Seed: the stage-1 build from empty, through `diffBatch` ──────────
	log(`${n}: seeding`);
	let w = new W0();
	await w.start();
	await w.call('install', { pool: 'index', generation });
	await w.call('open', { h: 'db', opts: { ...opts, create: true, generation: 1 } });
	await w.call('call', { h: 'db', m: 'setLive', a: live });
	const gen = (await w.call('gen', { n })) as { ms: number; meanBytes: number };
	const build = (await w.call('seed', { h: 'db', batch: BATCH, method: 'diffBatch' })) as {
		batches: number;
		totalMs: number;
		longestMs: number;
	};
	line('Stage-1 rebuild (empty index, diff batches)', 'bulk', build.totalMs, `${build.batches} batches of ${BATCH}; rows generated in ${fmt(gen.ms)}, mean ${Math.round(gen.meanBytes)} B`);
	line(`Longest transaction: a diff batch of ${BATCH}`, 'tx', build.longestMs, '');
	// Settle the search index's segments, as maintenance would.
	let merges = 0;
	let longestMerge = 0;
	for (;;) {
		const t0 = performance.now();
		const m = (await w.call('call', { h: 'db', m: 'ftsMergeStep' })) as { step: string; more?: boolean };
		longestMerge = Math.max(longestMerge, performance.now() - t0);
		merges++;
		if (!(m.step === 'merged' && m.more) || merges > 500) break;
	}
	line('Longest transaction: an FTS merge step', 'tx', longestMerge, `${merges} steps`);
	await w.call('close', { h: 'db' });
	await w.call('uninstall', { pool: 'index' });
	const slots = (await w.call('slots', { dir: `.crema-history-index-${generation}` })) as {
		name: string;
		size: number;
	}[];
	const dbBytes = slots.filter((s) => s.name === `/${file}`).reduce((s, x) => s + Math.max(x.size - 4096, 0), 0);
	const sideBytes = slots
		.filter((s) => s.name.startsWith(`/${file}-`))
		.reduce((s, x) => s + Math.max(x.size - 4096, 0), 0);
	line('Database size (rows, FTS, indexes)', undefined, undefined, `${mib(dbBytes)}, ${Math.round(dbBytes / n)} B a row; journal or WAL ${mib(sideBytes)}`);
	lines[lines.length - 1].bytes = dbBytes;
	w.terminate();

	// ── Open to first page painted, from a new Worker, three times ───────
	log(`${n}: open to first page`);
	const opens: number[] = [];
	let parts = '';
	for (let i = 0; i < 3; i++) {
		w = new W0();
		const t0 = performance.now();
		await w.start();
		const t1 = performance.now();
		await w.call('install', { pool: 'index', generation });
		const t2 = performance.now();
		await w.call('open', { h: 'db', opts });
		const t3 = performance.now();
		await w.call('call', { h: 'db', m: 'count', a: {} });
		const page = (await w.call('call', { h: 'db', m: 'page', a: { query: {}, anchor: 'start', limit: PAGE } })) as Page;
		const t4 = performance.now();
		paint(list, page);
		await nextFrame();
		const t5 = performance.now();
		opens.push(t5 - t0);
		parts = `Worker + wasm ${fmt(t1 - t0)}, install ${fmt(t2 - t1)}, open ${fmt(t3 - t2)}, count + page ${fmt(t4 - t3)}, paint ${fmt(t5 - t4)}`;
		if (i < 2) {
			await w.call('close', { h: 'db' });
			await w.call('uninstall', { pool: 'index' });
			w.terminate();
		}
	}
	line('Open to first page painted', 'open', median(opens), `median of 3 cold Workers (worst ${fmt(Math.max(...opens))}); last: ${parts}`);

	const call = (m: string, a?: unknown) => w.call('call', { h: 'db', m, a });

	// ── The list ─────────────────────────────────────────────────────────
	log(`${n}: list`);
	let worstFirst = 0;
	let worstSort = '';
	const perSort: string[] = [];
	for (const key of KEYS) {
		for (const dir of ['desc', 'asc']) {
			const query = { sort: { key, dir } };
			const [m] = await repeat(async () => {
				await call('count', query);
				await call('page', { query, anchor: 'start', limit: PAGE });
			});
			perSort.push(`${key} ${dir} ${fmt(m)}`);
			if (m > worstFirst) {
				worstFirst = m;
				worstSort = `${key} ${dir}`;
			}
		}
	}
	line('`count` + first page, any sort', 'first', worstFirst, `worst: ${worstSort}; ${perSort.join(', ')}`);

	const total = (await call('count', {})) as number;
	const top = (await call('page', { query: {}, anchor: 'start', limit: PAGE })) as Page;
	const [next] = await repeat(() => call('page', { query: {}, anchor: { after: top.last }, limit: PAGE }));
	line('Next page (keyset)', 'next', next, 'date desc, from the top');
	const [offset] = await repeat(() =>
		call('page', { query: {}, anchor: { offset: Math.max(total - PAGE, 0) }, limit: PAGE })
	);
	line('Offset jump to the end', 'offset', offset, `offset ${Math.max(total - PAGE, 0)}`);

	// ── Search, stats, facets ────────────────────────────────────────────
	log(`${n}: search`);
	let worstSearch = 0;
	let worstText = '';
	for (const text of SEARCHES) {
		const [m] = await repeat(async () => {
			await call('count', { text });
			await call('page', { query: { text }, anchor: 'start', limit: PAGE });
		});
		if (m > worstSearch) {
			worstSearch = m;
			worstText = text;
		}
	}
	line('Search, 3+ characters (count + first page)', 'search', worstSearch, `worst: "${worstText}"`);
	const [stats] = await repeat(() => call('stats', {}));
	line('Stats over all rows', 'stats', stats, '');
	const [facets] = await repeat(() => call('facets', {}));
	line('Facets', 'facets', facets, '');

	// ── Single writes ────────────────────────────────────────────────────
	log(`${n}: writes`);
	// This Worker is a new one: it needs the rows to edit.
	await w.call('gen', { n });
	let k = 0;
	const inserts: number[] = [];
	for (let i = 0; i < REPS + 1; i++) {
		const row = await w.call('genRow', { i: i % n, id: `shot:bench-${generation}-${k++}`, completedAt: 1_790_000_000_000 + k });
		const t0 = performance.now();
		await call('diffBatch', [{ rowJson: row }]);
		if (i) inserts.push(performance.now() - t0);
	}
	line('Insert one shot (row + FTS)', 'insert', median(inserts), 'one-row `diffBatch` (stage 1 writes the index this way)');
	const edits: number[] = [];
	for (let i = 0; i < REPS + 1; i++) {
		const row = await w.call('genRow', { i: n - 1, metadata: { rating: 1 + (i % 5) } });
		const t0 = performance.now();
		await call('diffBatch', [{ rowJson: row }]);
		if (i) edits.push(performance.now() - t0);
	}
	line('Edit (star tap)', 'edit', median(edits), 'the newest row, rating changed');

	// ── A search while the FTS table is rebuilt (the scan path) ──────────
	log(`${n}: FTS rebuild`);
	await call('startFtsRebuild');
	const [scan] = await repeat(async () => {
		await call('count', { text: 'guji' });
		await call('page', { query: { text: 'guji' }, anchor: 'start', limit: PAGE });
	});
	line('Search during an FTS rebuild (scan)', 'scan', scan, '"guji", count + first page');
	let longestStep = 0;
	let steps = 0;
	const r0 = performance.now();
	for (;;) {
		const t0 = performance.now();
		const m = (await call('maintain')) as { step: string };
		longestStep = Math.max(longestStep, performance.now() - t0);
		steps++;
		if (m.step === 'ftsSwapped' || m.step === 'idle' || steps > 10_000) break;
	}
	line('Longest transaction: an FTS rebuild batch', 'tx', longestStep, `${steps} steps, ${fmt(performance.now() - r0)} in all`);

	await w.call('close', { h: 'db' });
	await w.call('uninstall', { pool: 'index' });
	w.terminate();
	return { rows: n, journal, lines, env: navigator.userAgent };
}

/** The result as a Markdown table. */
export function markdown(r: Result): string {
	const out = [
		`#### ${r.rows.toLocaleString('en')} rows, ${r.journal} — ${r.env}`,
		'',
		'| Measure | Budget | Browser | | Detail |',
		'| --- | --- | --- | --- | --- |'
	];
	for (const l of r.lines) {
		const verdict = l.ms === undefined || l.budgetMs === undefined ? '' : l.ms <= l.budgetMs ? 'ok' : '**over**';
		out.push(
			`| ${l.measure} | ${l.budgetMs === undefined ? 'report' : fmt(l.budgetMs)} | ${l.ms === undefined ? '' : fmt(l.ms)} | ${verdict} | ${l.detail} |`
		);
	}
	return out.join('\n');
}

declare global {
	interface Window {
		bench: { run: (n: number, journal: 'truncate' | 'exclusiveWal') => Promise<Result>; markdown: typeof markdown };
	}
}

const list = document.getElementById('list') as HTMLElement;
const out = document.getElementById('out') as HTMLElement;
const status = document.getElementById('status') as HTMLElement;
window.bench = { run: (n, j) => bench(n, j, list, (s) => (status.textContent = s)), markdown };

async function runAll() {
	const q = new URLSearchParams(location.search);
	const sizes = (q.get('rows') ?? '5000,20000,100000').split(',').map(Number);
	const journal = (q.get('journal') ?? HISTORY_JOURNAL) as 'truncate' | 'exclusiveWal';
	for (const n of sizes) {
		const r = await window.bench.run(n, journal);
		out.textContent += markdown(r) + '\n\n';
	}
	status.textContent = 'done';
}

document.getElementById('run')?.addEventListener('click', () => void runAll());
if (new URLSearchParams(location.search).get('auto') === '1') void runAll();
