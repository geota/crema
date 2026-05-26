/**
 * `$lib/bean/visualizer-sync` — bidirectional sync between Crema's bean
 * library and Visualizer's `/coffee_bags` + `/roasters` REST endpoints
 * (two-way direction).
 *
 * Direction:
 *   - **Pull** every remote bag + roaster into the Crema library on demand.
 *     Mappings round-trip via the `metadata` open object (Visualizer's
 *     `additionalProperties: true` lets us tuck Crema-only fields there
 *     losslessly).
 *   - **Push** every Crema bean + roaster (create / update / delete) the
 *     remote doesn't already mirror. Identity rides on the `visualizerId`
 *     field on the Crema record; on first push we capture the remote id and
 *     persist it so subsequent updates PATCH the same row.
 *   - **Conflict resolution** — last-write-wins on `updated_at` per the
 *     coordinator's direction. Visualizer is the source-of-truth for the
 *     shared library across devices: if both sides changed since the last
 *     sync, the remote wins. Tested separately in the sync helper.
 *
 * Auth: OAuth 2.0 Authorization-Code + PKCE (Doorkeeper on Visualizer's
 * side). Tokens live in `$lib/visualizer/token-store`; this module asks
 * `withFreshToken` for a known-fresh bearer and sets
 * `Authorization: Bearer …` on every request. HTTP-Basic was removed in
 * the 2026-05-24 cut — one Visualizer SaaS, one auth path.
 *
 * Premium gating: bag / roaster *write* endpoints require Visualizer
 * premium (€5/mo). We detect the gating via HTTP 403 (or a recognisable
 * error payload) on the first push attempt, cache the flag in localStorage
 * so we don't re-probe every sync, and surface "Connected — read-only
 * (free tier)" in the UI.
 *
 * Sans-IO unfriendly by definition (this calls `fetch`), so it lives in the
 * shell rather than the wasm core.
 */

import { readJson, writeJson } from '$lib/utils/storage';
import {
	isConnected,
	NotAuthenticatedError,
	signatureForBean,
	signatureForRoaster,
	withFreshToken
} from '$lib/visualizer';
import { updateSyncConfig } from '$lib/visualizer/sync-config';
import type { components } from '$lib/visualizer/openapi';
import {
	blankBean,
	blankRoaster,
	type Bean,
	type BeanMix,
	type BeanOrigin,
	type Roaster
} from './model';
import type { BeanLibraryStore } from './store.svelte';

// Spec-typed aliases so the wire shape stays in lock-step with the
// vendored OpenAPI spec (`pnpm openapi` regenerates `openapi.d.ts`).
type CoffeeBagDetail = components['schemas']['CoffeeBagDetail'];
type CoffeeBagListResponse = components['schemas']['CoffeeBagListResponse'];
type CoffeeBagWriteRequest = components['schemas']['CoffeeBagWriteRequest'];
type RoasterDetail = components['schemas']['RoasterDetail'];
type RoasterListResponse = components['schemas']['RoasterListResponse'];
type RoasterWriteRequest = components['schemas']['RoasterWriteRequest'];

/** Default API base — overridable for development. */
const API_BASE = 'https://visualizer.coffee/api';
/** localStorage key for the sync settings (creds + last-sync metadata). */
const SYNC_KEY = 'crema.beans.sync.v1';

/**
 * Persisted sync metadata. Credentials live in `$lib/visualizer/token-store`
 * — the OAuth bearer is the source of authority. This shape holds only the
 * sync bookkeeping (`lastSyncAt`, `premium`).
 */
export interface VisualizerSyncSettings {
	/** Unix-ms timestamp of the last successful sync. */
	lastSyncAt: number | null;
	/**
	 * Cached premium-status flag. `true` = bag/roaster writes succeed.
	 * `false` = the user is on the free tier; reads still work. `null` =
	 * not yet probed. We cache so we don't re-probe on every sync.
	 */
	premium: boolean | null;
}

const DEFAULT_SYNC_SETTINGS: VisualizerSyncSettings = {
	lastSyncAt: null,
	premium: null
};

