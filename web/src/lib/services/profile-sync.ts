/**
 * `$lib/services/profile-sync` — the lazy DE1 profile-upload coordinator as an
 * Effect service (docs/53 §2.7 PR 6.1, T-21).
 *
 * Lifts the orchestrator's `syncActiveProfile` coordination (and its three
 * mutable private fields) out of `state/app.svelte.ts`. The actual upload still
 * happens through the core + BLE write cascade — the bytes leave via
 * `executeCommand`'s `WriteCharacteristic` → `De1FrameAck` loop, and completion
 * arrives as a `ProfileUploadCompleted` / `ProfileUploadFailed` event folded by
 * `applyCoreOutput`. This service owns only the *coordination*:
 *
 *  - `this.pendingUploadFingerprint`  → `Ref<Option<PendingUpload>>` (fingerprint
 *    + the waiter share one entry now — they were always set/cleared together);
 *  - `this.pendingUploadCompletion`   → a `Deferred<boolean>` inside that entry,
 *    so concurrent callers on the same fingerprint await one outcome (piggyback);
 *  - the 15 s `window.setTimeout` backstop → `Effect.timeoutFail` →
 *    `ProfileUploadTimeoutError` → caught back to `false`;
 *  - `this.lastProfileUploadCompletedAtMs` → `Ref<Option<number>>`, read by the
 *    `profileDownloadGuard` (the BC 9788201734 race — see below).
 *
 * The fold calls `completed` / `failed` (via `runtime.runSync`, synchronous so
 * the supersession `cancel` below observes the cleared pending before it
 * returns); the app keeps the snapshot patch + `localStorage` persist + webhook
 * (those read the rune state / profile store, which this service must not touch).
 *
 * **Deliberate change from the field version**: timestamps use Effect's `Clock`
 * (`Date.now()` live, `TestClock`-driven in tests) instead of
 * `performance.now()`. Over the 500 ms guard window the monotonic-vs-wall-clock
 * difference is immaterial, and it makes the guard + timeout deterministically
 * testable — the whole point of lifting this out (R-03). See
 * `profile-sync.vitest.ts` for the piggyback / supersession / timeout coverage.
 */

import { Clock, Context, Deferred, Duration, Effect, Layer, Option, Ref } from 'effect';
import { ProfileUploadTimeoutError } from '../effect/errors.ts';

/**
 * Minimum ms between `ProfileUploadCompleted` and the next state request. The
 * DE1 firmware finishes the final flash write inside `APIView::write` for the
 * tail frame and only clears `ProfileDownloadInProgress` when that write
 * returns; a state-request that lands inside the window aborts the shot to
 * HeaterDown after preinfuse (BC 9788201734). Reaprime's
 * `ConnectionTimings.profileDownloadGuard` is 500 ms; Crema matches.
 */
export const PROFILE_DOWNLOAD_GUARD_MS = 500;

/** Backstop for a never-arriving Completed/Failed — comfortably beyond a
 *  healthy ~1-2 s upload. On timeout the caller proceeds with `false`. */
const UPLOAD_TIMEOUT = Duration.seconds(15);

/** The in-flight upload's desired fingerprint + the waiter every caller on it
 *  awaits. Fingerprint and waiter were always set/cleared together in the
 *  field version, so they live in one entry here. */
interface PendingUpload {
	readonly fingerprint: string;
	readonly done: Deferred.Deferred<boolean>;
}

/**
 * Everything `sync` needs that lives outside the service — the snapshot read,
 * the core/BLE upload trigger, and the event-log sink. All app-provided so the
 * service stays free of rune-store + `CremaCore` coupling (and unit-testable
 * with fakes).
 */
export interface ProfileSyncRequest {
	/** The effective fingerprint to land on the DE1 (profile + QC overrides). */
	readonly desired: string;
	/** `true` when the snapshot's cached `activeProfileFingerprint` already
	 *  matches `desired` — short-circuits to success with no upload. */
	readonly alreadyLoaded: boolean;
	/** Trigger the upload: `core.uploadProfile` + fold the output. Returns once
	 *  the writes are *dispatched* (not completed); completion arrives later via
	 *  `completed`. */
	readonly upload: Effect.Effect<void>;
	/** Cancel the in-flight upload at the core (emits `ProfileUploadFailed`,
	 *  which the fold turns into `failed`). Awaited before a superseding upload
	 *  installs, so the old waiter is resolved + cleared first. */
	readonly cancel: Effect.Effect<void>;
	/** Append a line to the shared event log (the timeout path uses it). */
	readonly log: (line: string) => void;
}

