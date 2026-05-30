/**
 * `$lib/effect/runtime.test` — composition smoke test for the real AppLayer
 * (docs/53 T-09..T-11 wiring).
 *
 * Builds the actual app runtime (not a mock) and runs an effect through the
 * composed auth trio, proving the layer graph wires with no missing
 * dependencies and the services construct + run together on a ManagedRuntime.
 * localStorage is absent under node:test, so TokenVault seeds null — that's
 * fine; we're verifying composition, not persistence.
 *
 * Run: `cd web && node --experimental-strip-types --experimental-detect-module
 *   --test src/lib/effect/runtime.test.ts`
 */

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { Effect } from 'effect';
import { createAppRuntime } from './runtime.ts';
import { TokenVault } from '../services/token-vault.ts';

describe('AppLayer composition', () => {
	it('builds the real runtime and runs an effect through TokenVault (OAuth+HttpClient provided)', async () => {
		const runtime = createAppRuntime();
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