export function readSyncSettings(): VisualizerSyncSettings {
	const raw = readJson<VisualizerSyncSettings>(SYNC_KEY, DEFAULT_SYNC_SETTINGS);
	// Tolerate the legacy `{ username, password, lastSyncAt, premium }`
	// shape — `migrateLegacyBasicAuth` strips the credential fields out
	// at boot, but we may be called before that runs (or in tests).
	return {
		lastSyncAt: raw.lastSyncAt ?? null,
		premium: raw.premium ?? null
	};
}
export function writeSyncSettings(next: Partial<VisualizerSyncSettings>): void {
	const merged = { ...readSyncSettings(), ...next };
	writeJson(SYNC_KEY, merged);
}

/** A single line in the post-sync activity log. */
export interface SyncLogEntry {
	direction: 'push' | 'pull' | 'skip' | 'delete';
	kind: 'bean' | 'roaster';
	id: string;
	name: string;
	at: number;
	error?: string;
}

/** The aggregate result returned by {@link runSync}. */
export interface SyncResult {
	ok: boolean;
	pulled: number;
	pushed: number;
	deleted: number;
	skipped: number;
	premiumLocked: boolean;
	log: SyncLogEntry[];
	error?: string;
}

// ── Visualizer wire shapes ─────────────────────────────────────────────
//
// The read-side rides on the spec types directly (`CoffeeBagDetail`,
// `RoasterDetail`). The Crema-side merged shapes below add the read-only
// fields the spec exposes on the LIST/DETAIL paths but not on the write
// envelopes (Visualizer round-trips `metadata` losslessly, so the
// Crema-only extension keys live there). The write paths build the
// spec's `*WriteRequest` envelopes via {@link bagBodyToWriteRequest} /
// {@link roasterBodyToWriteRequest} below.

/** Crema-side merged shape used by `beanToWire` / `beanFromWire`.
 *  Loosens DETAIL's required `id` + `name` so the local-only encode
 *  side can produce a body for `POST /coffee_bags` (no id yet). */
type BagWire = Omit<CoffeeBagDetail, 'id' | 'name'> & {
	id?: string;
	name: string;
	/** Mirrors the write request's `roaster_id` (DETAIL omits it but LIST
	 * filters expose it; the round-trip needs it). */
	roaster_id?: string | null;
	canonical_coffee_bag_id?: string | null;
	updated_at?: string | null;
};

/** Crema-side merged shape used by `roasterToWire` / `roasterFromWire`. */
type RoasterWire = Omit<RoasterDetail, 'id' | 'name'> & {
	id?: string;
	name: string;
	canonical_roaster_id?: string | null;
};

// ── Mapping helpers ────────────────────────────────────────────────────

/**
 * Crema 1..10 → Visualizer's free-text `roast_level`. Exported so the shot
 * uploader (`$lib/visualizer/shot-sync`) can encode the inline bean snapshot's
 * `roastLevel` with the same banding the bean library uses.
 */
export function roastLevelToWire(level: number | null): string | null {
	if (level == null) return null;
	if (level <= 2) return 'Light';
	if (level <= 4) return 'Medium-Light';
	if (level <= 6) return 'Medium';
	if (level <= 8) return 'Medium-Dark';
	return 'Dark';
}
/** Visualizer's free-text `roast_level` → Crema's 1..10 scale. */
function roastLevelFromWire(label: string | null | undefined): number | null {
	if (!label) return null;
	const norm = label.trim().toLowerCase();
	if (norm.includes('cinnamon') || norm === 'light') return 2;
	if (norm.includes('medium-light') || norm.includes('city')) return 3;
	if (norm.includes('medium-dark') || norm.includes('full city')) return 6;
	if (norm.includes('dark') || norm.includes('french') || norm.includes('italian'))
		return 9;
	if (norm.includes('medium')) return 5;
	return null;
}

function mixFromMetadata(meta: Record<string, unknown> | null | undefined): BeanMix | null {
	const mix = meta?.crema_mix;
	return mix === 'single' || mix === 'blend' ? mix : null;
}

