/**
 * The bag edit route keeps the editor, and its draft, when the bag is deleted
 * elsewhere while it is open (REVIEW-497 P4, PLAN follow-up 10). Before, the
 * route's `{#if bean}` unmounted the editor and the draft was dropped with no
 * message ("Loading…" forever).
 */

import 'fake-indexeddb/auto';
import '#lib/testing/dom-stubs.js';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';

vi.mock('$app/navigation', () => ({
	goto: vi.fn(async () => {}),
	afterNavigate: () => {},
	beforeNavigate: () => {}
}));
vi.mock('$app/state', () => ({
	page: { params: { id: 'bean:a' }, url: new URL('http://localhost/beans/bean%3Aa/edit') }
}));
vi.mock('#lib/shell/app-context.js', () => ({
	getCremaAppContext: () => () => ({ services: null, app: null })
}));

import { blankBean, getBeanStore } from '#lib/bean/index.js';
import EditPage from './+page.svelte';

const library = getBeanStore();
let component: ReturnType<typeof mount> | null = null;

afterEach(() => {
	if (component) unmount(component);
	component = null;
	document.body.innerHTML = '';
	library.clearAll();
});

describe('/beans/[id]/edit', () => {
	it('a bag deleted elsewhere keeps the editor and the draft, with a note', () => {
		library.upsertBean({ ...blankBean('bean:a'), name: 'Guji', roastedOn: '2026-11-01' });
		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(EditPage, { target });
		flushSync();
		const name = [...target.querySelectorAll('input')].find((i) => i.value === 'Guji')!;
		name.value = 'Guji Natural';
		name.dispatchEvent(new Event('input', { bubbles: true }));
		flushSync();

		library.deleteBean('bean:a');
		flushSync();
		expect(target.textContent).not.toContain('Loading…');
		expect([...target.querySelectorAll('input')].some((i) => i.value === 'Guji Natural'), 'the draft is still there').toBe(true);
		expect(target.querySelector('[role=alert].be-gone')?.textContent).toContain('deleted on another tab or device');
	});
});
