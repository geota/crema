/**
 * `$lib/services/shot-sync.vitest` — vitest for the `ShotSync` HTTP orchestration
 * (docs/53 T-12, the store-coupled half of T-16).
 *
 * Covers the service behavior that was previously verified only by hand: the
 * `call()` error-taxonomy mapping (402/403→premium, 404→not-found, 204→null,
 * other→HttpStatusError), the `requireConnected` gate, and the
 * `pullAllShotsSince` pagination walk (cursor stop, maxPages truncation,
 * skip-on-non-auth-detail-error vs re-throw-on-auth). These paths are wasm-free
 * — the wasm-side reconcile/signature digests stay pinned by the existing
 * node:test `visualizer/shot-sync.test.ts`, and uploadShot's payload (which
 * calls the wasm signature) is left to that + a future wasm-init test.
 *
 * HttpClient + TokenVault are provided as fakes (the real HttpClient raises
 * HttpStatusError on non-2xx, so the fake does too). Run: `pnpm test:vitest`.
 */

import { Cause, Effect, Exit, Layer } from 'effect';
import { beforeAll, describe, expect, it } from 'vitest';
import { ShotSync, ShotSyncLive } from './shot-sync.ts';
import { HttpClient, type HttpRequest } from './http-client.ts';
import { TokenVault } from './token-vault.ts';
import { HttpStatusError } from '../effect/errors.ts';
import type { TokenSet } from '../visualizer/oauth.ts';
import { initTestWasm } from '../wasm/test-init.ts';

// `pullAllShotsSince` now materialises each row through the wasm-backed
// `wireShotFromDetail` / `samplesFromVisualizerDetail` (CORE1), so the bundle
// must be initialised before the pagination tests run.
beforeAll(async () => {
	await initTestWasm();
});

type Reply =
	| { ok: true; status?: number; json?: unknown }
	| { ok: false; status: number; body?: string };

/** A fake HttpClient: routes each request through `handler`, mapping non-2xx to
 *  HttpStatusError exactly as the real client does. */
