/**
 * `#lib/services/upload-queue.vitest` — vitest for the `UploadQueue` drain
 * orchestration (docs/53 T-14, the store-coupled half of T-16).
 *
 * This is the behavior that was previously verified only by the docs/55 manual
 * smoke test ("kill the network mid-upload → confirm it queues and drains on
 * reconnect, and a persistently-failing op drops after exactly 3 attempts").
 * It's unreachable under the node:test runner (`upload-queue.ts` imports
 * `#lib/history` and the `ShotSync`/`BeanSync` tags), so it lives here: vitest
 * resolves `#lib`, jsdom supplies `localStorage`, the history store is mocked,
 * and fake `ShotSync` / `BeanSync` layers stand in for the network.
 *
 * Run: `cd web && pnpm test:vitest` (or `vitest run upload-queue`).
 */

import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { Effect, Layer, ManagedRuntime } from 'effect';

// runEntry reaches the history store via `#lib/history/index.js`; mock it so no real
// rune store is instantiated and we can observe the bind / purge calls.
const mockHistory = {
	seriesReady: vi.fn(async () => true),
	get: vi.fn(),
	bindVisualizerId: vi.fn(),
	purgeTombstone: vi.fn()
};
vi.mock('#lib/history/index.js', () => ({ getHistoryStore: () => mockHistory }));

import { UploadQueue, UploadQueueLive } from './upload-queue.ts';
import { ShotSync } from './shot-sync.ts';
import { BeanSync } from './bean-sync.ts';
import {
	HttpStatusError,
	NetworkError,
	NotAuthenticatedError,
	ShotWithoutSeriesError,
	TokenRefreshFailedError,
	VisualizerPremiumGatedError
} from '../effect/errors.ts';
import { enqueueEntry, readQueue } from './queue-store.ts';
import { pendingEdit, settlePendingEdit } from '#lib/visualizer/pending-edits.js';
import { readSyncConfig, updateSyncConfig } from '#lib/visualizer/sync-config.js';
import { initTestWasm } from '#lib/testing/test-init.js';
import { toast } from '#lib/components/shared/toast.svelte.js';

// The drain's recoverable-vs-drop decision goes through the wasm-backed
// `isRecoverable` (CORE5), so init the bundle first.
beforeAll(async () => {
	await initTestWasm();
});

const die = (label: string) => () => Effect.die(`unused: ${label}`);

/** A ShotSync stub — only the queue-reachable methods are real. */
function fakeShotSync(over: {
	uploadShot?: (shot: unknown) => Effect.Effect<{ visualizerId: string }, unknown>;
	deleteShot?: (id: string) => Effect.Effect<void, unknown>;
	pushPendingEdit?: (history: unknown, id: string) => Effect.Effect<unknown, unknown>;
}) {
	return Layer.succeed(
		ShotSync,
		ShotSync.of({
			uploadShot: (over.uploadShot ?? die('uploadShot')) as never,
			deleteShot: (over.deleteShot ?? die('deleteShot')) as never,
			patchEditedShot: undefined as never,
			pushPendingEdit: (over.pushPendingEdit ?? die('pushPendingEdit')) as never,
			patchShot: die('patchShot') as never,
			pullShots: die('pullShots') as never,
			fetchShotDetail: die('fetchShotDetail') as never,
			pullAllShotsSince: die('pullAllShotsSince') as never,
			applyShotReconciliation: die('applyShotReconciliation') as never,
			pullAndReconcileShots: die('pullAndReconcileShots') as never,
			uploadUnsyncedShots: die('uploadUnsyncedShots') as never
		})
	);
}

function fakeBeanSync(over: {
	deleteBean?: (id: string) => Effect.Effect<void, unknown>;
	deleteRoaster?: (id: string) => Effect.Effect<void, unknown>;
	refreshPremiumIfStale?: Effect.Effect<boolean | null>;
}) {
	return Layer.succeed(
		BeanSync,
		BeanSync.of({
			uploadBean: die('uploadBean') as never,
			uploadRoaster: die('uploadRoaster') as never,
			searchCatalogue: die('searchCatalogue') as never,
			searchCatalogueRoasters: die('searchCatalogueRoasters') as never,
			deleteBean: (over.deleteBean ?? die('deleteBean')) as never,
			deleteRoaster: (over.deleteRoaster ?? die('deleteRoaster')) as never,
			runSync: die('runSync') as never,
			fetchAccount: Effect.die('unused: fetchAccount') as never,
			refreshPremium: Effect.die('unused: refreshPremium') as never,
			refreshPremiumIfStale: (over.refreshPremiumIfStale ??
				Effect.die('unused: refreshPremiumIfStale')) as never,
			testConnection: Effect.die('unused: testConnection') as never
		})
	);
}

