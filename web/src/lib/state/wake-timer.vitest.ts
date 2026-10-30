/**
 * F16 (AC-F16.1, lead's rule): the core's wake hint is one one-shot timer,
 * replaced by each hint, cancelled cleanly, and never firing once cancelled.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WakeTimer } from './wake-timer';

beforeEach(() => {
	vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'performance'] });
});
afterEach(() => vi.useRealTimers());

function timer() {
	const ticks: number[] = [];
	const wake = new WakeTimer(
		() => performance.now(),
		(at) => ticks.push(at)
	);
	return { wake, ticks };
}

describe('WakeTimer', () => {
	it('ticks the core at the hinted time', () => {
		const { wake, ticks } = timer();
		const at = performance.now() + 500;
		wake.arm(at);
		vi.advanceTimersByTime(499);
		expect(ticks).toEqual([]);
		vi.advanceTimersByTime(1);
		expect(ticks).toEqual([at]);
		expect(wake.pending).toBe(false);
	});

	it('keeps at most one wake: a new hint replaces the pending one', () => {
		const { wake, ticks } = timer();
		const t0 = performance.now();
		wake.arm(t0 + 1_500);
		wake.arm(t0 + 500);
		expect(vi.getTimerCount()).toBe(1);
		vi.advanceTimersByTime(2_000);
		expect(ticks).toEqual([t0 + 500]);
	});

	it('never fires once cancelled (backgrounded, reset, link gone)', () => {
		const { wake, ticks } = timer();
		wake.arm(performance.now() + 500);
		wake.cancel();
		expect(wake.pending).toBe(false);
		expect(vi.getTimerCount()).toBe(0);
		vi.advanceTimersByTime(10_000);
		expect(ticks).toEqual([]);
		wake.cancel(); // a second cancel is harmless
	});

	it('a hint already past ticks at once', () => {
		const { wake, ticks } = timer();
		const at = performance.now() - 10;
		wake.arm(at);
		vi.advanceTimersByTime(0);
		expect(ticks).toHaveLength(1);
		expect(ticks[0]).toBeGreaterThanOrEqual(at);
	});
});
