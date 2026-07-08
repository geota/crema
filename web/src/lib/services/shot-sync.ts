/**
 * `$lib/services/shot-sync` — the `ShotSync` service (docs/53 §1.3, §2.4, T-12).
 *
 * Syncs Crema's local shot library against Visualizer's `/shots*` endpoints (per the
 * vendored OpenAPI spec at `../visualizer/openapi.json`). It consumes
 * `HttpClient` + `TokenVault`:
 *
 *  - every authenticated call funnels through `TokenVault.withFreshToken`
 *    (proactive refresh + one-shot 401 retry), exactly as the old `call()`
 *    funnelled through `withFreshToken`;
 *  - `HttpClient.request` raises `HttpStatusError` on any non-2xx, which `call`
 *    maps back onto the Visualizer error taxonomy: 402/403 → premium-gated,
 *    404 → not-found, 401 handled upstream by `TokenVault`, everything else
 *    propagates as `HttpStatusError` (the old `'other'` bucket).
 *
 * **Behavior is preserved bit-for-bit** with the old module (plan §4): same wire
 * bytes, same premium-gate probe, the soft follow-up PATCH on upload, the
 * `Effect.iterate` pagination walk's stop condition, the per-detail
 * re-throw-on-auth / swallow-others, and the snapshot-vs-live-bean discipline.
 * The pure converters (`wireShotFromDetail`, `samplesFromVisualizerDetail`, the
 * inline-bean / grinder / tag resolvers, `buildShotPayload`) are copied verbatim
 * from the old module; the pinned-digest signature helpers stay in
 * `../visualizer/shot-sync-signatures.ts` (untouched).
 *
 * `ShotSyncLive` is the production implementation, composed into `AppLayer`.
 *
 * **No automated behavioral coverage**: `shot-sync.test.ts` drives the wasm
 * bundle directly, not this module, and `ShotSync` is store-coupled
 * (`$lib/bean`, `$lib/history`, `$lib/settings`) so it isn't node:test-able.
 * Correctness rests on the careful port below + a manual browser smoke test
 * (connect a real Visualizer account; upload + delete a shot; pull/reconcile).
 */

import { Context, Effect, Layer, Option } from 'effect';
import { HttpClient } from './http-client.ts';
import { TokenVault } from './token-vault.ts';
import { NotAuthenticatedError, ResponseDecodeError, VisualizerNotFoundError } from '../effect/errors.ts';
import {
	API_BASE,
	describeVisualizerError,
	isRecoverable,
	visualizerCall,
	type VisualizerCallError,
	type VisualizerCallOptions
} from './visualizer-call.ts';
import {
	ShotUploadResultSchema,
	ShotListResponseSchema,
	decodeResponse
} from '../effect/schema/visualizer.ts';
import { getBeanStore } from '$lib/bean/store.svelte';
import { exportStoredShotAsV2Json } from '$lib/history/v2-export';
import type { ShotPatchInputs, TimedSample } from '$lib/core';
import type { ShotBean, StoredShot } from '$lib/history/model';
import type { HistoryStore } from '$lib/history/store.svelte';
import { getSettingsStore } from '$lib/settings';
import type { components } from '$lib/visualizer/openapi';
import {
	reconcileShots,
	signatureForShot,
	storedShotFromWire,
	type WireShot
} from '$lib/visualizer/shot-sync-signatures';
import { appendSyncLog, readSyncConfig } from '$lib/visualizer/sync-config';
import {
	samplesFromVisualizerDetail as wasmSamplesFromVisualizerDetail,
	wireShotFromDetail as wasmWireShotFromDetail,
	parseV2Profile as wasmParseV2Profile,
	visualizerShotPatchJson as wasmVisualizerShotPatchJson
} from '$lib/wasm/de1_wasm';
import { enqueueEntry as enqueueSyncOp } from './queue-store.ts';

// Spec-typed aliases — these ride DIRECTLY on the wire so they stay
// in lock-step with the OpenAPI spec (regenerate via `pnpm openapi`).
type ShotSummary = components['schemas']['ShotSummary'];
type Paging = components['schemas']['Paging'];
type ShotDetail = components['schemas']['ShotDetail'];
type ShotUpdateRequest = components['schemas']['ShotUpdateRequest'];

export type { ShotSummary, ShotDetail, Paging };

// ── Settings-aware payload builder (copied verbatim from the old module) ──

