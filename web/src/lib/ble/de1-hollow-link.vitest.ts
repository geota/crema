/**
 * `De1Manager` over a hollow link (#167 follow-up, cohort review 2026-10-25):
 * a first or launch connect whose StateInfo read never answers goes to the
 * reconnect loop instead of failing terminally; the connect program runs with
 * the reconnect loop held, so a timed-out read cannot start a second loop;
 * a program that finishes over a link that dropped meanwhile never reports
 * ready; and a re-seed after a reconnect hands a hollow link back to the
 * loop through the hold, which keeps its place on the backoff ladder.
 */

import { describe, expect, it, vi } from 'vitest';
import { Effect, Exit } from 'effect';
import type { AppRuntime } from '#lib/effect/runtime.js';
import type { CremaCore } from '#lib/core/index.js';

vi.mock('#lib/services/de1-orchestrator.js', async (importOriginal) => {
	const real = await importOriginal<typeof import('#lib/services/de1-orchestrator.js')>();
	return {
		STATE_SEED_STEP: 'machine state read',
		de1ConnectProgram: () => ({}),
		de1SeedProgram: () => ({}),
		// The real identity program, so the test sees the reads it dispatches.
		de1MachineInfoProgram: real.de1MachineInfoProgram
	};
});
vi.mock('#lib/wasm/de1_wasm.js', () => ({ de1WriteTargetUuid: () => null }));
vi.mock('./de1-uuids', () => ({
	De1Uuids: { SERVICE: 'a000', STATE_INFO: 'a00e', FW_MAP_REQUEST: 'a009' },
	// The wasm bundle is mocked out, so the core's identity table is stood in
	// for by the registers this test checks.
	connectIdentityRegisters: () => [
		'FirmwareVersion',
		'MachineModel',
		'GhcInfo',
		'SerialNumber',
		'HeaterVoltage'
	],
	de1NotificationSource: () => null
}));

import { De1Manager } from './de1';
import { HOLLOW_LINK_STATUS } from './stuck-link';
import type { De1Transport } from './de1-transport';

function fakeDevice(releaseResult = false) {
	const cbs: { reconnected?: () => void; attempt?: (n: number) => void } = {};
	const device = {
		name: 'DE1',
		id: 'dev-A',
		connectionState: 'connected',
		disconnect: vi.fn(),
		setSink: () => undefined,
		onDisconnected: () => undefined,
		onReconnectAttempt: (l: (n: number) => void) => (cbs.attempt = l),
		onReconnected: (l: () => void) => (cbs.reconnected = l),
		readCharacteristic: vi.fn(async () => new Uint8Array()),
		holdReconnect: vi.fn(),
		releaseReconnect: vi.fn((_healthy: boolean) => releaseResult),
		recycle: vi.fn()
	};
	return { device, transport: device as unknown as De1Transport, cbs };
}

function setup(exits: Exit.Exit<void, unknown>[]) {
	const states: string[] = [];
	const lines: string[] = [];
	/** Identity reads dispatched and states reported, in order. */
	const timeline: string[] = [];
	const runtime = {
		// The mocked connect / seed programs are plain objects and take the
		// scripted exits; a real Effect (the identity program) really runs.
		runPromiseExit: vi.fn(async (program: unknown) =>
			Effect.isEffect(program)
				? Effect.runPromiseExit(program as Effect.Effect<void>)
				: (exits.shift() ?? Exit.void)
		)
	} as unknown as AppRuntime;
	const manager = new De1Manager(
		{
			de1LinkLost: async () => ({ actions: [] }),
			reset: async () => ({ actions: [] }),
			readMmr: async (reg: string) => ({ events: [], commands: [], reg })
		} as unknown as CremaCore,
		{
			onCoreOutput: (out) => {
				const reg = (out as unknown as { reg?: string }).reg;
				if (reg !== undefined) timeline.push(`read ${reg}`);
			},
			onStatus: (l) => lines.push(l),
			onState: (s) => {
				states.push(s);
				timeline.push(`state ${s}`);
			},
			onDiagnostics: () => undefined
		},
		runtime
	);
	const attach = (d: De1Transport) =>
		(manager as unknown as { attach(d: De1Transport): Promise<void> }).attach(d);
	return { manager, states, lines, timeline, attach };
}

