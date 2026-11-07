/**
 * `#lib/services/shot-sync-unsynced.vitest` — vitest for
 * `ShotSync.uploadUnsyncedShots`. A failure that may have landed anyway goes
 * back on the queue and re-POSTs: Visualizer's `sha` hands the same row back, so
 * nothing here pulls to find it. The wasm reconcile + signature are mocked to
 * fixed outputs; we exercise the orchestration around them.
 * Run: `pnpm test:vitest`.
 */

import { Effect, Exit, Layer } from 'effect';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReconcileAction } from '#lib/visualizer/shot-sync-signatures.js';
import { initTestWasm } from '#lib/testing/test-init.js';

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
	appendSyncLog: vi.fn(),
	/** The shot id whose export throws, as `profile: "junk"` does. */
	throwsOn: null as string | null,
	/** `uploadsHeld`, and the cut-off `excludedFromUpload` reads. */
	held: false,
	excludedBefore: 0
}));

vi.mock('#lib/settings/index.js', () => ({
	getSettingsStore: () => ({
		current: { visualizerIncludeProfile: true, visualizerIncludeNotes: true, visualizerPrivacy: 'unlisted', grinderModel: '' }
	})
}));
vi.mock('#lib/history/v2-export.js', () => ({
	exportStoredShotAsV2Json: (shot: { id: string }) => {
		if (shot.id === h.throwsOn) throw new TypeError('junk profile');
		return JSON.stringify({ profile: { title: 'P' }, metadata: {} });
	}
}));
vi.mock('#lib/bean/store.svelte.js', () => ({ getBeanStore: () => ({ getBean: () => null }) }));
vi.mock('#lib/bean/visualizer-sync.js', () => ({ roastLevelToWire: () => null }));
vi.mock('#lib/visualizer/shot-sync-signatures.js', () => ({
	signatureForShot: () => 'SIG',
	signatureForBean: () => 'b',
	signatureForRoaster: () => 'r',
	reconcileShots: () => h.reconcile,
	storedShotFromWire: () => null
}));

// Capture queue + sync-log writes so we can assert "never enqueued a create".
vi.mock('./queue-store.ts', () => ({ enqueueEntry: h.enqueueEntry }));
// buildShotPayload reads the upload prefs from the sync-config (the unification).
vi.mock('#lib/visualizer/sync-config.js', () => ({
	uploadsHeld: () => h.held,
	excludedFromUpload: (s: { completedAt: number }) => s.completedAt < h.excludedBefore,
	appendSyncLog: h.appendSyncLog,
	readSyncConfig: () => ({ includeProfile: true, includeNotes: true, privacy: 'unlisted' })
}));

import { describeUnsyncedStop, ShotSync, ShotSyncLive, type UnsyncedUploadResult } from './shot-sync.ts';
import { HttpStatusError, NetworkError } from '../effect/errors.ts';
import {
	markUnboundTombstone,
	markUploadAttempted,
	uploadAttempted,
	uploadReturned
} from '#lib/visualizer/upload-marks.js';
import { HttpClient, type HttpRequest } from './http-client.ts';
import { TokenVault } from './token-vault.ts';
import type { StoredShot } from '#lib/history/model.js';
import type { HistoryStore } from '#lib/history/store.svelte.js';
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
		get rawAll() {
			return [...map.values()];
		},
		get: (id: string) => map.get(id),
		bindVisualizerId: (id: string, visualizerId: string) => {
			const s = map.get(id);
			if (s) (s as { visualizerId?: string | null }).visualizerId = visualizerId;
		},
		seriesReady: async () => true,
		insertPulled: (s: StoredShot) => map.set(s.id, { ...s }),
		setTags: () => {},
		backfillTelemetry: () => {},
		purgeTombstone: () => {}
	};
	return store as unknown as HistoryStore;
}

const shot = (over: Partial<StoredShot> = {}): StoredShot =>
	({
		id: 's1',
		completedAt: 1_000_000,
		profileName: 'P',
		visualizerId: null,
		bean: null,
		tags: [],
		grinderModel: null,
		record: { duration: 30_000, samples: [{ t: 0 }] },
		...over
	}) as unknown as StoredShot;

/** POST upload → 2xx with NO id. */
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
	localStorage.clear();
	h.throwsOn = null;
	h.held = false;
	h.excludedBefore = 0;
	h.reconcile = [];
	h.enqueueEntry.mockClear();
	h.appendSyncLog.mockClear();
});

