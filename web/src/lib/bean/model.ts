/**
 * `$lib/bean/model` — the bean library types.
 *
 * One bag of coffee is a {@link Bean}; the roastery that produced it is a
 * {@link Roaster}; and a snapshot frozen onto each completed shot is a
 * {@link ShotBean}. All three mirror the canonical Rust types in
 * `de1_domain::bean` (docs/28 §data-model-proposal) so the Android shell can
 * consume identical shapes on day one via `#[typeshare]`.
 *
 * The shell owns persistence — the core is sans-IO — so this file defines the
 * shape, the migration from the legacy single-bean store and the pure helpers
 * (freshness band, days off roast) that ride on the wasm bridge. CRUD lives
 * on {@link BeanLibraryStore} in `./store.svelte`.
 */

import type { Roast } from '$lib/profiles';
import {
	roast_band as wasmRoastBand,
	days_off_roast as wasmDaysOffRoast,
	roast_freshness as wasmRoastFreshness
} from '$lib/wasm/de1_wasm';

// ── Wire types (mirrored from de1_domain::bean) ─────────────────────────

/** Single-origin vs blend. Lowercase wire string — matches Rust `BeanMix`. */
export type BeanMix = 'single' | 'blend';

/**
 * Origin metadata for a bag — country, region, farm and the rest of the
 * upstream provenance. All optional and free-form because real coffee bag
 * labels are inconsistent. Mirrors `de1_domain::BeanOrigin`.
 */
export interface BeanOrigin {
	country: string | null;
	region: string | null;
	farm: string | null;
	farmer: string | null;
	variety: string | null;
	elevation: string | null;
	processing: string | null;
	harvestTime: string | null;
}

/** A blank origin — every field `null`. */
export function blankOrigin(): BeanOrigin {
	return {
		country: null,
		region: null,
		farm: null,
		farmer: null,
		variety: null,
		elevation: null,
		processing: null,
		harvestTime: null
	};
}

/**
 * One bag of coffee — a row in the bean library. Mirrors `de1_domain::Bean`.
 *
 * The id is a stable `bean:<uuid>`. `createdAt` / `updatedAt` are Unix epoch
 * milliseconds; the store bumps `updatedAt` on every patch. `metadata` is the
 * open JSON escape valve — Beanconqueror-only fields and Visualizer-only keys
 * ride here, with the agreement that Visualizer ignores unknown properties so
 * round-trips are lossless (per Visualizer's `additionalProperties: true` on
 * `CoffeeBagDetail.metadata`).
 */
export interface Bean {
	id: string;
	name: string;
	roasterId: string | null;
	roastedOn: string | null;
	openedOn: string | null;
	frozenOn: string | null;
	defrostedOn: string | null;
	/** 1..10 (Visualizer canonical scale). */
	roastLevel: number | null;
	mix: BeanMix | null;
	decaf: boolean;
	origin: BeanOrigin;
	bagSizeG: number;
	remainingG: number;
	qualityScore: string;
	tastingNotes: string;
	/** Star rating 0..5; 0 = unrated. */
	rating: number;
	placeOfPurchase: string | null;
	url: string | null;
	notes: string;
	favourite: boolean;
	/** Unix epoch ms; `null` = active. */
	archivedAt: number | null;
	/**
	 * Unix epoch ms when this bag was soft-deleted, or `null` when
	 * active. Required for cross-device sync tombstone propagation
	 * (docs/36 §3): the next sync push DELETEs the remote row, then
	 * garbage-collects the local tombstone. The library UI filters
	 * tombstones out everywhere except the sync layer.
	 */
	deletedAt: number | null;
	grinder: string;
	grinderSetting: string;
	/**
	 * Free-form user tags — `["daily-driver", "comp"]`. Defaults to `[]`.
	 * Mirrors the profile tag pattern (`$lib/profiles`'s `CremaProfile.tags`)
	 * so the filter / chip UI behaves consistently across the two libraries.
	 */
	tags: string[];
	visualizerId: string | null;
	beanconquerorId: string | null;
	imageRef: string | null;
	/**
	 * Open JSON for fields Crema/Visualizer don't model first-class. Use
	 * sparingly — the type-safe fields above are preferred.
	 */
	metadata: Record<string, unknown>;
	createdAt: number;
	updatedAt: number;
}

