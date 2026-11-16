/**
 * `#lib/history/p3-rows.js` — the whole list of rows this tab holds, P3w's,
 * in memory (#310 W3, N1).
 *
 * The store doesn't put its rows on its public type: every consumer asks a
 * query (`reads.svelte.ts`). Only the store's own seams read the whole list,
 * through {@link p3RowsOf}, and `p3-rows-guard.vitest.ts` names each one:
 * the dev oracles, the in-memory list `index_fallback` draws, the P3 backup
 * source, sync's planners on stage 1 (`sync-view.ts`) and the queries' P3w
 * answers. A key rather than a property, so neither a destructuring nor an
 * alias reaches the rows without importing this module.
 */
import type { ShotRow } from './model';

/** The key the store answers on. */
export const P3_ROWS: unique symbol = Symbol('crema.history.p3Rows');

/** Every live row newest first (`live`), and every row, tombstones too (`raw`). Reactive. */
export interface P3Rows {
	readonly live: readonly ShotRow[];
	readonly raw: readonly ShotRow[];
}

/** What holds them: the history store, or a test's stand-in. */
export interface HoldsP3Rows {
	[P3_ROWS](): P3Rows;
}

export function p3RowsOf(history: HoldsP3Rows): P3Rows {
	return history[P3_ROWS]();
}
