/**
 * `$lib/services/shot-sync-upload.vitest` — vitest for `ShotSync.uploadShot`'s
 * payload shaping + soft post-upload PATCH (docs/53 T-12, §4.1 "no byte changes
 * to requests"). This was the last manual-only ShotSync path.
 *
 * The wasm `signatureForShot` (its value is pinned by the node:test
 * `visualizer/shot-sync.test.ts`) and the Decent-v2 export are mocked to fixed
 * outputs so the test isolates what's otherwise uncovered: the settings gating
 * (privacy / includeProfile / includeNotes), the injected `metadata.crema`
 * block, and the upload → soft-PATCH sequence (a PATCH failure must NOT fail the
 * upload). Run: `pnpm test:vitest`.
 */

import { Effect, Exit, Layer } from 'effect';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// Controllable settings + the wasm signature / v2 export, all hoisted so the
// mock factories can close over them.
const h = vi.hoisted(() => ({
	// grinderModel stays in app settings; the visualizer upload prefs moved to
	// the sync-config (the unification) — mocked separately below.
	settings: { grinderModel: '' } as Record<string, unknown>,
	cfg: {
		includeProfile: true,
		includeNotes: true,
		privacy: 'unlisted'
	} as Record<string, unknown>,
	exportJson: JSON.stringify({ profile: { title: 'P' }, metadata: { notes: 'tasty' } }),
	beanVisualizerId: null as string | null
}));

vi.mock('$lib/settings', () => ({ getSettingsStore: () => ({ current: h.settings }) }));
vi.mock('$lib/visualizer/sync-config', async (importOriginal) => ({
	...(await importOriginal<typeof import('$lib/visualizer/sync-config')>()),
	readSyncConfig: () => h.cfg
}));
vi.mock('$lib/history/v2-export', () => ({ exportStoredShotAsV2Json: () => h.exportJson }));
vi.mock('$lib/bean/store.svelte', () => ({
	getBeanStore: () => ({ getBean: () => ({ visualizerId: h.beanVisualizerId }) })
}));
vi.mock('$lib/visualizer/shot-sync-signatures', () => ({
	signatureForShot: () => 'SIG-FIXED',
	signatureForBean: () => 'b',
	signatureForRoaster: () => 'r',
	reconcileShots: () => [],
	storedShotFromWire: () => null
}));

import { ShotSync, ShotSyncLive } from './shot-sync.ts';
import { HttpClient, type HttpRequest } from './http-client.ts';
import { TokenVault } from './token-vault.ts';
import { HttpStatusError } from '../effect/errors.ts';
import type { StoredShot } from '$lib/history/model';
import type { TokenSet } from '../visualizer/oauth.ts';

function mkHttp(handler: (req: HttpRequest) => { ok: true; status?: number; json?: unknown } | { ok: false; status: number }) {
	const calls: HttpRequest[] = [];
	const layer = Layer.succeed(
		HttpClient,
		HttpClient.of({
			request: (req) => {
				calls.push(req);
				const r = handler(req);
				if (!r.ok) return Effect.fail(new HttpStatusError({ status: r.status, url: req.url }));
				const status = r.status ?? 200;
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

function run<A, E>(program: Effect.Effect<A, E, ShotSync>, http: Layer.Layer<HttpClient>) {
	return Effect.runPromiseExit(
		Effect.provide(program, Layer.provide(ShotSyncLive, Layer.merge(http, vault)))
	);
}

/** A minimal StoredShot — only the fields uploadShot reads matter here. */
const shot = (over: Partial<StoredShot> = {}): StoredShot =>
	({ id: 's1', bean: null, tags: [], grinderModel: null, ...over }) as unknown as StoredShot;

const postBody = (calls: HttpRequest[]) =>
	JSON.parse(calls.find((c) => c.method === 'POST')!.body as string) as Record<string, unknown>;
const patchCall = (calls: HttpRequest[]) => calls.find((c) => c.method === 'PATCH');

beforeEach(() => {
	h.settings = { grinderModel: '' };
	h.cfg = { includeProfile: true, includeNotes: true, privacy: 'unlisted' };
	h.exportJson = JSON.stringify({ profile: { title: 'P' }, metadata: { notes: 'tasty' } });
	h.beanVisualizerId = null;
});

describe('ShotSync.uploadShot — payload shaping', () => {
	it('injects metadata.crema (localId + signature) and the privacy setting', async () => {
		const { layer, calls } = mkHttp(() => ({ ok: true, json: { id: 'viz-1' } }));
		const exit = await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot()))), layer);
		expect(Exit.isSuccess(exit)).toBe(true);
		const body = postBody(calls);
		expect(body.privacy).toBe('unlisted');
		const crema = (body.metadata as Record<string, unknown>).crema as Record<string, unknown>;
		expect(crema.localId).toBe('s1');
		expect(crema.signature).toBe('SIG-FIXED');
		// Defaults keep profile + notes.
		expect(body.profile).toBeDefined();
		expect((body.metadata as Record<string, unknown>).notes).toBe('tasty');
	});

	it('drops the profile when includeProfile is off', async () => {
		h.cfg.includeProfile = false;
		const { layer, calls } = mkHttp(() => ({ ok: true, json: { id: 'viz-1' } }));
		await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot()))), layer);
		expect(postBody(calls).profile).toBeUndefined();
	});

	it('nulls the notes when includeNotes is off', async () => {
		h.cfg.includeNotes = false;
		const { layer, calls } = mkHttp(() => ({ ok: true, json: { id: 'viz-1' } }));
		await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot()))), layer);
		expect((postBody(calls).metadata as Record<string, unknown>).notes).toBeNull();
	});
});

describe('ShotSync.uploadShot — post-upload PATCH', () => {
	it('fires a follow-up PATCH carrying tag_list when the shot has tags', async () => {
		const { layer, calls } = mkHttp((req) =>
			req.method === 'POST' ? { ok: true, json: { id: 'viz-1' } } : { ok: true, json: {} }
		);
		await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot({ tags: ['espresso'] })))), layer);
		const patch = patchCall(calls);
		expect(patch).toBeDefined();
		expect(patch!.url).toContain('/shots/viz-1');
		const shotBody = (JSON.parse(patch!.body as string) as { shot: Record<string, unknown> }).shot;
		expect(shotBody.tag_list).toEqual(['espresso']);
	});

	it('does NOT fire a PATCH when there is nothing to patch', async () => {
		const { layer, calls } = mkHttp(() => ({ ok: true, json: { id: 'viz-1' } }));
		await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot()))), layer);
		expect(patchCall(calls)).toBeUndefined();
	});

	it('keeps the upload successful even when the follow-up PATCH fails (soft)', async () => {
		const { layer, calls } = mkHttp((req) =>
			req.method === 'POST' ? { ok: true, json: { id: 'viz-1' } } : { ok: false, status: 500 }
		);
		const exit = await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot({ tags: ['x'] })))), layer);
		expect(Exit.isSuccess(exit)).toBe(true); // PATCH 500 swallowed
		expect(patchCall(calls)).toBeDefined(); // it WAS attempted
		if (Exit.isSuccess(exit)) expect(exit.value.visualizerId).toBe('viz-1');
	});

	it('fails ResponseDecodeError when upload returns no id', async () => {
		const { layer } = mkHttp(() => ({ ok: true, json: {} }));
		const exit = await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot()))), layer);
		expect(Exit.isFailure(exit)).toBe(true);
	});
});
