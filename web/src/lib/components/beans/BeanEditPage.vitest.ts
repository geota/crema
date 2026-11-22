/**
 * The bag and roaster editors keep a draft until Save (save-boundaries review
 * F2, 2026-11-21): typing writes nothing, Save applies only the fields changed
 * onto the row as it is now (a shot's debit during the edit stands), Cancel
 * really discards, and leaving with unsaved changes asks first. Before the
 * fix an existing bag was written on every keystroke and Cancel discarded
 * nothing.
 */

import 'fake-indexeddb/auto';
import '#lib/testing/dom-stubs.js';
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { flushSync, mount, tick, unmount, type Component } from 'svelte';

type Nav = {
	type: string;
	willUnload: boolean;
	delta?: number;
	to: { url: URL } | null;
	cancel: () => void;
};
const nav = vi.hoisted(() => ({ hooks: [] as ((n: unknown) => void)[], goto: vi.fn(async () => {}) }));
vi.mock('$app/navigation', () => ({
	goto: nav.goto,
	afterNavigate: () => {},
	beforeNavigate: (fn: (n: unknown) => void) => nav.hooks.push(fn)
}));
vi.mock('#lib/shell/app-context.js', () => ({
	getCremaAppContext: () => () => ({ services: null, app: null })
}));

import {
	beanImageRefFor,
	blankBean,
	blankRoaster,
	getBeanImageStore,
	getBeanStore,
	type Bean
} from '#lib/bean/index.js';
import { dismissConfirm, getActiveDialog } from '#lib/components/shared/confirm-dialog.svelte.js';
import BeanEditPage from './BeanEditPage.svelte';
import RoasterEditor from './RoasterEditor.svelte';

const library = getBeanStore();
let component: ReturnType<typeof mount> | null = null;

function bag(): Bean {
	const roaster = { ...blankRoaster('Onyx'), id: 'roaster:onyx' };
	library.upsertRoaster(roaster);
	const b: Bean = {
		...blankBean('bean:a'),
		name: 'Guji',
		roasterId: roaster.id,
		roastedOn: '2026-11-01',
		bagSize: 250,
		remaining: 200
	};
	library.upsertBean(b);
	return library.getBean('bean:a')!;
}

function mountEditor(c: typeof BeanEditPage | typeof RoasterEditor, props: Record<string, unknown>): HTMLElement {
	const target = document.createElement('div');
	document.body.appendChild(target);
	component = mount(c as Component<Record<string, unknown>>, { target, props });
	flushSync();
	return target;
}

function button(root: HTMLElement, text: string): HTMLButtonElement {
	const b = [...root.querySelectorAll('button')].find((el) => el.textContent?.trim() === text);
	if (!b) throw new Error(`no ${text} button`);
	return b;
}

function type(input: HTMLInputElement, value: string): void {
	input.value = value;
	input.dispatchEvent(new Event('input', { bubbles: true }));
	flushSync();
}

/** The text input showing `value` (Svelte sets `value` as a property). */
function inputWith(root: HTMLElement, value: string): HTMLInputElement {
	const el = [...root.querySelectorAll('input')].find((i) => i.value === value);
	if (!el) throw new Error(`no input showing ${value}`);
	return el;
}

/** Answer the open dialog, and close it as the dialog host would. */
function answer(value: boolean): void {
	const d = getActiveDialog();
	if (!d) throw new Error('no dialog open');
	d.resolve(value);
	dismissConfirm(d.options);
}

function navigation(over: Partial<Nav> = {}): Nav {
	return { type: 'link', willUnload: false, to: { url: new URL('http://localhost/brew') }, cancel: vi.fn(), ...over };
}

beforeEach(() => {
	localStorage.clear();
	library.clearAll();
	nav.hooks.length = 0;
	nav.goto.mockClear();
});

afterEach(() => {
	const open = getActiveDialog();
	if (open) dismissConfirm(open.options);
	if (component) unmount(component);
	component = null;
	document.body.innerHTML = '';
});

