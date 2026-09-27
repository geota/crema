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
	brewMethodPresets as wasmBrewMethodPresets,
	normalizeBrewMethod as wasmNormalize
} from '$lib/wasm/de1_wasm';

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
 * One curated method preset — a chip in the log form. The id and the seed
 * numbers come from the core (`de1_domain::brew_method_presets`, the one
 * source of truth for both shells); the display label is the UI's, keyed
 * by id. "Other" is not a preset — it is the free-text chip.
 */
export interface BrewMethodPreset extends CoreBrewMethodPreset {
	/** Display label ("French press"). */
	readonly label: string;
}

/** Display labels, keyed by the core preset id. */
const METHOD_LABELS: Readonly<Record<string, string>> = {
	espresso: 'Espresso',
	pourover: 'V60 / pourover',
	aeropress: 'AeroPress',
	french_press: 'French press',
	moka: 'Moka',
	cold_brew: 'Cold brew',
	drip: 'Drip machine',
	siphon: 'Siphon',
	clever: 'Clever / Switch'
};

let presetCache: readonly BrewMethodPreset[] | null = null;

/**
 * The chip row, in the core's display order, each preset carrying its
 * label. Parsed once from the core (`brewMethodPresets`) — call after the
 * wasm bundle is up (the log form only opens once it is).
 */
export function brewMethodPresets(): readonly BrewMethodPreset[] {
	if (presetCache) return presetCache;
	const core = JSON.parse(wasmBrewMethodPresets()) as CoreBrewMethodPreset[];
	presetCache = core.map((p) => ({ ...p, label: METHOD_LABELS[p.id] ?? p.id }));
	return presetCache;
}

/** The free-text chip's id — typed names are normalized and stored as-is. */
export const OTHER_METHOD = 'other';

/** How many preset chips render inline before the "More ▾" overflow. */
export const INLINE_PRESET_COUNT = 6;

/**
 * Display label for any stored method string: the preset label when
 * curated, else the free text re-humanized ("karlsbad_kanne" →
 * "Karlsbad kanne"). `null`/empty (machine espresso) → "Espresso".
 */
export function methodLabel(method: string | null | undefined): string {
	const m = method?.trim().toLowerCase();
	if (!m) return 'Espresso';
	const label = METHOD_LABELS[m];
	if (label) return label;
	const words = m.replace(/_/g, ' ').trim();
	return words.charAt(0).toUpperCase() + words.slice(1);
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
				rows: input.rows
			})
		)
	) as BrewLogSeeds;
}