/** Build a brand-new bag with a freshly minted id and timestamp. */
export function blankBean(id?: string): Bean {
	const now = Date.now();
	return {
		id: id ?? mintBeanId(),
		name: '',
		roasterId: null,
		roastedOn: null,
		openedOn: null,
		frozenOn: null,
		defrostedOn: null,
		roastLevel: null,
		mix: null,
		decaf: false,
		origin: blankOrigin(),
		bagSizeG: 0,
		remainingG: 0,
		qualityScore: '',
		tastingNotes: '',
		rating: 0,
		placeOfPurchase: null,
		url: null,
		notes: '',
		favourite: false,
		archivedAt: null,
		deletedAt: null,
		grinder: '',
		grinderSetting: '',
		tags: [],
		visualizerId: null,
		beanconquerorId: null,
		imageRef: null,
		metadata: {},
		createdAt: now,
		updatedAt: now
	};
}

/**
 * One roastery — a record in the roaster directory. Sparse on purpose:
 * Visualizer's `RoasterDetail` is itself minimal, and Beanconqueror has no
 * first-class roaster, so promotion-from-string at import time keeps things
 * honest. Mirrors `de1_domain::Roaster`.
 */
export interface Roaster {
	id: string;
	name: string;
	website: string | null;
	/**
	 * Logo / hero image URL. Mirrors Visualizer `RoasterDetail.image_url` —
	 * round-trips losslessly on sync. Renders as a small thumbnail in the
	 * roaster card and the editor's preview slot. `null` falls back to the
	 * deterministic two-letter mark.
	 */
	imageUrl: string | null;
	/**
	 * City — e.g. `"Portland"`. Crema-only; rides in `metadata.crema.city`
	 * on Visualizer round-trip so the wire stays lossless.
	 */
	city: string | null;
	country: string | null;
	notes: string;
	/**
	 * Pointer to the canonical roaster id when this row was tagged as a
	 * duplicate. `null` = this row is itself canonical (or has not been
	 * deduped). Mirrors Visualizer `RoasterDetail.canonical_roaster_id`.
	 * The Roasters tab filters duplicates out by default (showing only
	 * canonicals); merge-duplicates wires this field on commit.
	 */
	canonicalRoasterId: string | null;
	visualizerId: string | null;
	/**
	 * Unix epoch ms when this roaster was soft-deleted, or `null` when
	 * active. Mirrors {@link Bean.deletedAt} (docs/36 §3): the next sync
	 * push DELETEs the remote row, then GC's the local tombstone.
	 */
	deletedAt: number | null;
	metadata: Record<string, unknown>;
	createdAt: number;
	updatedAt: number;
}

/** Build a brand-new roaster row. */
export function blankRoaster(name: string, id?: string): Roaster {
	const now = Date.now();
	return {
		id: id ?? mintRoasterId(),
		name,
		website: null,
		imageUrl: null,
		city: null,
		country: null,
		notes: '',
		canonicalRoasterId: null,
		visualizerId: null,
		deletedAt: null,
		metadata: {},
		createdAt: now,
		updatedAt: now
	};
}

// ── Id minting ─────────────────────────────────────────────────────────

/** Crypto-random id when available; weak fallback for the bookkeeping case. */
function mintId(prefix: string): string {
	const rnd =
		typeof crypto !== 'undefined' && 'randomUUID' in crypto
			? crypto.randomUUID()
			: Math.random().toString(36).slice(2) + Date.now().toString(36);
	return `${prefix}:${rnd}`;
}
export function mintBeanId(): string {
	return mintId('bean');
}
export function mintRoasterId(): string {
	return mintId('roaster');
}

// ── Pure helpers — delegate to the wasm core ───────────────────────────

