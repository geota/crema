import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import MoreMenu from './MoreMenu.svelte';

describe('MoreMenu', () => {
	let component: ReturnType<typeof mount> | null = null;

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	function render() {
		const onImport = vi.fn();
		const onExport = vi.fn();
		const target = document.body.appendChild(document.createElement('div'));
		component = mount(MoreMenu, {
			target,
			props: {
				label: 'More actions',
				items: [
					{ icon: 'ph ph-upload-simple', title: 'Import', onclick: onImport },
					{ icon: 'ph ph-file-text', title: 'Export', disabled: true, onclick: onExport }
				]
			}
		});
		flushSync();
		const trigger = target.querySelector('button') as HTMLButtonElement;
		return { target, trigger, onImport, onExport };
	}

	it('stays closed until the button is pressed, then focuses the first item', () => {
		const { target, trigger } = render();
		expect(target.querySelector('[role="menu"]')).toBeNull();
		expect(trigger.getAttribute('aria-expanded')).toBe('false');
		trigger.click();
		flushSync();
		expect(trigger.getAttribute('aria-expanded')).toBe('true');
		expect(document.activeElement?.textContent).toContain('Import');
	});

	it('runs a picked item and closes', () => {
		const { target, trigger, onImport } = render();
		trigger.click();
		flushSync();
		target.querySelector<HTMLButtonElement>('[role="menuitem"]')?.click();
		flushSync();
		expect(onImport).toHaveBeenCalledOnce();
		expect(target.querySelector('[role="menu"]')).toBeNull();
	});

	it('disables an item that cannot run', () => {
		const { target, trigger } = render();
		trigger.click();
		flushSync();
		const items = target.querySelectorAll<HTMLButtonElement>('[role="menuitem"]');
		expect(items[1]?.disabled).toBe(true);
	});

	it('closes on Escape and hands focus back', () => {
		const { target, trigger } = render();
		trigger.click();
		flushSync();
		target
			.querySelector('[role="menu"]')
			?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
		flushSync();
		expect(target.querySelector('[role="menu"]')).toBeNull();
		expect(document.activeElement).toBe(trigger);
	});

	it('closes on a click outside', () => {
		const { target, trigger } = render();
		trigger.click();
		flushSync();
		document.body.click();
		flushSync();
		expect(target.querySelector('[role="menu"]')).toBeNull();
	});
});