/** Encode a Crema bean → Visualizer's POST/PATCH wire body. */
export function beanToWire(bean: Bean, roasterRemoteId: string | null): BagWire {
	const crema: Record<string, unknown> = {
		crema_id: bean.id,
		crema_mix: bean.mix,
		crema_decaf: bean.decaf,
		crema_favourite: bean.favourite,
		crema_bag_size_g: bean.bagSize,
		crema_remaining_g: bean.remaining,
		crema_rating: bean.rating,
		crema_grinder: bean.grinder,
		crema_grinder_setting: bean.grinderSetting,
		crema_beanconqueror_id: bean.beanconquerorId,
		crema_opened_on: bean.openedOn,
		crema_updated_at: bean.updatedAt
	};
	// Round-trip the Crema metadata blob too (lossless escape valve).
	const meta = { ...(bean.metadata ?? {}), crema };
	return {
		id: bean.visualizerId ?? undefined,
		name: bean.name || 'Untitled bag',
		roaster_id: roasterRemoteId,
		roast_date: bean.roastedOn,
		frozen_date: bean.frozenOn,
		defrosted_date: bean.defrostedOn,
		roast_level: roastLevelToWire(bean.roastLevel),
		country: bean.origin.country,
		region: bean.origin.region,
		farm: bean.origin.farm,
		farmer: bean.origin.farmer,
		variety: bean.origin.variety,
		elevation: bean.origin.elevation,
		processing: bean.origin.processing,
		harvest_time: bean.origin.harvestTime,
		quality_score: bean.qualityScore || null,
		tasting_notes: bean.tastingNotes || null,
		place_of_purchase: bean.placeOfPurchase,
		url: bean.url,
		notes: bean.notes || null,
		archived_at: bean.archivedAt ? new Date(bean.archivedAt).toISOString() : null,
		metadata: meta
	};
}

/**
 * Decode a Visualizer bag → Crema bean. Reuses the local roaster id when the
 * caller's `roasterIdLookup` resolves the remote `roaster_id`. The Crema-only
 * fields (favourite, bag size, grinder, mix, …) ride out of the wire body's
 * `metadata.crema` block so a round-trip is lossless.
 */
export function beanFromWire(
	wire: BagWire,
	roasterIdLookup: (remoteId: string | null | undefined) => string | null
): Bean {
	const meta = (wire.metadata ?? {}) as Record<string, unknown>;
	const crema = (meta.crema ?? {}) as Record<string, unknown>;
	const bean = blankBean(typeof crema.crema_id === 'string' ? crema.crema_id : undefined);
	bean.visualizerId = wire.id ?? null;
	bean.name = wire.name;
	bean.roasterId = roasterIdLookup(wire.roaster_id);
	bean.roastedOn = wire.roast_date ?? null;
	bean.frozenOn = wire.frozen_date ?? null;
	bean.defrostedOn = wire.defrosted_date ?? null;
	bean.openedOn = typeof crema.crema_opened_on === 'string' ? crema.crema_opened_on : null;
	bean.roastLevel = roastLevelFromWire(wire.roast_level);
	bean.mix = mixFromMetadata(meta);
	bean.decaf = crema.crema_decaf === true;
	bean.origin = {
		country: wire.country ?? null,
		region: wire.region ?? null,
		farm: wire.farm ?? null,
		farmer: wire.farmer ?? null,
		variety: wire.variety ?? null,
		elevation: wire.elevation ?? null,
		processing: wire.processing ?? null,
		harvestTime: wire.harvest_time ?? null
	} satisfies BeanOrigin;
	bean.bagSize =
		typeof crema.crema_bag_size_g === 'number' ? crema.crema_bag_size_g : 0;
	bean.remaining =
		typeof crema.crema_remaining_g === 'number' ? crema.crema_remaining_g : 0;
	bean.qualityScore = wire.quality_score ?? '';
	bean.tastingNotes = wire.tasting_notes ?? '';
	bean.rating = typeof crema.crema_rating === 'number' ? crema.crema_rating : 0;
	bean.placeOfPurchase = wire.place_of_purchase ?? null;
	bean.url = wire.url ?? null;
	bean.notes = wire.notes ?? '';
	bean.favourite = crema.crema_favourite === true;
	bean.archivedAt = wire.archived_at ? Date.parse(wire.archived_at) : null;
	bean.grinder = typeof crema.crema_grinder === 'string' ? crema.crema_grinder : '';
	bean.grinderSetting =
		typeof crema.crema_grinder_setting === 'string' ? crema.crema_grinder_setting : '';
	bean.beanconquerorId =
		typeof crema.crema_beanconqueror_id === 'string'
			? crema.crema_beanconqueror_id
			: null;
	bean.updatedAt =
		typeof crema.crema_updated_at === 'number'
			? crema.crema_updated_at
			: wire.updated_at
				? Date.parse(wire.updated_at)
				: Date.now();
	bean.metadata = {
		...meta,
		crema: undefined // keep Crema-only out of the visible blob
	};
	delete (bean.metadata as Record<string, unknown>).crema;
	return bean;
}

