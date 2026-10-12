/**
 * `$lib/firmware/flash-driver` — runs a DE1 firmware flash for the web shell.
 *
 * Every protocol decision lives in the Rust core (`de1_app::firmware_flash`):
 * which bytes, which characteristic, when a phase is over, when to give up,
 * how long the link stays locked. The core answers each input with a
 * `FlashStep` — `actions` to perform and a `status` snapshot. This driver is
 * the I/O half and nothing more:
 *
 * - performs the actions strictly **one at a time** (one write-with-response
 *   in flight — de1app `bluetooth.tcl:3303-3312`, decaid
 *   `unified_de1.firmware.dart:239`) and reports each ACK, failure or read
 *   back to the core;
 * - bounds every GATT call ({@link WRITE_TIMEOUT_MS}, {@link READ_TIMEOUT_MS}),
 *   so a promise Chrome never settles can't hold the lock forever;
 * - routes `FWMapRequest` (A009) notifications and link loss / recovery to it;
 * - ticks it every {@link TICK_MS}, also while an action is in flight (the
 *   core's timeouts are computed from timestamps and it ignores stale ACKs);
 * - holds the DE1 link exclusively while the core says writes are locked, and
 *   keeps the page awake while a flash is running.
 *
 * All calls into the core go through one serial chain, so a tick, a
 * notification and an ACK can never interleave. Queued actions carry the
 * generation they were issued in; a cancel, failure or link loss starts a new
 * one, so a chunk queued before it is never written after it (audit L1).
 */

import type {
	CremaCore,
	FlashAction,
	FlashShellConditions,
	FlashStartError,
	FlashStatus,
	FlashStep
} from '$lib/core';
import { FlashPhase, WriteTarget } from '$lib/core/crema-core';
import { describeError } from '$lib/utils/error';

/** The core's flash clock cadence (decaid polls A009 every 250 ms). */
export const TICK_MS = 250;

/** How often a running upload republishes its status (UI churn bound). */
export const STATUS_PUBLISH_MS = 250;

/** Bound on one GATT write (Decenza / the Android shell use 5 s). */
export const WRITE_TIMEOUT_MS = 5_000;

/** Bound on one A009 read (decaid `_firmwareMapPollReadTimeout`). */
export const READ_TIMEOUT_MS = 2_000;

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
	| 'firmwareFlashExit'
	| 'firmwareFlashStatus'
>;

/** The DE1 link surface the driver needs (`De1Manager`). */
export interface FlashIo {
	/** One flash write with response (allowed through the lock); rejects on failure. */
	write(target: WriteTarget, data: Uint8Array): Promise<void>;
	/** A fresh GATT read of A009. */
	readFwMap(): Promise<Uint8Array>;
	/** Enable A009 notifications. */
	subscribeFwMap(): Promise<void>;
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
	/** Overridable for tests. */
	writeTimeoutMs?: number;
	readTimeoutMs?: number;
}

/** Phases during which the page must stay open. */
const ACTIVE: ReadonlySet<FlashPhase> = new Set([
	FlashPhase.Preparing,
	FlashPhase.Erasing,
	FlashPhase.Uploading,
	FlashPhase.Verifying
]);

/** Reject after `ms` with a timeout error. */
export function withTimeout<T>(p: Promise<T>, ms: number, what: string): Promise<T> {
	return new Promise<T>((resolve, reject) => {
		const t = setTimeout(() => reject(new Error(`${what} timed out after ${ms} ms`)), ms);
		p.then(
			(v) => {
				clearTimeout(t);
				resolve(v);
			},
			(e: unknown) => {
				clearTimeout(t);
				reject(e);
			}
		);
	});
}

interface Queued {
	action: FlashAction;
	gen: number;
}

