/**
 * The steam / hot-water write through the real app wiring (core/shell fixes
 * AC-F5.2): a real wasm core inside a real `CremaApp`, with only the DE1's
 * GATT write replaced. The shell pushes the steam / hot-water targets and the
 * hot-water dose from the settings store; the core does the read-modify-write,
 * so the fields the shell doesn't own (flags included) reach the DE1
 * unchanged.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';

/** A cuuid_0B packet: flags, steam °C, steam s, water °C, water ml, water s,
 *  espresso ml, group °C (U16P8). */
function shotSettings(v: number[], groupC: number): Uint8Array {
	return new Uint8Array([...v, Math.floor(groupC), Math.round((groupC % 1) * 256)]);
}

afterEach(() => {
	vi.restoreAllMocks();
});

describe('steam / hot-water targets (AC-F5.2)', () => {
	it('the web shell writes through the core and keeps the fields it does not own', async () => {
		const { loadCore } = await import('#lib/core/index.js');
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		const { De1Manager } = await import('#lib/ble/index.js');
		const written: Uint8Array[] = [];
		vi.spyOn(De1Manager.prototype, 'writeCharacteristic').mockImplementation((async (
			target: string,
			data: Uint8Array
		) => {
			if (target === 'De1ShotSettings') written.push(data);
			return true;
		}) as never);
		const core = await loadCore();
		core.reset();
		const app = new CremaApp(core, null);
		const internals = app as unknown as {
			de1: { callbacks: { onState(s: string): void } };
			applyCoreOutput(o: unknown): void;
		};
		// The machine reports a set flag bit and its own timeout, espresso
		// volume and group target (the connect-time seed read), then Idle;
		// the on-ready sweep settles the link and writes its packet.
		internals.applyCoreOutput(
			await core.onNotification(
				'De1ShotSettings',
				shotSettings([0x40, 160, 30, 90, 200, 45, 220], 88.5),
				0
			)
		);
		internals.applyCoreOutput(await core.onNotification('De1State', new Uint8Array([2, 0]), 0));
		internals.de1.callbacks.onState('ready');
		await vi.waitFor(() => expect(written.length).toBeGreaterThan(0));
		written.length = 0;

		// Values other than the defaults the sweep just wrote (the same bytes
		// are never written twice), pushed from the settings store.
		const { getSettingsStore } = await import('#lib/settings/index.js');
		const { HotWaterMode } = await import('#lib/core/crema-core.js');
		const settings = getSettingsStore();
		settings.set('qcSteamTempC', 150);
		settings.set('qcSteamTimeS', 20);
		settings.set('qcHotWaterTempC', 85);
		settings.setHotWaterDose({ ...settings.hotWaterDose(), mode: HotWaterMode.Volume, volumeMl: 160 });
		await app.pushSteamHotwater();
		await vi.waitFor(() => expect(written).toHaveLength(1));

		// The hot-water timeout is Crema's now (the 60 s backstop of a
		// volume dose); the flags, espresso volume and group target are kept.
		expect([...written[0]]).toEqual([...shotSettings([0x40, 150, 20, 85, 160, 60, 220], 88.5)]);
	});

	it('settings steam edit keeps the dose', async () => {
		// AC-HW22: Settings → Steam goes through the app's one push, which
		// sends the persisted dose — ratio mode stays ratio mode in the core
		// call, never a bare volume.
		const { loadCore } = await import('#lib/core/index.js');
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		const { getSettingsStore } = await import('#lib/settings/index.js');
		const { HotWaterMode } = await import('#lib/core/crema-core.js');
		const core = await loadCore();
		core.reset();
		const app = new CremaApp(core, null);
		const sent = vi.spyOn(core, 'setSteamHotwaterTargets');
		const settings = getSettingsStore();
		settings.setHotWaterDose({ ...settings.hotWaterDose(), mode: HotWaterMode.Ratio, ratio: 2.5 });
		// What `BrewDefaultsSection`'s steam stepper does.
		settings.set('qcSteamTempC', 155);
		await app.pushSteamHotwater();
		const targets = sent.mock.calls.at(-1)?.[0];
		expect(targets?.steamTempC).toBe(155);
		expect(targets?.hotWater.mode).toBe(HotWaterMode.Ratio);
		expect(targets?.hotWater.ratio).toBe(2.5);
	});
});
