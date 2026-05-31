/**
 * `$lib/services/token-vault-coherence.test` — node:test for the T-16
 * localStorage-coherence fix in `TokenVault.getTokens`.
 *
 * `token-vault.test.ts` covers the refresh / 401 semantics with localStorage
 * ABSENT (the `SubscriptionRef` is the source of truth there). This file covers
 * the complementary browser path that T-16 depends on: under option A the old
 * `token-store.ts` still owns sign-in / sign-out + the bean-pull auth, sharing
 * the `crema.visualizer.tokens.v1` key, so `getTokens` MUST read localStorage
 * fresh — a sign-in routed through token-store has to be visible to shot auth
 * with no stale in-memory cache. These lock that in with a localStorage
 * polyfill (process-isolated, so the ref-based suite is unaffected).
 *
 * Run: `cd web && node --experimental-strip-types --experimental-detect-module
 *   --test src/lib/services/token-vault-coherence.test.ts`
 */

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { Effect, Layer } from 'effect';
import type { TokenSet } from '../visualizer/oauth.ts';

class MemStorage {
	private map = new Map<string, string>();
	getItem(k: string): string | null {
		return this.map.has(k) ? (this.map.get(k) as string) : null;
	}
	setItem(k: string, v: string): void {
		this.map.set(k, String(v));
	}
	removeItem(k: string): void {
		this.map.delete(k);
	}
	clear(): void {
		this.map.clear();
	}
}
// Node's `localStorage` is a getter that returns undefined without
// `--localstorage-file`; `defineProperty` swaps in a real data property.
Object.defineProperty(globalThis, 'localStorage', {
	value: new MemStorage(),
	configurable: true,
	writable: true
});

const { TokenVault, TokenVaultLive } = await import('./token-vault.ts');
const { OAuth } = await import('./oauth.ts');

const TOKENS_KEY = 'crema.visualizer.tokens.v1';
const FAR = Date.now() + 60 * 60 * 1000; // ~1h out → fresh

const tokenAt = (accessToken: string): TokenSet => ({
	accessToken,
	refreshToken: 'RT',
	expiresAt: FAR,
	scope: 'read',
	tokenType: 'Bearer'
});

/** An OAuth stub — these tests never trigger a refresh. */
const oauthStub = Layer.succeed(
	OAuth,
	OAuth.of({
		startLogin: () => Effect.void,
		exchangeCode: () => Effect.die('n/a'),
		refreshToken: () => Effect.die('n/a'),
		revokeToken: () => Effect.void
	})
);
const layer = Layer.provide(TokenVaultLive, oauthStub);

const reset = () => (globalThis as { localStorage: Storage }).localStorage.clear();
const writeLs = (t: TokenSet) =>
	(globalThis as { localStorage: Storage }).localStorage.setItem(TOKENS_KEY, JSON.stringify(t));

describe('TokenVault.getTokens — localStorage coherence (T-16)', () => {
	it('reads a token written directly to localStorage (external sign-in visible)', async () => {
		reset();
		// Simulate token-store persisting a sign-in to the shared key.
		writeLs(tokenAt('EXTERNAL'));
		const tokens = await Effect.runPromise(
			TokenVault.pipe(
				Effect.flatMap((v) => v.getTokens),
				Effect.provide(layer)
			)
		);
		assert.equal(tokens?.accessToken, 'EXTERNAL');
	});

	it('returns null after the token is removed from localStorage externally', async () => {
		reset();
		writeLs(tokenAt('GONE_SOON'));
		const got = await Effect.runPromise(
			TokenVault.pipe(
				Effect.flatMap((v) =>
					// Read once (warms the ref), then clear LS externally and read again.
					v.getTokens.pipe(
						Effect.zipRight(Effect.sync(reset)),
						Effect.zipRight(v.getTokens)
					)
				),
				Effect.provide(layer)
			)
		);
		assert.equal(got, null);
	});

	it('prefers the fresh localStorage value over a stale in-memory ref', async () => {
		reset();
		const got = await Effect.runPromise(
			TokenVault.pipe(
				Effect.flatMap((v) =>
					// storeTokens seeds the ref + LS with A, then an external writer
					// (token-store) rotates LS to B. getTokens must return B, not the
					// ref's stale A — this is the whole point of the option-A fix.
					v.storeTokens(tokenAt('A')).pipe(
						Effect.zipRight(Effect.sync(() => writeLs(tokenAt('B')))),
						Effect.zipRight(v.getTokens)
					)
				),
				Effect.provide(layer)
			)
		);
		assert.equal(got?.accessToken, 'B');
	});

	it('falls back to null when localStorage holds a malformed token blob', async () => {
		reset();
		(globalThis as { localStorage: Storage }).localStorage.setItem(TOKENS_KEY, '{"nope":true}');
		const got = await Effect.runPromise(
			TokenVault.pipe(
				Effect.flatMap((v) => v.getTokens),
				Effect.provide(layer)
			)
		);
		assert.equal(got, null);
	});
});
