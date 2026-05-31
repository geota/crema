/**
 * `$lib/visualizer/oauth.test` — node:test suite for the PKCE helpers.
 *
 * The web shell does not (yet) wire vitest into the build — these tests
 * are runnable with the platform's built-in test runner:
 *
 * ```
 * cd web && node \
 *   --experimental-strip-types \
 *   --experimental-detect-module \
 *   --test src/lib/visualizer/oauth.test.ts
 * ```
 *
 * They exercise only the pure crypto helpers — no fetch, no DOM. The
 * RFC 7636 §A appendix fixture is used to lock the SHA-256 challenge
 * derivation to the spec value.
 *
 * The `.ts` extension on the import is intentional: Node's built-in
 * strip-types resolver requires the explicit extension. SvelteKit /
 * Vite (which is what powers `pnpm check` + `pnpm build`) is tolerant
 * either way, so the explicit `.ts` works in both worlds.
 */

import assert from 'node:assert/strict';
import { describe, it } from 'vitest';
import {
	codeChallengeFromVerifier,
	generateCodeVerifier,
	randomState
} from './oauth.ts';

describe('generateCodeVerifier', () => {
	it('returns a string of 64 URL-safe characters', () => {
		const v = generateCodeVerifier();
		assert.equal(typeof v, 'string');
		assert.equal(v.length, 64);
		// RFC 7636 §4.1: `ALPHA / DIGIT / "-" / "." / "_" / "~"`. Our
		// implementation uses URL-safe base64 (subset of that set, minus `~`),
		// so the regex below is the right one to assert.
		assert.match(v, /^[A-Za-z0-9_-]{64}$/);
	});

	it('produces a different verifier on each call', () => {
		const a = generateCodeVerifier();
		const b = generateCodeVerifier();
		assert.notEqual(a, b);
	});
});

describe('codeChallengeFromVerifier (RFC 7636 §A.1 fixture)', () => {
	it('matches the SHA-256 / base64-url value in the spec', async () => {
		// From RFC 7636 §A.1 (verbatim):
		//   code_verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
		//   code_challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
		const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
		const expected = 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM';
		const got = await codeChallengeFromVerifier(verifier);
		assert.equal(got, expected);
	});
});

describe('randomState', () => {
	it('returns a non-empty string with URL-safe base64 chars', () => {
		const s = randomState();
		assert.equal(typeof s, 'string');
		assert.ok(s.length > 0);
		assert.match(s, /^[A-Za-z0-9_-]+$/);
	});

	it('produces unique values across many draws', () => {
		const seen = new Set<string>();
		for (let i = 0; i < 100; i++) seen.add(randomState());
		assert.equal(seen.size, 100);
	});

	it('respects the byte-length parameter', () => {
		// 32 bytes → 43 base64-url chars (rounded up from 42.66, no padding).
		const s = randomState(32);
		assert.equal(s.length, 43);
	});
});
