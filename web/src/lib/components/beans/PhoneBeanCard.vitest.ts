/** The phone bag card's primary pill, and the delete sheet's confirmation. */
import '#lib/testing/dom-stubs.js';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { confirmDialog } from '#lib/components/shared/confirm-dialog.svelte.js';
import { getBeanStore, type Bean } from '#lib/bean/index.js';
import BeanDeleteSplit from './BeanDeleteSplit.svelte';
import PhoneBeanCard from './PhoneBeanCard.svelte';
import WithAppContext from '#lib/testing/WithAppContext.svelte';

vi.mock('#lib/components/shared/confirm-dialog.svelte.js', () => ({
	confirmDialog: vi.fn()
}));
Element.prototype.animate = vi.fn(
	() => ({ cancel: vi.fn(), finish: vi.fn() }) as unknown as Animation
);

const bean = (patch: Partial<Bean> = {}): Bean =>
	({
		id: 'bean:1',
		name: 'Gesha',
		roasterId: null,
		favourite: false,
		bagSize: 250,
		remaining: 100,
		rating: 0,
		roastLevel: null,
		origin: {},
		tags: [],
		archivedAt: null,
		...patch
	}) as Bean;

describe('PhoneBeanCard', () => {
	let card: ReturnType<typeof mount> | null = null;
	afterEach(() => {
		if (card) unmount(card);
		card = null;
		document.body.innerHTML = '';
	});
	function show(b: Bean, isActive = false) {
		const calls = {
			onOpen: vi.fn(),
			onToggleFavourite: vi.fn(),
			onSetActive: vi.fn(),
			onRestore: vi.fn(),
			onEdit: vi.fn(),
			onMenu: vi.fn()
		};
		card = mount(PhoneBeanCard, {
			target: document.body,
			props: { bean: b, roaster: null, isActive, ...calls }
		});
		flushSync();
		return { calls, main: document.querySelector<HTMLButtonElement>('.pcact-main')! };
	}

	it('sets a plain bag active', () => {
		const { calls, main } = show(bean());
		expect(main.textContent).toContain('Set active');
		main.click();
		expect(calls.onSetActive).toHaveBeenCalledExactlyOnceWith('bean:1');
	});

	it('restores an archived bag rather than activating it', () => {
		const { calls, main } = show(bean({ archivedAt: 1 } as Partial<Bean>));
		expect(main.textContent).toContain('Restore');
		main.click();
		expect(calls.onRestore).toHaveBeenCalledExactlyOnceWith('bean:1');
		expect(calls.onSetActive).not.toHaveBeenCalled();
	});

	it('does nothing on the active bag', () => {
		const { calls, main } = show(bean(), true);
		expect(main.textContent).toContain('Active bean');
		main.click();
		expect(calls.onSetActive).not.toHaveBeenCalled();
		expect(calls.onRestore).not.toHaveBeenCalled();
	});

	it('routes the body, the star, the pencil and ⋮ to their own handlers', () => {
		const { calls } = show(bean());
		const q = (s: string) => document.querySelector<HTMLButtonElement>(s)!;
		q('.pbc-open').click();
		q('.pbc-star').click();
		q('[aria-label="Edit Gesha"]').click();
		q('[aria-label="More actions for Gesha"]').click();
		expect(calls.onOpen).toHaveBeenCalledOnce();
		expect(calls.onToggleFavourite).toHaveBeenCalledOnce();
		expect(calls.onEdit).toHaveBeenCalledOnce();
		expect(calls.onMenu).toHaveBeenCalledOnce();
	});
});

describe('BeanDeleteSplit as a sheet', () => {
	let host: ReturnType<typeof mount> | null = null;
	afterEach(() => {
		if (host) unmount(host);
		host = null;
		document.body.innerHTML = '';
		vi.mocked(confirmDialog).mockReset();
	});

	it('keeps the confirmation: Cancel deletes nothing, Delete deletes the bag', async () => {
		const library = getBeanStore();
		const { id } = library.quickAdd('Sheet test', 'Roaster', '2026-09-20');
		host = mount(WithAppContext, {
			target: document.body,
			props: {
				app: null,
				component: BeanDeleteSplit,
				props: { beanId: id, beanName: 'Sheet test', sheet: { open: true, onClose: vi.fn() } }
			}
		});
		flushSync();
		const local = (): HTMLButtonElement =>
			[...document.querySelectorAll<HTMLButtonElement>('.poverflow-item')].find((b) =>
				b.textContent?.includes('Delete from this device')
			)!;

		vi.mocked(confirmDialog).mockResolvedValueOnce(false);
		local().click();
		await vi.waitFor(() => expect(confirmDialog).toHaveBeenCalledOnce());
		await Promise.resolve();
		expect(library.getBean(id)).toBeTruthy();

		vi.mocked(confirmDialog).mockResolvedValueOnce(true);
		local().click();
		await vi.waitFor(() => expect(library.getBean(id)).toBeFalsy());
	});
});
