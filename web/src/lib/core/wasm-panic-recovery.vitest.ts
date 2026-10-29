/**
 * The real wasm bridge survives a Rust panic (cohort review 2026-10-25,
 * report 01 P2-3).
 *
 * On wasm32 a panic is a trap: wasm-bindgen never released the borrow it held
 * for the call, so every later call on the memoized `CremaBridge` threw
 * "recursive use of an object detected" until the page reloaded, and the only
 * trace was `RuntimeError: unreachable`. The bridge now keeps its core in a
 * take/put slot and logs panics through a hook; these tests drive the built
 * module (`pnpm wasm`, loaded by `vitest.setup.ts`) to prove it.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

import { CremaBridge, last_panic_message, NotificationSource } from '#lib/wasm/de1_wasm.js';

/** A DE1 `StateInfo` notification for Idle. */
function idle(bridge: CremaBridge): { events: unknown[] } {
	return JSON.parse(
		bridge.on_notification(NotificationSource.De1State, new Uint8Array([2, 0]), 1_000)
	);
}

afterEach(() => {
	vi.restoreAllMocks();
});

describe('CremaBridge after a panic', () => {
	it('traps once, logs the message, and keeps serving calls', () => {
		const errors = vi.spyOn(console, 'error').mockImplementation(() => {});
		const bridge = new CremaBridge();
		expect(Array.isArray(idle(bridge).events)).toBe(true);

		expect(() => bridge.debug_trap()).toThrow(WebAssembly.RuntimeError);

		// The panic hook ran before the trap: the message and location are not lost.
		expect(errors.mock.calls.flat().join('\n')).toContain('CremaBridge::debug_trap');
		expect(last_panic_message()).toContain('CremaBridge::debug_trap');

		// The next call recovers instead of throwing "recursive use of an object".
		expect(Array.isArray(idle(bridge).events)).toBe(true);
		expect(bridge.panic_recoveries()).toBe(1);
		// …and the ones after it are ordinary.
		expect(Array.isArray(idle(bridge).events)).toBe(true);
		expect(bridge.in_replay()).toBe(false);
		expect(bridge.panic_recoveries()).toBe(1);
	});

	it('recovers from repeated panics, one reset each', () => {
		vi.spyOn(console, 'error').mockImplementation(() => {});
		const bridge = new CremaBridge();
		for (let i = 1; i <= 3; i++) {
			expect(() => bridge.debug_trap()).toThrow(WebAssembly.RuntimeError);
			expect(Array.isArray(idle(bridge).events)).toBe(true);
			expect(bridge.panic_recoveries()).toBe(i);
		}
	});

	it("no longer panics on the reviewer's inputs", () => {
		const bridge = new CremaBridge();
		// 01 P1-2: a huge persisted flush timeout trapped on every connect.
		const sweep = JSON.parse(bridge.connect_sweep('{"flushTimeoutS":1e30}', 1_000));
		expect(Array.isArray(sweep.commands)).toBe(true);
		// 01 P1-1: a huge finite shot time trapped the import.
		expect(() =>
			bridge.import_v2_json_shot('{"clock":1700000000,"meta":{"time":1e30}}')
		).not.toThrow();
		expect(bridge.panic_recoveries()).toBe(0);
	});
});
