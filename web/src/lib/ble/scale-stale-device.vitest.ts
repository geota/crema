/**
 * `ScaleManager` and a stale connect program (audit r13 L-1): once the user
 * disconnects (or a new scale replaces it), the old handshake can't identify
 * a scale in the core, report status / identity / UUIDs, or change state;
 * and a failed handshake clears whatever the core already identified, so no
 * "ghost" scale arms stop-at-weight with no weight data.
 */

import { describe, expect, it, vi } from 'vitest';
import { Exit } from 'effect';
import type { AppRuntime } from '$lib/effect/runtime';
import type { CremaCore } from '$lib/core';
import type { ScaleConnectDeps } from '$lib/services/scale-orchestrator';
import { ScaleManager } from './scale';
import type { BleDevice } from './transport';

const programDeps: ScaleConnectDeps[] = [];
// The chooser is cancelled (connect()'s requestDevice rejects).
vi.mock('./transport', async (importOriginal) => ({
	...(await importOriginal<typeof import('./transport')>()),
	requestDevice: () => Promise.reject(new Error('User cancelled the requestDevice() chooser.'))
}));
vi.mock('$lib/wasm/de1_wasm', async (importOriginal) => ({
	...(await importOriginal<typeof import('$lib/wasm/de1_wasm')>()),
	scaleScanUuids: () => JSON.stringify({ name_prefixes: ['BOOKOO'], service_uuids: [] })
}));
vi.mock('$lib/services/scale-orchestrator', () => ({
	scaleConnectProgram: (deps: ScaleConnectDeps) => {
		programDeps.push(deps);
		return {};
	}
}));

function fakeScale(id = 'scale-A') {
	const cbs: { disconnected?: () => void; attempt?: (n: number) => void; reconnected?: () => void } = {};
	const device = {
		name: 'BOOKOO_SC',
		id,
		disconnect: vi.fn(),
		setSink: () => undefined,
		setReconnectTimeline: () => undefined,
		onDisconnected: (l: () => void) => (cbs.disconnected = l),
		onReconnectAttempt: (l: (n: number) => void) => (cbs.attempt = l),
		onReconnected: (l: () => void) => (cbs.reconnected = l)
	};
	return { device: device as unknown as BleDevice, cbs, disconnect: device.disconnect };
}

function setup() {
	programDeps.length = 0;
	const states: string[] = [];
	const lines: string[] = [];
	const identified: string[] = [];
	const finishes: ((exit: Exit.Exit<void, unknown>) => void)[] = [];
	const core = {
		connectScale: vi.fn(async () => 'Bookoo'),
		scaleUuids: vi.fn(async () => ({})),
		queryScaleSettings: vi.fn(async () => ({ actions: [] })),
		disconnectScale: vi.fn(async () => undefined)
	};
	const runtime = {
		runPromiseExit: () => new Promise((resolve) => finishes.push(resolve))
	} as unknown as AppRuntime;
	const manager = new ScaleManager(
		core as unknown as CremaCore,
		{
			onCoreOutput: () => undefined,
			onStatus: (l) => lines.push(l),
			onState: (s) => states.push(s),
			onScaleIdentified: (n) => identified.push(n),
			onReconnected: () => undefined
		},
		runtime
	);
	const attach = (d: BleDevice) =>
		(manager as unknown as { attach(d: BleDevice): Promise<void> }).attach(d);
	return { manager, core, states, lines, identified, finishes, attach };
}

describe('ScaleManager stale handshake', () => {
	it('a handshake abandoned by Disconnect identifies nothing and reports nothing', async () => {
		const t = setup();
		const scale = fakeScale();
		const pending = t.attach(scale.device);
		await Promise.resolve();
		const stale = programDeps[0];
		await t.manager.disconnect();
		expect(t.core.disconnectScale).toHaveBeenCalledTimes(1);
		t.states.length = 0;
		t.lines.length = 0;

		await expect(stale.core.connectScale('BOOKOO_SC', [])).rejects.toThrow('Scale was disconnected');
		await expect(stale.core.scaleUuids()).rejects.toThrow();
		await expect(stale.core.queryScaleSettings()).rejects.toThrow();
		expect(t.core.connectScale).not.toHaveBeenCalled();
		stale.onStatus('Core recognised scale: Bookoo');
		stale.onScaleIdentified('BOOKOO_SC');
		scale.cbs.reconnected?.();
		t.finishes[0](Exit.void);
		await pending;

		expect(t.lines).toEqual([]);
		expect(t.identified).toEqual([]);
		expect(t.states).toEqual([]);
	});

	it('a failed handshake clears a scale the core already identified', async () => {
		const t = setup();
		const pending = t.attach(fakeScale().device);
		await Promise.resolve();
		await programDeps[0].core.connectScale('BOOKOO_SC', []);
		t.finishes[0](Exit.fail({ step: 'scale UUIDs', cause: new Error('boom') }));
		await pending;
		expect(t.states.at(-1)).toBe('failed');
		expect(t.core.disconnectScale).toHaveBeenCalledTimes(1);
	});

	it('Re-pair with the chooser cancelled leaves no ghost scale (audit r14 L-1)', async () => {
		const t = setup();
		const old = fakeScale();
		const pending = t.attach(old.device);
		await Promise.resolve();
		t.finishes[0](Exit.void);
		await pending; // the old scale is ready
		expect(t.core.disconnectScale).not.toHaveBeenCalled();
		await t.manager.connect(); // Re-pair; the chooser is cancelled
		expect(old.disconnect).toHaveBeenCalledTimes(1);
		expect(t.core.disconnectScale).toHaveBeenCalledTimes(1);
		expect(t.states.at(-1)).toBe('failed');
	});

	it("a stale handshake's late failure leaves the replacement scale identified", async () => {
		const t = setup();
		const pendingOld = t.attach(fakeScale('scale-A').device);
		await Promise.resolve();
		void t.attach(fakeScale('scale-B').device); // replaced mid-handshake
		await Promise.resolve();
		const before = t.core.disconnectScale.mock.calls.length;
		t.finishes[0](Exit.fail({ step: 'scale UUIDs', cause: new Error('late') }));
		await pendingOld;
		expect(t.core.disconnectScale.mock.calls.length).toBe(before);
		expect(t.states).not.toContain('failed');
	});

	it('a teardown forgets the scale before closing it, so its own disconnect event reports nothing', async () => {
		const t = setup();
		const scale = fakeScale();
		// Like Chromium: closing the link fires the device's disconnect event at once.
		scale.disconnect.mockImplementation(() => scale.cbs.disconnected?.());
		const pending = t.attach(scale.device);
		await Promise.resolve();
		t.finishes[0](Exit.void);
		await pending;
		t.states.length = 0;
		await t.manager.disconnect();
		expect(scale.disconnect).toHaveBeenCalledTimes(1);
		expect(t.states).toEqual(['disconnected']); // the explicit one only

		const next = fakeScale('scale-B');
		next.disconnect.mockImplementation(() => next.cbs.disconnected?.());
		const pendingNext = t.attach(next.device);
		await Promise.resolve();
		t.finishes[1](Exit.void);
		await pendingNext;
		t.states.length = 0;
		await t.manager.connect(); // Re-pair; the chooser is cancelled
		expect(next.disconnect).toHaveBeenCalledTimes(1);
		expect(t.states).toEqual(['connecting', 'failed']);
	});
});