describe('the bag editor keeps a draft', () => {
	it('typing writes nothing; Save writes the edit onto the bag as it is now', async () => {
		const root = mountEditor(BeanEditPage, { bean: bag() });
		const name = inputWith(root, 'Guji');
		type(name, 'Guji Natural');
		expect(library.getBean('bean:a')!.name, 'nothing written while typing').toBe('Guji');

		// A shot debits the bag while the editor is open.
		library.debitBean('bean:a', 18);
		expect(library.getBean('bean:a')!.remaining).toBe(182);

		button(root, 'Save').click();
		await tick();
		await tick();
		const saved = library.getBean('bean:a')!;
		expect(saved.name).toBe('Guji Natural');
		expect(saved.remaining, 'the debit during the edit stands').toBe(182);
		expect(saved.roasterId).toBe('roaster:onyx');
	});

	it('Cancel asks when there are changes, and discarding leaves the bag as it was', async () => {
		const before = bag();
		const root = mountEditor(BeanEditPage, { bean: before });
		type(inputWith(root, 'Guji'), 'Renamed');
		button(root, 'Cancel').click();
		await tick();
		const dialog = getActiveDialog();
		expect(dialog?.options.message).toBe('Discard unsaved changes?');
		answer(true);
		await tick();
		expect(library.getBean('bean:a')).toEqual(before);
	});

	it('Cancel with no changes leaves without asking', async () => {
		const root = mountEditor(BeanEditPage, { bean: bag() });
		button(root, 'Cancel').click();
		await tick();
		expect(getActiveDialog()).toBeNull();
		expect(nav.goto).toHaveBeenCalled();
	});

	it('leaving with unsaved changes asks first, and Discard goes on', async () => {
		const root = mountEditor(BeanEditPage, { bean: bag() });
		const clean = navigation();
		for (const h of nav.hooks) h(clean);
		expect(clean.cancel, 'nothing to lose: no question').not.toHaveBeenCalled();

		type(inputWith(root, 'Guji'), 'Renamed');
		const away = navigation();
		for (const h of nav.hooks) h(away);
		expect(away.cancel).toHaveBeenCalled();
		const dialog = getActiveDialog();
		expect(dialog?.options.message).toBe('Discard unsaved changes?');
		answer(true);
		await tick();
		await tick();
		expect(nav.goto).toHaveBeenCalledWith(away.to!.url);
		expect(library.getBean('bean:a')!.name).toBe('Guji');

	});

	it("closing the tab with unsaved changes gets the browser's prompt", () => {
		const root = mountEditor(BeanEditPage, { bean: bag() });
		type(inputWith(root, 'Guji'), 'Renamed');
		const unload = navigation({ type: 'leave', willUnload: true, to: null });
		for (const h of nav.hooks) h(unload);
		expect(unload.cancel).toHaveBeenCalled();
		expect(getActiveDialog()).toBeNull();
	});

	it('a bigger bag size keeps the grams left of a bag in use (GEN2)', async () => {
		const root = mountEditor(BeanEditPage, { bean: bag() });
		button(root, '340g').click();
		flushSync();
		button(root, 'Save').click();
		await tick();
		await tick();
		const saved = library.getBean('bean:a')!;
		expect(saved.bagSize).toBe(340);
		expect(saved.remaining).toBe(200);
	});

	/** The Remaining stepper's shown value ("Remaining: 0 g, edit"). */
	function shownRemaining(root: HTMLElement): string | null {
		return root.querySelector('button[aria-label^="Remaining: "]')?.textContent?.trim() ?? null;
	}

	it('resizing a bag that ran out leaves it empty: a resize is not a refill (REVIEW-CORE H6)', async () => {
		library.upsertBean({ ...bag(), remaining: 0 });
		const root = mountEditor(BeanEditPage, { bean: library.getBean('bean:a')! });
		button(root, '340g').click();
		flushSync();
		expect(shownRemaining(root), 'shown as it will be saved').toBe('0');
		button(root, 'Save').click();
		await tick();
		await tick();
		const saved = library.getBean('bean:a')!;
		expect(saved.bagSize).toBe(340);
		expect(saved.remaining, 'still empty, as on Android').toBe(0);
	});

	it('a bag with no size before starts full at its first size', async () => {
		library.upsertBean({ ...bag(), bagSize: 0, remaining: 0 });
		const root = mountEditor(BeanEditPage, { bean: library.getBean('bean:a')! });
		button(root, '340g').click();
		flushSync();
		expect(shownRemaining(root)).toBe('340');
		button(root, 'Save').click();
		await tick();
		await tick();
		expect(library.getBean('bean:a')!.remaining).toBe(340);
	});

	it('Remaining set in the editor stands over a resize', async () => {
		const root = mountEditor(BeanEditPage, { bean: bag() });
		root.querySelector<HTMLButtonElement>('button[aria-label="Increase Remaining"]')!.click();
		flushSync();
		button(root, '340g').click();
		flushSync();
		expect(shownRemaining(root)).toBe('201');
		button(root, 'Save').click();
		await tick();
		await tick();
		expect(library.getBean('bean:a')!.remaining).toBe(201);
	});
});

