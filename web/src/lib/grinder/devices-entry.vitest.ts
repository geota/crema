/**
 * The grinder's opt-in place in Devices and the rail: shown only with the
 * setting on and a grinder that connects over Bluetooth; while connected its
 * status names the grind.
 */
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { getBeanStore } from '#lib/bean/index.js';
import { getSettingsStore } from '#lib/settings/index.js';
import type { Grinder, GrinderReading } from '#lib/core/crema-core.js';
import { GrinderState } from '#lib/core/crema-core.js';
import { NO_GRINDER } from '#lib/state/ui-state.svelte.js';
import { getGrinderLinkStore, type GrinderLinks } from './connected-link.svelte';
import {
	grinderDeviceDetail,
	grinderDeviceName,
	grinderInDevices,
	hasBluetoothGrinder,
	linkedGrinders,
	showGrinderInDevices
} from './devices-entry.svelte';

const MOTTO = 'bookoo-motto-80';
const scale = { min: 0, max: 10, step: 0.1, unit: '' };
const grinder = (id: string, over: Partial<Grinder> = {}): Grinder => ({
	id,
	name: id,
	scale,
	updatedAt: 1,
	...over
});
const link = (grinderId: string) => ({ grinderId, linkedAt: 1 });

const reading = (value: string): GrinderReading => ({
	at_ms: 1,
	state: GrinderState.Idle,
	raw_state: 'IDLE',
	setting: { value, number: Number(value) },
	settings: {}
});

describe('the visibility rule', () => {
	const motto = grinder('grinder:motto', { presetId: MOTTO });
	const niche = grinder('grinder:niche');
	const linked: GrinderLinks = { 'dev-1': link(motto.id) };

	it('toggle off: hidden, even with a Bluetooth grinder', () => {
		expect(showGrinderInDevices(false, linked, [motto, niche])).toBe(false);
	});

	it('toggle on without a Bluetooth grinder: hidden', () => {
		expect(showGrinderInDevices(true, {}, [motto, niche])).toBe(false);
		// A link to a record that is gone, archived or deleted is no grinder of theirs.
		expect(showGrinderInDevices(true, linked, [niche])).toBe(false);
		expect(showGrinderInDevices(true, linked, [{ ...motto, archivedAt: 2 }, niche])).toBe(false);
		expect(showGrinderInDevices(true, linked, [{ ...motto, deletedAt: 2 }, niche])).toBe(false);
	});

	it('toggle on with one: shown', () => {
		expect(showGrinderInDevices(true, linked, [motto, niche])).toBe(true);
		expect(linkedGrinders(linked, [motto, niche])).toEqual([motto]);
	});
});

describe('the status detail', () => {
	it('is the current grind, never the battery', () => {
		expect(grinderDeviceDetail({ reading: reading('2.4'), capabilities: null })).toBe('Grind 2.4');
	});

	it('carries the unit the grinder reports', () => {
		const capabilities = { setting: { unit: 'μm' } } as unknown as typeof NO_GRINDER.capabilities;
		expect(grinderDeviceDetail({ reading: reading('480'), capabilities })).toBe('Grind 480 μm');
	});

	it('is nothing before a reading carries a setting', () => {
		expect(grinderDeviceDetail(NO_GRINDER)).toBeNull();
		expect(grinderDeviceDetail({ reading: { ...reading('1'), setting: undefined }, capabilities: null })).toBeNull();
	});
});

describe('the name', () => {
	const mine = grinder('grinder:a', { name: 'Motto at home', presetId: MOTTO });
	const other = grinder('grinder:b', { name: 'Motto at work', presetId: MOTTO });
	const links: GrinderLinks = { 'dev-1': link(mine.id), 'dev-2': link(other.id) };

	it('is the shown advertised name while connected, never more', () => {
		expect(grinderDeviceName({ ...NO_GRINDER, name: 'BOOKOO MT80' }, links, [mine, other])).toBe(
			'BOOKOO MT80'
		);
	});

	it('is the linked record when nothing is connected', () => {
		expect(grinderDeviceName({ ...NO_GRINDER, deviceId: 'dev-2' }, links, [mine, other])).toBe(
			'Motto at work'
		);
		expect(grinderDeviceName(NO_GRINDER, links, [mine, other])).toBe('Motto at home');
		expect(grinderDeviceName(NO_GRINDER, {}, [])).toBe('Grinder');
	});
});

describe('over the app stores', () => {
	const lib = getBeanStore();
	const links = getGrinderLinkStore();
	const settings = getSettingsStore();

	beforeAll(async () => {
		await initTestWasm();
	});

	beforeEach(() => {
		localStorage.clear();
		lib.clearAll();
		links.reload();
		settings.reset();
	});

	it('the setting is off by default', () => {
		expect(settings.current.grinderInDevices).toBe(false);
	});

	it('follows the setting and the links', () => {
		const niche = lib.addGrinder('Niche Zero')!;
		expect(hasBluetoothGrinder()).toBe(false);
		settings.set('grinderInDevices', true);
		expect(grinderInDevices()).toBe(false);

		const motto = lib.addGrinder('', MOTTO)!;
		links.link('dev-1', motto.id);
		expect(hasBluetoothGrinder()).toBe(true);
		expect(grinderInDevices()).toBe(true);

		settings.set('grinderInDevices', false);
		expect(grinderInDevices()).toBe(false);
		expect(hasBluetoothGrinder()).toBe(true);
		expect(niche.id).not.toBe(motto.id);
	});
});
