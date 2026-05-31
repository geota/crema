/**
 * `$lib/effect/runtime.test` — composition smoke test for the node-resolvable
 * slice of the app runtime (docs/53 T-09..T-12 wiring).
 *
 * Builds the auth trio (TokenVault <- OAuth <- HttpClient) on a real
 * `ManagedRuntime` and runs an effect through it, proving that subgraph wires
 * with no missing dependencies and the services construct + run together.
 * localStorage is absent under node:test, so TokenVault seeds null — that's
 * fine; we're verifying composition, not persistence.
 *
 * **Why not the real `AppLayer` / `createAppRuntime()`**: as of T-12, `AppLayer`
 * also composes `ShotSyncLive`, which is store-coupled (`$lib/bean`,
 * `$lib/history`, `$lib/settings`, Svelte runes) and therefore not loadable
 * under node's resolver — importing `runtime.ts` here would fail with
 * `ERR_MODULE_NOT_FOUND ('$lib')`. The full `AppLayer` is only ever
 * instantiated in the browser (D-03), and its composition completeness is now a
 * compile-time guarantee: `createAppRuntime(): ManagedRuntime<AppServices,
 * never>` only typechecks if every service's requirements are provided, so an
 * unprovided dep fails `pnpm check`. This test covers what can be verified
 * headlessly — the trio's runtime construction. It mirrors the same provide
 * wiring `runtime.ts` uses.
 *
 * Run: `cd web && node --experimental-strip-types --experimental-detect-module
 *   --test src/lib/effect/runtime.test.ts`
 */

import assert from 'node:assert/strict';
import { describe, it } from 'vitest';
import { Effect, Layer, ManagedRuntime } from 'effect';
import { HttpClientLive } from '../services/http-client.ts';
import { OAuthLive } from '../services/oauth.ts';
import { TokenVaultLive } from '../services/token-vault.ts';
import { TokenVault } from '../services/token-vault.ts';

describe('AppLayer composition (auth trio)', () => {
	it('builds the runtime and runs an effect through TokenVault (OAuth+HttpClient provided)', async () => {
		// Same provide structure as runtime.ts: OAuth consumes HttpClient;
		// TokenVault consumes OAuth.
		const OAuthProvided = Layer.provide(OAuthLive, HttpClientLive);
		const TokenVaultProvided = Layer.provide(TokenVaultLive, OAuthProvided);
		const runtime = ManagedRuntime.make(TokenVaultProvided);
		try {
			const tokens = await runtime.runPromise(Effect.flatMap(TokenVault, (v) => v.getTokens));
			// No persisted tokens under node:test -> null. The point is that the
			// composed graph (TokenVault <- OAuth <- HttpClient) built and ran.
			assert.equal(tokens, null);
		} finally {
			await runtime.dispose();
		}
	});
});
