/**
 * `#lib/capture/prune.js` — delete the raw captures past the limit (#311).
 *
 * Only captures of shots the history names are touched (an explicit id list),
 * never ones a listing merely doesn't explain. It runs once the history has
 * loaded, and never while its health isn't `ok`, so a corrupt load can't read
 * as "nothing pinned". The same rule previews what a lower limit would delete.
 */

import type { CapturedShot } from '#lib/core/crema-core.js';
import { capturesToDrop as wasmCapturesToDrop } from '#lib/wasm/de1_wasm.js';
import type { HistoryStore } from '#lib/history/store.svelte.js';
import { p3OnlyReads, rowsNamed, type HistoryReads } from '#lib/history/reads.svelte.js';
import { coreCaptureKeep, readCaptureKeep, type CaptureKeepChoice } from './limit.ts';
import { getCaptureStore } from './store.ts';

/**
 * The shots whose captures `choice` would delete now, newest kept and pinned
 * spared; empty when the history isn't safely loaded or IndexedDB is missing.
 */
export async function capturesOverLimit(
	history: HistoryStore,
	choice: CaptureKeepChoice,
	reads: HistoryReads = p3OnlyReads()
): Promise<string[]> {
	if (typeof indexedDB === 'undefined') return [];
	// Not loaded is not empty: with no rows, every capture would look unclaimed.
	if (!(await history.readyWithin())) return [];
	if (history.rowsHealth() !== 'ok') return [];
	const keys = await getCaptureStore().keys();
	// The rows the captures belong to, tombstones too, from P3w only (#310 W3,
	// REVIEW-472 N2): it deletes user bytes on a rule about pins, and on stage 1
	// the index only follows P3w, so asking it adds a wait and nothing else.
	const rows = await reads.answer(rowsNamed(keys), history);
	// The health again: the rows may have gone while the question was out.
	if (history.rowsHealth() !== 'ok') return [];
	const shots: CapturedShot[] = rows.map((s) => ({ id: s.id, completedAtMs: s.completedAt, pinned: s.pinned === true }));
	return JSON.parse(
		wasmCapturesToDrop(JSON.stringify({ shots, keep: coreCaptureKeep(choice) }))
	) as string[];
}

/** Delete the captures past the limit (the stored one by default); the ids deleted. */
export async function pruneCaptures(
	history: HistoryStore,
	choice?: CaptureKeepChoice
): Promise<string[]> {
	// The rows first: the first read of the choice stores today's limit for an
	// install that has shots, and before the load it would look like a new one.
	if (!(await history.readyWithin())) return [];
	// The history stored the choice when it loaded; this read only finds it.
	const doomed = await capturesOverLimit(history, choice ?? readCaptureKeep(() => history.holdsAnyRow));
	const store = getCaptureStore();
	const gone: string[] = [];
	for (const id of doomed) {
		try {
			await store.delete(id);
			gone.push(id);
		} catch (err) {
			console.warn('[capture prune] could not delete a capture', { id, err });
		}
	}
	return gone;
}
