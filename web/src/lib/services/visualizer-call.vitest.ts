/**
 * `$lib/services/visualizer-call.vitest` — the consolidated Visualizer call
 * seam (the deepening that pulled the duplicated `call` + taxonomy + policy out
 * of `ShotSync` / `BeanSync` / `UploadQueue`).
 *
 * This is now the ONE place the call mapping (auth header, 402/403→premium,
 * 404→not-found, 204→null, malformed-2xx→NetworkError) and the shared policy
 * (`isRecoverable`, `describeVisualizerError`) are unit-tested. The service
 * vitests keep their orchestration cases (pagination, the upload PATCH
 * follow-up, the 5-phase runSync) which now exercise this seam end-to-end.
 *
 * HttpClient + TokenVault are provided as fakes (the real HttpClient raises
 * HttpStatusError on non-2xx, so the fake does too).
 */

import { Cause, Effect, Exit, Layer } from 'effect';
import { beforeAll, describe, expect, it } from 'vitest';
import {
	describeVisualizerError,
	isRecoverable,
	visualizerCall,
	type VisualizerCallError
} from './visualizer-call.ts';
import { initTestWasm } from '$lib/testing/test-init';

// `isRecoverable` now delegates the retry policy to the wasm core (CORE5).
beforeAll(async () => {
	await initTestWasm();
});
import { HttpClient, type HttpRequest } from './http-client.ts';
import { TokenVault } from './token-vault.ts';
import {
	HttpStatusError,
	NetworkError,
	NotAuthenticatedError,
	ResponseDecodeError,
	TokenRefreshFailedError,
	VisualizerNotFoundError,
	VisualizerPremiumGatedError
} from '../effect/errors.ts';
import type { TokenSet } from '../visualizer/oauth.ts';

type Reply =
	| { ok: true; status?: number; json?: unknown; text?: string }
	| { ok: false; status: number; body?: string };

/** A fake HttpClient: routes each request through `handler`, mapping non-2xx to
 *  HttpStatusError exactly as the real client does. `text` injects a raw body so
 *  a malformed-JSON 2xx can be exercised. */
function mkHttp(handler: (req: HttpRequest) => Reply) {
	return Layer.succeed(
		HttpClient,
		HttpClient.of({
			request: (req) => {
				const r = handler(req);
				if (!r.ok) {
					return Effect.fail(new HttpStatusError({ status: r.status, url: req.url, body: r.body }));
				}
				if (r.text !== undefined) {
					return Effect.succeed(
						new Response(r.text, { status: 200, headers: { 'content-type': 'application/json' } })
					);
				}
				const status = r.status ?? 200;
				if (status === 204) return Effect.succeed(new Response(null, { status }));
				return Effect.succeed(
					new Response(JSON.stringify(r.json ?? {}), {
						status,
						headers: { 'content-type': 'application/json' }
					})
				);
			}
		})
	);
}

const aToken: TokenSet = {
	accessToken: 'tok',
	refreshToken: 'rt',
	expiresAt: Date.now() + 3_600_000,
	scope: 'read',
	tokenType: 'Bearer'
};

/** A fake TokenVault: `withFreshToken` runs the request with a static token. */
function mkVault(token: TokenSet | null) {
	return Layer.succeed(
		TokenVault,
		TokenVault.of({
			getTokens: Effect.succeed(token),
			storeTokens: () => Effect.void,
			clearTokens: Effect.void,
			withFreshToken: ((req: (t: string) => Effect.Effect<unknown, unknown>) =>
				req('tok')) as never,
			changes: undefined as never
		})
	);
}

function run<A, E>(
	program: Effect.Effect<A, E, HttpClient | TokenVault>,
	handler: (req: HttpRequest) => Reply
): Effect.Effect<A, E, never> {
	return Effect.provide(program, Layer.merge(mkHttp(handler), mkVault(aToken)));
}

const failTag = async (eff: Effect.Effect<unknown, unknown, never>) => {
	const exit = await Effect.runPromiseExit(eff);
	if (!Exit.isFailure(exit)) return { tag: undefined as string | undefined, value: undefined };
	const f = Cause.failureOption(exit.cause);
	const value = f._tag === 'Some' ? (f.value as { _tag?: string; status?: number; visualizerId?: string; endpoint?: string }) : undefined;
	return { tag: value?._tag, value };
};

describe('visualizerCall — success bodies', () => {
	it('returns the parsed JSON for a 2xx body', async () => {
		const out = await Effect.runPromise(run(visualizerCall('/me'), () => ({ ok: true, json: { id: 'u1' } })));
		expect(out).toEqual({ id: 'u1' });
	});

	it('returns null for a 204', async () => {
		const out = await Effect.runPromise(
			run(visualizerCall('/shots/x', { method: 'DELETE' }), () => ({ ok: true, status: 204 }))
		);
		expect(out).toBeNull();
	});
});

