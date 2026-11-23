/**
 * Brew modes polish: Brew's "All recipes →" opens Profiles on its recipes
 * only (`?filter=recipes`), with a way back to the whole library.
 */

import '#lib/testing/dom-stubs.js';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import { page } from '$app/state';
import { initTestWasm } from '#lib/testing/test-init.js';
import WithAppContext from '#lib/testing/WithAppContext.svelte';
import { getProfileStore } from '#lib/profiles/index.js';
import ProfilesPage from './+page.svelte';

describe('the Profiles page recipes filter', () => {
	let component: ReturnType<typeof mount> | null = null;

	beforeEach(async () => {
		await initTestWasm();
		localStorage.clear();
		await getProfileStore().ensureLoaded();
	});

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
		page.url = new URL('http://localhost/');
	});

	async function open(url: string): Promise<HTMLElement> {
		page.url = new URL(url);
		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(WithAppContext, { target, props: { app: null, component: ProfilesPage } });
		flushSync();
		await tick();
		return target;
	}

	it('?filter=recipes shows the recipes and leaves the profiles out', async () => {
		const target = await open('http://localhost/profiles?filter=recipes');
		expect(target.querySelector('#recipes'), 'the recipes section').not.toBeNull();
		expect(target.querySelectorAll('.pp-recipe-card').length).toBeGreaterThan(0);
		expect(target.querySelector('.pp-grid'), 'no profile grid').toBeNull();
		expect(target.querySelector('.pp-filters'), 'no profile filters').toBeNull();
		expect(target.textContent).toContain('Showing recipes only');
		const back = [...target.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Show profiles too');
		expect(back, 'a way back to the whole library').toBeDefined();
	});

	it('without the filter the profiles and the recipes both show', async () => {
		const target = await open('http://localhost/profiles');
		expect(target.querySelector('.pp-grid')).not.toBeNull();
		expect(target.querySelector('#recipes')).not.toBeNull();
		expect(target.textContent).not.toContain('Showing recipes only');
	});

	it('an unknown filter is the whole library', async () => {
		const target = await open('http://localhost/profiles?filter=nonsense');
		expect(target.querySelector('.pp-grid')).not.toBeNull();
	});
});
