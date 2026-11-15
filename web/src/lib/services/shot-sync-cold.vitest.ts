/**
 * #310 P4w: a Visualizer upload from a cold start sends each shot whole. The
 * history's rows carry no samples, so the backlog loop loads each shot just
 * before sending it; one that can't be read is skipped, never sent with an
 * empty curve. And a pull's merge never fills a recipe over the one the
 * store holds for a row, which a row can't show.
 */
import { Effect, Layer } from 'effect';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';

const h = vi.hoisted(() => ({
	exported: [] as { id: string; samples: number; profile: boolean }[],
	appendSyncLog: vi.fn()
}));

vi.mock('#lib/settings/index.js', () => ({
	getSettingsStore: () => ({
		current: { visualizerIncludeProfile: true, visualizerIncludeNotes: true, visualizerPrivacy: 'unlisted', grinderModel: '' }
	})
}));
vi.mock('#lib/history/v2-export.js', () => ({
	exportStoredShotAsV2Json: (shot: { id: string; record: { samples: unknown[] }; profile?: unknown }) => {
		h.exported.push({ id: shot.id, samples: shot.record.samples.length, profile: shot.profile != null });
		return JSON.stringify({ profile: { title: 'P' }, metadata: {} });
	}
}));
vi.mock('#lib/bean/store.svelte.js', () => ({ getBeanStore: () => ({ getBean: () => null }) }));
vi.mock('#lib/visualizer/sync-config.js', async (importOriginal) => ({
	...(await importOriginal<typeof import('#lib/visualizer/sync-config.js')>()),
	uploadsHeld: () => false,
	excludedFromUpload: () => false,
	appendSyncLog: h.appendSyncLog,
	readSyncConfig: () => ({ includeProfile: true, includeNotes: true, privacy: 'unlisted', shotPullCursor: null, pullCursorEpoch: 1 })
}));

import { ShotSync, ShotSyncLive } from './shot-sync.ts';
import { HttpClient, type HttpRequest } from './http-client.ts';
import { TokenVault } from './token-vault.ts';
import type { TokenSet } from '../visualizer/oauth.ts';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { HistoryDb, META_MIGRATION } from '#lib/history/history-db.js';
import { writeDoneMarker } from '#lib/history/store-marker.js';
import { summarize } from '#lib/history/summary.js';
import { boot, freshStorage, stored } from '#lib/history/idb-test-kit.js';
import type { StoredShot } from '#lib/history/model.js';
import type { HistoryStore } from '#lib/history/store.svelte.js';

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	freshStorage();
	h.exported = [];
	h.appendSyncLog.mockClear();
});

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
let nextId = 0;
const http = Layer.succeed(
	HttpClient,
	HttpClient.of({
		request: (req: HttpRequest) =>
			Effect.succeed(
				new Response(JSON.stringify(req.method === 'POST' ? { id: `viz-${++nextId}` } : {}), {
					status: 200,
					headers: { 'content-type': 'application/json' }
				})
			)
	})
);
const run = <A, E>(program: Effect.Effect<A, E, ShotSync>) =>
	Effect.runPromise(Effect.provide(program, Layer.provide(ShotSyncLive, Layer.merge(http, vault))) as Effect.Effect<A, never>);

async function seed(shots: readonly StoredShot[]): Promise<void> {
	const db = new HistoryDb({ expectExisting: () => false });
	await db.putShots(
		shots.map((shot) => ({ shot, details: true })),
		summarize
	);
	await db.setMeta(META_MIGRATION, { state: 'done', sourceHash: '0:0', sourceIds: shots.map((s) => s.id) });
	db.close();
	writeDoneMarker(1, shots.length);
}

