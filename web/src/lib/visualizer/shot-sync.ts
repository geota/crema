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
 * Direction & modes (docs/36 §2): unchanged from previous cut.
 *   - **Backup**  — push only.
 *   - **Pull**    — read remote shots; de-dup via `signatureForShot`.
 *   - **Two-way** — both directions; LWW conflict resolution.
 *
 * Auth: every authenticated call funnels through `withFreshToken`.
 */

import { VisualizerError } from '$lib/bean';
// Imported directly from the store module rather than via `$lib/bean`
// to avoid an import cycle: `$lib/bean/index.ts` re-exports
// `$lib/visualizer`, which is *this* module's package.
import { getBeanStore } from '$lib/bean/store.svelte';
import { exportStoredShotAsV2Json } from '$lib/history/v2-export';
import type { StoredShot } from '$lib/history/model';
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
	// `flavor = rating * 3` on PATCH (docs/38 §"Recommendations" row 2),
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
 * Resolve the bag-link + tag-list metadata that `POST /shots/upload`
 * doesn't accept on its body but `PATCH /shots/{id}` does. Per docs/28
 * §"Bean ↔ shot association", the shot carries a denormalised
 * {@link StoredShot.bean} snapshot (content source of truth) plus a
 * `beanId` link into the live library; the snapshot is frozen at
 * completion, while the live bean carries the mutable Visualizer link
 * (`Bean.visualizerId`) and the editable `Bean.tags` array.
 *
 * Resolution rules:
 *   - `coffee_bag_id`: read from the live bean (looked up via
 *     `shot.bean.beanId`). The snapshot itself has no `visualizerId`
 *     field today — if it ever gains one, prefer that.
 *   - `tag_list`: union of {@link StoredShot.tags} (shot-level tags;
 *     pulled-back from a previous Visualizer sync) and the live bean's
 *     `tags` array. Shot tags ride first, bean tags after, dedup is
 *     case-sensitive preserving first occurrence (mirrors the
 *     `TagInput` "trim + already-includes" pattern in `$lib/components`).
 *     The shot-snapshot's `tags` field (if it ever lands per docs/28) is
 *     read defensively and folded in ahead of the live bean's tags.
 *
 * Returns `null` on either field when nothing is resolvable. Caller
 * skips the follow-up PATCH entirely if the whole record is empty (so
 * an empty union doesn't clobber server-side tags).
 */
function resolvePostUploadLinks(shot: StoredShot): {
	coffeeBagId: string | null;
	tagList: string[] | null;
} {
	// `ShotBean` doesn't carry a snapshotted `visualizerId` today, but
	// docs/28 §design-decisions §3 calls out the snapshot as the content
	// source of truth — so we read defensively in case the field lands
	// later. Fall through to the live bean if absent.
	const snapshot = shot.bean ?? null;
	const snapshotLoose = snapshot as unknown as Record<string, unknown> | null;
	const snapshotVisualizerId =
		snapshotLoose && typeof snapshotLoose.visualizerId === 'string'
			? (snapshotLoose.visualizerId as string)
			: null;
	const snapshotTagsRaw = snapshotLoose?.tags;
	const snapshotTags = Array.isArray(snapshotTagsRaw)
		? snapshotTagsRaw.filter((t): t is string => typeof t === 'string')
		: [];

	let liveBean: ReturnType<ReturnType<typeof getBeanStore>['getBean']> = null;
	const liveBeanId = snapshot?.beanId ?? null;
	if (liveBeanId) {
		try {
			liveBean = getBeanStore().getBean(liveBeanId);
		} catch {
			liveBean = null;
		}
	}

	const coffeeBagId = snapshotVisualizerId ?? liveBean?.visualizerId ?? null;

	// Union: shot.tags first, snapshot-bean.tags second, live-bean.tags
	// third. Each source is trimmed + filtered for non-empties to match
	// the bean library's normalisation (`TagInput.commit` in
	// `$lib/components/profiles/TagInput.svelte`: `t.trim()`, dedup
	// case-sensitive). Set preserves first-seen order.
	const merged: string[] = [];
	const seen = new Set<string>();
	const pushAll = (src: readonly string[]): void => {
		for (const raw of src) {
			const t = raw.trim();
			if (!t || seen.has(t)) continue;
			seen.add(t);
			merged.push(t);
		}
	};
	pushAll(shot.tags ?? []);
	pushAll(snapshotTags);
	pushAll(liveBean?.tags ?? []);

	const tagList = merged.length > 0 ? merged : null;

	return { coffeeBagId, tagList };
}

/**
 * Upload a single shot. POSTs to `/shots/upload` with the JSON payload
 * (per the OAS `application/json` request body — Visualizer accepts one
 * of the brew-payload shapes, and Crema produces the Decent variant).
 * Resolves with the remote `visualizerId` so the caller can bind it onto
 * the local row.
 *
 * After a successful upload, fires a soft follow-up `PATCH /shots/{id}`
 * to wire the shot to its synced coffee bag (item 1) and to seed the
 * shot's `tag_list` from the bean's tags (item 5), per docs/38
 * §"Recommendations" rows 1 + 5. Both fields live on
 * `ShotUpdateRequest` (not `DecentUploadPayload`), so they must ride a
 * separate PATCH. PATCH failure is treated as soft — logged, not
 * rethrown — so a flaky link wire doesn't lose the uploaded shot.
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

	// Items 1 + 5: link the shot to its bag and stamp tags via a
	// follow-up PATCH. Soft failure — log + swallow.
	const { coffeeBagId, tagList } = resolvePostUploadLinks(shot);
	if (coffeeBagId != null || (tagList != null && tagList.length > 0)) {
		try {
			await patchShot(result.id, {
				...(coffeeBagId != null ? { coffeeBagId } : {}),
				...(tagList != null && tagList.length > 0 ? { tagList } : {})
			});
		} catch (e) {
			console.warn(
				'[Crema] post-upload PATCH (coffee_bag_id / tag_list) failed:',
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
 * follows (per docs/38 §"Recommendations"):
 *
 *   - `notes` → `private_notes` (kept out of the public profile view)
 *   - `rating` (0..5 star) → `flavor` (0..15 SCA cupping slot) via
 *     `flavor = rating * 3` clamped to 0..15 and rounded to int. A
 *     `rating` of `null` omits `flavor` entirely.
 *   - `coffeeBagId` → `coffee_bag_id` — link the shot to a synced bag.
 *   - `tagList` → `tag_list` — overwrite the shot's tag set.
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
	}
): Promise<void> {
	if (!isConnected()) {
		throw new VisualizerError(401, 'auth', 'Sign in to Visualizer first.');
	}
	const shotBody: ShotUpdateRequest['shot'] = {};
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

/**
 * @deprecated Kept for source compatibility. Forwards to
 * {@link pullAllShotsSince}. Prefer the new name + signature.
 */
export async function pullAllShots(
	sinceMs: number,
	opts: {
		perPage?: number;
		maxPages?: number;
		onProgress?: (p: { fetched: number; page: number; hasMore: boolean }) => void;
	} = {}
): Promise<{ shots: WireShot[]; truncated: boolean }> {
	return pullAllShotsSince(sinceMs, {
		itemsPerPage: opts.perPage,
		maxPages: opts.maxPages,
		onProgress: opts.onProgress
			? (p) =>
					opts.onProgress!({
						fetched: p.fetched,
						page: p.page - 1, // legacy callers expected 0-based
						hasMore: p.hasMore
					})
			: undefined
	});
}

// Reconciliation, signature helpers, and the WireShot type live in
// `./shot-sync-signatures.ts` — pure, no wasm / fetch / settings.

// Silence the unused-import lint when the DeleteResult alias isn't wired
// through any helper above — it's exported for callers that want to
// type their direct usage.
export type { DeleteResult };
