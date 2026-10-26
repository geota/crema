/**
 * `De1Manager.writeCharacteristic` reports whether the GATT write reached the
 * DE1. The executor synthesises a profile header / frame ack only on `true`,
 * so a failed write can no longer be acknowledged as a loaded profile frame
 * (cohort review 02 P0-2).
 */

import { describe, expect, it } from 'vitest';
import type { CremaCore } from '#lib/core/index.js';
import { WriteTarget } from '#lib/core/crema-core.js';
import { De1Manager } from './de1';

function manager(lines: string[] = []): De1Manager {
	return new De1Manager({} as CremaCore, {
		onCoreOutput: () => undefined,
		onStatus: (line) => lines.push(line),
		onState: () => undefined,
		onDiagnostics: () => undefined
	});
}

/** Install a fake transport (the field is private; this is a test seam). */
function withDevice(m: De1Manager, write: () => Promise<void>): void {
	(m as unknown as { device: unknown }).device = { write };
}

const frame = Uint8Array.from([0, 0x40, 0x90, 0xb4, 0x14, 0, 0x04, 0]);

describe('De1Manager.writeCharacteristic result', () => {
	it('resolves true when the write succeeds', async () => {
		const m = manager();
		withDevice(m, async () => undefined);
		await expect(m.writeCharacteristic(WriteTarget.De1ProfileFrame, frame)).resolves.toBe(true);
	});

	it('resolves false (and logs) when the write fails', async () => {
		const lines: string[] = [];
		const m = manager(lines);
		withDevice(m, async () => {
			throw new Error('GATT operation failed');
		});
		await expect(m.writeCharacteristic(WriteTarget.De1ProfileFrame, frame)).resolves.toBe(false);
		expect(lines.at(-1)).toContain('DE1 write to De1ProfileFrame failed');
	});

	it('resolves false when not connected or a flash holds the link', async () => {
		const m = manager();
		await expect(m.writeCharacteristic(WriteTarget.De1ProfileHeader, frame)).resolves.toBe(false);
		withDevice(m, async () => undefined);
		m.setFirmwareExclusive(true);
		await expect(m.writeCharacteristic(WriteTarget.De1ProfileHeader, frame)).resolves.toBe(false);
	});
});
