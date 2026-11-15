/**
 * `#lib/history/list-versions.svelte.js` — what kind of change the History
 * list just saw (#310 P5w, SPEC decision 5).
 *
 * About 15 derivations on the History page used to rerun on every change to
 * the rows, a star tap included: at 20,000 rows that is 350 to 700 ms on a
 * tablet. They read these versions instead of the rows:
 *
 *  - {@link ListVersions.list} moves when the set or the order changes, or a
 *    field a filter, facet, count or stat reads: `completedAt`,
 *    `profileName`, `brewMethod`, `tags`, `bean`, `barista`, `drinker`,
 *    `visualizerId`, `decentId`, `deletedAt`, `pinned`, and every field this
 *    module doesn't name below (the summary, the duration, the dose …), so an
 *    unknown change reruns everything rather than nothing.
 *  - {@link ListVersions.rating} moves for the star rating.
 *  - {@link ListVersions.text} moves for the fields only the text search
 *    reads: notes, plan, the legacy bean label, recipe and grinder model.
 *  - Fields no derivation on the list reads (the remote version stamp,
 *    privacy, grind setting, bag debit) move none. Rows draw them from the
 *    current row, not from the derivations.
 *
 * The row objects a version-keyed derivation holds are as of its last rerun.
 * Anything that shows a row reads the current one by id.
 *
 * A later phase (history-sqlite W2) replaces this with the core's change
 * kinds; nothing outside the History page reads it.
 */

import type { AnyShot } from './model';

/** Which versions a change between two copies of one row moves. */
export interface RowChange {
	list: boolean;
	rating: boolean;
	text: boolean;
}

/** `metadata` fields that move {@link ListVersions.rating}. */
const RATING_META = new Set(['rating']);
/** `metadata` fields that move {@link ListVersions.text}. */
const TEXT_META = new Set(['notes', 'nextPlan', 'beans']);
/** Top-level fields that move {@link ListVersions.text}. */
const TEXT_TOP = new Set(['recipeName', 'grinderModel']);
/** `metadata` fields nothing on the list derives from. */
const QUIET_META = new Set(['grinderSetting']);
/** Top-level fields nothing on the list derives from. */
const QUIET_TOP = new Set(['visualizerUpdatedAt', 'privacy', 'bagDebit']);

function keysOf(a: object, b: object): Set<string> {
	return new Set([...Object.keys(a), ...Object.keys(b)]);
}

/** Field by field, one level down: a row's `record` and `summary` are rebuilt with the row. */
function sameShallow(a: unknown, b: unknown): boolean {
	if (a === b) return true;
	if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
	const ra = a as Record<string, unknown>;
	const rb = b as Record<string, unknown>;
	for (const k of keysOf(ra, rb)) if (ra[k] !== rb[k]) return false;
	return true;
}

/** What changed between `a` and `b`, two copies of the same row. */
export function rowChange(a: AnyShot, b: AnyShot): RowChange {
	const out: RowChange = { list: false, rating: false, text: false };
	if (a === b) return out;
	const ra = a as unknown as Record<string, unknown>;
	const rb = b as unknown as Record<string, unknown>;
	for (const k of keysOf(a, b)) {
		if (ra[k] === rb[k]) continue;
		if (k === 'record' || k === 'summary') {
			if (!sameShallow(ra[k], rb[k])) out.list = true;
		} else if (k === 'metadata') {
			const ma = (ra[k] ?? {}) as Record<string, unknown>;
			const mb = (rb[k] ?? {}) as Record<string, unknown>;
			for (const m of keysOf(ma, mb)) {
				if (ma[m] === mb[m]) continue;
				if (RATING_META.has(m)) out.rating = true;
				else if (TEXT_META.has(m)) out.text = true;
				else if (!QUIET_META.has(m)) out.list = true;
			}
		} else if (TEXT_TOP.has(k)) out.text = true;
		else if (!QUIET_TOP.has(k)) out.list = true;
		if (out.list && out.rating && out.text) break;
	}
	return out;
}

/** What changed between two lists of rows. */
export function listChange(prev: readonly AnyShot[], next: readonly AnyShot[]): RowChange {
	const out: RowChange = { list: false, rating: false, text: false };
	if (prev === next) return out;
	if (prev.length !== next.length) return { list: true, rating: true, text: true };
	for (let i = 0; i < next.length; i++) {
		const a = prev[i];
		const b = next[i];
		if (a === b) continue;
		if (a.id !== b.id) return { list: true, rating: true, text: true };
		const c = rowChange(a, b);
		out.list ||= c.list;
		out.rating ||= c.rating;
		out.text ||= c.text;
	}
	return out;
}

/**
 * Version numbers over a reactive list of rows. Each getter is a derived
 * number, so a reader reruns only when its own version moved: a star tap
 * moves `rating` and leaves every reader of `list` alone.
 */
export class ListVersions<T extends AnyShot = AnyShot> {
	#prev: readonly T[] = [];
	#counts = { list: 0, rating: 0, text: 0 };
	readonly #source: () => readonly T[];

	/** One pass over the rows per change: compared by identity, then by field for the rows that changed. */
	readonly #all = $derived.by(() => {
		const next = this.#source();
		const c = listChange(this.#prev, next);
		this.#prev = next;
		this.#counts = {
			list: this.#counts.list + (c.list ? 1 : 0),
			rating: this.#counts.rating + (c.rating ? 1 : 0),
			text: this.#counts.text + (c.text ? 1 : 0)
		};
		return this.#counts;
	});
	readonly #list = $derived(this.#all.list);
	readonly #rating = $derived(this.#all.rating);
	readonly #text = $derived(this.#all.text);

	constructor(source: () => readonly T[]) {
		this.#source = source;
	}

	/** Moves when the set, the order or a facet field changes. */
	get list(): number {
		return this.#list;
	}

	/** Moves when a rating changes (and with {@link list}). */
	get rating(): number {
		return this.#rating;
	}

	/** Moves when a searched text field changes (and with {@link list}). */
	get text(): number {
		return this.#text;
	}
}
