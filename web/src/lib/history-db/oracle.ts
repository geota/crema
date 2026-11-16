/**
 * `#lib/history-db/oracle.js` — stage 1's dev oracle (#310 PLAN W2, SPEC §3,
 * §6, N7). In dev and in the tests, each index answer the History window
 * takes is compared with the core's reference rules (`reference::filter_sort`
 * and `reference::stats`) over this tab's rows in memory, and a mismatch
 * throws. The reference is built into `de1-wasm` only with its `dev-oracle`
 * feature (`pnpm wasm:test`, `web/.wasm-test/`), so the production bundle
 * holds none of it, and this module loads nothing outside dev and tests.
 *
 * An answer is comparable only when the index and the rows are the same
 * history: this tab has nothing unwritten or untaken (`HistoryStore.inStep`),
 * the index has applied exactly the P3 seqs this tab knows of, and the index
 * hasn't been written since the answer was read. Otherwise the check is
 * skipped: a later answer comes.
 */
import type { BrewHistoryStats } from '#lib/core/crema-core.js';
import type { HistoryQuery, WindowAnswer } from './window.svelte.js';
import type { Live } from './protocol.js';

/** The reference rules, as `de1-wasm` built with `dev-oracle` exports them. */
export interface ReferenceFns {
	historyReferenceFilterSort(rows: string, query: string, live: string): string;
	historyReferenceStats(rows: string, query: string, live: string): string;
}

/** An index answer that the reference rules don't give. */
export class HistoryOracleMismatch extends Error {
	constructor(
		readonly kind: string,
		readonly query: HistoryQuery,
		readonly detail: string
	) {
		super(`history dev oracle: the index's ${kind} differs from the reference rules: ${detail}`);
		this.name = 'HistoryOracleMismatch';
	}
}

const env = (import.meta as { env?: { DEV?: boolean; MODE?: string } }).env;
/** Dev or tests: the only builds the oracle runs in. */
export const ORACLE_ON = env?.DEV === true || env?.MODE === 'test';

let loading: Promise<ReferenceFns | null> | null = null;

/**
 * The reference rules: from `web/.wasm-test/` (`pnpm wasm:test`), in dev
 * and tests only. Null when it isn't built there (dev says so once), and
 * always in production.
 */
export function loadReference(): Promise<ReferenceFns | null> {
	if (!ORACLE_ON) return Promise.resolve(null);
	loading ??= (async () => {
		try {
			if (env?.MODE === 'test') {
				const { readFile } = await import('node:fs/promises');
				const { resolve } = await import('node:path');
				const bytes = await readFile(resolve(process.cwd(), '.wasm-test/de1_wasm_bg.wasm'));
				const mod = (await import(/* @vite-ignore */ resolve(process.cwd(), '.wasm-test/de1_wasm.js'))) as ReferenceFns & {
					initSync(m: { module: BufferSource }): unknown;
				};
				mod.initSync({ module: bytes });
				if (typeof mod.historyReferenceFilterSort !== 'function') throw new Error('built without dev-oracle');
				return mod;
			}
			const found = import.meta.glob('../../../.wasm-test/de1_wasm.js');
			const load = Object.values(found)[0];
			if (!load) throw new Error('not built');
			const mod = (await load()) as ReferenceFns & { default(): Promise<unknown> };
			await mod.default();
			if (typeof mod.historyReferenceFilterSort !== 'function') throw new Error('built without dev-oracle');
			return mod;
		} catch (e) {
			console.warn(`history dev oracle off: run \`pnpm wasm:test\` (${e instanceof Error ? e.message : String(e)})`);
			return null;
		}
	})();
	return loading;
}

/** Test seam: a reference to use instead of loading one. */
export function setReferenceForTest(fns: ReferenceFns | null): void {
	loading = Promise.resolve(fns);
}

