/**
 * The profile editor's Brew temp field (F6 validation P2): moving it shifts
 * every step through the core AND marks the draft edited, so Discard and the
 * unsaved-changes prompt see it.
 */

import '#lib/testing/dom-stubs.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';

const nav = vi.hoisted(() => ({ hooks: [] as ((n: unknown) => void)[], goto: vi.fn(async () => {}) }));
vi.mock('$app/navigation', () => ({
	goto: nav.goto,
	afterNavigate: () => {},
	beforeNavigate: (fn: (n: unknown) => void) => nav.hooks.push(fn)
}));
import WithAppContext from '#lib/testing/WithAppContext.svelte';
import ProfileEditor from './ProfileEditor.svelte';
import { getProfileStore } from '#lib/profiles/index.js';
import { dismissConfirm, getActiveDialog } from '#lib/components/shared/confirm-dialog.svelte.js';

describe('ProfileEditor Brew temp', () => {
	let component: ReturnType<typeof mount> | null = null;

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	it('a Brew temp change marks the draft edited', async () => {
		await getProfileStore().ensureLoaded();
		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(WithAppContext, {
			target,
			props: { app: null, component: ProfileEditor, props: { mode: 'new' } }
		});
		flushSync();
		await tick();
		const discard = [...target.querySelectorAll('button')].find(
			(b) => b.textContent?.trim() === 'Discard changes'
		) as HTMLButtonElement | undefined;
		expect(discard, 'the Discard button').toBeDefined();
		expect(discard!.disabled, 'nothing edited yet').toBe(true);

		const plus = target.querySelector('button[aria-label="Increase Brew temp"]') as HTMLButtonElement;
		expect(plus, 'the Brew temp stepper').not.toBeNull();
		plus.click();
		flushSync();
		await tick();
		expect(discard!.disabled, 'the Brew temp change is an edit').toBe(false);
	});
});

describe('ProfileEditor leave guard (FINAL S6)', () => {
	let component: ReturnType<typeof mount> | null = null;

	beforeEach(() => {
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

	async function mountEditor(): Promise<HTMLElement> {
		await getProfileStore().ensureLoaded();
		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(WithAppContext, {
			target,
			props: { app: null, component: ProfileEditor, props: { mode: 'new' } }
		});
		flushSync();
		await tick();
		return target;
	}

	function edit(target: HTMLElement): void {
		(target.querySelector('button[aria-label="Increase Brew temp"]') as HTMLButtonElement).click();
		flushSync();
	}

	function leave(over: Record<string, unknown> = {}) {
		const n = { type: 'link', willUnload: false, to: { url: new URL('http://localhost/brew') }, cancel: vi.fn(), ...over };
		for (const h of nav.hooks) h(n);
		return n;
	}

	it('leaving through the app with unsaved changes asks first, and Discard goes on', async () => {
		const target = await mountEditor();
		expect(leave().cancel, 'nothing to lose: no question').not.toHaveBeenCalled();

		edit(target);
		const away = leave();
		expect(away.cancel).toHaveBeenCalled();
		const d = getActiveDialog();
		expect(d?.options.message).toBe('Discard unsaved changes?');
		d!.resolve(true);
		dismissConfirm(d!.options);
		await tick();
		await tick();
		expect(nav.goto).toHaveBeenCalledWith(away.to.url);
	});

	it("closing the tab with unsaved changes gets the browser's prompt", async () => {
		const target = await mountEditor();
		edit(target);
		const unload = leave({ willUnload: true, to: null });
		expect(unload.cancel).toHaveBeenCalled();
		expect(getActiveDialog()).toBeNull();
	});

	it("the editor's own Back asks once, not twice", async () => {
		const target = await mountEditor();
		edit(target);
		(target.querySelector('button.pe-back') as HTMLButtonElement).click();
		await tick();
		const d = getActiveDialog();
		expect(d?.options.message).toBe('Leave the editor? Unsaved changes will be lost.');
		d!.resolve(true);
		dismissConfirm(d!.options);
		await tick();
		await tick();
		expect(nav.goto).toHaveBeenCalled();
		expect(leave().cancel, 'Back already asked').not.toHaveBeenCalled();
	});
});
