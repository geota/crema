/**
 * Quick Controls' Save writes each changed part to its owner (save-boundaries
 * review F1, Android #496 parity). Before, web wrote the grind to the bag
 * before asking for a name (a cancelled prompt kept it) and always created a
 * new profile. Since #488 the grind goes to the (bag, active grinder) row of
 * `Bean.grinds`, and the old `grinderSetting` is never written.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { blankBean, getBeanStore, type Bean } from '#lib/bean/index.js';
import {
	effectiveCremaProfile,
	getProfileStore,
	newProfileId,
	normalizeBrewOverrides,
	type CremaProfile
} from '#lib/profiles/index.js';
import {
	needsName,
	offersSaveAsNew,
	quickSavePlan,
	runQuickSave,
	saveEnabled,
	undoQuickSave,
	type QuickSaveDeps
} from './quick-save';

const beans = getBeanStore();
const NO_GRINDER = '';
const profiles = getProfileStore();
const deps: QuickSaveDeps = { beans, profiles, bake: effectiveCremaProfile, newId: newProfileId };

let builtin: CremaProfile;

/** The bag's saved grind for the active grinder (none: the "no grinder" slot). */
function saved(): string | undefined {
	const id = beans.activeGrinder?.id ?? NO_GRINDER;
	return beans.getBean('bean:a')!.grinds?.find((g) => g.grinderId === id)?.grind;
}

function bag(grind = '12'): Bean {
	const id = beans.activeGrinder?.id ?? NO_GRINDER;
	const grinds = grind ? [{ grinderId: id, grinderName: beans.activeGrinder?.name ?? '', grind, starred: false }] : [];
	beans.upsertBean({ ...blankBean('bean:a'), name: 'Guji', grinderSetting: 'old', grinds });
	return beans.getBean('bean:a')!;
}

function custom(): CremaProfile {
	const p: CremaProfile = { ...builtin, id: newProfileId(), name: 'Mine', source: 'custom', pinned: false };
	profiles.save(p);
	return profiles.get(p.id)!;
}

/** The plan and the run's options for a dialled dose on `profile`. */
function dialled(profile: CremaProfile | null, dial: number, dose?: number) {
	const overrides = profile && dose !== undefined ? normalizeBrewOverrides(profile, { dose }) : {};
	return {
		plan: quickSavePlan({ bean: beans.activeBean, grinder: beans.activeGrinder, brewGrind: dial, profile, overrides }),
		opts: { profile, overrides }
	};
}

beforeEach(async () => {
	localStorage.clear();
	beans.clearAll();
	await profiles.ensureLoaded();
	for (const p of profiles.all.filter((x) => x.source === 'custom')) profiles.delete(p.id);
	builtin = profiles.all.find((p) => p.source === 'builtin' && p.dose > 0)!;
	profiles.setActive(null);
});

