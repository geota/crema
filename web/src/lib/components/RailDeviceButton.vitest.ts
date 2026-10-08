import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import type { BleConnectionState } from '$lib/ble/connection-state';
import RailDeviceButton from './RailDeviceButton.svelte';
import { railAction, railDotClass, railLabel, railStatus } from './rail-status';

describe('rail status policy', () => {
	const cases: [BleConnectionState, string, string, string][] = [
		['idle', 'disconnected', 'off', 'connect'],
		['disconnected', 'disconnected', 'off', 'connect'],
		['failed', 'disconnected', 'off', 'connect'],
		['connecting', 'connecting', 'is-busy', 'none'],
		['subscribing', 'connecting', 'is-busy', 'none'],
		['reconnecting', 'reconnecting', 'is-busy', 'retry'],
		['ready', 'connected', 'is-on', 'menu']
	];
	it.each(cases)('%s → %s, dot %s, tap %s', (state, status, dot, action) => {
		const s = railStatus(state);
		expect(s).toBe(status);
		expect(railDotClass(s)).toBe(dot);
		expect(railAction(s)).toBe(action);
	});

	it('labels each state', () => {
		expect(railLabel('de1', 'disconnected')).toBe('Click to connect DE1');
		expect(railLabel('de1', 'reconnecting')).toBe('DE1 reconnecting — click to retry now');
		expect(railLabel('de1', 'connected')).toBe('DE1 connected — click for options');
		expect(railLabel('scale', 'disconnected')).toBe('Click to connect scale');
		expect(railLabel('scale', 'connecting')).toBe('Scale connecting…');
	});
});

describe('RailDeviceButton', () => {
	let component: ReturnType<typeof mount> | null = null;

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	function render(linkState: BleConnectionState) {
		const onConnect = vi.fn();
		const onRetry = vi.fn();
		const onDisconnect = vi.fn();
		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(RailDeviceButton, {
			target,
			props: { device: 'de1', linkState, deviceName: 'DE1-ABC', onConnect, onRetry, onDisconnect }
		});
		flushSync();
		const button = target.querySelector<HTMLButtonElement>('button.cside-status')!;
		const dot = button.querySelector<HTMLElement>('.cside-status-dot')!;
		const click = (el: HTMLElement): void => {
			el.click();
			flushSync();
		};
		return { target, button, dot, click, onConnect, onRetry, onDisconnect };
	}

	it('disconnected: dot off, tap connects', () => {
		const r = render('idle');
		expect(r.dot.classList.contains('off')).toBe(true);
		expect(r.button.getAttribute('aria-label')).toBe('Click to connect DE1');
		r.click(r.button);
		expect(r.onConnect).toHaveBeenCalledOnce();
		expect(r.onDisconnect).not.toHaveBeenCalled();
		expect(r.target.querySelector('[role="menu"]')).toBeNull();
	});

	it('connecting: dot amber, tap does nothing', () => {
		const r = render('connecting');
		expect(r.dot.classList.contains('is-busy')).toBe(true);
		r.click(r.button);
		expect(r.onConnect).not.toHaveBeenCalled();
		expect(r.onRetry).not.toHaveBeenCalled();
		expect(r.onDisconnect).not.toHaveBeenCalled();
		expect(r.target.querySelector('[role="menu"]')).toBeNull();
	});

	it('reconnecting: dot amber (not green), tap retries — never disconnects', () => {
		const r = render('reconnecting');
		expect(r.dot.classList.contains('is-busy')).toBe(true);
		expect(r.dot.classList.contains('off')).toBe(false);
		expect(r.button.title).toBe('DE1 reconnecting — click to retry now');
		r.click(r.button);
		expect(r.onRetry).toHaveBeenCalledOnce();
		expect(r.onDisconnect).not.toHaveBeenCalled();
	});

	it('connected: tap opens the menu, Disconnect in it disconnects', () => {
		const r = render('ready');
		expect(r.dot.classList.contains('off')).toBe(false);
		expect(r.dot.classList.contains('is-busy')).toBe(false);
		expect(r.button.getAttribute('aria-expanded')).toBe('false');
		r.click(r.button);
		// One tap only opens the menu.
		expect(r.onDisconnect).not.toHaveBeenCalled();
		const menu = r.target.querySelector<HTMLElement>('[role="menu"]')!;
		expect(menu).not.toBeNull();
		expect(r.button.getAttribute('aria-expanded')).toBe('true');
		expect(menu.textContent).toContain('DE1-ABC');
		expect(menu.textContent).toContain('Connected');
		const item = menu.querySelector<HTMLButtonElement>('[role="menuitem"]')!;
		expect(item.textContent).toContain('Disconnect');
		r.click(item);
		expect(r.onDisconnect).toHaveBeenCalledOnce();
		expect(r.target.querySelector('[role="menu"]')).toBeNull();
	});

	it('connected: Esc closes the menu and returns focus to the button', async () => {
		const r = render('ready');
		r.click(r.button);
		const menu = r.target.querySelector<HTMLElement>('[role="menu"]')!;
		// Focus moves into the menu on open.
		await vi.waitFor(() => expect(document.activeElement?.getAttribute('role')).toBe('menuitem'));
		menu.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
		flushSync();
		expect(r.target.querySelector('[role="menu"]')).toBeNull();
		expect(r.button.getAttribute('aria-expanded')).toBe('false');
		expect(document.activeElement).toBe(r.button);
		expect(r.onDisconnect).not.toHaveBeenCalled();
	});

	it('connected: a click outside closes the menu', () => {
		const r = render('ready');
		r.click(r.button);
		expect(r.target.querySelector('[role="menu"]')).not.toBeNull();
		r.click(document.body);
		expect(r.target.querySelector('[role="menu"]')).toBeNull();
		expect(r.onDisconnect).not.toHaveBeenCalled();
	});
});
