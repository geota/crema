/**
 * `#lib/services/oauth.test` — node:test for the OAuth service's typed-error
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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Cause, Effect, Exit, Layer } from 'effect';
import { HttpStatusError, NetworkError, ResponseDecodeError } from '../effect/errors.ts';
import { HttpClient, HttpClientLive } from './http-client.ts';
import { OAuth, OAuthLive, classifyRefreshFailure } from './oauth.ts';
import { TOKEN_URL } from '../visualizer/oauth.ts';

/** OAuthLive with its HttpClient dependency satisfied. */
const OAuthProvided = Layer.provide(OAuthLive, HttpClientLive);

function failTag(exit: Exit.Exit<unknown, { readonly _tag?: string }>): string | undefined {
	if (!Exit.isFailure(exit)) return undefined;
	const f = Cause.findErrorOption(exit.cause);
	return f._tag === 'Some' ? f.value?._tag : undefined;
}

describe('OAuth service', () => {
	it('refreshToken maps a failure to TokenRefreshFailedError', async () => {
		const exit = await Effect.runPromiseExit(
			OAuth.pipe(
				Effect.flatMap((o) => o.refreshToken('some-refresh-token')),
				Effect.provide(OAuthProvided)
			)
		);
		assert.equal(failTag(exit), 'TokenRefreshFailedError');
	});
});

// ── Review 03 P1-1: only a definitive grant rejection ends the session ──────

const status = (s: number, body?: string) => new HttpStatusError({ status: s, url: TOKEN_URL, body });

describe('classifyRefreshFailure', () => {
	it.each([
		[400, '{"error":"invalid_grant","error_description":"The provided authorization grant is invalid"}'],
		[401, '{"error":"invalid_grant"}']
	])('HTTP %i invalid_grant is definitive (TokenRefreshFailedError)', (s, body) => {
		expect(classifyRefreshFailure(status(s, body))._tag).toBe('TokenRefreshFailedError');
	});

	it.each([500, 502, 503, 504, 408, 429])('HTTP %i stays a retryable HttpStatusError', (s) => {
		const out = classifyRefreshFailure(status(s, '<html>busy</html>'));
		expect(out._tag).toBe('HttpStatusError');
		expect(out instanceof HttpStatusError && out.status).toBe(s);
	});

	it.each([
		['a transport failure', new NetworkError({ cause: new TypeError('Failed to fetch'), url: TOKEN_URL })],
		['an undecodable 2xx body', new ResponseDecodeError({ url: TOKEN_URL, cause: 'html' })],
		['401 invalid_client', status(401, '{"error":"invalid_client"}')],
		['400 with a non-JSON body', status(400, '<html>captive portal</html>')],
		['a proxy 403', status(403, 'Forbidden')]
	])('%s keeps the session (NetworkError)', (_label, cause) => {
		expect(classifyRefreshFailure(cause)._tag).toBe('NetworkError');
	});
});

describe('OAuth.refreshToken against a fake token endpoint', () => {
	afterEach(() => {
		vi.unstubAllEnvs();
	});

	const withEndpoint = (respond: () => Effect.Effect<Response, NetworkError | HttpStatusError>) =>
		Layer.provide(OAuthLive, Layer.succeed(HttpClient, HttpClient.of({ request: respond })));

	const refresh = (respond: () => Effect.Effect<Response, NetworkError | HttpStatusError>) =>
		Effect.runPromiseExit(
			OAuth.pipe(
				Effect.flatMap((o) => o.refreshToken('RT')),
				Effect.provide(withEndpoint(respond))
			)
		);

	it('surfaces a network failure as NetworkError, not TokenRefreshFailedError', async () => {
		vi.stubEnv('VITE_VISUALIZER_CLIENT_ID', 'client-1');
		const exit = await refresh(() =>
			Effect.fail(new NetworkError({ cause: new TypeError('Failed to fetch'), url: TOKEN_URL }))
		);
		expect(failTag(exit)).toBe('NetworkError');
	});

	it('surfaces a 503 as HttpStatusError', async () => {
		vi.stubEnv('VITE_VISUALIZER_CLIENT_ID', 'client-1');
		expect(failTag(await refresh(() => Effect.fail(status(503))))).toBe('HttpStatusError');
	});

	it('maps invalid_grant to TokenRefreshFailedError', async () => {
		vi.stubEnv('VITE_VISUALIZER_CLIENT_ID', 'client-1');
		const exit = await refresh(() => Effect.fail(status(400, '{"error":"invalid_grant"}')));
		expect(failTag(exit)).toBe('TokenRefreshFailedError');
	});

	it('surfaces a 200 captive-portal page as NetworkError', async () => {
		vi.stubEnv('VITE_VISUALIZER_CLIENT_ID', 'client-1');
		const exit = await refresh(() => Effect.succeed(new Response('<html>log in to Wi-Fi</html>')));
		expect(failTag(exit)).toBe('NetworkError');
	});

	it('posts the refresh grant with a timeout', async () => {
		vi.stubEnv('VITE_VISUALIZER_CLIENT_ID', 'client-1');
		const seen: { timeoutMs?: number; body?: string }[] = [];
		const layer = Layer.provide(
			OAuthLive,
			Layer.succeed(
				HttpClient,
				HttpClient.of({
					request: (req) => {
						seen.push(req);
						return Effect.succeed(
							new Response(JSON.stringify({ access_token: 'NEW', refresh_token: 'RT2', expires_in: 3600 }))
						);
					}
				})
			)
		);
		const out = await Effect.runPromise(
			OAuth.pipe(Effect.flatMap((o) => o.refreshToken('RT')), Effect.provide(layer))
		);
		expect(out.accessToken).toBe('NEW');
		expect(seen[0]?.timeoutMs).toBeGreaterThan(0);
		expect(seen[0]?.body).toContain('grant_type=refresh_token');
	});
});