describe('ShotSync.uploadUnsyncedShots — a 2xx with no id', () => {
	it('leaves the shot in the backlog, with no pull to find it', async () => {
		const history = mkHistory([shot()]);
		const { layer, calls } = mkHttp(handler);
		const program = ShotSync.pipe(Effect.flatMap((s) => s.uploadUnsyncedShots(history)));
		const exit = await Effect.runPromiseExit(
			Effect.provide(program, Layer.provide(ShotSyncLive, Layer.merge(layer, vault)))
		);
		expect(Exit.isSuccess(exit)).toBe(true);

		expect(history.get('s1')?.visualizerId).toBeFalsy();
		expect(calls.filter((c) => c.url.includes('/shots?'))).toEqual([]);
		// Nothing proves it landed, and the next pass re-POSTs to the same row.
		expect(h.appendSyncLog.mock.calls.some(([e]) => e.direction === 'skip' && e.id === 's1')).toBe(true);
	});
});

describe('the upload attempt mark (#331)', () => {
	const posting = (reply: () => Effect.Effect<Response, unknown>) => {
		const seen: boolean[] = [];
		const layer = Layer.succeed(
			HttpClient,
			HttpClient.of({
				request: () => {
					seen.push(uploadAttempted('s1'));
					return reply() as never;
				}
			})
		);
		return { seen, layer };
	};
	const upload = (layer: Layer.Layer<HttpClient>) =>
		Effect.runPromiseExit(
			Effect.provide(
				ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot()))),
				Layer.provide(ShotSyncLive, Layer.merge(layer, vault))
			)
		);

	it('is down before the POST goes, and stays when nothing says it failed', async () => {
		const { seen, layer } = posting(() =>
			Effect.fail(new HttpStatusError({ status: 503, url: 'u', body: '' }))
		);
		await upload(layer);
		expect(seen).toEqual([true]);
		expect(uploadAttempted('s1')).toBe(true);
	});

	it('stays through a dropped connection', async () => {
		const { layer } = posting(() => Effect.fail(new NetworkError({ cause: 'x', url: 'u' })));
		await upload(layer);
		expect(uploadAttempted('s1')).toBe(true);
	});

	it('goes when Visualizer refuses the shot', async () => {
		const { layer } = posting(() =>
			Effect.fail(new HttpStatusError({ status: 422, url: 'u', body: '{"error":"bad file"}' }))
		);
		await upload(layer);
		expect(uploadAttempted('s1')).toBe(false);
	});
});

describe('ShotSync.pullAndReconcileShots — unbound tombstones (#331)', () => {
	const pull = (history: HistoryStore, reply: (req: HttpRequest) => Effect.Effect<Response, unknown>, since = 0) => {
		const layer = Layer.succeed(HttpClient, HttpClient.of({ request: (req) => reply(req) as never }));
		return Effect.runPromise(
			Effect.provide(
				ShotSync.pipe(Effect.flatMap((s) => s.pullAndReconcileShots(history, since))),
				Layer.provide(ShotSyncLive, Layer.merge(layer, vault))
			)
		);
	};
	const json = (body: unknown) => Effect.succeed(new Response(JSON.stringify(body)));
	const emptyList = () => json({ data: [], paging: { count: 0, page: 1, limit: 50, pages: 1 } });
	const oneRow = (req: HttpRequest) =>
		req.url.includes('/shots?')
			? json({ data: [{ id: 'viz-1', clock: 1000, updated_at: 2000 }], paging: { count: 1, page: 1, limit: 50, pages: 1 } })
			: Effect.fail(new HttpStatusError({ status: 503, url: req.url }));
	const withPurge = (history: HistoryStore) => {
		const purge = vi.fn();
		(history as unknown as { purgeTombstone: typeof purge }).purgeTombstone = purge;
		return purge;
	};

	it('unbound_tombstone_expires_after_complete_pull', async () => {
		markUnboundTombstone('gone', false);
		const history = mkHistory([]);
		const purge = withPurge(history);
		await pull(history, emptyList);
		expect(purge).toHaveBeenCalledWith('gone');
	});

	it('a cursor past the attempt leaves it: the row may be behind the cursor', async () => {
		markUploadAttempted('gone');
		uploadReturned('gone');
		markUnboundTombstone('gone', false);
		const history = mkHistory([]);
		const purge = withPurge(history);
		const since = Date.now() - 60_000;
		await pull(history, emptyList, since);
		expect(purge).not.toHaveBeenCalled();
	});

	it('a cursor a day older than the attempt speaks for it', async () => {
		markUploadAttempted('gone');
		uploadReturned('gone');
		markUnboundTombstone('gone', false);
		const history = mkHistory([]);
		const purge = withPurge(history);
		await pull(history, emptyList, Date.now() - 2 * 24 * 3_600_000);
		expect(purge).toHaveBeenCalledWith('gone');
	});

	it('with no attempt time only a walk from the start can end it', async () => {
		markUnboundTombstone('gone', false);
		const history = mkHistory([]);
		const purge = withPurge(history);
		await pull(history, emptyList, 1);
		expect(purge).not.toHaveBeenCalled();
	});

	it('a pull already running when it was written leaves it', async () => {
		const history = mkHistory([]);
		const purge = withPurge(history);
		await pull(history, () => {
			markUnboundTombstone('gone', false);
			return emptyList();
		});
		expect(purge).not.toHaveBeenCalled();
	});

	it('a pull that missed a row leaves it', async () => {
		markUnboundTombstone('gone', false);
		const history = mkHistory([]);
		const purge = withPurge(history);
		await pull(history, oneRow);
		expect(purge).not.toHaveBeenCalled();
	});

	it('leaves one with its POST still out', async () => {
		markUploadAttempted('gone');
		markUnboundTombstone('gone', false);
		const history = mkHistory([]);
		const purge = withPurge(history);
		await pull(history, emptyList);
		expect(purge).not.toHaveBeenCalled();
		uploadReturned('gone');
	});
});

