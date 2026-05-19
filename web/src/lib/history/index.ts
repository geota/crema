/**
 * `$lib/history` — the shot-history library: the persisted record model and
 * the localStorage-backed store.
 *
 * Shot records are real and persisted in `localStorage`. They are written by
 * the orchestrator's `ShotCompleted` handler (see `lib/state/app.svelte.ts`)
 * — there is no mock data. See `store.svelte.ts` for the persistence detail.
 */

export {
	type ShotRecord,
	type ShotBean,
	shotId,
	ratioLabel,
	stars,
	shotJsonl,
	shotFilename
} from './model';

export {
	HistoryStore,
	getHistoryStore,
	type ShotCompletion
} from './store.svelte';
