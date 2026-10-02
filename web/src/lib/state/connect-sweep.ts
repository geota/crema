/**
 * The DE1 connect sweep, shell side.
 *
 * The list of writes is core-owned (`CremaCore::connect_sweep`,
 * `de1_domain::connect_sweep` — de1app `later_new_de1_connection_setup` +
 * `set_heater_tweaks`, Decenza `sendInitialSettings`): this module only
 * builds the settings snapshot the core needs, runs the sweep once each time
 * the DE1 becomes ready, and drives the once-a-minute USB-charger check
 * (Decenza `BatteryManager`) while it stays ready. Android's `ConnectSweep.kt` is the twin.
 */

import type { CoreOutput, CremaCore } from '$lib/core';
import { type ConnectSweepSettings, UsbChargingMode } from '$lib/core/crema-core';
import type { Settings } from '$lib/settings/store.svelte';
import { defaultRefillPointMm } from './ui-state.svelte';

/**
 * The charger decision is re-sent every 60 s, even unchanged: the DE1
 * re-enables its USB port after 10 minutes, so an OFF must be reasserted
 * (Decenza `BatteryManager::applySmartCharging`).
 */
export const USB_CHARGER_CHECK_INTERVAL_MS = 60_000;

const USB_MODES: Record<Settings['usbChargingMode'], UsbChargingMode> = {
	smart: UsbChargingMode.Smart,
	smartHigh: UsbChargingMode.SmartHigh,
	alwaysOn: UsbChargingMode.AlwaysOn
};

/**
 * The core's sweep snapshot from the user's settings, the active profile's
 * tank-temperature target (`0` = none) and the tablet battery (`null` =
 * unreadable). Heater tweaks Crema has no setting for stay unset, so the core
 * asserts de1app's defaults.
 */
export function buildConnectSweepSettings(
	s: Settings,
	tankTempC: number,
	batteryPercent: number | null
): ConnectSweepSettings {
	return {
		fanThresholdC: s.fanThresholdC,
		steamTwoTap: s.steamTwoTapStop,
		refillPointMm: s.waterRefillPointMm ?? defaultRefillPointMm(),
		steamTempC: s.qcSteamTempC,
		steamTimeoutS: s.qcSteamTimeS,
		hotWaterTempC: s.qcHotWaterTempC,
		hotWaterVolumeMl: s.qcHotWaterVolumeMl,
		steamFlowMlS: s.qcSteamFlowMlS,
		flushTimeoutS: s.qcFlushTimeS,
		flushTempC: s.qcFlushTempC,
		steamEco: s.steamEcoMode,
		tankTempC: Number.isFinite(tankTempC) ? tankTempC : 0,
		usbCharging: USB_MODES[s.usbChargingMode] ?? UsbChargingMode.AlwaysOn,
		...(batteryPercent === null ? {} : { batteryPercent: Math.round(batteryPercent) })
	};
}

/** The tablet battery, %, via the Battery Status API where the browser has it. */
export async function readBatteryPercent(): Promise<number | null> {
	try {
		const nav = globalThis.navigator as
			| (Navigator & { getBattery?: () => Promise<{ level: number }> })
			| undefined;
		if (!nav?.getBattery) return null;
		const battery = await nav.getBattery();
		return Number.isFinite(battery.level) ? Math.round(battery.level * 100) : null;
	} catch {
		return null;
	}
}

/** What the runner needs from the app — injected so it tests without BLE. */
export interface ConnectSweepDeps {
	core: Pick<CremaCore, 'connectSweep' | 'usbChargerTick'>;
	settings: () => Settings;
	tankTempC: () => Promise<number>;
	battery: () => Promise<number | null>;
	apply: (out: CoreOutput) => void;
	now?: () => number;
}

/**
 * Runs the sweep on every transition into `ready` and the USB-charger check
 * every minute while ready; stops the minute check on any other state.
 */
export class ConnectSweepRunner {
	private timer: ReturnType<typeof setInterval> | null = null;
	private ready = false;

	constructor(private readonly deps: ConnectSweepDeps) {}

	/** Feed every DE1 connection-state change. */
	onDe1State(state: string): void {
		const ready = state === 'ready';
		if (ready && !this.ready) {
			void this.sweep();
			this.timer = setInterval(() => void this.tick(), USB_CHARGER_CHECK_INTERVAL_MS);
		} else if (!ready) {
			this.stop();
		}
		this.ready = ready;
	}

	/** Stop the minute check (disconnect / teardown). */
	stop(): void {
		if (this.timer !== null) {
			clearInterval(this.timer);
			this.timer = null;
		}
	}

	private async sweep(): Promise<void> {
		try {
			const [tank, battery] = await Promise.all([this.deps.tankTempC(), this.deps.battery()]);
			const snapshot = buildConnectSweepSettings(this.deps.settings(), tank, battery);
			const now = this.deps.now?.() ?? Date.now();
			this.deps.apply(await this.deps.core.connectSweep(snapshot, now));
		} catch {
			// Best-effort, like every connect write: the next connect retries.
		}
	}

	private async tick(): Promise<void> {
		try {
			const mode = USB_MODES[this.deps.settings().usbChargingMode] ?? UsbChargingMode.AlwaysOn;
			this.deps.apply(await this.deps.core.usbChargerTick(mode, await this.deps.battery()));
		} catch {
			// Next minute retries.
		}
	}
}
