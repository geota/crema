/**
 * `#lib/utils/timeout.js` — bound a promise by a deadline.
 *
 * One helper for every "give up after N ms" in the shell (the factory reset's
 * revocations, the DE1 liveness probe). The underlying work is not cancelled:
 * a promise has no handle for that, so the caller only stops waiting. Timer
 * hooks are injectable so fake-clock tests can drive the deadline.
 */

export interface TimeoutTimers {
	readonly setTimeout?: (fn: () => void, ms: number) => unknown;
	readonly clearTimeout?: (handle: unknown) => void;
}

/** Thrown (as the rejection) when {@link withTimeout}'s deadline passes first. */
export class TimeoutError extends Error {
	constructor(message = 'timed out') {
		super(message);
		this.name = 'TimeoutError';
	}
}

/**
 * Settle like `p`, or reject with a {@link TimeoutError} carrying `message`
 * once `ms` elapse first. The timer is cleared as soon as `p` settles.
 */
export function withTimeout<T>(
	p: Promise<T>,
	ms: number,
	message = 'timed out',
	timers: TimeoutTimers = {}
): Promise<T> {
	const setT = timers.setTimeout ?? ((fn, delay) => globalThis.setTimeout(fn, delay));
	const clearT =
		timers.clearTimeout ?? ((h) => globalThis.clearTimeout(h as ReturnType<typeof setTimeout>));
	return new Promise<T>((resolve, reject) => {
		const timer = setT(() => reject(new TimeoutError(message)), ms);
		p.then(
			(v) => {
				clearT(timer);
				resolve(v);
			},
			(e: unknown) => {
				clearT(timer);
				reject(e);
			}
		);
	});
}
