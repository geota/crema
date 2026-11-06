/**
 * A field commits as its screen goes: Back, a filter change or a rotation takes
 * it away after the page's selection has moved on, or gone. The edit must still
 * land on the shot it was typed on.
 */
import '#lib/testing/dom-stubs.js';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import type { StoredShot } from '#lib/history/index.js';
import ShotDetail from './ShotDetail.svelte';
import WithAppContext from '#lib/testing/WithAppContext.svelte';

vi.mock('#lib/shell/phone.svelte.js', () => ({ phoneLayout: { current: true } }));
Element.prototype.animate = vi.fn(
	() => ({ cancel: vi.fn(), finish: vi.fn() }) as unknown as Animation
);

const shotOf = (id: string, notes: string): StoredShot =>
	({
		id,
		profileName: `Profile ${id}`,
		completedAt: 1_790_000_000_000,
		metadata: { notes, rating: 0 },
		record: { duration: 30_000, samples: [] },
		bean: null,
		tags: []
	}) as unknown as StoredShot;

describe('ShotDetail keeps to the shot it was mounted for', () => {
	const A = shotOf('shot:a', 'A notes');
	const B = shotOf('shot:b', 'B notes');
	let host: ReturnType<typeof mount> | null = null;

	afterEach(() => {
		if (host) unmount(host);
		host = null;
		document.body.innerHTML = '';
	});

	function show() {
		let shot = $state<StoredShot | null>(A);
		let menuOpen = $state(false);
		const calls = {
			onNotesChange: vi.fn(),
			onNextPlanChange: vi.fn(),
			onRatingChange: vi.fn(),
			onPrivacyChange: vi.fn(),
			onGrinderModelChange: vi.fn(),
			onPersonChange: vi.fn(),
			onTagsChange: vi.fn(),
			onGrindChange: vi.fn(),
			onBeanChange: vi.fn()
		};
		host = mount(WithAppContext, {
			target: document.body,
			props: {
				app: null,
				component: ShotDetail,
				props: {
					get shot() {
						return shot;
					},
					get phoneMenuOpen() {
						return menuOpen;
					},
					set phoneMenuOpen(v: boolean) {
						menuOpen = v;
					},
					...calls
				}
			}
		});
		flushSync();
		const field = (label: string): HTMLTextAreaElement => {
			const found = [...document.querySelectorAll('.pshot-field')].find((f) =>
				f.textContent?.includes(label)
			);
			return found!.querySelector('textarea')!;
		};
		return {
			calls,
			field,
			moveTo(next: StoredShot | null): void {
				shot = next;
				flushSync();
			},
			isMenuOpen: () => menuOpen,
			openMenu(): void {
				menuOpen = true;
				flushSync();
			}
		};
	}
	function type(el: HTMLTextAreaElement | HTMLInputElement, value: string): void {
		el.value = value;
		el.dispatchEvent(new Event('input', { bubbles: true }));
	}
	const change = (el: Element): boolean => el.dispatchEvent(new Event('change', { bubbles: true }));

	it.each([
		['Tasting notes', 'onNotesChange'],
		['Next time', 'onNextPlanChange']
	] as const)('%s: a change after the selection moved still names the first shot', async (label, cb) => {
		const { calls, field, moveTo } = show();
		const el = field(label);
		type(el, 'typed on A');
		moveTo(B);
		change(el);
		await Promise.resolve();
		expect(calls[cb]).toHaveBeenCalledExactlyOnceWith('shot:a', 'typed on A');
	});

	it.each([
		['Tasting notes', 'onNotesChange'],
		['Next time', 'onNextPlanChange']
	] as const)('%s: a change after the selection emptied neither throws nor loses it', async (label, cb) => {
		const { calls, field, moveTo } = show();
		const el = field(label);
		type(el, 'typed on A');
		moveTo(null);
		change(el);
		await Promise.resolve();
		expect(calls[cb]).toHaveBeenCalledExactlyOnceWith('shot:a', 'typed on A');
	});

	it('saves typed text when the field leaves with no change event, once', async () => {
		const { calls, field } = show();
		type(field('Tasting notes'), 'typed on A');
		unmount(host!);
		host = null;
		await Promise.resolve();
		expect(calls.onNotesChange).toHaveBeenCalledExactlyOnceWith('shot:a', 'typed on A');
		expect(calls.onNextPlanChange).not.toHaveBeenCalled();
	});

	it('does not save a field nobody typed in', async () => {
		const { calls } = show();
		unmount(host!);
		host = null;
		await Promise.resolve();
		expect(calls.onNotesChange).not.toHaveBeenCalled();
	});

	it('does not save twice when change already committed', async () => {
		const { calls, field } = show();
		const el = field('Tasting notes');
		type(el, 'typed on A');
		change(el);
		unmount(host!);
		host = null;
		await Promise.resolve();
		expect(calls.onNotesChange).toHaveBeenCalledOnce();
	});

	it('barista and grinder model name the first shot too', async () => {
		const { calls, moveTo } = show();
		const barista = document.querySelector<HTMLInputElement>('.hi-person input')!;
		const grinder = document.querySelector<HTMLInputElement>('.hi-grinder input')!;
		type(barista, 'Ada');
		type(grinder, 'Niche');
		moveTo(B);
		change(barista);
		change(grinder);
		await Promise.resolve();
		expect(calls.onPersonChange).toHaveBeenCalledExactlyOnceWith('shot:a', 'barista', 'Ada');
		expect(calls.onGrinderModelChange).toHaveBeenCalledExactlyOnceWith('shot:a', 'Niche');
	});

	it('a star and a privacy chip name the first shot after the selection moved', () => {
		const { calls, moveTo } = show();
		const star = document.querySelector<HTMLButtonElement>('[role="radio"]')!;
		const chip = document.querySelector<HTMLButtonElement>('.hi-privacy-chip')!;
		moveTo(B);
		star.click();
		chip.click();
		expect(calls.onRatingChange.mock.calls[0][0]).toBe('shot:a');
		expect(calls.onPrivacyChange.mock.calls[0][0]).toBe('shot:a');
	});

	it('stays on the first shot on screen once the selection moved', () => {
		const { moveTo } = show();
		moveTo(B);
		expect(document.querySelector('.pshot-strip-title')?.textContent).toBe('Profile shot:a');
	});

	it('shuts the ⋮ sheet with the screen, so the next shot does not open on it', async () => {
		const { openMenu, isMenuOpen } = show();
		openMenu();
		expect(isMenuOpen()).toBe(true);
		unmount(host!);
		host = null;
		await Promise.resolve();
		flushSync();
		expect(isMenuOpen()).toBe(false);
	});
});