/**
 * Classify a 1..10 roast level into a named band: `1–3 → 'light'`,
 * `4–6 → 'medium'`, `7–10 → 'dark'`. Returns `null` for a `null` level.
 * Delegates to `de1_domain::roast_band` via the wasm bridge.
 */
export function roastBand(level: number | null): Roast | null {
	if (level == null) return null;
	const rounded = Math.round(level);
	if (!Number.isFinite(rounded)) return null;
	return (wasmRoastBand(rounded) ?? null) as Roast | null;
}

/** The roast level a quick-set pill maps to: light→1, medium→5, dark→10. */
export const ROAST_PILL_LEVEL: Readonly<Record<Roast, number>> = {
	light: 1,
	medium: 5,
	dark: 10
};

/**
 * Finer-grained 5-band classification for the RoastSlider display label —
 * the design's slider shows five band labels (Light / Med-light / Medium /
 * Med-dark / Dark) under the 1..10 track. Returns `'—'` when the level is
 * unset.
 *
 * Mapping (1..10 → label):
 *
 * - `1..=2`  → `'Light'`
 * - `3..=4`  → `'Med-light'`
 * - `5`      → `'Medium'`
 * - `6..=7`  → `'Med-dark'`
 * - `8..=10` → `'Dark'`
 *
 * The 3-band {@link roastBand} stays canonical and rides on every
 * `RoastBand` comparison in the freshness math — this is purely a
 * UI-display helper for the slider.
 */
export function roastBand5(level: number | null): string {
	if (level == null) return '—';
	const n = Math.round(level);
	if (n <= 2) return 'Light';
	if (n <= 4) return 'Med-light';
	if (n === 5) return 'Medium';
	if (n <= 7) return 'Med-dark';
	return 'Dark';
}

/**
 * Bag-state classifier for the library tile / drawer: which section a bag
 * belongs in (`'active' | 'frozen' | 'archived'`) and the dot colour token
 * for the status pip. The freshness window is *not* this — bag state is
 * about user lifecycle (open / sealed / frozen / done), not roast age.
 */
export type BagState = 'archived' | 'frozen' | 'active';

/** Bag-state label for grouping in the bags grid. */
export function bagState(bean: Bean): BagState {
	if (bean.archivedAt != null) return 'archived';
	if (bean.frozenOn && !bean.defrostedOn) return 'frozen';
	return 'active';
}

/**
 * Whole days between a `yyyy-mm-dd` roast date and `asOf` (default: now).
 * Returns `null` when no roast date is set; clamped at `0` so a future-dated
 * roast never reads negative.
 */
export function daysOffRoast(
	roastedOn: string | null,
	asOf: number | Date = Date.now()
): number | null {
	if (!roastedOn) return null;
	const nowMs = asOf instanceof Date ? asOf.getTime() : asOf;
	const days = wasmDaysOffRoast(roastedOn, nowMs);
	return days == null ? null : days;
}

/** A bean's rest verdict against the ideal window for its roast band. */
export type Freshness = 'best' | 'ok' | 'bad';

/** Rate days-off-roast against the band's ideal window. */
export function roastFreshness(
	band: Roast | null,
	days: number | null
): Freshness | null {
	if (band == null || days == null) return null;
	return (wasmRoastFreshness(band, days) ?? null) as Freshness | null;
}

/**
 * One-line label summarising a bag — `"Yirgacheffe · Counter Culture · 14d off
 * roast"`. Pieces that are unset are omitted; the dot-separator collapses.
 * Mirrors `de1_domain::Bean::display_summary`.
 */
export function beanDisplaySummary(
	bean: Bean,
	roaster: Roaster | null,
	asOf: number | Date = Date.now()
): string {
	const parts: string[] = [];
	const name = bean.name.trim();
	const country = bean.origin.country?.trim() ?? '';
	if (name) parts.push(name);
	else if (country) parts.push(country);
	const roasterName = roaster?.name.trim();
	if (roasterName) parts.push(roasterName);
	const days = daysOffRoast(bean.roastedOn, asOf);
	if (days != null) parts.push(`${days}d off roast`);
	return parts.join(' · ');
}

