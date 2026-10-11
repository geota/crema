/**
 * `$lib/firmware/flash-driver` — runs a DE1 firmware flash for the web shell.
 *
 * Every protocol decision lives in the Rust core (`de1_app::firmware_flash`):
 * which bytes, which characteristic, when a phase is over, when to give up.
 * The core answers each input with a `FlashStep` — `actions` to perform and a
 * `status` snapshot. This driver is the I/O half and nothing more:
 *
 * - performs the actions strictly **one at a time** (one write-with-response
 *   in flight — de1app `bluetooth.tcl:3303-3312`, decaid
 *   `unified_de1.firmware.dart:239`) and reports each ACK, failure or read
 *   back to the core;
 * - routes `FWMapRequest` (A009) notifications and link loss / recovery to it;
 * - ticks it every {@link TICK_MS} (the erase / verify poll, the settle, the
 *   timeouts, the post-restart build reads);
 * - holds the DE1 link exclusively while the core says writes are locked, and
 *   keeps the page awake while a flash is running.
 *
 * All calls into the core go through one serial chain, so a tick, a
 * notification and an ACK can never interleave.
 */

import type {
	CremaCore,
	FlashAction,
	FlashShellConditions,
	FlashStartError,
	FlashStatus,
	FlashStep
} from '$lib/core';
import { FlashPhase, type WriteTarget } from '$lib/core/crema-core';
import { describeError } from '$lib/utils/error';

/** The core's flash clock cadence (decaid polls A009 every 250 ms). */
export const TICK_MS = 250;

/** How often a running upload republishes its status (UI churn bound). */
export const STATUS_PUBLISH_MS = 250;

/** The core surface the driver needs — a `CremaCore` satisfies it. */
export type FlashCore = Pick<
	CremaCore,
	| 'firmwareFlashStart'
	| 'firmwareFlashOnWriteAck'
	| 'firmwareFlashOnWriteFailed'
	| 'firmwareFlashOnFwMap'
	| 'firmwareFlashOnReadFailed'
	| 'firmwareFlashTick'
	| 'firmwareFlashLinkRestored'
	| 'firmwareFlashCancel'
	| 'firmwareFlashDismiss'
	| 'firmwareFlashStatus'
>;

/** The DE1 link surface the driver needs — `De1Manager` plus a build read. */
export interface FlashIo {
	/** One write with response; rejects on failure. */
	write(target: WriteTarget, data: Uint8Array): Promise<void>;
	/** A fresh GATT read of A009. */
	readFwMap(): Promise<Uint8Array>;
	/** Enable A009 notifications. */
	subscribeFwMap(): Promise<void>;
	/** Issue the core's MMR `0x800010` read through the normal write path. */
	readBuild(): Promise<void>;
	/** Drop every non-flash write while `on`. */
	setExclusive(on: boolean): void;
	/** Route A009 notifications (`null` to stop). */
	setFwMapListener(listener: ((data: Uint8Array, atMs: number) => void) | null): void;
	/** Observe link loss / recovery; returns the unsubscribe. */
	addLinkListener(listener: { lost(): void; restored(): void }): () => void;
}

/** Screen-on + leave-page guard while a flash runs. */
export interface FlashKeepAlive {
	/** `true` while the flash must not be interrupted. */
	set(active: boolean): void;
}

export interface FlashDriverOptions {
	/** Every status change (throttled while uploading); `null` = no flash. */
	onStatus: (status: FlashStatus | null) => void;
	/** One-line diagnostics for the event log. */
	onLog?: (line: string) => void;
	keepAlive?: FlashKeepAlive;
	now?: () => number;
	setInterval?: (fn: () => void, ms: number) => unknown;
	clearInterval?: (id: unknown) => void;
}

/** Phases during which the page must stay open. */
const ACTIVE: ReadonlySet<FlashPhase> = new Set([
	FlashPhase.Preparing,
	FlashPhase.Erasing,
	FlashPhase.Uploading,
	FlashPhase.Verifying
]);

