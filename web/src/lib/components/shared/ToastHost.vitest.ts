/**
 * Temporary messages (brew-messages PRD §2): one action at most, a labelled
 * ✕, `alert` for errors and `status` otherwise, and a countdown line only on
 * messages that close on their own.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import ToastHost from './ToastHost.svelte';
import { dismissToast, getToasts, toast } from './toast.svelte.js';

let cmp: ReturnType<typeof mount> | null = null;
afterEach(() => {
	for (const t of getToasts()) dismissToast(t.id);
	if (cmp) unmount(cmp);
	cmp = null;
	document.body.innerHTML = '';
});

function render(): HTMLElement {
	const target = document.createElement('div');
	document.body.appendChild(target);
	cmp = mount(ToastHost, { target, props: {} });
	flushSync();
	return target;
}

describe('ToastHost', () => {
	it('shows a countdown on timed messages and none on pinned ones', () => {
		const el = render();
		toast.info('Profile loaded', 4000);
		toast.error('Upload failed', 0);
		flushSync();
		const [timed, pinned] = el.querySelectorAll<HTMLElement>('.toast');
		expect(timed.getAttribute('role')).toBe('status');
		expect(pinned.getAttribute('role')).toBe('alert');
		const line = timed.querySelector<HTMLElement>('.toast-countdown');
		expect(line?.style.getPropertyValue('--toast-ms')).toBe('4000ms');
		expect(pinned.querySelector('.toast-countdown')).toBeNull();
	});

	it('the action runs its callback once and closes the message', () => {
		const el = render();
		const run = vi.fn();
		const onDismiss = vi.fn();
		toast.action('Shot aborted and discarded', 'Undo', run, 8000, 'info', { onDismiss });
		flushSync();
		el.querySelector<HTMLButtonElement>('.toast-action')!.click();
		flushSync();
		expect(run).toHaveBeenCalledTimes(1);
		expect(onDismiss).toHaveBeenCalledTimes(1);
		expect(el.querySelector('.toast')).toBeNull();
	});

	it('✕ closes without running the action', () => {
		const el = render();
		const run = vi.fn();
		toast.action('Shot aborted and discarded', 'Undo', run);
		flushSync();
		const close = el.querySelector<HTMLButtonElement>('.toast-close')!;
		expect(close.getAttribute('aria-label')).toBe('Dismiss notification');
		close.click();
		flushSync();
		expect(run).not.toHaveBeenCalled();
		expect(el.querySelector('.toast')).toBeNull();
	});
});
