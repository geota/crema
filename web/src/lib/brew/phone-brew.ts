/**
 * `#lib/brew/phone-brew` — the words on the phone Brew screen, the rules of
 * Android's `PhoneBrewScreen` for the web's snapshot.
 *
 * Nothing here decides what the machine does: the mode chips' numbers come
 * from the core's resolved `ModeTargets` and `HotWaterTarget`, and this module
 * only turns them into the compact lines a 90 px chip can hold.
 */

import type { HotWaterTarget, ModeTargets } from '#lib/core/crema-core.js';
import { MachineState } from '#lib/core/crema-core.js';
import { convertTemp } from '#lib/settings/format.js';
import type { TempUnit } from '#lib/settings/store.svelte.js';
import type { ProfileSegment } from '#lib/profiles/index.js';
import { hotWaterRestingAmount, hotWaterRunning, type HotWaterUnits } from './hot-water.js';

/** The dash a chip or a stat shows for a value that isn't known yet. */
export const UNKNOWN_VALUE = '—';

/** Which service mode the DE1 is in, as `BrewDashboard` derives it. */
export type ModeState = 'idle' | 'steaming' | 'dispensing' | 'flushing';

/** Android's `BrewReadiness`: the Ready card's status line. */
export type BrewReadiness = 'not-connected' | 'syncing' | 'asleep' | 'heating' | 'ready';

/**
 * "Ready" needs a state the DE1 actually reported: a link with none yet is
 * syncing, not ready (Android `brewReadiness`).
 */
export function brewReadiness(
	connected: boolean,
	state: MachineState | null | undefined,
	substate: string | null | undefined
): BrewReadiness {
	if (!connected) return 'not-connected';
	if (state == null) return 'syncing';
	if (state === MachineState.Sleep) return 'asleep';
	if (substate === 'Heating') return 'heating';
	return 'ready';
}

/** A temperature as a chip shows it: whole degrees, no unit (`148°`). */
function compactDegrees(celsius: number, unit: TempUnit): string {
	return `${Math.round(Number(convertTemp(celsius, unit).value))}°`;
}

/** The hot-water amount with its space dropped: `105ml`, `30s`, `3.6oz`. */
function compactAmount(target: HotWaterTarget, units: HotWaterUnits): string {
	return hotWaterRestingAmount(target, units).replace(' fl oz', 'oz').replace(/ (ml|s)$/, '$1');
}

/** `elapsed / total s` — a running mode's chip line. */
function runningLine(elapsedS: number, targetS: number): string {
	return `${elapsedS.toFixed(1)} / ${targetS.toFixed(0)} s`;
}

export interface ModeChipSubInputs {
	mode: ModeState;
	elapsedS: number;
	targets: ModeTargets;
	hotWater: HotWaterTarget | null;
	tempUnit: TempUnit;
	units: HotWaterUnits;
	/** False until the app has loaded: the chips then show {@link UNKNOWN_VALUE}. */
	known: boolean;
}

/**
 * The second line of the three mode chips (Android `ModeCluster`): the target
 * at rest, a live counter on the chip whose mode is running, a dash before the
 * targets are known. A running chip never shows the dash: the mode is real.
 */
export function modeChipSubs(i: ModeChipSubInputs): { steam: string; water: string; flush: string } {
	const { targets: t } = i;
	const deg = (c: number): string => compactDegrees(c, i.tempUnit);
	const rest = (line: string): string => (i.known ? line : UNKNOWN_VALUE);
	return {
		steam:
			i.mode === 'steaming'
				? runningLine(i.elapsedS, t.steamTimeoutS)
				: rest(`${deg(t.steamTempC)} · ${Math.round(t.steamTimeoutS)}s`),
		water:
			i.mode === 'dispensing'
				? i.hotWater
					? hotWaterRunning(i.hotWater, i.elapsedS, i.units)
					: runningLine(i.elapsedS, t.hotWaterTimeoutS)
				: rest(
						i.hotWater
							? `${compactAmount(i.hotWater, i.units)} · ${deg(t.hotWaterTempC)}`
							: deg(t.hotWaterTempC)
					),
		flush:
			i.mode === 'flushing'
				? runningLine(i.elapsedS, t.flushTimeS)
				: rest(`${Math.round(t.flushTimeS)}s purge`)
	};
}

/** The Ready card's PRESSURE stat: the profile's highest pressure target. */
export function peakPressureBar(segments: readonly ProfileSegment[] | undefined): number | null {
	const targets = (segments ?? []).filter((s) => s.mode !== 'flow').map((s) => s.target);
	return targets.length > 0 ? Math.max(...targets) : null;
}

/** The Ready card's EST. TIME stat: the sum of the profile's segment times. */
export function estimatedShotSeconds(segments: readonly ProfileSegment[] | undefined): number | null {
	if (!segments || segments.length === 0) return null;
	return Math.trunc(segments.reduce((sum, s) => sum + s.time, 0));
}

/** "just now", "12m ago", "1w ago" (Android `relativeAgo`). */
export function relativeAgo(epochMs: number, nowMs: number = Date.now()): string {
	const min = Math.max(0, Math.floor((nowMs - epochMs) / 60_000));
	if (min < 1) return 'just now';
	if (min < 60) return `${min}m ago`;
	const hours = Math.floor(min / 60);
	if (hours < 24) return `${hours}h ago`;
	const days = Math.floor(hours / 24);
	if (days < 7) return `${days}d ago`;
	const weeks = Math.floor(days / 7);
	if (weeks < 5) return `${weeks}w ago`;
	const months = Math.floor(days / 30);
	if (months < 12) return `${months}mo ago`;
	return `${Math.floor(days / 365)}y ago`;
}
