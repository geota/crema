/**
 * `$lib/services/de1-orchestrator.vitest` — vitest for the DE1 connect program
 * (docs/53 T-17). Mocks at the transport seam (D-07 keeps Web Bluetooth in
 * `transport.ts`): a fake `De1Transport` + core slice let us assert the
 * orchestration that Effect adds — step order, `De1ConnectStepFailed{step}`
 * attribution, the serial best-effort MMR sweep, and non-fatal-read swallowing
 * — without any real hardware. Run: `pnpm test:vitest`.
 */

import { Cause, Effect, Exit } from 'effect';
import { describe, expect, it, vi } from 'vitest';
import { de1ConnectProgram, type De1ConnectDeps } from './de1-orchestrator.ts';
import { De1Uuids } from '$lib/ble/de1-uuids';
import { MmrRegister } from '$lib/core/crema-core';
import type { De1Transport } from '$lib/ble/de1-transport';
import type { CoreOutput, CremaCore } from '$lib/core';

const EMPTY_OUTPUT = {} as CoreOutput;

function mkDevice(over: Partial<De1Transport> = {}): De1Transport {
	return {
		connectionState: 'connected',
		name: 'DE1',
		id: 'dev-1',
		setSink: vi.fn(),
		onDisconnected: vi.fn(),
		onStateChanged: vi.fn(),
		onReconnectAttempt: vi.fn(),
		onReconnected: vi.fn(),
		connectGatt: vi.fn(() => Promise.resolve()),
		startNotifications: vi.fn(() => Promise.resolve()),
		write: vi.fn(() => Promise.resolve()),
		readCharacteristic: vi.fn(() => Promise.resolve(new Uint8Array())),
		disconnect: vi.fn(),
		...over
	};
}

function mkCore(over: Partial<Pick<CremaCore, 'onNotification' | 'readMmr'>> = {}) {
	return {
		onNotification: vi.fn(() => Promise.resolve(EMPTY_OUTPUT)),
		readMmr: vi.fn(() => Promise.resolve(EMPTY_OUTPUT)),
		...over
	} as Pick<CremaCore, 'onNotification' | 'readMmr'>;
}

// No return annotation — let TS infer the precise Mock types so `deps.onStatus`
// etc. stay both callable (assignable to De1ConnectDeps) and assertable.
function mkDeps(device: De1Transport, core = mkCore()) {
	return {
		device,
		core,
		onStatus: vi.fn<(line: string) => void>(),
		onCoreOutput: vi.fn<(o: CoreOutput) => void>(),
		onGattVerified: vi.fn<() => void>()
	} satisfies De1ConnectDeps;
}

const run = (deps: De1ConnectDeps) => Effect.runPromiseExit(de1ConnectProgram(deps));
const failStep = (exit: Exit.Exit<void, { step?: string }>) => {
	if (!Exit.isFailure(exit)) return undefined;
	const f = Cause.failureOption(exit.cause);
	return f._tag === 'Some' ? f.value.step : undefined;
};

describe('de1ConnectProgram — happy path', () => {
	it('connects, subscribes in order, verifies, reads, and sweeps MMR', async () => {
		const device = mkDevice();
		const deps = mkDeps(device);
		const exit = await run(deps);

		expect(Exit.isSuccess(exit)).toBe(true);
		expect(device.connectGatt).toHaveBeenCalledOnce();

		// Five subscribes in the documented order.
		const subChars = (device.startNotifications as ReturnType<typeof vi.fn>).mock.calls.map((c) => c[1]);
		expect(subChars).toEqual([
			De1Uuids.STATE_INFO,
			De1Uuids.SHOT_SAMPLE,
			De1Uuids.WATER_LEVELS,
			De1Uuids.MMR_READ,
			De1Uuids.SHOT_SETTINGS
		]);

		expect(deps.onGattVerified).toHaveBeenCalledOnce();

		// Three connect-time one-shot reads (version / state / shot-settings).
		expect(device.readCharacteristic).toHaveBeenCalledTimes(3);

		// FirmwareVersion first, then the 9-register sweep in declaration order.
		const sweptRegs = (deps.core.readMmr as ReturnType<typeof vi.fn>).mock.calls.map((c) => c[0]);
		expect(sweptRegs[0]).toBe(MmrRegister.FirmwareVersion);
		expect(sweptRegs.slice(1)).toEqual([
			MmrRegister.CpuBoardVersion,
			MmrRegister.MachineModel,
			MmrRegister.GhcInfo,
			MmrRegister.RefillKit,
			MmrRegister.SerialNumber,
			MmrRegister.HeaterVoltage,
			MmrRegister.FlushTemp,
			MmrRegister.GhcMode,
			MmrRegister.CalibrationFlowMultiplier
		]);
	});
});

describe('de1ConnectProgram — fatal steps', () => {
	it('attributes a GATT connect failure to the "GATT connect" step', async () => {
		const device = mkDevice({ connectGatt: vi.fn(() => Promise.reject(new Error('no gatt'))) });
		const deps = mkDeps(device);
		const exit = await run(deps);
		expect(failStep(exit)).toBe('GATT connect');
		expect(deps.onGattVerified).not.toHaveBeenCalled();
		expect(device.startNotifications).not.toHaveBeenCalled();
	});

	it('attributes a subscribe failure to the right characteristic step', async () => {
		// Fail the 2nd subscribe (ShotSample).
		let n = 0;
		const device = mkDevice({
			startNotifications: vi.fn(() => {
				n += 1;
				return n === 2 ? Promise.reject(new Error('not a DE1')) : Promise.resolve();
			})
		});
		const deps = mkDeps(device);
		const exit = await run(deps);
		expect(failStep(exit)).toBe('ShotSample characteristic A00D');
		expect(deps.onGattVerified).not.toHaveBeenCalled();
		// Stopped at step 2 — only 2 subscribe attempts.
		expect((device.startNotifications as ReturnType<typeof vi.fn>).mock.calls).toHaveLength(2);
	});
});

describe('de1ConnectProgram — non-fatal reads/sweep', () => {
	it('succeeds even when a connect-time read fails (logs + continues)', async () => {
		const device = mkDevice({
			readCharacteristic: vi.fn(() => Promise.reject(new Error('read flaked')))
		});
		const deps = mkDeps(device);
		const exit = await run(deps);
		expect(Exit.isSuccess(exit)).toBe(true);
		expect(deps.onGattVerified).toHaveBeenCalledOnce();
		// All three reads were attempted despite each failing.
		expect(device.readCharacteristic).toHaveBeenCalledTimes(3);
	});

	it('succeeds and keeps sweeping when one MMR read fails', async () => {
		let n = 0;
		const core = mkCore({
			readMmr: vi.fn(() => {
				n += 1;
				return n === 3 ? Promise.reject(new Error('mmr flaked')) : Promise.resolve(EMPTY_OUTPUT);
			})
		});
		const deps = mkDeps(mkDevice(), core);
		const exit = await run(deps);
		expect(Exit.isSuccess(exit)).toBe(true);
		// FirmwareVersion + 9 sweep regs all attempted (serial), one failure included.
		expect((core.readMmr as ReturnType<typeof vi.fn>).mock.calls).toHaveLength(10);
	});
});
