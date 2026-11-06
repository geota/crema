/**
 * The phone top bar's contract with a route (`usePhoneBar`) and its Devices
 * sheet, which must act like the rail's device buttons.
 */
import '#lib/testing/dom-stubs.js';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount, type Component } from 'svelte';
import XIcon from 'phosphor-svelte/lib/XIcon';
import type { CremaApp } from '#lib/state/index.js';
import PhoneTopBar from './PhoneTopBar.svelte';
import PhoneBarRoute from '#lib/testing/PhoneBarRoute.svelte';
import { phoneBarConfig, type PhoneBarConfig } from './phone-bar.svelte';

// jsdom has no Web Animations, which the sheet's transition starts on open.
Element.prototype.animate = vi.fn(
	() => ({ cancel: vi.fn(), finish: vi.fn() }) as unknown as Animation
);

describe('PhoneTopBar', () => {
	const mounted: ReturnType<typeof mount>[] = [];

	afterEach(() => {
		while (mounted.length) unmount(mounted.pop()!);
		document.body.innerHTML = '';
	});

	function render(state: Record<string, unknown> = {}) {
		const target = document.body.appendChild(document.createElement('div'));
		const app = {
			state: {
				current: { de1State: 'idle', scaleState: 'idle', de1Diagnostics: {}, ...state }
			},
			connectDe1: vi.fn(),
			connectScale: vi.fn(),
			disconnectDe1: vi.fn(),
			disconnectScale: vi.fn(),
			retryReconnect: vi.fn()
		};
		mounted.push(mount(PhoneTopBar, { target, props: { app: app as unknown as CremaApp } }));
		flushSync();
		return { app, target };
	}
	const route = (props: { config: () => PhoneBarConfig }) => {
		const c = mount(PhoneBarRoute, { target: document.body, props });
		flushSync();
		return c;
	};
	const labels = (root: Element) =>
		[...root.querySelectorAll('.ptop-action')].map((a) => a.getAttribute('aria-label'));
	const button = (name: string): HTMLButtonElement => {
		const found = [...document.querySelectorAll('button')].find(
			(b) => b.textContent?.trim() === name
		);
		if (!found) throw new Error(`no ${name} button`);
		return found;
	};
	function openDevices(): void {
		document.querySelector<HTMLButtonElement>('.ptop-action[aria-label^="Devices"]')!.click();
		flushSync();
	}

	it('shows the nav title and the shared actions with no route config', () => {
		const { target } = render();
		expect(target.querySelector('.ptop-title')?.textContent).toBe('Brew');
		expect(labels(target)).toEqual(['Devices', 'Settings']);
	});

	it('keeps the title out of the heading outline', () => {
		const { target } = render();
		expect(target.querySelector('h1')).toBeNull();
		expect(target.querySelector('.ptop-title')?.getAttribute('aria-hidden')).toBe('true');
	});

	it('takes the title and actions from the route, and gives them back on unmount', () => {
		const { target } = render();
		const onclick = vi.fn();
		const r = route({
			config: () => ({
				title: 'Beans',
				actions: [{ id: 'x', label: 'Import', icon: XIcon as Component, onclick }, 'settings']
			})
		});
		flushSync();
		expect(target.querySelector('.ptop-title')?.textContent).toBe('Beans');
		expect(labels(target)).toEqual(['Import', 'Settings']);
		target.querySelector<HTMLButtonElement>('[aria-label="Import"]')!.click();
		expect(onclick).toHaveBeenCalledOnce();

		unmount(r);
		flushSync();
		expect(phoneBarConfig()).toBeNull();
		expect(target.querySelector('.ptop-title')?.textContent).toBe('Brew');
	});

	it('a route unmounting late does not clear the next route', () => {
		render();
		const first = route({ config: () => ({ title: 'One' }) });
		route({ config: () => ({ title: 'Two' }) });
		unmount(first);
		flushSync();
		expect(phoneBarConfig()?.title).toBe('Two');
	});

	it('Pair connects the device from the Devices sheet', () => {
		const { app } = render();
		openDevices();
		document.querySelector<HTMLButtonElement>('[aria-label="Pair machine"]')!.click();
		expect(app.connectDe1).toHaveBeenCalledOnce();
		document.querySelector<HTMLButtonElement>('[aria-label="Pair scale"]')!.click();
		expect(app.connectScale).toHaveBeenCalledOnce();
	});

	it('a connected device offers Disconnect, a reconnecting one Retry now', () => {
		const { app } = render({ de1State: 'ready', scaleState: 'reconnecting', scaleName: 'Lunar' });
		openDevices();
		expect(document.body.textContent).toContain('Lunar');
		button('Disconnect').click();
		expect(app.disconnectDe1).toHaveBeenCalledOnce();
		button('Retry now').click();
		expect(app.retryReconnect).toHaveBeenCalledWith('scale');
	});

	it('nothing to tap while a device is connecting', () => {
		const { app } = render({ de1State: 'connecting' });
		openDevices();
		const pair = document.querySelector<HTMLButtonElement>('[aria-label="Pair machine"]')!;
		expect(pair.disabled).toBe(true);
		expect(app.connectDe1).not.toHaveBeenCalled();
	});

	it('the Bluetooth action turns copper when the DE1 is connected', () => {
		const { target } = render({ de1State: 'ready' });
		const action = target.querySelector('.ptop-action[aria-label^="Devices"]')!;
		expect(action.getAttribute('aria-label')).toBe('Devices, DE1 connected');
		expect(action.classList.contains('is-accent')).toBe(true);
	});
});
