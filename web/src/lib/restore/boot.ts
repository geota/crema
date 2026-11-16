/**
 * `#lib/restore/boot` — the restore marker at boot, wired to the real stores:
 * an interrupted restore says so, with Dismiss; a finished one runs the
 * deletes it owes once they're safe (see `marker.ts`).
 */
import { getHistorySource } from '#lib/history-source/source.js';
import { getCaptureStore } from '#lib/capture/store.js';
import { getBeanStore } from '#lib/bean/index.js';
import { getBeanImageStore, refForBean } from '#lib/bean/image-storage.js';
import { toast } from '#lib/components/shared/toast.svelte.js';
import { RESTORE_LOCK, type LockManagerLike } from './tab-safety';
import { checkMarkerAtBoot, finishMarker, type MarkerBoot } from './marker';

/** Whether `crema-restore` is held in some tab. */
async function restoreLockHeld(): Promise<boolean> {
	const locks =
		typeof navigator !== 'undefined' && 'locks' in navigator
			? (navigator.locks as unknown as LockManagerLike & {
					query?: () => Promise<{ held?: { name?: string }[] }>;
				})
			: undefined;
	if (!locks?.query) return false;
	try {
		return ((await locks.query()).held ?? []).some((l) => l.name === RESTORE_LOCK);
	} catch {
		return false;
	}
}

/** Run once a boot knows it isn't frozen. */
export async function checkRestoreMarker(): Promise<MarkerBoot> {
	const history = getHistorySource();
	// The deletes go by "not listed", which only means something once the rows load.
	if (!(await history.readyWithin())) return { kind: 'waiting' };
	// Listed after the check that no restore runs, as the deletes read it.
	let listed: ReadonlySet<string> = new Set();
	const found = await checkMarkerAtBoot({
		restoreRunning: async () => {
			if (await restoreLockHeld()) return true;
			listed = await history.listedIds();
			return false;
		},
		listedShots: () => listed,
		listedBeans: () => new Set(getBeanStore().beans.map((b) => b.id)),
		shotsHealth: () => history.rowsHealth(),
		// The P3 store: a row's details went with its explicit delete, so only
		// the legacy backend's series go. `history.db`: the details a crash
		// left after a wipe's row delete (#310 R1w).
		deleteSeries: (id) => history.deleteOwed(id),
		deleteCapture: (id) => getCaptureStore().delete(id),
		deletePhoto: (beanId) => getBeanImageStore().delete(refForBean(beanId))
	});
	if (found.kind === 'interrupted') {
		toast.action(
			`Your last restore from ${found.backupName} didn't finish. Run it again to complete it.`,
			'Dismiss',
			() => void finishMarker(),
			60_000
		);
	}
	return found;
}
