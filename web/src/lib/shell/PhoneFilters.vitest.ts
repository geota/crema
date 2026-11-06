/** The phone filter strip's pills: what a tap reports, with the sheets involved. */
import '#lib/testing/dom-stubs.js';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import PhoneChipRow from './PhoneChipRow.svelte';
import PhoneDropdownPill from './PhoneDropdownPill.svelte';
import PhoneSortPill from './PhoneSortPill.svelte';

Element.prototype.animate = vi.fn(
	() => ({ cancel: vi.fn(), finish: vi.fn() }) as unknown as Animation
);

describe('phone filter pills', () => {
	const mounted: ReturnType<typeof mount>[] = [];
	afterEach(() => {
		while (mounted.length) unmount(mounted.pop()!);
		document.body.innerHTML = '';
	});
	function show<P extends Record<string, unknown>>(component: never, props: P): void {
		mounted.push(mount(component, { target: document.body, props }));
		flushSync();
	}
	const q = (selector: string): HTMLButtonElement => {
		const el = document.querySelector<HTMLButtonElement>(selector);
		if (!el) throw new Error(`no ${selector}`);
		return el;
	};

	it('a chip reports its id, and says when it is pressed', () => {
		const onselect = vi.fn();
		show(PhoneChipRow as never, {
			label: 'Filter',
			onselect,
			chips: [
				{ id: 'all', label: 'All', count: 3, selected: true },
				{ id: 'light', label: 'Light', count: 1 }
			]
		});
		const [all, light] = document.querySelectorAll<HTMLButtonElement>('.pchip');
		expect(all.getAttribute('aria-pressed')).toBe('true');
		expect(all.textContent).toContain('3');
		expect(light.getAttribute('aria-pressed')).toBe('false');
		light.click();
		expect(onselect).toHaveBeenCalledExactlyOnceWith('light');
	});

	it('the sort pill flips direction in place and picks a field from a sheet', () => {
		const onchange = vi.fn();
		show(PhoneSortPill as never, {
			value: { field: 'name', direction: 'asc' },
			options: [
				{ field: 'name', label: 'Name' },
				{ field: 'roast', label: 'Roast' }
			],
			onchange
		});
		q('.psort-dir').click();
		expect(onchange).toHaveBeenLastCalledWith({ field: 'name', direction: 'desc' });
		expect(document.querySelector('[role="dialog"]')).toBeNull();
		q('.psort-key').click();
		flushSync();
		[...document.querySelectorAll<HTMLButtonElement>('.poption-item')]
			.find((b) => b.textContent?.includes('Roast'))!
			.click();
		expect(onchange).toHaveBeenLastCalledWith({ field: 'roast', direction: 'asc' });
	});

	it('the dropdown pill names its choice and offers the rest in a sheet', () => {
		const onpick = vi.fn();
		show(PhoneDropdownPill as never, {
			icon: 'ph ph-calendar',
			label: 'Range',
			value: '30d',
			options: [
				{ id: 'all', label: 'All time' },
				{ id: '30d', label: 'Last 30 days' }
			],
			onpick
		});
		expect(q('.pdrop').getAttribute('aria-label')).toBe('Range: Last 30 days');
		q('.pdrop').click();
		flushSync();
		const rows = document.querySelectorAll<HTMLButtonElement>('.poption-item');
		expect(rows[1].getAttribute('aria-pressed')).toBe('true');
		rows[0].click();
		expect(onpick).toHaveBeenCalledExactlyOnceWith('all');
	});
});
