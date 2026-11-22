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
		// Every step at 95 °C: the dial can reach 100 and no further; a value
		// beyond the core's bounds is refused.
		expect(params.brewTempBounds().max).toBe(100);
		expect(normalizeBrewOverrides(active.value, { brewTemp: 104 })).toEqual({});
		// A later step at 98 °C leaves 2 °C of room: 99 is clamped to 97.
		const hotter = profileAt(95, 'Hotter');
		hotter.segments = hotter.segments.map((s, i) => (i === 1 ? { ...s, temp: 98 } : s));
		active.value = hotter;
		expect(params.brewTempBounds().max).toBe(97);
		expect(normalizeBrewOverrides(hotter, { brewTemp: 99 })).toEqual({ brewTemp: 97 });
	});
});

describe('the dial lives at app level (F7)', () => {
	it('a dial mounted again starts from the app-level overrides for its profile', () => {
		const active = new ReactiveBox(profileAt(92, 'A'));
		// What the app holds for the active profile (CremaApp.brewOverridesFor).
		const app = new ReactiveBox<{ profileId: string; qc: { brewTemp?: number } } | null>(null);
		const overridesFor = (p: CremaProfile) =>
			app.value?.profileId === p.id ? app.value.qc : {};
		const mount = () =>
			new BrewParamState(
				() => seedOf(active.value),
				undefined,
				undefined,
				() => brewTempRange(active.value),
				() => overridesFor(active.value)
			);

		// The dial moves; the dashboard reports it to the app.
		const first = mount();
		first.set('brewTemp', 94.5);
		app.value = { profileId: active.value.id, qc: first.qcOverrides() };

		// Leaving Brew drops the component; coming back mounts a new dial.
		const again = mount();
		expect(again.current.brewTemp).toBe(94.5);
		expect(again.qcOverrides()).toEqual({ brewTemp: 94.5 });
		expect(again.isOverridden('brewTemp')).toBe(true);

		// Another profile: the app's overrides are for the old one — none.
		active.value = profileAt(90, 'B');
		flushSync();
		expect(again.current.brewTemp).toBe(90);
		expect(again.qcOverrides()).toEqual({});
	});
});
