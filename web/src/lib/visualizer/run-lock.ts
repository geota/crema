/**
 * `#lib/visualizer/run-lock.js` — one Visualizer pull at a time, across tabs
 * (`navigator.locks` on {@link RUN_LOCK}). The tab holding it runs the drain
 * between pages; other tabs skip their drains meanwhile, so PATCHes and
 * deletes still go, from one place.
 */

import type { LockManagerLike } from '#lib/restore/tab-safety.js';

/** The lock a pull holds. */
export const RUN_LOCK = 'crema.visualizer.run';

let locks: LockManagerLike | undefined | null = null;
let heldHere = false;

function manager(): LockManagerLike | undefined {
	if (locks !== null) return locks;
	return typeof navigator === 'undefined' ? undefined : (navigator.locks as unknown as LockManagerLike | undefined);
}

/**
 * Take the run lock if it's free: a release function, or `null` when another
 * tab holds it. With no lock manager there is one tab to think of.
 */
export function acquireRunLock(): Promise<(() => void) | null> {
	const m = manager();
	if (!m) {
		heldHere = true;
		return Promise.resolve(() => {
			heldHere = false;
		});
	}
	return new Promise((resolve) => {
		void m.request(RUN_LOCK, { ifAvailable: true }, (lock) => {
			if (!lock) {
				resolve(null);
				return undefined;
			}
			heldHere = true;
			return new Promise<void>((release) =>
				resolve(() => {
					heldHere = false;
					release();
				})
			);
		});
	});
}

/** Whether a pull in another tab holds the lock: this tab's drain waits. */
export async function runHeldElsewhere(): Promise<boolean> {
	if (heldHere) return false;
	const m = manager();
	if (!m?.query) return false;
	const state = await m.query();
	return (state.held ?? []).some((h) => h.name === RUN_LOCK);
}

/** Test seam: a lock manager to use (`undefined`: none), or `null` for the browser's. */
export function setRunLocksForTest(manager: LockManagerLike | undefined | null): void {
	locks = manager;
	heldHere = false;
}
