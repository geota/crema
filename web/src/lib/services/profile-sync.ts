/**
 * `#lib/services/profile-sync.js` — the shot start's wait for the DE1 to hold
 * the profile it needs, as an Effect service (docs/53 §2.7 PR 6.1, T-21).
 *
 * The core owns the profile sync (`profile_sync.rs`, reaprime
 * `WorkflowDeviceSync`): the shell sets the desired profile
 * (`CremaCore.setDesiredProfile`) and the core uploads it — on every connect,
 * on a change, coalesced while an upload runs, retried with backoff, never
 * while the machine is busy. The shell no longer decides when to upload, keeps
 * no "DE1 holds X" cache, and persists nothing. This service only lets a
 * caller wait until the DE1 holds a given effective-profile fingerprint:
 *
 *  - `sync` resolves `true` at once when the core's status already says so,
 *    otherwise when the fold reports `ProfileSyncHolds` for that fingerprint
 *    (`holds`), `false` on a failed upload (`failed`) or after 15 s. Callers
 *    on the same fingerprint share one waiter.
 *
 * The firmware's profile-download guard (BC 9788201734) is the core's: it
 * holds a state request through an upload and the 500 ms after it (F16).
 * See `profile-sync.vitest.ts`.
 */

import { Context, Deferred, Duration, Effect, Layer, Ref } from 'effect';
import { ProfileUploadTimeoutError } from '../effect/errors.ts';

/**
 * The ack the shell synthesises for a profile write, or `null` for none.
 *
 * The DE1 answers a `HeaderWrite` / `FrameWrite` with only an ATT write
 * response, so the shell confirms each one to the core itself: the header as
 * `De1HeaderAck`, every frame as `De1FrameAck`. A write that did not reach
 * the DE1 (`delivered === false`) is never acknowledged, so the core fails
 * the upload instead of reporting a half-written profile as loaded (cohort
 * review 02 P0-2). Android's `De1BleManager.writeCharacteristic` applies the
 * same rule.
 */
export function profileWriteAck(
	target: string,
	delivered: boolean
): 'De1HeaderAck' | 'De1FrameAck' | null {
	if (!delivered) return null;
	if (target === 'De1ProfileHeader') return 'De1HeaderAck';
	if (target === 'De1ProfileFrame') return 'De1FrameAck';
	return null;
}

/** Backstop for a never-arriving outcome — comfortably beyond a healthy
 *  ~1-2 s upload. On timeout the caller proceeds with `false`; the core keeps
 *  retrying on its own. */
const UPLOAD_TIMEOUT = Duration.seconds(15);

/** What `sync` needs from the app. */
export interface ProfileSyncRequest {
	/** The effective-profile fingerprint the caller needs on the DE1. */
	readonly desired: string;
	/** The core's status already says the DE1 holds `desired`. */
	readonly alreadyLoaded: boolean;
	/** Append a line to the shared event log (the timeout path uses it). */
	readonly log: (line: string) => void;
}

export class ProfileSync extends Context.Service<
	ProfileSync,
	{
		/**
		 * Wait until the DE1 holds `desired`. `true` when it already does or
		 * when the core reports it landed; `false` on a failed upload or the
		 * 15 s timeout. Never fails.
		 */
		readonly sync: (req: ProfileSyncRequest) => Effect.Effect<boolean>;
		/**
		 * Called by the `ProfileSyncHolds` fold: the DE1 now holds
		 * `fingerprint`. Resolves its waiters `true`. Fully synchronous.
		 */
		readonly holds: (fingerprint: string) => Effect.Effect<void>;
		/**
		 * Called by the `ProfileUploadFailed` fold: resolves every waiter
		 * `false` (the start fails; the core retries on its own). Fully
		 * synchronous.
		 */
		readonly failed: Effect.Effect<void>;
	}
>()('crema/ProfileSync') {}

export const ProfileSyncLive = Layer.effect(
	ProfileSync,
	Effect.gen(function* () {
		const waiters = yield* Ref.make<ReadonlyMap<string, Deferred.Deferred<boolean>>>(new Map());

		const holds = (fingerprint: string): Effect.Effect<void> =>
			Effect.gen(function* () {
				const map = yield* Ref.get(waiters);
				const done = map.get(fingerprint);
				if (done === undefined) return;
				const next = new Map(map);
				next.delete(fingerprint);
				yield* Ref.set(waiters, next);
				yield* Deferred.succeed(done, true);
			});

		const failed: Effect.Effect<void> = Effect.gen(function* () {
			const map = yield* Ref.getAndSet(waiters, new Map());
			for (const done of map.values()) yield* Deferred.succeed(done, false);
		});

		const sync = (req: ProfileSyncRequest): Effect.Effect<boolean> =>
			Effect.gen(function* () {
				if (req.alreadyLoaded) return true;
				// One waiter per fingerprint: a second caller shares it.
				const map = yield* Ref.get(waiters);
				let waiter = map.get(req.desired);
				if (waiter === undefined) {
					waiter = yield* Deferred.make<boolean>();
					yield* Ref.set(waiters, new Map(map).set(req.desired, waiter));
				}
				const mine = waiter;
				return yield* Deferred.await(mine).pipe(
					Effect.timeoutOrElse({
						duration: UPLOAD_TIMEOUT,
						orElse: () => Effect.fail(new ProfileUploadTimeoutError())
					}),
					Effect.catchTag('ProfileUploadTimeoutError', () =>
						Effect.gen(function* () {
							yield* Ref.update(waiters, (now) => {
								if (now.get(req.desired) !== mine) return now;
								const next = new Map(now);
								next.delete(req.desired);
								return next;
							});
							req.log('The DE1 did not confirm the profile within 15 s — the core keeps retrying.');
							return false;
						})
					)
				);
			});

		return ProfileSync.of({ sync, holds, failed });
	})
);
