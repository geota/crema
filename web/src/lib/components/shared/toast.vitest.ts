/**
 * The toast store's `onDismiss` hook: an owner that pins a toast (the
 * engine-reset notice) learns when it is gone, however it went, so it can
 * offer it again later (round-2 verification V3 #3).
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

import { dismissToast, getToasts, toast } from './toast.svelte.js';

afterEach(() => {
	for (const t of getToasts()) dismissToast(t.id);
});

describe('toast onDismiss', () => {
	it('runs once when the toast is closed, and not for other toasts', () => {
		const onDismiss = vi.fn();
		const id = toast.action('Pinned', 'Reload', () => undefined, 0, 'error', { onDismiss });
		const other = toast.action('Other', 'Undo', () => undefined, 0);
		dismissToast(other);
		expect(onDismiss).not.toHaveBeenCalled();
		dismissToast(id);
		expect(onDismiss).toHaveBeenCalledTimes(1);
		dismissToast(id); // already gone
		expect(onDismiss).toHaveBeenCalledTimes(1);
	});

	it('runs when the auto-dismiss timer removes the toast', () => {
		vi.useFakeTimers();
		try {
			const onDismiss = vi.fn();
			toast.action('Brief', 'Undo', () => undefined, 1000, 'info', { onDismiss });
			vi.advanceTimersByTime(1000);
			expect(onDismiss).toHaveBeenCalledTimes(1);
			expect(getToasts()).toHaveLength(0);
		} finally {
			vi.useRealTimers();
		}
	});
});

describe('toast duration (the countdown line)', () => {
	it('records each toast’s duration, and 0 for a pinned one', () => {
		toast.info('Profile loaded');
		toast.error('Upload failed');
		toast.action('Shot discarded', 'Undo', () => undefined);
		toast.action('Update ready', 'Reload', () => undefined, 0);
		expect(getToasts().map((t) => t.durationMs)).toEqual([4000, 7000, 8000, 0]);
	});
});
