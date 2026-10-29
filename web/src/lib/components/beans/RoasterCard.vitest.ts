import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import type { Roaster } from '#lib/bean/index.js';
import RoasterCard from './RoasterCard.svelte';

// The card's delete control reads the shell's app context; no shell here.
vi.mock('#lib/shell/app-context.js', () => ({
	getCremaAppContext: () => () => ({ services: null, app: null })
}));

// Review 05 P3-3: a roaster website from an import must never render as a
// `javascript:` (or other non-http) link.
describe('RoasterCard website link', () => {
	let component: ReturnType<typeof mount> | null = null;
	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	function render(website: string | null): HTMLElement {
		const target = document.createElement('div');
		document.body.appendChild(target);
		const roaster = {
			id: 'roaster:1',
			name: 'Onyx',
			website,
			imageUrl: null,
			city: null,
			country: null,
			notes: ''
		} as unknown as Roaster;
		const noop = (): void => {};
		component = mount(RoasterCard, {
			target,
			props: { roaster, count: 1, dupOf: null, onOpen: noop, onEdit: noop, onDuplicate: noop, onUnmerge: noop }
		});
		flushSync();
		return target;
	}

	it('links an https website', () => {
		const a = render('https://www.onyxcoffeelab.com/').querySelector<HTMLAnchorElement>('a.rcd-site');
		expect(a?.getAttribute('href')).toBe('https://www.onyxcoffeelab.com/');
		expect(a?.textContent).toContain('onyxcoffeelab.com');
	});

	it('links a bare host as https instead of a relative path', () => {
		const a = render('onyxcoffeelab.com').querySelector<HTMLAnchorElement>('a.rcd-site');
		expect(a?.getAttribute('href')).toBe('https://onyxcoffeelab.com/');
	});

	it.each(['javascript:alert(1)', 'javascript://example.com/%0aalert(1)', 'data:text/html,hi'])(
		'renders no link for %s',
		(website) => {
			const target = render(website);
			expect(target.querySelector('a.rcd-site')).toBeNull();
			for (const a of target.querySelectorAll('a')) {
				expect(a.getAttribute('href') ?? '').not.toMatch(/^(javascript|data):/i);
			}
		}
	);
});
