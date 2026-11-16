/**
 * `#lib/history-db/window.svelte.js` — the History list's window over the
 * stage-1 index (#310 PLAN W2, SPEC §7, §10, S14). Android's `IndexWindow`
 * (A2), for web.
 *
 * - **What it holds:** the query's `count` (null until it has answered, so
 *   nothing says "empty" before then, rule 2), and pages of {@link PAGE} rows
 *   by page number, loaded around the visible range with a page of lead each
 *   way, at most {@link MAX_PAGES} kept. A row not loaded yet is `null`: the
 *   list draws a skeleton of the same height.
 * - **Reads:** keyset from a loaded neighbour of the same list order (cost
 *   doesn't grow with depth), an offset jump otherwise. The stats, the facets
 *   and the day groups are queries too, after the first page, so the list
 *   doesn't queue behind them on the store's one connection.
 * - **Change notices** (SPEC §10): one that can move this query's rows or
 *   order ({@link movesList}) refetches the count and the visible pages and
 *   drops the others; one that changes rows already loaded refetches only
 *   those, by id. A star tap under the date sort refetches nothing but the
 *   row (and, after {@link SETTLE_MS}, the stats).
 * - **Ordering** (S14): it subscribes before its first query; each page
 *   carries the `write_seq` it was read at, and one older than the last
 *   notice applied is dropped and read again. A gap in the notices' seqs
 *   refetches everything.
 * - **Pages are for display only** (the C2b review): an edit that moves a
 *   row across the cursor could show it twice, so a page that holds a row
 *   another kept page holds drops the other, and a keyed `{#each}` never
 *   sees one id twice.
 */
import type { BrewHistoryStats } from '#lib/core/crema-core.js';
import type { ChangeSet, ReadName } from './protocol.js';

/** Rows per page (SPEC §7). */
export const PAGE = 64;
/** Pages kept (SPEC §7). */
export const MAX_PAGES = 8;
/** The stats' and facets' debounce after a notice (SPEC §10). */
export const SETTLE_MS = 300;

/** The core's `SortKey`. */
export type SortKey = 'date' | 'rating' | 'profile' | 'bean' | 'yield' | 'time';

/** The core's `HistoryQuery`, as JSON (SPEC §6). */
export interface HistoryQuery {
	text: string;
	sinceMs: number | null;
	profile: string | null;
	bean: { bag: string } | { coffee: string } | null;
	method: string | null;
	tagsAll: string[];
	pinnedOnly: boolean;
	sort: { key: SortKey; dir: 'asc' | 'desc' };
}

/** The core's `Facets`. */
export interface Facets {
	profiles: [string, number][];
	methods: [string, number][];
	tags: [string, number][];
	coffees: { coffeeKey: string; name: string | null; roaster: string | null; lastMs: number; count: number; bags: number }[];
	pinned: number;
}

/** A row as the index gives it. */
export interface RowOut {
	id: string;
	rowJson: string;
}

interface PageOut {
	rows: RowOut[];
	startIndex: number | null;
	first: string | null;
	last: string | null;
	atStart: boolean;
	atEnd: boolean;
	seq: number;
}

type Anchor = 'start' | { after: string } | { before: string } | { offset: number } | { around: { id: string; before: number } };

/** What the window reads through: `HistoryDbClient`. */
export interface IndexReads {
	read<T = unknown>(name: ReadName, arg?: unknown): Promise<T>;
	onChange(f: (c: ChangeSet) => void): () => void;
}

/** A search needs this many characters (owner question 3): fewer is no search. */
export const MIN_SEARCH_CHARS = 3;

/**
 * Whether `text` may be a search: 3 characters once trimmed. The core folds
 * before it counts, which never adds characters, so this can say yes where
 * the core says no, never the reverse: a window refetches a little more often
 * than it must, never less.
 */
export function maySearch(text: string): boolean {
	return [...text.trim()].length >= MIN_SEARCH_CHARS;
}

const BIT: Record<SortKey, number> = { date: 1, rating: 2, profile: 4, bean: 8, yield: 16, time: 32 };

interface Kinds {
	set?: boolean;
	order?: number;
	facet?: boolean;
	stats?: boolean;
	text?: boolean;
	display?: boolean;
}

