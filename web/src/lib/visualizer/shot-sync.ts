/**
 * `$lib/visualizer/shot-sync` — shot-history sync between Crema's local
 * shot library and Visualizer's `/shots*` endpoints (per the vendored
 * OpenAPI spec at `./openapi.json`; types regenerate with `pnpm openapi`).
 *
 * Wire-level corrections applied 2026-05-24 against spec v1.8.2:
 *   - `GET /shots` uses `items=` (max 100), NOT `per_page=`. There is no
 *     `since=<ms>` query — we sort by `updated_at` and stop pagination
 *     once a returned row is older than the local cursor.
 *   - Response is `{ data: ShotSummary[], paging: { count, page, limit,
 *     pages } }`; the spec has no `next_cursor`. `ShotSummary` carries
 *     only `{ id, clock, updated_at }` (unix SECONDS, not ms — Crema
 *     stores ms; we convert at this boundary).
 *   - Full shot fields require `GET /shots/{id}` → `ShotDetail`.
 *   - `POST /shots/upload` accepts a raw JSON payload (one of the brew
 *     payload schemas — Crema's `exportStoredShotAsV2Json` produces the
 *     Decent shape).
 *   - `PATCH /shots/{id}` expects `{ shot: {...} }`, NOT a bare body.
 *     The Crema-side `{ rating, notes }` patch maps to
 *     `{ shot: { espresso_enjoyment, private_notes } }`.
 *   - `DELETE /shots/{id}` returns 200 + `{ success: true }`.
 *
 * Direction & modes: unchanged from previous cut.
 *   - **Backup**  — push only.
 *   - **Pull**    — read remote shots; de-dup via `signatureForShot`.
 *   - **Two-way** — both directions; LWW conflict resolution.
 *
 * Auth: every authenticated call funnels through `withFreshToken`.
 */

import { VisualizerError } from '$lib/bean';
// Imported directly from the store / sync modules rather than via
// `$lib/bean` to avoid an import cycle: `$lib/bean/index.ts` re-exports
// `$lib/visualizer`, which is *this* module's package.
import { getBeanStore } from '$lib/bean/store.svelte';
import { roastLevelToWire } from '$lib/bean/visualizer-sync';
import { exportStoredShotAsV2Json } from '$lib/history/v2-export';
import type { ShotBean, StoredShot } from '$lib/history/model';
import { getSettingsStore } from '$lib/settings';
import { isConnected, NotAuthenticatedError, withFreshToken } from './token-store';
import type { components } from './openapi';
import {
	reconcileShots,
	signatureForBean,
	signatureForRoaster,
	signatureForShot,
	storedShotFromWire,
	type ReconcileAction,
	type WireShot
} from './shot-sync-signatures';

/** Visualizer API base. */
const API_BASE = 'https://visualizer.coffee/api';

// Spec-typed aliases — these ride DIRECTLY on the wire so they stay
// in lock-step with the OpenAPI spec (regenerate via `pnpm openapi`).
type ShotSummary = components['schemas']['ShotSummary'];
type ShotListResponse = components['schemas']['ShotListResponse'];
type Paging = components['schemas']['Paging'];
type DefaultShotDetail = components['schemas']['DefaultShotDetail'];
type ShotDetail = components['schemas']['ShotDetail'];
type ShotUploadResult = components['schemas']['ShotUploadResult'];
type ShotUpdateRequest = components['schemas']['ShotUpdateRequest'];
type DeleteResult = components['schemas']['DeleteResult'];

// Re-export the pure helpers so existing import paths through
// `$lib/visualizer` continue to resolve via this module.
export {
	reconcileShots,
	signatureForBean,
	signatureForRoaster,
	signatureForShot,
	storedShotFromWire,
	type ReconcileAction,
	type WireShot
};

// Re-export spec-typed schema aliases for callers that want them.
export type { ShotSummary, ShotDetail, Paging };