export function roasterToWire(roaster: Roaster): RoasterWire {
	// Tuck the Crema-only `city` field into the metadata escape valve.
	// Visualizer's `RoasterWire` does not (yet) model `metadata`, so we
	// can't push the city as an extension key on the parent body — for
	// the moment we round-trip it through the roaster's own `metadata`
	// blob when (and if) Visualizer adds the field. Until then, `city`
	// stays local. This keeps the wire shape strict and the round-trip
	// non-lossy for the fields Visualizer does model.
	return {
		id: roaster.visualizerId ?? undefined,
		name: roaster.name,
		website: roaster.website,
		image_url: roaster.imageUrl,
		canonical_roaster_id: roaster.canonicalRoasterId
	};
}

export function roasterFromWire(wire: RoasterWire): Roaster {
	const r = blankRoaster(wire.name);
	r.visualizerId = wire.id ?? null;
	r.website = wire.website ?? null;
	r.imageUrl = wire.image_url ?? null;
	r.canonicalRoasterId = wire.canonical_roaster_id ?? null;
	return r;
}

// ── Spec-envelope helpers ──────────────────────────────────────────────
//
// Both `POST /roasters` and `PATCH /roasters/{id}` expect
// `{ roaster: {...} }` per the `RoasterWriteRequest` schema; ditto
// `CoffeeBagWriteRequest` → `{ coffee_bag: {...} }`. The previous cut
// sent the bare body, which the server treats as `{}` (no recognised
// keys) — every write was a no-op. These two helpers wrap each body in
// the right envelope so the writes actually land.

function roasterBodyToWriteRequest(body: RoasterWire): RoasterWriteRequest {
	return {
		roaster: {
			name: body.name,
			website: body.website ?? null,
			canonical_roaster_id: body.canonical_roaster_id ?? null
		}
	};
}

function bagBodyToWriteRequest(body: BagWire): CoffeeBagWriteRequest {
	return {
		coffee_bag: {
			name: body.name,
			roaster_id: body.roaster_id ?? null,
			canonical_coffee_bag_id: body.canonical_coffee_bag_id ?? null,
			roast_date: body.roast_date ?? null,
			frozen_date: body.frozen_date ?? null,
			defrosted_date: body.defrosted_date ?? null,
			roast_level: body.roast_level ?? null,
			country: body.country ?? null,
			region: body.region ?? null,
			farm: body.farm ?? null,
			farmer: body.farmer ?? null,
			variety: body.variety ?? null,
			elevation: body.elevation ?? null,
			processing: body.processing ?? null,
			harvest_time: body.harvest_time ?? null,
			quality_score: body.quality_score ?? null,
			tasting_notes: body.tasting_notes ?? null,
			place_of_purchase: body.place_of_purchase ?? null,
			url: body.url ?? null,
			notes: body.notes ?? null,
			metadata: body.metadata ?? null
		}
	};
}

// ── HTTP plumbing ──────────────────────────────────────────────────────

interface FetchOptions {
	method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
	body?: unknown;
}

/**
 * Throw when the HTTP response is an error. Tagged so the caller can branch on
 * `kind: 'auth' | 'premium' | 'other'`. `'auth'` now means "the OAuth bearer
 * was rejected and refresh failed" — the caller should prompt re-sign-in.
 */
export class VisualizerError extends Error {
	constructor(
		readonly status: number,
		readonly kind: 'auth' | 'premium' | 'network' | 'other',
		message: string
	) {
		super(message);
	}
}

/**
 * Single HTTP entry point — every other helper in this file funnels here.
 * Always sends `Authorization: Bearer …` from {@link withFreshToken}; on a
 * 401 the token-store will have transparently refreshed once, and a still-
 * failing call surfaces as `VisualizerError('auth')`.
 */
