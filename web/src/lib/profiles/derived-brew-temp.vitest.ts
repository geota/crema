/**
 * F6 validation P1: a profile's top-level brew temp is its first step's
 * temperature (the brew-temp dial's anchor), kept derived by the core on save,
 * so the cards, the dial and the editor show the same number — also for a
 * custom profile saved before the rule (93 over 92 °C steps).
 */

import { describe, expect, it } from 'vitest';
import { blankProfile, brewTempRange, effectiveCremaProfile, getProfileStore } from './index';

describe('the brew temp follows the first step', () => {
	it('a save re-derives a stale brew temp from the steps', () => {
		const stale = { ...blankProfile(), name: 'Old blank', brewTemp: 93 };
		stale.segments = stale.segments.map((s) => ({ ...s, temp: 92 }));
		const store = getProfileStore();
		store.save(stale);
		expect(store.get(stale.id)?.brewTemp).toBe(92);
		expect(brewTempRange(stale).anchor).toBe(92);
	});

	it("the editor's brew temp shifts every step", () => {
		const p = blankProfile();
		p.segments = p.segments.map((s, i) => ({ ...s, temp: [94, 90, 88, 88][i] ?? 88 }));
		const edited = effectiveCremaProfile(p, { brewTemp: 92 });
		expect(edited.segments.map((s) => s.temp)).toEqual([92, 88, 86, 86]);
		expect(edited.brewTemp).toBe(92);
	});
});