export interface OracleDeps<R> {
	/** This tab's rows, tombstones too, as stored (with their summary). */
	rows(): readonly R[];
	idOf(row: R): string;
	completedAtOf(row: R): number;
	/** The live libraries the index was given. */
	live(): Live;
	/** `HistoryStore.inStep`. */
	inStep(): { seq: number; quiet: boolean };
	/** Reads from the index, as the window does. */
	read<T>(name: 'indexState' | 'writeSeq' | 'count' | 'stats' | 'indexOf' | 'groupCounts', arg?: unknown): Promise<T>;
	/** Told of a mismatch, before it throws. */
	onMismatch?(m: HistoryOracleMismatch): void;
	/**
	 * Skip an answer that comes sooner than this after the last check began:
	 * dev keeps the page fast at 20,000 rows; the tests check every answer.
	 */
	throttleMs?: number;
}

/** Relative tolerance for the stats: SQLite sums in its own order (SPEC §7). */
const STATS_TOLERANCE = 1e-5;

function close(a: number | null | undefined, b: number | null | undefined): boolean {
	if (a == null || b == null) return a == null && b == null;
	const scale = Math.max(Math.abs(a), Math.abs(b), 1e-9);
	return Math.abs(a - b) / scale <= STATS_TOLERANCE;
}

const STAT_FIELDS = ['beansUsedG', 'avgWeightG', 'avgRatio', 'avgTimeS', 'avgRating'] as const;

/** Where `a` and `b` differ, or null. */
export function statsDiffer(a: BrewHistoryStats, b: BrewHistoryStats): string | null {
	if (a.count !== b.count) return `count ${a.count} vs ${b.count}`;
	if (!!a.mixedMethods !== !!b.mixedMethods) return `mixedMethods ${a.mixedMethods} vs ${b.mixedMethods}`;
	if ((a.scopeMethod ?? null) !== (b.scopeMethod ?? null)) return `scopeMethod ${a.scopeMethod} vs ${b.scopeMethod}`;
	for (const f of STAT_FIELDS) if (!close(a[f], b[f])) return `${f} ${a[f]} vs ${b[f]}`;
	return null;
}

/** The dev oracle over one tab's rows. */
export class DevOracle<R> {
	readonly #fns: ReferenceFns;
	readonly #d: OracleDeps<R>;
	#chain: Promise<void> = Promise.resolve();
	/** The reference's ids per query, for one state of the rows. */
	#cache = new Map<string, string[]>();
	#cacheKey = '';
	#rowsJson: string | null = null;
	#lastAt = -Infinity;
	/** Answers checked and skipped (not comparable then), for the tests. */
	checked = 0;
	skipped = 0;
	readonly mismatches: HistoryOracleMismatch[] = [];

	constructor(fns: ReferenceFns, d: OracleDeps<R>) {
		this.#fns = fns;
		this.#d = d;
	}

