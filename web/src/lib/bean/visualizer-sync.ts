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
 * **As of the token-store retirement this module is PURE** — the live
 * bidirectional sync + the authed HTTP (`call` / `runSync` / `testConnection` /
 * the premium probe) moved to the `BeanSync` Effect service
 * (`$lib/services/bean-sync`), which funnels through `TokenVault.withFreshToken`
 * + `HttpClient`. What remains here is the wire-shape contract the service
 * reuses: the `*ToWire` / `*FromWire` converters, the `*WriteRequest` envelope
 * builders, the persisted `VisualizerSyncSettings` (lastSyncAt + cached premium
 * flag), `clearVisualizerPremiumCache`, and the legacy `VisualizerError` shape.
 *
 * Premium gating: bag / roaster *write* endpoints require Visualizer premium;
 * `BeanSync` detects the 402/403, caches the flag in localStorage (read/written
 * here), and the UI surfaces "read-only (free tier)".
 */

import { readJson, writeJson } from '$lib/utils/storage';
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

export function roasterBodyToWriteRequest(body: RoasterWire): RoasterWriteRequest {
	return {
		roaster: {
			name: body.name,
			website: body.website ?? null,
			canonical_roaster_id: body.canonical_roaster_id ?? null
		}
	};
}

export function bagBodyToWriteRequest(body: BagWire): CoffeeBagWriteRequest {
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

/**
 * Legacy error shape from the old `fetch`-based sync (the bean/roaster HTTP now
 * lives in the `BeanSync` service with `Data.TaggedError`s). Kept only because
 * it's still re-exported through `$lib/bean` for any external `instanceof`
 * checks during the migration.
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
 * Clear the cached premium-tier flag from both sync-config locations.
 * Called on disconnect so that reconnecting with a different account
 * (or after a tier change) doesn't inherit a stale `false` — the next
 * Test or Sync will re-probe from scratch.
 */
export function clearVisualizerPremiumCache(): void {
	writeSyncSettings({ premium: null });
	updateSyncConfig({ premium: null });
}
