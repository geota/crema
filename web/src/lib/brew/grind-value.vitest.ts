/**
 * One grind value (#488, save-boundaries round 2: REVIEW-CORE H1, H2, L1;
 * REVIEW-FINAL M2, S10, N2): web reads the core's `grind_value` for the
 * text a dial writes, comparison, display, scale refusal, Brew's grind view
 * and the chips, and keeps Brew's grind as text.
 */
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { blankBean, type Bean } from '#lib/bean/index.js';
import {
	countsInThirds,
	DEFAULT_GRIND_SCALE,
	formatGrind,
	grindChips,
	grindNumber,
	grindScaleRefusal,
	grindStartFromBlank,
	grindText,
	sameGrind,
	ScaleRefusal,
	shotGrindScale,
	stepText,
	type Grinder,
	type GrindScale
} from '#lib/bean/grinder.js';
import { grindView } from './grind-view';
import { BREW_GRIND_KEY, BrewGrindSession } from './grind-session.svelte';

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	localStorage.clear();
});

const FINE: GrindScale = { min: 0, max: 10, step: 0.05, unit: '' };
const MOTTO: GrindScale = { min: 0, max: 999, step: 1, unit: 'μm' };

function grinder(scale: GrindScale, id = 'grinder:g'): Grinder {
	return { id, name: 'G', scale } as Grinder;
}

function bag(grind: string | null, grinderId = 'grinder:g'): Bean {
	return {
		...blankBean('bean:a'),
		name: 'Guji',
		grinds: grind === null ? [] : [{ grinderId, grinderName: 'G', grind, starred: false }]
	};
}

describe('the core grind text (H1, M2)', () => {
	it('keeps the step\'s decimals: 2.25 on a 0.05 step, not 2.2 or 2.3', () => {
		expect(grindText(2.25, FINE)).toBe('2.25');
		expect(grindText(2.2 + 0.05, FINE), 'float noise from a stepper').toBe('2.25');
		expect(grindText(14, DEFAULT_GRIND_SCALE)).toBe('14');
		expect(grindText(4.2, DEFAULT_GRIND_SCALE)).toBe('4.2');
		expect(grindText(480, MOTTO)).toBe('480');
	});

	it('compares as numbers when both parse, else as trimmed text', () => {
		expect(sameGrind('15', '15.0')).toBe(true);
		expect(sameGrind('2,5', '2.5')).toBe(true);
		expect(sameGrind(' 2.1.3 ', '2.1.3')).toBe(true);
		expect(sameGrind('2.1.3', '2.1.4')).toBe(false);
	});

	it('adds the unit only after a plain number', () => {
		expect(formatGrind('480', MOTTO)).toBe('480 μm');
		expect(formatGrind('6 + a tooth', MOTTO)).toBe('6 + a tooth');
		expect(formatGrind('', MOTTO)).toBe('—');
		expect(formatGrind(null, MOTTO)).toBe('—');
	});

	it('parses only plain decimals', () => {
		expect(grindNumber('12,5')).toBe(12.5);
		expect(grindNumber('2.1.3')).toBeNull();
		expect(grindNumber('0x10')).toBeNull();
		expect(grindNumber('Infinity')).toBeNull();
		expect(grindNumber('')).toBeNull();
	});

	it('keeps an imported exponent or huge value as text, never a number to expand (security review)', () => {
		expect(grindNumber('1e999999999')).toBeNull();
		expect(grindNumber('1e5')).toBeNull();
		expect(grindNumber('9'.repeat(400))).toBeNull();
		expect(grindNumber('1000000')).toBe(1e6);
		expect(grindNumber('1000001')).toBeNull();
		expect(grindNumber('0.' + '1'.repeat(31)), 'more than 32 characters').toBeNull();
		expect(formatGrind('1e999999999', MOTTO), 'shown as typed').toBe('1e999999999');
		expect(grindView({ bag: null, grinder: grinder(MOTTO), brewGrind: '1e999999999' }).dial, 'the core keeps it as text').toBeNull();
	});
});

