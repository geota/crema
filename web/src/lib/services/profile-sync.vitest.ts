/**
 * `#lib/services/profile-sync.vitest` — vitest for the `ProfileSync`
 * coordination service (docs/53 T-21, risk R-03).
 *
 * This is the behavior that was previously buried in `state/app.svelte.ts`'s
 * `syncActiveProfile` + the two event-fold arms, driven by `window.setTimeout`
 * and three mutable fields — unreachable by any automated test. Lifting it into
 * a service (with the upload/cancel/log injected) makes the four subtle
 * behaviors R-03 calls out deterministically testable:
 *
 *  - **cache hit** → short-circuit `true`, no upload;
 *  - **success / failure** → the fold's `completed` / `failed` resolve the
 *    waiter (and `completed` hands back the fingerprint to persist);
 *  - **piggyback** → a second caller on the same fingerprint shares one upload;
 *  - **supersession** → a different fingerprint cancels the in-flight one (old
 *    waiter resolves `false`), the newer wins;
 *  - **15 s timeout** → resolves `false` + logs (driven by `TestClock`);
 *  - **profile-download guard** → waits out the remaining 500 ms window.
 *
 * The time-free behaviors run on the live runtime with a tiny real `sleep` to
 * let forked fibers settle; the time-based ones use Effect's `TestClock` (no
 * `@effect/vitest` — `TestClock.layer()` under plain vitest). Run:
 * `cd web && pnpm test:vitest` (or `vitest run profile-sync`).
 */

import { describe, expect, it } from 'vitest';
import { Deferred, Duration, Effect, Fiber } from 'effect';
import { TestClock } from 'effect/testing';
import { ProfileSync, ProfileSyncLive, profileWriteAck } from './profile-sync.ts';

const noop = () => {};

/** Run a program against a fresh live `ProfileSync` (real clock). */
function run<A>(program: Effect.Effect<A, never, ProfileSync>): Promise<A> {
	return Effect.runPromise(Effect.provide(program, ProfileSyncLive));
}

/** Run a program against a fresh `ProfileSync` with Effect's `TestClock`. */
function runTest<A>(program: Effect.Effect<A, never, ProfileSync>): Promise<A> {
	return Effect.runPromise(
		Effect.provide(Effect.provide(program, ProfileSyncLive), TestClock.layer())
	);
}

/** Build an `upload` thunk that records the call + signals `started`. */
const signalUpload = (started: Deferred.Deferred<void>) =>
	Deferred.succeed(started, undefined).pipe(Effect.asVoid);

describe('ProfileSync.sync — cache hit', () => {
	it('short-circuits to true without uploading', async () => {
		const result = await run(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				let uploaded = false;
				const ok = yield* ps.sync({
					desired: 'fp1',
					alreadyLoaded: true,
					upload: Effect.sync(() => {
						uploaded = true;
					}),
					cancel: Effect.void,
					log: noop
				});
				return { ok, uploaded };
			})
		);
		expect(result.ok).toBe(true);
		expect(result.uploaded).toBe(false);
	});
});

describe('ProfileSync.sync — completion', () => {
	it('resolves true and returns the fingerprint when the upload completes', async () => {
		const result = await run(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				const started = yield* Deferred.make<void>();
				const fiber = yield* Effect.forkChild(
					ps.sync({
						desired: 'fp1',
						alreadyLoaded: false,
						upload: signalUpload(started),
						cancel: Effect.void,
						log: noop
					})
				);
				yield* Deferred.await(started); // pending installed; fiber now awaiting
				const committed = yield* ps.completed; // the fold's ProfileUploadCompleted arm
				const ok = yield* Fiber.join(fiber);
				return { committed, ok };
			})
		);
		expect(result.committed).toBe('fp1');
		expect(result.ok).toBe(true);
	});

	it('resolves false and commits nothing when the upload fails', async () => {
		const result = await run(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				const started = yield* Deferred.make<void>();
				const fiber = yield* Effect.forkChild(
					ps.sync({
						desired: 'fp1',
						alreadyLoaded: false,
						upload: signalUpload(started),
						cancel: Effect.void,
						log: noop
					})
				);
				yield* Deferred.await(started);
				yield* ps.failed; // the fold's ProfileUploadFailed arm
				const ok = yield* Fiber.join(fiber);
				// A late completion now finds nothing pending → commits nothing.
				const lateCommit = yield* ps.completed;
				return { ok, lateCommit };
			})
		);
		expect(result.ok).toBe(false);
		expect(result.lateCommit).toBe(null);
	});
});

