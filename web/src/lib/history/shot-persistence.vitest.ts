/**
 * `$lib/history/shot-persistence.vitest` — vitest for the Visualizer shot-push
 * (docs/53 T-22, the safe win).
 *
 * `pushShotToVisualizer` is the `ShotCompleted` commit's fire-and-forget upload,
 * re-expressed as an Effect that runs *on the app runtime* so it classifies the
 * REAL `Data.TaggedError`s (retiring the old squashed-`_tag` boundary probe).
 * This locks in the behavior the squash workaround made untestable: the gating,
 * the success bind + push-log, and the recoverable-vs-terminal routing to the
 * retry queue. Run: `cd web && pnpm test:vitest` (or `vitest run shot-persistence`).
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Effect, Layer } from 'effect';

// Stores + sync-config reached via `$lib`; mock them so no rune store / real
// localStorage is touched and we can observe bind / log calls + drive gating.
// `vi.hoisted` makes the shared spies safe to reference inside the (hoisted)
// `vi.mock` factories.
const h = vi.hoisted(() => {
	let direction: 'push' | 'off' = 'push';
	return {
		mockHistory: { get: vi.fn(), bindVisualizerId: vi.fn() },
		mockSettings: { current: { visualizerAutoUpload: true } },
		appendSyncLog: vi.fn(),
		getDirection: () => direction,
		setDirection: (d: 'push' | 'off') => {
			direction = d;
		}
	};
});
const { mockHistory, mockSettings, appendSyncLog } = h;

// shot-persistence.ts imports `getHistoryStore` from `./store.svelte` (relative),
// so the mock must target that path, not the `$lib/history` barrel.
vi.mock('./store.svelte', () => ({ getHistoryStore: () => h.mockHistory }));
vi.mock('$lib/settings', () => ({ getSettingsStore: () => h.mockSettings }));
vi.mock('$lib/visualizer', () => ({
	appendSyncLog: h.appendSyncLog,
	directionPushes: (d: string) => d === 'push',
	readSyncConfig: () => ({ direction: { shots: h.getDirection() } })
}));

import { pushShotToVisualizer } from './shot-persistence.ts';
import { ShotSync } from '$lib/services/shot-sync';
import { UploadQueue } from '$lib/services/upload-queue';
import { HttpStatusError, NetworkError, VisualizerPremiumGatedError } from '$lib/effect/errors';

const die = (label: string) => () => Effect.die(`unused: ${label}`);

function fakeShotSync(uploadShot: (shot: unknown) => Effect.Effect<{ visualizerId: string }, unknown>) {
	return Layer.succeed(
		ShotSync,
		ShotSync.of({
			uploadShot: uploadShot as never,
			deleteShot: die('deleteShot') as never,
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

const enqueue = vi.fn(() => Effect.void);
const fakeUploadQueue = Layer.succeed(
	UploadQueue,
	UploadQueue.of({
		enqueue: enqueue as never,
		dequeue: die('dequeue') as never,
		isPending: die('isPending') as never,
		getQueue: die('getQueue') as never,
		clearQueue: die('clearQueue') as never,
		drain: die('drain') as never,
		armLifecycle: die('armLifecycle') as never
	})
);

function run(uploadShot: (shot: unknown) => Effect.Effect<{ visualizerId: string }, unknown>) {
	return Effect.runPromise(
		Effect.provide(pushShotToVisualizer('s1'), Layer.merge(fakeShotSync(uploadShot), fakeUploadQueue))
	);
}

beforeEach(() => {
	vi.clearAllMocks();
	h.setDirection('push');
	mockSettings.current.visualizerAutoUpload = true;
	mockHistory.get.mockReturnValue({ id: 's1', profileName: 'Test', visualizerId: undefined });
});

describe('pushShotToVisualizer — gating', () => {
	it('no-ops when the shots direction does not push', async () => {
		h.setDirection('off');
		const uploadShot = vi.fn(() => Effect.succeed({ visualizerId: 'v1' }));
		await run(uploadShot);
		expect(uploadShot).not.toHaveBeenCalled();
		expect(appendSyncLog).not.toHaveBeenCalled();
	});

	it('no-ops when visualizerAutoUpload is off', async () => {
		mockSettings.current.visualizerAutoUpload = false;
		const uploadShot = vi.fn(() => Effect.succeed({ visualizerId: 'v1' }));
		await run(uploadShot);
		expect(uploadShot).not.toHaveBeenCalled();
	});

	it('no-ops when the local row has vanished', async () => {
		mockHistory.get.mockReturnValue(undefined);
		const uploadShot = vi.fn(() => Effect.succeed({ visualizerId: 'v1' }));
		await run(uploadShot);
		expect(uploadShot).not.toHaveBeenCalled();
	});
});

describe('pushShotToVisualizer — success', () => {
	it('binds the returned id and logs a push', async () => {
		await run(() => Effect.succeed({ visualizerId: 'viz-1' }));
		expect(mockHistory.bindVisualizerId).toHaveBeenCalledWith('s1', 'viz-1');
		expect(enqueue).not.toHaveBeenCalled();
		expect(appendSyncLog).toHaveBeenCalledWith(
			expect.objectContaining({ direction: 'push', entity: 'shot', id: 's1' })
		);
	});
});

describe('pushShotToVisualizer — typed-error routing', () => {
	it('enqueues a recoverable failure (network) and logs a skip', async () => {
		await run(() => Effect.fail(new NetworkError({ cause: new Error('offline'), url: '/u' })));
		expect(enqueue).toHaveBeenCalledWith(
			expect.objectContaining({ entity: 'shot', id: 's1', op: 'create' })
		);
		expect(appendSyncLog).toHaveBeenCalledWith(
			expect.objectContaining({ direction: 'skip', id: 's1' })
		);
		expect(mockHistory.bindVisualizerId).not.toHaveBeenCalled();
	});

	it('enqueues a recoverable 5xx', async () => {
		await run(() => Effect.fail(new HttpStatusError({ status: 503, url: '/u' })));
		expect(enqueue).toHaveBeenCalledOnce();
	});

	it('does NOT enqueue a terminal failure (premium) — logs a skip only', async () => {
		await run(() => Effect.fail(new VisualizerPremiumGatedError({ endpoint: '/shots/upload' })));
		expect(enqueue).not.toHaveBeenCalled();
		expect(appendSyncLog).toHaveBeenCalledWith(
			expect.objectContaining({ direction: 'skip', id: 's1' })
		);
	});

	it('does NOT enqueue a terminal 4xx (404)', async () => {
		await run(() => Effect.fail(new HttpStatusError({ status: 404, url: '/u' })));
		expect(enqueue).not.toHaveBeenCalled();
	});
});