// ── Settings-aware payload builder ────────────────────────────────────

/**
 * Build the JSON payload `POST /shots/upload` accepts. Crema's
 * `exportStoredShotAsV2Json` produces the Decent v2 shape (timestamp,
 * elapsed, profile, pressure, flow, temperature, totals) — Visualizer's
 * `DecentUploadPayload` (`#/components/schemas/DecentUploadPayload`).
 *
 * Applies the four `visualizer*` user settings (privacy, includeProfile,
 * includeNotes) so the user's choices ride on the wire.
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

	// Privacy + a Crema-side escape-valve block so the round-trip back
	// can re-bind. Visualizer ignores unknown keys per its
	// `additionalProperties: true` contract.
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

// ── HTTP plumbing ─────────────────────────────────────────────────────

interface FetchOptions {
	method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
	body?: string;
}

/**
 * Single authenticated HTTP entry point — wraps `withFreshToken` so 401
 * → refresh-once is automatic. Returns parsed JSON for 2xx + body;
 * `null` for 204. Throws {@link VisualizerError} on failure.
 */
async function call(path: string, opts: FetchOptions = {}): Promise<unknown> {
	const url = `${API_BASE}${path}`;
	let res: Response;
	try {
		res = await withFreshToken(async (token) => {
			const headers: Record<string, string> = {
				Authorization: `Bearer ${token}`,
				Accept: 'application/json'
			};
			if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
			const r = await fetch(url, {
				method: opts.method ?? 'GET',
				headers,
				body: opts.body
			});
			if (r.status === 401) {
				throw Object.assign(new Error('Unauthorized'), { status: 401, kind: 'auth' });
			}
			return r;
		});
	} catch (e) {
		if (e instanceof NotAuthenticatedError) {
			throw new VisualizerError(401, 'auth', e.message);
		}
		const err = e as { status?: number; kind?: string; message?: string };
		if (err && err.kind === 'auth') {
			throw new VisualizerError(
				401,
				'auth',
				'Visualizer rejected the access token. Please sign in again.'
			);
		}
		throw new VisualizerError(
			0,
			'network',
			`Network error: ${e instanceof Error ? e.message : String(e)}`
		);
	}
	if (res.status === 402 || res.status === 403) {
		const text = await res.text().catch(() => '');
		throw new VisualizerError(
			res.status,
			'premium',
			text || 'Premium subscription required for writes.'
		);
	}
	if (!res.ok) {
		const text = await res.text().catch(() => '');
		throw new VisualizerError(
			res.status,
			'other',
			`HTTP ${res.status}: ${text || res.statusText}`
		);
	}
	if (res.status === 204) return null;
	return res.json();
}

// ── Wire / Crema boundary helpers ─────────────────────────────────────

