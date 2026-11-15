/**
 * `#lib/history/storage-persist.svelte.js` — whether the browser promised to
 * keep this site's storage (#310 P3w, SPEC §2).
 *
 * `navigator.storage.persist()` is asked at boot. Chrome decides on its own,
 * so a refusal is possible, and then the browser may drop the whole site
 * under disk pressure: history, marker and all. Settings shows the answer, so
 * the user knows when a backup is the only copy.
 */

/** `null` until asked, or where the browser can't say. */
let granted = $state<boolean | null>(null);

/** The browser's answer to `persist()`: true kept, false may be cleared, null unknown. */
export function storagePersisted(): boolean | null {
	return granted;
}

/** Ask once at boot, and keep the answer. */
export async function askStoragePersist(): Promise<boolean | null> {
	if (typeof navigator === 'undefined' || typeof navigator.storage?.persist !== 'function') return null;
	try {
		const already = typeof navigator.storage.persisted === 'function' ? await navigator.storage.persisted() : false;
		granted = already || (await navigator.storage.persist());
	} catch {
		granted = null;
	}
	return granted;
}
