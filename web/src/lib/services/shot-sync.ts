/**
 * `$lib/services/shot-sync` — the `ShotSync` service (docs/53 §1.3, §2.4, T-12).
 *
 * The Effect-native home for what `visualizer/shot-sync.ts` does today: sync
 * Crema's local shot library against Visualizer's `/shots*` endpoints (per the
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
 * This service is NOT wired into production yet — `visualizer/shot-sync.ts`
 * remains the live implementation until the T-16 facade swap. `ShotSyncLive`
 * only needs to compose into `AppLayer`.
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
import {
	HttpStatusError,
	NetworkError,
	NotAuthenticatedError,
	ResponseDecodeError,
	TokenRefreshFailedError,
	VisualizerNotFoundError,
	VisualizerPremiumGatedError
} from '../effect/errors.ts';
import {
	ShotUploadResultSchema,
	ShotListResponseSchema,
	decodeResponse
} from '../effect/schema/visualizer.ts';
import { getBeanStore } from '$lib/bean/store.svelte';
import { roastLevelToWire } from '$lib/bean/visualizer-sync';
import { exportStoredShotAsV2Json } from '$lib/history/v2-export';
import type { RustTimedSample } from '$lib/core';
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
import { appendSyncLog } from '$lib/visualizer/sync-config';
import { enqueue as enqueueSyncOp } from '$lib/visualizer/upload-queue';

/** Visualizer API base. */
const API_BASE = 'https://visualizer.coffee/api';

// Spec-typed aliases — these ride DIRECTLY on the wire so they stay
// in lock-step with the OpenAPI spec (regenerate via `pnpm openapi`).
type ShotSummary = components['schemas']['ShotSummary'];
type Paging = components['schemas']['Paging'];
type DefaultShotDetail = components['schemas']['DefaultShotDetail'];
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
function buildShotPayload(shot: StoredShot): string {
	const settings = getSettingsStore().current;
	const json = JSON.parse(exportStoredShotAsV2Json(shot)) as Record<string, unknown>;

	if (!settings.visualizerIncludeProfile) {
		delete json.profile;
	}
	if (!settings.visualizerIncludeNotes) {
		const metadata = (json.metadata as Record<string, unknown> | undefined) ?? null;
		if (metadata && 'notes' in metadata) {
			metadata.notes = null;
		}
	}

	json.privacy = settings.visualizerPrivacy;
	json.metadata = {
		...((json.metadata as Record<string, unknown> | undefined) ?? {}),
		crema: {
			localId: shot.id,
			signature: signatureForShot(shot),
			appVersion: '0.0.1'
		}
	};

	return JSON.stringify(json);
}

// ── Wire / Crema boundary helpers (copied verbatim from the old module) ───

/**
 * Convert a full `ShotDetail` (returned from `GET /shots/{id}`) plus the
 * originating summary's `clock` / `updated_at` into Crema's {@link WireShot}
 * (unix MS, normalised field names). Visualizer's default detail flattens
 * scoring + journal fields onto the shot row; Beanconqueror's variant nests them
 * under `meta.visualizer` — we read from both shapes defensively.
 */
function wireShotFromDetail(summary: ShotSummary, detail: ShotDetail): WireShot {
	const fallback = detail as DefaultShotDetail;
	const bcMeta = (detail as components['schemas']['BeanconquerorShotDetail']).meta?.visualizer;
	const profileTitle = fallback.profile_title ?? null;
	// `duration` in the spec is seconds (the BC payload's `meta.visualizer.duration`
	// is also seconds). Convert to milliseconds so the de-dup signature lines up
	// with Crema's `StoredShot.duration` (ms).
	const durationSec = fallback.duration ?? bcMeta?.duration ?? null;
	const durationMs = durationSec != null ? Math.round(durationSec * 1000) : 0;
	// drink_weight is a stringified gram value in the default response.
	const drinkWeightG = ((): number | null => {
		const raw = fallback.drink_weight;
		if (raw == null) return null;
		const n = Number(raw);
		return Number.isFinite(n) ? n : null;
	})();
	const espressoNotes =
		fallback.private_notes ?? bcMeta?.private_notes ?? fallback.espresso_notes ?? null;
	// Crema rating is 0..5; the wire's cupping slots are 0..15. We write
	// `flavor = rating * 3` on PATCH, so the pull does the inverse: prefer
	// `flavor`, fall back to the legacy `espresso_enjoyment` field for shots
	// uploaded before the switch, then round-trip back to the 0..5 scale.
	const flavorRaw =
		fallback.flavor ??
		bcMeta?.flavor ??
		fallback.espresso_enjoyment ??
		bcMeta?.espresso_enjoyment ??
		null;
	const rating = flavorRaw != null ? Math.max(0, Math.min(5, Math.round(flavorRaw / 3))) : null;
	// Spec note (`openapi.json` v1.8.2): the read-side detail field is `tags` on
	// `DefaultShotDetail`; the write-side PATCH field is `tag_list` on
	// `ShotUpdateRequest`. The Beanconqueror detail variant has no shot-level
	// tags. We surface both shapes defensively in case a future serializer
	// renames the read field too.
	const fallbackTags = Array.isArray(fallback.tags) ? fallback.tags : null;
	const tagList: string[] = fallbackTags
		? fallbackTags.filter((t): t is string => typeof t === 'string')
		: [];
	return {
		id: summary.id,
		clock: summary.clock * 1000, // unix sec → ms
		duration_ms: durationMs,
		profile_title: profileTitle,
		final_weight_g: drinkWeightG,
		notes: espressoNotes,
		rating,
		updated_at_ms: summary.updated_at * 1000, // unix sec → ms
		tag_list: tagList
	};
}

