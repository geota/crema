/**
 * `$lib/bean/search` — fuzzy library search, delegated to the core.
 *
 * The ranking itself lives in `de1_domain::bean_search` (issue 62) so web,
 * Android tablet and Android phone return the same rows in the same order
 * for the same query. This module is the thin web side of that: it keeps the
 * serialised corpus warm and hands back a `Map` keyed by row id.
 *
 * **Why the corpus is cached.** The core takes the library as JSON, so a
 * naive call would re-stringify every bag on every keystroke. The library
 * changes when the user edits a bag; the query changes when they type. Those
 * are different clocks, so the JSON is memoised on array identity and only
 * the (cheap) match runs per keystroke.
 */

import { searchBeans as wasmSearchBeans, searchRoasters as wasmSearchRoasters } from '$lib/wasm/de1_wasm';
import { SearchField, type FieldHit, type SearchHit, type SearchSegment } from '$lib/core/crema-core';
import type { Bean, Roaster } from './model';

export { SearchField };
export type { FieldHit, SearchHit, SearchSegment };

/**
 * Hits keyed by `Bean.id` / `Roaster.id`, plus the relevance order. A `Map`
 * because the callers all need both "is this row a match?" (filtering) and
 * "how good was it?" (sorting), and a linear scan per row is the one thing a
 * search must not do.
 */
export interface SearchResults {
	/** `id → hit` for every row that matched. Empty for a blank query. */
	byId: Map<string, SearchHit>;
	/** Whether a query was actually run (false for a blank query). */
	active: boolean;
	/**
	 * Relevance of one row, or `-1` when it did not match. Sorting on the
	 * *score* rather than on the result index is deliberate: rows that tie
	 * exactly then fall through to whatever the caller's sort control says,
	 * instead of being frozen into the core's id tiebreak.
	 */
	score: (id: string) => number;
}

const EMPTY: SearchResults = { byId: new Map(), active: false, score: () => -1 };

/** The blank result — a shared instance so `$derived` sees a stable value. */
export function noSearch(): SearchResults {
	return EMPTY;
}

function toResults(hits: SearchHit[]): SearchResults {
	const byId = new Map<string, SearchHit>();
	for (const h of hits) byId.set(h.id, h);
	return { byId, active: true, score: (id) => byId.get(id)?.score ?? -1 };
}

/**
 * One-entry memo over `JSON.stringify`, keyed on array identity. The bean
 * store hands out the same array reference until something in it changes, so
 * identity is exactly the right key — and cheaper than any hash of the
 * contents would be.
 */
function memoJson<T>(): (rows: readonly T[]) => string {
	let lastRows: readonly T[] | null = null;
	let lastJson = '[]';
	return (rows) => {
		if (rows !== lastRows) {
			lastRows = rows;
			lastJson = JSON.stringify(rows);
		}
		return lastJson;
	};
}

const beansJson = memoJson<Bean>();
const roastersJson = memoJson<Roaster>();

/**
 * Rank `beans` against `query`. Roasters are passed so a bag can match on its
 * roastery's name. A blank query returns {@link noSearch} — the caller keeps
 * its own ordering rather than being handed an arbitrary one.
 */
export function searchBeans(
	beans: readonly Bean[],
	roasters: readonly Roaster[],
	query: string
): SearchResults {
	if (!query.trim()) return EMPTY;
	try {
		return toResults(
			JSON.parse(wasmSearchBeans(beansJson(beans), roastersJson(roasters), query)) as SearchHit[]
		);
	} catch (err) {
		// A search box must never take the page down. Falling through to "no
		// matches" would silently hide the whole library, so log and treat the
		// query as inactive instead — the user sees their unfiltered list.
		console.error('Bean search failed', err);
		return EMPTY;
	}
}

/** The roaster-directory half of {@link searchBeans}. */
export function searchRoasters(roasters: readonly Roaster[], query: string): SearchResults {
	if (!query.trim()) return EMPTY;
	try {
		return toResults(JSON.parse(wasmSearchRoasters(roastersJson(roasters), query)) as SearchHit[]);
	} catch (err) {
		console.error('Roaster search failed', err);
		return EMPTY;
	}
}

/**
 * The field hit a tile should surface as a "matched in …" line, or `null`
 * when the match needs no explanation.
 *
 * Name and roaster hits are suppressed: both are already the largest text on
 * the tile and are highlighted in place, so repeating them below would be
 * noise. Everything else — a process, a tasting note, where the bag was
 * bought — is invisible until we say so.
 */
export function explanatoryHit(hit: SearchHit | undefined): FieldHit | null {
	if (!hit) return null;
	const first = hit.fields.find((f) => f.field !== SearchField.Name && f.field !== SearchField.Roaster);
	return first ?? null;
}

/**
 * `id → score` for a ranked result, or `null` when no query is running —
 * the shape the shared `HeaderPicker` ranking hook takes, so the brew bean
 * dropdown finds the same bags the library page does.
 */
export function scoreMap(results: SearchResults): Map<string, number> | null {
	if (!results.active) return null;
	const m = new Map<string, number>();
	for (const [id, hit] of results.byId) m.set(id, hit.score);
	return m;
}

/** The `FieldHit` for one specific field, for highlighting it in place. */
export function hitForField(hit: SearchHit | undefined, field: SearchField): FieldHit | null {
	return hit?.fields.find((f) => f.field === field) ?? null;
}
