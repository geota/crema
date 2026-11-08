/**
 * What a pull may do to rows already here. `tag_list` is `[]` on a free account
 * and tells nothing about the shot, so an empty list never clears local tags
 * (#326, the stopgap); and a pull never removes a row, so a full history refuses
 * the add and the rest of the plan goes on.
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

/** Answers how many new rows a full history refused. */
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

describe('remote tags on a pull', () => {
	it('free_pull_keeps_tags: an empty remote list leaves local tags', async () => {
		const history = new HistoryStore();
		history.insertPulled(localShot('shot:a', 'vis-1'));
		history.setTags('shot:a', ['washed', 'gesha']);

		await reconcile(history, [remoteShot('vis-1', { tag_list: [] })]);

		expect(history.get('shot:a')?.tags).toEqual(['washed', 'gesha']);
	});

	it('a missing remote list leaves local tags', async () => {
		const history = new HistoryStore();
		history.insertPulled(localShot('shot:a', 'vis-1'));
		history.setTags('shot:a', ['washed']);

		await reconcile(history, [remoteShot('vis-1', { tag_list: undefined })]);

		expect(history.get('shot:a')?.tags).toEqual(['washed']);
	});

	it('remote tags fill a row with none', async () => {
		const history = new HistoryStore();
		history.insertPulled(localShot('shot:a', 'vis-1'));

		await reconcile(history, [remoteShot('vis-1', { tag_list: ['washed'] })]);

		expect(history.get('shot:a')?.tags).toEqual(['washed']);
	});

	it('remote tags don’t replace local ones', async () => {
		const history = new HistoryStore();
		history.insertPulled(localShot('shot:a', 'vis-1'));
		history.setTags('shot:a', ['mine']);

		await reconcile(history, [remoteShot('vis-1', { tag_list: ['theirs'] })]);

		expect(history.get('shot:a')?.tags).toEqual(['mine']);
	});
});

describe('a pull at the cap', () => {
	const full = (): HistoryStore => {
		const history = new HistoryStore();
		for (let i = 0; i < history.capacity; i++) {
			history.insertPulled({
				...localShot(`shot:${i}`, `vis-${i}`),
				completedAt: COMPLETED - i
			});
		}
		return history;
	};

	it('pull_never_evicts_at_cap: refuses the add and counts it', async () => {
		const history = full();
		const before = history.all.map((s) => s.id);

		const refused = await reconcile(history, [
			remoteShot('vis-new-1', { clock: COMPLETED + 1, duration_ms: 1 }),
			remoteShot('vis-new-2', { clock: COMPLETED + 2, duration_ms: 2 })
		]);

		expect(refused).toBe(2);
		expect(history.all.map((s) => s.id)).toEqual(before);
	});

	it('cap_refusal_skips_and_continues: a later update still applies', async () => {
		const history = full();

		const refused = await reconcile(history, [
			remoteShot('vis-new', { clock: COMPLETED + 1, duration_ms: 1 }),
			remoteShot('vis-7', { tag_list: ['later-row'] })
		]);

		expect(refused).toBe(1);
		expect(history.get('shot:7')?.tags).toEqual(['later-row']);
	});

	it('a pulled row takes a free slot, and no row is lost', async () => {
		const history = new HistoryStore();
		history.insertPulled(localShot('shot:a', 'vis-1'));

		const refused = await reconcile(history, [
			remoteShot('vis-new', { clock: COMPLETED + 1, duration_ms: 1 })
		]);

		expect(refused).toBe(0);
		expect(history.all).toHaveLength(2);
	});
});
