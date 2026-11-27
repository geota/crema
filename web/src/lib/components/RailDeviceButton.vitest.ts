import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import type { BleConnectionState } from '#lib/ble/connection-state.js';
import { initTestWasm } from '#lib/testing/test-init.js';
import { getBeanStore } from '#lib/bean/index.js';
import { getSettingsStore } from '#lib/settings/index.js';
import { getGrinderLinkStore } from '#lib/grinder/connected-link.svelte.js';
import { NO_GRINDER, type ConnectedGrinderView } from '#lib/state/ui-state.svelte.js';
import type { CremaApp } from '#lib/state/index.js';
import RailDeviceButton from './RailDeviceButton.svelte';
import RailDevices from './RailDevices.svelte';
import { railAction, railDotClass, railLabel, railStatus, type RailDevice } from './rail-status';

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
		expect(railLabel('grinder', 'disconnected')).toBe('Click to connect grinder');
		expect(railLabel('grinder', 'reconnecting')).toBe('Grinder reconnecting — click to retry now');
		expect(railLabel('grinder', 'connected')).toBe('Grinder connected — click for options');
	});
});

describe('RailDeviceButton', () => {
	let component: ReturnType<typeof mount> | null = null;

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	function render(linkState: BleConnectionState, device: RailDevice = 'de1', deviceName = 'DE1-ABC') {
		const onConnect = vi.fn();
		const onRetry = vi.fn();
		const onDisconnect = vi.fn();
		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(RailDeviceButton, {
			target,
			props: { device, linkState, deviceName, onConnect, onRetry, onDisconnect }
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

	it('a grinder button reads Grinder and has the same menu', () => {
		const r = render('ready', 'grinder', 'BOOKOO Motto 80');
		expect(r.button.textContent).toContain('Grinder');
		expect(r.dot.classList.contains('off')).toBe(false);
		r.click(r.button);
		const menu = r.target.querySelector<HTMLElement>('[role="menu"]')!;
		expect(menu.getAttribute('aria-label')).toBe('Grinder options');
		expect(menu.textContent).toContain('BOOKOO Motto 80');
		r.click(menu.querySelector<HTMLButtonElement>('[role="menuitem"]')!);
		expect(r.onDisconnect).toHaveBeenCalledOnce();
	});
});

describe('RailDevices', () => {
	const MOTTO = 'bookoo-motto-80';
	const lib = getBeanStore();
	const links = getGrinderLinkStore();
	const settings = getSettingsStore();
	let component: ReturnType<typeof mount> | null = null;

	beforeAll(async () => {
		await initTestWasm();
	});

	beforeEach(() => {
		localStorage.clear();
		lib.clearAll();
		links.reload();
		settings.reset();
	});

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	function render(grinder: ConnectedGrinderView = NO_GRINDER) {
		const app = {
			state: {
				current: { de1State: 'idle', scaleState: 'idle', de1Diagnostics: {}, grinder }
			},
			connectGrinder: vi.fn(),
			reconnectGrinder: vi.fn(),
			disconnectGrinder: vi.fn(),
			retryReconnect: vi.fn()
		};
		const target = document.body.appendChild(document.createElement('div'));
		component = mount(RailDevices, { target, props: { app: app as unknown as CremaApp } });
		flushSync();
		const labels = [...target.querySelectorAll('.cside-status-label')].map((l) => l.textContent);
		return { app, target, labels };
	}

	function linkMotto(): void {
		const motto = lib.addGrinder('', MOTTO)!;
		links.link('dev-1', motto.id);
	}

	it('toggle off: the DE1 and scale only, as before', () => {
		linkMotto();
		expect(render().labels).toEqual(['DE1', 'Scale']);
	});

	it('toggle on without a Bluetooth grinder: the DE1 and scale only', () => {
		lib.addGrinder('Niche Zero');
		settings.set('grinderInDevices', true);
		expect(render().labels).toEqual(['DE1', 'Scale']);
	});

	it('toggle on with one: a grinder button under the scale, wired to the grinder link', () => {
		linkMotto();
		settings.set('grinderInDevices', true);
		const { app, target, labels } = render();
		expect(labels).toEqual(['DE1', 'Scale', 'Grinder']);
		const grinder = target.querySelector<HTMLButtonElement>('[aria-label="Click to connect grinder"]')!;
		grinder.click();
		expect(app.connectGrinder).toHaveBeenCalledOnce();
	});

	it('a reconnecting grinder is amber and retries the grinder', () => {
		linkMotto();
		settings.set('grinderInDevices', true);
		const { app, target } = render({ ...NO_GRINDER, state: 'reconnecting' });
		const button = target.querySelector<HTMLButtonElement>('[data-status="reconnecting"]')!;
		expect(button.querySelector('.cside-status-dot')!.classList.contains('is-busy')).toBe(true);
		button.click();
		expect(app.retryReconnect).toHaveBeenCalledWith('grinder');
	});
});
