/**
 * The water rules are the core's and the web shell only applies them
 * (core/shell fixes F9, review §2.7): the counted-water deltas
 * (`Telemetry.counted_ml`, espresso and hot water only), the low-water
 * warning (`WaterLow`, once per dip) and the user's threshold pushed to the
 * core. Drives the real `CremaApp` and the real wasm core.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import type { CoreOutput, CremaCore, NotificationSource } from '#lib/core/index.js';
import { MachineState } from '#lib/core/crema-core.js';

/** The core's own test `ShotSample` (positive group flow). */
const SAMPLE = new Uint8Array([
	0x12, 0x34, 0x90, 0x00, 0x28, 0x00, 0x5c, 0x80, 0x00, 0x58, 0x00, 0x5a, 0x00, 0x5d, 0x00, 0x60,
	0x40, 0x03, 0x96
]);

const STATE_CODE: Partial<Record<MachineState, number>> = {
	[MachineState.Espresso]: 4,
	[MachineState.Steam]: 5,
	[MachineState.HotWater]: 6,
	[MachineState.Descale]: 10,
	[MachineState.HotWaterRinse]: 15,
	[MachineState.SteamRinse]: 16,
	[MachineState.Clean]: 18,
	[MachineState.AirPurge]: 20
};

let core: CremaCore;

beforeAll(async () => {
	await initTestWasm();
	core = await (await import('#lib/core/index.js')).loadCore();
});

beforeEach(() => {
	localStorage.clear();
});

afterEach(async () => {
	await core.reset();
});

async function makeApp() {
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	const { getMaintenanceStore } = await import('./store.svelte');
	const { getToasts, dismissToast } = await import('#lib/components/shared/toast.svelte.js');
	for (const t of getToasts()) dismissToast(t.id);
	const app = new CremaApp(core, null);
	const internals = app as unknown as { applyCoreOutput(out: CoreOutput): void };
	const feed = async (source: NotificationSource, bytes: Uint8Array, atMs: number) =>
		internals.applyCoreOutput(await core.onNotification(source, bytes, atMs));
	return {
		app,
		feed,
		litres: () => getMaintenanceStore().current.totalLitres,
		toasts: () => getToasts().map((t) => t.message)
	};
}

describe('water rules from the core', () => {
	it('AC-F9 water is counted only for espresso and hot water', async () => {
		const t = await makeApp();
		let clock = 1_000;
		for (const [state, counts] of [
			[MachineState.Steam, false],
			[MachineState.Descale, false],
			[MachineState.Clean, false],
			[MachineState.SteamRinse, false],
			[MachineState.AirPurge, false],
			[MachineState.Espresso, true],
			[MachineState.HotWater, true],
			[MachineState.HotWaterRinse, true]
		] as const) {
			await core.reset();
			const before = t.litres();
			await t.feed('De1State', new Uint8Array([STATE_CODE[state]!, 5]), clock);
			await t.feed('De1ShotSample', SAMPLE, clock);
			await t.feed('De1ShotSample', SAMPLE, clock + 250);
			clock += 10_000;
			if (counts) expect(t.litres(), state).toBeGreaterThan(before);
			else expect(t.litres(), state).toBe(before);
		}
	});

	it('AC-F9 the low-water warning fires once per dip, and the threshold reaches the core', async () => {
		const t = await makeApp();
		// Raw 0 mm → 5 mm depth → 124 ml: above the 110 ml default.
		await t.feed('De1WaterLevels', new Uint8Array([0, 0, 5, 0]), 0);
		expect(t.toasts()).toEqual([]);
		// Raise the threshold through the app: the core re-arms and warns.
		await t.app.applyWaterWarnMl(130);
		await t.feed('De1WaterLevels', new Uint8Array([0, 0, 5, 0]), 100);
		expect(t.toasts()).toEqual(['Water low — 124 ml left, refill soon']);
		// The same dip doesn't warn twice.
		await t.feed('De1WaterLevels', new Uint8Array([0, 0, 5, 0]), 200);
		expect(t.toasts()).toHaveLength(1);
	});
});