	/** Every check queued so far has run. */
	async settled(): Promise<void> {
		let c: Promise<void>;
		do {
			c = this.#chain;
			await c;
		} while (c !== this.#chain);
	}

	/** Checks `a` once the current checks have run. A mismatch throws, uncaught, in dev and tests. */
	check(a: WindowAnswer<R>): void {
		this.#chain = this.#chain.then(async () => {
			try {
				await this.#check(a);
			} catch (e) {
				if (!(e instanceof HistoryOracleMismatch)) return;
				this.mismatches.push(e);
				this.#d.onMismatch?.(e);
				console.error(e.message);
				// Uncaught, so a test fails and dev's console shows it.
				queueMicrotask(() => {
					throw e;
				});
			}
		});
	}

	/** The rows' state now: a new one empties the caches. */
	#state(key: string): void {
		if (key === this.#cacheKey) return;
		this.#cache = new Map();
		this.#cacheKey = key;
		this.#rowsJson = null;
	}

	/** The reference's ids for `q` over the rows as they are. */
	#ids(q: HistoryQuery, rowsJson: () => string): string[] {
		const key = JSON.stringify(q);
		let ids = this.#cache.get(key);
		if (!ids) {
			ids = JSON.parse(this.#fns.historyReferenceFilterSort(rowsJson(), key, JSON.stringify(this.#d.live()))) as string[];
			this.#cache.set(key, ids);
		}
		return ids;
	}

	async #check(a: WindowAnswer<R>): Promise<void> {
		const now = performance.now();
		if (now - this.#lastAt < (this.#d.throttleMs ?? 0)) return void this.skipped++;
		this.#lastAt = now;
		const before = this.#d.inStep();
		if (!before.quiet) return void this.skipped++;
		const st = await this.#d.read<{ sourceSeq: number; seenSeqs: number[] }>('indexState');
		const indexSeq = Number(await this.#d.read<number>('writeSeq'));
		if (st.sourceSeq !== before.seq || st.seenSeqs.length > 0) return void this.skipped++;
		// A page carries the seq it was read at: comparable only if nothing was written since.
		if (a.kind === 'page' && a.seq !== indexSeq) return void this.skipped++;
		// The rest are read again now, bracketed by the index's seq.
		let again: unknown = null;
		if (a.kind === 'count') again = Number(await this.#d.read<number>('count', a.query));
		else if (a.kind === 'stats') again = await this.#d.read<BrewHistoryStats>('stats', a.query);
		else if (a.kind === 'indexOf') {
			const n = await this.#d.read<number | null>('indexOf', { query: a.query, id: a.id });
			again = n == null ? null : Number(n);
		} else if (a.kind === 'groups') {
			again = (await this.#d.read<number[]>('groupCounts', { query: a.query, edgesMs: a.edges })).map(Number);
		}
		const after = this.#d.inStep();
		const indexAfter = Number(await this.#d.read<number>('writeSeq'));
		if (!after.quiet || after.seq !== before.seq || indexAfter !== indexSeq) return void this.skipped++;
		const rows = this.#d.rows();
		this.#state(`${after.seq}:${indexSeq}:${rows.length}`);
		const rowsJson = () => (this.#rowsJson ??= JSON.stringify(rows));
		this.checked++;
		const q = a.query;
		const ids = () => this.#ids(q, rowsJson);
		switch (a.kind) {
			case 'count': {
				if (again !== ids().length) throw new HistoryOracleMismatch('count', q, `${again} vs ${ids().length}`);
				return;
			}
			case 'page': {
				const got = a.rows.map((r) => this.#d.idOf(r));
				const want = ids().slice(a.start, a.start + got.length);
				if (got.join('\n') !== want.join('\n')) {
					const at = got.findIndex((id, i) => id !== want[i]);
					throw new HistoryOracleMismatch('page', q, `at ${a.start + at}: ${got[at]} vs ${want[at]}`);
				}
				return;
			}
			case 'indexOf': {
				const i = ids().indexOf(a.id);
				const want = i < 0 ? null : i;
				if (again !== want) throw new HistoryOracleMismatch('indexOf', q, `${a.id}: ${again} vs ${want}`);
				return;
			}
			case 'groups': {
				const at = new Map(rows.map((r) => [this.#d.idOf(r), this.#d.completedAtOf(r)]));
				const want = new Array<number>(a.edges.length + 1).fill(0);
				for (const id of ids()) {
					const t = at.get(id) ?? 0;
					const g = a.edges.findIndex((e) => t >= e);
					want[g < 0 ? a.edges.length : g]++;
				}
				const got = again as number[];
				if (got.join() !== want.join()) throw new HistoryOracleMismatch('groupCounts', q, `${got} vs ${want}`);
				return;
			}
			case 'stats': {
				const want = JSON.parse(this.#fns.historyReferenceStats(rowsJson(), JSON.stringify(q), JSON.stringify(this.#d.live()))) as BrewHistoryStats;
				const diff = statsDiffer(again as BrewHistoryStats, want);
				if (diff) throw new HistoryOracleMismatch('stats', q, diff);
				return;
			}
		}
	}
}