/** Run `q => q.drain` (or any UploadQueue program) over a fresh layer. Queue
 *  state lives in localStorage, so sequential drains see persisted progress. */
function drainWith(
	shot: Parameters<typeof fakeShotSync>[0],
	bean: Parameters<typeof fakeBeanSync>[0] = {}
) {
	const layer = Layer.provide(
		UploadQueueLive,
		Layer.merge(fakeShotSync(shot), fakeBeanSync(bean))
	);
	return Effect.runPromise(
		Effect.provide(
			UploadQueue.pipe(Effect.flatMap((q) => q.drain)),
			layer
		)
	);
}

beforeEach(() => {
	localStorage.clear();
	vi.restoreAllMocks();
	// The backoffs carry random jitter; these tests jump the clock to exact times.
	vi.spyOn(Math, 'random').mockReturnValue(0);
	mockHistory.get.mockReset();
	mockHistory.bindVisualizerId.mockReset();
	mockHistory.purgeTombstone.mockReset();
	// Online by default; the offline-guard test overrides.
	vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
});

describe('UploadQueue.drain — shot create', () => {
	it('uploads, binds the returned id, and dequeues on success', async () => {
		mockHistory.get.mockReturnValue({ id: 's1', visualizerId: undefined });
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });

		const result = await drainWith({
			uploadShot: () => Effect.succeed({ visualizerId: 'viz-1' })
		});

		expect(result.succeeded).toBe(1);
		expect(result.processed).toBe(1);
		expect(mockHistory.bindVisualizerId).toHaveBeenCalledWith('s1', 'viz-1');
		expect(readQueue().entries).toHaveLength(0);
	});

	it('reads the row only after its series have landed, spending no attempt', async () => {
		let land = (_: boolean): void => {};
		mockHistory.seriesReady.mockImplementationOnce(() => new Promise((resolve) => (land = resolve)));
		const hydrated = { id: 's1', visualizerId: undefined };
		mockHistory.get.mockReturnValue(hydrated);
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });
		const uploads: unknown[] = [];

		const draining = drainWith({
			uploadShot: (s) => {
				uploads.push(s);
				return Effect.succeed({ visualizerId: 'viz-1' });
			}
		});
		await vi.waitFor(() => expect(mockHistory.seriesReady).toHaveBeenCalled());
		await Promise.resolve();
		expect(uploads).toEqual([]);
		expect(mockHistory.get).not.toHaveBeenCalled();
		expect(readQueue().entries[0].attempts).toBe(0);

		land(true);

		expect((await draining).succeeded).toBe(1);
		expect(uploads).toEqual([hydrated]);
	});

	it('non_brew_no_samples_refused: drops the entry on the first try, with no retry', async () => {
		mockHistory.get.mockReturnValue({ id: 's1', visualizerId: undefined });
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });

		const result = await drainWith({
			uploadShot: () => Effect.fail(new ShotWithoutSeriesError({ shotId: 's1' }))
		});

		expect(result.dropped).toBe(1);
		expect(readQueue().entries).toHaveLength(0);
		expect(mockHistory.bindVisualizerId).not.toHaveBeenCalled();
	});

	it('drops a non-recoverable failure (premium) on the first drain', async () => {
		mockHistory.get.mockReturnValue({ id: 's1', visualizerId: undefined });
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });

		const result = await drainWith({
			uploadShot: () => Effect.fail(new VisualizerPremiumGatedError({ endpoint: '/shots/upload' }))
		});

		expect(result.dropped).toBe(1);
		expect(readQueue().entries).toHaveLength(0);
	});

	it('retries a recoverable op and DROPS it after exactly 3 attempts', async () => {
		// Fake timers so the per-entry backoff (nextAttemptAt) can be jumped
		// without real waits; Date.now() drives both backoff + the ready filter.
		vi.useFakeTimers();
		try {
			vi.setSystemTime(0);
			mockHistory.get.mockReturnValue({ id: 's1', visualizerId: undefined });
			enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });

			const alwaysFails = {
				uploadShot: () => Effect.fail(new NetworkError({ cause: new Error('offline'), url: '/u' }))
			};

			// Attempt 1 → recoverable, stays queued with attempts=1.
			let r = await drainWith(alwaysFails);
			expect(r.processed).toBe(1);
			expect(r.dropped).toBe(0);
			expect(readQueue().entries[0]?.attempts).toBe(1);

			// Jump past backoff(1)=2s.
			vi.setSystemTime(2_001);
			r = await drainWith(alwaysFails);
			expect(r.dropped).toBe(0);
			expect(readQueue().entries[0]?.attempts).toBe(2);

			// Jump past backoff(2)=4s → this 3rd attempt hits MAX_ATTEMPTS and drops.
			vi.setSystemTime(2_001 + 4_001);
			r = await drainWith(alwaysFails);
			expect(r.dropped).toBe(1);
			expect(readQueue().entries).toHaveLength(0);
		} finally {
			vi.useRealTimers();
		}
	});

	it('skips an entry whose backoff has not elapsed (nextAttemptAt in the future)', async () => {
		vi.useFakeTimers();
		try {
			vi.setSystemTime(0);
			mockHistory.get.mockReturnValue({ id: 's1', visualizerId: undefined });
			enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });
			const fails = {
				uploadShot: () => Effect.fail(new NetworkError({ cause: new Error('x'), url: '/u' }))
			};
			await drainWith(fails); // attempts=1, nextAttemptAt=2s
			// Drain again immediately (now=0) — entry not ready, nothing processed.
			const r = await drainWith(fails);
			expect(r.processed).toBe(0);
			expect(readQueue().entries[0]?.attempts).toBe(1);
		} finally {
			vi.useRealTimers();
		}
	});
});

