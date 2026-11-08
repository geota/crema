/**
 * `#lib/restore/boot` — the restore marker at boot, wired to the real stores:
 * an interrupted restore says so, with Dismiss; a finished one runs the
 * deletes it owes once they're safe (see `marker.ts`).
 */
import { getHistoryStore } from '#lib/history/index.js';
import { deleteSeries } from '#lib/history/series-store.js';
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
	const history = getHistoryStore();
	await history.seriesReady();
	const found = await checkMarkerAtBoot({
		restoreRunning: restoreLockHeld,
		listedShots: () => new Set(history.rawAll.map((s) => s.id)),
		listedBeans: () => new Set(getBeanStore().beans.map((b) => b.id)),
		deleteSeries,
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
