/**
 * Brew's Manual mode on the wide dashboard (brew modes §2–§4): the Profile
 * picker lists Espresso profiles then Manual recipes; picking a recipe turns
 * Brew into the guided brew, picking a profile turns it back; the rows lock
 * while a shot or a brew runs.
 */
import '#lib/testing/dom-stubs.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import WithAppContext from '#lib/testing/WithAppContext.svelte';
import BrewDashboard from './BrewDashboard.svelte';
import { getProfileStore } from '#lib/profiles/index.js';
import { getCremaUiState } from '#lib/state/ui-state.svelte.js';
import type { CremaApp, UiSnapshot } from '#lib/state/index.js';
import { getBrewSelectionStore } from '#lib/brew/selection.svelte.js';
import { getGuidedBrewStore } from '#lib/brew/session.svelte.js';
import { getRecipeStore } from '#lib/brew/recipes.svelte.js';

vi.mock('#lib/shell/phone.svelte.js', () => ({ phoneLayout: { current: false } }));

const RECIPE = 'builtin:hoffmann-1-cup-v60';

describe('Brew in Manual mode (wide)', () => {
	let component: ReturnType<typeof mount> | null = null;
	let app: CremaApp & {
		brewSessionArm: ReturnType<typeof vi.fn>;
		brewSessionBegin: ReturnType<typeof vi.fn>;
	};

	beforeEach(async () => {
		await getProfileStore().ensureLoaded();
		getProfileStore().setActive(getProfileStore().all[0].id);
		getGuidedBrewStore().reset();
	});

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
		getGuidedBrewStore().reset();
	});

	async function render(ui: Partial<UiSnapshot> = {}): Promise<HTMLElement> {
		app = {
			brewOverridesFor: () => ({}),
			setBrewOverrides: vi.fn(),
			state: getCremaUiState(),
			brewSessionArm: vi.fn(async () => {}),
			brewSessionBegin: vi.fn(async () => {})
		} as unknown as typeof app;
		const target = document.body.appendChild(document.createElement('div'));
		component = mount(WithAppContext, {
			target,
			props: { app, component: BrewDashboard, props: { ui: { ...getCremaUiState().current, ...ui } } }
		});
		flushSync();
		await tick();
		return target;
	}

	const text = (root: ParentNode, sel: string) => root.querySelector(sel)?.textContent?.replace(/\s+/g, ' ').trim();
	const buttons = (root: ParentNode) => [...root.querySelectorAll('button')];
	const button = (root: ParentNode, label: string) =>
		buttons(root).find((b) => b.textContent?.replace(/\s+/g, ' ').trim() === label);
	function openPicker(root: HTMLElement): HTMLElement {
		root.querySelector<HTMLElement>('.bh-block-profile .bh-block-tap')!.click();
		flushSync();
		return root.querySelector<HTMLElement>('.hpick')!;
	}
	const section = (root: ParentNode, id: 'profiles' | 'recipes') =>
		[...root.querySelectorAll<HTMLElement>('.hpick-section')][id === 'profiles' ? 0 : 1];
	const rowNamed = (root: ParentNode, name: string) =>
		buttons(root).find((b) => b.querySelector('.hpick-primary')?.textContent === name);

	it('shows the recipe, its steps, Duplicate to edit, Hot water and Start brew', async () => {
		getBrewSelectionStore().pickRecipe(RECIPE);
		const recipe = getRecipeStore().get(RECIPE)!;
		const root = await render();

		expect(text(root, '.bh-block-profile .t-eyebrow')).toMatch(/^Recipe · /);
		expect(text(root, '.bh-block-profile .bh-title')).toBe(recipe.name);
		expect(button(root, 'Duplicate to edit'), 'Quick Controls gives way').toBeDefined();
		expect(button(root, 'Quick Controls')).toBeUndefined();

		expect(root.querySelectorAll('.ms-step')).toHaveLength(recipe.steps!.length);
		expect(text(root, '.msc-eyebrow')).toBe(`Step 1 of ${recipe.steps!.length} · Auto`);

		const foot = root.querySelector<HTMLElement>('.crema-foot-actions')!;
		expect(foot.textContent).toContain('Hot water');
		expect(foot.textContent).not.toContain('Steam');
		expect(foot.textContent).not.toContain('Flush');
		expect(button(foot, 'Start brew')).toBeDefined();
		expect(button(foot, 'Coffee')).toBeUndefined();
	});

	it('lists Espresso profiles then Manual recipes, and a profile pick goes back to Espresso', async () => {
		getBrewSelectionStore().pickRecipe(RECIPE);
		const root = await render();
		const picker = openPicker(root);
		expect(root.querySelector('[role="tab"]'), 'no tabs').toBeNull();
		expect([...picker.querySelectorAll('.hpick-section-head')].map((h) => h.textContent)).toEqual([
			'Espresso profiles',
			'Manual recipes'
		]);
		expect(text(section(picker, 'recipes'), '.hpick-item.is-active .hpick-primary')).toBe(getRecipeStore().get(RECIPE)!.name);
		expect(section(picker, 'profiles').querySelector('.hpick-item.is-active'), 'no profile is the selection').toBeNull();

		const other = getProfileStore().all[1];
		rowNamed(section(picker, 'profiles'), other.name)!.click();
		flushSync();

		expect(getBrewSelectionStore().manual).toBe(false);
		expect(getProfileStore().activeId).toBe(other.id);
		expect(text(root, '.bh-block-profile .t-eyebrow')).toBe('Profile');
		expect(button(root, 'Quick Controls')).toBeDefined();
	});

	it('picks a recipe from the Manual recipes section, and the search filters both', async () => {
		getBrewSelectionStore().pickProfile(getProfileStore().all[0].id);
		const root = await render();
		const picker = openPicker(root);
		const recipe = getRecipeStore().get(RECIPE)!;
		const input = picker.querySelector<HTMLInputElement>('input')!;
		input.value = 'aeropress';
		input.dispatchEvent(new Event('input', { bubbles: true }));
		flushSync();
		const recipeRows = [...section(picker, 'recipes').querySelectorAll('.hpick-primary')].map((e) => e.textContent);
		expect(recipeRows.length).toBeGreaterThan(0);
		expect(recipeRows.every((n) => /aeropress/i.test(n ?? ''))).toBe(true);
		expect(section(picker, 'profiles').querySelector('.hpick-empty')?.textContent).toBe('No matches');

		input.value = '';
		input.dispatchEvent(new Event('input', { bubbles: true }));
		flushSync();
		rowNamed(section(picker, 'recipes'), recipe.name)!.click();
		flushSync();
		expect(getBrewSelectionStore().recipe?.id).toBe(RECIPE);
		expect(button(root, 'Start brew')).toBeDefined();
	});

	it('starts the brew, and locks every row while it runs', async () => {
		getBrewSelectionStore().pickRecipe(RECIPE);
		const root = await render();
		button(root, 'Start brew')!.click();
		await tick();
		await tick();
		expect(app.brewSessionArm).toHaveBeenCalledWith(getRecipeStore().get(RECIPE), false);
		expect(app.brewSessionBegin).toHaveBeenCalled();

		// The core's events, as CremaApp routes them.
		getGuidedBrewStore().armed(getRecipeStore().get(RECIPE)!, false);
		getGuidedBrewStore().started(performance.now());
		flushSync();
		expect(button(root, 'Finish')).toBeDefined();
		expect(button(root, 'Duplicate to edit')?.disabled).toBe(true);

		const picker = openPicker(root);
		for (const id of ['profiles', 'recipes'] as const) {
			expect(text(section(picker, id), '.hpick-locked')).toBe('Finish the current brew first');
		}
		expect(buttons(picker).filter((b) => b.classList.contains('hpick-item')).every((b) => b.disabled)).toBe(true);
	});

	it('locks only the profile rows while a shot runs', async () => {
		getBrewSelectionStore().pickProfile(getProfileStore().all[0].id);
		const root = await render({ shotInProgress: true });
		const picker = openPicker(root);
		expect(text(section(picker, 'profiles'), '.hpick-locked')).toBe('Finish the current shot first');
		expect(section(picker, 'profiles').querySelector<HTMLButtonElement>('.hpick-item')!.disabled).toBe(true);
		expect(section(picker, 'recipes').querySelector('.hpick-locked')).toBeNull();
		expect(section(picker, 'recipes').querySelector<HTMLButtonElement>('.hpick-item')!.disabled).toBe(false);
	});
});