describe('ShotSync.uploadUnsyncedShots — the stored series (#330)', () => {
	it('reads the backlog only after the series have landed', async () => {
		h.reconcile = [];
		const history = mkHistory([shot()]);
		let land: ((ready: boolean) => void) | undefined;
		const waiting = new Promise<boolean>((resolve) => (land = resolve));
		(history as unknown as { seriesReady: () => Promise<boolean> }).seriesReady = () => waiting;
		const all = vi.spyOn(history, 'all', 'get');

		const running = run(history);
		await Promise.resolve();
		expect(all).not.toHaveBeenCalled();

		land?.(true);
		await running;
		expect(all).toHaveBeenCalled();
	});
});

describe('ShotSync.uploadUnsyncedShots — shots with no telemetry (#330)', () => {
	it('leaves them out of the pass, with one log line for all of them', async () => {
		h.reconcile = [];
		const bare = (id: string) => shot({ id, record: { duration: 30_000, samples: [] } } as Partial<StoredShot>);
		const history = mkHistory([bare('a'), bare('b'), bare('c')]);
		h.appendSyncLog.mockClear();

		await run(history);

		expect(h.appendSyncLog).toHaveBeenCalledTimes(1);
		expect(h.appendSyncLog.mock.calls[0][0]).toMatchObject({
			direction: 'skip',
			error: '3 shots have no telemetry to upload.'
		});
		expect(h.enqueueEntry).not.toHaveBeenCalled();
	});
});

