/**
 * `$lib/services/upload-queue.vitest` — vitest for the `UploadQueue` drain
 * orchestration (docs/53 T-14, the store-coupled half of T-16).
 *
 * This is the behavior that was previously verified only by the docs/55 manual
 * smoke test ("kill the network mid-upload → confirm it queues and drains on
 * reconnect, and a persistently-failing op drops after exactly 3 attempts").
 * It's unreachable under the node:test runner (`upload-queue.ts` imports
 * `$lib/history` and the `ShotSync`/`BeanSync` tags), so it lives here: vitest
 * resolves `$lib`, jsdom supplies `localStorage`, the history store is mocked,
 * and fake `ShotSync` / `BeanSync` layers stand in for the network.
 *
 * Run: `cd web && pnpm test:vitest` (or `vitest run upload-queue`).
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Effect, Layer } from 'effect';

// runEntry reaches the history store via `$lib/history`; mock it so no real
// rune store is instantiated and we can observe the bind / purge calls.
const mockHistory = {
	get: vi.fn(),
	bindVisualizerId: vi.fn(),
	purgeTombstone: vi.fn()
};
vi.mock('$lib/history', () => ({ getHistoryStore: () => mockHistory }));

import { UploadQueue, UploadQueueLive } from './upload-queue.ts';
import { ShotSync } from './shot-sync.ts';
import { BeanSync } from './bean-sync.ts';
import { HttpStatusError, NetworkError, VisualizerPremiumGatedError } from '../effect/errors.ts';
import { enqueueEntry, readQueue } from './queue-store.ts';

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
			deleteBean: (over.deleteBean ?? die('deleteBean')) as never,
			deleteRoaster: (over.deleteRoaster ?? die('deleteRoaster')) as never
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