function mkHttp(handler: (req: HttpRequest) => Reply) {
	return Layer.succeed(
		HttpClient,
		HttpClient.of({
			request: (req) => {
				const r = handler(req);
				if (!r.ok) {
					return Effect.fail(new HttpStatusError({ status: r.status, url: req.url, body: r.body }));
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

/** A fake TokenVault: `withFreshToken` runs the request with a static token (no
 *  refresh — refresh semantics are covered by token-vault.test.ts). */
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

function provide<A, E>(
	program: Effect.Effect<A, E, ShotSync>,
	handler: (req: HttpRequest) => Reply,
	token: TokenSet | null = aToken
): Effect.Effect<A, E, never> {
	const layer = Layer.provide(ShotSyncLive, Layer.merge(mkHttp(handler), mkVault(token)));
	return Effect.provide(program, layer);
}

const failTag = async (eff: Effect.Effect<unknown, unknown, never>) => {
	const exit = await Effect.runPromiseExit(eff);
	if (!Exit.isFailure(exit)) return { tag: undefined as string | undefined, value: undefined };
	const f = Cause.failureOption(exit.cause);
	const value = f._tag === 'Some' ? (f.value as { _tag?: string; status?: number }) : undefined;
	return { tag: value?._tag, value };
};

describe('ShotSync.requireConnected', () => {
	it('deleteShot fails NotAuthenticatedError when not signed in', async () => {
		const got = await failTag(
			provide(
				ShotSync.pipe(Effect.flatMap((s) => s.deleteShot('viz-1'))),
				() => ({ ok: true, status: 200, json: { success: true } }),
				null
			)
		);
		expect(got.tag).toBe('NotAuthenticatedError');
	});
});

describe('ShotSync.deleteShot — error taxonomy', () => {
	it('treats a 404 as success (already gone)', async () => {
		const exit = await Effect.runPromiseExit(
			provide(
				ShotSync.pipe(Effect.flatMap((s) => s.deleteShot('viz-1'))),
				() => ({ ok: false, status: 404 })
			)
		);
		expect(Exit.isSuccess(exit)).toBe(true);
	});

	it('maps 403 to VisualizerPremiumGatedError', async () => {
		const got = await failTag(
			provide(
				ShotSync.pipe(Effect.flatMap((s) => s.deleteShot('viz-1'))),
				() => ({ ok: false, status: 403 })
			)
		);
		expect(got.tag).toBe('VisualizerPremiumGatedError');
	});

	it('propagates a 500 as HttpStatusError', async () => {
		const got = await failTag(
			provide(
				ShotSync.pipe(Effect.flatMap((s) => s.deleteShot('viz-1'))),
				() => ({ ok: false, status: 500, body: 'boom' })
			)
		);
		expect(got.tag).toBe('HttpStatusError');
		expect(got.value?.status).toBe(500);
	});
});

describe('ShotSync.pullShots', () => {
	it('decodes summaries + paging from a 200 page', async () => {
		const out = await Effect.runPromise(
			provide(
				ShotSync.pipe(Effect.flatMap((s) => s.pullShots(1, 50))),
				() => ({
					ok: true,
					json: {
						data: [{ id: 'a', clock: 20, updated_at: 20 }],
						paging: { count: 1, page: 1, limit: 50, pages: 1 }
					}
				})
			)
		);
		expect(out.summaries).toHaveLength(1);
		expect(out.paging.pages).toBe(1);
	});

	it('returns an empty page on 204', async () => {
		const out = await Effect.runPromise(
			provide(
				ShotSync.pipe(Effect.flatMap((s) => s.pullShots(1, 50))),
				() => ({ ok: true, status: 204 })
			)
		);
		expect(out.summaries).toHaveLength(0);
	});
});

describe('ShotSync.pullAllShotsSince — pagination', () => {
	const listReply = (data: unknown[], pages: number): Reply => ({
		ok: true,
		json: { data, paging: { count: data.length, page: 1, limit: 50, pages } }
	});
	const isList = (req: HttpRequest) => req.url.includes('/shots?');

	it('stops at the cursor and returns only newer shots', async () => {
		// cursor = 10s; summary a (20) is newer → kept, b (5) is older → stops.
		const out = await Effect.runPromise(
			provide(
				ShotSync.pipe(Effect.flatMap((s) => s.pullAllShotsSince(10_000))),
				(req) =>
					isList(req)
						? listReply(
								[
									{ id: 'a', clock: 20, updated_at: 20 },
									{ id: 'b', clock: 5, updated_at: 5 }
								],
								1
							)
						: { ok: true, json: {} } // detail for /shots/a
			)
		);
		expect(out.shots.map((s) => s.id)).toEqual(['a']);
		expect(out.truncated).toBe(false);
	});

	it('reports truncated=true when it hits the maxPages cap', async () => {
		const out = await Effect.runPromise(
			provide(
				ShotSync.pipe(Effect.flatMap((s) => s.pullAllShotsSince(0, { maxPages: 1 }))),
				(req) =>
					isList(req) ? listReply([{ id: 'a', clock: 20, updated_at: 20 }], 5) : { ok: true, json: {} }
			)
		);
		expect(out.truncated).toBe(true);
		expect(out.shots).toHaveLength(1);
	});

	it('skips a row whose detail fetch fails for a non-auth reason', async () => {
		const out = await Effect.runPromise(
			provide(
				ShotSync.pipe(Effect.flatMap((s) => s.pullAllShotsSince(0))),
				(req) =>
					isList(req)
						? listReply([{ id: 'a', clock: 20, updated_at: 20 }], 1)
						: { ok: false, status: 500 } // detail 500 → skip the row
			)
		);
		expect(out.shots).toHaveLength(0);
	});

	it('re-throws when a detail fetch fails with auth (401)', async () => {
		const got = await failTag(
			provide(
				ShotSync.pipe(Effect.flatMap((s) => s.pullAllShotsSince(0))),
				(req) =>
					isList(req)
						? listReply([{ id: 'a', clock: 20, updated_at: 20 }], 1)
						: { ok: false, status: 401 }
			)
		);
		expect(got.tag).toBe('HttpStatusError');
		expect(got.value?.status).toBe(401);
	});
});
