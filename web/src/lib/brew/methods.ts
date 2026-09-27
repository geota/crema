/**
 * `$lib/brew/methods` — the Brew Log method vocabulary (issue #10).
 *
 * Storage accepts ANY normalized method string (the core's
 * `normalize_brew_method` rule); this module owns what the *UI* makes
 * of one — display labels, the last-used-method memory, and the bridge
 * to the core's preset table (`brew_method_presets`) and log-form
 * seeding rule (`brew_log_seeds`). Tea is deliberately absent — a BC tea
 * brew still imports, carrying its name as a free-text method.
 */

import type {
	BrewLogPrefill,
	BrewLogSeeds,
	BrewMethodPreset as CoreBrewMethodPreset,
	BrewSeedInput,
	BrewSeries
} from '$lib/core/crema-core';
import {
	brewLogSeedsJson as wasmBrewLogSeeds,
	brewMethodPresetsWithCustom as wasmBrewMethodPresetsWithCustom,
	normalizeBrewMethod as wasmNormalize
} from '$lib/wasm/de1_wasm';
import { getCustomMethodStore, isCustomMethodId } from './custom-methods.svelte';
import { PRESET_METHOD_LABELS } from './method-labels';

/**
 * Values a caller seeds the Log-brew form with — "Log again" passes a
 * whole prior brew, the bean drawer passes its bean, a finished guided
 * session passes the measured summary. Everything optional; the form
 * falls back to last-used / preset seeds per field.
 */
export interface LogBrewPrefill {
	method?: string;
	beanId?: string | null;
	dose?: number | null;
	waterG?: number | null;
	yieldOut?: number | null;
	grinderSetting?: string | null;
	tempC?: number | null;
	durationMs?: number | null;
	notes?: string | null;
	rating?: number | null;
	completedAt?: number;
	/** Guided sessions only. */
	recipeName?: string | null;
	brewSeries?: BrewSeries | null;
}

/**
 * One method-picker entry — a chip in the log form. The id and the seed
 * numbers come from the core (`de1_domain::brew_method_presets_with_custom`,
 * the one source of truth for both shells); a curated preset's display
 * label is the UI's, keyed by id, a custom method's is the user's. "Other"
 * is not a preset — it is the free-text chip.
 */
export interface BrewMethodPreset extends CoreBrewMethodPreset {
	/** Display label ("French press", "ORB"). */
	readonly label: string;
	/** Whether this is one of the user's own methods. */
	readonly custom: boolean;
}

/**
 * Every pickable method, in the core's order: the curated presets, then
 * the user's live custom methods. Reads the custom-method store, so a
 * template calling it re-renders when a method is added or renamed.
 */
export function brewMethodPresets(): readonly BrewMethodPreset[] {
	const custom = getCustomMethodStore().live;
	const core = JSON.parse(
		wasmBrewMethodPresetsWithCustom(JSON.stringify(custom))
	) as CoreBrewMethodPreset[];
	return core.map((p) => ({
		...p,
		label: p.label ?? PRESET_METHOD_LABELS[p.id] ?? p.id,
		custom: p.label != null
	}));
}

/** The free-text chip's id — typed names are normalized and stored as-is. */
export const OTHER_METHOD = 'other';

/** How many preset chips render inline before the "More ▾" overflow. */
export const INLINE_PRESET_COUNT = 6;

/**
 * Display label for any stored method string:
 *
 * 1. a curated preset's label;
 * 2. a custom method's current label — live or deleted, so a rename
 *    follows every past row and a deleted method's rows keep their name;
 * 3. the row's snapshotted `brewMethodLabel` (the method's list was lost,
 *    e.g. a restore elsewhere without it);
 * 4. the free text re-humanized ("karlsbad_kanne" → "Karlsbad kanne").
 *
 * `null`/empty (machine espresso) → "Espresso". Android's `methodLabel`
 * applies the same order.
 */
export function methodLabel(
	method: string | null | undefined,
	snapshotLabel?: string | null
): string {
	const m = method?.trim().toLowerCase();
	if (!m) return 'Espresso';
	const label = PRESET_METHOD_LABELS[m];
	if (label) return label;
	if (isCustomMethodId(m)) {
		const own = getCustomMethodStore().get(m)?.label;
		if (own) return own;
		if (snapshotLabel?.trim()) return snapshotLabel.trim();
		return 'Custom method';
	}
	if (snapshotLabel?.trim()) return snapshotLabel.trim();
	const words = m.replace(/_/g, ' ').trim();
	return words.charAt(0).toUpperCase() + words.slice(1);
}

