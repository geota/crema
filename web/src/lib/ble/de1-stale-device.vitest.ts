/**
 * `De1Manager` ignores a replaced device's late callbacks (audit r9 L-1): a
 * reconnect loop that finishes, or a terminal disconnect that arrives, after
 * the device was dropped must not report a lost link or change the state of
 * whatever link replaced it. The drop itself reports the loss once.
 */

import { describe, expect, it, vi } from 'vitest';
import { Exit } from 'effect';
import type { AppRuntime } from '#lib/effect/runtime.js';
import type { CremaCore } from '#lib/core/index.js';
import { De1Manager } from './de1';

// Capture each connect program's dependencies so a test can play a stale
// program's late reports (the fake runtime never runs the Effect).
const programDeps: {
	onStatus(line: string): void;
	onCoreOutput(output: unknown): void;
	onGattVerified(): void;
}[] = [];
vi.mock('#lib/services/de1-orchestrator.js', () => ({
	de1ConnectProgram: (deps: (typeof programDeps)[number]) => {
		programDeps.push(deps);
		return {};
	}
}));
import type { De1Transport } from './de1-transport';

function fakeDevice(id = 'dev-A') {
	const cbs: { disconnected?: () => void; attempt?: (n: number) => void; reconnected?: () => void } = {};
	const device = {
		name: 'DE1',
		id,
		disconnect: vi.fn(),
		setSink: () => undefined,
		onDisconnected: (l: () => void) => (cbs.disconnected = l),
		onReconnectAttempt: (l: (n: number) => void) => (cbs.attempt = l),
		onReconnected: (l: () => void) => (cbs.reconnected = l)
	};
	return { device: device as unknown as De1Transport, cbs, disconnect: device.disconnect };
}

describe('De1Manager stale device', () => {
	it("drops a replaced device's late callbacks and reports the drop once", async () => {
		const states: string[] = [];
		const de1LinkLost = vi.fn(async () => ({ actions: [] }));
		const manager = new De1Manager({ de1LinkLost } as unknown as CremaCore, {
			onCoreOutput: () => undefined,
			onStatus: () => undefined,
			onState: (s) => states.push(s),
			onDiagnostics: () => undefined
		});
		const lost = vi.fn();
		manager.addLinkListener({ lost });
		const { device, cbs, disconnect } = fakeDevice();
		// No runtime: attach wires the callbacks, then drops the device.
		await (manager as unknown as { attach(d: De1Transport): Promise<void> }).attach(device);
		expect(disconnect).toHaveBeenCalledTimes(1);
		expect(de1LinkLost).toHaveBeenCalledTimes(1);
		expect(lost).toHaveBeenCalledTimes(1);
		states.length = 0;

		cbs.attempt?.(1);
		cbs.reconnected?.();
		cbs.disconnected?.();

		expect(states).toEqual([]);
		expect(de1LinkLost).toHaveBeenCalledTimes(1);
		expect(lost).toHaveBeenCalledTimes(1);
	});

	function managerWithRuntime() {
		const states: string[] = [];
		const finishes: ((exit: Exit.Exit<void, unknown>) => void)[] = [];
		const runtime = {
			runPromiseExit: () => new Promise((resolve) => finishes.push(resolve))
		} as unknown as AppRuntime;
		const manager = new De1Manager(
			{
				de1LinkLost: async () => ({ actions: [] }),
				reset: async () => ({ actions: [] })
			} as unknown as CremaCore,
			{
				onCoreOutput: () => undefined,
				onStatus: () => undefined,
				onState: (s) => states.push(s),
				onDiagnostics: () => undefined
			},
			runtime
		);
		const attach = (d: De1Transport) =>
			(manager as unknown as { attach(d: De1Transport): Promise<void> }).attach(d);
		return { manager, states, finishes, attach };
	}

	it('a stale handshake success leaves a different replacement alone and closes its own link (audit r10 L-2, r11 L-1)', async () => {
		const { manager, states, finishes, attach } = managerWithRuntime();
		const first = fakeDevice('dev-A');
		const pending = attach(first.device);
		await Promise.resolve();
		await manager.disconnect(); // mid-handshake
		expect(first.disconnect).toHaveBeenCalledTimes(1);
		const second = fakeDevice('dev-B');
		void attach(second.device);
		states.length = 0;
		finishes[0](Exit.void); // the first handshake completes late
		await pending;
		expect(states).not.toContain('ready');
		expect(second.disconnect).not.toHaveBeenCalled();
		expect(first.disconnect).toHaveBeenCalledTimes(2); // its late link is closed
		expect(manager.identity()?.id).toBe('dev-B');
	});

	it('a stale handshake on the same DE1 leaves the shared link to the replacement', async () => {
		const { manager, finishes, attach } = managerWithRuntime();
		const first = fakeDevice('dev-A');
		const pending = attach(first.device);
		await Promise.resolve();
		await manager.disconnect();
		const second = fakeDevice('dev-A');
		void attach(second.device);
		finishes[0](Exit.void);
		await pending;
		expect(first.disconnect).toHaveBeenCalledTimes(1); // not again: the GATT link is shared
		expect(second.disconnect).not.toHaveBeenCalled();
	});

	it('a stale handshake failure does not drop the replacement', async () => {
		const { manager, states, finishes, attach } = managerWithRuntime();
		const first = fakeDevice('dev-A');
		const pending = attach(first.device);
		await Promise.resolve();
		await manager.disconnect();
		const second = fakeDevice('dev-B');
		void attach(second.device);
		states.length = 0;
		finishes[0](Exit.fail({ step: 'connect', cause: new Error('Device was disconnected') }));
		await pending;
		expect(states).not.toContain('failed');
		expect(second.disconnect).not.toHaveBeenCalled();
		expect(manager.identity()?.id).toBe('dev-B');
	});

	it("a stale handshake's late reports reach nothing (audit r12 L-1)", async () => {
		programDeps.length = 0;
		const lines: string[] = [];
		const outputs: unknown[] = [];
		const diagnostics: unknown[] = [];
		const runtime = { runPromiseExit: () => new Promise(() => undefined) } as unknown as AppRuntime;
		const manager = new De1Manager(
			{ de1LinkLost: async () => ({ actions: [] }), reset: async () => ({ actions: [] }) } as unknown as CremaCore,
			{
				onCoreOutput: (o) => outputs.push(o),
				onStatus: (l) => lines.push(l),
				onState: () => undefined,
				onDiagnostics: (d) => diagnostics.push(d)
			},
			runtime
		);
		void (manager as unknown as { attach(d: De1Transport): Promise<void> }).attach(fakeDevice('dev-A').device);
		await Promise.resolve();
		const stale = programDeps[0];
		stale.onStatus('while current');
		expect(lines).toContain('while current');
		await manager.disconnect();
		lines.length = 0;
		outputs.length = 0;
		diagnostics.length = 0;
		stale.onStatus('Ready — receiving DE1 notifications');
		stale.onCoreOutput({ actions: [] });
		stale.onGattVerified();
		expect(lines).toEqual([]);
		expect(outputs).toEqual([]);
		expect(diagnostics).toEqual([]);
	});
});
