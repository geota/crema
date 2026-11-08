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
import { HISTORY_KEY, type HistoryStore } from '#lib/history/store.svelte.js';
import { storeHealth } from '../utils/storage.ts';
import { coreCaptureKeep, readCaptureKeep, type CaptureKeepChoice } from './limit.ts';
import { getCaptureStore } from './store.ts';

/**
 * The shots whose captures `choice` would delete now, newest kept and pinned
 * spared; empty when the history isn't safely loaded or IndexedDB is missing.
 */
export async function capturesOverLimit(history: HistoryStore, choice: CaptureKeepChoice): Promise<string[]> {
	if (typeof indexedDB === 'undefined') return [];
	await history.seriesReady();
	if (storeHealth(HISTORY_KEY) !== 'ok') return [];
	const keys = new Set(await getCaptureStore().keys());
	const shots: CapturedShot[] = history.rawAll
		.filter((s) => keys.has(s.id))
		.map((s) => ({ id: s.id, completedAtMs: s.completedAt, pinned: s.pinned === true }));
	return JSON.parse(
		wasmCapturesToDrop(JSON.stringify({ shots, keep: coreCaptureKeep(choice) }))
	) as string[];
}

/** Delete the captures past the limit (the stored one by default); the ids deleted. */
export async function pruneCaptures(
	history: HistoryStore,
	// The history's constructor stored the choice; this read only finds it.
	choice: CaptureKeepChoice = readCaptureKeep(() => history.rawAll.length > 0)
): Promise<string[]> {
	const doomed = await capturesOverLimit(history, choice);
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
