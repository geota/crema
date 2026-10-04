/**
 * `$lib/services/bean-sync.vitest` — vitest for the `BeanSync` mutations (docs/53
 * T-13). Previously verified only by the docs/55 "delete a bag + roaster on
 * Visualizer" manual step; `bean-sync.ts` pulls the bean store + wire converters
 * (`$lib/bean`) so it's unreachable under node:test.
 *
 * Covers: POST-vs-PATCH on `visualizerId` (create binds the returned id, update
 * keeps the existing one), the premium (402/403) mapping, the missing-id decode
 * failure, and delete's 404-as-success. HttpClient + TokenVault are fakes.
 * Run: `pnpm test:vitest`.
 */

import { Cause, Effect, Exit, Layer } from 'effect';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { BeanSync, BeanSyncLive, PREMIUM_REFRESH_INTERVAL_MS } from './bean-sync.ts';
import { HttpClient, type HttpRequest } from './http-client.ts';
import { TokenVault } from './token-vault.ts';
import { HttpStatusError } from '../effect/errors.ts';
import { blankBean, blankRoaster } from '$lib/bean';
import type { TokenSet } from '../visualizer/oauth.ts';
import { initTestWasm } from '$lib/testing/test-init';
import { readSyncConfig } from '$lib/visualizer/sync-config';
import { readSyncSettings, writeSyncSettings } from '$lib/bean/visualizer-sync';
import { completeVisualizerSignIn } from './visualizer-sign-in.ts';

// `uploadBean` / `uploadRoaster` build their bodies through the wasm-backed
// `beanToWire` / `roasterToWire` (CORE1), so init the bundle first.
beforeAll(async () => {
	await initTestWasm();
});

type Reply = { ok: true; status?: number; json?: unknown } | { ok: false; status: number };

