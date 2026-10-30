/**
 * After a firmware flash lets go of the link, the app re-reads the machine
 * identity (core/shell review 2026-10-30 §2.1-2). The reads a reconnect runs
 * while the flash holds the link are refused, and web never re-ran them, so
 * the new firmware build, model, serial, GhcInfo and heater voltage stayed
 * stale or unknown until a manual reconnect. Android re-runs them in its
 * `afterFirmwareRelease`.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MmrRegister } from '#lib/core/index.js';

afterEach(() => {
	vi.restoreAllMocks();
});

async function makeApp() {
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	const reads: MmrRegister[] = [];
	const core = new Proxy(
		{
			readMmr: async (reg: MmrRegister) => {
				reads.push(reg);
				return { events: [], commands: [] };
			},
			onCoreStopped: () => undefined
		} as Record<string, unknown>,
		{
			get: (target, prop: string) =>
				prop in target ? target[prop] : async () => ({ events: [], commands: [] })
		}
	);
	const app = new CremaApp(core as never, null);
	const internals = app as unknown as {
		de1: { device: unknown };
		state: { patch(p: Record<string, unknown>): void };
		afterFirmwareRelease(): void;
	};
	// A connected DE1: the manager only needs a current device to read over.
	internals.de1.device = { id: 'de1-A' };
	return { reads, internals };
}

describe('machine identity after a firmware flash', () => {
	it('AC-F18e re-reads firmware build, serial and model when the flash releases the link', async () => {
		const t = await makeApp();
		t.internals.state.patch({ de1State: 'ready' });
		t.internals.afterFirmwareRelease();
		await vi.waitFor(() => expect(t.reads).toContain(MmrRegister.CalibrationFlowMultiplier));
		expect(t.reads[0]).toBe(MmrRegister.FirmwareVersion);
		expect(t.reads).toEqual(
			expect.arrayContaining([
				MmrRegister.FirmwareVersion,
				MmrRegister.SerialNumber,
				MmrRegister.MachineModel,
				MmrRegister.GhcInfo,
				MmrRegister.HeaterVoltage
			])
		);
	});

	it('reads nothing when the DE1 is not ready at release', async () => {
		const t = await makeApp();
		t.internals.state.patch({ de1State: 'reconnecting' });
		t.internals.afterFirmwareRelease();
		await new Promise((r) => setTimeout(r, 20));
		expect(t.reads).toEqual([]);
	});
});