describe('ProfileSync.sync — piggyback', () => {
	it('shares one upload between two callers on the same fingerprint', async () => {
		const result = await run(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				let uploadCount = 0;
				const started = yield* Deferred.make<void>();
				const mkUpload = () =>
					Effect.sync(() => {
						uploadCount += 1;
					}).pipe(Effect.andThen(Deferred.succeed(started, undefined)), Effect.asVoid);
				const f1 = yield* Effect.forkChild(
					ps.sync({ desired: 'fp1', alreadyLoaded: false, upload: mkUpload(), cancel: Effect.void, log: noop })
				);
				yield* Deferred.await(started);
				const f2 = yield* Effect.forkChild(
					ps.sync({ desired: 'fp1', alreadyLoaded: false, upload: mkUpload(), cancel: Effect.void, log: noop })
				);
				yield* Effect.sleep(Duration.millis(5)); // let f1 release the permit + f2 piggyback
				const committed = yield* ps.completed;
				const r1 = yield* Fiber.join(f1);
				const r2 = yield* Fiber.join(f2);
				return { uploadCount, committed, r1, r2 };
			})
		);
		expect(result.uploadCount).toBe(1); // only one upload despite two callers
		expect(result.committed).toBe('fp1');
		expect(result.r1).toBe(true);
		expect(result.r2).toBe(true);
	});
});

describe('ProfileSync.sync — supersession', () => {
	it('cancels an in-flight upload of a different fingerprint; the newer wins', async () => {
		const result = await run(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				const started1 = yield* Deferred.make<void>();
				const started2 = yield* Deferred.make<void>();
				const f1 = yield* Effect.forkChild(
					ps.sync({
						desired: 'fp1',
						alreadyLoaded: false,
						upload: signalUpload(started1),
						cancel: Effect.void,
						log: noop
					})
				);
				yield* Deferred.await(started1);
				const f2 = yield* Effect.forkChild(
					ps.sync({
						desired: 'fp2',
						alreadyLoaded: false,
						upload: signalUpload(started2),
						// `cancel` models the orchestrator reacting to the core's
						// ProfileUploadFailed{Aborted} — the fold runs `failed`.
						cancel: ps.failed,
						log: noop
					})
				);
				yield* Deferred.await(started2); // fp2 installed; fp1 already cancelled
				const committed = yield* ps.completed;
				const r1 = yield* Fiber.join(f1);
				const r2 = yield* Fiber.join(f2);
				return { committed, r1, r2 };
			})
		);
		expect(result.r1).toBe(false); // superseded
		expect(result.r2).toBe(true); // newer won
		expect(result.committed).toBe('fp2');
	});
});

describe('ProfileSync.sync — background syncs never displace an upload (cohort review 2026-10-28, #2 N2)', () => {
	it('an automatic re-upload waits on the user start in flight instead of cancelling it', async () => {
		const result = await run(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				const startedUser = yield* Deferred.make<void>();
				let cancelled = false;
				let autoUploaded = false;
				const user = yield* Effect.forkChild(
					ps.sync({
						desired: 'fp-with-qc',
						alreadyLoaded: false,
						upload: signalUpload(startedUser),
						cancel: Effect.void,
						log: noop
					})
				);
				yield* Deferred.await(startedUser);
				const auto = yield* Effect.forkChild(
					ps.sync({
						desired: 'fp-no-qc',
						alreadyLoaded: false,
						upload: Effect.sync(() => {
							autoUploaded = true;
						}),
						cancel: Effect.sync(() => {
							cancelled = true;
						}),
						log: noop,
						background: true
					})
				);
				yield* Effect.sleep(Duration.millis(5));
				const committed = yield* ps.completed;
				return {
					committed,
					user: yield* Fiber.join(user),
					auto: yield* Fiber.join(auto),
					cancelled,
					autoUploaded
				};
			})
		);
		expect(result.cancelled).toBe(false);
		expect(result.autoUploaded).toBe(false);
		expect(result.user).toBe(true);
		expect(result.auto).toBe(true);
		expect(result.committed).toBe('fp-with-qc');
	});

	it('a user start still supersedes an automatic upload', async () => {
		const result = await run(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				const startedAuto = yield* Deferred.make<void>();
				const startedUser = yield* Deferred.make<void>();
				const auto = yield* Effect.forkChild(
					ps.sync({
						desired: 'fp-no-qc',
						alreadyLoaded: false,
						upload: signalUpload(startedAuto),
						cancel: Effect.void,
						log: noop,
						background: true
					})
				);
				yield* Deferred.await(startedAuto);
				const user = yield* Effect.forkChild(
					ps.sync({
						desired: 'fp-with-qc',
						alreadyLoaded: false,
						upload: signalUpload(startedUser),
						cancel: ps.failed,
						log: noop
					})
				);
				yield* Deferred.await(startedUser);
				const committed = yield* ps.completed;
				return { committed, auto: yield* Fiber.join(auto), user: yield* Fiber.join(user) };
			})
		);
		expect(result.auto).toBe(false);
		expect(result.user).toBe(true);
		expect(result.committed).toBe('fp-with-qc');
	});
});

