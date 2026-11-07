/**
 * `#lib/services/shot-sync-upload.vitest` — vitest for `ShotSync.uploadShot`'s
 * payload shaping + soft post-upload PATCH (docs/53 T-12, §4.1 "no byte changes
 * to requests"). This was the last manual-only ShotSync path.
 *
 * The wasm `signatureForShot` (its value is pinned by the node:test
 * `visualizer/shot-sync.test.ts`) and the Decent-v2 export are mocked to fixed
 * outputs so the test isolates what's otherwise uncovered: the settings gating
 * (privacy / includeProfile / includeNotes), the injected `metadata.crema`
 * block, and the upload → soft-PATCH sequence (a PATCH failure must NOT fail the
 * upload). Run: `pnpm test:vitest`.
 */

import { Effect, Exit, Layer } from 'effect';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { markShotEdit, pendingEdit, type ShotEdit } from '#lib/visualizer/pending-edits.js';

// Controllable settings + the wasm signature / v2 export, all hoisted so the
// mock factories can close over them.
const h = vi.hoisted(() => ({
	// grinderModel stays in app settings; the visualizer upload prefs moved to
	// the sync-config (the unification) — mocked separately below.
	settings: { grinderModel: '' } as Record<string, unknown>,
	cfg: {
		includeProfile: true,
		includeNotes: true,
		privacy: 'unlisted'
	} as Record<string, unknown>,
	exportJson: JSON.stringify({ profile: { title: 'P' }, metadata: { notes: 'tasty' } }),
	beanVisualizerId: null as string | null,
	actions: [] as unknown[]
}));

vi.mock('#lib/settings/index.js', () => ({ getSettingsStore: () => ({ current: h.settings }) }));
vi.mock('#lib/visualizer/sync-config.js', async (importOriginal) => ({
	...(await importOriginal<typeof import('#lib/visualizer/sync-config.js')>()),
	readSyncConfig: () => h.cfg
}));
vi.mock('#lib/history/v2-export.js', () => ({ exportStoredShotAsV2Json: () => h.exportJson }));
vi.mock('#lib/bean/store.svelte.js', () => ({
	getBeanStore: () => ({ getBean: () => ({ visualizerId: h.beanVisualizerId }) })
}));
vi.mock('#lib/visualizer/shot-sync-signatures.js', () => ({
	signatureForShot: () => 'SIG-FIXED',
	signatureForBean: () => 'b',
	signatureForRoaster: () => 'r',
	reconcileShots: () => h.actions,
	storedShotFromWire: () => null
}));

import { ShotSync, ShotSyncLive } from './shot-sync.ts';
import { writeSyncSettings } from '#lib/bean/visualizer-sync.js';

/** Seed the bean-sync premium flag the edit sync reads (`undefined` = never written → null). */
const setPremium = (premium: boolean | null | undefined): void => {
	localStorage.clear();
	if (premium !== undefined) writeSyncSettings({ premium });
};
import { HttpClient, type HttpRequest } from './http-client.ts';
import { TokenVault } from './token-vault.ts';
import { HttpStatusError } from '../effect/errors.ts';
import type { StoredShot } from '#lib/history/model.js';
import type { HistoryStore } from '#lib/history/store.svelte.js';
import type { TokenSet } from '../visualizer/oauth.ts';

