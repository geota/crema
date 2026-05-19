/**
 * `$lib/settings/format` — the shared unit-formatting helpers.
 *
 * Every Crema readout (Brew, History, Scale) measures in the core's canonical
 * SI-ish units — grams, °C, bar, mL — but the user picks a display unit in
 * Settings (`weightUnit` / `tempUnit` / `pressureUnit` / `volumeUnit`). These
 * helpers are the single place that conversion + labelling happens, so a unit
 * change in Settings reaches every screen at once.
 *
 * Two shapes per quantity:
 *  - `convert*` returns `{ value, unit }` — a pre-formatted numeric string and
 *    its unit label, separate, for readouts that style the two differently
 *    (e.g. the brew dashboard's `ChannelReadout`).
 *  - `format*` joins them into one `"12.3 g"`-style string for flat readouts.
 *
 * A `null`/non-finite value formats as a dim dash (`—`) with an empty unit,
 * the convention the dashboards already use.
 */

import type { PressureUnit, TempUnit, VolumeUnit, WeightUnit } from './store.svelte';

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
