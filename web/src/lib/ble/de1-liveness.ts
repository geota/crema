/**
 * `#lib/ble/de1-liveness.js` — mid-session dead-link detection for the DE1.
 * The web mirror of Android's `De1BleManager.livenessLoop` (geota/crema#65),
 * same rule, same numbers.
 *
 * A dead link does not always deliver a disconnect: Decenza measured a link
 * Qt kept reporting connected for 22.5 s while stop presses were discarded,
 * and reaprime forces a reconnect after 10 s without a push frame. A healthy
 * connected DE1 streams water-level notifications about twice a second, so
 * while inbound traffic is recent this does nothing (and so never reads
 * mid-shot, when ShotSamples keep the link busy). Only after
 * {@link LIVENESS_QUIET_MS} of silence does it issue a passive StateInfo read,
 * which the DE1 answers even while asleep — it never wakes the machine. After
 * {@link LIVENESS_MAX_FAILURES} failed probes in a row the owner drops the
 * link into the ordinary reconnect loop.
 *
 * Paused while a firmware flash holds the link (the flash owns its I/O and
 * its own link-loss handling). Timers and the clock are injected for tests.
 */

/** How often the check runs, ms (Android `LIVENESS_CHECK_INTERVAL_MS`). */
export const LIVENESS_CHECK_INTERVAL_MS = 15_000;

/** Inbound silence before a probe read, ms (Android `LIVENESS_QUIET_MS`). */
export const LIVENESS_QUIET_MS = 30_000;

/** Failed probes in a row before the link is presumed dead (Android: 3). */
export const LIVENESS_MAX_FAILURES = 3;

/** One probe read's ceiling, ms (Android's 5 s write/read timeout). */
export const LIVENESS_PROBE_TIMEOUT_MS = 5_000;

export interface LivenessDeps {
	/** Monotonic clock, ms (`performance.now()`, the notification stamp clock). */
	readonly now: () => number;
	/** When the last notification arrived, on the {@link now} clock. */
	readonly lastInboundAtMs: () => number;
	/** The passive StateInfo read; rejects on a dead link. */
	readonly probe: () => Promise<unknown>;
	/** True while the check must not touch the link (a firmware flash). */
	readonly paused: () => boolean;
	/** The link is presumed dead: drop it into the reconnect loop. */
	readonly onDead: () => void;
	/** A probe failed (`failures` in a row, after `quietMs` of silence) — for the log. */
	readonly onProbeFailed?: (failures: number, quietMs: number) => void;
	readonly setInterval?: (fn: () => void, ms: number) => unknown;
	readonly clearInterval?: (handle: unknown) => void;
	readonly setTimeout?: (fn: () => void, ms: number) => unknown;
	readonly clearTimeout?: (handle: unknown) => void;
}

/** One liveness watch over one ready link; {@link stop} it when the link leaves ready. */
export class De1Liveness {
	readonly #deps: LivenessDeps;
	#timer: unknown = null;
	#failures = 0;
	#probing = false;
	/** When the last probe succeeded — counts as inbound traffic. */
	#lastProbeOkAtMs: number;
	#stopped = false;

	constructor(deps: LivenessDeps) {
		this.#deps = deps;
		this.#lastProbeOkAtMs = deps.now();
	}

	/** Start the periodic check (the link just became ready). */
	start(): void {
		if (this.#timer !== null || this.#stopped) return;
		const setI = this.#deps.setInterval ?? ((fn, ms) => globalThis.setInterval(fn, ms));
		this.#timer = setI(() => void this.tick(), LIVENESS_CHECK_INTERVAL_MS);
	}

	/** Stop for good (the link left ready, or the device was replaced). */
	stop(): void {
		this.#stopped = true;
		if (this.#timer === null) return;
		const clearI =
			this.#deps.clearInterval ??
			((h) => globalThis.clearInterval(h as ReturnType<typeof setInterval>));
		clearI(this.#timer);
		this.#timer = null;
	}

	/** One check. Exported for tests; the interval calls it. */
	async tick(): Promise<void> {
		if (this.#stopped || this.#probing || this.#deps.paused()) return;
		const now = this.#deps.now();
		const lastHeard = Math.max(this.#deps.lastInboundAtMs(), this.#lastProbeOkAtMs);
		const quietMs = now - lastHeard;
		if (quietMs < LIVENESS_QUIET_MS) {
			this.#failures = 0;
			return;
		}
		this.#probing = true;
		let ok: boolean;
		try {
			await this.#withTimeout(this.#deps.probe());
			ok = true;
		} catch {
			ok = false;
		} finally {
			this.#probing = false;
		}
		if (this.#stopped) return;
		if (ok) {
			this.#lastProbeOkAtMs = this.#deps.now();
			this.#failures = 0;
			return;
		}
		this.#failures += 1;
		this.#deps.onProbeFailed?.(this.#failures, quietMs);
		if (this.#failures >= LIVENESS_MAX_FAILURES) {
			this.#failures = 0;
			this.stop();
			this.#deps.onDead();
		}
	}

	#withTimeout<T>(p: Promise<T>): Promise<T> {
		const setT = this.#deps.setTimeout ?? ((fn, ms) => globalThis.setTimeout(fn, ms));
		const clearT =
			this.#deps.clearTimeout ??
			((h) => globalThis.clearTimeout(h as ReturnType<typeof setTimeout>));
		return new Promise<T>((resolve, reject) => {
			const timer = setT(() => reject(new Error('liveness probe timed out')), LIVENESS_PROBE_TIMEOUT_MS);
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
}