function mkHttp(
	handler: (
		req: HttpRequest
	) => { ok: true; status?: number; json?: unknown } | { ok: false; status: number; body?: string }
) {
	const calls: HttpRequest[] = [];
	const layer = Layer.succeed(
		HttpClient,
		HttpClient.of({
			request: (req) => {
				calls.push(req);
				const r = handler(req);
				if (!r.ok) return Effect.fail(new HttpStatusError({ status: r.status, url: req.url, body: r.body }));
				const status = r.status ?? 200;
				return Effect.succeed(
					new Response(JSON.stringify(r.json ?? {}), {
						status,
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

function run<A, E>(program: Effect.Effect<A, E, ShotSync>, http: Layer.Layer<HttpClient>) {
	return Effect.runPromiseExit(
		Effect.provide(program, Layer.provide(ShotSyncLive, Layer.merge(http, vault)))
	);
}

/** A minimal StoredShot — only the fields uploadShot reads matter here. */
const shot = (over: Partial<StoredShot> = {}): StoredShot =>
	({
		id: 's1',
		bean: null,
		tags: [],
		grinderModel: null,
		record: { duration: 30_000, samples: [{ t: 0 }] },
		...over
	}) as unknown as StoredShot;

const postBody = (calls: HttpRequest[]) =>
	JSON.parse(calls.find((c) => c.method === 'POST')!.body as string) as Record<string, unknown>;
const patchCall = (calls: HttpRequest[]) => calls.find((c) => c.method === 'PATCH');

beforeEach(() => {
	localStorage.clear();
	h.settings = { grinderModel: '' };
	h.cfg = { includeProfile: true, includeNotes: true, privacy: 'unlisted' };
	h.exportJson = JSON.stringify({ profile: { title: 'P' }, metadata: { notes: 'tasty' } });
	h.beanVisualizerId = null;
});

describe('ShotSync.uploadShot — payload shaping', () => {
	it('injects metadata.crema (localId + signature) and the privacy setting', async () => {
		const { layer, calls } = mkHttp(() => ({ ok: true, json: { id: 'viz-1' } }));
		const exit = await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot()))), layer);
		expect(Exit.isSuccess(exit)).toBe(true);
		const body = postBody(calls);
		expect(body.privacy).toBe('unlisted');
		const crema = (body.metadata as Record<string, unknown>).crema as Record<string, unknown>;
		expect(crema.localId).toBe('s1');
		expect(crema.signature).toBe('SIG-FIXED');
		// Defaults keep profile + notes.
		expect(body.profile).toBeDefined();
		expect((body.metadata as Record<string, unknown>).notes).toBe('tasty');
	});

	it('drops the profile when includeProfile is off', async () => {
		h.cfg.includeProfile = false;
		const { layer, calls } = mkHttp(() => ({ ok: true, json: { id: 'viz-1' } }));
		await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot()))), layer);
		expect(postBody(calls).profile).toBeUndefined();
	});

	it('nulls the notes when includeNotes is off', async () => {
		h.cfg.includeNotes = false;
		const { layer, calls } = mkHttp(() => ({ ok: true, json: { id: 'viz-1' } }));
		await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot()))), layer);
		expect((postBody(calls).metadata as Record<string, unknown>).notes).toBeNull();
	});
});

describe('ShotSync.uploadShot — the no-telemetry guard (#330)', () => {
	it('non_brew_no_samples_refused: refuses before any request', async () => {
		const { layer, calls } = mkHttp(() => ({ ok: true, json: { id: 'viz-1' } }));
		const bare = shot({ record: { duration: 30_000, samples: [] } } as Partial<StoredShot>);

		const out = await run(
			ShotSync.pipe(
				Effect.flatMap((s) => s.uploadShot(bare)),
				Effect.flip
			),
			layer
		);

		expect(out).toMatchObject({ _tag: 'Success', value: { _tag: 'ShotWithoutSeriesError' } });
		expect(calls).toEqual([]);
	});
});

