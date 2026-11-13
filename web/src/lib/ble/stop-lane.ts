/**
 * `#lib/ble/stop-lane.js` — the shell's half of the core's `Command::Stop` and
 * `Command::CancelStop` (`DESIGN.md` §3.6), the same rule as Android's
 * `CoreLane`: at most one stop outstanding, a second dropped while one is
 * queued or in flight, and a cancel drops one that hasn't started. Web
 * Bluetooth can't recall a write on the air, so a cancel can't stop that one.
 */
export class StopLane {
	/** The outstanding stop, if any. */
	#current: { cancelled: boolean } | null = null;

	/**
	 * @param write writes the stop ahead of the queue, checking `guard` when
	 *   it starts. It must queue synchronously, so the stop's place is fixed
	 *   by the call.
	 * @param log the shell's event log.
	 */
	constructor(
		private readonly write: (guard: () => boolean) => Promise<boolean>,
		private readonly log: (line: string) => void
	) {}

	/** `Command::Stop`. Resolves whether it was written; `false` when dropped. */
	stop(): Promise<boolean> {
		if (this.#current !== null) {
			this.log('Stop write already in flight — not stacking another');
			return Promise.resolve(false);
		}
		const token = { cancelled: false };
		this.#current = token;
		const queuedAt = performance.now();
		return this.write(() => !token.cancelled).then((written) => {
			if (this.#current === token) this.#current = null;
			if (written) this.log(`Stop write delivered in ${Math.round(performance.now() - queuedAt)} ms`);
			return written;
		});
	}

	/** `Command::CancelStop`: drop the outstanding stop if it hasn't started. */
	cancel(): void {
		const token = this.#current;
		if (token === null) return;
		token.cancelled = true;
		this.#current = null;
		this.log('Stale stop write cancelled — machine already stopped');
	}
}
