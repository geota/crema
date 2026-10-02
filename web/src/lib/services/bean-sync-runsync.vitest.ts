/**
 * `$lib/services/bean-sync-runsync.vitest` — vitest for `BeanSync.runSync`, the
 * bidirectional bean/roaster sync ported off `bean/visualizer-sync.ts` so
 * token-store can be retired. This orchestration had zero automated coverage.
 *
 * Covers: pull-new (remote roaster/bag → local upsert), bind-by-name (a local
 * row with no visualizerId binds to the matching remote rather than duplicating),
 * push (local create → POST → bind id), the premium downshift (a 403 on the
 * first write flips the run read-only + caches premium=false), and the
 * not-signed-in early return. As of CORE4 the pull-reconcile runs through the
 * real wasm kernel (`reconcileRoasters`/`reconcileBeans`), and
 * beanFromWire/roasterFromWire/beanToWire are real too — so the test exercises
 * the actual cross-shell matching. The `shot-sync-signatures` mock below is now
 * vestigial (bean-sync no longer imports it) but harmless. Run: `pnpm test:vitest`.
 */

import { Effect, Layer } from 'effect';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('$lib/visualizer/shot-sync-signatures', () => ({
	signatureForRoaster: ({ name }: { name: string }) => `rs:${name.trim().toLowerCase()}`,
	signatureForBean: ({ name }: { name: string }) => `bs:${(name ?? '').trim().toLowerCase()}`,
	signatureForShot: () => '',
	reconcileShots: () => [],
	storedShotFromWire: () => null
}));

import { BeanSync, BeanSyncLive } from './bean-sync.ts';
import { HttpClient, type HttpRequest } from './http-client.ts';
import { TokenVault } from './token-vault.ts';
import { HttpStatusError } from '../effect/errors.ts';
import { blankBean, blankRoaster, type Bean, type Roaster } from '$lib/bean';
import type { BeanLibraryStore } from '$lib/bean/store.svelte';
import type { TokenSet } from '../visualizer/oauth.ts';
import { initTestWasm } from '$lib/testing/test-init';

// `runSync` decodes each remote row through the wasm-backed `beanFromWire` /
// `roasterFromWire` and encodes pushes through `beanToWire` (CORE1), so the
// bundle must be initialised first. (`shot-sync-signatures` stays mocked.)
beforeAll(async () => {
	await initTestWasm();
});

type Reply = { ok: true; json?: unknown } | { ok: false; status: number };

