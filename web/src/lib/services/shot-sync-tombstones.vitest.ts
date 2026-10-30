/**
 * A shot deleted locally must not come back on the next Visualizer pull
 * (core/shell review 2026-10-30 §2.4-4). Deleting a synced shot leaves a
 * tombstone until the remote DELETE lands; reconcile was handed
 * `history.all`, which hides tombstones, so the planner treated the
 * still-present remote shot as new and pulled it back in.
 */
import { Effect, Layer } from 'effect';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import type { StoredShot } from '#lib/history/model.js';
import type { WireShot } from '#lib/visualizer/shot-sync-signatures.js';
import { ShotSync, ShotSyncLive } from './shot-sync.ts';
import { HttpClient } from './http-client.ts';
import { TokenVault } from './token-vault.ts';

vi.mock('#lib/history/series-store.js', () => ({
	putSeries: async () => {},
	getAllSeries: async () => new Map(),
	deleteSeries: async () => {},
	pruneSeriesTo: async () => {},
	clearAllSeries: async () => {}
}));

import { HistoryStore } from '#lib/history/store.svelte.js';

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	localStorage.clear();
});

// Reconcile is local: no request is made, so the HTTP and token fakes refuse.
const layer = Layer.provide(
	ShotSyncLive,
	Layer.merge(
		Layer.succeed(HttpClient, HttpClient.of({ request: () => Effect.die('no HTTP in reconcile') })),
		Layer.succeed(
			TokenVault,
			TokenVault.of({
				getTokens: Effect.succeed(null),
				storeTokens: () => Effect.void,
				clearTokens: Effect.void,
				withFreshToken: (() => Effect.die('no HTTP in reconcile')) as never,
				changes: undefined as never
			})
		)
	)
);

const reconcile = (history: HistoryStore, remote: WireShot[]) =>
	Effect.runPromise(
		Effect.provide(
			Effect.flatMap(ShotSync, (s) => s.applyShotReconciliation(history, remote)),
			layer
		)
	);

const COMPLETED = 1_700_000_000_000;

function localShot(id: string, visualizerId: string | null): StoredShot {
	return {
		formatVersion: 3,
		id,
		completedAt: COMPLETED,
		profileName: 'Test Profile',
		metadata: {},
		record: { duration: 30_000, samples: [] },
		tags: [],
		visualizerId
	};
}

function remoteShot(id: string, over: Partial<WireShot> = {}): WireShot {
	return {
		id,
		clock: COMPLETED,
		duration_ms: 30_000,
		profile_title: 'Test Profile',
		final_weight_g: null,
		notes: null,
		rating: null,
		updated_at_ms: COMPLETED,
		tag_list: ['remote-tag'],
		...over
	};
}

describe('ShotSync.applyShotReconciliation with tombstones', () => {
	it('AC-F18a a shot deleted locally is not pulled back while its remote DELETE is pending', async () => {
		const history = new HistoryStore();
		history.insertPulled(localShot('shot:deleted', 'vis-1'));
		history.delete('shot:deleted'); // synced → tombstoned, DELETE pending
		expect(history.all).toHaveLength(0);

		await reconcile(history, [remoteShot('vis-1')]);

		expect(history.all).toHaveLength(0);
		expect(history.rawAll.map((s) => s.id)).toEqual(['shot:deleted']);
		// The tombstone is left alone: no tag update from the remote.
		expect(history.get('shot:deleted')?.tags).toEqual([]);
		expect(history.get('shot:deleted')?.deletedAt).not.toBeNull();
	});

	it('still pulls new remote shots and updates live ones next to a tombstone', async () => {
		const history = new HistoryStore();
		history.insertPulled(localShot('shot:live', 'vis-2'));
		history.insertPulled(localShot('shot:deleted', 'vis-1'));
		history.delete('shot:deleted');

		await reconcile(history, [
			remoteShot('vis-1'),
			remoteShot('vis-2'),
			remoteShot('vis-3', { clock: COMPLETED + 60_000 })
		]);

		expect(history.get('shot:live')?.tags).toEqual(['remote-tag']);
		expect(history.all.map((s) => s.visualizerId).sort()).toEqual(['vis-2', 'vis-3']);
		expect(history.rawAll).toHaveLength(3);
	});
});
