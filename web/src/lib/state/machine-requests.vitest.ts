/**
 * F16a on web: the core holds a state request through the firmware's
 * profile-download guard and asks for the tick that releases it (`WakeAt`);
 * the app's one wake timer delivers that tick, and is cancelled when the link
 * goes or the tab is hidden. A real wasm core in a real `CremaApp`, the DE1's
 * GATT write replaced.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CoreOutput } from '#lib/core/crema-core.js';

const IDLE = 2;
/** The RequestedState byte for Steam / Idle (`de1_protocol::requested_state`). */
const STEAM = 5;
const STOP = 2;

function modelReply(): Uint8Array {
	const packet = new Uint8Array(20);
	packet.set([0x80, 0x00, 0x0c], 1);
	new DataView(packet.buffer).setUint32(4, 1, true);
	return packet;
}

beforeEach(() => {
	localStorage.clear();
	vi.useFakeTimers({
		toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'performance']
	});
});

afterEach(() => {
	vi.useRealTimers();
	vi.restoreAllMocks();
});

async function setup() {
	const { loadCore } = await import('#lib/core/index.js');
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	const { De1Manager } = await import('#lib/ble/index.js');
	const { getProfileStore, brewTempRange } = await import('#lib/profiles/index.js');
	const requests: number[] = [];
	vi.spyOn(De1Manager.prototype, 'writeCharacteristic').mockImplementation(
		async (target: string, data: Uint8Array, guard?: () => boolean, settled?: (ok: boolean) => void) => {
			if (guard && !guard()) return false;
			if (target === 'De1RequestedState') requests.push(data[0]);
			settled?.(true);
			return true;
		}
	);
	const core = await loadCore();
	core.reset();
	await core.setDesiredProfile(null, {}, 0);
	const app = new CremaApp(core, null);
	const internals = app as unknown as {
		de1: { callbacks: { onState(s: string): void } };
		applyCoreOutput(o: CoreOutput): void;
		wake: { pending: boolean };
	};
	internals.applyCoreOutput(await core.onNotification('De1MmrRead', modelReply(), performance.now()));
	internals.de1.callbacks.onState('ready');
	internals.applyCoreOutput(
		await core.onNotification('De1State', new Uint8Array([IDLE, 0]), performance.now())
	);
	const profiles = getProfileStore();
	await profiles.ensureLoaded();
	const profile = profiles.all.find((p) => p.segments.length <= 20 && brewTempRange(p).max > 0)!;
	return { app, core, internals, requests, profile };
}

describe('state requests wait out the profile-download guard (F16a, web)', () => {
	it('a request right after an upload goes out 500 ms later, on the wake tick', async () => {
		const { app, requests, profile, internals } = await setup();
		await app.setDesiredProfile(profile);
		await vi.advanceTimersByTimeAsync(0); // the upload's acks land
		requests.length = 0;
		await app.requestMachineState('Steam' as never);
		expect(requests, 'held inside the guard').toEqual([]);
		expect(internals.wake.pending).toBe(true);
		await vi.advanceTimersByTimeAsync(499);
		expect(requests).toEqual([]);
		await vi.advanceTimersByTimeAsync(1);
		expect(requests).toEqual([STEAM]);
	});

	it('Stop is never held', async () => {
		const { app, requests, profile } = await setup();
		await app.setDesiredProfile(profile);
		await vi.advanceTimersByTimeAsync(0);
		requests.length = 0;
		await app.requestMachineState('Idle' as never);
		expect(requests).toEqual([STOP]);
	});

	it('the wake tick is cancelled when the link goes or the tab is hidden', async () => {
		const { app, core, internals, profile } = await setup();
		const ticks = vi.spyOn(core, 'onTick');
		await app.setDesiredProfile(profile);
		await vi.advanceTimersByTimeAsync(0);
		await app.requestMachineState('Steam' as never);
		expect(internals.wake.pending).toBe(true);
		internals.de1.callbacks.onState('disconnected');
		expect(internals.wake.pending).toBe(false);
		await vi.advanceTimersByTimeAsync(1_000);
		expect(ticks).not.toHaveBeenCalled();

		// Hidden tab.
		internals.applyCoreOutput({
			events: [{ type: 'WakeAt', content: { at_ms: performance.now() + 500 } }],
			commands: []
		} as CoreOutput);
		expect(internals.wake.pending).toBe(true);
		Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
		document.dispatchEvent(new Event('visibilitychange'));
		expect(internals.wake.pending).toBe(false);
		await vi.advanceTimersByTimeAsync(1_000);
		expect(ticks).not.toHaveBeenCalled();
		Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
	});
});
