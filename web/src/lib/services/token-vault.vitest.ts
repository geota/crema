/**
 * `$lib/services/token-vault.test` — node:test for the TokenVault refresh /
 * 401-retry semantics (docs/53 T-11).
 *
 * OAuth is replaced with a controllable mock so we can drive refresh outcomes
 * without a network or browser. localStorage is absent under node:test, so the
 * SubscriptionRef (seeded null) is the source of truth — we seed via
 * storeTokens. The pinned behaviors: proactive refresh on near-expiry, one-shot
 * forced refresh + retry on 401, and the no-token / refresh-failure error map.
 *
 * Run: `cd web && node --experimental-strip-types --experimental-detect-module
 *   --test src/lib/services/token-vault.test.ts`
 */

import assert from 'node:assert/strict';
import { describe, it } from 'vitest';
import { Cause, Effect, Exit, Layer } from 'effect';
import { HttpStatusError } from '../effect/errors.ts';
import { OAuth } from './oauth.ts';
import { TokenVault, TokenVaultLive } from './token-vault.ts';
import type { TokenSet } from '../visualizer/oauth.ts';

const tokenAt = (expiresAt: number, accessToken = 'AT', refreshToken: string | null = 'RT'): TokenSet => ({
	accessToken,
	refreshToken,
	expiresAt,
	scope: 'read',
	tokenType: 'Bearer'
});

/** An OAuth layer whose refreshToken returns `next` (or fails if null). */
function mockOAuth(next: TokenSet | null) {
	return Layer.succeed(
		OAuth,
		OAuth.of({
			startLogin: () => Effect.void,
			exchangeCode: () => Effect.die('n/a'),
			refreshToken: () =>
				next ? Effect.succeed(next) : Effect.die('refresh exploded'),
			revokeToken: () => Effect.void
		})
	);
}

const provide = (next: TokenSet | null) =>
	Layer.provide(TokenVaultLive, mockOAuth(next));

const failTag = (exit: Exit.Exit<unknown, { readonly _tag?: string }>) =>
	Exit.isFailure(exit)
		? (() => {
				const f = Cause.failureOption(exit.cause);
				return f._tag === 'Some' ? f.value?._tag : undefined;
			})()
		: undefined;

// Relative to the real Date.now() the service reads (EXPIRY_SAFETY_MS is 5 min).
const FAR = Date.now() + 60 * 60 * 1000; // ~1h out -> fresh
const SOON = Date.now() + 60 * 1000; // ~1min out -> expiring

describe('TokenVault.withFreshToken', () => {
	it('fails NotAuthenticatedError when no tokens are stored', async () => {
		const exit = await Effect.runPromiseExit(
			TokenVault.pipe(
				Effect.flatMap((v) => v.withFreshToken((t) => Effect.succeed(t))),
				Effect.provide(provide(null))
			)
		);
		assert.equal(failTag(exit), 'NotAuthenticatedError');
	});

	it('passes a fresh token straight through (no refresh)', async () => {
		const exit = await Effect.runPromiseExit(
			TokenVault.pipe(
				Effect.flatMap((v) =>
					v.storeTokens(tokenAt(FAR, 'fresh')).pipe(
						Effect.zipRight(v.withFreshToken((t) => Effect.succeed(t)))
					)
				),
				Effect.provide(provide(tokenAt(FAR, 'REFRESHED')))
			)
		);
		assert.ok(Exit.isSuccess(exit));
		assert.equal(exit.value, 'fresh'); // not the refreshed token
	});

	it('proactively refreshes a near-expiry token before calling req', async () => {
		const exit = await Effect.runPromiseExit(
			TokenVault.pipe(
				Effect.flatMap((v) =>
					v.storeTokens(tokenAt(SOON, 'stale')).pipe(
						Effect.zipRight(v.withFreshToken((t) => Effect.succeed(t)))
					)
				),
				Effect.provide(provide(tokenAt(FAR, 'REFRESHED')))
			)
		);
		assert.ok(Exit.isSuccess(exit));
		assert.equal(exit.value, 'REFRESHED');
	});

	it('forces a refresh and retries once on a 401', async () => {
		let calls = 0;
		const exit = await Effect.runPromiseExit(
			TokenVault.pipe(
				Effect.flatMap((v) =>
					v.storeTokens(tokenAt(FAR, 'stale')).pipe(
						Effect.zipRight(
							v.withFreshToken((token) => {
								calls += 1;
								return token === 'REFRESHED'
									? Effect.succeed(token)
									: Effect.fail(new HttpStatusError({ status: 401, url: 'u' }));
							})
						)
					)
				),
				Effect.provide(provide(tokenAt(FAR, 'REFRESHED')))
			)
		);
		assert.ok(Exit.isSuccess(exit));
		assert.equal(exit.value, 'REFRESHED');
		assert.equal(calls, 2); // first 401, retry succeeds
	});

	it('clears + fails NotAuthenticatedError on 401 with no refresh token', async () => {
		const exit = await Effect.runPromiseExit(
			TokenVault.pipe(
				Effect.flatMap((v) =>
					v.storeTokens(tokenAt(FAR, 'stale', null)).pipe(
						Effect.zipRight(
							v.withFreshToken(() => Effect.fail(new HttpStatusError({ status: 401, url: 'u' })))
						)
					)
				),
				Effect.provide(provide(null))
			)
		);
		assert.equal(failTag(exit), 'NotAuthenticatedError');
	});

	it('propagates a non-401 HttpStatusError unchanged', async () => {
		const exit = await Effect.runPromiseExit(
			TokenVault.pipe(
				Effect.flatMap((v) =>
					v.storeTokens(tokenAt(FAR, 'stale')).pipe(
						Effect.zipRight(
							v.withFreshToken(() => Effect.fail(new HttpStatusError({ status: 500, url: 'u' })))
						)
					)
				),
				Effect.provide(provide(tokenAt(FAR)))
			)
		);
		assert.equal(failTag(exit), 'HttpStatusError');
	});
});