/**
 * Convert a full `ShotDetail` (returned from `GET /shots/{id}`) plus the
 * originating summary's `clock` / `updated_at` into Crema's
 * {@link WireShot} (unix MS, normalised field names). Visualizer's
 * default detail flattens scoring + journal fields onto the shot row;
 * Beanconqueror's variant nests them under `meta.visualizer` — we read
 * from both shapes defensively.
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
	const espressoNotes = fallback.private_notes ?? bcMeta?.private_notes ?? fallback.espresso_notes ?? null;
	// Crema rating is 0..5; the wire's cupping slots are 0..15. We write
	// `flavor = rating * 3` on PATCH,
	// so the pull does the inverse: prefer `flavor`, fall back to the
	// legacy `espresso_enjoyment` field for shots uploaded before the
	// switch, then round-trip back to the 0..5 scale.
	const flavorRaw =
		fallback.flavor ??
		bcMeta?.flavor ??
		fallback.espresso_enjoyment ??
		bcMeta?.espresso_enjoyment ??
		null;
	const rating =
		flavorRaw != null ? Math.max(0, Math.min(5, Math.round(flavorRaw / 3))) : null;
	// Spec note (`openapi.json` v1.8.2): the read-side detail field is
	// `tags` on `DefaultShotDetail`; the write-side PATCH field is
	// `tag_list` on `ShotUpdateRequest`. The Beanconqueror detail variant
	// has no shot-level tags. We surface both shapes defensively in case
	// a future serializer renames the read field too.
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

// ── Public surface ────────────────────────────────────────────────────

/**
 * Map a {@link ShotBean} snapshot onto the inline-bean PATCH fields
 * Visualizer accepts on `ShotUpdateRequest.shot` (the spec marks `shot`
 * as `additionalProperties: true`, so the wire allows fields beyond the
 * explicitly-listed ones, mirroring the read-side `DefaultShotDetail`).
 *
 * **Snapshot-only**: every field below is read from the frozen-at-
 * completion {@link ShotBean}, never from the live bean. Reading
 * live-bean content would retroactively rewrite history when the
 * user later edits the bag. The one place
 * live-bean lookup is correct is the {@link resolveCoffeeBagId} fallback
 * for the `coffee_bag_id` *link pointer* — content stays snapshot-only.
 *
 * Each Crema field maps to a Visualizer wire slot per the field map in
 * the PR description; empty strings / `null` sources are skipped so
 * an empty bean doesn't blow away server-side values the user filled in
 * via Visualizer's web UI.
 *
 * Pure (no fetch, no settings, no wasm). The test suite mirrors this
 * helper inline (the same way it mirrors `signatureForShot` /
 * `storedShotFromWire`) because `$lib` aliases don't resolve under
 * `node --test`. Any change to the logic here MUST land in the test's
 * inline mirror as well.
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
	// NOTE: `grinder_model` (e.g. "Niche Zero") is equipment-level, not
	// bean-level — it's resolved separately via {@link resolveGrinderModel}
	// and added to the same PATCH body alongside the inline-bean fields.
	// Don't add a `grinder_model` slot here.
	return out;
}

/**
 * Resolve the shot's equipment-level grinder model for the post-upload
 * PATCH. Cascade order (per #81):
 *
 *   1. {@link StoredShot.grinderModel} — the snapshot taken at shot
 *      completion (or a per-shot override the user typed in the shot
 *      detail panel). Wins because it's the shot's own frozen value.
 *   2. `settings.prefs.grinderModel` — the equipment-level default.
 *      Used as the fallback for legacy shots that completed before #81
 *      shipped (their `grinderModel` is `null`) and for any shot
 *      whose snapshot captured an empty default.
 *   3. `null` — neither carries a value; the upload omits the field
 *      entirely so a blank doesn't clobber a Visualizer-side edit.
 *
 * The settings fallback is the *only* place the equipment-level pref is
 * read at upload time — mirroring the snapshot-wins discipline used for
 * the bean fields, but tolerating legacy records that pre-date the
 * snapshot field. Trims both sides so " " never rides the wire.
 *
 * Pure (no fetch, no wasm). The shot-sync test mirrors this helper
 * inline — keep both in lock-step.
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
 * Resolve the `coffee_bag_id` link pointer for a shot's post-upload
 * PATCH. The snapshot is the content source of truth — but the bag's
 * *Visualizer id* is a stable mutable link not part of the shot's
 * content, so we read it from the LIVE bean every time. The snapshot's `beanId` is the FK we look up
 * against the bean library.
 *
 * This is THE ONLY place the live bean is read on the upload path —
 * everywhere else we deliberately stick to the snapshot so a later
 * content edit can't rewrite history. The link is intentionally
 * never snapshotted: a bag synced to Visualizer *after* the shot was
 * pulled would otherwise be unreachable from the older shot.
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
 * Resolve the shot's `tag_list` for the post-upload PATCH. Auto-tag
 * uploaded shots with {@link StoredShot.tags} — the single source of
 * truth for shot-level tags. Bean tags get folded into
 * this list at completion time (and at retroactive rebind time via
 * `HistoryStore.setBeanFromLive`), so there's no second source to merge.
 *
 * Trim + dedup (case-sensitive, preserve first-seen order) mirror the
 * `TagInput.commit` "trim + already-includes" pattern in
 * `$lib/components/profiles/TagInput.svelte`.
 *
 * Returns `null` when the list is empty so the caller can skip the
 * `tag_list` field entirely — sending `[]` would clobber any tags the
 * user added on Visualizer's web side.
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

/**
 * Upload a single shot. POSTs to `/shots/upload` with the JSON payload
 * (per the OAS `application/json` request body — Visualizer accepts one
 * of the brew-payload shapes, and Crema produces the Decent variant).
 * Resolves with the remote `visualizerId` so the caller can bind it onto
 * the local row.
 *
 * After a successful upload, fires a soft follow-up `PATCH /shots/{id}`
 * carrying every field `DecentUploadPayload` doesn't accept on its body
 * but `ShotUpdateRequest.shot` does:
 *   - `coffee_bag_id` — link to the synced coffee bag.
 *   - `tag_list` — auto-tag with the bean's tags.
 *   - `bean_brand` / `bean_type` / `roast_date` / `roast_level` /
 *     `bean_notes` / `grinder_setting` — the inline bean snapshot
 *     content. All sourced from {@link StoredShot.bean} — the
 *     *snapshot*, never the live bean — so a later edit to the bag
 *     can't retroactively rewrite history.
 *
 * PATCH failure is treated as soft — logged, not rethrown — so a flaky
 * link wire doesn't lose the uploaded shot.
 */