// ── Legacy migration ───────────────────────────────────────────────────

/**
 * The pre-library single-bean shape — `{ roaster, type, roastedOn, roastLevel,
 * grinder }` stored under `crema.bean.current.v1`. Kept here so the library
 * store can migrate it forward on first load.
 */
export interface LegacyCurrentBean {
	roaster: string;
	type: string;
	roastedOn: string | null;
	roastLevel: number | null;
	grinder: string;
}

/**
 * Migrate the old single-bean record into a fresh library {@link Bean} plus
 * (optionally) a {@link Roaster} synthesised from the legacy roaster string.
 * Returns `null` when the legacy record is empty (nothing to migrate). The
 * legacy `type` becomes the new `name`; the legacy `roaster` is promoted to
 * a Roaster row when non-empty.
 */
export function migrateLegacyCurrentBean(raw: unknown): {
	bean: Bean;
	roaster: Roaster | null;
} | null {
	if (typeof raw !== 'object' || raw === null) return null;
	const obj = raw as Record<string, unknown>;
	// Accept either the v1 shape (roaster + type) or the earliest shape (name).
	const roasterStr =
		typeof obj.roaster === 'string' ? obj.roaster.trim() : '';
	const typeStr =
		typeof obj.type === 'string'
			? obj.type.trim()
			: typeof obj.name === 'string'
				? obj.name.trim()
				: '';
	const grinderStr = typeof obj.grinder === 'string' ? obj.grinder.trim() : '';
	const roastedOn = typeof obj.roastedOn === 'string' ? obj.roastedOn : null;
	// roastLevel can be a number or a legacy band word.
	let roastLevel: number | null = null;
	const rawLevel = obj.roastLevel;
	if (typeof rawLevel === 'number' && Number.isFinite(rawLevel)) {
		roastLevel = Math.max(1, Math.min(10, Math.round(rawLevel)));
	} else if (rawLevel === 'light' || rawLevel === 'medium' || rawLevel === 'dark') {
		roastLevel = ROAST_PILL_LEVEL[rawLevel];
	}

	// Empty → nothing to migrate.
	if (!roasterStr && !typeStr && !roastedOn && roastLevel == null && !grinderStr) {
		return null;
	}

	const roaster = roasterStr ? blankRoaster(roasterStr) : null;
	const bean = blankBean();
	bean.name = typeStr || roasterStr || 'Imported bean';
	bean.roasterId = roaster?.id ?? null;
	bean.roastedOn = roastedOn;
	bean.roastLevel = roastLevel;
	bean.grinder = grinderStr;
	bean.favourite = true; // The legacy active bean → pinned to the brew strip.
	return { bean, roaster };
}

// ── Defensive deserialiser ─────────────────────────────────────────────

/**
 * Coerce a stored `unknown` into a valid {@link Bean}, filling missing fields
 * from {@link blankBean}. Never throws — used on load so a stale shape cannot
 * crash the app.
 */
