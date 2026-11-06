import { describe, expect, it } from 'vitest';
import { HotWaterMode, MachineState, type HotWaterTarget, type ModeTargets } from '#lib/core/crema-core.js';
import {
	brewReadiness,
	estimatedShotSeconds,
	modeChipSubs,
	peakPressureBar,
	relativeAgo,
	type ModeChipSubInputs
} from './phone-brew';
import type { ProfileSegment } from '#lib/profiles/index.js';

const targets = {
	steamTempC: 148,
	steamTimeoutS: 12,
	hotWaterTempC: 80,
	hotWaterTimeoutS: 60,
	flushTempC: 93,
	flushTimeS: 4
} as ModeTargets;
const volume = { mode: HotWaterMode.Volume, volumeMl: 105 } as HotWaterTarget;
const inputs = (over: Partial<ModeChipSubInputs> = {}): ModeChipSubInputs => ({
	mode: 'idle',
	elapsedS: 0,
	targets,
	hotWater: volume,
	tempUnit: 'C',
	units: { weightUnit: 'g', volumeUnit: 'ml' },
	known: true,
	...over
});

describe('modeChipSubs', () => {
	it('shows the targets at rest in the compact Android form', () => {
		expect(modeChipSubs(inputs())).toEqual({
			steam: '148° · 12s',
			water: '105ml · 80°',
			flush: '4s purge'
		});
	});

	it('follows the temperature unit', () => {
		const subs = modeChipSubs(inputs({ tempUnit: 'F' }));
		expect(subs.steam).toBe('298° · 12s');
		expect(subs.water).toBe('105ml · 176°');
	});

	it('follows the volume unit, and a time target shows its seconds', () => {
		const us = inputs({ units: { weightUnit: 'g', volumeUnit: 'floz' } });
		expect(modeChipSubs(us).water).toMatch(/^3\.\doz · 80°$/);
		const timed = { mode: HotWaterMode.Time, timeS: 30, wireVolumeMl: 0 } as HotWaterTarget;
		expect(modeChipSubs(inputs({ hotWater: timed })).water).toBe('30s · 80°');
		const open = { mode: HotWaterMode.Time, timeS: null, wireVolumeMl: 0 } as unknown as HotWaterTarget;
		expect(modeChipSubs(inputs({ hotWater: open })).water).toBe('no limit · 80°');
	});

	it('swaps only the running chip to the live counter', () => {
		const subs = modeChipSubs(inputs({ mode: 'steaming', elapsedS: 3.24 }));
		expect(subs.steam).toBe('3.2 / 12 s');
		expect(subs.water).toBe('105ml · 80°');
		expect(modeChipSubs(inputs({ mode: 'flushing', elapsedS: 1 })).flush).toBe('1.0 / 4 s');
		expect(modeChipSubs(inputs({ mode: 'dispensing', elapsedS: 2 })).water).toBe('2.0 s · 105 ml');
	});

	it('shows a dash before the targets are known, but never on a running mode', () => {
		expect(modeChipSubs(inputs({ known: false }))).toEqual({ steam: '—', water: '—', flush: '—' });
		const running = modeChipSubs(inputs({ known: false, mode: 'steaming', elapsedS: 1 }));
		expect(running.steam).toBe('1.0 / 12 s');
		expect(running.flush).toBe('—');
	});
});

describe('brewReadiness', () => {
	it('needs a reported state before it says ready', () => {
		expect(brewReadiness(false, MachineState.Idle, 'Ready')).toBe('not-connected');
		expect(brewReadiness(true, null, null)).toBe('syncing');
		expect(brewReadiness(true, MachineState.Sleep, 'Ready')).toBe('asleep');
		expect(brewReadiness(true, MachineState.Idle, 'Heating')).toBe('heating');
		expect(brewReadiness(true, MachineState.Idle, 'Ready')).toBe('ready');
	});
});

describe('the Ready card stats', () => {
	const seg = (mode: 'pressure' | 'flow', target: number, time: number) =>
		({ mode, target, time }) as ProfileSegment;

	it('peak pressure ignores flow segments', () => {
		expect(peakPressureBar([seg('pressure', 7.5, 10), seg('flow', 9, 5)])).toBe(7.5);
		expect(peakPressureBar([seg('flow', 2, 5)])).toBeNull();
		expect(peakPressureBar(undefined)).toBeNull();
	});

	it('estimated time sums the segments', () => {
		expect(estimatedShotSeconds([seg('pressure', 7, 10.5), seg('flow', 2, 14)])).toBe(24);
		expect(estimatedShotSeconds([])).toBeNull();
	});
});

describe('relativeAgo', () => {
	const now = 1_000_000_000_000;
	const min = 60_000;
	it('buckets like Android', () => {
		expect(relativeAgo(now - 30_000, now)).toBe('just now');
		expect(relativeAgo(now - 5 * min, now)).toBe('5m ago');
		expect(relativeAgo(now - 3 * 60 * min, now)).toBe('3h ago');
		expect(relativeAgo(now - 2 * 1440 * min, now)).toBe('2d ago');
		expect(relativeAgo(now - 8 * 1440 * min, now)).toBe('1w ago');
		expect(relativeAgo(now - 60 * 1440 * min, now)).toBe('2mo ago');
		expect(relativeAgo(now - 800 * 1440 * min, now)).toBe('2y ago');
	});
});
