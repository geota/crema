/**
 * The facade's guard around a stopped wasm bridge (final cohort review
 * 2026-10-28, 01 P3-1): after the recovery cap the bridge throws on every
 * call, and each throw from inside the wasm leaks its shadow-stack frame, so
 * the shell must stop calling in. The last test drives the real module.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

import { CremaBridge, NotificationSource } from '#lib/wasm/de1_wasm.js';
import { CoreStoppedError, guardStoppedBridge, isCoreStopped } from './stopped-guard.js';

afterEach(() => {
	vi.restoreAllMocks();
});

/** A stand-in bridge whose `tick` fails once `stopped` is set. */
class FakeBridge {
	stopped = false;
	recover = false;
	calls = 0;
	core_stopped(): boolean {
		return this.stopped;
	}
	panic_recoveries(): number {
		return this.stopped ? 5 : 0;
	}
	tick(n: number): number {
		this.calls++;
		if (this.stopped) throw new Error('crema core stopped: reload');
		if (this.recover) throw new WebAssembly.RuntimeError('unreachable');
		return n * 2;
	}
}

describe('guardStoppedBridge', () => {
	it('passes calls and their results through while the core runs', () => {
		const fake = new FakeBridge();
		const onStopped = vi.fn();
		const bridge = guardStoppedBridge(fake, onStopped);
		expect(bridge.tick(21)).toBe(42);
		expect(fake.calls).toBe(1);
		// A trap the bridge recovers from is not a stop.
		fake.recover = true;
		expect(() => bridge.tick(1)).toThrow(WebAssembly.RuntimeError);
		fake.recover = false;
		expect(bridge.tick(2)).toBe(4);
		expect(onStopped).not.toHaveBeenCalled();
	});

	it('tells the shell once and never calls into a stopped bridge again', () => {
		const fake = new FakeBridge();
		const onStopped = vi.fn();
		const bridge = guardStoppedBridge(fake, onStopped);
		fake.stopped = true;
		expect(() => bridge.tick(1)).toThrow(CoreStoppedError);
		expect(onStopped).toHaveBeenCalledTimes(1);
		expect(fake.calls).toBe(1);
		for (let i = 0; i < 10; i++) {
			expect(() => bridge.tick(i)).toThrow('crema core stopped: reload');
		}
		expect(fake.calls).toBe(1);
		expect(onStopped).toHaveBeenCalledTimes(1);
		// The read-only counters stay callable.
		expect(bridge.core_stopped()).toBe(true);
		expect(bridge.panic_recoveries()).toBe(5);
	});

	it('keeps a stopped real bridge answering with the clear error, call after call', () => {
		vi.spyOn(console, 'error').mockImplementation(() => {});
		const onStopped = vi.fn();
		const bridge = guardStoppedBridge(new CremaBridge(), onStopped);
		const idle = () =>
			bridge.on_notification(NotificationSource.De1State, new Uint8Array([2, 0]), 1_000);
		// MAX_PANIC_RECOVERIES (5) resets, then one panic too many.
		for (let i = 0; i < 6; i++) {
			expect(() => bridge.debug_trap()).toThrow(WebAssembly.RuntimeError);
			if (i < 5) expect(() => idle()).not.toThrow();
		}
		expect(() => idle()).toThrow(CoreStoppedError);
		expect(onStopped).toHaveBeenCalledTimes(1);
		// Unguarded, a few hundred of these exhausted the shadow stack and
		// turned into "memory access out of bounds"; guarded, none reach the wasm.
		for (let i = 0; i < 20_000; i++) {
			expect(() => bridge.on_tick(i)).toThrow(/^crema core stopped: /);
		}
		expect(bridge.core_stopped()).toBe(true);
		expect(bridge.panic_recoveries()).toBe(5);
		// The module still works for a fresh bridge, as after a reload.
		const fresh = new CremaBridge();
		expect(() => fresh.on_tick(1)).not.toThrow();
	});
});

describe('guardStoppedBridge stop context (V2 P3-2)', () => {
	it("hands the bridge's stop message to the shell, the context it logs once", () => {
		const fake = new FakeBridge();
		const onStopped = vi.fn();
		const bridge = guardStoppedBridge(fake, onStopped);
		fake.stopped = true;
		expect(() => bridge.tick(1)).toThrow(CoreStoppedError);
		expect(onStopped).toHaveBeenCalledWith('crema core stopped: reload');
	});

	it('isCoreStopped tells a refused call from any other failure', () => {
		expect(isCoreStopped(new CoreStoppedError('crema core stopped'))).toBe(true);
		expect(isCoreStopped(new Error('bad packet'))).toBe(false);
		expect(isCoreStopped('crema core stopped')).toBe(false);
	});
});
