import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TimeoutError, withTimeout } from './timeout.js';

describe('withTimeout (review 06 #14: one shared helper)', () => {
	beforeEach(() => vi.useFakeTimers());
	afterEach(() => vi.useRealTimers());

	it('settles with the promise when it wins', async () => {
		await expect(withTimeout(Promise.resolve(7), 1_000)).resolves.toBe(7);
		const err = new Error('boom');
		await expect(withTimeout(Promise.reject(err), 1_000)).rejects.toBe(err);
		expect(vi.getTimerCount()).toBe(0);
	});

	it('rejects with a TimeoutError carrying the message once the deadline passes', async () => {
		const pending = withTimeout(new Promise<never>(() => {}), 500, 'probe timed out');
		const assertion = expect(pending).rejects.toMatchObject({
			name: 'TimeoutError',
			message: 'probe timed out'
		});
		await vi.advanceTimersByTimeAsync(499);
		await vi.advanceTimersByTimeAsync(1);
		await assertion;
		await expect(pending).rejects.toBeInstanceOf(TimeoutError);
	});

	it('uses injected timer hooks', async () => {
		let fire: (() => void) | null = null;
		const cleared: unknown[] = [];
		const pending = withTimeout(new Promise<never>(() => {}), 50, 'x', {
			setTimeout: (fn) => {
				fire = fn;
				return 'h1';
			},
			clearTimeout: (h) => cleared.push(h)
		});
		expect(fire).not.toBeNull();
		fire!();
		await expect(pending).rejects.toBeInstanceOf(TimeoutError);
		const ok = withTimeout(Promise.resolve(1), 50, 'x', {
			setTimeout: () => 'h2',
			clearTimeout: (h) => cleared.push(h)
		});
		await expect(ok).resolves.toBe(1);
		expect(cleared).toEqual(['h2']);
	});
});
