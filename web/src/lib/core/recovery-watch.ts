/**
 * `#lib/core/recovery-watch.js` — notice when the wasm core reset itself after
 * a panic.
 *
 * The bridge survives a panicked call by installing a fresh core and counting
 * the reset (`CremaBridge::panic_recoveries`). A fresh core has its defaults:
 * the behaviour settings the shell pushed one by one at startup — stop on
 * weight, auto-tare, shot target weight, and the rest — are gone until the app
 * reloads, and the live session (shot in progress, connected scale) is dropped.
 * The connect sweep is safe (it writes from the shell's saved settings), but a
 * shot pulled on a reset core could ignore stop-at-weight. So the shell tells
 * the user and offers a reload, which rebuilds everything from saved settings.
 * (Re-pushing every setting in place is a follow-up: some setters write to the
 * machine as they run, so a blind replay is not safe.)
 */
export class CoreRecoveryWatch {
	private seen: number;

	/**
	 * @param read the bridge's current reset count (absent on test doubles).
	 * @param onReset called once per increase with the new count.
	 */
	constructor(
		private readonly read: (() => number) | undefined,
		private readonly onReset: (count: number) => void
	) {
		this.seen = read?.() ?? 0;
	}

	/** Check after every core call; fires `onReset` when the count grew. */
	check(): void {
		const now = this.read?.() ?? 0;
		if (now > this.seen) {
			this.seen = now;
			this.onReset(now);
		}
	}
}