/**
 * Build the JSON payload `POST /shots/upload` accepts. Crema's
 * `exportStoredShotAsV2Json` produces the Decent v2 shape; applies the four
 * `visualizer*` user settings (privacy, includeProfile, includeNotes) so the
 * user's choices ride on the wire, plus a Crema-side escape-valve block so the
 * round-trip back can re-bind.
 */
function buildShotPayload(shot: StoredShot): Record<string, unknown> {
	const cfg = readSyncConfig();
	const json = JSON.parse(exportStoredShotAsV2Json(shot)) as Record<string, unknown>;

	if (!cfg.includeProfile) {
		delete json.profile;
	}
	if (!cfg.includeNotes) {
		const metadata = (json.metadata as Record<string, unknown> | undefined) ?? null;
		if (metadata && 'notes' in metadata) {
			metadata.notes = null;
		}
	}

	// Per-shot override wins; absent/null inherits the sync-config default.
	json.privacy = shot.privacy ?? cfg.privacy;
	json.metadata = {
		...((json.metadata as Record<string, unknown> | undefined) ?? {}),
		crema: {
			localId: shot.id,
			signature: signatureForShot(shot),
			appVersion: '0.0.1'
		}
	};

	return json;
}

// ── Wire / Crema boundary helpers (copied verbatim from the old module) ───

/**
 * Convert a full `ShotDetail` (returned from `GET /shots/{id}`) plus the
 * originating summary's `clock` / `updated_at` into Crema's {@link WireShot}
 * (unix MS, normalised field names). Visualizer's default detail flattens
 * scoring + journal fields onto the shot row; Beanconqueror's variant nests them
 * under `meta.visualizer` — both shapes are read defensively in the Rust core
 * (`de1_domain::visualizer_wire::wire_shot_from_detail`, CORE1). `clock` /
 * `updated_at` ride in as unix SECONDS; the core converts to ms.
 */
function wireShotFromDetail(summary: ShotSummary, detail: ShotDetail): WireShot {
	const raw = wasmWireShotFromDetail(
		JSON.stringify({
			summary: { id: summary.id, clock: summary.clock, updated_at: summary.updated_at },
			detail
		})
	);
	return JSON.parse(raw) as WireShot;
}

/**
 * Reconstruct Crema's per-sample telemetry from a Visualizer shot detail.
 *
 * Visualizer stores the curve as parallel columns — a time axis plus one array
 * per channel — zipped by index into the row-shaped {@link TimedSample} the
 * history chart + peak derivation consume (time axis SECONDS → ms). A detail
 * with no time axis (a Beanconqueror row, or a curveless shot) yields `[]`.
 * Delegates to `de1_domain::visualizer_wire::samples_from_visualizer_detail`
 * (CORE1) so every shell zips the columns identically.
 */
function samplesFromVisualizerDetail(detail: ShotDetail): TimedSample[] {
	return JSON.parse(wasmSamplesFromVisualizerDetail(JSON.stringify(detail))) as TimedSample[];
}

// ── PATCH-body resolvers ──────────────────────────────────────────────────
//
// The body ASSEMBLY (inline-bean field mapping, rating→flavor, key naming,
// blank-skipping) lives in the core — `de1_domain::visualizer_shot_patch`,
// review #42: this module and Android's `VisualizerSync` had drifted (the
// Kotlin builder sent only `bean_brand`/`bean_type`, and the shells
// disagreed on what a cleared rating does to `flavor`). What remains here
// are the SHELL-side resolvers: config lookups and the live-vs-snapshot
// reading discipline the core can't know about.

/**
 * Resolve the shot's equipment-level grinder model for the post-upload PATCH.
 * Cascade (per #81): snapshot {@link StoredShot.grinderModel} wins, then the
 * equipment-level `settings.prefs.grinderModel` default (legacy fallback), then
 * `null` (omit the field so a blank doesn't clobber a Visualizer-side edit).
 */
export function resolveGrinderModel(
	shot: StoredShot,
	settingsDefault: string | undefined | null
): string | null {
	const fromShot = shot.grinderModel?.trim();
	if (fromShot) return fromShot;
	const fromSettings = settingsDefault?.trim();
	if (fromSettings) return fromSettings;
	return null;
}

/**
 * Resolve the `coffee_bag_id` link pointer for a shot's post-upload PATCH. The
 * snapshot is the content source of truth — but the bag's *Visualizer id* is a
 * stable mutable link, so we read it from the LIVE bean every time. This is THE
 * ONLY place the live bean is read on the upload path.
 */
function resolveCoffeeBagId(shot: StoredShot): string | null {
	const liveBeanId = shot.bean?.beanId ?? null;
	if (!liveBeanId) return null;
	try {
		const liveBean = getBeanStore().getBean(liveBeanId);
		return liveBean?.visualizerId ?? null;
	} catch {
		return null;
	}
}