describe('ShotSync.uploadShot — post-upload PATCH', () => {
	it('fires a follow-up PATCH carrying tag_list when the shot has tags (premium)', async () => {
		setPremium(true);
		const { layer, calls } = mkHttp((req) =>
			req.method === 'POST' ? { ok: true, json: { id: 'viz-1' } } : { ok: true, json: {} }
		);
		await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot({ tags: ['espresso'] })))), layer);
		const patch = patchCall(calls);
		expect(patch).toBeDefined();
		expect(patch!.url).toContain('/shots/viz-1');
		const shotBody = (JSON.parse(patch!.body as string) as { shot: Record<string, unknown> }).shot;
		expect(shotBody.tag_list).toEqual(['espresso']);
	});

	it('does NOT fire a PATCH when there is nothing to patch', async () => {
		const { layer, calls } = mkHttp(() => ({ ok: true, json: { id: 'viz-1' } }));
		await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot()))), layer);
		expect(patchCall(calls)).toBeUndefined();
	});

	it('skips the follow-up PATCH on a free account when tags are all it would carry', async () => {
		setPremium(false);
		const { layer, calls } = mkHttp(() => ({ ok: true, json: { id: 'viz-1' } }));
		const exit = await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot({ tags: ['espresso'] })))), layer);
		expect(Exit.isSuccess(exit)).toBe(true);
		expect(patchCall(calls)).toBeUndefined();
	});

	it('keeps the upload successful even when the follow-up PATCH fails (soft)', async () => {
		setPremium(true);
		const { layer, calls } = mkHttp((req) =>
			req.method === 'POST' ? { ok: true, json: { id: 'viz-1' } } : { ok: false, status: 500 }
		);
		const exit = await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot({ tags: ['x'] })))), layer);
		expect(Exit.isSuccess(exit)).toBe(true); // PATCH 500 swallowed
		expect(patchCall(calls)).toBeDefined(); // it WAS attempted
		if (Exit.isSuccess(exit)) expect(exit.value.visualizerId).toBe('viz-1');
		// The drain sends them again once the row is bound.
		expect(pendingEdit('s1')).toMatchObject({ annotations: true, tags: { add: ['x'], remove: [] } });
	});

	it('fails ResponseDecodeError when upload returns no id', async () => {
		const { layer } = mkHttp(() => ({ ok: true, json: {} }));
		const exit = await run(ShotSync.pipe(Effect.flatMap((s) => s.uploadShot(shot()))), layer);
		expect(Exit.isFailure(exit)).toBe(true);
	});
});

