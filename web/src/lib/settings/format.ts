/**
 * `$lib/settings/format` — the shared unit-formatting helpers.
 *
 * Every Crema readout (Brew, History, Scale) measures in the core's canonical
 * SI-ish units — grams, °C, bar, mL — but the user picks a display unit in
 * Settings (`weightUnit` / `tempUnit` / `pressureUnit` / `volumeUnit`). These
 * helpers are the single place that conversion + labelling happens, so a unit
 * change in Settings reaches every screen at once.
 *
 * Three shapes per quantity:
 *  - `convert*` returns `{ value, unit }` — a pre-formatted numeric string and
 *    its unit label, separate, for readouts that style the two differently
 *    (e.g. the brew dashboard's `ChannelReadout`).
 *  - `format*` joins them into one `"12.3 g"`-style string for flat readouts.
 *  - `toCanonical*` inverts the conversion for editable inputs (a stepper
 *    showing oz still commits grams to the canonical store).
 *
 * Plus a dimension-keyed facade — `displayValue` / `displayToCanonical` /
 * `unitLabel` / `displayDecimals` — so a unit-aware control can take a single
 * `dimension` prop and let this module own the rest.
 *
 * A `null`/non-finite value formats as a dim dash (`—`) with an empty unit,
 * the convention the dashboards already use.
 */

import type {
	PressureUnit,
	Settings,
	TempUnit,
	VolumeUnit,
	WeightUnit
} from './store.svelte';
import {
	bar_to_psi as wasmBarToPsi,
	celsius_to_fahrenheit as wasmCelsiusToFahrenheit,
	fahrenheit_to_celsius as wasmFahrenheitToCelsius,
	fl_oz_to_ml as wasmFlOzToMl,
	grams_to_oz as wasmGramsToOz,
	ml_to_fl_oz as wasmMlToFlOz,
	oz_to_grams as wasmOzToGrams,
	psi_to_bar as wasmPsiToBar
} from '$lib/wasm/de1_wasm';

/** The placeholder shown for a missing reading — matches the dashboards. */
const DASH = '—';

/** A formatted measurement split into its numeric string and unit label. */
export interface Measurement {
	/** The formatted numeric part, or `—` when there is no reading. */
	value: string;
	/** The unit label, or `''` when there is no reading. */
	unit: string;
}

/** Whether a value is a real, finite number worth formatting. */
function present(v: number | null | undefined): v is number {
	return v != null && Number.isFinite(v);
}

// ────────────────────────────────────────────────────────────────────────────
// Display-side conversion (canonical → user-chosen)
// ────────────────────────────────────────────────────────────────────────────

/**
 * Convert a weight (canonical grams) to the chosen {@link WeightUnit}.
 * `g` shows grams to one decimal; `oz` converts via the core's
 * `grams_to_oz` (1 oz = 28.3495 g) to two. The arithmetic lives in
 * `de1_domain::units` so every shell shares one set of constants — see
 * `docs/26-shell-to-core-audit.md` item #1.
 *
 * **The unit label is always honest.** A missing reading formats the value
 * as `—` but the `unit` still reflects the user's pref — otherwise a
 * `"— g"` placeholder would lie about Celsius / bar / grams whenever the
 * user had picked the imperial unit. Same for {@link convertTemp},
 * {@link convertPressure}, {@link convertVolume}.
 */
export function convertWeight(grams: number | null | undefined, unit: WeightUnit): Measurement {
	const label = unit === 'oz' ? 'oz' : 'g';
	if (!present(grams)) return { value: DASH, unit: label };
	if (unit === 'oz') return { value: wasmGramsToOz(grams).toFixed(2), unit: 'oz' };
	return { value: grams.toFixed(1), unit: 'g' };
}

/**
 * Convert a temperature (canonical °C) to the chosen {@link TempUnit}.
 * `F` converts via the core's `celsius_to_fahrenheit` (`°C × 9/5 + 32`).
 * Both show one decimal. Unit label stays honest when the value is
 * missing — see {@link convertWeight}.
 */
