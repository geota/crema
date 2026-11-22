/**
 * `#lib/history/reads.svelte.js` — what the app's consumers ask of the history
 * (#310 PLAN W3, SPEC §7, §9, N1). Nothing outside the store reads its whole
 * list: each consumer asks one of the {@link Query}s here, which answers from
 * the stage-1 index while it is fresh, and from P3w's rows in memory
 * otherwise. In dev and tests each index answer is also compared with P3w's,
 * and a mismatch throws.
 *
 * - **Fresh** ({@link whileFresh}): this tab's index client answers (`ok`),
 *   this tab's store has loaded and has nothing unwritten or untaken
 *   (`HistoryStore.inStep`), the index has applied exactly the P3 seqs this
 *   tab knows of, and the index's `writeSeq` and this tab's seq are the same
 *   after the query as before it. Anything else, an error, or no answer in
 *   {@link FRESH_WAIT_MS}, and P3w answers: on stage 1 its rows are the truth,
 *   and the index only follows them.
 * - **Not loaded is not empty** (P3 spec's `ready` gates): {@link HistoryReads.answer}
 *   waits for the store's rows first and rejects as `whenReady` does, so a
 *   gated caller skips and tries later. An index that hasn't answered never
 *   answers as empty: P3w answers instead, once its wait is over.
 * - **Reactive** ({@link HistoryReads.watch}): a component's answer, asked
 *   again when the rows move. Where this tab has no usable index (no Worker,
 *   or `index_fallback`) it is P3w's answer at once, as the component read it
 *   before W3.
 * - **`page` is for display only** (the C2b review): a query that acts on
 *   each row (an export, a free-up plan, capture prune) walks the whole
 *   result between two equal seqs and dedupes by id, or reads by id (`get`).
 *
 * Sync and the upload passes read P3w through `sync-view.ts` instead (the
 * W3 decision; see there).
 */
import { untrack } from 'svelte';
import type { HistoryDbClient } from '#lib/history-db/client.js';
import { historyDb } from '#lib/history-db/start.js';
import type { HistoryQuery, RowOut } from '#lib/history-db/window.svelte.js';
import { ORACLE_ON } from '#lib/history-db/oracle.js';
import { collectSyncProjection } from '#lib/history-db/sync-projection.js';
import { decodeRow } from './index-list.svelte';
import type { ShotRow } from './model';
import { lastGrindsFromRows, type LastGrind } from '#lib/bean/grinder.js';
import { p3RowsOf, type P3Rows } from './p3-rows';
import { getHistoryStore, type HistoryStore } from './store.svelte';

/** How long a query waits for a fresh index answer before P3w answers. */
export const FRESH_WAIT_MS = 2_000;
/** Rows per page when a query walks a whole result. */
export const WALK_PAGE = 500;

/** A read of the index, as `HistoryDbClient.read`. */
export type IndexRead = <T>(name: Parameters<HistoryDbClient['read']>[0], arg?: unknown) => Promise<T>;

/** One question a consumer asks: its answer from the index, and from P3w's rows. */
export interface Query<T> {
	/** For messages, and to tell one watched question from the next. */
	readonly key: string;
	/** The answer from P3w's rows, as the consumer worked it out before W3. */
	p3(rows: P3Rows): T;
	/** The answer from the index. Throws when it can't give one. */
	index(read: IndexRead): Promise<T>;
	/** Where an index answer and P3w's differ, or null. */
	differ(index: T, p3: T): string | null;
}

/** The index moved under a walk: this answer belongs to no one state. */
class Moved extends Error {}

/** An index answer that P3w's rows don't give (dev and tests only). */
export class HistoryReadMismatch extends Error {
	constructor(
		readonly query: string,
		readonly detail: string
	) {
		super(`history reads: the index's answer to ${query} differs from P3w's: ${detail}`);
		this.name = 'HistoryReadMismatch';
	}
}

// ── The questions ───────────────────────────────────────────────────────

const BY_DATE = { key: 'date', dir: 'desc' } as const;

function listQuery(over: Partial<HistoryQuery> = {}): HistoryQuery {
	return {
		text: '',
		sinceMs: null,
		profile: null,
		bean: null,
		method: null,
		tagsAll: [],
		pinnedOnly: false,
		sort: { ...BY_DATE },
		...over
	};
}

interface PageOut {
	rows: RowOut[];
	last: string | null;
	atEnd: boolean;
	seq: number;
}

/**
 * Every row `q` lists, in its order, walked by keyset from one state of the
 * index (each page's seq the same), each id once.
 */
