import { beforeEach, describe, expect, it } from 'vitest';
import { BrewMethodStyle, CustomMethodLabelError } from '$lib/core/crema-core';
import { getHistoryStore } from '$lib/history';
import {
	getCustomMethodStore,
	isCustomMethodId,
	labelErrorText,
	methodIconKey,
	styleSeeds,
	type CustomMethodDraft
} from './custom-methods.svelte';
import {
	brewLogSeeds,
	brewMethodPresets,
	lastUsedMethod,
	methodLabel,
	methodLabelSnapshot,
	rememberMethod,
	shotMethodLabel
} from './methods';
import { newRecipeFor } from './recipes.svelte';

/*
 * The rules (style seeds, merged list, validation, the style-shaped first
 * recipe) are the core's — pinned by `de1_domain::brew_custom` tests. These
 * check the store, the wasm delegation and the label-resolution order the
 * web shares with Android.
 */
const draft = (label: string, extra: Partial<CustomMethodDraft> = {}): CustomMethodDraft => ({
	label,
	style: BrewMethodStyle.Percolation,
	icon: null,
	seedDoseG: null,
	seedWaterG: null,
	seedTempC: null,
	...extra
});

describe('custom brew methods', () => {
	beforeEach(() => {
		getCustomMethodStore().applyBackup([], true);
		localStorage.clear();
	});

	it('creates a custom: method that joins the pickers after the presets', () => {
		const store = getCustomMethodStore();
		const orb = store.create(draft('  ORB ', { icon: 'funnel', seedDoseG: 18 }))!;
		expect(orb.id).toMatch(/^custom:[0-9a-f-]{36}$/);
		expect(isCustomMethodId(orb.id)).toBe(true);
		expect(orb.label).toBe('ORB');
		// The style's default icon is stored blank; own seeds are kept.
		expect(orb.icon).toBeUndefined();
		expect(orb.seedDoseG).toBe(18);
		const presets = brewMethodPresets();
		const last = presets[presets.length - 1];
		expect(presets).toHaveLength(12);
		expect(last).toMatchObject({ id: orb.id, label: 'ORB', custom: true, seedDoseG: 18 });
		expect(last.seedWaterG).toBe(250);
		expect(presets[0]).toMatchObject({ id: 'espresso', label: 'Espresso', custom: false });
	});

	it('validates through the core against presets and live methods', () => {
		const store = getCustomMethodStore();
		expect(store.validate('   ').error).toBe(CustomMethodLabelError.Empty);
		expect(store.validate('x'.repeat(41)).error).toBe(CustomMethodLabelError.TooLong);
		expect(store.validate('chemex').error).toBe(CustomMethodLabelError.Duplicate);
		expect(store.validate('v60 / pourover').error).toBe(CustomMethodLabelError.Duplicate);
		const orb = store.create(draft('ORB'))!;
		expect(store.create(draft('orb'))).toBeNull();
		expect(store.validate('orb', orb.id).error).toBeNull();
		expect(labelErrorText(CustomMethodLabelError.Duplicate)).toMatch(/already/);
		store.remove(orb.id);
		expect(store.validate('ORB').error).toBeNull();
	});

	it('labels follow a rename; a deleted method keeps its name; snapshots are the fallback', () => {
		const store = getCustomMethodStore();
		const orb = store.create(draft('ORB'))!;
		expect(methodLabelSnapshot(orb.id)).toBe('ORB');
		expect(methodLabelSnapshot('pourover')).toBeNull();
		store.update(orb.id, draft('ORB v2'));
		expect(methodLabel(orb.id, 'ORB')).toBe('ORB v2');
		store.remove(orb.id);
		expect(brewMethodPresets().some((p) => p.id === orb.id)).toBe(false);
		expect(methodLabel(orb.id, 'ORB')).toBe('ORB v2');
		// Unknown to this device (restored elsewhere): the snapshot wins.
		expect(shotMethodLabel({ brewMethod: 'custom:lost', brewMethodLabel: 'Mine' })).toBe('Mine');
		expect(methodLabel('custom:lost')).toBe('Custom method');
		expect(methodLabel('karlsbad_kanne')).toBe('Karlsbad kanne');
		expect(methodLabel('pourover', 'ignored')).toBe('V60 / pourover');
	});

	it('seeds the log form and the first recipe from the method and its style', () => {
		const store = getCustomMethodStore();
		const press = store.create(
			draft('Bripe', { style: BrewMethodStyle.Pressure, seedWaterG: 200 })
		)!;
		expect(styleSeeds(BrewMethodStyle.Immersion).seedDoseG).toBe(30);
		expect(methodIconKey(press)).toBe('cylinder');
		const s = brewLogSeeds({
			method: press.id,
			beanId: null,
			beanGrinderSetting: null,
			prefill: undefined,
			rows: []
		});
		expect([s.method, s.dose, s.water, s.tempC]).toEqual([press.id, 14, 200, 90]);
		const r = newRecipeFor(press.id);
		expect(r.name).toBe('Bripe recipe');
		expect(r.method).toBe(press.id);
		expect((r.steps ?? []).map((x) => x.kind)).toEqual(['pour', 'steep', 'press']);
	});

	it('a deleted method no longer opens the log form', () => {
		const store = getCustomMethodStore();
		const orb = store.create(draft('ORB'))!;
		rememberMethod(orb.id);
		expect(lastUsedMethod()).toBe(orb.id);
		store.remove(orb.id);
		expect(lastUsedMethod()).toBeNull();
	});

	it('re-tags a free-text brew with a new method and its snapshot', () => {
		const history = getHistoryStore();
		const shot = history.addManualBrew({
			method: 'orb_brewer',
			completedAt: 1,
			bean: null,
			dose: null,
			waterG: null,
			yieldOut: null,
			grinderSetting: null,
			brewTempC: null,
			durationMs: null,
			rating: null,
			notes: null,
			nextPlan: null
		});
		expect(shot.brewMethodLabel).toBeUndefined();
		const orb = getCustomMethodStore().create(draft('Orb brewer'))!;
		history.retagMethod(shot.id, orb.id, orb.label);
		const after = history.get(shot.id)!;
		expect(after.brewMethod).toBe(orb.id);
		expect(after.brewMethodLabel).toBe('Orb brewer');
	});
});
