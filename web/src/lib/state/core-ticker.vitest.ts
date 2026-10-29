/**
 * The steady core clock (cohort review 2026-10-28, #2 N1): it ticks once a
 * second while the DE1 link is up or recovering, and stops when it is gone.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CORE_TICK_MS, CoreTicker } from './core-ticker';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

function ticker() {
	const ticks: number[] = [];
	const applied: unknown[] = [];
	let clock = 0;
	const t = new CoreTicker({
		core: {
			onTick: async (now) => {
				ticks.push(now);
				return { events: [], commands: [] };
			}
		},
		apply: (o) => applied.push(o),
		now: () => (clock += CORE_TICK_MS)
	});
	return { t, ticks, applied };
}

describe('CoreTicker', () => {
	it('ticks every second while connecting, subscribing, ready or reconnecting', async () => {
		for (const state of ['connecting', 'subscribing', 'ready', 'reconnecting'] as const) {
			const { t, ticks, applied } = ticker();
			t.onDe1State(state);
			await vi.advanceTimersByTimeAsync(3 * CORE_TICK_MS);
			expect(ticks, state).toHaveLength(3);
			expect(applied, state).toHaveLength(3);
			t.stop();
		}
	});

	it('does not tick while idle, disconnected or failed, and stops on a drop', async () => {
		const { t, ticks } = ticker();
		for (const state of ['idle', 'disconnected', 'failed'] as const) {
			t.onDe1State(state);
			await vi.advanceTimersByTimeAsync(3 * CORE_TICK_MS);
		}
		expect(ticks).toHaveLength(0);
		t.onDe1State('ready');
		await vi.advanceTimersByTimeAsync(CORE_TICK_MS);
		t.onDe1State('disconnected');
		await vi.advanceTimersByTimeAsync(5 * CORE_TICK_MS);
		expect(ticks).toHaveLength(1);
		expect(t.running).toBe(false);
	});

	it('repeated ready reports keep one clock', async () => {
		const { t, ticks } = ticker();
		t.onDe1State('ready');
		t.onDe1State('subscribing');
		t.onDe1State('ready');
		await vi.advanceTimersByTimeAsync(2 * CORE_TICK_MS);
		expect(ticks).toHaveLength(2);
		t.stop();
	});
});