async function walkRows(read: IndexRead, q: HistoryQuery, limit = WALK_PAGE): Promise<ShotRow[]> {
	const out: ShotRow[] = [];
	const seen = new Set<string>();
	let anchor: 'start' | { after: string } = 'start';
	let seq: number | null = null;
	for (;;) {
		const page: PageOut = await read<PageOut>('page', { query: q, anchor, limit });
		if (seq === null) seq = Number(page.seq);
		else if (Number(page.seq) !== seq) throw new Moved('the index moved between pages');
		for (const r of page.rows) {
			if (seen.has(r.id)) continue;
			seen.add(r.id);
			out.push(decodeRow(r.rowJson));
		}
		if (page.atEnd || page.rows.length === 0 || !page.last) return out;
		anchor = { after: page.last };
	}
}

/** Two id lists as sets: where they differ, or null. */
function idsDiffer(a: readonly string[], b: readonly string[]): string | null {
	const as = new Set(a);
	const bs = new Set(b);
	if (as.size !== a.length) return `the index named a row twice (${a.length} rows, ${as.size} ids)`;
	const missing = b.filter((id) => !as.has(id));
	const extra = a.filter((id) => !bs.has(id));
	if (missing.length === 0 && extra.length === 0) return null;
	return `missing ${missing.slice(0, 3).join(', ') || 'none'}; extra ${extra.slice(0, 3).join(', ') || 'none'} (${a.length} vs ${b.length})`;
}

const rowIdsDiffer = (a: readonly ShotRow[], b: readonly ShotRow[]) =>
	idsDiffer(
		a.map((r) => r.id),
		b.map((r) => r.id)
	);

/**
 * A bag's live rows, newest first, matched by the frozen bean snapshot's id:
 * the bean drawer's shots and stock, the context card's doses, the
 * dashboard's dial-in card.
 */
export function bagRows(beanId: string): Query<ShotRow[]> {
	return {
		key: `bagRows:${beanId}`,
		p3: (rows) => rows.live.filter((s) => s.bean?.beanId === beanId),
		index: (read) => walkRows(read, listQuery({ bean: { bag: beanId } })),
		differ: rowIdsDiffer
	};
}

/**
 * The newest grind per grinder on a bag (#488), newest first: by grinder id,
 * and by name for rows from before grinder ids. Brew's pick order reads it
 * when no grind is saved for the active grinder.
 */
export function lastGrinds(beanId: string): Query<LastGrind[]> {
	return {
		key: `lastGrinds:${beanId}`,
		p3: (rows) => lastGrindsFromRows(rows.live.filter((s) => s.bean?.beanId === beanId)),
		index: (read) => read<LastGrind[]>('lastGrindsForBag', beanId),
		differ: (a, b) => {
			const of = (l: readonly LastGrind[]) => l.map((g) => `${g.grinderId ?? ''}|${g.grinder.trim().toLowerCase()}|${g.grind}`);
			const x = of(a).join(';');
			const y = of(b).join(';');
			return x === y ? null : `${x} vs ${y}`;
		}
	};
}

/** Every live row, newest first: the Brew Log's seeds, import dedup, exports, the free-up plan. */
export function liveRows(): Query<ShotRow[]> {
	return {
		key: 'liveRows',
		p3: (rows) => [...rows.live],
		index: (read) => walkRows(read, listQuery()),
		differ: rowIdsDiffer
	};
}

/** The newest live row, or null: the dashboard's just-finished shot. */
export function newestRow(): Query<ShotRow | null> {
	return {
		key: 'newestRow',
		p3: (rows) => rows.live[0] ?? null,
		async index(read) {
			const page = await read<PageOut>('page', { query: listQuery(), anchor: 'start', limit: 1 });
			const r = page.rows[0];
			return r ? decodeRow(r.rowJson) : null;
		},
		// Two rows of one time are both the newest: the order between them is the store's.
		differ: (a, b) =>
			(a?.completedAt ?? null) === (b?.completedAt ?? null) ? null : `${a?.id ?? 'none'} vs ${b?.id ?? 'none'}`
	};
}

/** How many live rows, or how many pinned: the counts in Settings. */
export function liveCount(opts: { pinnedOnly?: boolean } = {}): Query<number> {
	const pinnedOnly = opts.pinnedOnly === true;
	return {
		key: `liveCount:${pinnedOnly}`,
		p3: (rows) => (pinnedOnly ? rows.live.filter((s) => s.pinned === true).length : rows.live.length),
		index: async (read) => Number(await read<number>('count', listQuery({ pinnedOnly }))),
		differ: (a, b) => (a === b ? null : `${a} vs ${b}`)
	};
}

