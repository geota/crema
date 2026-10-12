/**
 * `De1Manager` ignores a replaced device's late callbacks (audit r9 L-1): a
 * reconnect loop that finishes, or a terminal disconnect that arrives, after
 * the device was dropped must not report a lost link or change the state of
 * whatever link replaced it. The drop itself reports the loss once.
 */

import { describe, expect, it, vi } from 'vitest';
import type { CremaCore } from '$lib/core';
import { De1Manager } from './de1';
import type { De1Transport } from './de1-transport';

function fakeDevice() {
	const cbs: { disconnected?: () => void; attempt?: (n: number) => void; reconnected?: () => void } = {};
	const device = {
		name: 'DE1',
		id: 'dev-A',
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
});
