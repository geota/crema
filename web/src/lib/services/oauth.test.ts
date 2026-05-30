/**
 * `$lib/services/oauth.test` — node:test for the OAuth service's typed-error
 * contracts (docs/53 T-10).
 *
 * Scope note: under node:test `import.meta.env` is undefined, so the env-read
 * paths (startLogin's `isConfigured()`) can't be exercised here — they're
 * Vite-only and covered by the build + Phase 3's manual smoke gate. We test the
 * `refreshToken -> TokenRefreshFailedError` contract that TokenVault (T-11)
 * depends on, which maps the failure inside the promise.
 *
 * Run: `cd web && node --experimental-strip-types --experimental-detect-module
 *   --test src/lib/services/oauth.test.ts`
 */

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { Cause, Effect, Exit } from 'effect';
import { OAuth, OAuthLive } from './oauth.ts';

function failTag(exit: Exit.Exit<unknown, { readonly _tag?: string }>): string | undefined {
	if (!Exit.isFailure(exit)) return undefined;
	const f = Cause.failureOption(exit.cause);
	return f._tag === 'Some' ? f.value?._tag : undefined;
}

describe('OAuth service', () => {
	it('refreshToken maps a failure to TokenRefreshFailedError', async () => {
		const exit = await Effect.runPromiseExit(
			OAuth.pipe(
				Effect.flatMap((o) => o.refreshToken('some-refresh-token')),
				Effect.provide(OAuthLive)
			)
		);
		assert.equal(failTag(exit), 'TokenRefreshFailedError');
	});
});
