/**
 * `#lib/history/pending.js` — the pending journal (#310 P3w, SPEC §1): a new
 * shot the store couldn't take, kept in `crema.history.pending` until it can.
 *
 * A failed save of a new shot first retries with the row alone, about 1 KB.
 * If that fails too, or the store can't be opened at all (`unavailable`,
 * `lost`), the row lands here, which survives a tab close and merges at the
 * next boot. The journal holds rows only, never series: those stay in memory
 * and are retried on each later save, and the banner says the curve isn't
 * saved. At most {@link PENDING_MAX_CHARS}.
 */
import { readStored, writeStore } from '#lib/utils/storage.js';
import type { StoredShot } from './model';

export const PENDING_KEY = 'crema.history.pending';
/** About 1 MB of UTF-16. */
export const PENDING_MAX_CHARS = 512 * 1024;

/** The journal's rows; an unreadable journal reads as empty and is left in place. */
export function readPending(): Record<string, unknown>[] {
	const r = readStored(PENDING_KEY);
	if (r.kind !== 'loaded' || !Array.isArray(r.value)) return [];
	return r.value.filter((v): v is Record<string, unknown> => typeof v === 'object' && v !== null);
}

/**
 * Add `shot`'s row to the journal, or replace the one with its id. Without its
 * series: only the row's fields. Whether it was kept.
 */
export function addPending(shot: StoredShot): boolean {
	const row = { ...shot, record: { duration: shot.record.duration, samples: [] } };
	const rows = readPending().filter((r) => r.id !== shot.id);
	rows.push(row as unknown as Record<string, unknown>);
	const text = JSON.stringify(rows);
	if (text.length > PENDING_MAX_CHARS) return false;
	return writeStore(PENDING_KEY, rows, false) === 'ok';
}

/** Drop the journal's rows of `ids`, once the store holds them. */
export function dropPending(ids: readonly string[]): void {
	if (ids.length === 0) return;
	const gone = new Set(ids);
	const rows = readPending().filter((r) => typeof r.id !== 'string' || !gone.has(r.id));
	try {
		if (rows.length === 0) localStorage.removeItem(PENDING_KEY);
		else writeStore(PENDING_KEY, rows, false);
	} catch {
		// It stays; merging it again is harmless, as a held id is skipped.
	}
}