describe('thirds (Fellow Ode, step 1/3)', () => {
	const ODE: GrindScale = { min: 1, max: 11, step: Math.fround(1 / 3), unit: '' };

	it('writes 4⅓ and 4⅔, never 4.33, and reads them and 4 1/3 back as numbers', () => {
		expect(countsInThirds(ODE)).toBe(true);
		expect(countsInThirds(DEFAULT_GRIND_SCALE)).toBe(false);
		expect(grindText(4 + 1 / 3, ODE)).toBe('4⅓');
		expect(grindText(4 + Math.fround(1 / 3), ODE), 'a step from 4').toBe('4⅓');
		expect(grindText(14 / 3, ODE)).toBe('4⅔');
		expect(grindNumber('4⅓')).toBeCloseTo(4 + 1 / 3, 12);
		expect(grindNumber('4 1/3')).toBe(grindNumber('4⅓'));
		expect(grindNumber('4⅔')).toBeCloseTo(14 / 3, 12);
		expect(grindNumber('41/3'), 'a fraction after a whole number needs a space').toBeNull();
		expect(sameGrind('4⅓', '4 1/3')).toBe(true);
		expect(stepText(ODE)).toBe('⅓');
		expect(stepText({ min: 0, max: 10, step: 0.05, unit: '' })).toBe('0.05');
	});

	it('the chips around 4⅓ land on thirds', () => {
		expect(grindChips(ODE, '4⅓', 3).map((c) => c.text)).toEqual(['4', '4⅓', '4⅔']);
		expect(grindChips(ODE, '4 1/3', 3).map((c) => c.text)).toEqual(['4', '4⅓', '4⅔']);
	});
});

describe('shotGrindScale (history)', () => {
	const ode = { id: 'grinder:ode', name: 'Fellow Ode', scale: { min: 1, max: 11, step: 1 / 3, unit: '' } } as Grinder;

	it("reads the shot's grinder by id, else by name for an old shot, else the default", () => {
		expect(shotGrindScale({ grinderId: 'grinder:ode' }, [ode])).toBe(ode.scale);
		expect(shotGrindScale({ grinderId: null, grinderModel: ' fellow ode ' }, [ode])).toBe(ode.scale);
		expect(shotGrindScale({ grinderId: 'grinder:gone', grinderModel: 'Fellow Ode' }, [ode])).toBe(DEFAULT_GRIND_SCALE);
		expect(shotGrindScale({}, [ode])).toBe(DEFAULT_GRIND_SCALE);
	});
});

describe('scale refusal (Devices, the core rule Android uses)', () => {
	it('refuses what the core refuses, a step wider than the range included', () => {
		expect(grindScaleRefusal({ min: 0, max: 10, step: 0.05, unit: '' })).toBeNull();
		expect(grindScaleRefusal({ min: Number.NaN, max: 10, step: 1, unit: '' })).toBe(ScaleRefusal.NotANumber);
		expect(grindScaleRefusal({ min: 10, max: 10, step: 1, unit: '' })).toBe(ScaleRefusal.LowestNotBelowHighest);
		expect(grindScaleRefusal({ min: 0, max: 10, step: 0, unit: '' })).toBe(ScaleRefusal.StepNotPositive);
		expect(grindScaleRefusal({ min: 0, max: 10, step: 11, unit: '' })).toBe(ScaleRefusal.StepTooLarge);
	});
});