/**
 * Resolve the shot's `tag_list` for the post-upload PATCH. Trim + dedup
 * (case-sensitive, preserve first-seen order). Returns `null` when empty so the
 * caller can skip the field entirely — sending `[]` would clobber tags the user
 * added on Visualizer's web side.
 */
function resolveTagList(shot: StoredShot): string[] | null {
	const merged: string[] = [];
	const seen = new Set<string>();
	for (const raw of shot.tags ?? []) {
		const t = raw.trim();
		if (!t || seen.has(t)) continue;
		seen.add(t);
		merged.push(t);
	}
	return merged.length > 0 ? merged : null;
}

// ── Call-error policy local to ShotSync ───────────────────────────────────
// The shared call + taxonomy + `isRecoverable` + `describeVisualizerError` live
// in `visualizer-call.ts`. These two predicates have a single caller each
// (`pullAllShotsSince` / `uploadUnsyncedShots`), so they stay local rather than
// being hoisted into a shared module they'd be the only outside user of.

/** `'auth'`-equivalent failures — re-thrown rather than swallowed on a pull. */
function isAuthError(e: VisualizerCallError): boolean {
	return (
		e._tag === 'NotAuthenticatedError' ||
		e._tag === 'TokenRefreshFailedError' ||
		(e._tag === 'HttpStatusError' && e.status === 401)
	);
}

/** Auth / premium failures abort the unsynced-shots loop (no point retrying). */
function shouldAbortLoop(e: VisualizerCallError | ResponseDecodeError): boolean {
	return (
		e._tag === 'NotAuthenticatedError' ||
		e._tag === 'TokenRefreshFailedError' ||
		(e._tag === 'HttpStatusError' && e.status === 401) ||
		e._tag === 'VisualizerPremiumGatedError'
	);
}

/**
 * An AMBIGUOUS-SUCCESS upload failure: a `ResponseDecodeError` (the server
 * returned a 2xx with no parseable `id`) or a `NetworkError` (a malformed 2xx
 * body — `visualizerCall` can't tell that apart from a real transport drop).
 * In both, `POST /shots/upload` may already have created the shot server-side
 * even though we never read its id, so re-POSTing it — exactly what an
 * `op: 'create'` retry-queue entry does — would create a REAL duplicate on the
 * account. These route to the self-heal-by-reconcile path in
 * `uploadUnsyncedShots` instead of the naive enqueue.
 *
 * (Fully duplicate-proof uploads ultimately need a server-side idempotency key
 * on `POST /shots/upload`; that's a Visualizer-side change, out of scope here.)
 */
function isAmbiguousSuccess(e: VisualizerCallError | ResponseDecodeError): boolean {
	return e._tag === 'ResponseDecodeError' || e._tag === 'NetworkError';
}

/**
 * Slop (ms) subtracted from a shot's `completedAt` to seed the self-heal pull
 * cursor. A just-uploaded shot's remote `updated_at` is ~now (≥ `completedAt`),
 * so any non-negative slop already includes it; the margin only guards against
 * clock skew between the device and the server.
 */
const SELF_HEAL_SLOP_MS = 60_000;

// ── Public surface ──────────────────────────────────────────────────────

export interface ShotPatch {
	rating?: number | null;
	notes?: string;
	coffeeBagId?: string | null;
	tagList?: string[];
	/**
	 * The frozen-at-completion bean snapshot for the inline-bean block.
	 * **Snapshot-only** — never the live bean (reading live-bean content
	 * would retroactively rewrite history). The core builder maps it onto
	 * `bean_brand` / `bean_type` / `roast_date` / `roast_level` /
	 * `bean_notes` / `grinder_setting`, skipping blanks so an empty bean
	 * doesn't blow away server-side values the user filled in via
	 * Visualizer's web UI.
	 */
	bean?: ShotBean | null;
	grinderModel?: string;
	/**
	 * Per-shot visibility. Sent as the same `privacy` vocabulary the upload
	 * document uses; the PATCH schema's `shot` object is
	 * `additionalProperties: true`, so the key is accepted — server-side
	 * application is best-effort (the formal schema doesn't model it).
	 */
	privacy?: 'public' | 'unlisted' | 'private';
}

/**
 * Progress callback fired between pages when `pullAllShotsSince` walks
 * pagination. UIs can wire this to a progress bar or counter.
 */
