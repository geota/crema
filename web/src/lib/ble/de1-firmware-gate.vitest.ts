/**
 * `De1Manager`'s firmware gate: while a flash holds the link, every ordinary
 * DE1 write is dropped (with a status line), so no shell-side writer — the
 * connect sweep, a user-present poke, a brew control — can cut in.
 */

import { describe, expect, it } from 'vitest';
import type { CremaCore } from '$lib/core';
import { WriteTarget } from '$lib/core/crema-core';
import { De1Manager } from './de1';

describe('De1Manager firmware gate', () => {
	it('drops a non-flash write while exclusive, and only then', async () => {
		const lines: string[] = [];
		const manager = new De1Manager({} as CremaCore, {
			onCoreOutput: () => undefined,
			onStatus: (line) => lines.push(line),
			onState: () => undefined,
			onDiagnostics: () => undefined
		});
		manager.setFirmwareExclusive(true);
		await manager.writeCharacteristic(WriteTarget.De1MmrWrite, Uint8Array.from([4, 0x80, 0x38, 0x60]));
		expect(lines.at(-1)).toBe('DE1 write skipped: firmware update in progress (De1MmrWrite)');
		manager.setFirmwareExclusive(false);
		await expect(manager.firmwareWrite(WriteTarget.De1MmrWrite, new Uint8Array(4))).rejects.toThrow(
			'not connected'
		);
	});
});
