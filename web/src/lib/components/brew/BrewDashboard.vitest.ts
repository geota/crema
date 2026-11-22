/**
 * The Brew dashboard's wiring to the app-level dial (F7 validation P2): the
 * dial seeds from `app.brewOverridesFor(active)` when it mounts and reports
 * the active profile's overrides to `app.setBrewOverrides`, which makes them
 * the core's desired profile. A dashboard that seeds from nothing, or stops
 * reporting, fails here.
 */

import '#lib/testing/dom-stubs.js';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import WithAppContext from '#lib/testing/WithAppContext.svelte';
import BrewDashboard from './BrewDashboard.svelte';
import { brewTempRange, getProfileStore, type CremaProfile } from '#lib/profiles/index.js';
import { getCremaUiState } from '#lib/state/ui-state.svelte.js';
import type { CremaApp } from '#lib/state/index.js';
import { getBrewGrind } from '#lib/brew/grind-session.svelte.js';
import { blankBean, getBeanStore } from '#lib/bean/index.js';
import { dismissConfirm, getActiveDialog } from '#lib/components/shared/confirm-dialog.svelte.js';

describe('BrewDashboard and the app-level dial', () => {
	let component: ReturnType<typeof mount> | null = null;

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	it('seeds the dial from the app and reports the active profile with it', async () => {
		const profiles = getProfileStore();
		await profiles.ensureLoaded();
		const profile = profiles.all.find(
			(p) => brewTempRange(p).max >= brewTempRange(p).anchor + 2
		);
		if (!profile) throw new Error('no suitable built-in profile');
		profiles.setActive(profile.id);
		// The dial was left at +2 °C on this profile (another screen, before
		// this mount).
		const dialled = { brewTemp: brewTempRange(profile).anchor + 2 };
		const setBrewOverrides = vi.fn<(p: CremaProfile, qc: object) => void>();
		const app = {
			brewOverridesFor: (p: CremaProfile | null) => (p?.id === profile.id ? dialled : {}),
			setBrewOverrides,
			state: getCremaUiState()
		} as unknown as CremaApp;

		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(WithAppContext, {
			target,
			props: { app, component: BrewDashboard, props: { ui: getCremaUiState().current } }
		});
		flushSync();
		await tick();

		expect(setBrewOverrides, 'the dashboard reports the dial').toHaveBeenCalled();
		const [reported, qc] = setBrewOverrides.mock.lastCall!;
		expect(reported.id).toBe(profile.id);
		expect(qc, 'with the overrides it seeded from the app').toEqual(dialled);
	});
});

/**
 * Quick Controls' Save on the dashboard (save-boundaries review F1): shown
 * with no profile, off when nothing changed, and nothing written before the
 * name prompt is answered. Before, Save was hidden without a profile and the
 * grind reached the bag before the prompt.
 */
describe('Quick Controls Save on the dashboard', () => {
	let component: ReturnType<typeof mount> | null = null;

	afterEach(() => {
		const open = getActiveDialog();
		if (open) dismissConfirm(open.options);
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	async function render(): Promise<HTMLElement> {
		const app = {
			brewOverridesFor: () => ({}),
			setBrewOverrides: vi.fn(),
			state: getCremaUiState()
		} as unknown as CremaApp;
		const target = document.body.appendChild(document.createElement('div'));
		component = mount(WithAppContext, {
			target,
			props: { app, component: BrewDashboard, props: { ui: getCremaUiState().current } }
		});
		flushSync();
		await tick();
		return target;
	}

	const sheet = (root: HTMLElement): HTMLElement => root.querySelector<HTMLElement>('.qsheet')!;
	function buttonIn(root: HTMLElement, text: string): HTMLButtonElement | undefined {
		return [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === text);
	}
	function click(root: HTMLElement, text: string): void {
		const b = buttonIn(root, text);
		if (!b) throw new Error(`no ${text} button`);
		b.click();
		flushSync();
	}

	/** The active bag with `grind` saved for no grinder (#488), and Brew's grind at it. */
	function activeBag(grind: string): void {
		const beans = getBeanStore();
		beans.clearAll();
		beans.upsertBean({
			...blankBean('bean:a'),
			name: 'Guji',
			grinds: [{ grinderId: '', grinderName: '', grind, starred: false }],
			bagSize: 250,
			remaining: 200
		});
		beans.setActiveBean('bean:a');
		getBrewGrind().set(Number(grind));
	}
	/** The bag's saved grind for no grinder. */
	const savedGrind = () => getBeanStore().getBean('bean:a')!.grinds?.find((g) => g.grinderId === '')?.grind;

	it('with no profile, Save is shown, off until the grind moves, then saves just the grind', async () => {
		const profiles = getProfileStore();
		await profiles.ensureLoaded();
		profiles.setActive(null);
		activeBag('4.2');
		const root = sheet(await render());
		const save = buttonIn(root, 'Save');
		expect(save, 'Save is shown with no profile').toBeDefined();
		expect(save!.disabled, 'nothing changed').toBe(true);

		click(root, 'Grind');
		click(root, '4.6');
		expect(buttonIn(root, 'Save')!.disabled).toBe(false);
		click(root, 'Save');
		await tick();
		expect(getActiveDialog(), 'no name prompt for a grind').toBeNull();
		expect(savedGrind()).toBe('4.6');
		expect(getBeanStore().getBean('bean:a')!.grinderSetting, 'the old field is never written').toBe('');
	});

	it('an unsaved dial survives a shot debit (REVIEW-497 M1)', async () => {
		await getProfileStore().ensureLoaded();
		getProfileStore().setActive(null);
		activeBag('4.2');
		const root = sheet(await render());
		click(root, 'Grind');
		click(root, '4.6');
		getBeanStore().debitBean('bean:a', 18);
		flushSync();
		await tick();
		expect(buttonIn(root, 'Save')!.disabled, 'the unsaved dial is still there').toBe(false);
		// The bag's saved grind catching up with the dial (another tab's
		// Save) leaves nothing to save; the dial itself never moves (#488 F9).
		getBeanStore().saveBagGrind('bean:a', null, '4.6');
		flushSync();
		await tick();
		expect(buttonIn(root, 'Save')!.disabled, 'saved: nothing left to save').toBe(true);
	});

	it('a built-in with a changed recipe asks for a name before writing anything', async () => {
		const profiles = getProfileStore();
		await profiles.ensureLoaded();
		const builtin = profiles.all.find((p) => p.source === 'builtin' && p.dose !== 20)!;
		profiles.setActive(builtin.id);
		activeBag('4.2');
		const count = profiles.all.length;
		const root = sheet(await render());
		root.querySelector<HTMLButtonElement>('.qchip[aria-label^="20"]')!.click();
		flushSync();
		click(root, 'Grind');
		click(root, '4.6');
		click(root, 'Save');
		await tick();
		const prompt = getActiveDialog();
		expect(prompt?.kind).toBe('prompt');
		expect(savedGrind(), 'the grind waits for the name').toBe('4.2');
		prompt!.resolve(null);
		dismissConfirm(prompt!.options);
		await tick();
		await tick();
		expect(savedGrind(), 'Cancel writes nothing').toBe('4.2');
		expect(profiles.all.length).toBe(count);
	});
});