export interface PullProgress {
	/** Number of WireShots materialised so far across all pages. */
	fetched: number;
	/** 1-based page index just completed. */
	page: number;
	/** Total pages reported by the server (best-effort; may be revised). */
	totalPages: number;
	/** Whether more pages remain. */
	hasMore: boolean;
}

export interface PullOptions {
	itemsPerPage?: number;
	maxPages?: number;
	onProgress?: (p: PullProgress) => void;
}

export interface PullResult {
	shots: WireShot[];
	/** Per-shot telemetry keyed by Visualizer id — applied on the `add` step. */
	samplesById: Map<string, TimedSample[]>;
	/** Per-shot recipe (core `Profile` JSON) keyed by Visualizer id — fetched at
	 *  pull time so a pulled shot carries its recipe (#12). */
	profilesById: Map<string, unknown>;
	truncated: boolean;
}

/** Internal iteration state for the `pullAllShotsSince` `Effect.iterate` walk. */
interface PullState {
	page: number;
	totalPages: number;
	reachedOlder: boolean;
	keepGoing: boolean;
}

export class ShotSync extends Context.Tag('crema/ShotSync')<
	ShotSync,
	{
		readonly uploadShot: (
			shot: StoredShot
		) => Effect.Effect<{ visualizerId: string }, VisualizerCallError | ResponseDecodeError>;
		readonly deleteShot: (
			visualizerId: string
		) => Effect.Effect<void, Exclude<VisualizerCallError, VisualizerNotFoundError>>;
		readonly patchShot: (visualizerId: string, patch: ShotPatch) => Effect.Effect<void, VisualizerCallError>;
		/**
		 * Push a local shot's editable fields onto its already-uploaded
		 * Visualizer copy — rating, notes (respecting the include-notes
		 * setting), effective privacy, bean binding, tags, grinder model.
		 * No-op when the shot has no `visualizerId`.
		 */
		readonly patchEditedShot: (history: HistoryStore, id: string) => Effect.Effect<void, VisualizerCallError>;
		readonly pullShots: (
			page?: number,
			itemsPerPage?: number
		) => Effect.Effect<{ summaries: ShotSummary[]; paging: Paging }, VisualizerCallError>;
		readonly fetchShotDetail: (id: string) => Effect.Effect<ShotDetail, VisualizerCallError>;
		readonly pullAllShotsSince: (
			sinceMs: number,
			opts?: PullOptions
		) => Effect.Effect<PullResult, VisualizerCallError>;
		readonly applyShotReconciliation: (
			history: HistoryStore,
			remote: WireShot[],
			samplesById?: ReadonlyMap<string, TimedSample[]>,
			profilesById?: ReadonlyMap<string, unknown>
		) => Effect.Effect<void>;
		readonly pullAndReconcileShots: (
			history: HistoryStore,
			sinceMs: number,
			opts?: PullOptions
		) => Effect.Effect<{ pulled: number; truncated: boolean }, VisualizerCallError>;
		readonly uploadUnsyncedShots: (history: HistoryStore) => Effect.Effect<void>;
	}
>() {}

