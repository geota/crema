/**
 * Devices → Grinder's "Connected grinder" card: always on Devices → Grinder
 * (no setting), with its Beta note; Connect, the live readout in the capabilities' units, the §2.7
 * messages, and the §6.1 link choices (Linked to, Which grinder is this?,
 * Add … to your grinders).
 */
import '#lib/testing/dom-stubs.js';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import { getBeanStore } from '#lib/bean/index.js';
import { getSettingsStore } from '#lib/settings/index.js';
import { GrinderState, type GrinderReading } from '#lib/core/crema-core.js';
import { NO_GRINDER, type ConnectedGrinderView } from '#lib/state/ui-state.svelte.js';
import { getGrinderLinkStore } from '#lib/grinder/connected-link.svelte.js';
import type { CremaApp } from '#lib/state/index.js';
import ConnectedGrinderCard from './ConnectedGrinderCard.svelte';
import GrinderTab from './GrinderTab.svelte';
import { grinderProblemText, grinderStatusLine } from './connected-grinder';

const MOTTO = 'bookoo-motto-80';
const lib = getBeanStore();
const links = getGrinderLinkStore();

const reading: GrinderReading = {
	at_ms: 1,
	state: GrinderState.Idle,
	raw_state: 'IDLE',
	setting: { value: '480', number: 480 },
	grind_rpm: 700,
	feed_rpm: 50,
	humidity_pct: 40,
	total_grinds: 120,
	preset: { type: 'Manual' },
	settings: {}
};

const live = (over: Partial<ConnectedGrinderView> = {}): ConnectedGrinderView => ({
	...NO_GRINDER,
	state: 'ready',
	deviceId: 'dev-1',
	name: 'BOOKOO MT80 0011aabb',
	model: MOTTO,
	capabilities: {
		setting: { kind: { type: 'Numeric', content: { min: 0, max: 999, step: 1 } }, unit: 'μm', calibrated: true, physical: true },
		reports_state: true,
		reports_grind_count: true,
		reports_humidity: true,
		reports_weight: false,
		commands: { start_stop: false }
	} as ConnectedGrinderView['capabilities'],
	info: { model: MOTTO, firmware: '1.2.19.0820', serial: 'MT80SECRET1234' },
	reading,
	...over
});

function fakeApp(view: ConnectedGrinderView) {
	return {
		state: { current: { grinder: view } },
		connectGrinder: vi.fn(async () => undefined),
		disconnectGrinder: vi.fn(async () => undefined),
		forgetGrinder: vi.fn(async () => undefined),
		reconnectGrinder: vi.fn(async () => undefined),
		retryReconnect: vi.fn()
	};
}

let component: ReturnType<typeof mount> | null = null;

function render(view: ConnectedGrinderView) {
	const app = fakeApp(view);
	const target = document.body.appendChild(document.createElement('div'));
	component = mount(ConnectedGrinderCard, { target, props: { app: app as unknown as CremaApp } });
	flushSync();
	return { target, app };
}

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	localStorage.clear();
	lib.clearAll();
	links.reload();
	getSettingsStore().reset();
});

afterEach(() => {
	if (component) unmount(component);
	component = null;
	document.body.innerHTML = '';
});