describe("Brew's grind view (H2, S10)", () => {
	it('not set is blank: "Grind —", no dial, no marker, nothing to save, the start on the step', () => {
		const v = grindView({ bag: bag('480'), grinder: grinder(MOTTO), brewGrind: null });
		expect(v.label).toBe('Grind —');
		expect(v.recorded).toBeNull();
		expect(v.dial).toBeNull();
		expect(v.marker).toBeNull();
		expect(v.saveGrind).toBeNull();
		expect(v.startFromBlank, 'the middle of the Motto 80, not a 4.2 placeholder').toBe(500);
		expect(grindStartFromBlank(DEFAULT_GRIND_SCALE)).toBe(10);
	});

	it('a set grind shows with its unit, and the marker and Save follow the core comparison', () => {
		const v = grindView({ bag: bag('480'), grinder: grinder(MOTTO), brewGrind: '482' });
		expect(v.label).toBe('Grind 482 μm');
		expect(v.recorded).toBe('482');
		expect(v.dial).toBe(482);
		expect(v.marker).toBe('Bag 480');
		expect(v.markerTitle).toContain('Guji has 480 μm saved for G');
		expect(v.saveGrind).toBe('482');
		const same = grindView({ bag: bag('15.0'), grinder: grinder(DEFAULT_GRIND_SCALE), brewGrind: '15' });
		expect(same.marker, '15 is 15.0').toBeNull();
	});

	it('nothing saved reads "Bag —"; no bag, no marker', () => {
		expect(grindView({ bag: bag(null), grinder: grinder(MOTTO), brewGrind: '480' }).marker).toBe('Bag —');
		expect(grindView({ bag: null, grinder: grinder(MOTTO), brewGrind: '480' }).marker).toBeNull();
	});

	it('a grind that isn\'t a plain number is Brew\'s grind as typed, with no dial (N2)', () => {
		const v = grindView({ bag: bag('2.1.3'), grinder: grinder(MOTTO), brewGrind: '2.1.3' });
		expect(v.label).toBe('Grind 2.1.3');
		expect(v.recorded).toBe('2.1.3');
		expect(v.dial).toBeNull();
		expect(v.marker).toBeNull();
	});

	it('the fine-step repro: 2.25 on a 0.05 step is what the header shows, the shot records and Save writes', () => {
		const v = grindView({ bag: bag('2.2'), grinder: grinder(FINE), brewGrind: grindText(2.25, FINE) });
		expect(v.label).toBe('Grind 2.25');
		expect(v.recorded).toBe('2.25');
		expect(v.saveGrind).toBe('2.25');
		expect(v.marker).toBe('Bag 2.2');
	});
});

describe('the chips (L1)', () => {
	it('single steps around Brew\'s grind, on the step and inside the scale', () => {
		expect(grindChips(MOTTO, '480', 5).map((c) => c.text)).toEqual(['478', '479', '480', '481', '482']);
		expect(grindChips(FINE, '2.25', 3).map((c) => c.text)).toEqual(['2.2', '2.25', '2.3']);
		expect(grindChips(MOTTO, '0', 3).map((c) => c.value)).toEqual([0, 1, 2]);
	});

	it('none when the grind is unset or isn\'t a plain number', () => {
		expect(grindChips(MOTTO, null, 5)).toEqual([]);
		expect(grindChips(MOTTO, '2.1.3', 5)).toEqual([]);
	});
});

describe("Brew's grind session is text", () => {
	it('starts not set, stores text, and blank clears it', () => {
		const s = new BrewGrindSession();
		expect(s.value).toBeNull();
		s.set('2.1.3');
		expect(s.value).toBe('2.1.3');
		expect(JSON.parse(localStorage.getItem(BREW_GRIND_KEY)!)).toBe('2.1.3');
		s.set('  ');
		expect(s.value).toBeNull();
	});

	it('the stepper writes the dial as the core does: 2.25 on a 0.05 step', () => {
		const s = new BrewGrindSession();
		s.setDial(2.2 + 0.05, FINE);
		expect(s.value).toBe('2.25');
	});

	it('reads a number stored before grinds were text', () => {
		localStorage.setItem(BREW_GRIND_KEY, '14');
		expect(new BrewGrindSession().value).toBe('14');
	});
});