/** A stored row's method label — {@link methodLabel} with its snapshot. */
export function shotMethodLabel(shot: {
	brewMethod?: string | null;
	brewMethodLabel?: string | null;
}): string {
	return methodLabel(shot.brewMethod, shot.brewMethodLabel);
}

/**
 * The label to snapshot onto a brew saved with `method` — the custom
 * method's current name, `null` for presets and free text (their label
 * derives from the id).
 */
export function methodLabelSnapshot(method: string | null | undefined): string | null {
	return isCustomMethodId(method) ? (getCustomMethodStore().get(method)?.label ?? null) : null;
}

/** The espresso-family rule — mirrors `de1_domain::is_espresso_method`. */
export function isEspressoMethod(method: string | null | undefined): boolean {
	const m = method?.trim().toLowerCase();
	return !m || m === 'espresso';
}

/**
 * Canonicalize a user-typed method for storage. Routes through the core
 * (`normalize_brew_method`) when the wasm bundle is up, with a
 * behaviour-matching JS fallback for boot/tests.
 */
export function normalizeMethod(raw: string): string | null {
	try {
		return wasmNormalize(raw) ?? null;
	} catch {
		const t = raw.trim().toLowerCase().replace(/[\s_-]+/g, '_').replace(/^_+|_+$/g, '');
		return t.length > 0 ? t : null;
	}
}

const LAST_METHOD_KEY = 'crema.brewlog.lastMethod.v1';

/**
 * The method of the last saved log, or `null` when none was remembered —
 * the core's seeding rule then opens on pourover.
 */
export function lastUsedMethod(): string | null {
	try {
		const stored = localStorage.getItem(LAST_METHOD_KEY);
		// A deleted custom method no longer opens the form.
		if (isCustomMethodId(stored) && !getCustomMethodStore().isLive(stored)) return null;
		if (stored && stored.trim()) return stored;
	} catch {
		// Storage unavailable (SSR/private mode) — fall through.
	}
	return null;
}

/**
 * The method a fresh surface (the Scale page's Brew setup) opens on — the
 * core's seeding rule with nothing prefilled: last-used, else pourover.
 */
export function openingMethod(): string {
	return brewLogSeeds({
		method: null,
		beanId: null,
		beanGrinderSetting: null,
		prefill: undefined,
		rows: []
	}).method;
}

/** Remember the method of a just-saved log. */
export function rememberMethod(method: string): void {
	try {
		localStorage.setItem(LAST_METHOD_KEY, method);
	} catch {
		// Best-effort.
	}
}

/**
 * Where a finished guided session's weight lands in the log prefill. The
 * scale weighs what's in the vessel: beverage-out for the espresso family
 * (so it's the **yield** — the core seeding reads espresso's yield slot,
 * never its water), water-in for filter methods. The scale's final reading
 * wins over the recipe's planned total; a non-positive plan is no value.
 */
export function guidedPrefillWeights(
	method: string | null | undefined,
	finalWeightG: number | null,
	plannedWaterG: number
): Pick<LogBrewPrefill, 'waterG' | 'yieldOut'> {
	const weight = finalWeightG ?? (plannedWaterG > 0 ? plannedWaterG : null);
	return isEspressoMethod(method)
		? { waterG: null, yieldOut: weight }
		: { waterG: weight, yieldOut: null };
}

/** A stored row, projected for {@link brewLogSeeds}. */
export type { BrewSeedInput, BrewLogSeeds };

/**
 * Seed the log form's numeric fields for `method` (`null` = the opening
 * method: prefill, then last-used, then pourover) — the core's
 * `brew_log_seeds` rule. `rows` newest first.
 */
export function brewLogSeeds(input: {
	method: string | null;
	beanId: string | null;
	beanGrinderSetting: string | null;
	prefill: LogBrewPrefill | undefined;
	rows: BrewSeedInput[];
}): BrewLogSeeds {
	const p = input.prefill;
	const prefill: BrewLogPrefill | undefined = p
		? {
				method: p.method ?? undefined,
				doseG: p.dose ?? undefined,
				waterG: p.waterG ?? undefined,
				yieldG: p.yieldOut ?? undefined,
				grinderSetting: p.grinderSetting ?? undefined,
				tempC: p.tempC ?? undefined,
				durationMs: p.durationMs ?? undefined
			}
		: undefined;
	return JSON.parse(
		wasmBrewLogSeeds(
			JSON.stringify({
				method: input.method ?? undefined,
				lastUsedMethod: lastUsedMethod() ?? undefined,
				beanId: input.beanId ?? undefined,
				beanGrinderSetting: input.beanGrinderSetting ?? undefined,
				prefill,
				rows: input.rows,
				// Tombstones included: an old row's custom method still seeds.
				customMethods: getCustomMethodStore().everything
			})
		)
	) as BrewLogSeeds;
}
