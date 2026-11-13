/**
 * The web pull run (SYNC-9) against a fake Visualizer: offset paging newest
 * first, strict `updated_after`, details, failing rows and rate limits. The
 * run's rules are the core's; these pin the loop that drives them.
 */
import { Effect, Layer } from 'effect';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { FakeLocks } from '#lib/testing/fake-locks.js';
import type { StoredShot } from '#lib/history/model.js';
import type { PoisonEntry } from '#lib/core/crema-core.js';
import { ShotSync, ShotSyncLive } from './shot-sync.ts';
import { HttpClient, type HttpRequest } from './http-client.ts';
import { TokenVault } from './token-vault.ts';
import { HttpStatusError } from '../effect/errors.ts';
import { readSyncConfig, updateSyncConfig } from '#lib/visualizer/sync-config.js';
import { readPoison, writePoison } from '#lib/visualizer/poison.js';
import { RUN_LOCK, setRunLocksForTest } from '#lib/visualizer/run-lock.js';
import { setWaitForTest } from '#lib/visualizer/pace.js';
import { markRestoringHere } from '#lib/restore/tab-safety.js';
import type { TokenSet } from '../visualizer/oauth.ts';

vi.mock('#lib/history/series-store.js', () => ({
	putSeries: async () => {},
	getAllSeries: async () => new Map(),
	deleteSeries: async () => {},
	pruneSeriesTo: async () => {},
	clearAllSeries: async () => {}
}));

import { HistoryStore } from '#lib/history/store.svelte.js';

const T0 = 1_700_000_000; // unix s

/** A Visualizer with `rows` (newest `updated_at` first when listed). */
class FakeVisualizer {
	rows: { id: string; clock: number; updated_at: number }[] = [];
	/** Detail ids that answer 503. */
	failing = new Set<string>();
	/** Detail ids that answer 404. */
	gone = new Set<string>();
	/** List pages that answer 429. */
	limitedPages = new Set<number>();
	requests: string[] = [];

	add(n: number, from = 0): void {
		for (let i = 0; i < n; i++) {
			const k = from + i;
			this.rows.push({ id: `r${k}`, clock: T0 + k * 100, updated_at: T0 + 10_000 + k });
		}
	}

	reply(req: HttpRequest): Effect.Effect<Response, HttpStatusError> {
		const url = new URL(req.url);
		this.requests.push(`${req.method ?? 'GET'} ${url.pathname}${url.search}`);
		const ok = (body: unknown) =>
			Effect.succeed(new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json' } }));
		const fail = (status: number) =>
			Effect.fail(new HttpStatusError({ status, url: req.url, body: '{"error":"x"}' }));
		if (url.pathname === '/api/shots') {
			const page = Number(url.searchParams.get('page'));
			if (this.limitedPages.has(page)) return fail(429);
			const items = Number(url.searchParams.get('items'));
			const after = url.searchParams.get('updated_after');
			const listed = this.rows
				.filter((r) => after === null || r.updated_at > Number(after))
				.sort((a, b) => b.updated_at - a.updated_at);
			const pages = Math.max(1, Math.ceil(listed.length / items));
			const p = Math.min(page, pages);
			return ok({
				data: listed.slice((p - 1) * items, p * items),
				paging: { count: listed.length, page: p, limit: items, pages }
			});
		}
		const id = url.pathname.split('/')[3];
		if (this.failing.has(id)) return fail(503);
		if (this.gone.has(id) || !this.rows.some((r) => r.id === id)) return fail(404);
		return ok({
			id,
			duration: 30,
			profile_title: 'P',
			drink_weight: '36',
			timeframe: ['0.0', '0.25'],
			data: { espresso_pressure: ['9.0', '9.0'], espresso_flow: ['2.0', '2.0'] }
		});
	}

	details(): string[] {
		return this.requests.filter((r) => /^GET \/api\/shots\/[^/?]+$/.test(r));
	}
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

function pull(v: FakeVisualizer, history: HistoryStore, opts: { fromStart?: boolean } = {}) {
	const http = Layer.succeed(HttpClient, HttpClient.of({ request: (req) => v.reply(req) }));
	return Effect.runPromise(
		Effect.provide(
			ShotSync.pipe(Effect.flatMap((s) => s.pullAndReconcileShots(history, opts))),
			Layer.provide(ShotSyncLive, Layer.merge(http, vault))
		)
	);
}

const waits: number[] = [];

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	localStorage.clear();
	updateSyncConfig({ pullCursorEpoch: 1, syncAccountId: 'u1' });
	waits.length = 0;
	setWaitForTest((ms) => Effect.sync(() => void waits.push(ms)));
	setRunLocksForTest(undefined);
});