describe('UploadQueue.drain — a remote delete outlives its attempts (#331)', () => {
	it('delete_after_3_failures_retries: parks without dropping, and a later try lands', async () => {
		vi.useFakeTimers();
		try {
			vi.setSystemTime(0);
			enqueueEntry({ entity: 'shot', id: 's1', op: 'delete', visualizerId: 'viz-9' });
			const down = {
				deleteShot: () => Effect.fail(new HttpStatusError({ status: 503, url: '/d' }))
			};
			for (const at of [0, 60_000, 120_000]) {
				vi.setSystemTime(at);
				await drainWith(down);
			}
			const parked = readQueue().entries[0];
			expect(parked?.attempts).toBe(2);
			expect(parked?.nextAttemptAt).toBe(120_000 + 3_600_000);

			vi.setSystemTime(120_000 + 3_600_000 - 1);
			expect((await drainWith(down)).processed).toBe(0);

			vi.setSystemTime(120_000 + 3_600_000);
			expect((await drainWith(down)).dropped).toBe(0);
			expect(readQueue().entries).toHaveLength(1);

			vi.setSystemTime(120_000 + 2 * 3_600_000);
			expect((await drainWith({ deleteShot: () => Effect.void })).succeeded).toBe(1);
			expect(readQueue().entries).toHaveLength(0);
			expect(mockHistory.purgeTombstone).toHaveBeenCalledWith('s1');
		} finally {
			vi.useRealTimers();
		}
	});

	it('a shot deleted before its retry is not uploaded', async () => {
		mockHistory.get.mockReturnValue({ id: 's1', deletedAt: 5 });
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });
		const uploadShot = vi.fn(() => Effect.succeed({ visualizerId: 'v' }));
		const result = await drainWith({ uploadShot });
		expect(uploadShot).not.toHaveBeenCalled();
		expect(result.succeeded).toBe(1);
		expect(readQueue().entries).toHaveLength(0);
	});

	it('a terminal failure stops the delete and leaves the tombstone', async () => {
		const notice = vi.spyOn(toast, 'error').mockImplementation(() => {});
		enqueueEntry({ entity: 'shot', id: 's1', op: 'delete', visualizerId: 'viz-9' });
		const result = await drainWith({
			deleteShot: () => Effect.fail(new HttpStatusError({ status: 403, url: '/d', body: '{"error":"You are not authorized to perform this action."}' }))
		});
		expect(result.dropped).toBe(1);
		expect(mockHistory.purgeTombstone).not.toHaveBeenCalled();
		expect(notice).toHaveBeenCalledTimes(1);
		expect(notice.mock.calls[0][0]).toContain('refused to delete a shot');
	});
});

