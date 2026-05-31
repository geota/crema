/**
 * `$lib/services/shot-sync-unsynced.vitest` — vitest for `ShotSync.uploadUnsyncedShots`'s
 * EF1 self-heal: a `POST /shots/upload` that returns a 2xx with no parseable
 * `id` (→ `ResponseDecodeError`) means the shot is ALREADY on the server but
 * never got its `visualizerId` bound locally. Re-POSTing it (the naive "enqueue
 * create" retry) would create a real duplicate on the account, so instead we
 * PULL the shots updated around this one and let `reconcileShots` bind the local
 * to its freshly-created remote by signature.
 *
 * These two tests pin that contract: an ambiguous-success upload (a) NEVER
 * enqueues a `create` op (which would re-POST → duplicate), and (b) binds via
 * the self-heal pull when the reconcile matches. The wasm reconcile + signature
 * are mocked to fixed outputs (their byte-values are pinned by the node:test
 * `visualizer/shot-sync.test.ts`); we exercise the orchestration around them.
 * Run: `pnpm test:vitest`.
 */

import { Effect, Exit, Layer } from 'effect';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReconcileAction } from '$lib/visualizer/shot-sync-signatures';
import { initTestWasm } from '$lib/wasm/test-init';

// The self-heal pull materialises rows through the wasm-backed
// `wireShotFromDetail` (CORE1; not mocked here), so init the bundle first.
beforeAll(async () => {
	await initTestWasm();
});

// Hoisted controllables: per test, what the (mocked) wasm `reconcileShots`
// returns for the self-heal pull, plus the queue + sync-log spies (hoisted so
// the `vi.mock` factories below — which run before module init — can close over
// them without a TDZ error).
const h = vi.hoisted(() => ({
	reconcile: [] as ReconcileAction[],
	enqueueEntry: vi.fn(),
	appendSyncLog: vi.fn()
}));

vi.mock('$lib/settings', () => ({
	getSettingsStore: () => ({
		current: { visualizerIncludeProfile: true, visualizerIncludeNotes: true, visualizerPrivacy: 'unlisted', grinderModel: '' }
	})
}));
vi.mock('$lib/history/v2-export', () => ({
	exportStoredShotAsV2Json: () => JSON.stringify({ profile: { title: 'P' }, metadata: {} })
}));
vi.mock('$lib/bean/store.svelte', () => ({ getBeanStore: () => ({ getBean: () => null }) }));
vi.mock('$lib/bean/visualizer-sync', () => ({ roastLevelToWire: () => null }));
vi.mock('$lib/visualizer/shot-sync-signatures', () => ({
	signatureForShot: () => 'SIG',
	signatureForBean: () => 'b',
	signatureForRoaster: () => 'r',
	reconcileShots: () => h.reconcile,
	storedShotFromWire: () => null
}));

// Capture queue + sync-log writes so we can assert "never enqueued a create".
vi.mock('./queue-store.ts', () => ({ enqueueEntry: h.enqueueEntry }));
vi.mock('$lib/visualizer/sync-config', () => ({ appendSyncLog: h.appendSyncLog }));

import { ShotSync, ShotSyncLive } from './shot-sync.ts';
import { HttpClient, type HttpRequest } from './http-client.ts';
import { TokenVault } from './token-vault.ts';
import type { StoredShot } from '$lib/history/model';
import type { HistoryStore } from '$lib/history/store.svelte';
import type { TokenSet } from '../visualizer/oauth.ts';

/** A fake HttpClient driven by `handler`; every reply here is a 2xx. */
function mkHttp(handler: (req: HttpRequest) => unknown) {
	const calls: HttpRequest[] = [];
	const layer = Layer.succeed(
		HttpClient,
		HttpClient.of({
			request: (req) => {
				calls.push(req);
				return Effect.succeed(
					new Response(JSON.stringify(handler(req) ?? {}), {
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

/** A minimal, mutable fake HistoryStore — only the methods the loop touches. */
function mkHistory(initial: StoredShot[]) {
	const map = new Map(initial.map((s) => [s.id, { ...s }]));
	const store = {
		get all() {
			return [...map.values()];
		},
		get: (id: string) => map.get(id),
		bindVisualizerId: (id: string, visualizerId: string) => {
			const s = map.get(id);
			if (s) (s as { visualizerId?: string | null }).visualizerId = visualizerId;
		},
		insertPulled: (s: StoredShot) => map.set(s.id, { ...s }),
		setTags: () => {},
		backfillTelemetry: () => {},
		purgeTombstone: () => {}
	};
	return store as unknown as HistoryStore;
}

const shot = (over: Partial<StoredShot> = {}): StoredShot =>
	({ id: 's1', completedAt: 1_000_000, profileName: 'P', visualizerId: null, bean: null, tags: [], grinderModel: null, ...over }) as unknown as StoredShot;

/** POST upload → 2xx with NO id; the self-heal list page carries one summary. */
const handler = (req: HttpRequest): unknown => {
	if (req.method === 'POST' && req.url.endsWith('/shots/upload')) return {}; // 2xx, no id
	if (req.url.includes('/shots?'))
		return { data: [{ id: 'viz-1', clock: 1000, updated_at: 2000 }], paging: { count: 1, page: 1, limit: 50, pages: 1 } };
	return {}; // detail GET /shots/viz-1
};

function run(history: HistoryStore) {
	const { layer } = mkHttp(handler);
	const program = ShotSync.pipe(Effect.flatMap((s) => s.uploadUnsyncedShots(history)));
	return Effect.runPromiseExit(Effect.provide(program, Layer.provide(ShotSyncLive, Layer.merge(layer, vault))));
}

beforeEach(() => {
	h.reconcile = [];
	h.enqueueEntry.mockClear();
	h.appendSyncLog.mockClear();
});

describe('ShotSync.uploadUnsyncedShots — EF1 self-heal on 2xx-no-id', () => {
	it('binds via the self-heal pull instead of re-POSTing (no duplicate enqueue)', async () => {
		// The reconcile pull matches the just-uploaded shot → emits a bind.
		h.reconcile = [{ kind: 'bind', localId: 's1', visualizerId: 'viz-1', remote: { id: 'viz-1' } as never }];
		const history = mkHistory([shot()]);

		const exit = await run(history);
		expect(Exit.isSuccess(exit)).toBe(true);

		// The local row is now bound to its freshly-created remote.
		expect(history.get('s1')?.visualizerId).toBe('viz-1');
		// Crucially, we did NOT enqueue a `create` op (which would re-POST → dupe).
		expect(h.enqueueEntry).not.toHaveBeenCalled();
		// And it is logged as a push (the bind landed).
		expect(h.appendSyncLog.mock.calls.some(([e]) => e.direction === 'push' && e.id === 's1')).toBe(true);
	});

	it('does NOT enqueue a create when the self-heal pull finds no match (avoids duplicate)', async () => {
		// Reconcile finds nothing to bind (e.g. the pull lagged the write).
		h.reconcile = [];
		const history = mkHistory([shot()]);

		const exit = await run(history);
		expect(Exit.isSuccess(exit)).toBe(true);

		// Still unbound — but a `ResponseDecodeError` is NOT recoverable, so the loop
		// must NOT enqueue a create (re-POST would duplicate the server-side shot).
		expect(history.get('s1')?.visualizerId).toBeFalsy();
		expect(h.enqueueEntry).not.toHaveBeenCalled();
		expect(h.appendSyncLog.mock.calls.some(([e]) => e.direction === 'skip' && e.id === 's1')).toBe(true);
	});
});
