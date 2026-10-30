/**
 * The Quick Sheet's brew overrides and their lifecycle (AC-F6.5, web half):
 * kept apart from the saved profile, cleared when the profile changes, and
 * cleared when dialled back to the profile's own value — with the core's rule
 * (`normalizeBrewOverrides`) agreeing with the sheet.
 */

import { describe, expect, it } from 'vitest';
import { flushSync } from 'svelte';
import { ReactiveBox } from '#lib/testing/reactive-box.svelte.js';
import {
	blankProfile,
	brewTempRange,
	normalizeBrewOverrides,
	preinfusionSeconds,
	type CremaProfile
} from '#lib/profiles/index.js';
import { BrewParamState, type BrewParamSeed } from './brew-params.svelte';

function profileAt(brewTemp: number, name: string): CremaProfile {
	const p = blankProfile();
	return { ...p, name, brewTemp, segments: p.segments.map((s) => ({ ...s, temp: brewTemp })) };
}

/** The dashboard's seed: the core's anchor and pre-infusion total. */
function seedOf(p: CremaProfile): BrewParamSeed {
	return {
		dose: p.dose,
		yield: p.yieldOut,
		brewTemp: brewTempRange(p).anchor,
		preinf: preinfusionSeconds(p)
	};
}

function sheet(active: ReactiveBox<CremaProfile>): BrewParamState {
	return new BrewParamState(
		() => seedOf(active.value),
		undefined,
		undefined,
		() => brewTempRange(active.value)
	);
}

describe('brew overrides (AC-F6.5)', () => {
	it('f6_5 a profile change clears the override', () => {
		const active = new ReactiveBox(profileAt(92, 'A'));
		const params = sheet(active);
		params.set('brewTemp', 94.5);
		expect(params.qcOverrides()).toEqual({ brewTemp: 94.5 });
		expect(params.brewTempOffset()).toBeCloseTo(2.5);

		active.value = profileAt(90, 'B');
		flushSync();
		expect(params.current.brewTemp).toBe(90);
		expect(params.qcOverrides()).toEqual({});
		expect(params.isOverridden('brewTemp')).toBe(false);
	});

	it('f6_5 dialling back to the profile clears the override, in the sheet and in the core', () => {
		const active = new ReactiveBox(profileAt(93, 'A'));
		const params = sheet(active);
		params.set('brewTemp', 95);
		params.set('brewTemp', 93);
		expect(params.qcOverrides()).toEqual({});
		expect(normalizeBrewOverrides(active.value, { brewTemp: 93 })).toEqual({});
		// The dial's Reset puts it back too.
		params.set('brewTemp', 96);
		params.resetKey('brewTemp');
		expect(params.isOverridden('brewTemp')).toBe(false);
	});

	it('the dial is bounded by the core range for the active profile', () => {
		const active = new ReactiveBox(profileAt(95, 'Hot'));
		const params = sheet(active);
		// Every step at 95 °C: the dial can reach 100 and no further.
		expect(params.brewTempBounds().max).toBe(100);
		expect(normalizeBrewOverrides(active.value, { brewTemp: 104 })).toEqual({ brewTemp: 100 });
	});
});
