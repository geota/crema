/**
 * TokenVault at rest: `storeTokens` writes the access + refresh tokens
 * WRAPPED by the secret box, `loadTokens` unwraps them, and a set whose key
 * is gone reads as signed out and switches `hasStoredVisualizerTokens` off
 * (so the auto-push skips instead of failing auth on every shot).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Effect, Layer } from 'effect';

// jsdom has no IndexedDB, so the real box would store plaintext. Use a box
// over an in-memory key the test can swap ("lose").
let key: Promise<CryptoKey | null> = Promise.resolve(null);
const newKey = () => crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
vi.mock('$lib/security/secret-box', async (importOriginal) => {
	const real = await importOriginal<typeof import('$lib/security/secret-box')>();
	// No caching wrapper: every call sees the current `key`, like a new session would.
	const box = {
		wrap: (p: string) => real.createSecretBox({ getKey: () => key }).wrap(p),
		unwrap: (s: string) => real.createSecretBox({ getKey: () => key }).unwrap(s)
	};
	return { ...real, secretBox: box };
});

const { OAuth } = await import('./oauth.ts');
const { TokenVault, TokenVaultLive, hasStoredVisualizerTokens, loadTokens } = await import('./token-vault.ts');
const { isWrapped } = await import('$lib/security/secret-box');

const TOKENS_KEY = 'crema.visualizer.tokens.v1';
const tokens = {
	accessToken: 'AT-secret',
	refreshToken: 'RT-secret',
	expiresAt: Date.now() + 3_600_000,
	scope: 'read',
	tokenType: 'Bearer'
};
const oauth = Layer.succeed(
	OAuth,
	OAuth.of({
		startLogin: () => Effect.void,
		exchangeCode: () => Effect.die('n/a'),
		refreshToken: () => Effect.die('n/a'),
		revokeToken: () => Effect.void
	})
);
const run = <A>(f: (v: Effect.Effect.Success<typeof TokenVault>) => Effect.Effect<A>) =>
	Effect.runPromise(Effect.flatMap(TokenVault, f).pipe(Effect.provide(Layer.provide(TokenVaultLive, oauth))));

beforeEach(async () => {
	localStorage.clear();
	key = newKey();
	await loadTokens(); // reset the unreadable flag
});

describe('TokenVault wrapping', () => {
	it('writes wrapped tokens and reads them back', async () => {
		await run((v) => v.storeTokens(tokens));
		const raw = JSON.parse(localStorage.getItem(TOKENS_KEY) ?? '{}');
		expect(isWrapped(raw.accessToken)).toBe(true);
		expect(isWrapped(raw.refreshToken)).toBe(true);
		expect(JSON.stringify(raw)).not.toContain('secret');
		expect(await loadTokens()).toEqual(tokens);
		expect(await run((v) => v.getTokens)).toEqual(tokens);
		expect(hasStoredVisualizerTokens()).toBe(true);
	});

	it('reads a pre-wrapping plaintext set as-is', async () => {
		localStorage.setItem(TOKENS_KEY, JSON.stringify(tokens));
		expect(await loadTokens()).toEqual(tokens);
	});

	it('treats a set it can no longer unwrap as signed out, and says so synchronously', async () => {
		await run((v) => v.storeTokens(tokens));
		key = newKey(); // the key that wrapped them is gone
		expect(await loadTokens()).toBeNull();
		expect(localStorage.getItem(TOKENS_KEY)).not.toBeNull(); // kept, not silently deleted
		expect(hasStoredVisualizerTokens()).toBe(false);
		// Signing in again clears it.
		await run((v) => v.storeTokens(tokens));
		expect(hasStoredVisualizerTokens()).toBe(true);
	});
});