describe('ProfileSync.sync — timeout (TestClock)', () => {
	it('cancels the core upload when the backstop fires (cohort review 2026-10-28, #2 N1)', async () => {
		const result = await runTest(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				const started = yield* Deferred.make<void>();
				let cancels = 0;
				const fiber = yield* Effect.forkChild(
					ps.sync({
						desired: 'fp1',
						alreadyLoaded: false,
						upload: signalUpload(started),
						// The core's ProfileUploadFailed{Aborted} fold runs `failed`.
						cancel: Effect.sync(() => {
							cancels += 1;
						}).pipe(Effect.andThen(ps.failed)),
						log: noop
					})
				);
				yield* Deferred.await(started);
				yield* TestClock.adjust(Duration.seconds(15));
				const ok = yield* Fiber.join(fiber);
				return { ok, cancels };
			})
		);
		expect(result.ok).toBe(false);
		expect(result.cancels).toBe(1);
	});

	it('resolves false + logs when no completion arrives within 15s', async () => {
		const result = await runTest(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				const logs: string[] = [];
				const started = yield* Deferred.make<void>();
				const fiber = yield* Effect.forkChild(
					ps.sync({
						desired: 'fp1',
						alreadyLoaded: false,
						upload: signalUpload(started),
						cancel: Effect.void,
						log: (l) => logs.push(l)
					})
				);
				yield* Deferred.await(started); // fiber armed its 15s backstop
				yield* TestClock.adjust(Duration.seconds(15));
				const ok = yield* Fiber.join(fiber);
				// Timeout cleared the pending entry → a late completion commits nothing.
				const lateCommit = yield* ps.completed;
				return { ok, logs, lateCommit };
			})
		);
		expect(result.ok).toBe(false);
		expect(result.logs.some((l) => l.includes('timed out'))).toBe(true);
		expect(result.lateCommit).toBe(null);
	});
});

describe('ProfileSync.profileDownloadGuard (TestClock)', () => {
	it('is a no-op before any completion', async () => {
		// Resolves immediately (no sleep) — would hang under TestClock if it slept.
		await runTest(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				yield* ps.profileDownloadGuard;
			})
		);
		expect(true).toBe(true);
	});

	it('waits out the remaining 500ms window since the last completion', async () => {
		const result = await runTest(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				yield* ps.completed; // stamps lastCompletedAt at test-time 0
				const fiber = yield* Effect.forkChild(ps.profileDownloadGuard);
				yield* TestClock.adjust(Duration.zero); // let the guard arm its 500ms sleep
				const waitingAtStart = (yield* Effect.sync(() => fiber.pollUnsafe())) === undefined;
				yield* TestClock.adjust(Duration.millis(499));
				const waitingAt499 = (yield* Effect.sync(() => fiber.pollUnsafe())) === undefined;
				yield* TestClock.adjust(Duration.millis(1)); // crosses 500ms → guard completes
				yield* Fiber.join(fiber);
				return { waitingAtStart, waitingAt499 };
			})
		);
		expect(result.waitingAtStart).toBe(true);
		expect(result.waitingAt499).toBe(true);
	});
});

describe('profileWriteAck (cohort review 02 P0-2)', () => {
	it('acks a delivered header as De1HeaderAck and a delivered frame as De1FrameAck', () => {
		expect(profileWriteAck('De1ProfileHeader', true)).toBe('De1HeaderAck');
		expect(profileWriteAck('De1ProfileFrame', true)).toBe('De1FrameAck');
	});

	it('never acks a write that did not reach the DE1', () => {
		expect(profileWriteAck('De1ProfileHeader', false)).toBeNull();
		expect(profileWriteAck('De1ProfileFrame', false)).toBeNull();
	});

	it('acks nothing for other targets', () => {
		expect(profileWriteAck('De1RequestedState', true)).toBeNull();
		expect(profileWriteAck('De1MmrWrite', true)).toBeNull();
	});
});
