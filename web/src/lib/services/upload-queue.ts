/**
 * `$lib/services/upload-queue` — persistent retry queue for Visualizer sync
 * operations (docs/53 §1.3, §2.4 PR 3.5, T-14).
 *
 * The Effect-native home for `visualizer/upload-queue.ts`. Every push that
 * fails for a *recoverable* reason (network / 5xx / 408) is enqueued; the queue
 * persists to localStorage so a tab refresh doesn't drop it, and drains on
 * launch / `online` / the 5-min foreground tick / each sync tail.
 *
 * What changed from the old module:
 *  - the `draining: boolean` singleton guard becomes an atomic `Ref<boolean>`
 *    single-flight (a concurrent drain still coalesces to `deferred` without
 *    blocking — a plain `Semaphore.withPermits` would serialise + re-drain,
 *    which changes behavior);
 *  - the 5-min foreground tick becomes `Schedule.spaced("5 minutes")` under
 *    `Effect.forkDaemon` (matching the old never-cleared `setInterval`);
 *  - `runEntry` calls the new `ShotSync` / `BeanSync` services;
 *  - **intentional behavior change (the one in the plan)**: `attempts` now
 *    counts *attempts made*, not *attempts queued*. The old `enqueue()` bumped
 *    `attempts` on every re-enqueue (including the first failure, before any
 *    retry ran), so the per-entry ceiling was one short. Now the drain owns the
 *    counter: a fresh entry starts at 0, each failed drain increments it, and
 *    the entry is dropped when it reaches `MAX_ATTEMPTS` — so a persistently
 *    failing recoverable op is retried a full 3 times before it's dropped.
 *    `enqueue()` no longer touches `attempts`.
 *
 * NOT wired into production — `visualizer/upload-queue.ts` stays the live impl
 * until the T-16 facade swap; `UploadQueueLive` only composes into `AppLayer`.
 * Store-coupled (`$lib/history` in `runEntry`), so not node:test-able — the
 * attempts-ceiling test lands with the Phase 7 `@effect/vitest` migration
 * (`TestClock` drives the backoff deterministically).
 */

import { Context, Effect, Layer, Ref, Schedule } from 'effect';
import { ShotSync } from './shot-sync.ts';
import { BeanSync } from './bean-sync.ts';
import type {
	HttpStatusError,
	NetworkError,
	NotAuthenticatedError,
	ResponseDecodeError,
	TokenRefreshFailedError,
	VisualizerNotFoundError,
	VisualizerPremiumGatedError
} from '../effect/errors.ts';
import {
	clearQueue as clearQueueStore,
	dequeueEntry,
	enqueueEntry,
	isPendingId,
	MAX_ATTEMPTS,
	persistRetry,
	readQueue,
	type EnqueueInput,
	type QueueEntry
} from './queue-store.ts';
import { getHistoryStore } from '$lib/history';

export type { EnqueueInput, QueueEntry } from './queue-store.ts';

export interface DrainResult {
	processed: number;
	succeeded: number;
	dropped: number;
	deferred: number;
}

const DEFERRED: DrainResult = { processed: 0, succeeded: 0, dropped: 0, deferred: 1 };

/** Every failure `runEntry` can surface (from ShotSync / BeanSync). */
type UploadError =
	| NetworkError
	| HttpStatusError
	| NotAuthenticatedError
	| TokenRefreshFailedError
	| VisualizerPremiumGatedError
	| VisualizerNotFoundError
	| ResponseDecodeError;

/**
 * Recoverable = worth a time-based retry: a transport failure, or a transient
 * 5xx / 408 / aborted status. Auth / premium / not-found / decode failures need
 * user action, not time, so they're dropped on the first drain.
 */
function isRecoverable(e: UploadError): boolean {
	switch (e._tag) {
		case 'NetworkError':
			return true;
		case 'HttpStatusError':
			return e.status === 0 || e.status === 408 || (e.status >= 500 && e.status < 600);
		default:
			return false;
	}
}

function describeError(e: UploadError): string {
	switch (e._tag) {
		case 'HttpStatusError':
			return `HTTP ${e.status}: ${e.body ?? ''}`.trim();
		case 'NetworkError':
			return `Network error: ${e.cause instanceof Error ? e.cause.message : String(e.cause)}`;
		case 'VisualizerPremiumGatedError':
			return 'Premium subscription required for writes.';
		case 'VisualizerNotFoundError':
			return `Visualizer row not found: ${e.visualizerId}`;
		case 'NotAuthenticatedError':
			return 'Not signed in to Visualizer.';
		case 'TokenRefreshFailedError':
			return 'Visualizer session expired.';
		case 'ResponseDecodeError':
			return 'Unexpected Visualizer response.';
	}
}

export class UploadQueue extends Context.Tag('crema/UploadQueue')<
	UploadQueue,
	{
		/**
		 * Register a sync op (set-like on entity+id+op). A fresh entry starts at
		 * `attempts: 0`; an existing one only has its `lastError` / `visualizerId`
		 * refreshed — the drain owns the attempt counter + schedule.
		 */
		readonly enqueue: (input: EnqueueInput) => Effect.Effect<void>;
		readonly dequeue: (
			entity: QueueEntry['entity'],
			id: string,
			op: QueueEntry['op']
		) => Effect.Effect<void>;
		readonly isPending: (entity: QueueEntry['entity'], id: string) => Effect.Effect<boolean>;
		/** Current queue snapshot for the sync UI. */
		readonly getQueue: Effect.Effect<readonly QueueEntry[]>;
		readonly clearQueue: Effect.Effect<void>;
		/** Drain every ready entry once. Concurrent calls coalesce to `deferred`. */
		readonly drain: Effect.Effect<DrainResult>;
		/** Arm the on-launch + 5-min-tick drain loop. Idempotent. */
		readonly armLifecycle: Effect.Effect<void>;
	}