describe('Quick Controls Save', () => {
	it('only the grind changed: saves just the grind to the bag, no profile, no prompt', () => {
		bag('12');
		beans.setActiveBean('bean:a');
		const count = profiles.all.length;
		const { plan, opts } = dialled(builtin, 12.5);
		expect(saveEnabled(plan)).toBe(true);
		expect(needsName(plan), 'a grind-only Save asks for no name').toBe(false);
		const r = runQuickSave(plan, deps, opts)!;
		expect(saved()).toBe('12.5');
		expect(beans.getBean('bean:a')!.grinderSetting, 'the old field is never written').toBe('old');
		expect(profiles.all.length, 'no profile copy').toBe(count);
		expect(r.message).toBe('Grind 12.5 saved to Guji');
		expect(r.undo).toBeNull();
	});

	it('saves to the active grinder\'s row, with its unit, and never moves the star', () => {
		const niche = beans.addGrinder('', 'bookoo-motto-80')!;
		beans.setActiveGrinder(niche.id);
		beans.upsertBean({
			...blankBean('bean:a'),
			name: 'Guji',
			grinds: [{ grinderId: 'grinder:other', grinderName: 'Other', grind: '9', starred: true }]
		});
		beans.setActiveBean('bean:a');
		const { plan, opts } = dialled(null, 480);
		const r = runQuickSave(plan, deps, opts)!;
		const rows = beans.getBean('bean:a')!.grinds!;
		expect(rows.find((g) => g.grinderId === niche.id)).toMatchObject({ grind: '480', starred: false });
		expect(rows.find((g) => g.grinderId === 'grinder:other')).toMatchObject({ grind: '9', starred: true });
		expect(r.message).toBe(`Grind 480 μm saved to Guji · ${niche.name}`);
	});

	it('nothing changed: Save is off', () => {
		bag('12');
		beans.setActiveBean('bean:a');
		expect(saveEnabled(dialled(builtin, 12).plan)).toBe(false);
		expect(saveEnabled(dialled(null, 12).plan)).toBe(false);
	});

	it('no grind on Brew has nothing to save', () => {
		bag('');
		beans.setActiveBean('bean:a');
		const plan = quickSavePlan({ bean: beans.activeBean, grinder: null, brewGrind: null, profile: null, overrides: {} });
		expect(saveEnabled(plan)).toBe(false);
		const moved = quickSavePlan({ bean: beans.activeBean, grinder: null, brewGrind: 4.4, profile: null, overrides: {} });
		expect(moved.grind).toBe('4.4');
	});

	it('a grind saved as "12,5" is the dial at 12.5', () => {
		bag('12,5');
		beans.setActiveBean('bean:a');
		expect(saveEnabled(dialled(null, 12.5).plan)).toBe(false);
	});

	it('no profile loaded: Save still saves the grind', () => {
		bag('12');
		beans.setActiveBean('bean:a');
		const { plan, opts } = dialled(null, 14);
		expect(saveEnabled(plan)).toBe(true);
		runQuickSave(plan, deps, opts);
		expect(saved()).toBe('14');
	});

	it('a custom profile is updated in place, with Undo, and Save as new is offered', () => {
		const mine = custom();
		const count = profiles.all.length;
		const { plan, opts } = dialled(mine, 4.2, mine.dose + 1);
		expect(needsName(plan)).toBe(false);
		expect(offersSaveAsNew(plan)).toBe(true);
		const r = runQuickSave(plan, deps, opts)!;
		expect(profiles.all.length, 'no copy').toBe(count);
		expect(profiles.get(mine.id)!.dose).toBe(mine.dose + 1);
		expect(r.message).toBe('“Mine” updated');
		const undone = undoQuickSave(r.undo!, deps);
		expect(undone.profileRestored).toBe(true);
		expect(profiles.get(mine.id)!.dose).toBe(mine.dose);
	});

	it('Undo after a later edit of the profile keeps that edit (REVIEW-496 M1)', () => {
		const mine = custom();
		const { plan, opts } = dialled(mine, 4.2, mine.dose + 1);
		const r = runQuickSave(plan, deps, opts)!;
		profiles.togglePin(mine.id);
		const undone = undoQuickSave(r.undo!, deps);
		expect(undone.profileRestored).toBe(false);
		expect(undone.message).toBe('“Mine” changed since; not undone');
		expect(profiles.get(mine.id)!.pinned, 'the later pin stands').toBe(true);
		expect(profiles.get(mine.id)!.dose).toBe(mine.dose + 1);
	});

	it('Undo puts the grind back only while it is still the one written', () => {
		bag('12');
		beans.setActiveBean('bean:a');
		const mine = custom();
		const first = dialled(mine, 13, mine.dose + 1);
		const r = runQuickSave(first.plan, deps, first.opts)!;
		expect(r.message).toBe('“Mine” updated · grind 13 saved to Guji');
		undoQuickSave(r.undo!, deps);
		expect(saved()).toBe('12');

		const again = dialled(profiles.get(mine.id)!, 13, mine.dose + 1);
		const r2 = runQuickSave(again.plan, deps, again.opts)!;
		beans.saveBagGrind('bean:a', null, '15');
		undoQuickSave(r2.undo!, deps);
		expect(saved()).toBe('15');
	});

	it('Save as new copies a custom profile and leaves it as it was', () => {
		const mine = custom();
		const { plan, opts } = dialled(mine, 4.2, mine.dose + 1);
		const r = runQuickSave(plan, deps, { ...opts, asNew: true, copyName: 'Mine strong' })!;
		expect(profiles.get(mine.id)!.dose).toBe(mine.dose);
		const copy = profiles.all.find((p) => p.name === 'Mine strong')!;
		expect(copy.dose).toBe(mine.dose + 1);
		expect(profiles.activeId).toBe(copy.id);
		expect(r.undo).toBeNull();
	});

	it('a built-in asks for a name: without one nothing is written, the grind included', () => {
		bag('12');
		beans.setActiveBean('bean:a');
		const count = profiles.all.length;
		const { plan, opts } = dialled(builtin, 13, builtin.dose + 1);
		expect(needsName(plan)).toBe(true);
		expect(offersSaveAsNew(plan)).toBe(false);
		expect(runQuickSave(plan, deps, { ...opts, copyName: null })).toBeNull();
		expect(saved(), 'a cancelled prompt writes no grind').toBe('12');
		expect(profiles.all.length).toBe(count);

		const r = runQuickSave(plan, deps, { ...opts, copyName: 'My blooming' })!;
		const copy = profiles.all.find((p) => p.name === 'My blooming')!;
		expect(copy.source).toBe('custom');
		expect(copy.dose).toBe(builtin.dose + 1);
		expect(profiles.activeId).toBe(copy.id);
		expect(saved()).toBe('13');
		expect(r.message).toBe('Saved “My blooming” · grind 13 saved to Guji');
	});
});