export class FlashDriver {
	private chain: Promise<void> = Promise.resolve();
	private queue: Queued[] = [];
	private gen = 0;
	private pumping = false;
	private tickQueued = false;
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
		this.gen++;
		this.attach();
		this.opts.onLog?.(
			`Firmware: flashing build ${step.status?.targetBuild} (${step.status?.chunksTotal} writes)`
		);
		this.apply(step, true);
		return null;
	}

	/** Cancel while the core still allows it (its Sleep follows). */
	cancel(): Promise<void> {
		return this.serial(async () => {
			this.gen++;
			this.queue = [];
			this.apply(await this.core.firmwareFlashCancel(this.now()), true);
		});
	}

	/** Forget a finished, unlocked flash and stop listening. */
	async dismiss(): Promise<boolean> {
		const cleared = await this.core.firmwareFlashDismiss();
		if (cleared) this.released();
		return cleared;
	}

	/** The user's explicit exit from the power-cycle step. */
	async exit(): Promise<boolean> {
		const cleared = await this.core.firmwareFlashExit();
		if (cleared) this.released();
		return cleared;
	}

	private released(): void {
		this.gen++;
		this.queue = [];
		this.detach();
		this.status = null;
		this.opts.keepAlive?.set(false);
		this.io.setExclusive(false);
		this.opts.onStatus(null);
	}

	/** Re-read the status (the flow reopening, a restore) and re-attach. */
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
					this.gen++;
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
		// Ticks run while an action is in flight too (audit M4); coalesce so a
		// slow action can't pile them up.
		if (this.tickQueued) return;
		this.tickQueued = true;
		void this.serial(async () => {
			this.tickQueued = false;
			this.apply(await this.core.firmwareFlashTick(this.now()));
		});
	}

	/** Run `fn` after everything already queued. */
	private serial(fn: () => Promise<void>): Promise<void> {
		const run = this.chain.then(fn).catch((error: unknown) => {
			this.opts.onLog?.(`Firmware: driver error: ${describeError(error)}`);
		});
		this.chain = run;
		return run;
	}

	/** Fold a step: publish its status, queue its actions, keep the link. */
	private apply(step: FlashStep, force = false): void {
		const prev = this.status;
		const status = step.status ?? null;
		this.status = status;
		this.io.setExclusive(status?.locksWrites ?? false);
		this.opts.keepAlive?.set(status !== null && ACTIVE.has(status.phase));
		this.trackUpload(prev, status);
		const now = this.now();
		const changed =
			prev?.phase !== status?.phase ||
			prev?.failure !== status?.failure ||
			prev?.locksWrites !== status?.locksWrites ||
			prev?.restartedOnBuild !== status?.restartedOnBuild;
		if (force || changed || now - this.lastPublish >= STATUS_PUBLISH_MS) {
			this.lastPublish = now;
			this.opts.onStatus(status);
		}
		if (changed && status !== null && !ACTIVE.has(status.phase)) {
			this.opts.onLog?.(
				`Firmware: ${status.phase}${status.failure ? ` (${status.failure.type})` : ''}${status.locksWrites ? ', link held' : ''}`
			);
		}
		if (step.actions.length > 0) {
			for (const action of step.actions) this.queue.push({ action, gen: this.gen });
			void this.pump();
		}
	}

	/** Log the measured write rate when the upload ends (the throughput). */
	private trackUpload(prev: FlashStatus | null, next: FlashStatus | null): void {
		if (next?.phase === FlashPhase.Uploading && prev?.phase !== FlashPhase.Uploading) {
			this.uploadStartedAt = this.now();
		}
		if (
			prev?.phase === FlashPhase.Uploading &&
			next?.phase !== FlashPhase.Uploading &&
			this.uploadStartedAt !== null
		) {
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
				const next = this.queue.shift() as Queued;
				await this.serial(() => this.perform(next));
			}
		} finally {
			this.pumping = false;
		}
	}

	private async perform({ action, gen }: Queued): Promise<void> {
		// Issued before a cancel / failure / link loss: never written after it.
		if (gen !== this.gen) return;
		const wt = this.opts.writeTimeoutMs ?? WRITE_TIMEOUT_MS;
		const rt = this.opts.readTimeoutMs ?? READ_TIMEOUT_MS;
		switch (action.type) {
			case 'Write': {
				const { target, data } = action.content;
				try {
					await withTimeout(this.io.write(target, Uint8Array.from(data)), wt, 'write');
				} catch (error) {
					this.gen++;
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
				await withTimeout(this.io.subscribeFwMap(), wt, 'A009 subscribe').catch(
					(error: unknown) => {
						this.opts.onLog?.(`Firmware: A009 subscribe failed: ${describeError(error)}`);
					}
				);
				return;
			case 'ReadFwMap': {
				let bytes: Uint8Array;
				try {
					bytes = await withTimeout(this.io.readFwMap(), rt, 'A009 read');
				} catch {
					this.apply(await this.core.firmwareFlashOnReadFailed(this.now()));
					return;
				}
				this.apply(await this.core.firmwareFlashOnFwMap(bytes, this.now()));
				return;
			}
			case 'ReadBuild':
				// The MMR read request goes through the lock like the flash's
				// own writes; the reply arrives as an ordinary MMR notification.
				await withTimeout(
					this.io.write(WriteTarget.De1MmrRequest, Uint8Array.from(action.content.data)),
					wt,
					'build read'
				).catch(() => undefined);
				this.apply(await this.core.firmwareFlashStatus(this.now()), true);
				return;
		}
	}
}
