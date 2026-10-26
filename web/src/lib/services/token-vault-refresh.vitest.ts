/**
 * TokenVault refresh failure handling and single-flight (review 03 P1-1, P1-2).
 *
 * Runs the real `OAuthLive` over a fake token endpoint (an `HttpClient` that
 * answers `/oauth/token` from a script), so the whole path from the HTTP
 * outcome to what stays in storage is exercised:
 *  - a transient failure (network, 503) keeps the stored session and the next
 *    call succeeds;
 *  - only `invalid_grant` signs the user out;
 *  - N concurrent callers holding an expiring token make exactly one refresh
 *    request, on both the proactive path and the 401 path;
 *  - a refresh that loses to a newer stored set never clears it.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Cause, Effect, Exit, Layer, SubscriptionRef } from 'effect';

// Identity secret box: these tests are about refresh, not wrapping, and must
// not depend on IndexedDB (absent under jsdom).
vi.mock('#lib/security/secret-box.js', async (importOriginal) => {
	const real = await importOriginal<typeof import('#lib/security/secret-box.js')>();
	return {
		...real,
		secretBox: { wrap: async (p: string) => p, unwrap: async (s: string) => s }
	};
});

const { HttpStatusError, NetworkError } = await import('../effect/errors.ts');
const { HttpClient } = await import('./http-client.ts');
const { OAuthLive } = await import('./oauth.ts');
const { TokenVault, TokenVaultLive } = await import('./token-vault.ts');
const { TOKEN_URL } = await import('../visualizer/oauth.ts');

type TokenSet = import('../visualizer/oauth.ts').TokenSet;
type Outcome = 'network' | number | { access: string; refresh?: string };

const SOON = () => Date.now() + 60_000; // inside the 5-minute refresh window
const FAR = () => Date.now() + 3_600_000;
const tokens = (accessToken: string, refreshToken: string | null, expiresAt: number): TokenSet => ({
	accessToken,
	refreshToken,
	expiresAt,
	scope: 'read',
	tokenType: 'Bearer'
});

/**
 * A fake token endpoint. Each refresh POST takes the next scripted outcome
 * (the last one repeats), after `delayMs` so concurrent callers overlap.
 * `onRequest` runs before answering (to simulate another tab writing).
 */
function endpoint(script: Outcome[], opts: { delayMs?: number; onRequest?: () => Promise<void> } = {}) {
	const bodies: string[] = [];
	const layer = Layer.succeed(
		HttpClient,
		HttpClient.of({
			request: (req) =>
				Effect.gen(function* () {
					if (req.url !== TOKEN_URL) return yield* Effect.die(`unexpected ${req.url}`);
					bodies.push(req.body ?? '');
					if (opts.delayMs) yield* Effect.sleep(opts.delayMs);
					if (opts.onRequest) yield* Effect.promise(opts.onRequest);
					const next = script[Math.min(bodies.length - 1, script.length - 1)];
					if (next === 'network') {
						return yield* new NetworkError({ cause: new TypeError('Failed to fetch'), url: TOKEN_URL });
					}
					if (typeof next === 'number') {
						const body = next === 400 ? '{"error":"invalid_grant"}' : 'upstream unavailable';
						return yield* new HttpStatusError({ status: next, url: TOKEN_URL, body });
					}
					return new Response(
						JSON.stringify({
							access_token: next.access,
							refresh_token: next.refresh,
							expires_in: 3600,
							token_type: 'Bearer',
							scope: 'read'
						})
					);
				})
		})
	);
	return { bodies, layer: Layer.provide(TokenVaultLive, Layer.provide(OAuthLive, layer)) };
}

type Vault = Effect.Success<typeof TokenVault>;
const run = <A, E>(layer: Layer.Layer<TokenVault>, f: (v: Vault) => Effect.Effect<A, E>) =>
	Effect.runPromiseExit(Effect.flatMap(TokenVault, f).pipe(Effect.provide(layer)));

const failTag = (exit: Exit.Exit<unknown, unknown>) => {
	if (!Exit.isFailure(exit)) return undefined;
	const f = Cause.findErrorOption(exit.cause);
	return f._tag === 'Some' ? (f.value as { _tag?: string })._tag : undefined;
};

/** A request that echoes the token it was handed. */
const echo = (t: string) => Effect.succeed(t);

beforeEach(() => {
	localStorage.clear();
	vi.stubEnv('VITE_VISUALIZER_CLIENT_ID', 'client-1');
});
afterEach(() => {
	vi.unstubAllEnvs();
});

