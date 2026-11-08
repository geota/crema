/**
 * `#lib/services/upload-queue.js` — persistent retry queue for Visualizer sync
 * operations (docs/53 §1.3, §2.4 PR 3.5, T-14).
 *
 * Every push that fails for a *recoverable* reason (network / 5xx / 408) is
 * enqueued; the queue
 * persists to localStorage so a tab refresh doesn't drop it, and drains on
 * launch / `online` / the 5-min foreground tick / each sync tail.
 *
 * What changed from the old module:
 *  - the `draining: boolean` singleton guard becomes an atomic `Ref<boolean>`
 *    single-flight (a concurrent drain still coalesces to `deferred` without
 *    blocking — a plain `Semaphore.withPermits` would serialise + re-drain,
 *    which changes behavior);
 *  - the 5-min foreground tick becomes `Schedule.spaced("5 minutes")` forked
 *    into the layer's scope, so `runtime.dispose()` stops it (review 03 P3-8);
 *  - `runEntry` calls the new `ShotSync` / `BeanSync` services;
 *  - **intentional behavior change (the one in the plan)**: `attempts` now
 *    counts *attempts made*, not *attempts queued*. The old `enqueue()` bumped
 *    `attempts` on every re-enqueue (including the first failure, before any
 *    retry ran), so the per-entry ceiling was one short. Now the drain owns the
 *    counter: a fresh entry starts at 0, each failed drain increments it, and
 *    the entry is dropped when it reaches the core's `MAX_QUEUE_ATTEMPTS` (3) — so a persistently
 *    failing recoverable op is retried a full 3 times before it's dropped.
 *    `enqueue()` no longer touches `attempts`.
 *
 * `UploadQueueLive` is the production implementation, composed into `AppLayer`.
 * Store-coupled (`#lib/history` in `runEntry`), so not node:test-able — the
 * attempts-ceiling test rides `@effect/vitest` (`TestClock` drives the backoff
 * deterministically).
 */

import { Context, Effect, Layer, Ref, Schedule, Scope } from 'effect';
import { ShotSync } from './shot-sync.ts';
import { BeanSync } from './bean-sync.ts';
import { ResponseDecodeError } from '../effect/errors.ts';
import {
	describeVisualizerError,
	isRateLimited,
	queueDecision,
	type VisualizerCallError
} from './visualizer-call.ts';
import {
	dequeueEntry,
	enqueueEntry,
	persistRetry,
	readQueue,
	type EnqueueInput,
	type QueueEntry
} from './queue-store.ts';
import { confirmAccount } from './visualizer-account.ts';
import { hasStoredVisualizerTokens } from './token-vault.ts';
import { getHistoryStore } from '#lib/history/index.js';
import { toast } from '#lib/components/shared/toast.svelte.js';
import { appendSyncLog } from '#lib/visualizer/sync-config.js';
import { directionPushes, readSyncConfig, uploadsHeld } from '#lib/visualizer/sync-config.js';
import { coolingDown } from '#lib/visualizer/pace.js';
import { runHeldElsewhere } from '#lib/visualizer/run-lock.js';
import {
	hasTagMarks,
	pendingEdit,
	pendingEdits,
	settlePendingEdit
} from '#lib/visualizer/pending-edits.js';

export type { EnqueueInput, QueueEntry } from './queue-store.ts';

export interface DrainResult {
	processed: number;
	succeeded: number;
	dropped: number;
	deferred: number;
	/** Remote deletes that landed: a pull between pages counts them as its own. */
	deletes?: number;
}

/** A remote delete Visualizer won't take: it says so, and the local delete stands. */
function reportRefusedDelete(entry: QueueEntry, why: string): void {
	const name = entry.entity === 'shot' ? 'Shot' : entry.entity === 'bean' ? 'Bag' : 'Roaster';
	appendSyncLog({ direction: 'delete', entity: entry.entity, id: entry.id, name, at: Date.now(), error: why });
	toast.error(`Visualizer refused to delete a ${name.toLowerCase()}, so its copy stays there: ${why}`);
}

const DEFERRED: DrainResult = { processed: 0, succeeded: 0, dropped: 0, deferred: 1 };

/** How often one tier signal may probe the tier (`POST /roasters`, a write). */
const TIER_PROBE_SPACING_MS = 24 * 60 * 60 * 1000;

/**
 * Every failure `runEntry` can surface (from ShotSync / BeanSync). What the
 * queue does with one is the core's rule (`queueDecision` in
 * `visualizer-call.ts`); `describeVisualizerError` words it.
 */
type UploadError = VisualizerCallError | ResponseDecodeError;

