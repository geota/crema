/**
 * `#lib/history-source/guards.js` — the SQLite source's lock around a hold's
 * release (#310 R1w, S6): only while no restore runs in any tab. A restore's
 * upsert of a held row ends its hold, so a release racing it could delete the
 * row the restore just brought back.
 */
import { RESTORE_LOCK, type LockManagerLike } from '#lib/restore/tab-safety.js';

/** `f` under `crema-restore`, if no tab holds it; null if one does. Without Web Locks, `f` alone. */
export function apartFromRestores(locks: LockManagerLike | undefined) {
	return async <T>(f: () => Promise<T>): Promise<T | null> => {
		if (!locks) return f();
		let out: T | null = null;
		await locks.request(RESTORE_LOCK, { mode: 'exclusive', ifAvailable: true }, async (lock) => {
			if (lock === null) return;
			out = await f();
		});
		return out;
	};
}