export class ProfileSync extends Context.Tag('crema/ProfileSync')<
	ProfileSync,
	{
		/**
		 * Ensure the DE1 holds the desired profile, awaiting confirmation.
		 * Returns `true` when loaded (cache hit, or the upload completed), `false`
		 * on upload failure / supersession / the 15 s timeout. Never fails.
		 */
		readonly sync: (req: ProfileSyncRequest) => Effect.Effect<boolean>;
		/**
		 * Called by the `ProfileUploadCompleted` fold. Stamps the
		 * download-guard clock, resolves the in-flight waiter with `true`, and
		 * returns the just-landed fingerprint (or `null` if no upload was
		 * pending) so the caller can commit it to the snapshot + `localStorage`.
		 * Fully synchronous (`runtime.runSync`-safe).
		 */
		readonly completed: Effect.Effect<string | null>;
		/**
		 * Called by the `ProfileUploadFailed` fold. Resolves the in-flight waiter
		 * with `false` and clears the pending entry; the DE1 keeps whatever it had
		 * before, so the snapshot fingerprint is intentionally left unchanged.
		 * Fully synchronous.
		 */
		readonly failed: Effect.Effect<void>;
		/**
		 * Wait out the remaining {@link PROFILE_DOWNLOAD_GUARD_MS} window since the
		 * last `ProfileUploadCompleted`, if any — call before a state request so a
		 * `requestMachineState(Espresso)` can't hit the firmware mid-flash.
		 */
		readonly profileDownloadGuard: Effect.Effect<void>;
	}
>() {}

export const ProfileSyncLive = Layer.effect(
	ProfileSync,
	Effect.gen(function* () {
		const pending = yield* Ref.make<Option.Option<PendingUpload>>(Option.none());
		const lastCompletedAtMs = yield* Ref.make<Option.Option<number>>(Option.none());
		// Serialises the decision section (read pending → cancel/install) so two
		// concurrent `sync` calls can't interleave between read and install. The
		// long await on the waiter happens *outside* the permit. `completed` /
		// `failed` deliberately do NOT take the permit — they run inside the fold
		// (synchronously, from `runtime.runSync`) and operate on the atomic Ref.
		const gate = yield* Effect.makeSemaphore(1);

		const completed: Effect.Effect<string | null> = Effect.gen(function* () {
			const now = yield* Clock.currentTimeMillis;
			yield* Ref.set(lastCompletedAtMs, Option.some(now));
			const prev = yield* Ref.getAndSet(pending, Option.none());
			if (Option.isNone(prev)) return null;
			yield* Deferred.succeed(prev.value.done, true);
			return prev.value.fingerprint;
		});

		const failed: Effect.Effect<void> = Effect.gen(function* () {
			const prev = yield* Ref.getAndSet(pending, Option.none());
			if (Option.isSome(prev)) yield* Deferred.succeed(prev.value.done, false);
		});

		const profileDownloadGuard: Effect.Effect<void> = Effect.gen(function* () {
			const last = yield* Ref.get(lastCompletedAtMs);
			if (Option.isNone(last)) return;
			const now = yield* Clock.currentTimeMillis;
			const remaining = PROFILE_DOWNLOAD_GUARD_MS - (now - last.value);
			if (remaining > 0) yield* Effect.sleep(Duration.millis(remaining));
		});

		const sync = (req: ProfileSyncRequest): Effect.Effect<boolean> =>
			Effect.gen(function* () {
				if (req.alreadyLoaded) return true;

				// Decision section under the permit: pick the waiter to await,
				// installing a fresh upload (and superseding any in-flight one on a
				// different fingerprint) when needed.
				const waiter = yield* gate.withPermits(1)(
					Effect.gen(function* () {
						const current = yield* Ref.get(pending);
						// Same fingerprint already in flight → piggyback on its waiter.
						if (Option.isSome(current) && current.value.fingerprint === req.desired) {
							return current.value.done;
						}
						// A different fingerprint is in flight → supersede it. `cancel`
						// folds `ProfileUploadFailed`, which runs `failed` synchronously
						// (resolving the old waiter `false` + clearing pending) before it
						// returns, so we install onto a clear slot.
						if (Option.isSome(current)) yield* req.cancel;
						const done = yield* Deferred.make<boolean>();
						yield* Ref.set(pending, Option.some({ fingerprint: req.desired, done }));
						// Dispatch the upload while holding the permit so no other `sync`
						// can install between our `set pending` and the first writes.
						yield* req.upload;
						return done;
					})
				);

				// Await the outcome outside the permit, with the 15 s backstop.
				return yield* Deferred.await(waiter).pipe(
					Effect.timeoutFail({
						duration: UPLOAD_TIMEOUT,
						onTimeout: () => new ProfileUploadTimeoutError()
					}),
					Effect.catchTag('ProfileUploadTimeoutError', () =>
						Effect.gen(function* () {
							// Drop the pending entry only if it's still *ours* — a late
							// Completed/Failed may have already replaced/cleared it.
							yield* Ref.update(pending, (cur) =>
								Option.isSome(cur) && cur.value.done === waiter ? Option.none() : cur
							);
							req.log(
								'Profile sync timed out waiting for ProfileUploadCompleted — proceeding anyway.'
							);
							return false;
						})
					)
				);
			});

		return ProfileSync.of({ sync, completed, failed, profileDownloadGuard });
	})
);
