/**
 * The DE1 idle-sleep timer ("Cool down after"), shell side. Android's
 * `IdleSleep.kt` is the twin.
 *
 * The timer itself is core-owned (`CremaCore::idle_sleep`,
 * `de1_domain::idle_sleep`): it decides when to send Sleep, never re-sends a
 * sleep that landed, never sends while the link is down, retries a blocked
 * expiry on every tick, and pokes `UserPresent` only on real activity. This
 * module only feeds it — the setting, DOM activity (pointer / keyboard /
 * the tab becoming visible), the DE1 link state and a periodic tick.
 *
 * Browsers throttle timers in a hidden tab (to once a minute, or less), so
 * expiry is never a single long `setTimeout`: every input carries
 * `Date.now()` and the core compares timestamps, so a late tick — or the
 * check run when the tab turns visible again — fires on arrival instead of
 * losing the elapsed time.
 */

import type { CoreOutput, CremaCore } from '$lib/core';
import { IdleActivity, type IdleSleepInput } from '$lib/core/crema-core';
import { idleSleepChoices as wasmIdleSleepChoices } from '$lib/wasm/de1_wasm';

/** The tick cadence — the core's `IDLE_SLEEP_TICK_MS`. */
export const IDLE_SLEEP_TICK_MS = 30_000;

/** Pointer / key events arrive in bursts; the timer needs at most one a second. */
export const IDLE_ACTIVITY_THROTTLE_MS = 1_000;

/** The default "Cool down after", minutes — de1app and Decenza both use 60. */
export const DEFAULT_IDLE_SLEEP_MIN = 60;

/** The choices when the wasm core isn't loaded yet — the core's `IDLE_SLEEP_CHOICES_MIN`. */
export const IDLE_SLEEP_FALLBACK_CHOICES: readonly number[] = [0, 15, 30, 60, 90, 120];

/** The "Cool down after" choices, minutes (`0` = Never), from the core. */
export function idleSleepChoices(): number[] {
	try {
		return Array.from(wasmIdleSleepChoices());
	} catch {
		return [...IDLE_SLEEP_FALLBACK_CHOICES];
	}
}

/** How a choice reads in Settings. */
export function idleSleepLabel(minutes: number): string {
	return minutes <= 0 ? 'Never' : `${minutes} min`;
}

/** What the runner needs from the app — injected so it tests without BLE. */
export interface IdleSleepDeps {
	core: Pick<CremaCore, 'idleSleep'>;
	apply: (out: CoreOutput) => void;
	/**
	 * The user's current "Cool down after", minutes. Re-sent before every
	 * tick — a no-op in the core unless it changed — so a change from any
	 * path (Settings, a backup restore) reaches the timer within one tick.
	 */
	minutes?: () => number;
	/** Wall clock, ms (default `Date.now`). */
	now?: () => number;
}

/** The DOM surface the runner listens on (a `Document` in the app). */
export interface IdleSleepDom {
	addEventListener(type: string, listener: () => void, options?: AddEventListenerOptions): void;
	removeEventListener(type: string, listener: () => void): void;
	readonly visibilityState?: DocumentVisibilityState;
}

export class IdleSleepRunner {
	private timer: ReturnType<typeof setInterval> | null = null;
	private ready = false;
	private lastTouchAt: number | null = null;
	private detach: (() => void) | null = null;

	constructor(private readonly deps: IdleSleepDeps) {}

	private now(): number {
		return this.deps.now?.() ?? Date.now();
	}

	private feed(input: IdleSleepInput): void {
		void this.deps.core
			.idleSleep(input, this.now())
			.then((out) => this.deps.apply(out))
			.catch(() => undefined);
	}

	/** The user's timeout, minutes (`0` = Never). */
	configure(minutes: number): void {
		this.feed({ type: 'Configure', content: { minutes: Math.max(0, Math.round(minutes)) } });
	}

	/** Feed every DE1 connection-state change; only edges reach the core. */
	onDe1State(state: string): void {
		const ready = state === 'ready';
		if (ready !== this.ready) this.feed({ type: 'Link', content: { ready } });
		this.ready = ready;
	}

	/** One check now (re-asserting the setting first, see {@link IdleSleepDeps.minutes}). */
	tick(): void {
		if (this.deps.minutes) this.configure(this.deps.minutes());
		this.feed({ type: 'Tick' });
	}

	/** The user did something. Touches / keys are throttled; the rest pass. */
	activity(kind: IdleActivity): void {
		if (kind === IdleActivity.Touch || kind === IdleActivity.Key) {
			const now = this.now();
			const last = this.lastTouchAt;
			if (last !== null && now - last >= 0 && now - last < IDLE_ACTIVITY_THROTTLE_MS) return;
			this.lastTouchAt = now;
		}
		this.feed({ type: 'Activity', content: { kind } });
	}

	/** Start the periodic tick (idempotent). */
	start(): void {
		this.timer ??= setInterval(() => this.tick(), IDLE_SLEEP_TICK_MS);
	}

	/**
	 * Listen for activity on `dom`: pointer / key presses, and the tab
	 * becoming visible (a foreground return, plus an immediate check — the
	 * throttled background ticks may be well behind).
	 */
	attach(dom: IdleSleepDom): void {
		this.detach?.();
		const onPointer = () => this.activity(IdleActivity.Touch);
		const onKey = () => this.activity(IdleActivity.Key);
		const onVisibility = () => {
			if (dom.visibilityState === 'visible') this.activity(IdleActivity.Foreground);
			this.tick();
		};
		dom.addEventListener('pointerdown', onPointer, { passive: true });
		dom.addEventListener('keydown', onKey, { passive: true });
		dom.addEventListener('visibilitychange', onVisibility);
		// Back / forward (a route change no click or key preceded).
		const onNavigate = () => this.activity(IdleActivity.Navigation);
		dom.addEventListener('popstate', onNavigate);
		this.detach = () => {
			dom.removeEventListener('popstate', onNavigate);
			dom.removeEventListener('pointerdown', onPointer);
			dom.removeEventListener('keydown', onKey);
			dom.removeEventListener('visibilitychange', onVisibility);
		};
	}

	/** Stop the tick and the listeners (teardown). */
	stop(): void {
		if (this.timer !== null) {
			clearInterval(this.timer);
			this.timer = null;
		}
		this.detach?.();
		this.detach = null;
	}
}