afterEach(() => {
	setWaitForTest(null);
	setRunLocksForTest(null);
});

const bound = (id: string, visualizerId: string, over: Partial<StoredShot> = {}): StoredShot => ({
	formatVersion: 3,
	id,
	completedAt: 1,
	profileName: 'P',
	metadata: {},
	record: { duration: 30_000, samples: [{ t: 0 } as never] },
	tags: [],
	visualizerId,
	...over
});

describe('the pull run', () => {
	it('pull_40_shots_gets_40: every row arrives, and the cursor is the server newest less a minute', async () => {
		const v = new FakeVisualizer();
		v.add(40);
		const history = new HistoryStore();
		const out = await pull(v, history);
		expect(out.outcome).toMatchObject({ kind: 'Complete', details: { applied: 40 } });
		expect(history.all.filter((s) => s.visualizerId)).toHaveLength(40);
		expect(v.details()).toHaveLength(40);
		const newest = T0 + 10_000 + 39;
		expect(readSyncConfig().shotPullCursor).toBe((newest - 60) * 1000);
		// Versions are written: the next run lists from the cursor and fetches nothing.
		v.requests = [];
		const again = await pull(v, history);
		expect(again.outcome).toMatchObject({ kind: 'Complete', details: { applied: 0 } });
		expect(v.details()).toEqual([]);
		expect(v.requests[0]).toContain(`updated_after=${newest - 60 - 1}`);
	});

	it('pull_503_row_holds_cursor: the rest apply, the cursor holds, the next run fetches only that row', async () => {
		const v = new FakeVisualizer();
		v.add(10);
		v.failing.add('r3');
		const history = new HistoryStore();
		const out = await pull(v, history);
		expect(out.outcome).toMatchObject({ kind: 'Partial', details: { reason: 'RowsFailed', applied: 9 } });
		expect(readSyncConfig().shotPullCursor).toBeNull();
		expect(v.details().filter((d) => d.endsWith('/r3'))).toHaveLength(2); // one retry in place
		v.failing.clear();
		v.requests = [];
		const again = await pull(v, history);
		expect(again.outcome).toMatchObject({ kind: 'Complete', details: { applied: 1 } });
		expect(v.details()).toEqual(['GET /api/shots/r3']);
	});

	it('pull_120_429_page_2: the first page stays applied, the run waits three times, then stops', async () => {
		const v = new FakeVisualizer();
		v.add(120);
		v.limitedPages.add(2);
		const history = new HistoryStore();
		const out = await pull(v, history);
		expect(out.outcome).toMatchObject({ kind: 'Partial', details: { reason: 'RateLimited', applied: 100 } });
		expect(history.all.filter((s) => s.visualizerId)).toHaveLength(100);
		expect(readSyncConfig().shotPullCursor).toBeNull();
		// 60 s, then ten minutes (and again), each with up to 20 % added.
		expect(waits).toHaveLength(3);
		expect(waits[0]).toBeGreaterThanOrEqual(60_000);
		expect(waits[1]).toBeGreaterThanOrEqual(600_000);
	});

	it('first_run_repairs_old_cursor: walks from the start once, skipping bound rows at or below the old cursor', async () => {
		const v = new FakeVisualizer();
		v.add(4);
		// An old cursor at r1's update time: r0 and r1 are below it.
		updateSyncConfig({ pullCursorEpoch: null, shotPullCursor: (T0 + 10_001) * 1000 });
		const history = new HistoryStore();
		history.insertPulled(bound('local-0', 'r0'));
		history.insertPulled(bound('local-1', 'r1', { completedAt: 2 }));
		const out = await pull(v, history);
		expect(out.repairing).toBe(true);
		expect(v.requests[0]).not.toContain('updated_after');
		// r0, r1 bound and below the old cursor: no detail. r2, r3 arrive.
		expect(v.details().sort()).toEqual(['GET /api/shots/r2', 'GET /api/shots/r3']);
		expect(out.outcome).toMatchObject({ kind: 'Complete' });
		expect(readSyncConfig()).toMatchObject({ pullCursorEpoch: 1, shotPullCursor: (T0 + 10_003 - 60) * 1000 });
	});

	it('a full history spends no detail on new rows, and holds the cursor', async () => {
		const v = new FakeVisualizer();
		v.add(3);
		const history = new HistoryStore();
		vi.spyOn(history, 'freeSlots', 'get').mockReturnValue(1);
		const out = await pull(v, history);
		expect(out.outcome).toMatchObject({ kind: 'Partial', details: { reason: 'HistoryFull' } });
		expect(out.refused).toBe(2);
		expect(v.details()).toHaveLength(1);
		expect(readSyncConfig().shotPullCursor).toBeNull();
	});

	it('one run at a time: a pull in another tab means this one does nothing', async () => {
		const locks = new FakeLocks();
		let release = (): void => {};
		void locks.request(RUN_LOCK, {}, () => new Promise<void>((r) => (release = r)));
		setRunLocksForTest(locks);
		const v = new FakeVisualizer();
		v.add(2);
		const out = await pull(v, new HistoryStore());
		expect(out.outcome).toEqual({ kind: 'Busy' });
		expect(v.requests).toEqual([]);
		release();
	});

	it('a poison row is tried alone once a day: a 404 ends it, any other failure waits a day', async () => {
		const now = Date.now();
		const entry = (id: string): PoisonEntry => ({ id, count: 3, lastCountedAtMs: now - 1, clock: 0, retryAtMs: now - 1 });
		writePoison([entry('gone'), entry('broken')]);
		const v = new FakeVisualizer();
		v.gone.add('gone');
		v.rows.push({ id: 'broken', clock: 0, updated_at: 1 });
		v.failing.add('broken');
		await pull(v, new HistoryStore());
		const left = readPoison();
		expect(left.map((e) => e.id)).toEqual(['broken']);
		expect(left[0].retryAtMs).toBeGreaterThanOrEqual(now + 24 * 3_600_000 - 1);
		// Not due again today: no second try.
		v.requests = [];
		await pull(v, new HistoryStore());
		expect(v.details().filter((d) => d.endsWith('/broken'))).toHaveLength(2); // the walk's own fetch and retry
	});

	it('a re-pull walks from the start and forgets failing rows', async () => {
		updateSyncConfig({ shotPullCursor: (T0 + 20_000) * 1000 });
		writePoison([{ id: 'x', count: 1, lastCountedAtMs: 0, clock: 0, retryAtMs: Date.now() + 1e9 }]);
		const v = new FakeVisualizer();
		v.add(2);
		const history = new HistoryStore();
		const out = await pull(v, history, { fromStart: true });
		expect(v.requests[0]).not.toContain('updated_after');
		expect(out.outcome).toMatchObject({ kind: 'Complete', details: { applied: 2 } });
		expect(readPoison()).toEqual([]);
	});

	it('a restore in this tab: the run waits for it, and says so', async () => {
		const v = new FakeVisualizer();
		v.add(3);
		markRestoringHere(true);
		try {
			const out = await pull(v, new HistoryStore());
			expect(out.outcome).toEqual({ kind: 'Busy', restore: true });
			expect(v.requests).toEqual([]);
		} finally {
			markRestoringHere(false);
		}
	});

	it('a restore that starts mid-walk stops the run, keeping what it applied, with its own words', async () => {
		const v = new FakeVisualizer();
		v.add(150);
		const history = new HistoryStore();
		const real = v.reply.bind(v);
		// The restore starts while the first page's rows are fetched.
		v.reply = (req) => {
			if (req.url.endsWith('/shots/r149')) markRestoringHere(true);
			return real(req);
		};
		try {
			const before = await pull(v, history);
			expect(before.outcome).toEqual({ kind: 'Busy', restore: true });
			expect(before.pulled).toBe(100);
			expect(readSyncConfig().shotPullCursor).toBeNull();
			expect(readSyncConfig().log[0].error).toBe('A restore started, so the pull stopped. It goes on with the next sync.');
		} finally {
			markRestoringHere(false);
		}
	});

	it('a poison row that comes through takes the detail updated_at as its version', async () => {
		const now = Date.now();
		writePoison([{ id: 'r0', count: 3, lastCountedAtMs: now - 1, clock: 0, retryAtMs: now - 1 }]);
		const v = new FakeVisualizer();
		v.add(1);
		const real = v.reply.bind(v);
		v.reply = (req) =>
			req.url.endsWith('/shots/r0') && v.requests.push('GET /api/shots/r0')
				? Effect.succeed(
						new Response(
							JSON.stringify({
								id: 'r0',
								duration: 30,
								updated_at: T0 + 10_000,
								timeframe: ['0.0', '0.25'],
								data: { espresso_pressure: ['9.0', '9.0'] }
							})
						)
					)
				: real(req);
		const history = new HistoryStore();
		await pull(v, history);
		expect(history.all.find((s) => s.visualizerId === 'r0')?.visualizerUpdatedAt).toBe(T0 + 10_000);
		expect(v.details()).toEqual(['GET /api/shots/r0']); // the walk skips it: up to date
	});
});
