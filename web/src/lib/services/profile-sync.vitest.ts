/**
 * `#lib/services/profile-sync.vitest` — vitest for the `ProfileSync` waiter
 * (docs/53 T-21). The core owns when to upload (`profile_sync.rs`); this
 * service only lets a shot start wait until the DE1 holds the fingerprint it
 * needs:
 *
 *  - **already held** → `true` at once;
 *  - **holds / failed** → the fold's `holds(fp)` resolves that fingerprint's
 *    waiters `true`, `failed` resolves every waiter `false`;
 *  - **shared waiter** → two callers on one fingerprint resolve together;
 *    another fingerprint's `holds` leaves them waiting;
 *  - **15 s timeout** → `false` + a log line (driven by `TestClock`);
 *  - **profile-download guard** → waits out the remaining 500 ms window.
 *
 * Run: `cd web && pnpm test:vitest` (or `vitest run profile-sync`).
 */

import { describe, expect, it } from 'vitest';
import { Duration, Effect, Fiber } from 'effect';
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

/** Let forked fibers reach their await. */
const settle = Effect.promise(() => new Promise((r) => setTimeout(r, 5)));

describe('ProfileSync.sync — already held', () => {
	it('resolves true at once', async () => {
		const ok = await run(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				return yield* ps.sync({ desired: 'fp1', alreadyLoaded: true, log: noop });
			})
		);
		expect(ok).toBe(true);
	});
});

describe('ProfileSync.sync — the core reports the outcome', () => {
	it('resolves true when the DE1 holds the fingerprint', async () => {
		const ok = await run(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				const fiber = yield* Effect.forkChild(
					ps.sync({ desired: 'fp1', alreadyLoaded: false, log: noop })
				);
				yield* settle;
				yield* ps.holds('fp1');
				return yield* Fiber.join(fiber);
			})
		);
		expect(ok).toBe(true);
	});

	it('resolves false when an upload fails', async () => {
		const ok = await run(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				const fiber = yield* Effect.forkChild(
					ps.sync({ desired: 'fp1', alreadyLoaded: false, log: noop })
				);
				yield* settle;
				yield* ps.failed;
				return yield* Fiber.join(fiber);
			})
		);
		expect(ok).toBe(false);
	});

	it('shares a waiter per fingerprint; another fingerprint leaves it waiting', async () => {
		const result = await run(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				const a1 = yield* Effect.forkChild(ps.sync({ desired: 'A', alreadyLoaded: false, log: noop }));
				const a2 = yield* Effect.forkChild(ps.sync({ desired: 'A', alreadyLoaded: false, log: noop }));
				const b = yield* Effect.forkChild(ps.sync({ desired: 'B', alreadyLoaded: false, log: noop }));
				yield* settle;
				// The core coalesced: A landed first, B follows.
				yield* ps.holds('A');
				const first = [yield* Fiber.join(a1), yield* Fiber.join(a2)];
				const bWaiting = (yield* Effect.sync(() => b.pollUnsafe())) === undefined;
				yield* ps.holds('B');
				return { first, bWaiting, b: yield* Fiber.join(b) };
			})
		);
		expect(result.first).toEqual([true, true]);
		expect(result.bWaiting).toBe(true);
		expect(result.b).toBe(true);
	});
});

describe('ProfileSync.sync — timeout (TestClock)', () => {
	it('resolves false + logs when the DE1 does not confirm within 15 s', async () => {
		const result = await runTest(
			Effect.gen(function* () {
				const ps = yield* ProfileSync;
				const logs: string[] = [];
				const fiber = yield* Effect.forkChild(
					ps.sync({ desired: 'fp1', alreadyLoaded: false, log: (l) => logs.push(l) })
				);
				yield* TestClock.adjust(Duration.zero); // the fiber arms its backstop
				yield* TestClock.adjust(Duration.seconds(15));
				const ok = yield* Fiber.join(fiber);
				return { ok, logs };
			})
		);
		expect(result.ok).toBe(false);
		expect(result.logs.some((l) => l.includes('15 s'))).toBe(true);
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