export function coerceBean(raw: unknown): Bean | null {
	if (typeof raw !== 'object' || raw === null) return null;
	const obj = raw as Record<string, unknown>;
	if (typeof obj.id !== 'string' || typeof obj.name !== 'string') return null;
	const base = blankBean(obj.id);
	base.name = obj.name;
	if (typeof obj.roasterId === 'string') base.roasterId = obj.roasterId;
	if (typeof obj.roastedOn === 'string') base.roastedOn = obj.roastedOn;
	if (typeof obj.openedOn === 'string') base.openedOn = obj.openedOn;
	if (typeof obj.frozenOn === 'string') base.frozenOn = obj.frozenOn;
	if (typeof obj.defrostedOn === 'string') base.defrostedOn = obj.defrostedOn;
	if (typeof obj.roastLevel === 'number') base.roastLevel = obj.roastLevel;
	if (obj.mix === 'single' || obj.mix === 'blend') base.mix = obj.mix;
	if (typeof obj.decaf === 'boolean') base.decaf = obj.decaf;
	if (typeof obj.origin === 'object' && obj.origin !== null) {
		const o = obj.origin as Record<string, unknown>;
		const origin = blankOrigin();
		const keys: (keyof BeanOrigin)[] = [
			'country',
			'region',
			'farm',
			'farmer',
			'variety',
			'elevation',
			'processing',
			'harvestTime'
		];
		for (const k of keys) {
			const v = o[k];
			if (typeof v === 'string') origin[k] = v;
		}
		base.origin = origin;
	}
	if (typeof obj.bagSizeG === 'number') base.bagSizeG = obj.bagSizeG;
	if (typeof obj.remainingG === 'number') base.remainingG = obj.remainingG;
	if (typeof obj.qualityScore === 'string') base.qualityScore = obj.qualityScore;
	if (typeof obj.tastingNotes === 'string') base.tastingNotes = obj.tastingNotes;
	if (typeof obj.rating === 'number') base.rating = obj.rating;
	if (typeof obj.placeOfPurchase === 'string') base.placeOfPurchase = obj.placeOfPurchase;
	if (typeof obj.url === 'string') base.url = obj.url;
	if (typeof obj.notes === 'string') base.notes = obj.notes;
	if (typeof obj.favourite === 'boolean') base.favourite = obj.favourite;
	if (typeof obj.archivedAt === 'number') base.archivedAt = obj.archivedAt;
	if (typeof obj.deletedAt === 'number') base.deletedAt = obj.deletedAt;
	if (typeof obj.grinder === 'string') base.grinder = obj.grinder;
	if (typeof obj.grinderSetting === 'string') base.grinderSetting = obj.grinderSetting;
	if (Array.isArray(obj.tags)) {
		base.tags = obj.tags.filter((t): t is string => typeof t === 'string');
	}
	if (typeof obj.visualizerId === 'string') base.visualizerId = obj.visualizerId;
	if (typeof obj.beanconquerorId === 'string') base.beanconquerorId = obj.beanconquerorId;
	if (typeof obj.imageRef === 'string') base.imageRef = obj.imageRef;
	if (typeof obj.metadata === 'object' && obj.metadata !== null) {
		base.metadata = obj.metadata as Record<string, unknown>;
	}
	if (typeof obj.createdAt === 'number') base.createdAt = obj.createdAt;
	if (typeof obj.updatedAt === 'number') base.updatedAt = obj.updatedAt;
	return base;
}

/** Coerce a stored `unknown` into a valid {@link Roaster}; `null` on garbage. */
export function coerceRoaster(raw: unknown): Roaster | null {
	if (typeof raw !== 'object' || raw === null) return null;
	const obj = raw as Record<string, unknown>;
	if (typeof obj.id !== 'string' || typeof obj.name !== 'string') return null;
	const base = blankRoaster(obj.name, obj.id);
	if (typeof obj.website === 'string') base.website = obj.website;
	if (typeof obj.imageUrl === 'string') base.imageUrl = obj.imageUrl;
	if (typeof obj.city === 'string') base.city = obj.city;
	if (typeof obj.country === 'string') base.country = obj.country;
	if (typeof obj.notes === 'string') base.notes = obj.notes;
	if (typeof obj.canonicalRoasterId === 'string')
		base.canonicalRoasterId = obj.canonicalRoasterId;
	if (typeof obj.visualizerId === 'string') base.visualizerId = obj.visualizerId;
	if (typeof obj.deletedAt === 'number') base.deletedAt = obj.deletedAt;
	if (typeof obj.metadata === 'object' && obj.metadata !== null) {
		base.metadata = obj.metadata as Record<string, unknown>;
	}
	if (typeof obj.createdAt === 'number') base.createdAt = obj.createdAt;
	if (typeof obj.updatedAt === 'number') base.updatedAt = obj.updatedAt;
	return base;
}