describe('ShotSync.patchEditedShot — pending-only PATCHes', () => {
	const PREMIUM_ONLY = ['private_notes', 'flavor', 'tag_list', 'coffee_bag_id', 'metadata', 'image'];
	const edited = (over: Partial<StoredShot> = {}): StoredShot =>
		({
			id: 's1',
			visualizerId: 'viz-1',
			bean: null,
			tags: ['crema'],
			grinderModel: null,
			metadata: { rating: 4, notes: 'juicy' },
			...over
		}) as unknown as StoredShot;
	const historyOf = (s: StoredShot) =>
		({
			all: [s],
			setTags: (_id: string, tags: string[]) => {
				(s as { tags?: string[] }).tags = tags;
			}
		}) as unknown as HistoryStore;
	/** Mark `edit` on `s` as the History page does, then send it. */
	const editAndPush = (
		s: StoredShot,
		layer: Layer.Layer<HttpClient>,
		edit: ShotEdit = 'annotations',
		history: HistoryStore = historyOf(s)
	) => {
		markShotEdit(s.id, edit);
		return run(ShotSync.pipe(Effect.flatMap((sync) => sync.pushPendingEdit(history, s.id))), layer);
	};
	const patchBodies = (calls: { method?: string; body?: unknown }[]) =>
		calls
			.filter((c) => c.method === 'PATCH')
			.map((c) => (JSON.parse(c.body as string) as { shot: Record<string, unknown> }).shot);
	const bean = { name: 'Kenya AA', roasterName: 'Sey' } as unknown as StoredShot['bean'];

	for (const premium of [false, null, undefined]) {
		it(`a ${premium === false ? 'free' : 'not-yet-probed'} account's journal edit sends no Premium-only field and no barista`, async () => {
			setPremium(premium);
			const { layer, calls } = mkHttp(() => ({ ok: true, json: {} }));
			const exit = await editAndPush(edited(), layer);
			expect(Exit.isSuccess(exit)).toBe(true);
			for (const body of patchBodies(calls)) {
				for (const key of PREMIUM_ONLY) expect(body).not.toHaveProperty(key);
				expect(body).not.toHaveProperty('barista');
			}
		});

		it(`a ${premium === false ? 'free' : 'not-yet-probed'} account PATCHes the rest without the Premium-only fields`, async () => {
			setPremium(premium);
			const { layer, calls } = mkHttp(() => ({ ok: true, json: {} }));
			const exit = await editAndPush(edited({ bean }), layer);
			expect(Exit.isSuccess(exit)).toBe(true);
			const body = (JSON.parse(patchCall(calls)!.body as string) as { shot: Record<string, unknown> }).shot;
			for (const key of PREMIUM_ONLY) expect(body).not.toHaveProperty(key);
			expect(body).toMatchObject({ bean_brand: 'Sey', bean_type: 'Kenya AA' });
		});

		it(`a ${premium === false ? 'free' : 'not-yet-probed'} account's tag edit waits, with no request (free_tag_edit_pushes_after_upgrade)`, async () => {
			setPremium(premium);
			const s = edited({ tags: ['crema', 'washed'] });
			const { layer, calls } = mkHttp(() => ({ ok: true, json: { tags: ['crema', 'washed'] } }));
			const exit = await editAndPush(s, layer, { tags: { before: ['crema'], after: ['crema', 'washed'] } });
			expect(exit).toMatchObject({ _tag: 'Success', value: 'waiting' });
			expect(calls).toEqual([]);
			expect(pendingEdit('s1')?.tags).toEqual({ add: ['washed'], remove: [] });

			// An upgrade carries the tags up.
			writeSyncSettings({ premium: true });
			const after = await run(
				ShotSync.pipe(Effect.flatMap((sync) => sync.pushPendingEdit(historyOf(s), 's1'))),
				layer
			);
			expect(after).toMatchObject({ _tag: 'Success', value: 'sent' });
			expect(calls.map((c) => c.method ?? 'GET')).toEqual(['GET', 'PATCH']);
			expect(pendingEdit('s1')).toBeUndefined();
		});
	}

	it('a barista edit sends it: cleared as an empty string, set trimmed', async () => {
		setPremium(false);
		const { layer, calls } = mkHttp(() => ({ ok: true, json: {} }));
		await editAndPush(edited({ metadata: { rating: 4, barista: null } } as Partial<StoredShot>), layer, 'barista');
		await editAndPush(edited({ metadata: { rating: 4, barista: ' Ana ' } } as Partial<StoredShot>), layer, 'barista');
		expect(patchBodies(calls).map((b) => b.barista)).toEqual(['', 'Ana']);
	});

	it('rating_patch_sends_no_tags: a rating edit sends the journal, not the tags or the barista', async () => {
		setPremium(true);
		const { layer, calls } = mkHttp(() => ({ ok: true, json: {} }));
		await editAndPush(edited({ metadata: { rating: 5 } } as Partial<StoredShot>), layer);
		const [body] = patchBodies(calls);
		expect(body).toMatchObject({ flavor: 15 });
		expect(body).not.toHaveProperty('tag_list');
		expect(body).not.toHaveProperty('barista');
		expect(calls.map((c) => c.method)).toEqual(['PATCH']);
	});

	it('a premium journal edit still sends private_notes and flavor', async () => {
		setPremium(true);
		const { layer, calls } = mkHttp(() => ({ ok: true, json: {} }));
		await editAndPush(edited(), layer);
		const body = (JSON.parse(patchCall(calls)!.body as string) as { shot: Record<string, unknown> }).shot;
		expect(body).toMatchObject({ flavor: 12, private_notes: '<p>juicy</p>' });
		expect(pendingEdit('s1')).toBeUndefined();
	});

	it('a barista edit whose PATCH failed rides the next push, then stops once it landed', async () => {
		setPremium(true);
		let fail = true;
		const { layer, calls } = mkHttp(() => (fail ? { ok: false, status: 500 } : { ok: true, json: {} }));
		const s = edited({ metadata: { rating: 4, barista: 'Ana' } } as Partial<StoredShot>);
		const first = await editAndPush(s, layer, 'barista');
		expect(Exit.isFailure(first)).toBe(true);
		expect(pendingEdit('s1')).toMatchObject({ barista: true, attempts: 1 });
		fail = false;
		await editAndPush(s, layer); // a later rating edit carries it
		await editAndPush(s, layer); // and another doesn't
		expect(patchBodies(calls).map((b) => b.barista)).toEqual(['Ana', 'Ana', undefined]);
	});

	it('failed_patch_retries_from_current_row: the retry sends the newest values', async () => {
		setPremium(true);
		let fail = true;
		const { layer, calls } = mkHttp(() => (fail ? { ok: false, status: 503 } : { ok: true, json: {} }));
		const s = edited({ metadata: { rating: 3 } } as Partial<StoredShot>);
		await editAndPush(s, layer);
		(s.metadata as { rating?: number }).rating = 5;
		fail = false;
		const retry = await run(
			ShotSync.pipe(Effect.flatMap((sync) => sync.pushPendingEdit(historyOf(s), 's1'))),
			layer
		);
		expect(Exit.isSuccess(retry)).toBe(true);
		expect(patchBodies(calls).map((b) => b.flavor)).toEqual([9, 15]);
		expect(pendingEdit('s1')).toBeUndefined();
	});

	it('an edit made while its PATCH was out stays marked', async () => {
		setPremium(true);
		const s = edited({ metadata: { rating: 3 } } as Partial<StoredShot>);
		const { layer } = mkHttp(() => {
			(s.metadata as { rating?: number }).rating = 4;
			return { ok: true, json: {} };
		});
		await editAndPush(s, layer);
		expect(pendingEdit('s1')).toMatchObject({ annotations: true });
	});

	it('tag_push_keeps_website_changes: GET first, then (remote ∪ add) − remove', async () => {
		setPremium(true);
		const s = edited({ tags: ['a', 'c'] });
		const { layer, calls } = mkHttp((req) =>
			req.method === 'PATCH'
				? { ok: true, json: { tags: ['w', 'c', 'a'] } }
				: { ok: true, json: { id: 'viz-1', tags: ['a', 'b', 'w'] } }
		);
		const exit = await editAndPush(s, layer, { tags: { before: ['a', 'b'], after: ['a', 'c'] } });
		expect(exit).toMatchObject({ _tag: 'Success', value: 'sent' });
		expect(calls.map((c) => c.method ?? 'GET')).toEqual(['GET', 'PATCH']);
		expect(patchBodies(calls)).toEqual([{ tag_list: ['a', 'w', 'c'] }]);
		// The website's tag arrives here too, and nothing is left to send.
		expect(s.tags).toEqual(['a', 'c', 'w']);
		expect(pendingEdit('s1')).toBeUndefined();
	});

	it('tag_clear_reaches_remote: removing the last tag sends []', async () => {
		setPremium(true);
		const s = edited({ tags: [] });
		const { layer, calls } = mkHttp((req) =>
			req.method === 'PATCH' ? { ok: true, json: { tags: [] } } : { ok: true, json: { tags: ['crema'] } }
		);
		await editAndPush(s, layer, { tags: { before: ['crema'], after: [] } });
		expect(patchBodies(calls)).toEqual([{ tag_list: [] }]);
		expect(pendingEdit('s1')).toBeUndefined();
	});

	it('a tag added while the push was out stays marked', async () => {
		setPremium(true);
		const s = edited({ tags: ['a'] });
		const { layer } = mkHttp((req) => {
			if (req.method !== 'PATCH') return { ok: true, json: { tags: [] } };
			markShotEdit('s1', { tags: { before: ['a'], after: ['a', 'b'] } });
			(s as { tags?: string[] }).tags = ['a', 'b'];
			return { ok: true, json: { tags: ['a'] } };
		});
		await editAndPush(s, layer, { tags: { before: [], after: ['a'] } });
		expect(pendingEdit('s1')?.tags).toEqual({ add: ['b'], remove: [] });
	});

	it('tag_400_is_a_tier_signal: a 400 on a tags-only PATCH keeps the marks for the tier check', async () => {
		setPremium(true);
		const s = edited({ tags: ['a'] });
		const { layer, calls } = mkHttp((req) =>
			req.method === 'PATCH' ? { ok: false, status: 400, body: '{"error":"param is missing"}' } : { ok: true, json: { tags: [] } }
		);
		const exit = await editAndPush(s, layer, { tags: { before: [], after: ['a'] } });
		expect(Exit.isFailure(exit)).toBe(true);
		expect(pendingEdit('s1')).toMatchObject({ tierSignal: true, tags: { add: ['a'] } });
		// Until the tier is checked, the tags aren't sent again.
		const again = await run(
			ShotSync.pipe(Effect.flatMap((sync) => sync.pushPendingEdit(historyOf(s), 's1'))),
			layer
		);
		expect(again).toMatchObject({ _tag: 'Success', value: 'waiting' });
		expect(calls).toHaveLength(2);
	});

	it('a reply without the tags sent is a tier signal too', async () => {
		setPremium(true);
		const s = edited({ tags: ['a'] });
		const { layer } = mkHttp((req) =>
			req.method === 'PATCH' ? { ok: true, json: { tags: [] } } : { ok: true, json: { tags: [] } }
		);
		const exit = await editAndPush(s, layer, { tags: { before: [], after: ['a'] } });
		expect(exit).toMatchObject({ _tag: 'Success', value: 'tier' });
		expect(pendingEdit('s1')).toMatchObject({ tierSignal: true, tags: { add: ['a'] } });
	});

	for (const [status, body] of [
		[404, '{}'],
		[403, '{"error":"You are not authorized to perform this action."}'],
		[422, '{"error":"Invalid"}']
	] as const) {
		it(`terminal_patch_clears_mark: ${status} clears it and says so`, async () => {
			setPremium(true);
			const { layer } = mkHttp(() => ({ ok: false, status, body }));
			const exit = await editAndPush(edited(), layer, 'barista');
			expect(Exit.isFailure(exit)).toBe(true);
			expect(pendingEdit('s1')).toBeUndefined();
			const real = await vi.importActual<typeof import('#lib/visualizer/sync-config.js')>(
				'#lib/visualizer/sync-config.js'
			);
			expect(real.readSyncConfig().log.some((l) => l.error?.startsWith("This edit can't reach Visualizer"))).toBe(true);
		});
	}

	it('a 503 keeps the mark for a later try', async () => {
		setPremium(true);
		const { layer } = mkHttp(() => ({ ok: false, status: 503 }));
		const before = Date.now();
		await editAndPush(edited(), layer);
		const edit = pendingEdit('s1');
		expect(edit).toMatchObject({ annotations: true, attempts: 1 });
		expect(edit?.nextAttemptAt ?? 0).toBeGreaterThan(before);
	});
});

