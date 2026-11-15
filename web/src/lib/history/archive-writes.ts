/**
 * `#lib/history/archive-writes.js` — the curve archive's writes in flight,
 * so a factory reset can stop them before it removes the directory (#420).
 *
 * Kept apart from the archive, as `store-names.ts` is, so the reset needn't
 * load the history to use it.
 */

const inFlight = new Set<Promise<unknown>>();
let stopped = false;

/** Whether archive writes are stopped: a factory reset is under way, or done. */
export function archiveWritesStopped(): boolean {
	return stopped;
}

/** Run one archive write, known to a reset that comes meanwhile. */
export function trackArchiveWrite<T>(run: () => Promise<T>): Promise<T> {
	const p = run();
	inFlight.add(p);
	void p.finally(() => inFlight.delete(p)).catch(() => {});
	return p;
}

/** Stop every later archive write, and wait for those in flight to end. */
export async function stopArchiveWrites(): Promise<void> {
	stopped = true;
	while (inFlight.size > 0) await Promise.allSettled([...inFlight]);
}

/** Test seam: writes go again. */
export function resumeArchiveWritesForTest(): void {
	stopped = false;
}
