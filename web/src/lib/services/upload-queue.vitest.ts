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
	TokenRefreshFailedError,
	VisualizerPremiumGatedError
} from '../effect/errors.ts';
import { enqueueEntry, readQueue } from './queue-store.ts';
import { initTestWasm } from '#lib/testing/test-init.js';

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
}) {
	return Layer.succeed(
		ShotSync,
		ShotSync.of({
			uploadShot: (over.uploadShot ?? die('uploadShot')) as never,
			deleteShot: (over.deleteShot ?? die('deleteShot')) as never,
			patchEditedShot: undefined as never,
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
			refreshPremiumIfStale: Effect.die('unused: refreshPremiumIfStale') as never,
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