describe('TokenVault refresh failures (review 03 P1-1)', () => {
	it('keeps the session through a 503 and refreshes on the next call', async () => {
		const { bodies, layer } = endpoint([503, { access: 'NEW', refresh: 'RT2' }]);
		const exit = await run(layer, (v) =>
			Effect.gen(function* () {
				yield* v.storeTokens(tokens('OLD', 'RT1', SOON()));
				const first = yield* Effect.exit(v.withFreshToken(echo));
				const afterFailure = yield* v.getTokens;
				const second = yield* v.withFreshToken(echo);
				return { first, afterFailure, second, stored: yield* v.getTokens };
			})
		);
		expect(Exit.isSuccess(exit)).toBe(true);
		if (!Exit.isSuccess(exit)) return;
		expect(failTag(exit.value.first)).toBe('HttpStatusError');
		expect(exit.value.afterFailure?.refreshToken).toBe('RT1');
		expect(exit.value.second).toBe('NEW');
		expect(exit.value.stored?.refreshToken).toBe('RT2');
		expect(bodies).toHaveLength(2);
	});

	it('keeps the session on a network failure (NetworkError, tokens untouched)', async () => {
		const { layer } = endpoint(['network']);
		const exit = await run(layer, (v) =>
			Effect.gen(function* () {
				yield* v.storeTokens(tokens('OLD', 'RT1', SOON()));
				const call = yield* Effect.exit(v.withFreshToken(echo));
				return { call, stored: yield* v.getTokens, ref: yield* SubscriptionRef.get(v.changes) };
			})
		);
		if (!Exit.isSuccess(exit)) throw new Error('vault program failed');
		expect(failTag(exit.value.call)).toBe('NetworkError');
		expect(exit.value.stored).toMatchObject({ accessToken: 'OLD', refreshToken: 'RT1' });
		expect(exit.value.ref).not.toBeNull();
	});

	it('keeps the session when the 401-retry refresh hits a network failure', async () => {
		const { layer } = endpoint(['network']);
		const exit = await run(layer, (v) =>
			Effect.gen(function* () {
				yield* v.storeTokens(tokens('OLD', 'RT1', FAR()));
				const call = yield* Effect.exit(
					v.withFreshToken(() => Effect.fail(new HttpStatusError({ status: 401, url: 'u' })))
				);
				return { call, stored: yield* v.getTokens };
			})
		);
		if (!Exit.isSuccess(exit)) throw new Error('vault program failed');
		expect(failTag(exit.value.call)).toBe('NetworkError');
		expect(exit.value.stored?.refreshToken).toBe('RT1');
	});

	it('signs out on invalid_grant', async () => {
		const { layer } = endpoint([400]);
		const exit = await run(layer, (v) =>
			Effect.gen(function* () {
				yield* v.storeTokens(tokens('OLD', 'RT1', SOON()));
				const call = yield* Effect.exit(v.withFreshToken(echo));
				return { call, stored: yield* v.getTokens, ref: yield* SubscriptionRef.get(v.changes) };
			})
		);
		if (!Exit.isSuccess(exit)) throw new Error('vault program failed');
		expect(failTag(exit.value.call)).toBe('TokenRefreshFailedError');
		expect(exit.value.stored).toBeNull();
		expect(exit.value.ref).toBeNull();
	});
});

describe('TokenVault single-flight refresh (review 03 P1-2)', () => {
	it('N parallel callers with an expiring token trigger exactly one refresh request', async () => {
		const { bodies, layer } = endpoint([{ access: 'NEW', refresh: 'RT2' }, 400], { delayMs: 20 });
		const exit = await run(layer, (v) =>
			Effect.gen(function* () {
				yield* v.storeTokens(tokens('OLD', 'RT1', SOON()));
				return yield* Effect.all(
					Array.from({ length: 6 }, () => v.withFreshToken(echo)),
					{ concurrency: 'unbounded' }
				);
			})
		);
		expect(Exit.isSuccess(exit) && exit.value).toEqual(Array(6).fill('NEW'));
		expect(bodies).toHaveLength(1);
		expect(bodies[0]).toContain('refresh_token=RT1');
	});

	it('N parallel 401s share one refresh and all retry with the new token', async () => {
		const { bodies, layer } = endpoint([{ access: 'NEW', refresh: 'RT2' }, 400], { delayMs: 20 });
		const exit = await run(layer, (v) =>
			Effect.gen(function* () {
				yield* v.storeTokens(tokens('OLD', 'RT1', FAR()));
				const req = (t: string) =>
					t === 'OLD' ? Effect.fail(new HttpStatusError({ status: 401, url: 'u' })) : Effect.succeed(t);
				return yield* Effect.all(
					Array.from({ length: 4 }, () => v.withFreshToken(req)),
					{ concurrency: 'unbounded' }
				);
			})
		);
		expect(Exit.isSuccess(exit) && exit.value).toEqual(Array(4).fill('NEW'));
		expect(bodies).toHaveLength(1);
	});

	it('a refresh rejected after another tab stored a newer set keeps that set', async () => {
		// While our refresh is in flight, "another tab" rotates the token and
		// stores the result; the server then rejects our now-stale RT1.
		const otherTab = tokens('OTHER', 'RT-OTHER', FAR());
		const { layer } = endpoint([400], {
			onRequest: async () => {
				await Effect.runPromise(
					Effect.flatMap(TokenVault, (v) => v.storeTokens(otherTab)).pipe(Effect.provide(layer))
				);
			}
		});
		const exit = await run(layer, (v) =>
			Effect.gen(function* () {
				yield* v.storeTokens(tokens('OLD', 'RT1', SOON()));
				const token = yield* v.withFreshToken(echo);
				return { token, stored: yield* v.getTokens };
			})
		);
		expect(Exit.isSuccess(exit)).toBe(true);
		if (!Exit.isSuccess(exit)) return;
		expect(exit.value.token).toBe('OTHER');
		expect(exit.value.stored).toMatchObject({ accessToken: 'OTHER', refreshToken: 'RT-OTHER' });
	});
});