describe('ShotSync pull merges through the core', () => {
	const local = (id: string, barista?: string | null, over: Partial<StoredShot> = {}): StoredShot =>
		({
			id,
			visualizerId: 'v',
			metadata: { barista },
			tags: [],
			record: { duration: 1, samples: [{ t: 0 }] },
			...over
		}) as unknown as StoredShot;
	const remote = (id: string, barista?: string | null, over: Record<string, unknown> = {}) =>
		({ id, clock: 1, duration_ms: 1, profile_title: null, final_weight_g: null, notes: null, rating: null, updated_at_ms: 5_000, tag_list: [], barista, ...over }) as never;
	/** A history whose `applyRemote` applies the barista and tags, recording each patch. */
	function historyOf(shots: StoredShot[]) {
		const applied: [string, Record<string, unknown>][] = [];
		const history = {
			get all() {
				return shots;
			},
			get rawAll() {
				return shots;
			},
			get: (id: string) => shots.find((x) => x.id === id),
			bindVisualizerId: vi.fn(),
			insertPulled: vi.fn(),
			applyRemote: (id: string, patch: Record<string, unknown>) => {
				applied.push([id, patch]);
				const s = shots.find((x) => x.id === id)!;
				if (typeof patch.barista === 'string') (s.metadata as Record<string, unknown>).barista = patch.barista;
				if (Array.isArray(patch.tags)) (s as { tags?: unknown }).tags = patch.tags;
			}
		} as unknown as HistoryStore;
		return { history, applied };
	}
	const reconcile = (history: HistoryStore, before?: Map<string, never>) => {
		const { layer } = mkHttp(() => ({ ok: true, json: {} }));
		return run(
			ShotSync.pipe(Effect.flatMap((sync) => sync.applyShotReconciliation(history, [], undefined, undefined, before))),
			layer
		);
	};

	it('the update and bind actions fill a blank barista, never a set one', async () => {
		const shots = [local('pulled-before'), local('recorded', null), local('named', 'Bo')];
		const { history, applied } = historyOf(shots);
		h.actions = [
			{ kind: 'update', localId: 'pulled-before', remote: remote('r1', 'Ana') },
			{ kind: 'bind', localId: 'recorded', visualizerId: 'r2', remote: remote('r2', 'Cy') },
			{ kind: 'update', localId: 'named', remote: remote('r3', 'Ana') }
		];
		const exit = await reconcile(history);
		h.actions = [];
		expect(Exit.isSuccess(exit)).toBe(true);
		expect(applied.map(([id, p]) => [id, p.barista ?? null, p.remoteVersion])).toEqual([
			['pulled-before', 'Ana', 5],
			['recorded', 'Cy', 5],
			['named', null, 5]
		]);
	});

	it('free_pull_keeps_tags: remote tags only add, and a pending tag edit wins', async () => {
		const shots = [
			local('kept', null, { tags: ['washed'] }),
			local('added', null, { tags: ['washed'] }),
			local('edited', null, { tags: ['washed'] })
		];
		markShotEdit('edited', { tags: { before: ['washed', 'gesha'], after: ['washed'] } });
		const { history } = historyOf(shots);
		h.actions = [
			{ kind: 'update', localId: 'kept', remote: remote('r1', null, { tag_list: [] }) },
			{ kind: 'update', localId: 'added', remote: remote('r2', null, { tag_list: ['Gesha'] }) },
			{ kind: 'update', localId: 'edited', remote: remote('r3', null, { tag_list: ['washed', 'gesha'] }) }
		];
		await reconcile(history);
		h.actions = [];
		expect(shots.map((s) => s.tags)).toEqual([['washed'], ['washed', 'Gesha'], ['washed']]);
	});

	it('tag_only_patch_during_fetch_not_reverted: a row edited since the pull began is left alone', async () => {
		// The pull began with a tag mark; a PATCH landed and cleared it meanwhile.
		const shots = [local('s1', null, { tags: ['a', 'b'] })];
		const before = new Map([
			['s1', { tags: ['a', 'b'], tagsPending: true, baristaPending: false, hasSeries: true, hasProfile: false, isBrew: false }]
		]) as never;
		const { history, applied } = historyOf(shots);
		h.actions = [{ kind: 'update', localId: 's1', remote: remote('r1', 'Ana', { tag_list: ['a'] }) }];
		const exit = await reconcile(history, before);
		h.actions = [];
		expect(applied).toEqual([]);
		expect(exit).toMatchObject({ _tag: 'Success', value: { held: 0 } });
	});

	it('edited_row_without_series_holds_cursor: the skip holds when the row still needs its curve', async () => {
		const shots = [local('s1', null, { record: { duration: 1, samples: [] } } as Partial<StoredShot>)];
		const before = new Map([
			['s1', { tags: [], tagsPending: false, barista: 'Bo', baristaPending: false, hasSeries: false, hasProfile: false, isBrew: false }]
		]) as never;
		const { history } = historyOf(shots);
		h.actions = [{ kind: 'update', localId: 's1', remote: remote('r1') }];
		const exit = await reconcile(history, before);
		h.actions = [];
		expect(exit).toMatchObject({ _tag: 'Success', value: { held: 1 } });
	});
});