/** Whether any filter or search narrows `q`'s rows (the core's `is_filtered`). */
export function isFiltered(q: HistoryQuery): boolean {
	return (
		maySearch(q.text) ||
		q.sinceMs != null ||
		q.profile != null ||
		q.bean != null ||
		q.method != null ||
		q.tagsAll.length > 0 ||
		q.pinnedOnly
	);
}

/** The core's `ChangeKinds::moves_list`: the change can move `q`'s rows or their order. */
export function movesList(k: Kinds, q: HistoryQuery): boolean {
	return (
		!!k.set ||
		((k.order ?? 0) & BIT[q.sort.key]) !== 0 ||
		(!!k.facet && isFiltered(q)) ||
		(!!k.text && maySearch(q.text))
	);
}

/** The core's `ChangeKinds::moves_stats`: `q`'s stats can move. */
export function movesStats(k: Kinds, q: HistoryQuery): boolean {
	return !!k.stats || !!k.set || (!!k.facet && isFiltered(q)) || (!!k.text && maySearch(q.text));
}

interface Loaded<R> {
	rows: R[];
	first: string | null;
	last: string | null;
	/** The list order it was read under. */
	gen: number;
	seq: number;
}

export interface WindowOptions<R> {
	query: HistoryQuery;
	reads: IndexReads;
	/** A stored row's JSON as what the list draws. */
	decode(rowJson: string): R;
	idOf(row: R): string;
	pageSize?: number;
	maxPages?: number;
	settleMs?: number;
	/** Day-group edges (ms, newest first) to count, or none. */
	edges?: readonly number[] | null;
	/** Told of each answer the dev oracle checks (W2): the count and every page read. */
	onAnswer?(a: WindowAnswer<R>): void;
	/** Told when the window read again after a notice: for the tests. */
	onRefetch?(why: 'relist' | 'patch' | 'stale' | 'gap'): void;
}

/** An answer the window took, for the dev oracle. */
export type WindowAnswer<R> =
	| { kind: 'count'; query: HistoryQuery; count: number }
	| { kind: 'page'; query: HistoryQuery; start: number; rows: R[]; seq: number }
	| { kind: 'stats'; query: HistoryQuery; stats: BrewHistoryStats }
	| { kind: 'groups'; query: HistoryQuery; edges: readonly number[]; groups: number[] }
	| { kind: 'indexOf'; query: HistoryQuery; id: string; index: number | null };

/** A window over the index for one query. */
export class IndexWindow<R> {
	readonly query: HistoryQuery;
	readonly #o: WindowOptions<R>;
	readonly #size: number;
	readonly #max: number;
	readonly #settle: number;
	#pages = new Map<number, Loaded<R>>();
	#loading = new Set<number>();
	#gen = 0;
	/** The last notice's seq, or the first page's when no notice came yet. */
	#seq: number | null = null;
	#shown: [number, number] = [0, -1];
	#side = false;
	#statsTimer: ReturnType<typeof setTimeout> | null = null;
	#facetsTimer: ReturnType<typeof setTimeout> | null = null;
	#dead = false;
	readonly #unsubscribe: () => void;
	#edges: readonly number[] | null;
	readonly #indexOf = new Map<string, Promise<number | null>>();

	/** Rows the query lists; null until it has answered. */
	count = $state<number | null>(null);
	/** The index couldn't answer: the list falls back to the in-memory rows. */
	failed = $state.raw<unknown>(null);
	/** The stat strip over the query; null until it has answered. */
	stats = $state.raw<BrewHistoryStats | null>(null);
	/** The filter options beside the query, each over it minus its own dimension. */
	facets = $state.raw<Facets | null>(null);
	/** The day groups' counts, newest first; null until they answer, or without edges. */
	groups = $state.raw<number[] | null>(null);
	/** Moves whenever a page lands or goes: whatever reads {@link rowAt} reruns. */
	version = $state(0);
	/** Moves when the list's rows or order may have moved (a relist): places from before are stale. */
	listGen = $state(0);
	/** From the window's start to its first page, ms. */
	firstPageMs: number | null = null;
	readonly #createdAt = performance.now();

