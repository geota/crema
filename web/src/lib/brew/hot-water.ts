/**
 * `#lib/brew/hot-water` — the words for the hot-water target.
 *
 * The core owns every number (`HotWaterTarget`: what Crema pours, what the
 * packet holds, where the weight stop sits); this module only turns a target
 * into the readout line, the resting chip amount and the running counter of
 * spec §2.7, in the user's units. Nothing here computes an amount.
 */

import type { HotWaterTarget } from '#lib/core/crema-core.js';
import { HotWaterBasis, HotWaterClamp, HotWaterMode } from '#lib/core/crema-core.js';
import { ratioLabel } from '#lib/wasm/de1_wasm.js';
import { convertWeight, formatVolume } from '#lib/settings/format.js';
import type { VolumeUnit, WeightUnit } from '#lib/settings/store.svelte.js';

/** The unit preferences the copy follows. */
export interface HotWaterUnits {
	weightUnit: WeightUnit;
	volumeUnit: VolumeUnit;
}

/** Above this many seconds the time readout warns of a large pour. */
const LARGE_POUR_S = 120;

/** A shot weight in the user's unit, a trailing ".0" dropped: `50 g`, `36.5 g`. */
function shotWeight(grams: number | null | undefined, unit: WeightUnit): string {
	const m = convertWeight(grams, unit);
	return `${m.value.replace(/\.0$/, '')} ${m.unit}`;
}

/** A time in whole seconds: `30 s`. */
function seconds(s: number): string {
	return `${Math.round(s)} s`;
}

/**
 * The readout line under the hot-water stepper (spec §2.7), or `''` when there
 * is nothing to say (volume mode without an operational scale).
 *
 * - Ratio: "1:3 → 150 ml for a 50 g shot"; no yield target: "No yield target ·
 *   150 ml"; capped / floored: the §2.4 copy.
 * - Volume: "Stops by weight" with an operational scale.
 * - Time: "Stops at 30 s"; above 120 s a large-pour warning; when the firmware
 *   needs a volume backstop in time mode, "or about 255 ml"; No limit:
 *   "Pours until you stop it".
 */
export function hotWaterReadout(target: HotWaterTarget, units: HotWaterUnits, ratio: number): string {
	if (target.mode === HotWaterMode.Time) {
		if (target.timeS == null) return 'Pours until you stop it, also from the group-head button';
		if (target.wireVolumeMl > 0) {
			return `Stops at ${seconds(target.timeS)}, or about ${formatVolume(target.wireVolumeMl, units.volumeUnit)}`;
		}
		if (target.timeS > LARGE_POUR_S) {
			return `Stops at ${seconds(target.timeS)} — a large pour, also from the group-head button`;
		}
		return `Stops at ${seconds(target.timeS)}`;
	}
	const ml = formatVolume(target.volumeMl, units.volumeUnit);
	if (target.mode === HotWaterMode.Volume) {
		return target.stopWeightG != null ? 'Stops by weight' : '';
	}
	if (target.basis === HotWaterBasis.NoYieldTarget) return `No yield target · ${ml}`;
	const r = ratioLabel(ratio);
	if (target.clamp === HotWaterClamp.Capped && target.requestedMl != null) {
		return `${r} → ${ml} (capped; ${r} of ${shotWeight(target.basisG, units.weightUnit)} is ${formatVolume(target.requestedMl, units.volumeUnit)})`;
	}
	if (target.clamp === HotWaterClamp.RaisedToMinimum) {
		return `${r} → ${ml} (raised to the ${ml} minimum)`;
	}
	return `${r} → ${ml} for a ${shotWeight(target.basisG, units.weightUnit)} shot`;
}

/**
 * The amount a resting chip shows after the temperature: "150 ml", "30 s", or
 * "no limit".
 */
export function hotWaterRestingAmount(target: HotWaterTarget, units: HotWaterUnits): string {
	if (target.mode === HotWaterMode.Time) {
		return target.timeS == null ? 'no limit' : seconds(target.timeS);
	}
	return formatVolume(target.volumeMl, units.volumeUnit);
}

/**
 * The running counter while hot water pours: "12.3 / 30.0 s" in time mode
 * ("12.3 s" for No limit), "12.3 s · 150 ml" in volume and ratio modes — the
 * 60 s backstop isn't the dose.
 */
export function hotWaterRunning(target: HotWaterTarget, elapsedS: number, units: HotWaterUnits): string {
	const elapsed = elapsedS.toFixed(1);
	if (target.mode === HotWaterMode.Time) {
		return target.timeS == null ? `${elapsed} s` : `${elapsed} / ${target.timeS.toFixed(1)} s`;
	}
	return `${elapsed} s · ${formatVolume(target.volumeMl, units.volumeUnit)}`;
}

/** The seconds a pour runs for, for a progress bar: time mode only, else 0. */
export function hotWaterTargetSeconds(target: HotWaterTarget): number {
	return target.mode === HotWaterMode.Time && target.timeS != null ? target.timeS : 0;
}