const hollow = Exit.fail({ _tag: 'De1ConnectStepFailed', step: 'machine state read', cause: new Error('timed out') });

describe('De1Manager hollow first connect', () => {
	it('goes to the reconnect loop, not to a terminal failure', async () => {
		const { states, lines, attach } = setup([hollow]);
		const f = fakeDevice();
		await attach(f.transport);
		expect(f.device.holdReconnect).toHaveBeenCalledOnce();
		expect(f.device.releaseReconnect).toHaveBeenCalledWith(false);
		expect(f.device.disconnect).not.toHaveBeenCalled();
		expect(states).not.toContain('failed');
		expect(states[states.length - 1]).toBe('reconnecting');
		expect(lines).toContain(HOLLOW_LINK_STATUS);
	});

	it('still fails terminally at a structural step (not a DE1)', async () => {
		const { states, attach } = setup([
			Exit.fail({ _tag: 'De1ConnectStepFailed', step: 'StateInfo characteristic A00E', cause: new Error('no') })
		]);
		const f = fakeDevice();
		await attach(f.transport);
		expect(f.device.disconnect).toHaveBeenCalled();
		expect(states[states.length - 1]).toBe('failed');
	});

	it('holds the loop for the whole program and reports ready only over a healthy link', async () => {
		const { states, attach, manager } = setup([Exit.void]);
		const f = fakeDevice(true);
		await attach(f.transport);
		expect(f.device.holdReconnect).toHaveBeenCalledBefore(f.device.releaseReconnect);
		expect(f.device.releaseReconnect).toHaveBeenCalledWith(true);
		expect(states[states.length - 1]).toBe('ready');
		await manager.disconnect();
	});

	it('a program that finishes after the link dropped reports reconnecting, never ready', async () => {
		const { states, attach } = setup([Exit.void]);
		const f = fakeDevice(false);
		await attach(f.transport);
		expect(states).not.toContain('ready');
		expect(states[states.length - 1]).toBe('reconnecting');
	});
});

describe('De1Manager identity reads after a hollow first connect (cohort review 2026-10-28, #3 P1)', () => {
	it('the first healthy re-seed reads the model, serial and firmware build before ready', async () => {
		const { timeline, attach, manager } = setup([hollow, Exit.void]);
		const f = fakeDevice(true);
		await attach(f.transport);
		expect(timeline).not.toContain('state ready');
		f.cbs.reconnected?.();
		await vi.waitFor(() => expect(timeline).toContain('state ready'));
		const ready = timeline.indexOf('state ready');
		for (const reg of ['FirmwareVersion', 'MachineModel', 'SerialNumber', 'HeaterVoltage', 'GhcInfo']) {
			const at = timeline.indexOf(`read ${reg}`);
			expect(at, reg).toBeGreaterThanOrEqual(0);
			expect(at, reg).toBeLessThan(ready);
		}
		await manager.disconnect();
	});

	it('a later re-seed on a link that already read its identity does not re-read it', async () => {
		const { timeline, attach, manager } = setup([Exit.void, Exit.void]);
		const f = fakeDevice(true);
		await attach(f.transport);
		timeline.length = 0;
		f.cbs.reconnected?.();
		await vi.waitFor(() => expect(timeline).toContain('state ready'));
		expect(timeline.filter((t) => t.startsWith('read '))).toEqual([]);
		await manager.disconnect();
	});
});

describe('De1Manager re-seed after a reconnect', () => {
	it('holds synchronously on recovery and hands a hollow link back through the hold', async () => {
		const { states, attach, manager } = setup([Exit.void, hollow]);
		const f = fakeDevice(true);
		await attach(f.transport);
		f.device.holdReconnect.mockClear();
		f.device.releaseReconnect.mockClear();
		f.cbs.reconnected?.();
		// Held before any await — nothing can start a second loop.
		expect(f.device.holdReconnect).toHaveBeenCalledOnce();
		await vi.waitFor(() => expect(f.device.releaseReconnect).toHaveBeenCalledWith(false));
		expect(f.device.recycle).not.toHaveBeenCalled();
		expect(states[states.length - 1]).not.toBe('ready');
		await manager.disconnect();
	});
});
