/**
 * The grinder's observation and the speed in History (spec grinder-ble §4.2,
 * §7; REVIEW-554): the list's grind label carries the speed once, the detail
 * says what the grinder reported and whether the grind was edited since, and
 * editing the speed replaces a trailing one in the grind text.
 */
import { beforeAll, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { formatGrind, hasVariableSpeed, sameGrind, withGrindRpm } from '#lib/bean/grinder.js';
import { grindLabel, grinderObservationText, type StoredShot } from './model';

beforeAll(async () => {
	await initTestWasm();
});

const um = { min: 0, max: 999, step: 1, unit: 'μm' };
const shot = (over: Partial<StoredShot> & { grind?: string }): StoredShot =>
	({ metadata: { grinderSetting: over.grind }, bean: null, ...over }) as unknown as StoredShot;

describe('grindLabel with a speed', () => {
	it('adds the speed once', () => {
		expect(grindLabel(shot({ grind: '480', grindRpm: 700 }))).toBe('Grind 480 · 700 rpm');
		expect(grindLabel(shot({ grind: '15 1200rpm', grindRpm: 1200 }))).toBe('Grind 15 1200rpm');
		expect(grindLabel(shot({ grind: '480' }))).toBe('Grind 480');
		expect(grindLabel(shot({ grindRpm: 900 }))).toBe('Grind 900 rpm');
		expect(grindLabel(shot({}))).toBeNull();
	});
});

describe('grinderObservationText', () => {
	const text = (s: StoredShot) => grinderObservationText(s, (g) => formatGrind(g, um), sameGrind);

	it('the grind before the shot, as the grinder said it', () => {
		const s = shot({
			grind: '480',
			grinderModel: 'Motto 80',
			grinderObserved: { source: 'LastGrind', setting: '480', grindRpm: 700, feedRpm: 50, grindMs: 9_800 }
		});
		expect(text(s)).toEqual({ line: '480 μm · 700 rpm · feed 50 · 9.8 s grind · from Motto 80', edited: null });
	});

	it('edited since: the grinder said otherwise', () => {
		const s = shot({ grind: '470', grinderObserved: { source: 'LastGrind', setting: '480' } });
		expect(text(s)?.edited).toBe('Edited (grinder said 480 μm)');
		expect(text(shot({ grind: '480.0', grinderObserved: { source: 'LastGrind', setting: '480' } }))?.edited).toBeNull();
	});

	it('a reading at shot start, and no observation', () => {
		const s = shot({ grind: '480', grinderObserved: { source: 'AtShotStart', setting: '480' } });
		expect(text(s)?.line).toBe('480 μm · reading at shot start');
		expect(text(shot({ grind: '480' }))).toBeNull();
	});
});

describe('withGrindRpm', () => {
	it('replaces a trailing speed, never appends a second one', () => {
		expect(withGrindRpm('15 1200rpm', 1100)).toBe('15 1100rpm');
		expect(withGrindRpm('15 1200 RPM', 1100)).toBe('15 1100rpm');
		expect(withGrindRpm('15 1200rpm', null)).toBe('15');
		expect(withGrindRpm('1200rpm', null)).toBeNull();
		// No speed in the text: it stays; the speed rides beside it.
		expect(withGrindRpm('480', 700)).toBe('480');
		expect(withGrindRpm('18 clicks', 700)).toBe('18 clicks');
		expect(withGrindRpm(null, 700)).toBeNull();
	});

	it('the Motto 80 is a variable-speed grinder; a Niche Zero is not', () => {
		expect(hasVariableSpeed({ presetId: 'bookoo-motto-80' })).toBe(true);
		expect(hasVariableSpeed({ presetId: 'niche-zero' })).toBe(false);
		expect(hasVariableSpeed(null)).toBe(false);
	});
});