/**
 * Every row's id, tombstones too, newest first: the Beanconqueror export,
 * which writes a tombstone's brew as it did before W3. From the sync
 * projection, collected whole (S3); a held row is deleted, and left out.
 */
export function everyId(): Query<string[]> {
	return {
		key: 'everyId',
		p3: (rows) => rows.raw.map((s) => s.id),
		async index(read) {
			const p = await collectSyncProjection(read);
			return p.rows
				.filter((r) => !r.held)
				.sort((x, y) => y.completedAt - x.completedAt || (x.id < y.id ? 1 : x.id > y.id ? -1 : 0))
				.map((r) => r.id);
		},
		differ: idsDiffer
	};
}

/** The named rows this device holds, tombstones too (capture prune: which captures the history names). */
export function rowsNamed(ids: readonly string[]): Query<ShotRow[]> {
	const want = [...new Set(ids)];
	const wanted = new Set(want);
	return {
		key: `rowsNamed:${want.length}`,
		p3: (rows) => rows.raw.filter((s) => wanted.has(s.id)),
		async index(read) {
			const out: ShotRow[] = [];
			for (let i = 0; i < want.length; i += WALK_PAGE) {
				const got = await read<RowOut[]>('get', want.slice(i, i + WALK_PAGE));
				for (const r of got) out.push(decodeRow(r.rowJson));
			}
			return out;
		},
		differ(a, b) {
			// What prune reads of each: its time and its pin.
			const of = (rows: readonly ShotRow[]) => rows.map((s) => `${s.id}@${Math.round(s.completedAt)}${s.pinned === true ? '*' : ''}`);
			return idsDiffer(of(a), of(b));
		}
	};
}

// ── Fresh ───────────────────────────────────────────────────────────────

/** What freshness reads: this tab's index client and store. */
export interface FreshDeps {
	db: Pick<HistoryDbClient, 'read' | 'status'> | null;
	store: Pick<HistoryStore, 'loaded' | 'health' | 'inStep'>;
	waitMs?: number;
}

/** This tab can ask its index at all: a client that answers, over loaded rows on `crema-history`. */
export function indexUsable(d: Pick<FreshDeps, 'db' | 'store'>): boolean {
	return !!d.db && d.db.status.health === 'ok' && d.store.loaded && d.store.health === 'ok';
}

/**
 * `run` against the index, if the index holds exactly what this tab's P3
 * store does, before and after: its answer, or null (P3w answers). `atFresh`
 * runs at the moment the answer is known fresh, before anything else can
 * move, for the dev comparison.
 */
export async function whileFresh<T>(
	d: FreshDeps,
	run: (read: IndexRead) => Promise<T>,
	atFresh?: (value: T) => void
): Promise<{ value: T } | null> {
	if (!indexUsable(d) || !d.db) return null;
	const db = d.db;
	const read: IndexRead = (name, arg) => db.read(name, arg);
	const attempt = async (): Promise<{ value: T } | null> => {
		const before = d.store.inStep;
		if (!before.quiet) return null;
		const st = await read<{ sourceSeq: number; seenSeqs: number[] }>('indexState');
		if (Number(st.sourceSeq) !== before.seq || st.seenSeqs.length > 0) return null;
		const seq0 = Number(await read<number>('writeSeq'));
		const value = await run(read);
		const seq1 = Number(await read<number>('writeSeq'));
		const after = d.store.inStep;
		if (seq1 !== seq0 || !after.quiet || after.seq !== before.seq || !indexUsable(d)) return null;
		atFresh?.(value);
		return { value };
	};
	const going = attempt().catch(() => null);
	let timer: ReturnType<typeof setTimeout> | undefined;
	const late = new Promise<null>((resolve) => {
		timer = setTimeout(() => resolve(null), d.waitMs ?? FRESH_WAIT_MS);
	});
	try {
		return await Promise.race([going, late]);
	} finally {
		clearTimeout(timer);
	}
}

// ── The service ─────────────────────────────────────────────────────────

export interface ReadsDeps {
	store(): HistoryStore;
	db(): Pick<HistoryDbClient, 'read' | 'status' | 'onStatus'> | null;
	waitMs?: number;
	/** Told of a mismatch, before it throws. */
	onMismatch?(m: HistoryReadMismatch): void;
	/** Throw a mismatch, uncaught (the default): a test of the comparison itself turns it off. */
	throwMismatch?: boolean;
}

