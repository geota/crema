/**
 * `$lib/replay/driver` — plays a parsed capture's events back with real-time
 * timing.
 *
 * {@link replayEvents} walks the {@link CaptureEvent} list and waits the `t`
 * delta between consecutive events before delivering each, so a captured shot
 * unfolds on screen at the same pace it did live. An absurd inter-event gap
 * (e.g. the seconds-long idle between two sessions in one capture file) is
 * clamped to {@link MAX_GAP_MS} so a replay never appears to hang. An optional
 * `speed` multiplier scales every wait, and an `AbortSignal` cancels the replay
 * between events.
 *
 * The driver is transport-agnostic: it knows nothing about the core. The caller
 * passes an `onEvent` callback that does the real work (feed the core, fold the
 * output) — see `CremaApp.replayCapture`.
 */

import type { CaptureEvent } from './capture';

/**
 * The largest inter-event wait the driver will honour, in milliseconds. A
 * capture can record a long idle stretch between two sessions; clamping the gap
 * keeps the replay moving while still preserving real-time pacing within a
 * shot.
 */
export const MAX_GAP_MS = 1000;

/** Options for {@link replayEvents}. */
export interface ReplayOptions {
	/**
	 * Playback speed multiplier — `1` is real time, `2` is twice as fast, `0.5`
	 * is half speed. Must be `> 0`; defaults to `1`.
	 */
	readonly speed?: number;
	/** Cancels the replay between events when aborted. */
	readonly signal?: AbortSignal;
	/**
	 * Called once before each event is delivered, with the event's zero-based
	 * index — drives progress UI.
	 */
	readonly onProgress?: (index: number, total: number) => void;
}

/** Raised by {@link replayEvents} when its `AbortSignal` fires. */
export class ReplayAbortedError extends Error {
	constructor() {
		super('Replay cancelled.');
		this.name = 'ReplayAbortedError';
	}
}

/** Resolve after `ms`, rejecting with {@link ReplayAbortedError} if `signal` fires first. */
function delay(ms: number, signal?: AbortSignal): Promise<void> {
	return new Promise((resolve, reject) => {
		if (signal?.aborted) {
			reject(new ReplayAbortedError());
			return;
		}
		const timer = setTimeout(() => {
			signal?.removeEventListener('abort', onAbort);
			resolve();
		}, ms);
		const onAbort = (): void => {
			clearTimeout(timer);
			reject(new ReplayAbortedError());
		};
		signal?.addEventListener('abort', onAbort, { once: true });
	});
}

/**
 * Replay `events` through `onEvent`, pacing them by their `t` deltas.
 *
 * Between event `i-1` and event `i` the driver waits `(t[i] - t[i-1]) / speed`
 * milliseconds, with the raw gap first clamped to {@link MAX_GAP_MS}. The first
 * event is delivered immediately. `onEvent` is awaited, so a slow consumer
 * naturally back-pressures the replay. The promise resolves when every event
 * has been delivered, or rejects with {@link ReplayAbortedError} if `signal`
 * fires (the in-flight wait is cancelled; no further events are delivered).
 */
export async function replayEvents(
	events: readonly CaptureEvent[],
	onEvent: (event: CaptureEvent) => void | Promise<void>,
	options: ReplayOptions = {}
): Promise<void> {
	const speed = options.speed && options.speed > 0 ? options.speed : 1;
	const { signal, onProgress } = options;

	let previousT: number | undefined;
	for (let i = 0; i < events.length; i++) {
		if (signal?.aborted) throw new ReplayAbortedError();

		const event = events[i];
		if (previousT !== undefined) {
			// Clamp absurd gaps (idle stretches between sessions) so the replay
			// never appears to hang, then scale by the speed multiplier.
			const rawGap = Math.max(0, event.t - previousT);
			const wait = Math.min(rawGap, MAX_GAP_MS) / speed;
			if (wait > 0) await delay(wait, signal);
		}
		previousT = event.t;

		onProgress?.(i, events.length);
		await onEvent(event);
	}
}
