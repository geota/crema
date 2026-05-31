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
	beanFromWire as wasmBeanFromWire,
	beanToWire as wasmBeanToWire,
	roastLevelFromWire as wasmRoastLevelFromWire,
	roastLevelToWire as wasmRoastLevelToWire,
	roasterFromWire as wasmRoasterFromWire,
	roasterToWire as wasmRoasterToWire
} from '$lib/wasm/de1_wasm';
import { mintBeanId, mintRoasterId, type Bean, type Roaster } from './model';

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

// ── Mapping helpers (delegated to the Rust core via wasm — CORE1) ────────
//
// `beanToWire` / `beanFromWire` / `roasterToWire` / `roasterFromWire` /
// `roastLevelToWire` / `roastLevelFromWire` (and the former `mixFromMetadata`)
// moved to `de1_domain::visualizer_wire` so every shell shares one
// byte-identical mapping. These TS wrappers preserve the historical API
// surface (the `roasterIdLookup` closure, the `BagWire` / `RoasterWire`
// shapes) so existing callers + the round-trip vitest don't change — only
// the bodies marshal JSON across the wasm boundary now. The pinned
// round-trip lives in `visualizer-sync.vitest.ts` (it now exercises wasm).

/**
 * Crema's 1..10 roast scale → Visualizer's free-text `roast_level`. Lossy by
 * design (10 levels → 5 bands) but idempotent after the first hop; the band
 * midpoints are pinned in `de1_domain::visualizer_wire::roast_level_*`.
 * Exported so the shot uploader can encode the inline bean snapshot's
 * `roastLevel` with the same banding the bean library uses.
 */
export function roastLevelToWire(level: number | null): string | null {
	return wasmRoastLevelToWire(level ?? undefined) ?? null;
}
/** Visualizer's free-text `roast_level` → Crema's 1..10 scale. See {@link roastLevelToWire}. */
export function roastLevelFromWire(label: string | null | undefined): number | null {
	return wasmRoastLevelFromWire(label ?? undefined) ?? null;
}

/** Encode a Crema bean → Visualizer's POST/PATCH wire body. */
export function beanToWire(bean: Bean, roasterRemoteId: string | null): BagWire {
	return JSON.parse(wasmBeanToWire(JSON.stringify(bean), roasterRemoteId ?? undefined)) as BagWire;
}

/**
 * Decode a Visualizer bag → Crema bean. Reuses the local roaster id when the
 * caller's `roasterIdLookup` resolves the remote `roaster_id`. The Crema-only
 * fields (favourite, bag size, grinder, mix, …) ride out of the wire body's
 * `metadata.crema` block so a round-trip is lossless. The lookup stays a TS
 * closure (it reads the shell's roaster store); the resolved local id + a
 * freshly-minted fallback id + `Date.now()` cross the boundary into wasm.
 */
export function beanFromWire(
	wire: BagWire,
	roasterIdLookup: (remoteId: string | null | undefined) => string | null
): Bean {
	const localRoasterId = roasterIdLookup(wire.roaster_id);
	const raw = wasmBeanFromWire(
		JSON.stringify(wire),
		localRoasterId ?? undefined,
		mintBeanId(),
		Date.now()
	);
	return JSON.parse(raw) as Bean;
}

export function roasterToWire(roaster: Roaster): RoasterWire {
	// The Crema-only `city` field stays local (Visualizer doesn't model it);
	// the Rust `roaster_to_wire` only emits the Visualizer-modelled fields.
	return JSON.parse(wasmRoasterToWire(JSON.stringify(roaster))) as RoasterWire;
}

export function roasterFromWire(wire: RoasterWire): Roaster {
	return JSON.parse(
		wasmRoasterFromWire(JSON.stringify(wire), mintRoasterId(), Date.now())
	) as Roaster;
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
