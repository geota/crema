/**
 * The profile editor's Brew temp field (F6 validation P2): moving it shifts
 * every step through the core AND marks the draft edited, so Discard and the
 * unsaved-changes prompt see it.
 */

import '#lib/testing/dom-stubs.js';
import { afterEach, describe, expect, it } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import WithAppContext from '#lib/testing/WithAppContext.svelte';
import ProfileEditor from './ProfileEditor.svelte';
import { getProfileStore } from '#lib/profiles/index.js';

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
