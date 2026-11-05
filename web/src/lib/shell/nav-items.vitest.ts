import { afterEach, describe, expect, it } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { page } from '$app/state';
import { NAV_ITEMS, PHONE_TABS, showsPhoneTabs, tabIsActive } from './nav-items';
import PhoneTabBar from './PhoneTabBar.svelte';

describe('nav items', () => {
	it('keeps the rail order and its digits', () => {
		expect(NAV_ITEMS.map((i) => `${i.kbd}:${i.id}`)).toEqual([
			'1:brew',
			'2:profiles',
			'3:beans',
			'4:history',
			'5:scale',
			'6:settings'
		]);
	});

	it('orders the tabs as Android does, without Settings', () => {
		expect(PHONE_TABS.map((t) => t.label)).toEqual(['Brew', 'Scale', 'Profiles', 'Beans', 'History']);
	});

	it('lights a tab on its pushed routes, and Brew only on /', () => {
		const tab = (id: string) => PHONE_TABS.find((t) => t.id === id)!;
		expect(tabIsActive(tab('beans'), '/beans')).toBe(true);
		expect(tabIsActive(tab('beans'), '/beans/new')).toBe(true);
		expect(tabIsActive(tab('beans'), '/beansy')).toBe(false);
		expect(tabIsActive(tab('brew'), '/')).toBe(true);
		expect(tabIsActive(tab('brew'), '/beans')).toBe(false);
	});

	it.each([
		['/', true],
		['/beans', true],
		['/history', true],
		['/profiles', true],
		['/scale', true],
		['/settings', true],
		[null, true],
		['/profiles/new', false],
		['/profiles/[id]/edit', false],
		['/beans/new', false],
		['/beans/[id]/edit', false],
		['/beans/roasters/new', false],
		['/beans/roasters/[id]/edit', false]
	])('tab bar on route %s: %s', (route, shown) => {
		expect(showsPhoneTabs(route)).toBe(shown);
	});
});

describe('PhoneTabBar', () => {
	let component: ReturnType<typeof mount> | null = null;

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	it('is a Primary nav of plain links, with the current page marked', () => {
		page.url = new URL('http://localhost/beans/new');
		const target = document.body.appendChild(document.createElement('div'));
		component = mount(PhoneTabBar, { target });
		flushSync();
		const nav = target.querySelector('nav');
		expect(nav?.getAttribute('aria-label')).toBe('Primary');
		expect(target.querySelector('[role="tablist"]')).toBeNull();
		const links = [...target.querySelectorAll('a')];
		expect(links.map((a) => a.textContent?.trim())).toEqual([
			'Brew',
			'Scale',
			'Profiles',
			'Beans',
			'History'
		]);
		expect(links.filter((a) => a.getAttribute('aria-current') === 'page').map((a) => a.textContent?.trim())).toEqual(['Beans']);
	});
});