describe('UploadQueue.drain — a row that throws (#329)', () => {
	it('drops that entry and carries on past it', async () => {
		mockHistory.get.mockImplementation((id: string) => ({ id }));
		enqueueEntry({ entity: 'shot', id: 'throws', op: 'create' });
		enqueueEntry({ entity: 'shot', id: 'fine', op: 'create' });
		const uploaded: string[] = [];
		const result = await drainWith({
			uploadShot: (shot) =>
				Effect.sync(() => {
					const { id } = shot as { id: string };
					if (id === 'throws') throw new Error('not serialisable');
					uploaded.push(id);
					return { visualizerId: 'v' };
				})
		});
		expect(uploaded).toEqual(['fine']);
		expect(result.processed).toBe(2);
		expect(result.succeeded).toBe(1);
		expect(result.dropped).toBe(1);
		expect(readQueue().entries).toHaveLength(0);
	});
});

describe('UploadQueue.drain — shot delete + bean/roaster delete', () => {
	it('deletes a remote shot and purges the tombstone', async () => {
		enqueueEntry({ entity: 'shot', id: 's1', op: 'delete', visualizerId: 'viz-9' });
		const result = await drainWith({ deleteShot: () => Effect.void });
		expect(result.succeeded).toBe(1);
		expect(mockHistory.purgeTombstone).toHaveBeenCalledWith('s1');
		expect(readQueue().entries).toHaveLength(0);
	});

	it('routes a bean delete through BeanSync.deleteBean', async () => {
		const deleteBean = vi.fn(() => Effect.void);
		enqueueEntry({ entity: 'bean', id: 'b1', op: 'delete', visualizerId: 'vb-1' });
		const result = await drainWith({}, { deleteBean });
		expect(deleteBean).toHaveBeenCalledWith('vb-1');
		expect(result.succeeded).toBe(1);
	});
});

describe('UploadQueue.drain — remote deletes survive auth trouble (review 03 P1-1)', () => {
	it('keeps a delete queued when the token refresh fails transiently', async () => {
		enqueueEntry({ entity: 'shot', id: 's1', op: 'delete', visualizerId: 'viz-9' });
		// A refresh that hit the network now surfaces as NetworkError, not as a
		// sign-out: the entry is retried, not dropped.
		const result = await drainWith({
			deleteShot: () =>
				Effect.fail(new NetworkError({ cause: new TypeError('Failed to fetch'), url: '/oauth/token' }))
		});
		expect(result.dropped).toBe(0);
		expect(readQueue().entries).toHaveLength(1);
		expect(readQueue().entries[0]?.attempts).toBe(1);
	});

	it.each([
		['TokenRefreshFailedError', () => new TokenRefreshFailedError({ cause: 'invalid_grant' })],
		['NotAuthenticatedError', () => new NotAuthenticatedError()]
	])('parks a delete on %s without spending an attempt', async (_tag, err) => {
		enqueueEntry({ entity: 'bean', id: 'b1', op: 'delete', visualizerId: 'vb-1' });
		const result = await drainWith({}, { deleteBean: () => Effect.fail(err()) });
		expect(result.dropped).toBe(0);
		expect(result.deferred).toBe(1);
		const [entry] = readQueue().entries;
		expect(entry?.attempts).toBe(0);
		expect(entry?.nextAttemptAt).toBeGreaterThan(Date.now() + 60_000);
	});

	it('still drops a shot create on a sign-out (the unsynced pass re-offers it)', async () => {
		mockHistory.get.mockReturnValue({ id: 's1', visualizerId: undefined });
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });
		const result = await drainWith({
			uploadShot: () => Effect.fail(new TokenRefreshFailedError({ cause: 'invalid_grant' }))
		});
		expect(result.dropped).toBe(1);
		expect(readQueue().entries).toHaveLength(0);
	});
});