export async function uploadShot(shot: StoredShot): Promise<{ visualizerId: string }> {
	if (!isConnected()) {
		throw new VisualizerError(401, 'auth', 'Sign in to Visualizer first.');
	}
	const body = buildShotPayload(shot);
	const result = (await call('/shots/upload', { method: 'POST', body })) as
		| ShotUploadResult
		| null;
	if (!result || !result.id) {
		throw new VisualizerError(
			0,
			'other',
			'Visualizer accepted the shot but returned no id.'
		);
	}

	// Post-upload PATCH: bag link + auto-tags + inline bean snapshot
	// + equipment-level grinder model. Build each block independently
	// so empty sources skip cleanly (no risk of overwriting a
	// Visualizer-side edit with a blank value).
	const coffeeBagId = resolveCoffeeBagId(shot);
	const tagList = resolveTagList(shot);
	const inlineBean = inlineBeanPatch(shot.bean);
	const hasInlineBean = Object.keys(inlineBean).length > 0;
	// Cascade `shot.grinderModel ?? settings.prefs.grinderModel` per #81.
	// Snapshot wins; settings is the legacy-fallback. Empty/null on both
	// sides → omit the field so a Visualizer-side edit survives.
	const grinderModel = resolveGrinderModel(
		shot,
		getSettingsStore().current.grinderModel
	);
	if (
		coffeeBagId != null ||
		(tagList != null && tagList.length > 0) ||
		hasInlineBean ||
		grinderModel != null
	) {
		try {
			await patchShot(result.id, {
				...(coffeeBagId != null ? { coffeeBagId } : {}),
				...(tagList != null && tagList.length > 0 ? { tagList } : {}),
				...(hasInlineBean ? { inlineBean } : {}),
				...(grinderModel != null ? { grinderModel } : {})
			});
		} catch (e) {
			console.warn(
				'[Crema] post-upload PATCH (coffee_bag_id / tag_list / inline bean / grinder_model) failed:',
				e instanceof Error ? e.message : String(e)
			);
		}
	}

	return { visualizerId: result.id };
}

/**
 * Delete a remote shot by id. `DELETE /shots/{id}` returns 200 +
 * `{success: true}` per the spec (not 204). A 404 is treated as success.
 */
