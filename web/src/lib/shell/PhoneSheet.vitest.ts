/** The bottom sheet's dialog behaviour, and the overflow sheet built on it. */
import '#lib/testing/dom-stubs.js';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { tick } from 'svelte';
import XIcon from 'phosphor-svelte/lib/XIcon';
import PhoneOverflowSheet from './PhoneOverflowSheet.svelte';

describe('PhoneOverflowSheet', () => {
	let component: ReturnType<typeof mount> | null = null;

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	function render(items = [{ id: 'a', icon: XIcon, label: 'Edit', onclick: vi.fn() }]) {
		const onClose = vi.fn();
		const target = document.body.appendChild(document.createElement('div'));
		component = mount(PhoneOverflowSheet, {
			target,
			props: { open: true, title: 'A profile', items, onClose }
		});
		flushSync();
		return { onClose, items };
	}

	it('is a labelled modal that takes focus', () => {
		render();
		const dialog = document.querySelector<HTMLElement>('[role="dialog"]')!;
		expect(dialog.getAttribute('aria-modal')).toBe('true');
		const name = document.getElementById(dialog.getAttribute('aria-labelledby')!);
		expect(name?.textContent).toBe('A profile');
		expect(document.activeElement).toBe(dialog);
	});

	it('Escape, Close and the scrim all dismiss it', () => {
		const { onClose } = render();
		const dialog = document.querySelector<HTMLElement>('[role="dialog"]')!;
		dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
		document.querySelector<HTMLButtonElement>('[aria-label="Close"]')!.click();
		document.querySelector<HTMLButtonElement>('.psheet-scrim')!.click();
		expect(onClose).toHaveBeenCalledTimes(3);
	});

	it('a row closes the sheet and runs its action', () => {
		const { onClose, items } = render();
		[...document.querySelectorAll('button')].find((b) => b.textContent?.includes('Edit'))!.click();
		expect(onClose).toHaveBeenCalledOnce();
		expect(items[0].onclick).toHaveBeenCalledOnce();
	});

	it('a disabled row stays visible and does nothing', async () => {
		const onclick = vi.fn();
		const { onClose } = render([
			{ id: 'a', icon: XIcon, label: 'Sleep machine', sub: 'Connect the DE1 first', disabled: true, onclick } as never
		]);
		await tick();
		const row = [...document.querySelectorAll('button')].find((b) =>
			b.textContent?.includes('Sleep machine')
		)!;
		expect(row.textContent).toContain('Connect the DE1 first');
		row.click();
		expect(onclick).not.toHaveBeenCalled();
		expect(onClose).not.toHaveBeenCalled();
	});
});
