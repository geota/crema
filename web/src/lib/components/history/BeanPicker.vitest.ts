/**
 * The picker's "Include archived" toggle and current-bag mark (geota/crema#301).
 * Which bags show is the core's, and `#lib/bean/picker.vitest.js` covers the
 * bridge; here it's what the toggle does to the page.
 */
import '#lib/testing/dom-stubs.js';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import { blankBean, getBeanStore, type Bean } from '#lib/bean/index.js';
import BeanPicker from './BeanPicker.svelte';

const bag = (id: string, createdAt: number, patch: Partial<Bean> = {}): Bean => ({
	...blankBean(),
	id,
	name: id,
	createdAt,
	...patch
});

const ROWS = '.bp-row-name';
const TOGGLE = 'button[aria-pressed]';

describe('BeanPicker', () => {
	let host: ReturnType<typeof mount> | null = null;

	beforeAll(async () => {
		await initTestWasm();
	});

	beforeEach(() => {
		const store = getBeanStore();
		store.clearAll();
		store.bulkAdd([bag('Live', 1), bag('Finished', 2, { archivedAt: 5 })], []);
	});

	afterEach(() => {
		if (host) unmount(host);
		host = null;
		document.body.innerHTML = '';
	});

	function show(currentBeanId: string | null, props: Record<string, unknown> = {}) {
		host = mount(BeanPicker, {
			target: document.body,
			props: {
				currentBeanId,
				onPick: vi.fn(),
				onClear: vi.fn(),
				onClose: vi.fn(),
				...props
			}
		});
		flushSync();
	}

	const names = () => [...document.querySelectorAll(ROWS)].map((n) => n.textContent);
	const toggle = () => document.querySelector<HTMLButtonElement>(TOGGLE);

	it('hides archived bags until the toggle is on', () => {
		show(null);
		expect(names()).toEqual(['Live']);
		expect(toggle()?.textContent).toContain('Include archived');
		expect(toggle()?.getAttribute('aria-pressed')).toBe('false');

		toggle()?.click();
		flushSync();
		expect(names()).toEqual(['Live', 'Finished']);
		expect(toggle()?.getAttribute('aria-pressed')).toBe('true');
	});

	it('shows an archived current bag first and marked, with the toggle off', () => {
		show('Finished');
		expect(names()).toEqual(['Finished', 'Live']);
		expect(document.querySelector('.bp-row.is-current .bp-row-name')?.textContent).toBe('Finished');
		expect(document.querySelector('.bp-row.is-current .bp-row-sub')?.textContent).toContain(
			'Archived'
		);
	});

	it('offers no toggle when there is nothing it could add', () => {
		show('Finished');
		expect(toggle()).toBeNull();
	});

	it('offers no toggle to a caller that cannot use an archived bag', () => {
		show(null, { allowArchived: false });
		expect(toggle()).toBeNull();
		expect(names()).toEqual(['Live']);
	});

	it('searches through the core', () => {
		show(null);
		const input = document.querySelector('input')!;
		input.value = 'liv';
		input.dispatchEvent(new Event('input', { bubbles: true }));
		flushSync();
		expect(names()).toEqual(['Live']);
		input.value = 'zzzzzz';
		input.dispatchEvent(new Event('input', { bubbles: true }));
		flushSync();
		expect(names()).toEqual([]);
		expect(document.body.textContent).toContain('No bags match');
	});
});