/** A watched answer: the current one, and whether it has come. */
export interface Watched<T> {
	readonly value: T;
	readonly answered: boolean;
}

export class HistoryReads {
	readonly #d: ReadsDeps;
	/** Moves when the client's status does: what makes a watched answer look again. */
	#statusTick = $state(0);
	#subscribed: object | null = null;
	/** Answers compared, and the mismatches, for the tests. */
	compared = 0;
	readonly mismatches: HistoryReadMismatch[] = [];

	constructor(d: ReadsDeps) {
		this.#d = d;
	}

	/** Follows the client's health, once one is there. */
	#follow(): void {
		const db = this.#d.db();
		if (!db || this.#subscribed === db) return;
		this.#subscribed = db;
		db.onStatus(() => this.#statusTick++);
	}

	#fresh(store: HistoryStore = this.#d.store()): FreshDeps {
		return { db: this.#d.db(), store, waitMs: this.#d.waitMs };
	}

	/** Whether this tab may ask its index now. Reactive on the client's health and the store's load. */
	indexUsable(store: HistoryStore = this.#d.store()): boolean {
		this.#follow();
		void this.#statusTick;
		return indexUsable(this.#fresh(store));
	}

	/**
	 * `q`'s answer: the index's while fresh, else P3w's. Waits for the store's
	 * rows first, and rejects as `whenReady` does (the caller skips). `store`
	 * is the app's unless named.
	 */
	async answer<T>(q: Query<T>, store: HistoryStore = this.#d.store()): Promise<T> {
		await store.whenReady();
		const got = await whileFresh(this.#fresh(store), (read) => q.index(read), (v) => this.#compare(q, v, store));
		return got ? got.value : q.p3(p3RowsOf(store));
	}

	/** P3w's answer now, where this tab has no usable index and the rows are in; else undefined. */
	now<T>(q: Query<T>, store: HistoryStore = this.#d.store()): { value: T } | undefined {
		if (this.indexUsable(store) || !store.loaded) return undefined;
		return { value: q.p3(p3RowsOf(store)) };
	}

	#compare<T>(q: Query<T>, index: T, store: HistoryStore): void {
		if (!ORACLE_ON) return;
		const p3 = q.p3(p3RowsOf(store));
		this.compared++;
		const diff = q.differ(index, p3);
		if (diff === null) return;
		const m = new HistoryReadMismatch(q.key, diff);
		this.mismatches.push(m);
		this.#d.onMismatch?.(m);
		if (this.#d.throwMismatch === false) return;
		console.error(m.message);
		// Uncaught, so a test fails and dev's console shows it.
		queueMicrotask(() => {
			throw m;
		});
	}

	/**
	 * `query()`'s answer for a component, asked again whenever the rows move
	 * or the query does. Call while a component initialises. Until an answer
	 * comes, `initial`; a null query is `initial`.
	 */
	watch<T>(query: () => Query<T> | null, initial: T, store: HistoryStore = this.#d.store()): Watched<T> {
		let got = $state.raw<{ key: string; value: T } | null>(null);
		const now = $derived.by(() => {
			const q = query();
			if (!q) return { value: initial };
			return this.now(q, store);
		});
		$effect(() => {
			if (now !== undefined) return;
			const q = query();
			if (!q) return;
			void store.rowsVersion;
			void store.loaded;
			let live = true;
			untrack(() =>
				this.answer(q, store).then(
					(value) => {
						if (live) got = { key: q.key, value };
					},
					() => {}
				)
			);
			return () => {
				live = false;
			};
		});
		return {
			get value() {
				if (now !== undefined) return now.value;
				const q = query();
				return got && q && got.key === q.key ? got.value : initial;
			},
			get answered() {
				if (now !== undefined) return true;
				const q = query();
				return !!got && !!q && got.key === q.key;
			}
		};
	}
}

let reads: HistoryReads | null = null;

/** The app's history reads, over its store and its index client. */
export function getHistoryReads(): HistoryReads {
	reads ??= new HistoryReads({ store: getHistoryStore, db: historyDb });
	return reads;
}

/**
 * Reads that never ask the index: P3w's answer, once the rows are in. For a
 * reader that deletes on a rule the index could only get wrong while it
 * trails (capture prune, REVIEW-472 N2).
 */
export function p3OnlyReads(): HistoryReads {
	return new HistoryReads({ store: getHistoryStore, db: () => null });
}

/** Test seam. */
export function setHistoryReadsForTest(r: HistoryReads | null): void {
	reads = r;
}
