/**
 * `#lib/history/store-marker.js` — the history store's marker, outside the
 * store (#310 P3w, SPEC §6).
 *
 * `crema.history.store` in `localStorage` says whether the rows moved to
 * `crema-history`. It must live outside the database: a lost database with
 * the marker gone too would look like a fresh install and migrate the old
 * copy again, silently losing everything since. Written after the database's
 * own `done`, and rebuilt from it when missing.
 *
 * A migration that failed leaves the marker at `failed`, with the count and
 * the next try: the database was just cleared, so it can't hold them. The
 * first two failures retry at the next boots, then at most once a day.
 */
import { readStored, writeJsonChecked } from '#lib/utils/storage.js';

export const STORE_MARKER_KEY = 'crema.history.store';

/** Retries at the next boots before the daily wait starts. */
export const QUICK_RETRIES = 2;
export const RETRY_AFTER_MS = 24 * 60 * 60 * 1000;

export type StoreMarker =
	| {
			state: 'done';
			/** When the copy passed its check. */
			at: number;
			/** Rows the old copy held then, for the lost banner. */
			rows: number;
	  }
	| {
			state: 'failed';
			failures: number;
			lastError: string;
			lastTryAt: number;
			/** Not before this, unless the user asks (Settings, Try again). */
			nextTryAt: number;
	  };

function isMarker(v: unknown): v is StoreMarker {
	if (typeof v !== 'object' || v === null) return false;
	const m = v as Record<string, unknown>;
	if (m.state === 'done') return typeof m.at === 'number';
	return m.state === 'failed' && typeof m.failures === 'number' && typeof m.nextTryAt === 'number';
}

/** What the marker says; `unreadable` when the key can't be read or parsed. */
export type MarkerRead = { kind: 'none' } | { kind: 'unreadable' } | { kind: 'marker'; marker: StoreMarker };

export function readStoreMarker(): MarkerRead {
	const r = readStored(STORE_MARKER_KEY);
	if (r.kind === 'absent') return { kind: 'none' };
	if (r.kind === 'loaded' && isMarker(r.value)) return { kind: 'marker', marker: r.value };
	return { kind: 'unreadable' };
}

/** Whether the marker says the rows moved, which makes a missing database lost. */
export function markerSaysDone(): boolean {
	const r = readStoreMarker();
	// An unreadable marker counts as done: it was written by a build that
	// migrated, as nothing else writes the key, and "done" is the side that
	// never re-migrates over a store that has data.
	return r.kind === 'unreadable' || (r.kind === 'marker' && r.marker.state === 'done');
}

export function writeDoneMarker(at: number, rows: number): boolean {
	return writeJsonChecked(STORE_MARKER_KEY, { state: 'done', at, rows } satisfies StoreMarker);
}

/** Record a failed migration and when to try again. */
export function writeFailedMarker(error: string, now: number): StoreMarker {
	const prior = readStoreMarker();
	const failures = (prior.kind === 'marker' && prior.marker.state === 'failed' ? prior.marker.failures : 0) + 1;
	const marker: StoreMarker = {
		state: 'failed',
		failures,
		lastError: error,
		lastTryAt: now,
		nextTryAt: failures <= QUICK_RETRIES ? now : now + RETRY_AFTER_MS
	};
	writeJsonChecked(STORE_MARKER_KEY, marker);
	return marker;
}

/** Whether a migration may run now: none failed, or its wait is over. */
export function migrationDue(now: number): boolean {
	const r = readStoreMarker();
	if (r.kind !== 'marker' || r.marker.state !== 'failed') return true;
	return now >= r.marker.nextTryAt;
}

/** Settings, Try again: the next boot tries the move, whatever the wait. */
export function retryMigrationSoon(now: number = Date.now()): void {
	const r = readStoreMarker();
	if (r.kind !== 'marker' || r.marker.state !== 'failed') return;
	writeJsonChecked(STORE_MARKER_KEY, { ...r.marker, nextTryAt: now } satisfies StoreMarker);
}
