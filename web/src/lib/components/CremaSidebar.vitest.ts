/**
 * The rail's Devices entry (brew-modes spec §1): Scale and Grinder when the
 * window is tall enough, else one Devices item opening the tab used last.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';

/** A `matchMedia` the tests can flip; `MediaQuery` reads it at import. */
const media = vi.hoisted(() => {
	const list = new EventTarget() as EventTarget & { matches: boolean; media: string };
	list.matches = true;
	list.media = '';
	(globalThis as { matchMedia?: unknown }).matchMedia = (query: string) => {
		list.media = query;
		return list;
	};
	return {
		list,
		set(matches: boolean) {
			list.matches = matches;
			list.dispatchEvent(new Event('change'));
		}
	};
});

const nav = vi.hoisted(() => ({
	after: [] as ((n: { to: { url: URL } | null }) => void)[],
	goto: vi.fn((_href: string) => Promise.resolve())
}));

vi.mock('$app/navigation', () => ({
	goto: nav.goto,
	afterNavigate: (fn: (n: { to: { url: URL } | null }) => void) => nav.after.push(fn),
	beforeNavigate: () => {}
}));

import { page } from '$app/state';
import CremaSidebar from './CremaSidebar.svelte';
import { RAIL_SPLIT_QUERY } from '#lib/shell/rail.svelte.js';
import { railItemActive, railItems } from '#lib/shell/nav-items.js';

/** Navigate as SvelteKit would: the URL, then the after-navigate hooks. */
function visit(href: string): void {
	const url = new URL(href, 'http://localhost');
	page.url = url;
	for (const fn of nav.after) fn({ to: { url } });
	flushSync();
}

describe('railItems', () => {
	it('splits Devices into Scale and Grinder, digits top to bottom', () => {
		expect(railItems(true, 'grinder').map((i) => `${i.kbd}:${i.label}:${i.href}`)).toEqual([
			'1:Brew:/',
			'2:Profiles:/profiles',
			'3:Beans:/beans',
			'4:History:/history',
			'5:Scale:/scale',
			'6:Grinder:/scale?tab=grinder',
			'7:Settings:/settings'
		]);
	});

	it('keeps one Devices item, on the last tab, when there is no room', () => {
		expect(railItems(false, 'grinder').map((i) => `${i.kbd}:${i.label}:${i.href}`)).toEqual([
			'1:Brew:/',
			'2:Profiles:/profiles',
			'3:Beans:/beans',
			'4:History:/history',
			'5:Devices:/scale?tab=grinder',
			'6:Settings:/settings'
		]);
		expect(railItems(false, 'scale').find((i) => i.label === 'Devices')?.href).toBe('/scale');
	});

	it('lights the split item for the tab on show only', () => {
		const [scale, grinder] = railItems(true, 'scale').filter((i) => i.devicesTab);
		const at = (href: string) => new URL(href, 'http://localhost');
		expect(railItemActive(scale, at('/scale'))).toBe(true);
		expect(railItemActive(scale, at('/scale?tab=scale'))).toBe(true);
		expect(railItemActive(grinder, at('/scale'))).toBe(false);
		expect(railItemActive(grinder, at('/scale?tab=grinder'))).toBe(true);
		expect(railItemActive(scale, at('/scale?tab=grinder'))).toBe(false);
		expect(railItemActive(scale, at('/settings'))).toBe(false);
	});

	it('lights the single Devices item on either tab', () => {
		const devices = railItems(false, 'grinder').find((i) => i.label === 'Devices')!;
		expect(railItemActive(devices, new URL('http://localhost/scale'))).toBe(true);
		expect(railItemActive(devices, new URL('http://localhost/scale?tab=grinder'))).toBe(true);
		expect(railItemActive(devices, new URL('http://localhost/'))).toBe(false);
	});
});

describe('CremaSidebar', () => {
	let component: ReturnType<typeof mount> | null = null;

	const links = () =>
		[...document.querySelectorAll<HTMLAnchorElement>('nav[aria-label="Primary"] .cside-items a')].map(
			(a) => ({
				label: a.textContent?.trim(),
				href: a.getAttribute('href'),
				current: a.getAttribute('aria-current') === 'page'
			})
		);
	const current = () => links().filter((l) => l.current).map((l) => l.label);

	function render(): void {
		const target = document.body.appendChild(document.createElement('div'));
		component = mount(CremaSidebar, { target, props: { app: null } });
		flushSync();
	}

	beforeEach(() => {
		media.set(true);
		visit('/');
	});

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		nav.after.length = 0;
		nav.goto.mockClear();
		document.body.innerHTML = '';
	});

	it('asks for the split height', () => {
		expect(media.list.media).toBe(RAIL_SPLIT_QUERY);
	});

	it('shows Scale and Grinder when tall, lighting the tab on show', () => {
		visit('/scale?tab=grinder');
		render();
		expect(links().map((l) => l.label)).toEqual([
			'Brew',
			'Profiles',
			'Beans',
			'History',
			'Scale',
			'Grinder',
			'Settings'
		]);
		expect(current()).toEqual(['Grinder']);
	});

	it('lights Scale on the bare Devices URL', () => {
		visit('/scale');
		render();
		expect(current()).toEqual(['Scale']);
	});

	it('collapses to one Devices item that opens the tab used last', () => {
		render();
		visit('/scale');
		media.set(false);
		flushSync();
		expect(links().map((l) => l.label)).toEqual(['Brew', 'Profiles', 'Beans', 'History', 'Devices', 'Settings']);
		const devices = () => links().find((l) => l.label === 'Devices')!;
		expect(devices().href).toBe('/scale');

		visit('/scale?tab=grinder');
		expect(devices().href).toBe('/scale?tab=grinder');

		// Another page keeps the memory.
		visit('/beans');
		expect(devices().href).toBe('/scale?tab=grinder');

		visit('/scale');
		visit('/history');
		expect(devices().href).toBe('/scale');
	});

	it('lights the single Devices item on either tab', () => {
		media.set(false);
		visit('/scale?tab=grinder');
		render();
		expect(current()).toEqual(['Devices']);
	});

	it('splits again when the window grows', () => {
		render();
		media.set(false);
		flushSync();
		media.set(true);
		flushSync();
		expect(links().map((l) => l.label)).toContain('Grinder');
	});

	it('maps the digits to the items on show', () => {
		render();
		window.dispatchEvent(new KeyboardEvent('keydown', { key: '6' }));
		expect(nav.goto).toHaveBeenLastCalledWith('/scale?tab=grinder');
		window.dispatchEvent(new KeyboardEvent('keydown', { key: '7' }));
		expect(nav.goto).toHaveBeenLastCalledWith('/settings');

		media.set(false);
		flushSync();
		window.dispatchEvent(new KeyboardEvent('keydown', { key: '6' }));
		expect(nav.goto).toHaveBeenLastCalledWith('/settings');
		nav.goto.mockClear();
		window.dispatchEvent(new KeyboardEvent('keydown', { key: '7' }));
		expect(nav.goto).not.toHaveBeenCalled();
	});
});