async function call(
	path: string,
	opts: FetchOptions = {}
): Promise<unknown> {
	const url = `${API_BASE}${path}`;
	const doFetch = async (token: string): Promise<Response> => {
		const headers: Record<string, string> = {
			Authorization: `Bearer ${token}`,
			Accept: 'application/json'
		};
		if (opts.body) headers['Content-Type'] = 'application/json';
		return fetch(url, {
			method: opts.method ?? 'GET',
			headers,
			body: opts.body ? JSON.stringify(opts.body) : undefined
		});
	};

	let res: Response;
	try {
		res = await withFreshToken(async (token) => {
			const r = await doFetch(token);
			// `withFreshToken` retries once on a 401 — throw a tagged
			// object so it recognises the failure shape.
			if (r.status === 401) {
				throw Object.assign(new Error('Unauthorized'), {
					status: 401,
					kind: 'auth'
				});
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
		// Premium gating — bag/roaster CRUD is paywalled.
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

// ── Public surface ─────────────────────────────────────────────────────

/**
 * Verify the connection AND probe the current premium tier.
 *
 * Two-step:
 *   1. Read `/coffee_bags?items=1` — catches auth errors early; works on
 *      any tier so doesn't reveal the user's status.
 *   2. Probe via {@link probePremiumWrite} — `POST /roasters` with a
 *      sentinel name then immediately `DELETE` it. The HTTP status of
 *      the POST is the one authoritative signal Visualizer's API
 *      exposes for tier membership (the spec confirms `/me` has no
 *      premium field).
 *
 * The discovered tier is written to both the legacy
 * `crema.beans.sync.v1` settings AND the active
 * `crema.visualizer.sync.v1` config so the UI's `premiumLocked`
 * derived value picks up the new tier on the same render. Without
 * this, a user who upgrades from free → supporter on visualizer.coffee
 * sees the bean/roaster push buttons stay greyed until they manually
 * run a Sync now that happens to attempt a write.
 *
 * Requires OAuth sign-in first — returns `{ ok: false, error }` otherwise.
 */
export async function testConnection(): Promise<
	{ ok: true; premium: boolean | null } | { ok: false; error: string }
> {
	if (!isConnected()) {
		return { ok: false, error: 'Sign in to Visualizer first.' };
	}
	try {
		await call('/coffee_bags?items=1');
		const premium = await probePremiumWrite();
		// Mirror the result into both caches so every UI surface that
		// reads either one (`premiumLocked` on BeanSyncSection reads the
		// active config; the legacy settings drive the upload-queue
		// recoverability classifier) agrees.
		writeSyncSettings({ premium });
		updateSyncConfig({ premium });
		return { ok: true, premium };
	} catch (e) {
		if (e instanceof VisualizerError) {
			return { ok: false, error: e.message };
		}
		return { ok: false, error: String(e) };
	}
}

/**
 * Clear the cached premium-tier flag from both sync-config locations.
 * Called on disconnect so that reconnecting with a different account
 * (or after a tier change) doesn't inherit a stale `false` — the next
 * Test or Sync will re-probe from scratch.
 */
export function clearVisualizerPremiumCache(): void {
	writeSyncSettings({ premium: null });
	updateSyncConfig({ premium: null });
}

/**
 * Probe Visualizer for the current premium tier via a sentinel write.
 *
 * `POST /roasters` is one of Visualizer's premium-gated write endpoints;
 * its status code is the authoritative signal:
 *   - 201 → premium (Supporter or higher)
 *   - 402/403 → free tier
 *   - anything else → unknown (network, transient server error)
 *
 * The sentinel roaster is deleted immediately after creation. If the
 * DELETE leg fails (network glitch between the two requests), the row
 * lingers on the user's Visualizer account; the next full
 * {@link runSync} reconcile pass will clean it up the same way it
 * cleans any locally-absent remote row. The name carries a `__crema_`
 * prefix so the user recognises it if they see it in the Visualizer UI.
 *
 * Returns `null` (not `false`) on transient/unknown failures so the UI
 * shows "tier unknown" rather than locking buttons.
 */
async function probePremiumWrite(): Promise<boolean | null> {
	const sentinelName = `__crema_premium_probe_${Date.now()}`;
	try {
		const created = (await call('/roasters', {
			method: 'POST',
			body: {
				roaster: {
					name: sentinelName,
					website: null,
					canonical_roaster_id: null
				}
			}
		})) as RoasterWire;
		if (created?.id) {
			try {
				await call(`/roasters/${created.id}`, { method: 'DELETE' });
			} catch {
				// Cleanup failure is non-fatal — the runSync reconcile
				// pass will tidy up. Don't surface to the user.
				console.warn(
					`[visualizer] premium-probe sentinel ${sentinelName} not cleaned up; will be removed on next full Sync`
				);
			}
		}
		return true;
	} catch (e) {
		if (e instanceof VisualizerError && e.kind === 'premium') {
			return false;
		}
		// Unknown error (network, 5xx, auth-after-refresh) — leave tier
		// undetermined so the UI doesn't lock anything pre-emptively.
		return null;
	}
}

/**
 * The main bidirectional sync. Reads every remote bag + roaster, pushes every
 * Crema-side create/update/delete, applies remote-wins last-write-wins on
 * conflicts. Returns an aggregate {@link SyncResult} with a log the UI can
 * surface as a recent-activity list.
 *
 * Defensive against premium gating: a 403 / 402 on the first write switches
 * the run into read-only mode and continues pulling without retrying writes.
 *
 * @param library Live store reference — sync mutates beans/roasters in place.
 */
export async function runSync(library: BeanLibraryStore): Promise<SyncResult> {
	const settings = readSyncSettings();
	const log: SyncLogEntry[] = [];
	const result: SyncResult = {
		ok: false,
		pulled: 0,
		pushed: 0,
		deleted: 0,
		skipped: 0,
		premiumLocked: settings.premium === false,
		log
	};
	if (!isConnected()) {
		result.error = 'Sign in to Visualizer first.';
		return result;
	}

	try {
		// 1) Pull every remote roaster — these are the parent rows for bags.
		//    `items=100` is the spec's per-page max; we walk pages 1..N
		//    until `paging.pages` is exhausted (or the safety cap fires).
		const remoteRoasters: RoasterWire[] = [];
		let page = 1;
		while (page < 50) {
			const body = (await call(
				`/roasters?items=100&page=${page}`
			)) as RoasterListResponse;
			const data = body?.data ?? [];
			remoteRoasters.push(...(data as RoasterWire[]));
			const totalPages = body?.paging?.pages ?? page;
			if (page >= totalPages || data.length === 0) break;
			page += 1;
		}
		// Build a remote-id → local-id lookup.
		const remoteRoasterIdToLocal = new Map<string, string>();
		// First pass: reconcile by visualizer id, then by signature, then by
		// name. Signature binding catches the case where the
		// user created the same roaster on two devices pre-sign-in — both
		// rows have null visualizerId locally, identical normalised names,
		// so the signature collision means we should bind to the remote
		// rather than create a duplicate row.
		for (const wire of remoteRoasters) {
			if (!wire.id) continue;
			const wireSig = signatureForRoaster({ name: wire.name });
			const localById = library.roasters.find((r) => r.visualizerId === wire.id);
			const localBySig =
				localById ??
				library.roasters.find(
					(r) =>
						r.visualizerId === null &&
						r.deletedAt == null &&
						signatureForRoaster({ name: r.name }) === wireSig
				);
			const localByName = localBySig ?? library.findRoasterByName(wire.name);
			if (localById) {
				// Remote wins on conflict. Pull the full mirrored field set
				// (name + website + the three roaster-CRUD extension fields)
				// rather than the prior partial patch so locally-edited
				// duplicates / logos / city snap to whatever the remote holds.
				library.updateRoaster(localById.id, {
					name: wire.name,
					website: wire.website ?? null,
					imageUrl: wire.image_url ?? null,
					canonicalRoasterId: wire.canonical_roaster_id ?? null,
					visualizerId: wire.id
				});
				remoteRoasterIdToLocal.set(wire.id, localById.id);
			} else if (localByName) {
				library.updateRoaster(localByName.id, { visualizerId: wire.id });
				remoteRoasterIdToLocal.set(wire.id, localByName.id);
				log.push({
					direction: 'pull',
					kind: 'roaster',
					id: localByName.id,
					name: wire.name,
					at: Date.now()
				});
			} else {
				const fresh = roasterFromWire(wire);
				library.upsertRoaster(fresh);
				remoteRoasterIdToLocal.set(wire.id, fresh.id);
				result.pulled += 1;
				log.push({
					direction: 'pull',
					kind: 'roaster',
					id: fresh.id,
					name: wire.name,
					at: Date.now()
				});
			}
		}

		// 2) Push every local roaster that has no visualizer id. Premium-gated.
		let premiumLocked = settings.premium === false;
		// Track whether we've already emitted the "downshift to read-only"
		// banner entry; we only want one regardless of how many writes 403.
		let premiumBannerLogged = settings.premium === false;
		const logPremiumBannerOnce = (): void => {
			if (premiumBannerLogged) return;
			premiumBannerLogged = true;
			log.push({
				direction: 'skip',
				kind: 'bean',
				id: '',
				name: 'Premium required',
				at: Date.now(),
				error:
					'Premium required — beans + roasters disabled from push. ' +
					'Upgrade at visualizer.coffee/premium.'
			});
		};
		for (const local of library.roasters) {
			if (local.visualizerId) continue;
			if (premiumLocked) {
				result.skipped += 1;
				log.push({
					direction: 'skip',
					kind: 'roaster',
					id: local.id,
					name: local.name,
					at: Date.now(),
					error: 'premium required'
				});
				continue;
			}
			try {
				const wire = (await call('/roasters', {
					method: 'POST',
					body: roasterBodyToWriteRequest(roasterToWire(local))
				})) as RoasterWire;
				if (wire?.id) {
					library.updateRoaster(local.id, { visualizerId: wire.id });
					remoteRoasterIdToLocal.set(wire.id, local.id);
				}
				result.pushed += 1;
				writeSyncSettings({ premium: true });
				log.push({
					direction: 'push',
					kind: 'roaster',
					id: local.id,
					name: local.name,
					at: Date.now()
				});
			} catch (e) {
				if (e instanceof VisualizerError && e.kind === 'premium') {
					premiumLocked = true;
					writeSyncSettings({ premium: false });
					logPremiumBannerOnce();
					log.push({
						direction: 'skip',
						kind: 'roaster',
						id: local.id,
						name: local.name,
						at: Date.now(),
						error: 'premium required'
					});
				} else {
					result.error = e instanceof Error ? e.message : String(e);
					log.push({
						direction: 'skip',
						kind: 'roaster',
						id: local.id,
						name: local.name,
						at: Date.now(),
						error: result.error
					});
				}
			}
		}

		// 3) Pull every remote bag. NOTE: `GET /coffee_bags` returns
		//    `CoffeeBagSummary[]` ({id, name}); a true round-trip would
		//    fetch `/coffee_bags/{id}` per row to read the full detail.
		//    Today we treat the summary as a thin BagWire (the spec's
		//    list endpoint doesn't carry the long form), and update calls
		//    overwrite with the full local payload anyway. Follow-up:
		//    paginate + per-row GET so pull-side mutations round-trip
		//    every field.
		const remoteBags: BagWire[] = [];
		page = 1;
		while (page < 50) {
			const body = (await call(
				`/coffee_bags?items=100&page=${page}`
			)) as CoffeeBagListResponse;
			const data = body?.data ?? [];
			remoteBags.push(...(data as BagWire[]));
			const totalPages = body?.paging?.pages ?? page;
			if (page >= totalPages || data.length === 0) break;
			page += 1;
		}
		// Map by Crema id (stored in metadata.crema.crema_id) → local row.
		// Falls back to signatureForBean when neither the
		// remote-id nor the crema_id round-trip yields a hit — same
		// rationale as the roaster pass above.
		const localBeans = library.beans;
		for (const wire of remoteBags) {
			const decoded = beanFromWire(wire, (rid) =>
				rid ? (remoteRoasterIdToLocal.get(rid) ?? null) : null
			);
			const decodedSig = signatureForBean({
				name: decoded.name,
				roasterName:
					(decoded.roasterId && library.getRoaster(decoded.roasterId)?.name) ?? null,
				roastedOn: decoded.roastedOn
			});
			const existing =
				localBeans.find(
					(b) =>
						(decoded.visualizerId && b.visualizerId === decoded.visualizerId) ||
						b.id === decoded.id
				) ??
				localBeans.find(
					(b) =>
						b.visualizerId === null &&
						b.deletedAt == null &&
						signatureForBean({
							name: b.name,
							roasterName: (b.roasterId && library.getRoaster(b.roasterId)?.name) ?? null,
							roastedOn: b.roastedOn
						}) === decodedSig
				);
			if (existing) {
				// Last-write-wins: remote wins per coordinator direction.
				library.replaceBean({ ...decoded, id: existing.id });
				log.push({
					direction: 'pull',
					kind: 'bean',
					id: existing.id,
					name: decoded.name,
					at: Date.now()
				});
			} else {
				library.upsertBean(decoded);
				result.pulled += 1;
				log.push({
					direction: 'pull',
					kind: 'bean',
					id: decoded.id,
					name: decoded.name,
					at: Date.now()
				});
			}
		}

		// 4) Push every local bag without a visualizerId (insert), and every
		// local bag whose updatedAt > lastSyncAt (update). Premium-gated.
		const lastSync = settings.lastSyncAt ?? 0;
		for (const local of library.beans) {
			if (premiumLocked) {
				if (!local.visualizerId) {
					result.skipped += 1;
					log.push({
						direction: 'skip',
						kind: 'bean',
						id: local.id,
						name: local.name,
						at: Date.now(),
						error: 'premium required'
					});
				}
				continue;
			}
			const remoteRoasterId = local.roasterId
				? library.getRoaster(local.roasterId)?.visualizerId ?? null
				: null;
			if (!local.visualizerId) {
				// Create.
				try {
					const wire = (await call('/coffee_bags', {
						method: 'POST',
						body: bagBodyToWriteRequest(beanToWire(local, remoteRoasterId))
					})) as BagWire;
					if (wire?.id) {
						library.updateBean(local.id, { visualizerId: wire.id });
					}
					result.pushed += 1;
					log.push({
						direction: 'push',
						kind: 'bean',
						id: local.id,
						name: local.name,
						at: Date.now()
					});
				} catch (e) {
					if (e instanceof VisualizerError && e.kind === 'premium') {
						premiumLocked = true;
						writeSyncSettings({ premium: false });
						logPremiumBannerOnce();
						log.push({
							direction: 'skip',
							kind: 'bean',
							id: local.id,
							name: local.name,
							at: Date.now(),
							error: 'premium required'
						});
					} else {
						log.push({
							direction: 'skip',
							kind: 'bean',
							id: local.id,
							name: local.name,
							at: Date.now(),
							error: e instanceof Error ? e.message : String(e)
						});
					}
				}
			} else if (local.updatedAt > lastSync) {
				// Update.
				try {
					await call(`/coffee_bags/${local.visualizerId}`, {
						method: 'PATCH',
						body: bagBodyToWriteRequest(beanToWire(local, remoteRoasterId))
					});
					result.pushed += 1;
					log.push({
						direction: 'push',
						kind: 'bean',
						id: local.id,
						name: local.name,
						at: Date.now()
					});
				} catch (e) {
					if (e instanceof VisualizerError && e.kind === 'premium') {
						premiumLocked = true;
						writeSyncSettings({ premium: false });
						logPremiumBannerOnce();
					}
					log.push({
						direction: 'skip',
						kind: 'bean',
						id: local.id,
						name: local.name,
						at: Date.now(),
						error: e instanceof Error ? e.message : String(e)
					});
				}
			}
		}

		// 5) Mark sync complete.
		writeSyncSettings({
			lastSyncAt: Date.now(),
			premium: premiumLocked ? false : (settings.premium ?? true)
		});
		result.ok = true;
		result.premiumLocked = premiumLocked;
		return result;
	} catch (e) {
		result.error = e instanceof Error ? e.message : String(e);
		return result;
	}
}

/**
 * Push a deletion for a single bean. Called from the library store after the
 * user deletes a bag locally so the remote stays in sync. Best-effort — a
 * failed deletion is logged but doesn't crash the local delete.
 */
export async function deleteRemoteBean(
	bean: Bean
): Promise<{ ok: boolean; error?: string }> {
	if (!bean.visualizerId) return { ok: true };
	if (!isConnected()) return { ok: true };
	const settings = readSyncSettings();
	if (settings.premium === false) return { ok: true }; // free tier — skip
	try {
		await call(`/coffee_bags/${bean.visualizerId}`, {
			method: 'DELETE'
		});
		return { ok: true };
	} catch (e) {
		return { ok: false, error: e instanceof Error ? e.message : String(e) };
	}
}

/** Push a roaster deletion. Same best-effort policy as {@link deleteRemoteBean}. */
export async function deleteRemoteRoaster(
	roaster: Roaster
): Promise<{ ok: boolean; error?: string }> {
	if (!roaster.visualizerId) return { ok: true };
	if (!isConnected()) return { ok: true };
	const settings = readSyncSettings();
	if (settings.premium === false) return { ok: true };
	try {
		await call(`/roasters/${roaster.visualizerId}`, {
			method: 'DELETE'
		});
		return { ok: true };
	} catch (e) {
		return { ok: false, error: e instanceof Error ? e.message : String(e) };
	}
}
