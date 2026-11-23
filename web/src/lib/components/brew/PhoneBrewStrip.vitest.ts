/**
 * The phone Brew dropdown (brew modes §2, owner revision): a search box
 * first, then Espresso profiles and Manual recipes (three recent each), then
 * Bean and Grinder. Typing searches all four, grouped by type, top three
 * with "n more", matches highlighted, ✕ back to the sections. Locked rows
 * are disabled (§4).
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import PhoneBrewStrip, { type StripProfileRow, type StripRecipeRow } from './PhoneBrewStrip.svelte';

const profile = (id: string, name: string): StripProfileRow => ({ id, name, meta: '1:2 · 18.0 g · 93.0 °C', segments: [] });
const recipe = (id: string, name: string, method = 'aeropress'): StripRecipeRow => ({
	id,
	name,
	meta: `${method} · 15 g · 250 g`,
	method,
	search: method
});

const ALL_PROFILES = [
	profile('p1', 'A-Flow / default-dark'),
	profile('p2', 'A-Flow / default-very-dark'),
	profile('p3', 'Blooming espresso'),
	profile('p4', 'Dark roast long'),
	profile('p5', 'Darker still')
];
const ALL_RECIPES = [recipe('r1', '1 Cup V60', 'pourover'), recipe('r2', 'Ultimate AeroPress'), recipe('r3', 'Official AeroPress')];

describe('PhoneBrewStrip dropdown', () => {
	let component: ReturnType<typeof mount> | null = null;

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	function render(props: Record<string, unknown> = {}) {
		const handlers = {
			onSelectProfile: vi.fn(),
			onSelectRecipe: vi.fn(),
			onSelectBean: vi.fn(),
			onSelectGrinder: vi.fn(),
			onAllProfiles: vi.fn(),
			onAllRecipes: vi.fn(),
			onAllBeans: vi.fn()
		};
		const target = document.body.appendChild(document.createElement('div'));
		component = mount(PhoneBrewStrip, {
			target,
			props: {
				title: 'A-Flow / default-dark',
				beanLine: 'House dark roast',
				profiles: ALL_PROFILES.slice(0, 3),
				allProfiles: ALL_PROFILES,
				recipes: ALL_RECIPES,
				allRecipes: ALL_RECIPES,
				beans: [{ id: 'b1', name: 'House dark roast', meta: '214 g left', mark: 'H', tone: '#c80' }],
				grinders: [{ id: 'g1', name: 'Niche Zero', line: 'Saved 12', starred: false }],
				activeProfileId: 'p1',
				...handlers,
				...props
			}
		});
		flushSync();
		target.querySelector<HTMLButtonElement>('.pstrip')!.click();
		flushSync();
		return { target, handlers };
	}

	const titles = (root: ParentNode) => [...root.querySelectorAll('.pdd-title')].map((t) => t.textContent?.trim());
	const names = (root: ParentNode) => [...root.querySelectorAll('.pdd-row .pdd-name')].map((n) => n.textContent?.trim());
	function type(root: HTMLElement, text: string): void {
		const input = root.querySelector<HTMLInputElement>('.pdd-search input')!;
		input.value = text;
		input.dispatchEvent(new Event('input', { bubbles: true }));
		flushSync();
	}
	const buttonNamed = (root: ParentNode, label: string) =>
		[...root.querySelectorAll('button')].find((b) => b.textContent?.replace(/\s+/g, ' ').trim() === label);

	it('shows the search, then profiles, recipes, bean and grinder', () => {
		const { target, handlers } = render();
		expect(target.querySelector('.pdd-search input')?.getAttribute('aria-label')).toBe(
			'Search profiles, recipes, beans and grinders'
		);
		expect(titles(target)).toEqual(['Espresso profiles', 'Manual recipes', 'Bean', 'Grinder']);
		buttonNamed(target, 'All recipes')!.click();
		expect(handlers.onAllRecipes).toHaveBeenCalled();
	});

	it('picks a recipe', () => {
		const { target, handlers } = render();
		[...target.querySelectorAll<HTMLButtonElement>('.pdd-row')]
			.find((b) => b.textContent?.includes('Ultimate AeroPress'))!
			.click();
		flushSync();
		expect(handlers.onSelectRecipe).toHaveBeenCalledWith('r2');
		expect(target.querySelector('.pstrip-dd'), 'the dropdown closes').toBeNull();
	});

	it('searches every type, grouped, top three with n more, matches highlighted', () => {
		const { target } = render();
		type(target, 'dark');
		expect(titles(target)).toEqual(['Espresso profiles · 4', 'Beans · 1']);
		expect(names(target)).toEqual([
			'A-Flow / default-dark',
			'A-Flow / default-very-dark',
			'Dark roast long',
			'House dark roast'
		]);
		expect([...target.querySelectorAll('.pdd-hit')].map((b) => b.textContent)).toEqual(['dark', 'dark', 'Dark', 'dark']);
		expect(target.querySelector('.pdd-nomatch')?.textContent?.trim()).toBe('No recipes or grinders match “dark”');

		buttonNamed(target, '1 more profile')!.click();
		flushSync();
		expect(names(target)).toContain('Darker still');
		expect(buttonNamed(target, '1 more profile')).toBeUndefined();
	});

	it('finds recipes by method, and ✕ goes back to the sections', () => {
		const { target } = render();
		type(target, 'pourover');
		expect(titles(target)[0]).toBe('Manual recipes · 1');
		target.querySelector<HTMLButtonElement>('button[aria-label="Clear search"]')!.click();
		flushSync();
		expect(titles(target)).toEqual(['Espresso profiles', 'Manual recipes', 'Bean', 'Grinder']);
		expect(target.querySelector<HTMLInputElement>('.pdd-search input')!.value).toBe('');
	});

	it('disables the locked rows, with the note', () => {
		const { target, handlers } = render({ profilesLocked: 'Finish the current shot first' });
		expect(target.querySelector('.pdd-locked')?.textContent).toBe('Finish the current shot first');
		const rows = [...target.querySelectorAll<HTMLButtonElement>('.pdd-row')];
		const profileRow = rows.find((b) => b.textContent?.includes('Blooming espresso'))!;
		expect(profileRow.disabled).toBe(true);
		profileRow.click();
		expect(handlers.onSelectProfile).not.toHaveBeenCalled();
		expect(rows.find((b) => b.textContent?.includes('1 Cup V60'))!.disabled).toBe(false);
	});

	it('marks the recipe as the selection in Manual, not the profile', () => {
		const { target } = render({ activeRecipeId: 'r1' });
		const active = [...target.querySelectorAll('.pdd-row.is-active .pdd-name')].map((n) => n.textContent?.trim());
		expect(active).toEqual(['1 Cup V60']);
	});
});
