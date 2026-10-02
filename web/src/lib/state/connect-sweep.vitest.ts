import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { CoreOutput } from '$lib/core';
import { UsbChargingMode, type ConnectSweepSettings } from '$lib/core/crema-core';
import { DEFAULT_SETTINGS, type Settings } from '$lib/settings/store.svelte';
import {
	buildConnectSweepSettings,
	ConnectSweepRunner,
	USB_CHARGER_CHECK_INTERVAL_MS
} from './connect-sweep';

const EMPTY: CoreOutput = { events: [], commands: [] };

function harness(settings: Settings = DEFAULT_SETTINGS) {
	const sweeps: ConnectSweepSettings[] = [];
	const ticks: [UsbChargingMode, number | null][] = [];
	const applied: CoreOutput[] = [];
	const core = {
		connectSweep: vi.fn(async (s: ConnectSweepSettings) => {
			sweeps.push(s);
			return EMPTY;
		}),
		usbChargerTick: vi.fn(async (mode: UsbChargingMode, pct: number | null) => {
			ticks.push([mode, pct]);
			return EMPTY;
		})
	};
	let current = settings;
	const runner = new ConnectSweepRunner({
		core,
		settings: () => current,
		tankTempC: async () => 30,
		battery: async () => 42,
		apply: (out) => applied.push(out),
		now: () => 1_000
	});
	return {
		runner,
		sweeps,
		ticks,
		applied,
		setSettings: (s: Settings) => (current = s)
	};
}

describe('connect sweep snapshot', () => {
	it('maps the settings, the active profile tank target and the battery', () => {
		const s: Settings = {
			...DEFAULT_SETTINGS,
			fanThresholdC: 48,
			steamTwoTapStop: true,
			waterRefillPointMm: 9,
			usbChargingMode: 'alwaysOn'
		};
		const snap = buildConnectSweepSettings(s, 35, 77);
		expect(snap.fanThresholdC).toBe(48);
		expect(snap.steamTwoTap).toBe(true);
		expect(snap.refillPointMm).toBe(9);
		expect(snap.tankTempC).toBe(35);
		expect(snap.batteryPercent).toBe(77);
		expect(snap.usbCharging).toBe(UsbChargingMode.AlwaysOn);
		expect(snap.steamTempC).toBe(s.qcSteamTempC);
		// Heater tweaks with no setting stay unset → the core's de1app defaults.
		expect(snap.phase1FlowMlS).toBeUndefined();
		expect(snap.hotWaterIdleTempC).toBeUndefined();
	});

	it('omits an unreadable battery', () => {
		expect('batteryPercent' in buildConnectSweepSettings(DEFAULT_SETTINGS, 0, null)).toBe(false);
	});
});

describe('connect sweep runner', () => {
	beforeEach(() => vi.useFakeTimers());
	afterEach(() => vi.useRealTimers());

	it('runs the sweep once each time the DE1 becomes ready', async () => {
		const h = harness();
		h.runner.onDe1State('connecting');
		await vi.advanceTimersByTimeAsync(0);
		expect(h.sweeps).toHaveLength(0);
		h.runner.onDe1State('ready');
		await vi.advanceTimersByTimeAsync(0);
		expect(h.sweeps).toHaveLength(1);
		expect(h.sweeps[0].tankTempC).toBe(30);
		expect(h.sweeps[0].batteryPercent).toBe(42);
		expect(h.applied).toHaveLength(1);
		// A repeated ready (status refresh) does not re-sweep.
		h.runner.onDe1State('ready');
		await vi.advanceTimersByTimeAsync(0);
		expect(h.sweeps).toHaveLength(1);
		// A reconnect does.
		h.runner.onDe1State('reconnecting');
		h.runner.onDe1State('ready');
		await vi.advanceTimersByTimeAsync(0);
		expect(h.sweeps).toHaveLength(2);
		h.runner.stop();
	});

	it('re-checks the USB charger every minute while ready, with the current mode', async () => {
		const h = harness();
		h.runner.onDe1State('ready');
		await vi.advanceTimersByTimeAsync(USB_CHARGER_CHECK_INTERVAL_MS);
		expect(h.ticks).toEqual([[UsbChargingMode.AlwaysOn, 42]]);
		h.setSettings({ ...DEFAULT_SETTINGS, usbChargingMode: 'smartHigh' });
		await vi.advanceTimersByTimeAsync(USB_CHARGER_CHECK_INTERVAL_MS);
		expect(h.ticks.at(-1)).toEqual([UsbChargingMode.SmartHigh, 42]);
		// Disconnected: the minute check stops.
		h.runner.onDe1State('disconnected');
		await vi.advanceTimersByTimeAsync(5 * USB_CHARGER_CHECK_INTERVAL_MS);
		expect(h.ticks).toHaveLength(2);
	});
});