/**
 * Reconstruct Crema's per-sample telemetry from a Visualizer shot detail.
 *
 * Visualizer stores the curve as parallel columns — a time axis plus one array
 * per channel — so we zip them by index into the row-shaped
 * {@link RustTimedSample} the history chart + peak derivation consume. The only
 * conversion is the time axis: Visualizer's `timeframe` is SECONDS,
 * `RustTimedSample.elapsed` is MILLISECONDS. A detail with no time axis (a
 * Beanconqueror row, or a shot stored without a curve) yields `[]`.
 */
function samplesFromVisualizerDetail(detail: ShotDetail): RustTimedSample[] {
	const d = detail as DefaultShotDetail;
	const time = d.timeframe;
	if (!Array.isArray(time) || time.length === 0) return [];
	const data = d.data ?? {};

	type Series = components['schemas']['ShotSeries'];
	const num = (arr: Series | undefined, i: number): number => {
		const v = arr?.[i];
		const n = typeof v === 'number' ? v : Number(v);
		return Number.isFinite(n) ? n : 0;
	};
	// Scale-derived channels: absent/zero means "no signal" downstream.
	const opt = (arr: Series | undefined, i: number): number | null => {
		const n = num(arr, i);
		return n > 0 ? n : null;
	};

	const pressure = data.espresso_pressure;
	const flow = data.espresso_flow;
	const weight = data.espresso_weight;
	const flowWeight = data.espresso_flow_weight;
	const pressureGoal = data.espresso_pressure_goal;
	const flowGoal = data.espresso_flow_goal;
	const tempGoal = data.espresso_temperature_goal;
	const tempMix = data.espresso_temperature_mix;
	const tempBasket = data.espresso_temperature_basket;
	const water = data.espresso_water_dispensed;

	const out: RustTimedSample[] = [];
	for (let i = 0; i < time.length; i++) {
		const tSec = num(time, i);
		const row: RustTimedSample = {
			elapsed: Math.round(tSec * 1000), // Visualizer seconds → Crema ms
			sample: {
				sampleTime: 0,
				groupPressure: num(pressure, i),
				groupFlow: num(flow, i),
				headTemp: num(tempBasket, i),
				mixTemp: num(tempMix, i),
				setHeadTemp: num(tempGoal, i),
				setMixTemp: 0,
				setGroupPressure: num(pressureGoal, i),
				setGroupFlow: num(flowGoal, i),
				frameNumber: 0,
				steamTemp: 0
			}
		};
		const w = opt(weight, i);
		if (w != null) row.scaleWeight = w;
		const wf = opt(flowWeight, i);
		if (wf != null) row.scaleFlowWeight = wf;
		const wd = opt(water, i);
		if (wd != null) row.dispensedVolume = wd;
		out.push(row);
	}
	return out;
}

// ── PATCH-body resolvers (copied verbatim from the old module) ────────────

/**
 * Map a {@link ShotBean} snapshot onto the inline-bean PATCH fields Visualizer
 * accepts on `ShotUpdateRequest.shot`. **Snapshot-only**: every field is read
 * from the frozen-at-completion {@link ShotBean}, never from the live bean —
 * reading live-bean content would retroactively rewrite history. Empty
 * strings / `null` sources are skipped so an empty bean doesn't blow away
 * server-side values the user filled in via Visualizer's web UI.
 */
