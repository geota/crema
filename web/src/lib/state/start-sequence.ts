/**
 * The one shot start in flight (cohort review 2026-10-29, 05 F2): Coffee →
 * profile sync → optional pre-shot flush → Espresso, held until the machine
 * reports Espresso. Android twin: `ShotStartSequence.kt`.
 *
 * Only one start exists at a time: {@link StartSequence.begin} returns null
 * while one is pending, so a second start can't run a chain whose late
 * Espresso request lands mid-shot. A user's machine request, Stop above all,
 * {@link StartSequence.cancel}s it, so a pre-flush ended by the user no
 * longer releases the shot. The pre-flush waiter belongs to the
 * {@link StartToken}, not to a field a second chain could overwrite.
 *
 * Natural flush completion and a user stop can't be told apart from what
 * the DE1 reports (the flush ends in Idle either way, and
 * `WaterSessionCompleted` carries no reason). So the rule is: an app Stop
 * cancels the start before Idle goes out, and after the flush the shot
 * starts only if the machine reports Idle (a group-head button that moved it
 * to Steam or Sleep cancels).
 *
 * `CremaApp.startShot` runs the stages; this file owns the token, the
 * waiter and the event hooks, so it unit-tests without the app.
 */

import { MachineState } from '#lib/core/crema-core.js';

/** Ceiling on the pre-shot flush before requesting Espresso anyway — the
 *  backstop if `WaterSessionCompleted(Flush)` is missed. */
export const PRESHOT_FLUSH_TIMEOUT_MS = 30_000;

/** How long a start stays pending after its Espresso request while the
 *  machine has not reported Espresso yet. Covers the gap before the state
 *  report, so a Start there is refused; a machine that never enters Espresso
 *  (a GHC-only machine refuses app starts) frees the button after this. */
export const ESPRESSO_ENTRY_TIMEOUT_MS = 5_000;

/** One start. Its identity is the sequence token. */
export class StartToken {
	cancelled = false;
	/** Set just before our pre-flush request goes out. */
	flushArmed = false;
	/** A flush began after `flushArmed`: only its end may release us. */
	flushStarted = false;
	flushEnded = false;
	espressoRequested = false;
	espressoEntered = false;
	#wake: (() => void) | null = null;

	/**
	 * Wait until `done()` holds, the start is cancelled, or `ms` pass.
	 * Resolves true unless it timed out.
	 */
	waitFor(done: () => boolean, ms: number): Promise<boolean> {
		if (done() || this.cancelled) return Promise.resolve(true);
		return new Promise((resolve) => {
			const timer = setTimeout(() => {
				this.#wake = null;
				resolve(false);
			}, ms);
			this.#wake = () => {
				if (!done() && !this.cancelled) return;
				clearTimeout(timer);
				this.#wake = null;
				resolve(true);
			};
		});
	}

	/** Re-check whatever is waiting. */
	wake(): void {
		this.#wake?.();
	}
}

export class StartSequence {
	#current: StartToken | null = null;

	constructor(
		/** The state the DE1 last reported. */
		private readonly machineState: () => MachineState | null,
		/** Called whenever {@link active} changes. */
		private readonly onActiveChanged: (active: boolean) => void,
		private readonly log: (line: string) => void
	) {}

	/** A start is pending: from {@link begin} until the machine is in
	 *  Espresso, the start fails, or it is cancelled. */
	get active(): boolean {
		return this.#current !== null;
	}

	/** Claim the sequence for a new start, or null when one is pending or a
	 *  shot is already running. */
	begin(): StartToken | null {
		if (this.#current !== null || this.machineState() === MachineState.Espresso) return null;
		const start = new StartToken();
		this.#current = start;
		this.onActiveChanged(true);
		return start;
	}

	/** `start` is still the pending start and was not cancelled. */
	isLive(start: StartToken): boolean {
		return !start.cancelled && this.#current === start;
	}

	/** Cancel the pending start, if any; returns it (null when none). */
	cancel(): StartToken | null {
		const start = this.#current;
		if (start === null) return null;
		this.#current = null;
		start.cancelled = true;
		start.wake();
		this.onActiveChanged(false);
		return start;
	}

	/** `start` is over without a cancel. A no-op when it is no longer the
	 *  pending start. */
	end(start: StartToken): void {
		if (this.#current !== start) return;
		this.#current = null;
		this.onActiveChanged(false);
	}

	/** Feed every `MachineStateChanged`. Espresso after our request ends the
	 *  wait; Espresso before it means the shot was started on the machine,
	 *  so this start stands down instead of requesting a second one. */
	onMachineState(state: MachineState): void {
		const start = this.#current;
		if (start === null || state !== MachineState.Espresso) return;
		if (start.espressoRequested) {
			start.espressoEntered = true;
			start.wake();
		} else {
			this.log('Shot started on the machine — app start dropped.');
			this.cancel();
		}
	}

	/** Feed `WaterSessionStarted(Flush)`. */
	onFlushStarted(): void {
		const start = this.#current;
		if (start?.flushArmed) start.flushStarted = true;
	}

	/** Feed `WaterSessionCompleted(Flush)`. */
	onFlushCompleted(): void {
		const start = this.#current;
		if (!start?.flushStarted) return;
		start.flushEnded = true;
		start.wake();
	}
}