export function convertTemp(celsius: number | null | undefined, unit: TempUnit): Measurement {
	const label = unit === 'F' ? '°F' : '°C';
	if (!present(celsius)) return { value: DASH, unit: label };
	if (unit === 'F') return { value: wasmCelsiusToFahrenheit(celsius).toFixed(1), unit: '°F' };
	return { value: celsius.toFixed(1), unit: '°C' };
}

/**
 * Convert a pressure (canonical bar) to the chosen {@link PressureUnit}.
 * `psi` converts via the core's `bar_to_psi` (1 bar = 14.5038 psi).
 * `bar` shows one decimal, `psi` none. Unit label stays honest when the
 * value is missing — see {@link convertWeight}.
 */
export function convertPressure(
	bar: number | null | undefined,
	unit: PressureUnit
): Measurement {
	const label = unit === 'psi' ? 'psi' : 'bar';
	if (!present(bar)) return { value: DASH, unit: label };
	if (unit === 'psi') return { value: String(Math.round(wasmBarToPsi(bar))), unit: 'psi' };
	return { value: bar.toFixed(1), unit: 'bar' };
}

/**
 * Convert a volume (canonical mL) to the chosen {@link VolumeUnit}.
 * `floz` converts via the core's `ml_to_fl_oz` (1 US fl oz = 29.5735 mL)
 * to one decimal; `ml` shows a whole number — a tank volume is never
 * meaningfully fractional. Unit label stays honest when the value is
 * missing — see {@link convertWeight}.
 */
export function convertVolume(ml: number | null | undefined, unit: VolumeUnit): Measurement {
	const label = unit === 'floz' ? 'fl oz' : 'mL';
	if (!present(ml)) return { value: DASH, unit: label };
	if (unit === 'floz') return { value: wasmMlToFlOz(ml).toFixed(1), unit: 'fl oz' };
	return { value: String(Math.round(ml)), unit: 'mL' };
}

/** Join a {@link Measurement} into a `"12.3 g"`-style string (no unit → bare value). */
function join(m: Measurement): string {
	return m.unit ? `${m.value} ${m.unit}` : m.value;
}

/** Format a weight (canonical grams) as one string in the chosen unit. */
export function formatWeight(grams: number | null | undefined, unit: WeightUnit): string {
	return join(convertWeight(grams, unit));
}

/** Format a temperature (canonical °C) as one string in the chosen unit. */
export function formatTemp(celsius: number | null | undefined, unit: TempUnit): string {
	return join(convertTemp(celsius, unit));
}

/** Format a pressure (canonical bar) as one string in the chosen unit. */
export function formatPressure(bar: number | null | undefined, unit: PressureUnit): string {
	return join(convertPressure(bar, unit));
}

/** Format a volume (canonical mL) as one string in the chosen unit. */
export function formatVolume(ml: number | null | undefined, unit: VolumeUnit): string {
	return join(convertVolume(ml, unit));
}

// ────────────────────────────────────────────────────────────────────────────
// Inverse conversion (user-chosen → canonical) — for editable inputs
// ────────────────────────────────────────────────────────────────────────────

/** Convert a display-unit weight back to canonical grams (uses the core's `oz_to_grams`). */
export function toCanonicalWeight(displayValue: number, unit: WeightUnit): number {
	return unit === 'oz' ? wasmOzToGrams(displayValue) : displayValue;
}

/** Convert a display-unit temperature back to canonical °C (uses the core's `fahrenheit_to_celsius`). */
export function toCanonicalTemp(displayValue: number, unit: TempUnit): number {
	return unit === 'F' ? wasmFahrenheitToCelsius(displayValue) : displayValue;
}