describe('visualizerCall — status taxonomy', () => {
	it('maps 402 to VisualizerPremiumGatedError (endpoint = path)', async () => {
		const got = await failTag(run(visualizerCall('/roasters', { method: 'POST', body: {} }), () => ({ ok: false, status: 402 })));
		expect(got.tag).toBe('VisualizerPremiumGatedError');
		expect(got.value?.endpoint).toBe('/roasters');
	});

	it('maps 403 to VisualizerPremiumGatedError', async () => {
		const got = await failTag(run(visualizerCall('/coffee_bags', { method: 'POST', body: {} }), () => ({ ok: false, status: 403 })));
		expect(got.tag).toBe('VisualizerPremiumGatedError');
	});

	it('maps 404 to VisualizerNotFoundError (visualizerId = path)', async () => {
		const got = await failTag(run(visualizerCall('/shots/abc', { method: 'DELETE' }), () => ({ ok: false, status: 404 })));
		expect(got.tag).toBe('VisualizerNotFoundError');
		expect(got.value?.visualizerId).toBe('/shots/abc');
	});

	it('propagates a 500 as HttpStatusError', async () => {
		const got = await failTag(run(visualizerCall('/shots'), () => ({ ok: false, status: 500, body: 'boom' })));
		expect(got.tag).toBe('HttpStatusError');
		expect(got.value?.status).toBe(500);
	});

	it('surfaces a malformed 2xx body as NetworkError (stays recoverable)', async () => {
		const got = await failTag(run(visualizerCall('/me'), () => ({ ok: true, text: 'not json{' })));
		expect(got.tag).toBe('NetworkError');
	});
});

describe('visualizerCall — request shape', () => {
	it('attaches the Bearer token and stringifies a JSON body', async () => {
		let captured: HttpRequest | undefined;
		await Effect.runPromise(
			run(visualizerCall('/coffee_bags', { method: 'POST', body: { roaster: { name: 'r' } } }), (req) => {
				captured = req;
				return { ok: true, json: { id: 'b1' } };
			})
		);
		expect(captured?.headers?.Authorization).toBe('Bearer tok');
		expect(captured?.headers?.['Content-Type']).toBe('application/json');
		expect(captured?.body).toBe(JSON.stringify({ roaster: { name: 'r' } }));
		expect(captured?.method).toBe('POST');
	});

	it('sends no body / no Content-Type for a GET', async () => {
		let captured: HttpRequest | undefined;
		await Effect.runPromise(
			run(visualizerCall('/me'), (req) => {
				captured = req;
				return { ok: true, json: {} };
			})
		);
		expect(captured?.body).toBeUndefined();
		expect(captured?.headers?.['Content-Type']).toBeUndefined();
	});
});

describe('isRecoverable — the canonical retry policy', () => {
	const httpErr = (status: number) => new HttpStatusError({ status, url: 'u' });
	const cases: ReadonlyArray<[string, VisualizerCallError | ResponseDecodeError, boolean]> = [
		['NetworkError', new NetworkError({ cause: 'x', url: 'u' }), true],
		['500', httpErr(500), true],
		['503', httpErr(503), true],
		['408', httpErr(408), true],
		['0 (transport-blocked — canonical resolution)', httpErr(0), true],
		['404', httpErr(404), false],
		['402', httpErr(402), false],
		['401', httpErr(401), false],
		['premium', new VisualizerPremiumGatedError({ endpoint: '/x' }), false],
		['not-authed', new NotAuthenticatedError(), false],
		['refresh-failed', new TokenRefreshFailedError({ cause: 'x' }), false],
		['decode', new ResponseDecodeError({ url: 'u', cause: 'x' }), false],
		['not-found', new VisualizerNotFoundError({ visualizerId: 'v' }), false]
	];
	for (const [label, err, expected] of cases) {
		it(`${label} → ${expected}`, () => {
			expect(isRecoverable(err)).toBe(expected);
		});
	}
});

describe('describeVisualizerError', () => {
	it('formats an HttpStatusError with status + body', () => {
		expect(describeVisualizerError(new HttpStatusError({ status: 500, url: 'u', body: 'boom' }))).toBe('HTTP 500: boom');
	});
	it('uses the generic "row" wording for not-found', () => {
		expect(describeVisualizerError(new VisualizerNotFoundError({ visualizerId: 'v9' }))).toBe('Visualizer row not found: v9');
	});
	it('gives an actionable message for premium-gated', () => {
		expect(describeVisualizerError(new VisualizerPremiumGatedError({ endpoint: '/x' }))).toBe('Premium subscription required for writes.');
	});
	it('gives an actionable message for not-authenticated', () => {
		expect(describeVisualizerError(new NotAuthenticatedError())).toBe('Sign in to Visualizer first.');
	});
});