export function inlineBeanPatch(bean: ShotBean | null | undefined): Record<string, unknown> {
	const out: Record<string, unknown> = {};
	if (!bean) return out;
	const roaster = bean.roasterName?.trim();
	if (roaster) out.bean_brand = roaster;
	const type = bean.name?.trim();
	if (type) out.bean_type = type;
	if (bean.roastedOn) out.roast_date = bean.roastedOn;
	const roastLevelStr = roastLevelToWire(bean.roastLevel ?? null);
	if (roastLevelStr) out.roast_level = roastLevelStr;
	const notes = bean.notes?.trim();
	if (notes) out.bean_notes = notes;
	const grinder = bean.grinderSetting?.trim();
	if (grinder) out.grinder_setting = grinder;
	// NOTE: `grinder_model` is equipment-level, not bean-level — resolved
	// separately via {@link resolveGrinderModel}. Don't add a slot here.
	return out;
}

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

// ── HTTP plumbing ─────────────────────────────────────────────────────────

interface FetchOptions {
	method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
	body?: string;
}

/** The closed error union every `call`-backed method can surface. */
type VisualizerCallError =
	| NetworkError
	| HttpStatusError
	| NotAuthenticatedError
	| TokenRefreshFailedError
	| VisualizerPremiumGatedError
	| VisualizerNotFoundError;

/** `'auth'`-equivalent failures — re-thrown rather than swallowed on a pull. */
function isAuthError(e: VisualizerCallError): boolean {
	return (
		e._tag === 'NotAuthenticatedError' ||
		e._tag === 'TokenRefreshFailedError' ||
		(e._tag === 'HttpStatusError' && e.status === 401)
	);
}

/** Human-readable summary for the sync log / console warn (display only). */
function describeError(e: VisualizerCallError | ResponseDecodeError): string {
	switch (e._tag) {
		case 'HttpStatusError':
			return `HTTP ${e.status}: ${e.body ?? ''}`.trim();
		case 'NetworkError':
			return `Network error: ${e.cause instanceof Error ? e.cause.message : String(e.cause)}`;
		case 'VisualizerPremiumGatedError':
			return 'Premium subscription required for writes.';
		case 'VisualizerNotFoundError':
			return `Visualizer shot not found: ${e.visualizerId}`;
		case 'NotAuthenticatedError':
			return 'Sign in to Visualizer first.';
		case 'TokenRefreshFailedError':
			return 'Visualizer rejected the access token. Please sign in again.';
		case 'ResponseDecodeError':
			return 'Unexpected Visualizer response.';
	}
}