export class FlashDriver {
	private chain: Promise<void> = Promise.resolve();
	private queue: FlashAction[] = [];
	private pumping = false;
	private busy = 0;
	private timer: unknown = null;
	private unlink: (() => void) | null = null;
	private status: FlashStatus | null = null;
	private lastPublish = 0;
	private uploadStartedAt: number | null = null;
	private readonly now: () => number;

	constructor(
		private readonly core: FlashCore,
		private readonly io: FlashIo,
		private readonly opts: FlashDriverOptions
	) {
		this.now = opts.now ?? (() => performance.now());
	}

	/** The latest status snapshot (`null` with no flash). */
	get current(): FlashStatus | null {
		return this.status;
	}

	/**
	 * Start flashing `image`. Resolves to the core's refusal, or `null` once
	 * the first action is under way.
	 */
	async start(image: Uint8Array, conditions: FlashShellConditions): Promise<FlashStartError | null> {
		const step = await this.core.firmwareFlashStart(image, conditions, this.now());
		if (step.error) {
			this.apply(step, true);
			return step.error;
		}
		this.attach();
		this.opts.onLog?.(
			`Firmware: flashing build ${step.status?.targetBuild} (${step.status?.chunksTotal} writes)`
		);
		this.apply(step, true);
		return null;
	}

	/** Cancel while the core still allows it. */
	cancel(): Promise<void> {
		return this.serial(async () => {
			this.queue = [];
			this.apply(await this.core.firmwareFlashCancel(this.now()), true);
		});
	}

	/** Forget a finished flash and stop listening. */
	async dismiss(): Promise<boolean> {
		const cleared = await this.core.firmwareFlashDismiss();
		if (cleared) {
			this.detach();
			this.status = null;
			this.opts.keepAlive?.set(false);
			this.io.setExclusive(false);
			this.opts.onStatus(null);
		}
		return cleared;
	}

	/** Re-read the status (e.g. when the flow reopens) and re-attach. */
	async refresh(): Promise<FlashStatus | null> {
		const step = await this.core.firmwareFlashStatus(this.now());
		if (step.status && this.timer === null) this.attach();
		this.apply(step, true);
		return this.status;
	}

	/** Stop the clock and listeners (the core keeps the flash). */
	dispose(): void {
		this.detach();
	}

	private attach(): void {
		if (this.timer !== null) return;
		const set = this.opts.setInterval ?? ((fn, ms) => setInterval(fn, ms));
		this.timer = set(() => this.tick(), TICK_MS);
		this.io.setFwMapListener((data, atMs) => {
			void this.serial(async () => this.apply(await this.core.firmwareFlashOnFwMap(data, atMs)));
		});
		this.unlink = this.io.addLinkListener({
			lost: () => {
				// The core heard it from `de1LinkLost`; publish the new phase.
				void this.serial(async () => {
					this.queue = [];
					this.apply(await this.core.firmwareFlashStatus(this.now()), true);
				});
			},
			restored: () => {
				void this.serial(async () =>
					this.apply(await this.core.firmwareFlashLinkRestored(this.now()), true)
				);
			}
		});
	}

	private detach(): void {
		if (this.timer !== null) {
			(this.opts.clearInterval ?? ((id) => clearInterval(id as number)))(this.timer);
			this.timer = null;
		}
		this.io.setFwMapListener(null);
		this.unlink?.();
		this.unlink = null;
	}

	private tick(): void {
		// Skip a tick while an action is still in flight — the next one
		// catches up; the core's timeouts are computed from timestamps.
		if (this.busy > 0 || this.pumping) return;
		void this.serial(async () => this.apply(await this.core.firmwareFlashTick(this.now())));
	}

	/** Run `fn` after everything already queued. */
	private serial(fn: () => Promise<void>): Promise<void> {
		this.busy++;
		const run = this.chain.then(fn).catch((error: unknown) => {
			this.opts.onLog?.(`Firmware: driver error: ${describeError(error)}`);
		});
		this.chain = run.finally(() => {
			this.busy--;
		});
		return run;
	}

