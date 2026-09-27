/**
 * `$lib/history` — the shot-history library: the persisted record model and
 * the localStorage-backed store.
 *
 * Shot records are real and persisted in `localStorage`. They are written by
 * the orchestrator's `ShotCompleted` handler (see `lib/state/app.svelte.ts`)
 * — there is no mock data. See `store.svelte.ts` for the persistence detail.
 */

export {
	type StoredShot,
	type ShotBean,
	type ShotMetadata,
	type ShotPeaks,
	shotId,
	ratioLabel,
	effectiveGrindSetting,
	grindLabel,
	shotFilename,
	snapshotFromBean,
	peaksOf,
	yieldOf,
	statsOf,
	brewStatsOf,
	methodOf,
	isBrewLog,
	isManualLog,
	hasTelemetry,
	flatSamplesOf
} from './model';

export {
	HistoryStore,
	getHistoryStore,
	type ShotCompletion,
	type ImportExtras,
	type ImportBeanExtras,
	type ManualBrewInput,
	type ManualBrewPatch
} from './store.svelte';

export { exportStoredShotAsV2Json, extractCremaExtras } from './v2-export';

