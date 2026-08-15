/**
 * `$lib/brew/methods` — the Brew Log method vocabulary (issue #10).
 *
 * Storage accepts ANY normalized method string (the core's
 * `normalize_brew_method` rule); this module owns what the *UI* makes
 * of one — the curated preset chips, display labels, per-method seed
 * values for the log form, and the last-used-method memory. Mirrors
 * Rust's `BREW_METHOD_PRESETS` (Tea deliberately absent — a BC tea brew
 * still imports, carrying its name as a free-text method).
 */

import type { BrewSeries } from '$lib/core/crema-core';
import { normalizeBrewMethod as wasmNormalize } from '$lib/wasm/de1_wasm';

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

/** One curated method preset — a chip in the log form. */
export interface BrewMethodPreset {
	/** The stored method string (`"french_press"`). */
	readonly id: string;
	/** Display label ("French press"). */
	readonly label: string;
	/** Seed dry dose, grams, for a first-ever log of this method. */
	readonly seedDose: number;
	/**
	 * Seed water-in, grams — `null` for espresso (which speaks yield)
	 * and for methods where water isn't usefully pre-fillable.
	 */
	readonly seedWater: number | null;
	/** Seed yield-out, grams — espresso only. */
	readonly seedYield: number | null;
	/** Seed water temperature, °C, or `null` (cold brew). */
	readonly seedTemp: number | null;
}

/**
 * The chip row, in display order. The first six render inline; the rest
 * live behind "More". Mirrors the spec's §3 defaults.
 */
export const BREW_METHOD_PRESETS: readonly BrewMethodPreset[] = [
	{ id: 'espresso', label: 'Espresso', seedDose: 18, seedWater: null, seedYield: 36, seedTemp: 93 },
	{ id: 'pourover', label: 'V60 / pourover', seedDose: 15, seedWater: 250, seedYield: null, seedTemp: 96 },
	{ id: 'aeropress', label: 'AeroPress', seedDose: 14, seedWater: 220, seedYield: null, seedTemp: 90 },
	{ id: 'french_press', label: 'French press', seedDose: 30, seedWater: 500, seedYield: null, seedTemp: 95 },
	{ id: 'moka', label: 'Moka', seedDose: 15, seedWater: 150, seedYield: null, seedTemp: null },
	{ id: 'cold_brew', label: 'Cold brew', seedDose: 60, seedWater: 700, seedYield: null, seedTemp: null },
	{ id: 'drip', label: 'Drip machine', seedDose: 30, seedWater: 500, seedYield: null, seedTemp: 94 },
	{ id: 'siphon', label: 'Siphon', seedDose: 20, seedWater: 300, seedYield: null, seedTemp: 92 },
	{ id: 'clever', label: 'Clever / Switch', seedDose: 18, seedWater: 300, seedYield: null, seedTemp: 94 }
];

/** How many preset chips render inline before the "More ▾" overflow. */
export const INLINE_PRESET_COUNT = 6;

/** Look up a preset by stored id, or `undefined` for free-text methods. */
export function presetFor(method: string | null | undefined): BrewMethodPreset | undefined {
	if (!method) return undefined;
	return BREW_METHOD_PRESETS.find((p) => p.id === method);
}

/**
 * Display label for any stored method string: the preset label when
 * curated, else the free text re-humanized ("karlsbad_kanne" →
 * "Karlsbad kanne"). `null`/empty (machine espresso) → "Espresso".
 */
export function methodLabel(method: string | null | undefined): string {
	const m = method?.trim().toLowerCase();
	if (!m) return 'Espresso';
	const preset = presetFor(m);
	if (preset) return preset.label;
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

/** The method the log form opens on — last saved, else pourover. */
export function lastUsedMethod(): string {
	try {
		const stored = localStorage.getItem(LAST_METHOD_KEY);
		if (stored && stored.trim()) return stored;
	} catch {
		// Storage unavailable (SSR/private mode) — fall through.
	}
	return 'pourover';
}

/** Remember the method of a just-saved log. */
export function rememberMethod(method: string): void {
	try {
		localStorage.setItem(LAST_METHOD_KEY, method);
	} catch {
		// Best-effort.
	}
}