/** Convert a display-unit pressure back to canonical bar (uses the core's `psi_to_bar`). */
export function toCanonicalPressure(displayValue: number, unit: PressureUnit): number {
	return unit === 'psi' ? wasmPsiToBar(displayValue) : displayValue;
}

/** Convert a display-unit volume back to canonical mL (uses the core's `fl_oz_to_ml`). */
export function toCanonicalVolume(displayValue: number, unit: VolumeUnit): number {
	return unit === 'floz' ? wasmFlOzToMl(displayValue) : displayValue;
}

// ────────────────────────────────────────────────────────────────────────────
// Dimension-keyed facade — lets a stepper take a single `dimension` prop
// ────────────────────────────────────────────────────────────────────────────

/**
 * The four measurable quantities a control can be parameterised by. A control
 * with `dimension="weight"` reads the user's `weightUnit` pref, formats its
 * canonical value into that unit on display, and inverts on commit.
 */
export type Dimension = 'weight' | 'temp' | 'pressure' | 'volume';

/** The unit suffix the user picked for `dimension`, ready to render. */
export function unitLabel(dim: Dimension, s: Settings): string {
	switch (dim) {
		case 'weight':
			return s.weightUnit === 'oz' ? 'oz' : 'g';
		case 'temp':
			return s.tempUnit === 'F' ? '°F' : '°C';
		case 'pressure':
			return s.pressureUnit === 'psi' ? 'psi' : 'bar';
		case 'volume':
			return s.volumeUnit === 'floz' ? 'fl oz' : 'mL';
	}
}

/**
 * How many decimals a control should render for `dimension` × the user's unit.
 * Chosen so the display granularity is roughly equivalent across unit choices
 * (e.g. 0.1 g ≈ 0.01 oz; 1 bar ≈ 14.5 psi so psi shows whole numbers).
 */
export function displayDecimals(dim: Dimension, s: Settings): number {
	switch (dim) {
		case 'weight':
			return s.weightUnit === 'oz' ? 2 : 1;
		case 'temp':
			return 1;
		case 'pressure':
			return s.pressureUnit === 'psi' ? 0 : 1;
		case 'volume':
			return s.volumeUnit === 'floz' ? 1 : 0;
	}
}

/** Canonical → display number (just the number — no formatting). */
export function canonicalToDisplay(dim: Dimension, canonical: number, s: Settings): number {
	switch (dim) {
		case 'weight':
			return s.weightUnit === 'oz' ? canonical / 28.3495 : canonical;
		case 'temp':
			return s.tempUnit === 'F' ? canonical * 1.8 + 32 : canonical;
		case 'pressure':
			return s.pressureUnit === 'psi' ? canonical * 14.5038 : canonical;
		case 'volume':
			return s.volumeUnit === 'floz' ? canonical / 29.5735 : canonical;
	}
}

/** Display number → canonical (inverse of {@link canonicalToDisplay}). */
export function displayToCanonical(dim: Dimension, display: number, s: Settings): number {
	switch (dim) {
		case 'weight':
			return toCanonicalWeight(display, s.weightUnit);
		case 'temp':
			return toCanonicalTemp(display, s.tempUnit);
		case 'pressure':
			return toCanonicalPressure(display, s.pressureUnit);
		case 'volume':
			return toCanonicalVolume(display, s.volumeUnit);
	}
}

/**
 * Format a canonical value as one `"12.3 g"`-style string, by dimension. The
 * generic counterpart of `formatWeight` etc.; useful when the caller has a
 * dimension prop but no convenient access to the per-unit helpers.
 */
export function formatByDimension(
	dim: Dimension,
	canonical: number | null | undefined,
	s: Settings
): string {
	switch (dim) {
		case 'weight':
			return formatWeight(canonical, s.weightUnit);
		case 'temp':
			return formatTemp(canonical, s.tempUnit);
		case 'pressure':
			return formatPressure(canonical, s.pressureUnit);
		case 'volume':
			return formatVolume(canonical, s.volumeUnit);
	}
}
