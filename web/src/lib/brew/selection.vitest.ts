/**
 * Brew's selection (brew modes §2, §4): the mode follows the selection, the
 * recipe lives in the key beside the profile key, and the picker locks
 * while something runs. The rules are the core's; these check the shell
 * stores and reads them as it should.
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BrewMode, BrewPickLockReason, BrewSelectionKind } from '#lib/core/crema-core.js';

const PROFILE_KEY = 'crema.profiles.activeId.v1';
const RECIPE = 'builtin:hoffmann-1-cup-v60';

/** Fresh modules, so each test reads storage as a new boot does. */
async function boot() {
	vi.resetModules();
	// The fresh wasm bridge needs its module again (vitest.setup.ts does the first).
	(await import('#lib/wasm/de1_wasm.js')).initSync({
		module: readFileSync(path.resolve('src/lib/wasm/de1_wasm_bg.wasm'))
	});
	const sel = await import('./selection.svelte');
	const { getProfileStore } = await import('#lib/profiles/index.js');
	const { getRecipeStore } = await import('./recipes.svelte');
	return { sel, store: sel.getBrewSelectionStore(), profiles: getProfileStore(), recipes: getRecipeStore() };
}

describe('Brew selection', () => {
	beforeEach(() => localStorage.clear());

	it('opens a fresh install in Espresso with nothing selected', async () => {
		const { store } = await boot();
		expect(store.mode).toBe(BrewMode.Espresso);
		expect(store.view.selection ?? null).toBeNull();
		expect(store.recipe).toBeNull();
	});

	it('reads a profile stored before brew modes as Profile(id)', async () => {
		localStorage.setItem(PROFILE_KEY, JSON.stringify('p-1'));
		const { store } = await boot();
		expect(store.view.selection).toEqual({ kind: BrewSelectionKind.Profile, id: 'p-1' });
		expect(store.mode).toBe(BrewMode.Espresso);
	});

	it('picks a recipe into the sibling key, keeping the profile, and reopens on it', async () => {
		localStorage.setItem(PROFILE_KEY, JSON.stringify('p-1'));
		const first = await boot();
		first.store.pickRecipe(RECIPE);
		expect(first.store.mode).toBe(BrewMode.Manual);
		expect(first.store.recipe?.id).toBe(RECIPE);
		expect(first.store.summary?.name).toBe(first.store.recipe?.name);
		expect(JSON.parse(localStorage.getItem(first.sel.RECIPE_KEY)!)).toBe(RECIPE);
		expect(JSON.parse(localStorage.getItem(PROFILE_KEY)!)).toBe('p-1');

		const again = await boot();
		expect(again.store.mode, 'the restart comes back in Manual').toBe(BrewMode.Manual);
		expect(again.store.recipe?.id).toBe(RECIPE);
	});

	it('picks a profile: Espresso, the recipe cleared', async () => {
		const { store, profiles, sel } = await boot();
		store.pickRecipe(RECIPE);
		store.pickProfile('p-2');
		expect(store.mode).toBe(BrewMode.Espresso);
		expect(store.recipeId).toBeNull();
		expect(profiles.activeId).toBe('p-2');
		expect(JSON.parse(localStorage.getItem(sel.RECIPE_KEY)!)).toBeNull();
	});

	it('keeps Manual when the profile key changes some other way', async () => {
		const { store, profiles } = await boot();
		store.pickRecipe(RECIPE);
		profiles.setActive('p-3');
		expect(store.mode).toBe(BrewMode.Manual);
	});

	it('falls back to the profile while the recipe is gone, without forgetting it', async () => {
		const { store, recipes, sel } = await boot();
		const own = recipes.duplicate(RECIPE)!;
		store.pickRecipe(own.id);
		expect(store.mode).toBe(BrewMode.Manual);
		recipes.remove(own.id);
		expect(store.mode).toBe(BrewMode.Espresso);
		expect(JSON.parse(localStorage.getItem(sel.RECIPE_KEY)!)).toBe(own.id);
	});
});

describe('the picker lock', () => {
	it('is off while nothing runs', async () => {
		const { sel } = await boot();
		expect(sel.brewPickLock({ shotRunning: false, guidedBrewRunning: false })).toBeNull();
	});

	it('locks the profile rows on a shot, every row on a guided brew', async () => {
		const { sel } = await boot();
		expect(sel.brewPickLock({ shotRunning: true, guidedBrewRunning: false })).toEqual({
			reason: BrewPickLockReason.Shot,
			recipesLocked: false,
			note: 'Finish the current shot first'
		});
		expect(sel.brewPickLock({ shotRunning: false, guidedBrewRunning: true })).toMatchObject({
			reason: BrewPickLockReason.GuidedBrew,
			recipesLocked: true,
			note: 'Finish the current brew first'
		});
	});

	it('counts armed, running and paused as a guided brew running, not idle or done', async () => {
		const { sel } = await boot();
		expect(['idle', 'armed', 'running', 'paused', 'done'].map((p) => sel.guidedBrewRunning(p as never))).toEqual([
			false,
			true,
			true,
			true,
			false
		]);
	});
});