describe('UploadQueue.drain — edits waiting to reach Visualizer', () => {
	const pushed = () => {
		const ids: string[] = [];
		const push = (_history: unknown, id: string) =>
			Effect.sync(() => {
				ids.push(id);
				return 'sent';
			});
		return { ids, push };
	};

	it('failed_patch_retries_from_current_row: sends the shots that are due, and only those', async () => {
		settlePendingEdit('due', { annotations: true, attempts: 1, nextAttemptAt: Date.now() - 1 });
		settlePendingEdit('later', { annotations: true, attempts: 1, nextAttemptAt: Date.now() + 60_000 });
		const { ids, push } = pushed();
		await drainWith({ pushPendingEdit: push });
		expect(ids).toEqual(['due']);
	});

	it('a direction that never pushes sends nothing', async () => {
		updateSyncConfig({ direction: { ...readSyncConfig().direction, shots: 'pull' } });
		settlePendingEdit('s1', { annotations: true });
		const { ids, push } = pushed();
		await drainWith({ pushPendingEdit: push });
		expect(ids).toEqual([]);
	});

	it('tag_400_is_a_tier_signal: confirmed Premium clears the tags and reports, so nothing loops', async () => {
		settlePendingEdit('s1', { annotations: true, tags: { add: ['a'], remove: [] }, tierSignal: true });
		const probe = vi.fn(() => true);
		const { ids, push } = pushed();
		await drainWith({ pushPendingEdit: push }, { refreshPremiumIfStale: Effect.sync(probe) });
		expect(probe).toHaveBeenCalledTimes(1);
		expect(pendingEdit('s1')).toEqual({ annotations: true });
		expect(ids).toEqual(['s1']);
		expect(readSyncConfig().log[0]).toMatchObject({ id: 's1', direction: 'skip' });
	});

	it('a tier signal on Free keeps the tags until an upgrade', async () => {
		settlePendingEdit('s1', { tags: { add: ['a'], remove: [] }, tierSignal: true });
		await drainWith(
			{ pushPendingEdit: pushed().push },
			{ refreshPremiumIfStale: Effect.succeed(false) }
		);
		expect(pendingEdit('s1')).toEqual({ tags: { add: ['a'], remove: [] } });
	});

	it('a tier check with no answer asks again later', async () => {
		settlePendingEdit('s1', { tags: { add: ['a'], remove: [] }, tierSignal: true });
		await drainWith(
			{ pushPendingEdit: pushed().push },
			{ refreshPremiumIfStale: Effect.succeed(null) }
		);
		expect(pendingEdit('s1')).toMatchObject({ tierSignal: true });
	});

	it('a 429 leaves the rest for a later drain', async () => {
		settlePendingEdit('a', { annotations: true });
		settlePendingEdit('b', { annotations: true });
		const ids: string[] = [];
		await drainWith({
			pushPendingEdit: (_h, id) => {
				ids.push(id);
				return Effect.fail(new HttpStatusError({ status: 429, url: 'x' }));
			}
		});
		expect(ids).toEqual(['a']);
	});
});

describe('UploadQueue.drain — offline guard', () => {
	it('defers entirely (no processing) when navigator reports offline', async () => {
		vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
		mockHistory.get.mockReturnValue({ id: 's1', visualizerId: undefined });
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });
		const result = await drainWith({
			uploadShot: () => Effect.succeed({ visualizerId: 'viz-1' })
		});
		expect(result.deferred).toBe(1);
		expect(result.processed).toBe(0);
		expect(readQueue().entries).toHaveLength(1); // untouched
	});
});

describe('UploadQueue.armLifecycle — runtime lifetime (review 03 P3-8)', () => {
	it('stops the 5-minute drain loop when the runtime is disposed', async () => {
		vi.useFakeTimers();
		try {
			vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
			mockHistory.get.mockReturnValue({ id: 's1', visualizerId: undefined });
			enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });
			let uploads = 0;
			const layer = Layer.provide(
				UploadQueueLive,
				Layer.merge(
					fakeShotSync({
						uploadShot: () => {
							uploads += 1;
							return Effect.fail(new HttpStatusError({ status: 503, url: '/u' }));
						}
					}),
					fakeBeanSync({})
				)
			);
			const runtime = ManagedRuntime.make(layer);
			await runtime.runPromise(UploadQueue.pipe(Effect.flatMap((q) => q.armLifecycle)));
			await vi.advanceTimersByTimeAsync(10);
			expect(uploads).toBe(1); // the startup flush

			await vi.advanceTimersByTimeAsync(5 * 60 * 1000);
			expect(uploads).toBe(2); // the loop is alive while the runtime is

			await runtime.dispose();
			await vi.advanceTimersByTimeAsync(15 * 60 * 1000);
			expect(uploads).toBe(2); // and dead once it is disposed
		} finally {
			vi.useRealTimers();
		}
	});
});