	constructor(o: WindowOptions<R>) {
		this.#o = o;
		// Plain data: it crosses `postMessage` as a structured clone.
		this.query = $state.snapshot(o.query) as HistoryQuery;
		this.#size = o.pageSize ?? PAGE;
		this.#max = o.maxPages ?? MAX_PAGES;
		this.#settle = o.settleMs ?? SETTLE_MS;
		this.#edges = o.edges ?? null;
		// Subscribed before the first query, so no notice falls between (S14).
		this.#unsubscribe = o.reads.onChange((c) => this.#onNotice(c));
		void this.#begin();
	}

	destroy(): void {
		this.#dead = true;
		this.#unsubscribe();
		if (this.#statsTimer) clearTimeout(this.#statsTimer);
		if (this.#facetsTimer) clearTimeout(this.#facetsTimer);
	}

	async #begin(): Promise<void> {
		await this.#refreshCount();
		this.#ensure();
		if (this.count === 0 || this.failed) this.#startSide();
	}

	/** The row at `i`, or null while its page loads. */
	rowAt(i: number): R | null {
		void this.version;
		return this.#pages.get(Math.floor(i / this.#size))?.rows[i % this.#size] ?? null;
	}

	/** A loaded row by id. */
	rowById(id: string): R | null {
		for (const p of this.#pages.values()) for (const r of p.rows) if (this.#o.idOf(r) === id) return r;
		return null;
	}

	/** `id`'s position among the loaded rows, without asking the index. */
	loadedIndexOf(id: string): number | null {
		void this.version;
		for (const [n, p] of this.#pages) {
			const i = p.rows.findIndex((r) => this.#o.idOf(r) === id);
			if (i >= 0) return n * this.#size + i;
		}
		return null;
	}

	/** Pages kept, for tests. */
	get loadedPages(): number[] {
		void this.version;
		return [...this.#pages.keys()].sort((a, b) => a - b);
	}

	/** Rows `first` to `last` are on screen: load what's missing around them. */
	visible(first: number, last: number): void {
		if (this.#shown[0] === first && this.#shown[1] === last) return;
		this.#shown = [first, last];
		this.#ensure();
	}

	/** `id`'s position in the list, or null when the query doesn't list it. */
	indexOf(id: string): Promise<number | null> {
		const key = `${this.#gen}:${id}`;
		let got = this.#indexOf.get(key);
		if (!got) {
			const at = this.loadedIndexOf(id);
			got =
				at !== null
					? Promise.resolve(at)
					: this.#guard(null, async () => {
							const n = await this.#o.reads.read<number | null>('indexOf', { query: this.query, id });
							const index = n == null ? null : Number(n);
							this.#o.onAnswer?.({ kind: 'indexOf', query: this.query, id, index });
							return index;
						});
			this.#indexOf.set(key, got);
		}
		return got;
	}

	/** The day groups cut at `edges` (newest first), or none. */
	dayEdges(edges: readonly number[] | null): void {
		const same = edges === this.#edges || (!!edges && !!this.#edges && edges.join() === this.#edges.join());
		if (same) return;
		this.#edges = edges;
		this.groups = null;
		if (edges) void this.#readGroups();
	}

	// ── Reads ──────────────────────────────────────────────────────────────

	async #guard<T>(fallback: T, f: () => Promise<T>): Promise<T> {
		try {
			return await f();
		} catch (e) {
			if (!this.#dead) this.failed = e;
			return fallback;
		}
	}

	async #refreshCount(): Promise<void> {
		const c = await this.#guard(null, () => this.#o.reads.read<number>('count', this.query));
		if (c === null || this.#dead) return;
		const n = Number(c);
		this.count = n;
		this.#o.onAnswer?.({ kind: 'count', query: this.query, count: n });
		const lastPage = n === 0 ? -1 : Math.floor((n - 1) / this.#size);
		let dropped = false;
		for (const p of [...this.#pages.keys()]) {
			if (p > lastPage) {
				this.#pages.delete(p);
				dropped = true;
			}
		}
		if (dropped) this.version++;
	}

	/** The visible pages and one of lead each way. */
	#wanted(): number[] {
		const c = this.count;
		if (c === null || c === 0) return [];
		const [first, last] = this.#shown;
		const lastPage = Math.floor((c - 1) / this.#size);
		const lo = Math.max(0, Math.floor(Math.max(0, first) / this.#size) - 1);
		const hi = Math.min(lastPage, Math.floor(Math.max(first, last, 0) / this.#size) + 1);
		const out: number[] = [];
		for (let p = lo; p <= hi; p++) out.push(p);
		return out;
	}

	#ensure(): void {
		if (this.failed || this.#dead) return;
		const wanted = this.#wanted();
		if (wanted.length === 0) return;
		for (const p of wanted) if (!this.#pages.has(p) && !this.#loading.has(p)) this.#load(p);
		if (this.#pages.size > this.#max) {
			const mid = (wanted[0] + wanted[wanted.length - 1]) / 2;
			const drop = [...this.#pages.keys()]
				.filter((p) => !wanted.includes(p))
				.sort((a, b) => Math.abs(b - mid) - Math.abs(a - mid))
				.slice(0, this.#pages.size - this.#max);
			for (const p of drop) this.#pages.delete(p);
			if (drop.length) this.version++;
		}
	}

	#load(p: number, offsetOnly = false): void {
		this.#loading.add(p);
		void this.#read(p, offsetOnly).finally(() => this.#loading.delete(p));
	}

	async #read(p: number, offsetOnly = false): Promise<void> {
		const atGen = this.#gen;
		const prev = offsetOnly ? undefined : this.#pages.get(p - 1);
		const next = offsetOnly ? undefined : this.#pages.get(p + 1);
		const anchor: Anchor =
			p === 0
				? 'start'
				: prev && prev.gen === atGen && prev.last
					? { after: prev.last }
					: next && next.gen === atGen && next.first
						? { before: next.first }
						: { offset: p * this.#size };
		const page = await this.#guard(null, () =>
			this.#o.reads.read<PageOut>('page', { query: this.query, anchor, limit: this.#size })
		);
		if (!page || this.#dead) return;
		const c = this.count;
		if (c === null) return;
		if (this.#gen !== atGen || (this.#seq !== null && page.seq < this.#seq)) {
			// The list moved, or a notice came after this read began (S14): read it again.
			this.#o.onRefetch?.('stale');
			if (this.#wanted().includes(p)) await this.#read(p, true);
			return;
		}
		const expected = Math.max(0, Math.min(this.#size, c - p * this.#size));
		if (anchor !== 'start' && !('offset' in anchor) && page.rows.length !== expected) {
			// A keyset page that doesn't fill its place: the count is behind; place it by offset.
			await this.#read(p, true);
			return;
		}
		if (this.#seq === null) this.#seq = page.seq;
		const rows = page.rows.map((r) => this.#o.decode(r.rowJson));
		this.#put(p, { rows, first: page.first, last: page.last, gen: atGen, seq: page.seq });
		this.#o.onAnswer?.({ kind: 'page', query: this.query, start: p * this.#size, rows, seq: page.seq });
	}

	/**
	 * Keep `page` at `p`, and drop any other kept page that shows one of its
	 * rows: a keyed `{#each}` throws on a duplicate key (the C2b review). The
	 * newer read wins; a dropped page that's still wanted reads again.
	 */
	#put(p: number, page: Loaded<R>): void {
		const ids = new Set(page.rows.map((r) => this.#o.idOf(r)));
		let clash = false;
		for (const [q, other] of [...this.#pages]) {
			if (q !== p && other.rows.some((r) => ids.has(this.#o.idOf(r)))) {
				this.#pages.delete(q);
				clash = true;
			}
		}
		this.#pages.set(p, page);
		this.version++;
		if (this.firstPageMs === null) {
			this.firstPageMs = performance.now() - this.#createdAt;
			this.#startSide();
		}
		if (clash) this.#ensure();
	}

	#startSide(): void {
		if (this.#side) return;
		this.#side = true;
		void this.#readStats();
		void this.#readFacets();
		if (this.#edges) void this.#readGroups();
	}

	async #readStats(): Promise<void> {
		const s = await this.#guard(null, () => this.#o.reads.read<BrewHistoryStats>('stats', this.query));
		if (!s || this.#dead) return;
		this.stats = s;
		this.#o.onAnswer?.({ kind: 'stats', query: this.query, stats: s });
	}

	async #readFacets(): Promise<void> {
		const f = await this.#guard(null, () => this.#o.reads.read<Facets>('facets', this.query));
		if (f && !this.#dead) this.facets = f;
	}

	async #readGroups(): Promise<void> {
		const edges = this.#edges;
		if (!edges) return;
		const g = await this.#guard(null, () =>
			this.#o.reads.read<number[]>('groupCounts', { query: this.query, edgesMs: edges })
		);
		if (!g || this.#dead || this.#edges !== edges) return;
		this.groups = g.map(Number);
		this.#o.onAnswer?.({ kind: 'groups', query: this.query, edges, groups: this.groups });
	}

	// ── Notices ────────────────────────────────────────────────────────────

	#onNotice(c: ChangeSet): void {
		if (this.#dead) return;
		const seq = Number(c.seq);
		const last = this.#seq;
		if (last !== null && seq <= last) return; // already in what was read
		this.#seq = seq;
		if (last !== null && seq !== last + 1) {
			// A notice went missing: everything again (S14).
			this.#o.onRefetch?.('gap');
			void this.#restart();
			return;
		}
		const k = (c.kinds ?? {}) as Kinds;
		const moves = movesList(k, this.query);
		if (moves) {
			this.#o.onRefetch?.('relist');
			void this.#relist();
		} else {
			const ids = [...c.upserted, ...c.deleted].filter((id) => this.rowById(id) !== null);
			if (ids.length > 0) {
				this.#o.onRefetch?.('patch');
				void this.#patch(ids);
			}
		}
		if (movesStats(k, this.query)) this.#later('stats');
		if (k.facet || k.set) this.#later('facets');
		if ((moves || k.set) && this.#edges) void this.#readGroups();
	}

	#later(what: 'stats' | 'facets'): void {
		if (!this.#side) return;
		const timer = what === 'stats' ? this.#statsTimer : this.#facetsTimer;
		if (timer) clearTimeout(timer);
		const t = setTimeout(() => {
			if (what === 'stats') {
				this.#statsTimer = null;
				void this.#readStats();
			} else {
				this.#facetsTimer = null;
				void this.#readFacets();
			}
		}, this.#settle);
		if (what === 'stats') this.#statsTimer = t;
		else this.#facetsTimer = t;
	}

	/** Everything again: a missed notice. */
	async #restart(): Promise<void> {
		await this.#relist();
		if (!this.#side) return;
		void this.#readStats();
		void this.#readFacets();
		if (this.#edges) void this.#readGroups();
	}

	/** The set or the order moved: the count, the visible pages again, the others dropped. */
	async #relist(): Promise<void> {
		this.#gen++;
		this.listGen++;
		this.#indexOf.clear();
		const before = this.#wanted();
		let dropped = false;
		for (const p of [...this.#pages.keys()]) {
			if (!before.includes(p)) {
				this.#pages.delete(p);
				dropped = true;
			}
		}
		if (dropped) this.version++;
		await this.#refreshCount();
		// The visible pages stay drawn until their new reads land (no flash of skeletons).
		for (const p of this.#wanted()) if (!this.#loading.has(p)) this.#load(p, true);
	}

	/** Rows on screen changed in place: read just those, and put them where they are. */
	async #patch(ids: string[]): Promise<void> {
		const atGen = this.#gen;
		const got = await this.#guard(null, () => this.#o.reads.read<RowOut[]>('get', ids));
		if (!got || this.#dead || this.#gen !== atGen) return;
		const rows = new Map(got.map((r) => [r.id, this.#o.decode(r.rowJson)]));
		let changed = false;
		for (const [p, page] of this.#pages) {
			if (!page.rows.some((r) => ids.includes(this.#o.idOf(r)))) continue;
			const next: R[] = [];
			let gone = false;
			for (const r of page.rows) {
				const id = this.#o.idOf(r);
				if (!ids.includes(id)) next.push(r);
				else if (rows.has(id)) next.push(rows.get(id)!);
				else gone = true;
			}
			// A row gone from the index (a held delete): the page reads again.
			if (gone) {
				this.#pages.delete(p);
				this.#load(p, true);
			} else this.#pages.set(p, { ...page, rows: next });
			changed = true;
		}
		if (changed) this.version++;
	}
}