/** A `recoverable` upload failure routes to the persistent retry queue. */
function isRecoverable(e: VisualizerCallError | ResponseDecodeError): boolean {
	return (
		e._tag === 'NetworkError' ||
		(e._tag === 'HttpStatusError' && ((e.status >= 500 && e.status < 600) || e.status === 408))
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

// ── Public surface ──────────────────────────────────────────────────────

export interface ShotPatch {
	rating?: number | null;
	notes?: string;
	coffeeBagId?: string | null;
	tagList?: string[];
	inlineBean?: Record<string, unknown>;
	grinderModel?: string;
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
	samplesById: Map<string, RustTimedSample[]>;
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
			samplesById?: ReadonlyMap<string, RustTimedSample[]>
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
		 * Single authenticated HTTP entry point. Funnels through
		 * `TokenVault.withFreshToken` (401 → refresh-once is automatic) and maps
		 * `HttpClient`'s `HttpStatusError` back onto the Visualizer taxonomy.
		 * Returns parsed JSON for 2xx + body; `null` for 204.
		 */
		const call = (path: string, opts: FetchOptions = {}): Effect.Effect<unknown, VisualizerCallError> =>
			vault
				.withFreshToken((token) => {
					const headers: Record<string, string> = {
						Authorization: `Bearer ${token}`,
						Accept: 'application/json'
					};
					if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
					return http.request({
						url: `${API_BASE}${path}`,
						method: opts.method ?? 'GET',
						headers,
						body: opts.body
					});
				})
				.pipe(
					Effect.catchTag(
						'HttpStatusError',
						(
							err
						): Effect.Effect<never, VisualizerPremiumGatedError | VisualizerNotFoundError | HttpStatusError> => {
							if (err.status === 402 || err.status === 403) {
								return Effect.fail(new VisualizerPremiumGatedError({ endpoint: path }));
							}
							if (err.status === 404) {
								return Effect.fail(new VisualizerNotFoundError({ visualizerId: path }));
							}
							return Effect.fail(err);
						}
					),
					Effect.flatMap((res) =>
						res.status === 204
							? Effect.succeed(null)
							: // A malformed 2xx body is effectively a transport anomaly; surface it
								// as a `NetworkError` so it stays recoverable (matches the old
								// module, where a raw `res.json()` rejection was a non-VisualizerError
								// → treated as recoverable on upload, swallowed on a detail pull).
								Effect.tryPromise({
									try: () => res.json(),
									catch: (cause) => new NetworkError({ cause, url: `${API_BASE}${path}` })
								})
					)
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
			// skip cleanly (no risk of overwriting a Visualizer-side edit).
			const coffeeBagId = resolveCoffeeBagId(shot);
			const tagList = resolveTagList(shot);
			const inlineBean = inlineBeanPatch(shot.bean);
			const hasInlineBean = Object.keys(inlineBean).length > 0;
			const grinderModel = resolveGrinderModel(shot, getSettingsStore().current.grinderModel);
			if (
				coffeeBagId != null ||
				(tagList != null && tagList.length > 0) ||
				hasInlineBean ||
				grinderModel != null
			) {
				yield* patchShot(result.id, {
					...(coffeeBagId != null ? { coffeeBagId } : {}),
					...(tagList != null && tagList.length > 0 ? { tagList } : {}),
					...(hasInlineBean ? { inlineBean } : {}),
					...(grinderModel != null ? { grinderModel } : {})
				}).pipe(
					Effect.catchAll((e) =>
						Effect.sync(() =>
							console.warn(
								'[Crema] post-upload PATCH (coffee_bag_id / tag_list / inline bean / grinder_model) failed:',
								describeError(e)
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
			// Start from the inline-bean block so the typed slots below take
			// precedence on key collisions.
			const shotBody: ShotUpdateRequest['shot'] = { ...(patch.inlineBean ?? {}) };
			if (patch.notes !== undefined) shotBody.private_notes = patch.notes;
			if (patch.rating !== undefined) {
				// `null` rating means "unrated" — omit `flavor` rather than sending 0
				// so we don't blow away an existing server-side cupping score.
				if (patch.rating != null) {
					shotBody.flavor = Math.max(0, Math.min(15, Math.round(patch.rating * 3)));
				}
			}
			if (patch.coffeeBagId !== undefined) shotBody.coffee_bag_id = patch.coffeeBagId;
			if (patch.tagList !== undefined) shotBody.tag_list = patch.tagList;
			if (patch.grinderModel !== undefined) shotBody.grinder_model = patch.grinderModel;
			const envelope: ShotUpdateRequest = { shot: shotBody };
			yield* call(`/shots/${visualizerId}`, {
				method: 'PATCH',
				body: JSON.stringify(envelope)
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
			const samplesById = new Map<string, RustTimedSample[]>();

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
			return { shots: wireShots, samplesById, truncated: finalState.keepGoing };
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
			samplesById?: ReadonlyMap<string, RustTimedSample[]>
		): Effect.Effect<void> =>
			Effect.sync(() => {
				const local = history.all;
				const actions = reconcileShots(local, remote);
				for (const action of actions) {
					if (action.kind === 'add') {
						const stored = storedShotFromWire(action.remote, samplesById?.get(action.remote.id));
						if (stored) history.insertPulled(stored);
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
			const { shots, samplesById, truncated } = yield* pullAllShotsSince(sinceMs, opts);
			if (shots.length > 0) yield* applyShotReconciliation(history, shots, samplesById);
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
						Effect.sync(() => {
							if (isRecoverable(e)) {
								enqueueSyncOp({
									entity: 'shot',
									id: shot.id,
									op: 'create',
									error: describeError(e)
								});
							}
							appendSyncLog({
								direction: 'skip',
								entity: 'shot',
								id: shot.id,
								name: shot.profileName ?? 'Shot',
								at: Date.now(),
								error: describeError(e)
							});
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

		return ShotSync.of({
			uploadShot,
			deleteShot,
			patchShot,
			pullShots,
			fetchShotDetail,
			pullAllShotsSince,
			applyShotReconciliation,
			pullAndReconcileShots,
			uploadUnsyncedShots
		});
	})
);