describe('from a cold start', () => {
	it('the backlog loop sends every shot whole, its curve and recipe loaded just before', async () => {
		const shots = Array.from({ length: 4 }, (_, i) => ({ ...realShot(i, realSamples(7, i)), visualizerId: null }));
		await seed(shots);
		const history = await boot();
		for (const row of history.all) expect('samples' in row.record).toBe(false);
		const out = await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadUnsyncedShots(history))));
		expect(out.uploaded).toBe(4);
		expect(h.exported.map((e) => e.samples)).toEqual([7, 7, 7, 7]);
		expect(h.exported.every((e) => e.profile)).toBe(true);
		for (const row of history.all) expect(row.visualizerId).toMatch(/^viz-/);
	});

	it('a shot that can’t be read is skipped and logged, never sent without its curve', async () => {
		const shots = Array.from({ length: 2 }, (_, i) => ({ ...realShot(i, realSamples(7, i)), visualizerId: null }));
		await seed(shots);
		let fail = false;
		const history = await boot({
			dbFault: (op) => {
				if (fail && op === 'read-details') throw new DOMException('gone', 'UnknownError');
			}
		});
		fail = true;
		const out = await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadUnsyncedShots(history))));
		expect(out.uploaded).toBe(0);
		expect(h.exported).toEqual([]);
		expect(h.appendSyncLog.mock.calls.filter(([e]) => e.error === 'This shot could not be read for upload.')).toHaveLength(2);
	});
});

describe('a pull’s merge onto a light row', () => {
	/** A pull whose list holds `viz-1`, changed, and whose detail offers a recipe. */
	const pullWith = (history: HistoryStore, seen: string[]) => {
		const json = (body: unknown) => Effect.succeed(new Response(JSON.stringify(body)));
		const layer = Layer.succeed(
			HttpClient,
			HttpClient.of({
				request: (req: HttpRequest) => {
					seen.push(req.url);
					if (req.url.includes('/shots?')) {
						return json({ data: [{ id: 'viz-1', clock: 1000, updated_at: 2000 }], paging: { count: 1, page: 1, limit: 50, pages: 1 } }) as never;
					}
					if (req.url.includes('/profile')) return json({ title: 'Pulled' }) as never;
					return json({
						id: 'viz-1',
						duration: 30,
						data: { espresso_pressure: [9] },
						timeframe: [0],
						profile_url: 'https://visualizer.coffee/api/shots/viz-1/profile'
					}) as never;
				}
			})
		);
		return Effect.runPromise(
			Effect.provide(
				ShotSync.pipe(Effect.flatMap((s) => s.pullAndReconcileShots(history))),
				Layer.provide(ShotSyncLive, Layer.merge(layer, vault))
			) as Effect.Effect<unknown, never>
		);
	};

	it('a bound row whose store holds a recipe: the pull fetches no recipe, and the row’s own stays', async () => {
		const own = { ...realShot(1, realSamples(7)), visualizerId: 'viz-1' };
		await seed([own]);
		const history = await boot();
		const seen: string[] = [];
		await pullWith(history, seen);
		await history.flushed();
		expect(seen.some((u) => u.includes('/shots/viz-1'))).toBe(true);
		expect(seen.filter((u) => u.includes('/profile'))).toEqual([]);
		expect((await stored()).details.get(own.id)!.profile).toEqual(own.profile);
	});

	it('a bound row with no recipe stored still asks for the pulled one', async () => {
		const own = { ...realShot(1, realSamples(7)), visualizerId: 'viz-1', profile: null };
		await seed([own]);
		const history = await boot();
		const seen: string[] = [];
		await pullWith(history, seen);
		await history.flushed();
		// Asked for: the store holds none (what it then fills is the pull's own rule).
		expect(seen.filter((u) => u.includes('/profile'))).toHaveLength(1);
	});

	it('a recipe a row can’t show is never filled over the one the store holds', async () => {
		const own = { ...realShot(1, realSamples(7)), visualizerId: 'viz-1' };
		await seed([own]);
		const history: HistoryStore = await boot();
		const wire = {
			id: 'viz-1',
			clock: own.completedAt,
			duration_ms: own.record.duration,
			profile_title: own.profileName,
			final_weight_g: null,
			notes: null,
			rating: null,
			updated_at_ms: 1,
			tag_list: []
		};
		const applied = vi.spyOn(history, 'applyRemote');
		await run(
			ShotSync.pipe(
				Effect.flatMap((s) =>
					s.applyShotReconciliation(history, [wire as never], undefined, new Map([['viz-1', { title: 'Pulled' }]]), undefined, true)
				)
			)
		);
		// The merge itself asks for no fill: a row counts as holding its recipe.
		expect(applied.mock.calls.map(([, patch]) => patch.fillProfile ?? false)).toEqual([false]);
		await history.flushed();
		const { details } = await stored();
		expect(details.get(own.id)!.profile).toEqual(own.profile);
		expect((await history.loadShot(own.id))!.profile).toEqual(own.profile);
	});
});