export class UploadQueue extends Context.Service<
	UploadQueue,
	{
		/**
		 * Register a sync op (set-like on entity+id+op). A fresh entry starts at
		 * `attempts: 0`; an existing one only has its `lastError` / `visualizerId`
		 * refreshed — the drain owns the attempt counter + schedule.
		 */
		readonly enqueue: (input: EnqueueInput) => Effect.Effect<void>;
		/** Drain every ready entry once. Concurrent calls coalesce to `deferred`. */
		readonly drain: Effect.Effect<DrainResult>;
		/** Arm the on-launch + 5-min-tick drain loop. Idempotent. */
		readonly armLifecycle: Effect.Effect<void>;
	}
>()('crema/UploadQueue') {}

export const UploadQueueLive = Layer.effect(
	UploadQueue,
	Effect.gen(function* () {
		const shotSync = yield* ShotSync;
		const beanSync = yield* BeanSync;
		const draining = yield* Ref.make(false);
		const lifecycleArmed = yield* Ref.make(false);
		// The layer's own scope: closed by `ManagedRuntime.dispose()`, which
		// interrupts whatever was forked into it.
		const layerScope = yield* Scope.Scope;

		const enqueue = (input: EnqueueInput): Effect.Effect<void> =>
			Effect.sync(() => enqueueEntry(input));

		const dequeue = (
			entity: QueueEntry['entity'],
			id: string,
			op: QueueEntry['op']
		): Effect.Effect<void> => Effect.sync(() => dequeueEntry(entity, id, op));

		/** Perform the actual sync call for one entry. */
		const runEntry = (entry: QueueEntry): Effect.Effect<void, UploadError> =>
			Effect.gen(function* () {
				if (entry.entity === 'shot') {
					if (entry.op === 'create') {
						// Spends no attempt, and a row read before this has no curve.
						yield* Effect.promise(() => getHistoryStore().seriesReady());
						const shot = yield* Effect.sync(() => getHistoryStore().get(entry.id));
						// Deleted meanwhile: the row is a tombstone now, and uploading it
						// would only make a remote copy to delete.
						if (!shot || shot.deletedAt != null) return;
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

		/**
		 * Visualizer didn't keep what was sent for `id`: ask the tier with a real
		 * probe (a cached answer, however fresh, may predate a lapse), at most
		 * once a day per signal, since the probe is a sentinel write. The
		 * attempt is stamped before it goes, so a probe that gets no answer
		 * waits a day too. One probe serves every shot in a drain (`probe`).
		 * Premium confirmed means the tags will never take, so the mark clears
		 * and says so rather than loop; Free leaves the mark until an upgrade.
		 */
		const settleTierSignal = (id: string, probe: { answer?: boolean | null }): Effect.Effect<void> =>
			Effect.gen(function* () {
				const signalled = pendingEdit(id);
				if (!signalled?.tierSignal) return;
				const now = Date.now();
				if (signalled.tierProbedAt != null && now - signalled.tierProbedAt < TIER_PROBE_SPACING_MS) return;
				settlePendingEdit(id, { ...signalled, tierProbedAt: now });
				if (probe.answer === undefined) probe.answer = yield* beanSync.refreshPremium;
				const premium = probe.answer;
				const edit = pendingEdit(id);
				if (!edit?.tierSignal || premium === null) return;
				if (!premium) {
					settlePendingEdit(id, { ...edit, tierSignal: undefined, tierProbedAt: undefined });
					return;
				}
				settlePendingEdit(id, { ...edit, tags: undefined, tierSignal: undefined, tierProbedAt: undefined });
				if (hasTagMarks(edit.tags)) {
					appendSyncLog({
						direction: 'skip',
						entity: 'shot',
						id,
						name: getHistoryStore().get(id)?.profileName ?? 'Shot',
						at: Date.now(),
						error: "Visualizer didn't keep this shot's tags, so Crema stopped sending them."
					});
				}
			});

		/**
		 * The PATCH retry list: every shot with an edit due, sent from the row as
		 * it stands. Not counted in the drain's result, which is the queue's.
		 */
		const drainEdits = Effect.gen(function* () {
			if (!directionPushes(readSyncConfig().direction.shots)) return;
			const history = getHistoryStore();
			const now = Date.now();
			const probe: { answer?: boolean | null } = {};
			for (const [id, edit] of pendingEdits()) {
				if ((edit.nextAttemptAt ?? 0) > now) continue;
				if (edit.tierSignal) yield* settleTierSignal(id, probe);
				const result = yield* Effect.result(
					shotSync.pushPendingEdit(history, id).pipe(
						Effect.catchDefect((cause) =>
							Effect.fail(new ResponseDecodeError({ url: `shot:${id}`, cause }))
						)
					)
				);
				// The rest wait out the limit.
				if (result._tag === 'Failure' && isRateLimited(result.failure)) return;
			}
		});

		const doDrain = Effect.gen(function* () {
			const out: DrainResult = { processed: 0, succeeded: 0, dropped: 0, deferred: 0 };
			const now = Date.now();
			let limited = false;
			const ready = readQueue().entries.filter((e) => e.nextAttemptAt <= now && !e.parkedFor);
			for (const entry of ready) {
				out.processed += 1;
				// A throw is a defect, which `Effect.result` lets through: one row
				// that can't be exported must not stop the rest of the queue. It
				// goes through the decision below as a terminal error, so the entry
				// is dropped at once; a row that throws won't stop throwing.
				const result = yield* Effect.result(
					runEntry(entry).pipe(
						Effect.catchDefect((cause) =>
							Effect.fail(new ResponseDecodeError({ url: `${entry.entity}:${entry.id}`, cause }))
						)
					)
				);
				if (result._tag === 'Success') {
					yield* dequeue(entry.entity, entry.id, entry.op);
					out.succeeded += 1;
					if (entry.op === 'delete') out.deletes = (out.deletes ?? 0) + 1;
				} else {
					const e = result.failure;
					const made = entry.attempts + 1;
					// The rule is the core's (`visualizer_queue_decision`), shared with
					// Android: the free-plan daily cap drops and stops (the shot stays
					// unsynced, so the next pass re-offers it); a remote delete while
					// signed out parks without spending an attempt (nothing else
					// re-offers it); a terminal or third failure drops; otherwise retry
					// on the core backoff, and a 429 leaves the rest for a later drain.
					const decision = queueDecision(entry.op, e, made);
					if (decision.kind === 'Park') {
						yield* Effect.sync(() =>
							persistRetry(entry, entry.attempts, describeVisualizerError(e), decision.details.delay_ms)
						);
						out.deferred += 1;
						continue;
					}
					if (decision.kind === 'Drop') {
						yield* dequeue(entry.entity, entry.id, entry.op);
						out.dropped += 1;
						// The row's tombstone stays, so the copy is never pulled back.
						if (entry.op === 'delete') {
							yield* Effect.sync(() => reportRefusedDelete(entry, describeVisualizerError(e)));
						}
						if (decision.details.stop_pass) break;
						continue;
					}
					yield* Effect.sync(() =>
						persistRetry(entry, made, describeVisualizerError(e), decision.details.delay_ms)
					);
					if (decision.details.stop_pass) {
						limited = true;
						break;
					}
				}
			}
			if (!limited) yield* drainEdits;
			return out;
		});

		const isOffline = Effect.sync(
			() => typeof navigator !== 'undefined' && navigator.onLine === false
		);

		const drain: Effect.Effect<DrainResult> = Effect.gen(function* () {
			if (yield* isOffline) return DEFERRED;
			// Nothing moves until `/me` says whose session this is, so each drain
			// asks. A session from before this was recorded gets its id here too.
			const config = readSyncConfig();
			if (config.accountUnconfirmed || (config.syncAccountId === null && hasStoredVisualizerTokens())) {
				const known = yield* confirmAccount.pipe(Effect.provideService(BeanSync, beanSync));
				if (!known && config.accountUnconfirmed) return DEFERRED;
			}
			if (uploadsHeld()) return DEFERRED;
			// A 429's cooldown: nothing goes before it ends. And a pull in another
			// tab runs the drain between its pages, so this one waits.
			if (coolingDown()) return DEFERRED;
			if (yield* Effect.promise(runHeldElsewhere)) return DEFERRED;
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
			// `Effect.repeat` runs `tick` ONCE immediately (the startup flush) and
			// then every 5 min thereafter, so a single daemon covers both. The old
			// code also armed a separate detached `drain` fiber, double-draining on
			// launch (EF4) — dropped. (Lone nuance: the startup flush now rides
			// `tick`, which skips while the tab is hidden/offline; at app launch the
			// tab is visible, and the `online` event + each sync tail still drain, so
			// a backlog is never stranded.) The loop is forked into the layer scope,
			// so it lives exactly as long as the runtime: `dispose()` (shell unmount,
			// HMR) interrupts it. A detached fork would outlive the runtime. The
			// `online` / `visibilitychange` DOM listeners are wired at the call site
			// at T-16, where the runtime is in scope — bridging DOM events into a
			// fiber needs the runtime handle (D-07).
			yield* Effect.forkIn(Effect.repeat(tick, Schedule.spaced('5 minutes')), layerScope);
		});

		return UploadQueue.of({
			enqueue,
			drain,
			armLifecycle
		});
	})
);