describe('ShotSync.uploadUnsyncedShots — free-plan daily quota (visualizer 3e9ba33c)', () => {
	// The exact reply `POST /shots/upload` sends at the cap.
	const QUOTA_BODY = JSON.stringify({
		error:
			"Could not save the provided file. You've reached your daily limit of 30 shots. Please consider upgrading to a premium account."
	});

	/** Every upload POST fails with `status` + `body`. */
	function runFailing(history: HistoryStore, status: number, body: string) {
		const posts: HttpRequest[] = [];
		const layer = Layer.succeed(
			HttpClient,
			HttpClient.of({
				request: (req) => {
					posts.push(req);
					return Effect.fail(new HttpStatusError({ status, url: req.url, body }));
				}
			})
		);
		const program = ShotSync.pipe(Effect.flatMap((s) => s.uploadUnsyncedShots(history)));
		return Effect.runPromise(
			Effect.provide(program, Layer.provide(ShotSyncLive, Layer.merge(layer, vault)))
		).then((result: UnsyncedUploadResult) => ({ result, posts }));
	}

	const three = () => mkHistory([shot({ id: 's1' }), shot({ id: 's2' }), shot({ id: 's3' })]);

	it('stops the backlog loop on the quota 422, queues nothing and keeps the rest unsynced', async () => {
		const history = three();
		const { result, posts } = await runFailing(history, 422, QUOTA_BODY);

		// One POST, then the loop stopped — no hammering the cap with s2, s3.
		expect(posts).toHaveLength(1);
		expect(result).toEqual({ uploaded: 0, stopped: { reason: 'quota', limit: 30 } });
		// Not queued for a timed retry; every shot stays eligible for the next pass.
		expect(h.enqueueEntry).not.toHaveBeenCalled();
		expect(history.all.every((s) => !s.visualizerId)).toBe(true);
		// The user is told why.
		expect(describeUnsyncedStop(result)).toEqual({
			kind: 'info',
			message: "Visualizer's free plan uploads up to 30 shots a day — the rest will upload tomorrow."
		});
	});

	it('keeps going past an ordinary 422 (a bad file is not the quota)', async () => {
		const history = three();
		const bad = JSON.stringify({ error: "Could not save the provided file. Profile file can't be blank" });
		const { result, posts } = await runFailing(history, 422, bad);
		expect(posts).toHaveLength(3);
		expect(result.stopped).toBeNull();
		expect(describeUnsyncedStop(result)).toBeNull();
	});

	it('stops on a blocked account and says why, not that Premium is needed', async () => {
		const history = three();
		const disabled = '{"error":"This account has been disabled. Contact miha@visualizer.coffee for more information."}';
		const { result, posts } = await runFailing(history, 403, disabled);
		expect(posts).toHaveLength(1);
		expect(describeUnsyncedStop(result)).toEqual({
			kind: 'error',
			message: 'This Visualizer account is disabled.'
		});
	});

	it('walks on past a row refused for itself', async () => {
		const history = three();
		const { result, posts } = await runFailing(history, 403, '{"error":"You are not authorized to perform this action."}');
		expect(posts).toHaveLength(3);
		expect(result.stopped).toBeNull();
	});

	it('unknown_identity_holds_uploads: a held account POSTs nothing and says why', async () => {
		h.held = true;
		const { result, posts } = await runFailing(three(), 500, '');
		expect(posts).toHaveLength(0);
		expect(describeUnsyncedStop(result)).toEqual({
			kind: 'info',
			message: 'Visualizer is waiting on your account. See Settings → Sharing.'
		});
	});

	it('decline_upload_lets_new_shots_flow: only the shots of then stay back', async () => {
		h.excludedBefore = 2_000_000;
		const history = mkHistory([shot({ id: 'old', completedAt: 1_000_000 }), shot({ id: 'new', completedAt: 3_000_000 })]);
		const { posts } = await runFailing(history, 500, '');
		expect(posts).toHaveLength(1);
	});

	it('stops on a 429 and queues that shot for a delayed retry', async () => {
		const history = three();
		const { result, posts } = await runFailing(history, 429, '{"error":"Too many requests. Please try again later."}');
		expect(posts).toHaveLength(1);
		expect(result.stopped).toEqual({ reason: 'rate-limited' });
		expect(h.enqueueEntry).toHaveBeenCalledTimes(1);
		const [entry] = h.enqueueEntry.mock.calls[0];
		expect(entry).toMatchObject({ entity: 'shot', id: 's1', op: 'create' });
		expect(entry.delayMs).toBeGreaterThanOrEqual(60_000);
	});
});

describe('uploadUnsyncedShots — a shot that throws (#329)', () => {
	it('skips that shot and uploads the rest of the pass', async () => {
		h.throwsOn = 'junk';
		const history = mkHistory([shot({ id: 'junk' }), shot({ id: 'after', completedAt: 2_000_000 })]);
		const { layer, calls } = mkHttp((req) =>
			req.method === 'POST' && req.url.endsWith('/shots/upload') ? { id: 'viz-2' } : {}
		);
		const program = ShotSync.pipe(Effect.flatMap((s) => s.uploadUnsyncedShots(history)));
		const exit = await Effect.runPromiseExit(
			Effect.provide(program, Layer.provide(ShotSyncLive, Layer.merge(layer, vault)))
		);

		expect(Exit.isSuccess(exit)).toBe(true);
		expect(Exit.isSuccess(exit) && exit.value.uploaded).toBe(1);
		expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
		expect(history.get('after')?.visualizerId).toBe('viz-2');
		expect(h.enqueueEntry).not.toHaveBeenCalled();
		expect(h.appendSyncLog).toHaveBeenCalledWith(
			expect.objectContaining({ direction: 'skip', id: 'junk' })
		);
	});
});