	/** Fold a step: publish its status, queue its actions, keep the link. */
	private apply(step: FlashStep, force = false): void {
		const prev = this.status;
		const status = step.status ?? null;
		this.status = status;
		const locked = status?.locksWrites ?? false;
		this.io.setExclusive(locked);
		this.opts.keepAlive?.set(status !== null && ACTIVE.has(status.phase));
		this.trackUpload(prev, status);
		const now = this.now();
		const phaseChanged = prev?.phase !== status?.phase || prev?.failure !== status?.failure;
		if (force || phaseChanged || now - this.lastPublish >= STATUS_PUBLISH_MS) {
			this.lastPublish = now;
			this.opts.onStatus(status);
		}
		if (status !== null && !ACTIVE.has(status.phase) && status.phase !== FlashPhase.AwaitingPowerCycle) {
			// Terminal: nothing more will come from the core unaided.
			if (prev === null || ACTIVE.has(prev.phase) || prev.phase === FlashPhase.AwaitingPowerCycle) {
				this.opts.onLog?.(`Firmware: ${status.phase}${status.failure ? ` (${status.failure.type})` : ''}`);
			}
		}
		if (step.actions.length > 0) {
			this.queue.push(...step.actions);
			void this.pump();
		}
	}

	/** Log the measured write rate when the upload ends (the throughput). */
	private trackUpload(prev: FlashStatus | null, next: FlashStatus | null): void {
		if (next?.phase === FlashPhase.Uploading && prev?.phase !== FlashPhase.Uploading) {
			this.uploadStartedAt = this.now();
		}
		if (prev?.phase === FlashPhase.Uploading && next?.phase !== FlashPhase.Uploading && this.uploadStartedAt !== null) {
			const secs = (this.now() - this.uploadStartedAt) / 1000;
			const n = next?.chunksAcked ?? 0;
			if (n > 0) {
				this.opts.onLog?.(
					`Firmware: ${n} writes in ${secs.toFixed(0)} s (${((secs * 1000) / n).toFixed(1)} ms per write)`
				);
			}
			this.uploadStartedAt = null;
		}
	}

	/** Perform queued actions one at a time, feeding each result back. */
	private async pump(): Promise<void> {
		if (this.pumping) return;
		this.pumping = true;
		try {
			while (this.queue.length > 0) {
				const action = this.queue.shift() as FlashAction;
				await this.serial(() => this.perform(action));
			}
		} finally {
			this.pumping = false;
		}
	}

	private async perform(action: FlashAction): Promise<void> {
		switch (action.type) {
			case 'Write': {
				const { target, data } = action.content;
				try {
					await this.io.write(target, Uint8Array.from(data));
				} catch (error) {
					this.queue = [];
					this.apply(
						await this.core.firmwareFlashOnWriteFailed(describeError(error), this.now()),
						true
					);
					return;
				}
				this.apply(await this.core.firmwareFlashOnWriteAck(this.now()));
				return;
			}
			case 'SubscribeFwMap':
				// Best effort: the poll covers a failed subscribe (decaid).
				await this.io.subscribeFwMap().catch((error: unknown) => {
					this.opts.onLog?.(`Firmware: A009 subscribe failed: ${describeError(error)}`);
				});
				return;
			case 'ReadFwMap': {
				let bytes: Uint8Array;
				try {
					bytes = await this.io.readFwMap();
				} catch {
					this.apply(await this.core.firmwareFlashOnReadFailed(this.now()));
					return;
				}
				this.apply(await this.core.firmwareFlashOnFwMap(bytes, this.now()));
				return;
			}
			case 'ReadBuild':
				await this.io.readBuild().catch(() => undefined);
				// The reply lands through the normal notification path, which
				// feeds the core's flash; publish whatever it decided.
				this.apply(await this.core.firmwareFlashStatus(this.now()), true);
				return;
		}
	}
}