function mkHttp(handler: (req: HttpRequest) => Reply) {
	const calls: HttpRequest[] = [];
	const layer = Layer.succeed(
		HttpClient,
		HttpClient.of({
			request: (req) => {
				calls.push(req);
				const r = handler(req);
				if (!r.ok) return Effect.fail(new HttpStatusError({ status: r.status, url: req.url }));
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
	return { layer, calls };
}

const aToken: TokenSet = {
	accessToken: 'tok',
	refreshToken: 'rt',
	expiresAt: Date.now() + 3_600_000,
	scope: 'read',
	tokenType: 'Bearer'
};
const vault = Layer.succeed(
	TokenVault,
	TokenVault.of({
		getTokens: Effect.succeed(aToken),
		storeTokens: () => Effect.void,
		clearTokens: Effect.void,
		withFreshToken: ((req: (t: string) => Effect.Effect<unknown, unknown>) => req('tok')) as never,
		changes: undefined as never
	})
);

function run<A, E>(program: Effect.Effect<A, E, BeanSync>, http: Layer.Layer<HttpClient>) {
	return Effect.runPromise(Effect.provide(program, Layer.provide(BeanSyncLive, Layer.merge(http, vault))));
}
const failTag = async (program: Effect.Effect<unknown, unknown, BeanSync>, http: Layer.Layer<HttpClient>) => {
	const exit = await Effect.runPromiseExit(
		Effect.provide(program, Layer.provide(BeanSyncLive, Layer.merge(http, vault)))
	);
	if (!Exit.isFailure(exit)) return undefined;
	const f = Cause.failureOption(exit.cause);
	return f._tag === 'Some' ? (f.value as { _tag?: string })._tag : undefined;
};

describe('BeanSync.uploadBean', () => {
	it('POSTs a fresh bag and binds the returned id', async () => {
		const { layer, calls } = mkHttp(() => ({ ok: true, json: { id: 'vb-1' } }));
		const out = await run(
			BeanSync.pipe(Effect.flatMap((b) => b.uploadBean(blankBean(), null))),
			layer
		);
		expect(out.visualizerId).toBe('vb-1');
		expect(calls[0].method).toBe('POST');
		expect(calls[0].url).toContain('/coffee_bags');
	});

	it('PATCHes an existing bag and keeps its id', async () => {
		const { layer, calls } = mkHttp(() => ({ ok: true, json: { id: 'ignored' } }));
		const bean = { ...blankBean(), visualizerId: 'existing-9' };
		const out = await run(BeanSync.pipe(Effect.flatMap((b) => b.uploadBean(bean, null))), layer);
		expect(out.visualizerId).toBe('existing-9');
		expect(calls[0].method).toBe('PATCH');
		expect(calls[0].url).toContain('/coffee_bags/existing-9');
	});

	it('fails ResponseDecodeError when the POST returns no id', async () => {
		const { layer } = mkHttp(() => ({ ok: true, json: {} }));
		const tag = await failTag(BeanSync.pipe(Effect.flatMap((b) => b.uploadBean(blankBean(), null))), layer);
		expect(tag).toBe('ResponseDecodeError');
	});

	it('maps a 403 to VisualizerPremiumGatedError', async () => {
		const { layer } = mkHttp(() => ({ ok: false, status: 403 }));
		const tag = await failTag(BeanSync.pipe(Effect.flatMap((b) => b.uploadBean(blankBean(), null))), layer);
		expect(tag).toBe('VisualizerPremiumGatedError');
	});
});

describe('BeanSync.uploadRoaster', () => {
	it('POSTs a fresh roaster and binds the returned id', async () => {
		const { layer, calls } = mkHttp(() => ({ ok: true, json: { id: 'vr-1' } }));
		const out = await run(
			BeanSync.pipe(Effect.flatMap((b) => b.uploadRoaster(blankRoaster('Acme')))),
			layer
		);
		expect(out.visualizerId).toBe('vr-1');
		expect(calls[0].method).toBe('POST');
		expect(calls[0].url).toContain('/roasters');
	});
});

describe('BeanSync.searchCatalogue', () => {
	it('GETs /canonical_coffee_bags with q + items and parses the page in core', async () => {
		const { layer, calls } = mkHttp(() => ({
			ok: true,
			json: {
				data: [
					{
						id: 'cb-1',
						canonical_roaster_id: 'cr-1',
						canonical_roaster_name: 'Onyx',
						name: 'Hambela',
						country: 'Ethiopia',
						processing: 'Washed',
						created_at: 'x',
						updated_at: 'x'
					}
				],
				paging: { count: 1, page: 1, limit: 10, pages: 1 }
			}
		}));
		const page = await run(
			BeanSync.pipe(Effect.flatMap((b) => b.searchCatalogue('onyx hambela'))),
			layer
		);
		expect(calls[0].method ?? 'GET').toBe('GET');
		expect(calls[0].url).toContain('/canonical_coffee_bags?q=onyx+hambela&items=10');
		expect(page.entries[0]).toMatchObject({ name: 'Hambela', roasterName: 'Onyx', meta: 'Ethiopia · Washed' });
	});

	it('surfaces HTTP failures as the Visualizer error taxonomy', async () => {
		const { layer } = mkHttp(() => ({ ok: false, status: 500 }));
		const tag = await failTag(BeanSync.pipe(Effect.flatMap((b) => b.searchCatalogue('x'))), layer);
		expect(tag).toBe('HttpStatusError');
	});
});

describe('BeanSync.searchCatalogueRoasters', () => {
	it('GETs /canonical_roasters with q + items and parses the page in core', async () => {
		const { layer, calls } = mkHttp(() => ({
			ok: true,
			json: {
				data: [{ id: 'cr-1', name: 'Onyx Coffee Lab', website: 'https://onyx', country: 'USA' }],
				paging: { count: 1, page: 1, limit: 10, pages: 1 }
			}
		}));
		const page = await run(
			BeanSync.pipe(Effect.flatMap((b) => b.searchCatalogueRoasters('onyx coffee'))),
			layer
		);
		expect(calls[0].url).toContain('/canonical_roasters?q=onyx+coffee&items=10');
		expect(page.entries[0]).toEqual({
			id: 'cr-1',
			name: 'Onyx Coffee Lab',
			website: 'https://onyx',
			country: 'USA'
		});
	});
});

describe('BeanSync delete', () => {
	it('treats a 404 on deleteBean as success', async () => {
		const { layer } = mkHttp(() => ({ ok: false, status: 404 }));
		const exit = await Effect.runPromiseExit(
			Effect.provide(
				BeanSync.pipe(Effect.flatMap((b) => b.deleteBean('vb-1'))),
				Layer.provide(BeanSyncLive, Layer.merge(layer, vault))
			)
		);
		expect(Exit.isSuccess(exit)).toBe(true);
	});

	it('propagates a 500 on deleteRoaster as HttpStatusError', async () => {
		const { layer } = mkHttp(() => ({ ok: false, status: 500 }));
		const tag = await failTag(BeanSync.pipe(Effect.flatMap((b) => b.deleteRoaster('vr-1'))), layer);
		expect(tag).toBe('HttpStatusError');
	});
});

describe('BeanSync.fetchAccount', () => {
	it('decodes /me into the camel-cased account', async () => {
		const { layer } = mkHttp(() => ({
			ok: true,
			json: { id: 'u1', name: 'Ada', public: true, avatar_url: 'http://x/a.png' }
		}));
		const out = await run(BeanSync.pipe(Effect.flatMap((b) => b.fetchAccount)), layer);
		expect(out).toEqual({ id: 'u1', name: 'Ada', public: true, avatarUrl: 'http://x/a.png' });
	});

	it('is just the /me read — no premium probe rides on it', async () => {
		const { layer, calls } = mkHttp(() => ({
			ok: true,
			json: { id: 'u1', name: 'Ada', public: true, avatar_url: 'http://x/a.png' }
		}));
		await run(BeanSync.pipe(Effect.flatMap((b) => b.fetchAccount)), layer);
		expect(calls.map((c) => `${c.method ?? 'GET'} ${new URL(c.url).pathname}`)).toEqual(['GET /api/me']);
	});

	it('fails ResponseDecodeError on a malformed /me', async () => {
		const { layer } = mkHttp(() => ({ ok: true, json: { nope: true } }));
		const tag = await failTag(BeanSync.pipe(Effect.flatMap((b) => b.fetchAccount)), layer);
		expect(tag).toBe('ResponseDecodeError');
	});
});

describe('BeanSync.testConnection', () => {
	beforeEach(() => localStorage.clear());

	it('reports premium when the sentinel write succeeds', async () => {
		const { layer } = mkHttp((req) => {
			if (req.method === 'POST') return { ok: true, json: { id: 'sentinel' } };
			return { ok: true, json: { data: [], paging: { pages: 1 } } }; // GET check + DELETE
		});
		const out = await run(BeanSync.pipe(Effect.flatMap((b) => b.testConnection)), layer);
		expect(out).toEqual({ ok: true, premium: true });
	});

	it('reports free tier when the sentinel write is premium-gated', async () => {
		const { layer } = mkHttp((req) =>
			req.method === 'POST' ? { ok: false, status: 403 } : { ok: true, json: { data: [] } }
		);
		const out = await run(BeanSync.pipe(Effect.flatMap((b) => b.testConnection)), layer);
		expect(out).toEqual({ ok: true, premium: false });
	});

	it('reports not-ok when the initial read fails auth', async () => {
		const { layer } = mkHttp(() => ({ ok: false, status: 401 }));
		const out = (await run(BeanSync.pipe(Effect.flatMap((b) => b.testConnection)), layer)) as {
			ok: boolean;
		};
		expect(out.ok).toBe(false);
	});
});

describe('BeanSync premium refresh (the bean-sync flag the edit sync reads)', () => {
	beforeEach(() => localStorage.clear());

	/** The sentinel roaster probe: POST /roasters answers `status`; GET / DELETE succeed. */
	const probeHttp = (status: number) =>
		mkHttp((req) => {
			if (req.method === 'POST') return status < 300 ? { ok: true, status, json: { id: 'sentinel' } } : { ok: false, status };
			return { ok: true, json: { data: [], paging: { pages: 1 } } };
		});
	const roasterPosts = (calls: HttpRequest[]) =>
		calls.filter((c) => c.method === 'POST' && new URL(c.url).pathname === '/api/roasters');
	const runRefresh = (http: Layer.Layer<HttpClient>, which: 'refreshPremium' | 'refreshPremiumIfStale') =>
		run(BeanSync.pipe(Effect.flatMap((b) => b[which])), http);

	it('refreshPremium runs the sentinel POST /roasters probe and caches premium + the probe time', async () => {
		const { layer, calls } = probeHttp(201);
		const before = Date.now();
		expect(await runRefresh(layer, 'refreshPremium')).toBe(true);
		expect(roasterPosts(calls)).toHaveLength(1);
		expect(calls.some((c) => c.method === 'DELETE' && c.url.endsWith('/roasters/sentinel'))).toBe(true);
		expect(calls.some((c) => c.url.includes('/coffee_bags/'))).toBe(false);
		const s = readSyncSettings();
		expect(s.premium).toBe(true);
		expect(s.premiumCheckedAt).toBeGreaterThanOrEqual(before);
		expect(readSyncConfig().premium).toBe(true);
	});

	it('refreshPremium caches free on a premium-gated (403) sentinel', async () => {
		const { layer } = probeHttp(403);
		expect(await runRefresh(layer, 'refreshPremium')).toBe(false);
		expect(readSyncSettings().premium).toBe(false);
	});

	it('an inconclusive probe keeps the cached flag and its timestamp', async () => {
		writeSyncSettings({ premium: true, premiumCheckedAt: 5 });
		const { layer } = probeHttp(500);
		expect(await runRefresh(layer, 'refreshPremium')).toBeNull();
		expect(readSyncSettings()).toMatchObject({ premium: true, premiumCheckedAt: 5 });
	});

	it('refreshPremiumIfStale (app start) skips the probe within 24 h of the last one', async () => {
		writeSyncSettings({ premium: false, premiumCheckedAt: Date.now() - 60 * 60 * 1000 });
		const { layer, calls } = probeHttp(201);
		expect(await runRefresh(layer, 'refreshPremiumIfStale')).toBe(false);
		expect(calls).toHaveLength(0);
		expect(readSyncSettings().premium).toBe(false);
	});

	it('refreshPremiumIfStale probes when the last check is over 24 h old, or never happened', async () => {
		writeSyncSettings({ premium: false, premiumCheckedAt: Date.now() - PREMIUM_REFRESH_INTERVAL_MS - 1 });
		const stale = probeHttp(201);
		expect(await runRefresh(stale.layer, 'refreshPremiumIfStale')).toBe(true);
		expect(roasterPosts(stale.calls)).toHaveLength(1);
		expect(Date.now() - readSyncSettings().premiumCheckedAt!).toBeLessThan(PREMIUM_REFRESH_INTERVAL_MS);

		localStorage.clear();
		const never = probeHttp(403);
		expect(await runRefresh(never.layer, 'refreshPremiumIfStale')).toBe(false);
		expect(roasterPosts(never.calls)).toHaveLength(1);
	});

	it('does nothing while signed out', async () => {
		const signedOut = Layer.succeed(TokenVault, TokenVault.of({
			getTokens: Effect.succeed(null),
			storeTokens: () => Effect.void,
			clearTokens: Effect.void,
			withFreshToken: (() => Effect.die('signed out')) as never,
			changes: undefined as never
		}));
		const { layer, calls } = probeHttp(201);
		const out = await Effect.runPromise(
			Effect.provide(
				BeanSync.pipe(Effect.flatMap((b) => b.refreshPremiumIfStale)),
				Layer.provide(BeanSyncLive, Layer.merge(layer, signedOut))
			)
		);
		expect(out).toBeNull();
		expect(calls).toHaveLength(0);
	});

	it('Test connection re-probes and stamps the probe time', async () => {
		writeSyncSettings({ premium: true, premiumCheckedAt: Date.now() - 1000 });
		const { layer, calls } = probeHttp(403);
		const out = await run(BeanSync.pipe(Effect.flatMap((b) => b.testConnection)), layer);
		expect(out).toEqual({ ok: true, premium: false });
		expect(roasterPosts(calls)).toHaveLength(1);
		expect(readSyncSettings().premium).toBe(false);
		expect(Date.now() - readSyncSettings().premiumCheckedAt!).toBeLessThan(1000);
	});

	it('sign-in stores the tokens, then refreshes the flag with the sentinel probe', async () => {
		const order: string[] = [];
		const recordingVault = Layer.succeed(TokenVault, TokenVault.of({
			getTokens: Effect.sync(() => {
				order.push('getTokens');
				return aToken;
			}),
			storeTokens: () => Effect.sync(() => void order.push('storeTokens')),
			clearTokens: Effect.void,
			withFreshToken: ((req: (t: string) => Effect.Effect<unknown, unknown>) => req('tok')) as never,
			changes: undefined as never
		}));
		const { layer, calls } = probeHttp(201);
		await Effect.runPromise(
			Effect.provide(
				completeVisualizerSignIn(aToken),
				Layer.merge(recordingVault, Layer.provide(BeanSyncLive, Layer.merge(layer, recordingVault)))
			)
		);
		expect(order[0]).toBe('storeTokens');
		expect(roasterPosts(calls)).toHaveLength(1);
		expect(readSyncSettings().premium).toBe(true);
	});
});