>() {}

export const UploadQueueLive = Layer.effect(
	UploadQueue,
	Effect.gen(function* () {
		const shotSync = yield* ShotSync;
		const beanSync = yield* BeanSync;
		const draining = yield* Ref.make(false);
		const lifecycleArmed = yield* Ref.make(false);

		const enqueue = (input: EnqueueInput): Effect.Effect<void> =>
			Effect.sync(() => enqueueEntry(input));

		const dequeue = (
			entity: QueueEntry['entity'],
			id: string,
			op: QueueEntry['op']
		): Effect.Effect<void> => Effect.sync(() => dequeueEntry(entity, id, op));

		const isPending = (entity: QueueEntry['entity'], id: string): Effect.Effect<boolean> =>
			Effect.sync(() => isPendingId(entity, id));

		const getQueue = Effect.sync(() => readQueue().entries as readonly QueueEntry[]);
		const clearQueue = Effect.sync(() => clearQueueStore());

		/** Perform the actual sync call for one entry. */
		const runEntry = (entry: QueueEntry): Effect.Effect<void, UploadError> =>
			Effect.gen(function* () {
				if (entry.entity === 'shot') {
					if (entry.op === 'create') {
						const shot = yield* Effect.sync(() => getHistoryStore().get(entry.id));
						if (!shot) return; // local row vanished — nothing to upload
						if (shot.visualizerId) return; // raced; already bound
						const { visualizerId } = yield* shotSync.uploadShot(shot);
						yield* Effect.sync(() => getHistoryStore().bindVisualizerId(entry.id, visualizerId));
						return;
					}
					if (entry.op === 'delete' && entry.visualizerId) {
						yield* shotSync.deleteShot(entry.visualizerId);
						yield* Effect.sync(() => getHistoryStore().purgeTombstone(entry.id));
						return;
					}
				}
				// Bean / roaster create+update still ride the visualizer-sync runSync
				// layer (never enqueued today) — but a delete only needs the remote id,
				// so future-proof it through BeanSync.
				if (entry.op === 'delete' && entry.visualizerId) {
					if (entry.entity === 'bean') yield* beanSync.deleteBean(entry.visualizerId);
					else if (entry.entity === 'roaster') yield* beanSync.deleteRoaster(entry.visualizerId);
				}
			});

		const doDrain = Effect.gen(function* () {
			const out: DrainResult = { processed: 0, succeeded: 0, dropped: 0, deferred: 0 };
			const now = Date.now();
			const ready = readQueue().entries.filter((e) => e.nextAttemptAt <= now);
			for (const entry of ready) {
				out.processed += 1;
				const result = yield* Effect.either(runEntry(entry));
				if (result._tag === 'Right') {
					yield* dequeue(entry.entity, entry.id, entry.op);
					out.succeeded += 1;
				} else {
					const made = entry.attempts + 1;
					if (!isRecoverable(result.left) || made >= MAX_ATTEMPTS) {
						yield* dequeue(entry.entity, entry.id, entry.op);
						out.dropped += 1;
					} else {
						yield* Effect.sync(() => persistRetry(entry, made, describeError(result.left)));
					}
				}
			}
			return out;
		});

		const isOffline = Effect.sync(
			() => typeof navigator !== 'undefined' && navigator.onLine === false
		);

		const drain: Effect.Effect<DrainResult> = Effect.gen(function* () {
			if (yield* isOffline) return DEFERRED;
			// Atomic non-blocking single-flight: acquire only if not already busy.
			const acquired = yield* Ref.modify(draining, (busy) =>
				busy ? [false, true] : [true, true]
			);
			if (!acquired) return DEFERRED;
			return yield* doDrain.pipe(Effect.ensuring(Ref.set(draining, false)));
		});

		/** One scheduled tick — skip while hidden / offline, else drain. */
		const tick = Effect.gen(function* () {
			const skip = yield* Effect.sync(
				() =>
					(typeof document !== 'undefined' && document.hidden) ||
					(typeof navigator !== 'undefined' && navigator.onLine === false)
			);
			if (!skip) yield* drain;
		});

		const armLifecycle = Effect.gen(function* () {
			const already = yield* Ref.getAndSet(lifecycleArmed, true);
			if (already) return;
			// Flush any backlog now, then drain every 5 min. `forkDaemon` matches
			// the old never-cleared `setInterval` lifetime (lives until the runtime
			// is disposed). The `online` / `visibilitychange` DOM listeners are
			// wired at the call site at T-16, where the runtime is in scope —
			// bridging DOM events into a fiber needs the runtime handle (D-07).
			yield* Effect.forkDaemon(drain);
			yield* Effect.forkDaemon(Effect.repeat(tick, Schedule.spaced('5 minutes')));
		});

		return UploadQueue.of({
			enqueue,
			dequeue,
			isPending,
			getQueue,
			clearQueue,
			drain,
			armLifecycle
		});
	})
);
