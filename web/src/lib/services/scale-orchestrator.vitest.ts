/**
 * `$lib/services/scale-orchestrator.vitest` — vitest for the scale connect
 * program (docs/53 T-19). Mocks at the transport seam: a fake `De1Transport` +
 * core slice assert the orchestration — step order, the core-reported-UUID
 * handoff, the distinct-vs-shared command-characteristic branch, and the
 * `ScaleConnectStepFailed{step}` attribution (unrecognised scale / no UUIDs /
 * transport throws). Run: `pnpm test:vitest`.
 */

import { Cause, Effect, Exit } from 'effect';
import { describe, expect, it, vi } from 'vitest';
import { scaleConnectProgram, type ScaleConnectDeps } from './scale-orchestrator.ts';
import type { De1Transport } from '$lib/ble/de1-transport';
import type { CoreOutput, CremaCore, ScaleUuids } from '$lib/core';

const EMPTY_OUTPUT = {} as CoreOutput;
const UUIDS = (over: Partial<ScaleUuids> = {}) =>
	({
		service: 'svc',
		weight_notify: 'wn',
		command_write: 'cw',
		command_notifies: true,
		...over
	}) as ScaleUuids;

function mkDevice(over: Partial<De1Transport> = {}): De1Transport {
	return {
		connectionState: 'connected',
		name: 'BOOKOO_SC',
		id: 'scale-1',
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

function mkCore(over: Partial<Pick<CremaCore, 'connectScale' | 'scaleUuids' | 'queryScaleSettings'>> = {}) {
	return {
		connectScale: vi.fn(() => Promise.resolve<string | undefined>('Bookoo Themis')),
		scaleUuids: vi.fn(() => Promise.resolve<ScaleUuids | undefined>(UUIDS())),
		queryScaleSettings: vi.fn(() => Promise.resolve(EMPTY_OUTPUT)),
		...over
	} as Pick<CremaCore, 'connectScale' | 'scaleUuids' | 'queryScaleSettings'>;
}

function mkDeps(device: De1Transport, core = mkCore()) {
	return {
		device,
		core,
		advertisedName: 'BOOKOO_SC',
		onStatus: vi.fn<(line: string) => void>(),
		onCoreOutput: vi.fn<(o: CoreOutput) => void>(),
		onScaleIdentified: vi.fn<(name: string) => void>(),
		onUuidsResolved: vi.fn<(u: ScaleUuids) => void>()
	} satisfies ScaleConnectDeps;
}

const run = (deps: ScaleConnectDeps) => Effect.runPromiseExit(scaleConnectProgram(deps));
const failStep = (exit: Exit.Exit<void, { step?: string }>) => {
	if (!Exit.isFailure(exit)) return undefined;
	const f = Cause.failureOption(exit.cause);
	return f._tag === 'Some' ? f.value.step : undefined;
};

describe('scaleConnectProgram — happy path', () => {
	it('connects, identifies, resolves UUIDs, subscribes both, and queries settings', async () => {
		const device = mkDevice();
		const deps = mkDeps(device);
		const exit = await run(deps);

		expect(Exit.isSuccess(exit)).toBe(true);
		expect(device.connectGatt).toHaveBeenCalledOnce();
		expect(deps.core.connectScale).toHaveBeenCalledWith('BOOKOO_SC', []);
		expect(deps.onUuidsResolved).toHaveBeenCalledOnce();
		// Distinct command characteristic → two subscriptions (weight + command).
		expect((device.startNotifications as ReturnType<typeof vi.fn>).mock.calls.map((c) => c[1])).toEqual([
			'wn',
			'cw'
		]);
		expect(deps.onScaleIdentified).toHaveBeenCalledWith('BOOKOO_SC');
		expect(deps.core.queryScaleSettings).toHaveBeenCalledOnce();
		expect(deps.onCoreOutput).toHaveBeenCalledOnce();
	});

	it('passes the discovered GATT services to the core for identification', async () => {
		const svc = '00001820-0000-1000-8000-00805f9b34fb';
		const device = mkDevice({ discoveredServiceUuids: vi.fn(() => Promise.resolve([svc])) });
		const deps = mkDeps(device);
		const exit = await run(deps);
		expect(Exit.isSuccess(exit)).toBe(true);
		expect(deps.core.connectScale).toHaveBeenCalledWith('BOOKOO_SC', [svc]);
	});

	it('subscribes only once when command == weight characteristic', async () => {
		const core = mkCore({
			scaleUuids: vi.fn(() => Promise.resolve(UUIDS({ command_write: 'wn' })))
		});
		const device = mkDevice();
		const exit = await run(mkDeps(device, core));
		expect(Exit.isSuccess(exit)).toBe(true);
		expect(device.startNotifications).toHaveBeenCalledTimes(1);
	});

	it('subscribes only to weight when the command characteristic is write-only', async () => {
		// The Decent Scale: command_write (e.g. 36f5) is distinct from
		// weight_notify but WRITE-ONLY — enabling notifications on it fails and
		// crashes the connect, so the shell must subscribe to weight only.
		const core = mkCore({
			scaleUuids: vi.fn(() =>
				Promise.resolve(UUIDS({ command_write: 'cw', command_notifies: false }))
			)
		});
		const device = mkDevice();
		const exit = await run(mkDeps(device, core));
		expect(Exit.isSuccess(exit)).toBe(true);
		expect(device.startNotifications).toHaveBeenCalledTimes(1);
		expect((device.startNotifications as ReturnType<typeof vi.fn>).mock.calls[0][1]).toBe('wn');
	});
});

describe('scaleConnectProgram — failure attribution', () => {
	it('fails "scale identification" when the core does not recognise the scale', async () => {
		const core = mkCore({ connectScale: vi.fn(() => Promise.resolve(undefined)) });
		const exit = await run(mkDeps(mkDevice(), core));
		expect(failStep(exit)).toBe('scale identification');
	});

	it('fails "scale UUIDs" when the core reports no UUIDs', async () => {
		const core = mkCore({ scaleUuids: vi.fn(() => Promise.resolve(undefined)) });
		const deps = mkDeps(mkDevice(), core);
		const exit = await run(deps);
		expect(failStep(exit)).toBe('scale UUIDs');
		expect(deps.onUuidsResolved).not.toHaveBeenCalled();
	});

	it('fails "GATT connect" when the transport connect rejects', async () => {
		const device = mkDevice({ connectGatt: vi.fn(() => Promise.reject(new Error('no gatt'))) });
		const exit = await run(mkDeps(device));
		expect(failStep(exit)).toBe('GATT connect');
		expect(device.startNotifications).not.toHaveBeenCalled();
	});

	it('fails "weight subscription" when the weight subscribe rejects', async () => {
		const device = mkDevice({ startNotifications: vi.fn(() => Promise.reject(new Error('nope'))) });
		const deps = mkDeps(device);
		const exit = await run(deps);
		expect(failStep(exit)).toBe('weight subscription');
		// UUIDs were resolved (sink installed) before the failing subscribe.
		expect(deps.onUuidsResolved).toHaveBeenCalledOnce();
	});
});