describe('the bag photo follows Save', () => {
	const images = getBeanImageStore();
	const settled = async (): Promise<void> => {
		for (let i = 0; i < 20; i++) await new Promise((r) => setTimeout(r, 5));
	};

	async function withPhoto(): Promise<{ bean: Bean; ref: string }> {
		const ref = beanImageRefFor('bean:a');
		await images.put(ref, new Blob(['jpeg']));
		bag();
		library.updateBean('bean:a', { imageRef: ref });
		return { bean: library.getBean('bean:a')!, ref };
	}

	it('Remove photo then Cancel keeps the photo', async () => {
		const { bean, ref } = await withPhoto();
		const root = mountEditor(BeanEditPage, { bean });
		button(root, 'Remove photo').click();
		flushSync();
		button(root, 'Cancel').click();
		await tick();
		answer(true);
		await settled();
		expect(library.getBean('bean:a')!.imageRef).toBe(ref);
		expect(await images.get(ref)).not.toBeNull();
	});

	it('Save is off while a picked photo is still being stored (REVIEW-497 follow-up 3)', async () => {
		let release: () => void = () => {};
		const put = vi.spyOn(images, 'put').mockImplementation(
			(ref: string) => new Promise<string>((resolve) => (release = () => resolve(ref)))
		);
		onTestFinished(() => put.mockRestore());
		const root = mountEditor(BeanEditPage, { bean: bag() });
		const input = root.querySelector<HTMLInputElement>('input[type=file]')!;
		Object.defineProperty(input, 'files', { value: [new File(['jpeg'], 'bag.jpg')] });
		input.dispatchEvent(new Event('change', { bubbles: true }));
		flushSync();
		const saves = [...root.querySelectorAll('button')].filter((b) => b.textContent?.trim() === 'Save');
		expect(saves.length).toBeGreaterThan(0);
		expect(saves.every((b) => b.disabled), 'Save waits for the photo').toBe(true);

		release();
		await settled();
		flushSync();
		expect(button(root, 'Save').disabled).toBe(false);
		button(root, 'Save').click();
		await settled();
		expect(library.getBean('bean:a')!.imageRef, 'the picked photo is saved').toMatch(/^idb:bean-image:bean:a#/);
	});

	it('Remove photo then Save deletes it', async () => {
		const { bean, ref } = await withPhoto();
		const root = mountEditor(BeanEditPage, { bean });
		button(root, 'Remove photo').click();
		flushSync();
		button(root, 'Save').click();
		await settled();
		expect(library.getBean('bean:a')!.imageRef).toBeNull();
		expect(await images.get(ref)).toBeNull();
	});
});

describe('the roaster editor keeps a draft', () => {
	it('typing writes nothing; Save merges onto the row as it is now; Cancel discards', async () => {
		library.upsertRoaster({ ...blankRoaster('Onyx'), id: 'roaster:onyx' });
		const root = mountEditor(RoasterEditor, { roaster: library.getRoaster('roaster:onyx')! });
		type(inputWith(root, 'Onyx'), 'Onyx Coffee Lab');
		expect(library.getRoaster('roaster:onyx')!.name).toBe('Onyx');

		// A sync sets the country meanwhile.
		library.updateRoaster('roaster:onyx', { country: 'USA' });
		button(root, 'Save').click();
		await tick();
		await tick();
		expect(library.getRoaster('roaster:onyx')).toMatchObject({ name: 'Onyx Coffee Lab', country: 'USA' });
	});

	it('Cancel really discards', async () => {
		library.upsertRoaster({ ...blankRoaster('Onyx'), id: 'roaster:onyx' });
		const before = library.getRoaster('roaster:onyx')!;
		const root = mountEditor(RoasterEditor, { roaster: before });
		type(inputWith(root, 'Onyx'), 'Typo');
		button(root, 'Cancel').click();
		await tick();
		answer(true);
		await tick();
		expect(library.getRoaster('roaster:onyx')).toEqual(before);
	});
});