function mkHttp(handler: (method: string, url: string) => Reply) {
	const calls: { method: string; url: string; body?: unknown }[] = [];
	const layer = Layer.succeed(
		HttpClient,
		HttpClient.of({
			request: (req: HttpRequest) => {
				const method = req.method ?? 'GET';
				calls.push({ method, url: req.url, body: req.body ? JSON.parse(req.body) : undefined });
				const r = handler(method, req.url);
				if (!r.ok) return Effect.fail(new HttpStatusError({ status: r.status, url: req.url }));
				return Effect.succeed(
					new Response(JSON.stringify(r.json ?? {}), {
						status: 200,
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
function mkVault(token: TokenSet | null) {
	return Layer.succeed(
		TokenVault,
		TokenVault.of({
			getTokens: Effect.succeed(token),
			storeTokens: () => Effect.void,
			clearTokens: Effect.void,
			withFreshToken: ((req: (t: string) => Effect.Effect<unknown, unknown>) => req('tok')) as never,
			changes: undefined as never
		})
	);
}

/** Minimal in-memory BeanLibraryStore covering the methods runSync touches. */
function mkLibrary(init: { roasters?: Roaster[]; beans?: Bean[] } = {}) {
	const roasters = [...(init.roasters ?? [])];
	const beans = [...(init.beans ?? [])];
	const lib = {
		get roasters() {
			return roasters;
		},
		get beans() {
			return beans;
		},
		findRoasterByName: (n: string) => roasters.find((r) => r.name.toLowerCase() === n.toLowerCase()),
		getRoaster: (id: string) => roasters.find((r) => r.id === id),
		getBean: (id: string) => beans.find((b) => b.id === id) ?? null,
		updateRoaster: (id: string, patch: Partial<Roaster>) => {
			const r = roasters.find((x) => x.id === id);
			if (r) Object.assign(r, patch);
		},
		upsertRoaster: (r: Roaster) => {
			const i = roasters.findIndex((x) => x.id === r.id);
			if (i >= 0) roasters[i] = r;
			else roasters.push(r);
		},
		replaceBean: (b: Bean) => {
			const i = beans.findIndex((x) => x.id === b.id);
			if (i >= 0) beans[i] = b;
			else beans.push(b);
		},
		upsertBean: (b: Bean) => {
			const i = beans.findIndex((x) => x.id === b.id);
			if (i >= 0) beans[i] = b;
			else beans.push(b);
		},
		updateBean: (id: string, patch: Partial<Bean>) => {
			const b = beans.find((x) => x.id === id);
			if (b) Object.assign(b, patch);
		}
	};
	return lib as unknown as BeanLibraryStore;
}

function run(library: BeanLibraryStore, http: Layer.Layer<HttpClient>, token: TokenSet | null = aToken) {
	return Effect.runPromise(
		Effect.provide(
			BeanSync.pipe(Effect.flatMap((b) => b.runSync(library))),
			Layer.provide(BeanSyncLive, Layer.merge(http, mkVault(token)))
		)
	);
}

const noBags = (method: string, url: string): Reply =>
	method === 'GET' && url.includes('/coffee_bags') ? { ok: true, json: { data: [], paging: { pages: 1 } } } : { ok: true, json: { data: [], paging: { pages: 1 } } };

beforeEach(() => {
	localStorage.clear();
});

describe('BeanSync.runSync — pull', () => {
	it('pulls a new remote roaster into the local library', async () => {
		const lib = mkLibrary();
		const { layer } = mkHttp((method, url) => {
			if (method === 'GET' && url.includes('/roasters'))
				return { ok: true, json: { data: [{ id: 'r1', name: 'Acme' }], paging: { pages: 1 } } };
			return noBags(method, url);
		});
		const result = await run(lib, layer);
		expect(result.ok).toBe(true);
		expect(result.pulled).toBe(1);
		expect(lib.roasters.find((r) => r.visualizerId === 'r1')?.name).toBe('Acme');
	});

	it('pulls a new remote bag into the local library', async () => {
		const lib = mkLibrary();
		const { layer } = mkHttp((method, url) => {
			if (method === 'GET' && url.includes('/coffee_bags'))
				return { ok: true, json: { data: [{ id: 'b1', name: 'Yirg' }], paging: { pages: 1 } } };
			return { ok: true, json: { data: [], paging: { pages: 1 } } };
		});
		const result = await run(lib, layer);
		expect(result.pulled).toBe(1);
		expect(lib.beans.find((b) => b.visualizerId === 'b1')?.name).toBe('Yirg');
	});

	it('binds a local roaster to the matching remote by name (no duplicate)', async () => {
		const local = { ...blankRoaster('Acme'), visualizerId: null };
		const lib = mkLibrary({ roasters: [local] });
		const { layer } = mkHttp((method, url) => {
			if (method === 'GET' && url.includes('/roasters'))
				return { ok: true, json: { data: [{ id: 'r1', name: 'Acme' }], paging: { pages: 1 } } };
			return { ok: true, json: { data: [], paging: { pages: 1 } } };
		});
		await run(lib, layer);
		expect(lib.roasters).toHaveLength(1);
		expect(lib.roasters[0].visualizerId).toBe('r1');
	});
});

describe('BeanSync.runSync — push + premium', () => {
	it('pushes a local roaster (premium) and binds the returned id', async () => {
		localStorage.setItem('crema.beans.sync.v1', JSON.stringify({ lastSyncAt: 0, premium: true }));
		const local = { ...blankRoaster('Acme'), visualizerId: null };
		const lib = mkLibrary({ roasters: [local] });
		const { layer, calls } = mkHttp((method, url) => {
			if (method === 'POST' && url.includes('/roasters')) return { ok: true, json: { id: 'r9' } };
			return { ok: true, json: { data: [], paging: { pages: 1 } } };
		});
		const result = await run(lib, layer);
		expect(result.pushed).toBe(1);
		expect(lib.roasters[0].visualizerId).toBe('r9');
		expect(calls.some((c) => c.method === 'POST' && c.url.includes('/roasters'))).toBe(true);
	});

	it('downshifts to read-only on a 403 and caches premium=false', async () => {
		const local = { ...blankRoaster('Acme'), visualizerId: null };
		const lib = mkLibrary({ roasters: [local] });
		const { layer } = mkHttp((method, url) => {
			if (method === 'POST' && url.includes('/roasters')) return { ok: false, status: 403 };
			return { ok: true, json: { data: [], paging: { pages: 1 } } };
		});
		const result = await run(lib, layer);
		expect(result.premiumLocked).toBe(true);
		const cached = JSON.parse(localStorage.getItem('crema.beans.sync.v1') ?? '{}');
		expect(cached.premium).toBe(false);
	});
});

describe('BeanSync.runSync — guards', () => {
	it('returns an error and does nothing when not signed in', async () => {
		const lib = mkLibrary();
		const { layer, calls } = mkHttp(() => ({ ok: true, json: { data: [], paging: { pages: 1 } } }));
		const result = await run(lib, layer, null);
		expect(result.ok).toBe(false);
		expect(result.error).toMatch(/sign in/i);
		expect(calls).toHaveLength(0);
	});
});

describe('BeanSync.runSync — Visualizer catalogue links', () => {
	const premium = () =>
		localStorage.setItem('crema.beans.sync.v1', JSON.stringify({ lastSyncAt: 0, premium: true }));

	it('sends canonical_coffee_bag_id on the bag POST and the bean-derived roaster link on the roaster POST', async () => {
		premium();
		const roaster = { ...blankRoaster('Onyx'), visualizerId: null };
		const bean: Bean = {
			...blankBean('bean:1'),
			name: 'Hambela',
			roasterId: roaster.id,
			canonicalCoffeeBagId: 'cb-1',
			canonicalRoasterId: 'cr-1'
		};
		const lib = mkLibrary({ roasters: [roaster], beans: [bean] });
		const { layer, calls } = mkHttp((method, url) => {
			if (method === 'POST' && url.includes('/roasters')) return { ok: true, json: { id: 'vr-1' } };
			if (method === 'POST' && url.includes('/coffee_bags')) return { ok: true, json: { id: 'vb-1' } };
			return { ok: true, json: { data: [], paging: { pages: 1 } } };
		});
		await run(lib, layer);
		const rPost = calls.find((c) => c.method === 'POST' && c.url.endsWith('/roasters'));
		expect((rPost?.body as { roaster: Record<string, unknown> }).roaster.canonical_roaster_id).toBe('cr-1');
		const bPost = calls.find((c) => c.method === 'POST' && c.url.endsWith('/coffee_bags'));
		const bag = (bPost?.body as { coffee_bag: Record<string, unknown> }).coffee_bag;
		expect(bag.canonical_coffee_bag_id).toBe('cb-1');
		expect(bag.roaster_id).toBe('vr-1');
	});

	it('PATCHes the catalogue link onto an already-synced, unlinked roaster (premium only)', async () => {
		premium();
		const roaster = { ...blankRoaster('Onyx'), visualizerId: 'vr-1', catalogueRoasterId: 'cr-1' };
		const lib = mkLibrary({ roasters: [roaster] });
		const { layer, calls } = mkHttp((method, url) => {
			if (method === 'GET' && url.includes('/roasters'))
				return { ok: true, json: { data: [{ id: 'vr-1', name: 'Onyx' }], paging: { pages: 1 } } };
			return { ok: true, json: { data: [], paging: { pages: 1 } } };
		});
		await run(lib, layer);
		const patch = calls.find((c) => c.method === 'PATCH' && c.url.endsWith('/roasters/vr-1'));
		expect((patch?.body as { roaster: Record<string, unknown> }).roaster.canonical_roaster_id).toBe('cr-1');
		expect(lib.roasters[0].catalogueRoasterId).toBe('cr-1');
	});

	it('never writes the link on a free account', async () => {
		localStorage.setItem('crema.beans.sync.v1', JSON.stringify({ lastSyncAt: 0, premium: false }));
		const roaster = { ...blankRoaster('Onyx'), visualizerId: 'vr-1', catalogueRoasterId: 'cr-1' };
		const lib = mkLibrary({ roasters: [roaster] });
		const { layer, calls } = mkHttp((method, url) => {
			if (method === 'GET' && url.includes('/roasters'))
				return { ok: true, json: { data: [{ id: 'vr-1', name: 'Onyx' }], paging: { pages: 1 } } };
			return { ok: true, json: { data: [], paging: { pages: 1 } } };
		});
		await run(lib, layer);
		expect(calls.every((c) => c.method === 'GET')).toBe(true);
	});

	it('pulls canonical_roaster_id as the catalogue link without touching the local dedup pointer', async () => {
		const roaster = {
			...blankRoaster('Onyx'),
			visualizerId: 'vr-1',
			canonicalRoasterId: 'roaster:other'
		};
		const lib = mkLibrary({ roasters: [roaster] });
		const { layer } = mkHttp((method, url) => {
			if (method === 'GET' && url.includes('/roasters'))
				return {
					ok: true,
					json: { data: [{ id: 'vr-1', name: 'Onyx', canonical_roaster_id: 'cr-5' }], paging: { pages: 1 } }
				};
			if (method === 'GET' && url.includes('/coffee_bags'))
				return {
					ok: true,
					json: {
						data: [{ id: 'vb-1', name: 'Hambela', roaster_id: 'vr-1', canonical_coffee_bag_id: 'cb-5' }],
						paging: { pages: 1 }
					}
				};
			return { ok: true, json: { data: [], paging: { pages: 1 } } };
		});
		await run(lib, layer);
		expect(lib.roasters[0].catalogueRoasterId).toBe('cr-5');
		expect(lib.roasters[0].canonicalRoasterId).toBe('roaster:other');
		expect(lib.beans.find((b) => b.visualizerId === 'vb-1')?.canonicalCoffeeBagId).toBe('cb-5');
	});
});