describe('the Connected grinder card', () => {
	it('shows on Devices → Grinder with no setting, marked Beta and asking for a capture', () => {
		const target = document.body.appendChild(document.createElement('div'));
		component = mount(GrinderTab, { target, props: {} });
		flushSync();
		expect(target.textContent).toContain('Connected grinder');
		expect(target.textContent).toContain('Beta');
		expect(target.textContent).toContain('Not tested on a real grinder yet');
	});

	it('offers Connect when nothing is connected', () => {
		const { target, app } = render(NO_GRINDER);
		expect(target.querySelector('[role="status"]')?.textContent).toBe('Not connected');
		const connect = [...target.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Connect')!;
		connect.click();
		expect(app.connectGrinder).toHaveBeenCalledOnce();
	});

	it('offers Reconnect when the grinder stopped sending data', () => {
		const { target, app } = render({ ...live({ state: 'failed', reading: null }), problem: { kind: 'no-data' } });
		expect(target.querySelector('[role="status"]')?.textContent).toBe('Not sending data');
		expect(target.textContent).toContain('Grinder not sending data');
		const reconnect = [...target.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Reconnect')!;
		reconnect.click();
		expect(app.reconnectGrinder).toHaveBeenCalledOnce();
		expect(app.connectGrinder).not.toHaveBeenCalled();
	});

	it('shows the live readout in the grinder’s units, the serial’s last four only', () => {
		const { target } = render(live());
		const text = target.textContent ?? '';
		expect(target.querySelector('[role="status"]')?.textContent).toBe('Live');
		expect(text).toContain('480 μm');
		expect(text).toContain('700 rpm');
		expect(text).toContain('Manual');
		expect(text).toContain('1.2.19.0820');
		expect(text).toContain('…1234');
		expect(text).not.toContain('SECRET');
		expect(text).toContain('Disconnect');
	});

	it('offers to add the model when no record fits, and links what it adds', () => {
		const { target } = render(live());
		const add = [...target.querySelectorAll('button')].find((b) =>
			b.textContent?.includes('to your grinders')
		)!;
		expect(add.textContent?.trim()).toBe('Add BOOKOO Motto 80 to your grinders');
		add.click();
		flushSync();
		const added = lib.grinders.find((g) => g.presetId === MOTTO)!;
		expect(links.linkOf('dev-1')).toBe(added.id);
		expect((target.querySelector('select') as HTMLSelectElement).value).toBe(added.id);
	});

	it('asks which grinder it is when several fit, and links the pick', () => {
		const a = lib.addGrinder('', MOTTO)!;
		const b = lib.addAnotherGrinder('', MOTTO)!;
		const { target } = render(live());
		expect(target.textContent).toContain('Which grinder is this?');
		const select = target.querySelector('select') as HTMLSelectElement;
		select.value = b.id;
		select.dispatchEvent(new Event('change', { bubbles: true }));
		flushSync();
		expect(links.linkOf('dev-1')).toBe(b.id);
		expect(target.textContent).toContain('Linked to');
		expect(a.id).not.toBe(b.id);
	});
});

describe('Show in Devices and the rail', () => {
	const toggle = (target: HTMLElement) =>
		[...target.querySelectorAll<HTMLButtonElement>('[role="switch"]')].find((b) =>
			b.getAttribute('aria-labelledby')?.endsWith('-in-devices')
		) ?? null;

	it('is not offered until one of the grinders connects over Bluetooth', () => {
		lib.addGrinder('Niche Zero');
		const { target } = render(NO_GRINDER);
		expect(target.textContent).not.toContain('Show in Devices and the rail');
		expect(toggle(target)).toBeNull();
	});

	it('is offered, off by default, once a device links to a grinder, and turns on', () => {
		const motto = lib.addGrinder('', MOTTO)!;
		links.link('dev-1', motto.id);
		const { target } = render(live());
		const sw = toggle(target)!;
		expect(target.textContent).toContain('Show in Devices and the rail');
		expect(sw.getAttribute('aria-checked')).toBe('false');
		sw.click();
		flushSync();
		expect(getSettingsStore().current.grinderInDevices).toBe(true);
		expect(sw.getAttribute('aria-checked')).toBe('true');
	});
});

describe('the card’s words', () => {
	const entry = { id: MOTTO, name: 'BOOKOO Motto 80', maker: 'BOOKOO', model: 'Motto 80' } as never;

	it('names the maker’s app in the §2.7 messages', () => {
		expect(grinderProblemText({ kind: 'no-answer' }, entry)).toBe(
			"The grinder didn't answer. Close the BOOKOO app, turn the grinder off and on, and try again."
		);
		expect(grinderProblemText({ kind: 'not-found' }, null)).toContain("Can't see your grinder");
		expect(
			grinderProblemText({ kind: 'firmware', firmware: '1.2.4.0416', minimum: '1.2.19.0820' }, entry)
		).toContain('Update the grinder in the BOOKOO app (it needs firmware 1.2.19.0820 or later');
	});

	it('says Updating firmware while the grinder updates', () => {
		expect(grinderStatusLine(live({ reading: { ...reading, state: GrinderState.Updating } }))).toBe(
			'Updating firmware…'
		);
		expect(grinderStatusLine({ ...NO_GRINDER, state: 'subscribing' })).toBe('Waiting for the grinder…');
	});
});
