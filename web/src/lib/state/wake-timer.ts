/**
 * The core's wake hint (`Event::WakeAt`, F16): the core asks to be ticked at
 * a time it is waiting for (the end of the profile-download guard, …),
 * between the shell's once-a-second ticks. One one-shot timer: each hint
 * replaces the pending one, and it is cancelled when the DE1 link goes, the
 * core resets or the tab is hidden (the regular tick still covers the
 * deadline then). Android twin: `WakeTimer.kt`.
 */
export class WakeTimer {
	#id: ReturnType<typeof setTimeout> | null = null;

	constructor(
		/** The clock the core's times are in (`performance.now()`). */
		private readonly now: () => number,
		/** Tick the core at `nowMs`. */
		private readonly tick: (nowMs: number) => void
	) {}

	/** Tick at `atMs`, replacing any pending wake. */
	arm(atMs: number): void {
		this.cancel();
		this.#id = setTimeout(
			() => {
				this.#id = null;
				this.tick(Math.max(atMs, this.now()));
			},
			Math.max(0, atMs - this.now())
		);
	}

	/** Drop the pending wake, if any. */
	cancel(): void {
		if (this.#id === null) return;
		clearTimeout(this.#id);
		this.#id = null;
	}

	/** A wake is pending. */
	get pending(): boolean {
		return this.#id !== null;
	}
}