export async function deleteShot(visualizerId: string): Promise<void> {
	if (!isConnected()) {
		throw new VisualizerError(401, 'auth', 'Sign in to Visualizer first.');
	}
	try {
		await call(`/shots/${visualizerId}`, { method: 'DELETE' });
	} catch (e) {
		if (e instanceof VisualizerError && e.status === 404) return;
		throw e;
	}
}

/**
 * Patch a remote shot's editable annotations. The spec requires a
 * `{ shot: {...} }` envelope. Crema-side fields map onto the spec as
 * follows:
 *
 *   - `notes` → `private_notes` (kept out of the public profile view)
 *   - `rating` (0..5 star) → `flavor` (0..15 SCA cupping slot) via
 *     `flavor = rating * 3` clamped to 0..15 and rounded to int. A
 *     `rating` of `null` omits `flavor` entirely.
 *   - `coffeeBagId` → `coffee_bag_id` — link the shot to a synced bag.
 *   - `tagList` → `tag_list` — overwrite the shot's tag set.
 *   - `inlineBean` — extra bean-content fields (`bean_brand`,
 *     `bean_type`, `roast_date`, `roast_level`, `bean_notes`,
 *     `grinder_setting`). Merged into `shot` via the spec's
 *     `additionalProperties: true` index signature on
 *     `ShotUpdateRequest.shot`.
 *   - `grinderModel` → `grinder_model` — equipment-level grinder model
 *     (sibling to `grinder_setting`, but shot-level not bean-level).
 *     Pre-resolved by {@link resolveGrinderModel} so this slot is the
 *     raw string Visualizer should store.
 *
 * Only the keys explicitly present on `patch` ride out; absent keys are
 * left untouched server-side.
 */
export async function patchShot(
	visualizerId: string,
	patch: {
		rating?: number | null;
		notes?: string;
		coffeeBagId?: string | null;
		tagList?: string[];
		inlineBean?: Record<string, unknown>;
		grinderModel?: string;
	}
): Promise<void> {
	if (!isConnected()) {
		throw new VisualizerError(401, 'auth', 'Sign in to Visualizer first.');
	}
	// Start from the inline-bean block (if any) so the typed slots below
	// take precedence on key collisions. `ShotUpdateRequest.shot` is
	// `{ explicit props } & { [key: string]: unknown }` per the spec's
	// `additionalProperties: true`, so the extra inline-bean fields ride
	// directly on the same body.
	const shotBody: ShotUpdateRequest['shot'] = { ...(patch.inlineBean ?? {}) };
	if (patch.notes !== undefined) shotBody.private_notes = patch.notes;
	if (patch.rating !== undefined) {
		// 0..5 star → 0..15 cupping slot. `null` rating means "unrated" —
		// omit `flavor` rather than sending 0 so we don't blow away an
		// existing server-side cupping score with a noise-zero.
		if (patch.rating != null) {
			shotBody.flavor = Math.max(0, Math.min(15, Math.round(patch.rating * 3)));
		}
	}
	if (patch.coffeeBagId !== undefined) shotBody.coffee_bag_id = patch.coffeeBagId;
	if (patch.tagList !== undefined) shotBody.tag_list = patch.tagList;
	if (patch.grinderModel !== undefined) {
		// Rides via the `additionalProperties: true` index signature on
		// `ShotUpdateRequest.shot` (the spec's read-side `DefaultShotDetail`
		// has a typed `grinder_model: string | null` slot, but the
		// write-side request only lists a subset and forwards the rest).
		shotBody.grinder_model = patch.grinderModel;
	}
	const envelope: ShotUpdateRequest = { shot: shotBody };
	await call(`/shots/${visualizerId}`, {
		method: 'PATCH',
		body: JSON.stringify(envelope)
	});
}

