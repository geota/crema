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
 * `g` shows grams to one decimal; `oz` converts (1 oz = 28.3495 g) to two.
 */
export function convertWeight(grams: number | null | undefined, unit: WeightUnit): Measurement {
	if (!present(grams)) return { value: DASH, unit: '' };
	if (unit === 'oz') return { value: (grams / 28.3495).toFixed(2), unit: 'oz' };
	return { value: grams.toFixed(1), unit: 'g' };
}

/**
 * Convert a temperature (canonical °C) to the chosen {@link TempUnit}.
 * `F` converts via `°C × 9/5 + 32`. Both show one decimal.
 */
export function convertTemp(celsius: number | null | undefined, unit: TempUnit): Measurement {
	if (!present(celsius)) return { value: DASH, unit: '' };
	if (unit === 'F') return { value: (celsius * 1.8 + 32).toFixed(1), unit: '°F' };
	return { value: celsius.toFixed(1), unit: '°C' };
}

/**
 * Convert a pressure (canonical bar) to the chosen {@link PressureUnit}.
 * `psi` converts (1 bar = 14.5038 psi). `bar` shows one decimal, `psi` none.
 */
export function convertPressure(
	bar: number | null | undefined,
	unit: PressureUnit
): Measurement {
	if (!present(bar)) return { value: DASH, unit: '' };
	if (unit === 'psi') return { value: String(Math.round(bar * 14.5038)), unit: 'psi' };
	return { value: bar.toFixed(1), unit: 'bar' };
}

/**
 * Convert a volume (canonical mL) to the chosen {@link VolumeUnit}.
 * `floz` converts (1 US fl oz = 29.5735 mL) to one decimal; `ml` shows a
 * whole number — a tank volume is never meaningfully fractional.
 */
export function convertVolume(ml: number | null | undefined, unit: VolumeUnit): Measurement {
	if (!present(ml)) return { value: DASH, unit: '' };
	if (unit === 'floz') return { value: (ml / 29.5735).toFixed(1), unit: 'fl oz' };
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

/** Convert a display-unit weight back to canonical grams. */
export function toCanonicalWeight(displayValue: number, unit: WeightUnit): number {
	return unit === 'oz' ? displayValue * 28.3495 : displayValue;
}

/** Convert a display-unit temperature back to canonical °C. */
export function toCanonicalTemp(displayValue: number, unit: TempUnit): number {
	return unit === 'F' ? (displayValue - 32) / 1.8 : displayValue;
}

/** Convert a display-unit pressure back to canonical bar. */
export function toCanonicalPressure(displayValue: number, unit: PressureUnit): number {
	return unit === 'psi' ? displayValue / 14.5038 : displayValue;
}

/** Convert a display-unit volume back to canonical mL. */
export function toCanonicalVolume(displayValue: number, unit: VolumeUnit): number {
	return unit === 'floz' ? displayValue * 29.5735 : displayValue;
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