describe('ShotSync: an offline barista clear survives the next pull (V3 #6, V2 P3-4)', () => {
	const remote = (id: string, barista: string) =>
		({ id, clock: 1, duration_ms: 1, profile_title: null, final_weight_g: null, notes: null, rating: null, updated_at_ms: null, tag_list: [], barista }) as never;

	function cleared() {
		const shot = {
			id: 's1',
			visualizerId: 'viz-1',
			bean: null,
			tags: [],
			grinderModel: null,
			record: { duration: 1, samples: [{ t: 0 }] },
			metadata: { rating: 4, barista: null }
		} as unknown as StoredShot;
		const filled = vi.fn((_id: string, patch: { barista?: string }) => {
			if (patch.barista) (shot.metadata as unknown as Record<string, unknown>).barista = patch.barista;
		});
		const history = {
			all: [shot],
			rawAll: [shot],
			get: (id: string) => (id === shot.id ? shot : undefined),
			bindVisualizerId: vi.fn(),
			insertPulled: vi.fn(),
			applyRemote: filled
		} as unknown as HistoryStore;
		return { shot, history, filled };
	}
	const patchBaristas = (calls: HttpRequest[]) =>
		calls
			.filter((c) => c.method === 'PATCH')
			.map((c) => (JSON.parse(c.body as string) as { shot: Record<string, unknown> }).shot.barista);
	const baristaFills = (filled: ReturnType<typeof cleared>['filled']) =>
		filled.mock.calls.filter(([, p]) => p.barista !== undefined);

	it('a failed clear is not refilled by a pull, in this session or the next, and is sent by the next edit', async () => {
		setPremium(true);
		const t = cleared();
		let fail = true;
		const { layer, calls } = mkHttp(() => (fail ? { ok: false, status: 503 } : { ok: true, json: {} }));
		// The clear's PATCH fails (offline).
		markShotEdit('s1', 'barista');
		const first = await run(
			ShotSync.pipe(
				Effect.flatMap((sync) =>
					Effect.gen(function* () {
						const exit = yield* Effect.exit(sync.patchEditedShot(t.history, 's1'));
						// A pull in the same session: the remote still has "Ana".
						h.actions = [{ kind: 'update', localId: 's1', remote: remote('viz-1', 'Ana') }];
						yield* sync.applyShotReconciliation(t.history, []);
						return exit;
					})
				)
			),
			layer
		);
		expect(Exit.isSuccess(first) && Exit.isFailure(first.value)).toBe(true);
		expect(baristaFills(t.filled)).toEqual([]);

		// A restart (a fresh layer): the mark was persisted, so the pull still
		// leaves the clear alone, and the next rating edit carries it.
		fail = false;
		await run(
			ShotSync.pipe(
				Effect.flatMap((sync) =>
					Effect.gen(function* () {
						yield* sync.applyShotReconciliation(t.history, []);
						markShotEdit('s1', 'annotations');
						yield* sync.patchEditedShot(t.history, 's1');
					})
				)
			),
			layer
		);
		expect(baristaFills(t.filled)).toEqual([]);
		expect(t.shot.metadata.barista).toBeNull();
		expect(patchBaristas(calls)).toEqual(['', '']);

		// The clear landed: the mark is gone and the fill rule applies again.
		await run(
			ShotSync.pipe(Effect.flatMap((sync) => sync.applyShotReconciliation(t.history, []))),
			layer
		);
		h.actions = [];
		expect(baristaFills(t.filled).map(([id, p]) => [id, p.barista])).toEqual([['s1', 'Ana']]);
	});
});
