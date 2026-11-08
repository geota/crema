/**
 * The core's `Command::Stop` reaches the DE1: Idle (`0x02`) on the
 * requested-state characteristic, ahead of the queue when the transport can,
 * and nothing once a `CancelStop` made the guard refuse.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import type { CremaCore } from '#lib/core/index.js';
import { MachineState, WriteTarget } from '#lib/core/crema-core.js';
import { initTestWasm } from '#lib/testing/test-init.js';
import { de1WriteTargetUuid } from '#lib/wasm/de1_wasm.js';
import { De1Manager, STOP_BYTE } from './de1';

type Written = { uuid: string; data: number[]; urgent: boolean };

function manager(device: object): De1Manager {
	const m = new De1Manager({} as CremaCore, {
		onCoreOutput: () => undefined,
		onStatus: () => undefined,
		onState: () => undefined,
		onDiagnostics: () => undefined
	});
	(m as unknown as { device: unknown }).device = device;
	return m;
}

beforeAll(async () => {
	await initTestWasm();
});

describe('De1Manager.writeStop', () => {
	it("writes the byte the core's own Idle request carries", async () => {
		const { loadCore } = await import('#lib/core/index.js');
		const core = await loadCore();
		core.reset();
		// No flow session: the core's Idle is a plain write of its Idle byte.
		const out = await core.requestMachineState(MachineState.Idle);
		const idle = out.commands.find(
			(c) => c.type === 'WriteCharacteristic' && c.content.target === WriteTarget.De1RequestedState
		);
		expect(idle?.type === 'WriteCharacteristic' && idle.content.data).toEqual([STOP_BYTE]);
	});

	it('writes Idle to the requested-state characteristic on the urgent lane', async () => {
		const written: Written[] = [];
		const m = manager({
			write: async () => undefined,
			writeUrgent: async (_s: string, uuid: string, data: Uint8Array, guard: () => boolean) => {
				if (!guard()) return false;
				written.push({ uuid, data: [...data], urgent: true });
				return true;
			}
		});
		await expect(m.writeStop(() => true)).resolves.toBe(true);
		expect(written).toEqual([
			{ uuid: de1WriteTargetUuid(WriteTarget.De1RequestedState), data: [STOP_BYTE], urgent: true }
		]);
	});

	it('writes nothing once the guard refuses', async () => {
		const written: Written[] = [];
		const m = manager({
			write: async () => undefined,
			writeUrgent: async (_s: string, uuid: string, data: Uint8Array, guard: () => boolean) => {
				if (!guard()) return false;
				written.push({ uuid, data: [...data], urgent: true });
				return true;
			}
		});
		await expect(m.writeStop(() => false)).resolves.toBe(false);
		expect(written).toEqual([]);
	});

	it('still stops through a transport with no urgent lane', async () => {
		const written: Written[] = [];
		const m = manager({
			write: async (_s: string, uuid: string, data: Uint8Array) => {
				written.push({ uuid, data: [...data], urgent: false });
			}
		});
		await expect(m.writeStop(() => true)).resolves.toBe(true);
		expect(written.map((w) => w.data)).toEqual([[STOP_BYTE]]);
	});
});