/**
 * Fetch one page of remote shot SUMMARIES. The summary is the spec's
 * `{ id, clock, updated_at }` triple — no telemetry or annotations.
 * Use {@link fetchShotDetail} to materialise a single full row.
 *
 * The default `sort=updated_at` so {@link pullAllShotsSince} can stop
 * walking as soon as it crosses the local cursor.
 */
export async function pullShots(
	page = 1,
	itemsPerPage = 50
): Promise<{ summaries: ShotSummary[]; paging: Paging }> {
	if (!isConnected()) {
		throw new VisualizerError(401, 'auth', 'Sign in to Visualizer first.');
	}
	const items = Math.min(100, Math.max(1, Math.floor(itemsPerPage)));
	const body = (await call(
		`/shots?page=${page}&items=${items}&sort=updated_at`
	)) as ShotListResponse;
	return {
		summaries: body?.data ?? [],
		paging: body?.paging ?? { count: 0, page, limit: items, pages: 1 }
	};
}

/** Fetch the full record for one shot (`GET /shots/{id}`). */
export async function fetchShotDetail(id: string): Promise<ShotDetail> {
	if (!isConnected()) {
		throw new VisualizerError(401, 'auth', 'Sign in to Visualizer first.');
	}
	return (await call(`/shots/${id}`)) as ShotDetail;
}

/**
 * Progress callback fired between pages when {@link pullAllShotsSince}
 * walks pagination. UIs can wire this to a progress bar or counter.
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

/**
 * Pull every remote shot updated since `sinceMs` (Crema-side unix-ms
 * cursor). Walks pages of `/shots?sort=updated_at` until either the
 * server runs out OR a summary's `updated_at` is older than the cursor.
 * For each summary newer than the cursor we fetch `/shots/{id}` and
 * build a {@link WireShot}.
 *
 * **Safety cap**: walks at most `maxPages` pages (default 200 → 20k
 * shots at items=100) so a buggy server can't pin the tab.
 */
export async function pullAllShotsSince(
	sinceMs: number,
	opts: {
		itemsPerPage?: number;
		maxPages?: number;
		onProgress?: (p: PullProgress) => void;
	} = {}
): Promise<{ shots: WireShot[]; truncated: boolean }> {
	const items = Math.min(100, Math.max(1, Math.floor(opts.itemsPerPage ?? 50)));
	const maxPages = opts.maxPages ?? 200;
	const cursorSec = Math.floor(sinceMs / 1000);
	const wireShots: WireShot[] = [];
	let totalPages = 1;
	let reachedOlder = false;

	for (let page = 1; page <= maxPages; page++) {
		const { summaries, paging } = await pullShots(page, items);
		totalPages = paging.pages || totalPages;
		for (const summary of summaries) {
			if (summary.updated_at < cursorSec) {
				// Sorted by updated_at desc on Visualizer's side, so once we
				// see an older row we know we've crossed the cursor.
				reachedOlder = true;
				continue;
			}
			try {
				const detail = await fetchShotDetail(summary.id);
				wireShots.push(wireShotFromDetail(summary, detail));
			} catch (e) {
				// One failed detail fetch shouldn't blow up the whole sync —
				// re-throw only on auth (no point continuing). Other errors
				// just skip the row; the next sync will retry.
				if (e instanceof VisualizerError && e.kind === 'auth') throw e;
			}
		}
		const hasMore = !reachedOlder && page < totalPages && summaries.length > 0;
		opts.onProgress?.({
			fetched: wireShots.length,
			page,
			totalPages,
			hasMore
		});
		if (!hasMore) {
			return { shots: wireShots, truncated: false };
		}
	}
	return { shots: wireShots, truncated: true };
}


// Reconciliation, signature helpers, and the WireShot type live in
// `./shot-sync-signatures.ts` — pure, no wasm / fetch / settings.

// Silence the unused-import lint when the DeleteResult alias isn't wired
// through any helper above — it's exported for callers that want to
// type their direct usage.
export type { DeleteResult };
