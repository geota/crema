/**
 * `#lib/state/core-ticker.js` — the steady core clock while a DE1 link is up
 * or recovering (cohort review 2026-10-28, #2 N1).
 *
 * `CremaCore::on_tick` runs the profile-upload ack watchdog, the wait for the
 * machine model, the repeat of an outstanding re-upload request, the
 * lost-scale watchdog and steam eco. Before this the only caller was the
 * guided-brew tick, so an upload whose last write failed never failed: the
 * "DE1 holds" fingerprint stayed, no re-upload was asked for, idle sleep stayed
 * pinned and a firmware flash stayed blocked.
 *
 * The guided-brew 250 ms tick keeps running beside this one; `on_tick` computes
 * everything from timestamps, so extra calls are harmless. Android's
 * `CoreTicker` runs the same 1 s cadence.
 */

import type { BleConnectionState } from '#lib/ble/connection-state.js';
import type { CoreOutput } from '#lib/core/crema-core.js';

/** Tick cadence — the watchdogs work in whole seconds. */
export const CORE_TICK_MS = 1_000;

/** Link states in which the core ticks: up, or on its way (back) up. */
const TICKING: ReadonlySet<BleConnectionState> = new Set([
	'connecting',
	'subscribing',
	'ready',
	'reconnecting'
]);

export interface CoreTickerDeps {
	readonly core: { onTick(nowMs: number): Promise<CoreOutput> };
	/** Fold the tick's output (events + commands) like any other. */
	readonly apply: (output: CoreOutput) => void;
	/** The notification clock (`performance.now()`). */
	readonly now?: () => number;
}

export class CoreTicker {
	#timer: ReturnType<typeof setInterval> | null = null;

	constructor(private readonly deps: CoreTickerDeps) {}

	/** Whether the clock is running. */
	get running(): boolean {
		return this.#timer !== null;
	}

	/** Follow the DE1 link state: tick while it is up or recovering. */
	onDe1State(state: BleConnectionState): void {
		if (TICKING.has(state)) this.start();
		else this.stop();
	}

	start(): void {
		if (this.#timer !== null) return;
		this.#timer = setInterval(() => void this.tick(), CORE_TICK_MS);
	}

	stop(): void {
		if (this.#timer === null) return;
		clearInterval(this.#timer);
		this.#timer = null;
	}

	private async tick(): Promise<void> {
		const now = this.deps.now?.() ?? performance.now();
		try {
			this.deps.apply(await this.deps.core.onTick(now));
		} catch {
			// A failed tick is retried by the next one.
		}
	}
}