export const ShotSyncLive = Layer.effect(
	ShotSync,
	Effect.gen(function* () {
		const http = yield* HttpClient;
		const vault = yield* TokenVault;

		/** Early bail mirroring the old `if (!isConnected())` guard. */
		const requireConnected: Effect.Effect<void, NotAuthenticatedError> = vault.getTokens.pipe(
			Effect.flatMap((t) => (t === null ? Effect.fail(new NotAuthenticatedError()) : Effect.void))
		);

		/**
		 * `ShotSync`'s authenticated entry point: the shared {@link visualizerCall}
		 * with this layer's captured `http` / `vault` provided, so every method
		 * built on it stays `R = never` (which is what lets `UploadQueue` call
		 * `ShotSync` without itself holding `HttpClient` / `TokenVault`).
		 */
		const call = (
			path: string,
			opts: VisualizerCallOptions = {}
		): Effect.Effect<unknown, VisualizerCallError> =>
			visualizerCall(path, opts).pipe(
				Effect.provideService(HttpClient, http),
				Effect.provideService(TokenVault, vault)
			);

		/**
		 * Upload a single shot, then fire a soft follow-up PATCH (bag link +
		 * auto-tags + inline-bean snapshot + grinder model). PATCH failure is
		 * soft — logged, not rethrown — so a flaky link wire doesn't lose the
		 * uploaded shot.
		 */
		const uploadShot = Effect.fn('ShotSync.uploadShot')(function* (shot: StoredShot) {
			yield* requireConnected;
			const body = buildShotPayload(shot);
			const raw = yield* call('/shots/upload', { method: 'POST', body });
			const result = decodeResponse(ShotUploadResultSchema, raw, 'POST /shots/upload');
			if (!result || !result.id) {
				return yield* new ResponseDecodeError({
					url: `${API_BASE}/shots/upload`,
					cause: 'Visualizer accepted the shot but returned no id.'
				});
			}

			// Post-upload PATCH: build each block independently so empty sources
			// skip cleanly (no risk of overwriting a Visualizer-side edit —
			// the core builder additionally drops blank inline-bean fields).
			const coffeeBagId = resolveCoffeeBagId(shot);
			const tagList = resolveTagList(shot);
			const grinderModel = resolveGrinderModel(shot, getSettingsStore().current.grinderModel);
			if (
				coffeeBagId != null ||
				(tagList != null && tagList.length > 0) ||
				shot.bean != null ||
				grinderModel != null
			) {
				yield* patchShot(result.id, {
					...(coffeeBagId != null ? { coffeeBagId } : {}),
					...(tagList != null && tagList.length > 0 ? { tagList } : {}),
					...(shot.bean != null ? { bean: shot.bean } : {}),
					...(grinderModel != null ? { grinderModel } : {})
				}).pipe(
					Effect.catchAll((e) =>
						Effect.sync(() =>
							console.warn(
								'[Crema] post-upload PATCH (coffee_bag_id / tag_list / inline bean / grinder_model) failed:',
								describeVisualizerError(e)
							)
						)
					)
				);
			}

			return { visualizerId: result.id };
		});

		/**
		 * Delete a remote shot by id. `DELETE /shots/{id}` returns 200 +
		 * `{ success: true }` per the spec. A 404 (→ `VisualizerNotFoundError`)
		 * is treated as success.
		 */
		const deleteShot = Effect.fn('ShotSync.deleteShot')(function* (visualizerId: string) {
			yield* requireConnected;
			yield* call(`/shots/${visualizerId}`, { method: 'DELETE' }).pipe(
				Effect.catchTag('VisualizerNotFoundError', () => Effect.void)
			);
		});

		/**
		 * Patch a remote shot's editable annotations. The spec requires a
		 * `{ shot: {...} }` envelope; only keys explicitly present on `patch` ride
		 * out. `rating` (0..5) → `flavor` (0..15) via `flavor = rating * 3`; a
		 * `null` rating omits `flavor`. Inline-bean fields ride via the spec's
		 * `additionalProperties: true` index signature.
		 */
		const patchShot = Effect.fn('ShotSync.patchShot')(function* (
			visualizerId: string,
			patch: ShotPatch
		) {
			yield* requireConnected;
			// The body assembly lives in the core (one builder for both
			// shells — review #42): rating→flavor with unrated/cleared
			// OMITTED (never clobber a server-side cupping score), the
			// inline-bean block with blank-skipping and the lossy
			// roast-level banding, and the wire key names.
			const inputs: ShotPatchInputs = {
				rating: patch.rating ?? undefined,
				notes: patch.notes,
				privacy: patch.privacy,
				grinderModel: patch.grinderModel,
				coffeeBagId: patch.coffeeBagId ?? undefined,
				tagList: patch.tagList ?? [],
				beanBrand: patch.bean?.roasterName ?? undefined,
				beanType: patch.bean?.name,
				roastDate: patch.bean?.roastedOn ?? undefined,
				roastLevel: patch.bean?.roastLevel ?? undefined,
				beanNotes: patch.bean?.notes,
				grinderSetting: patch.bean?.grinderSetting ?? undefined
			};
			const shotBody = JSON.parse(
				wasmVisualizerShotPatchJson(JSON.stringify(inputs))
			) as ShotUpdateRequest['shot'];
			const envelope: ShotUpdateRequest = { shot: shotBody };
			yield* call(`/shots/${visualizerId}`, {
				method: 'PATCH',
				body: envelope
			});
		});

		/**
		 * Fetch one page of remote shot SUMMARIES (`{ id, clock, updated_at }`).
		 * The default `sort=updated_at` lets `pullAllShotsSince` stop walking as
		 * soon as it crosses the local cursor.
		 */
		const pullShots = Effect.fn('ShotSync.pullShots')(function* (page = 1, itemsPerPage = 50) {
			yield* requireConnected;
			const items = Math.min(100, Math.max(1, Math.floor(itemsPerPage)));
			const raw = yield* call(`/shots?page=${page}&items=${items}&sort=updated_at`);
			const body = decodeResponse(ShotListResponseSchema, raw, 'GET /shots');
			return {
				// Spread to a mutable array — `Schema.Array` decodes to `readonly`.
				summaries: body ? [...body.data] : [],
				paging: body?.paging ?? { count: 0, page, limit: items, pages: 1 }
			};
		});

		/**
		 * Fetch the full record for one shot (`GET /shots/{id}`). The body is fed
		 * verbatim to the wasm reconcile path, so it is passed through as-is rather
		 * than validated — `ShotDetail` is a two-variant (Default/Beanconqueror)
		 * shape and a stripping schema would drop fields wasm needs (handoff §T-12).
		 */
		const fetchShotDetail = Effect.fn('ShotSync.fetchShotDetail')(function* (id: string) {
			yield* requireConnected;
			const raw = yield* call(`/shots/${id}`);
			return raw as ShotDetail;
		});

		/**
		 * Fetch a shot's recipe from `/shots/{id}/profile` (the shot detail has no
		 * step list) and convert it to the core `Profile` JSON. Used at pull time so
		 * a pulled shot is self-contained (#12). Throws on a profile-less stub (the
		 * v2 parser rejects an empty step list) — the caller skips it.
		 */
		const fetchShotProfile = Effect.fn('ShotSync.fetchShotProfile')(function* (id: string) {
			yield* requireConnected;
			const v2 = yield* call(`/shots/${id}/profile?format=json`);
			// `parseV2Profile` throws on a profile-less stub / invalid v2; swallow it
			// (the shot stays name-only) so a sync defect can't escape the pull walk.
			try {
				return JSON.parse(wasmParseV2Profile(JSON.stringify(v2))) as unknown;
			} catch {
				return null;
			}
		});

		/**
		 * Pull every remote shot updated since `sinceMs` (Crema-side unix-ms
		 * cursor). Walks pages of `/shots?sort=updated_at` until either the server
		 * runs out OR a summary's `updated_at` is older than the cursor. **Safety
		 * cap**: walks at most `maxPages` pages (default 200) so a buggy server
		 * can't pin the tab — when the cap is hit while more pages remain, returns
		 * `truncated: true`.
		 */
		const pullAllShotsSince = Effect.fn('ShotSync.pullAllShotsSince')(function* (
			sinceMs: number,
			opts: PullOptions = {}
		) {
			const items = Math.min(100, Math.max(1, Math.floor(opts.itemsPerPage ?? 50)));
			const maxPages = opts.maxPages ?? 200;
			const cursorSec = Math.floor(sinceMs / 1000);
			const wireShots: WireShot[] = [];
			const samplesById = new Map<string, TimedSample[]>();
			const profilesById = new Map<string, unknown>();

			const initial: PullState = { page: 1, totalPages: 1, reachedOlder: false, keepGoing: true };
			const finalState = yield* Effect.iterate(initial, {
				while: (s) => s.keepGoing && s.page <= maxPages,
				body: (s) =>
					Effect.gen(function* () {
						const { summaries, paging } = yield* pullShots(s.page, items);
						const totalPages = paging.pages || s.totalPages;
						let reachedOlder = s.reachedOlder;
						for (const summary of summaries) {
							if (summary.updated_at < cursorSec) {
								// Sorted by updated_at desc, so once we see an older row we've
								// crossed the cursor.
								reachedOlder = true;
								continue;
							}
							// One failed detail fetch shouldn't blow up the whole sync —
							// re-throw only on auth (no point continuing); other errors skip
							// the row and the next sync retries.
							const detailOpt = yield* fetchShotDetail(summary.id).pipe(
								Effect.map((d) => Option.some(d)),
								Effect.catchIf(
									(e) => !isAuthError(e),
									() => Effect.succeedNone
								)
							);
							if (Option.isSome(detailOpt)) {
								const detail = detailOpt.value;
								wireShots.push(wireShotFromDetail(summary, detail));
								samplesById.set(summary.id, samplesFromVisualizerDetail(detail));
								// Pull the recipe too so the shot is self-contained (#12); a
								// profile-less stub / fetch error just leaves it name-only.
								const profileOpt = yield* fetchShotProfile(summary.id).pipe(
									Effect.map(Option.fromNullable),
									Effect.catchIf(
										(e) => !isAuthError(e),
										() => Effect.succeedNone
									)
								);
								if (Option.isSome(profileOpt)) profilesById.set(summary.id, profileOpt.value);
							}
						}
						const hasMore = !reachedOlder && s.page < totalPages && summaries.length > 0;
						opts.onProgress?.({
							fetched: wireShots.length,
							page: s.page,
							totalPages,
							hasMore
						});
						return { page: s.page + 1, totalPages, reachedOlder, keepGoing: hasMore };
					})
			});

			// `keepGoing` is still true only when the loop exited because the
			// `maxPages` cap was hit — i.e. more pages remained.
			return { shots: wireShots, samplesById, profilesById, truncated: finalState.keepGoing };
		});

		/**
		 * Apply a {@link reconcileShots} plan against `history`. Logs each action
		 * via {@link appendSyncLog}; does not throw. Pure planner output translated
		 * to store mutations — wrapped in `Effect.sync` (calls wasm + the rune
		 * store synchronously, same as the old module).
		 */
		const applyShotReconciliation = (
			history: HistoryStore,
			remote: WireShot[],
			samplesById?: ReadonlyMap<string, TimedSample[]>,
			profilesById?: ReadonlyMap<string, unknown>
		): Effect.Effect<void> =>
			Effect.sync(() => {
				const local = history.all;
				const actions = reconcileShots(local, remote);
				for (const action of actions) {
					if (action.kind === 'add') {
						const stored = storedShotFromWire(action.remote, samplesById?.get(action.remote.id));
						if (stored) {
							// Attach the recipe fetched at pull time so the shot is
							// self-contained (#12); absent for profile-less remotes.
							const profile = profilesById?.get(action.remote.id);
							history.insertPulled(profile != null ? { ...stored, profile } : stored);
						}
						appendSyncLog({
							direction: 'pull',
							entity: 'shot',
							id: stored?.id ?? action.remote.id ?? '',
							name: stored?.profileName ?? 'Shot',
							at: Date.now()
						});
					} else if (action.kind === 'bind') {
						history.bindVisualizerId(action.localId, action.visualizerId);
						appendSyncLog({
							direction: 'pull',
							entity: 'shot',
							id: action.localId,
							name: 'Shot (bound)',
							at: Date.now()
						});
					} else if (action.kind === 'update') {
						// Wire `tag_list` defaults to `[]` server-side, so this patch is
						// idempotent — re-applying an empty list to a local with none is a
						// no-op write.
						history.setTags(action.localId, action.remote.tag_list ?? []);
						// Heal pre-telemetry-import stubs: if this bound row has no curve
						// locally but the pull carries one, backfill it (no-op otherwise).
						const samples = samplesById?.get(action.remote.id);
						if (samples && samples.length > 0) {
							history.backfillTelemetry(action.localId, samples, action.remote.duration_ms);
						}
					}
				}
			});

		/**
		 * Pull every shot updated since `sinceMs`, reconcile against `history`, and
		 * apply the resulting actions. Returns the shape callers need to surface a
		 * truncation warning.
		 */
		const pullAndReconcileShots = Effect.fn('ShotSync.pullAndReconcileShots')(function* (
			history: HistoryStore,
			sinceMs: number,
			opts: PullOptions = {}
		) {
			const { shots, samplesById, profilesById, truncated } = yield* pullAllShotsSince(
				sinceMs,
				opts
			);
			if (shots.length > 0)
				yield* applyShotReconciliation(history, shots, samplesById, profilesById);
			return { pulled: shots.length, truncated };
		});

		/**
		 * Upload every local shot that lacks a `visualizerId`. Recoverable
		 * failures route to the persistent retry queue; auth / premium failures
		 * abort the loop (continuing would just keep hammering the same wall).
		 */
		const uploadUnsyncedShots = Effect.fn('ShotSync.uploadUnsyncedShots')(function* (
			history: HistoryStore
		) {
			const list = history.all.filter((s) => !s.visualizerId);

			/**
			 * Record a non-success outcome: enqueue a recoverable error for a timed
			 * retry through `UploadQueue`, and append a `skip` line to the sync log.
			 * Shared by the plain-failure path and the self-heal "couldn't bind"
			 * fallback.
			 */
			const skipOrQueue = (shot: StoredShot, e: VisualizerCallError | ResponseDecodeError): void => {
				if (isRecoverable(e)) {
					enqueueSyncOp({
						entity: 'shot',
						id: shot.id,
						op: 'create',
						error: describeVisualizerError(e)
					});
				}
				appendSyncLog({
					direction: 'skip',
					entity: 'shot',
					id: shot.id,
					name: shot.profileName ?? 'Shot',
					at: Date.now(),
					error: describeVisualizerError(e)
				});
			};

			/**
			 * Self-heal an {@link isAmbiguousSuccess} upload: the shot may already
			 * be on the server, so rather than re-POST (→ duplicate) we PULL the
			 * shots updated around this one's completion and let `reconcileShots`
			 * bind the unsynced local to its freshly-created remote by djb2
			 * signature. Resolves `true` once the bind has landed (the local now
			 * carries a `visualizerId`). Never throws — a failed self-heal pull
			 * simply means "not bound yet", and the caller falls back to
			 * {@link skipOrQueue}.
			 */
			const selfHealBind = (shot: StoredShot): Effect.Effect<boolean> =>
				pullAndReconcileShots(history, shot.completedAt - SELF_HEAL_SLOP_MS).pipe(
					Effect.ignore,
					Effect.map(() => Boolean(history.get(shot.id)?.visualizerId))
				);

			/** Upload one shot; returns `true` when the loop should abort. */
			const uploadOne = (shot: StoredShot): Effect.Effect<boolean> =>
				uploadShot(shot).pipe(
					Effect.flatMap(({ visualizerId }) =>
						Effect.sync(() => {
							history.bindVisualizerId(shot.id, visualizerId);
							appendSyncLog({
								direction: 'push',
								entity: 'shot',
								id: shot.id,
								name: shot.profileName ?? 'Shot',
								at: Date.now()
							});
							return false;
						})
					),
					Effect.catchAll((e) =>
						isAmbiguousSuccess(e)
							? // The POST may already have landed — bind by reconcile, never
								// re-POST. A successful bind is logged as a push (the reconcile
								// pull also logs its own `bind` line); a miss falls back to the
								// queue ONLY when recoverable (so a 2xx-no-id is never re-POSTed).
								selfHealBind(shot).pipe(
									Effect.map((bound) => {
										if (bound) {
											appendSyncLog({
												direction: 'push',
												entity: 'shot',
												id: shot.id,
												name: shot.profileName ?? 'Shot',
												at: Date.now()
											});
										} else {
											skipOrQueue(shot, e);
										}
										// Ambiguous-success errors never abort the loop: they aren't
										// an auth/premium wall, and the remaining shots are unaffected.
										return false;
									})
								)
							: Effect.sync(() => {
									skipOrQueue(shot, e);
									return shouldAbortLoop(e);
								})
					)
				);

			// `Effect.iterate` over the index; on abort, jump the index past the end
			// to terminate (mirrors the old loop's early `return`).
			yield* Effect.iterate(0, {
				while: (i) => i < list.length,
				body: (i) => uploadOne(list[i]).pipe(Effect.map((aborted) => (aborted ? list.length : i + 1)))
			});
		});

		/**
		 * Map a local shot's editable surface onto a [ShotPatch] — the same
		 * resolvers the upload follow-up uses, plus the journal fields the
		 * History panel edits. Notes respect `visualizerIncludeNotes` (a user
		 * who opted out of uploading notes must not have an edit leak them).
		 * Privacy sends the EFFECTIVE value (per-shot override, else the
		 * settings default) so the server always matches what the user sees.
		 */
		const buildEditPatch = (shot: StoredShot): ShotPatch => {
			const settings = getSettingsStore().current;
			const cfg = readSyncConfig();
			const coffeeBagId = resolveCoffeeBagId(shot);
			const tagList = resolveTagList(shot);
			const grinderModel = resolveGrinderModel(shot, settings.grinderModel);
			return {
				rating: (shot.metadata.rating ?? 0) > 0 ? (shot.metadata.rating ?? null) : null,
				...(cfg.includeNotes ? { notes: shot.metadata.notes ?? '' } : {}),
				privacy: shot.privacy ?? cfg.privacy,
				...(coffeeBagId != null ? { coffeeBagId } : {}),
				...(tagList != null && tagList.length > 0 ? { tagList } : {}),
				...(shot.bean != null ? { bean: shot.bean } : {}),
				...(grinderModel != null ? { grinderModel } : {})
			};
		};

		const patchEditedShot = Effect.fn('ShotSync.patchEditedShot')(function* (
			history: HistoryStore,
			id: string
		) {
			const shot = history.all.find((s) => s.id === id);
			const vid = shot?.visualizerId;
			if (!shot || !vid) return;
			yield* patchShot(vid, buildEditPatch(shot));
		});

		return ShotSync.of({
			uploadShot,
			deleteShot,
			patchShot,
			patchEditedShot,
			pullShots,
			fetchShotDetail,
			pullAllShotsSince,
			applyShotReconciliation,
			pullAndReconcileShots,
			uploadUnsyncedShots
		});
	})
);
