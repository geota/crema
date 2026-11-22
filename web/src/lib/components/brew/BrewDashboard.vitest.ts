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
import { dismissToast, getToasts } from '#lib/components/shared/toast.svelte.js';

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
		// Save's dot reads ", includes the grind" to assistive tech (#488).
		return [...root.querySelectorAll('button')].find((b) => {
			const t = b.textContent?.trim() ?? '';
			return t === text || t === `${text}, includes the grind`;
		});
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
		getBrewGrind().set(grind);
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
		click(root, '4.4');
		expect(buttonIn(root, 'Save')!.disabled).toBe(false);
		expect(buttonIn(root, 'Save')!.querySelector('.qsave-dot'), 'the dot: Save includes the grind').not.toBeNull();
		click(root, 'Save');
		await tick();
		expect(getActiveDialog(), 'no name prompt for a grind').toBeNull();
		expect(savedGrind()).toBe('4.4');
		expect(getBeanStore().getBean('bean:a')!.grinderSetting, 'the old field is never written').toBe('');
	});

	it('an unsaved dial survives a shot debit (REVIEW-497 M1)', async () => {
		await getProfileStore().ensureLoaded();
		getProfileStore().setActive(null);
		activeBag('4.2');
		const root = sheet(await render());
		click(root, 'Grind');
		click(root, '4.4');
		getBeanStore().debitBean('bean:a', 18);
		flushSync();
		await tick();
		expect(buttonIn(root, 'Save')!.disabled, 'the unsaved dial is still there').toBe(false);
		// The bag's saved grind catching up with the dial (another tab's
		// Save) leaves nothing to save; the dial itself never moves (#488 F9).
		getBeanStore().saveBagGrind('bean:a', null, '4.4');
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
		click(root, '4.4');
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

/**
 * The Brew header's Grinder block and the unsaved-grind marker (#488, boards
 * WideBrew2, WideGrindUnsavedRest, WideGrindUnsavedQC).
 */
describe('the Grinder block and the unsaved-grind marker', () => {
	let component: ReturnType<typeof mount> | null = null;

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	async function render(): Promise<HTMLElement> {
		const app = { brewOverridesFor: () => ({}), setBrewOverrides: vi.fn(), state: getCremaUiState() } as unknown as CremaApp;
		const target = document.body.appendChild(document.createElement('div'));
		component = mount(WithAppContext, {
			target,
			props: { app, component: BrewDashboard, props: { ui: getCremaUiState().current } }
		});
		flushSync();
		await tick();
		return target;
	}

	function setup(brew: string | null, catalogueId = '') {
		const beans = getBeanStore();
		beans.clearAll();
		const g = beans.addGrinder(catalogueId ? '' : 'Mignon Specialita', catalogueId)!;
		beans.setActiveGrinder(g.id);
		beans.upsertBean({
			...blankBean('bean:a'),
			name: 'Ethiopia Guji',
			grinds: [{ grinderId: g.id, grinderName: g.name, grind: '4.2', starred: true }]
		});
		beans.setActiveBean('bean:a');
		getBrewGrind().set(brew);
		return g;
	}

	it('shows the grinder, the grind and "Bag 4.2" while the grind differs', async () => {
		setup('4.3');
		const root = await render();
		const block = root.querySelector<HTMLElement>('.bh-block-grinder')!;
		expect(block.textContent).toContain('Mignon Specialita');
		expect(block.textContent).toContain('Grind 4.3');
		const marker = block.querySelector<HTMLElement>('.gmark')!;
		expect(marker.textContent).toContain('Bag 4.2');
		expect(marker.title).toContain('Ethiopia Guji has 4.2 saved for Mignon Specialita');
		expect(block.querySelector('.bh-grinder-star'), 'the bag\'s starred grinder').not.toBeNull();
		// Quick Controls: the marker in the Grind card's label row and the ringed chip.
		const qc = root.querySelector<HTMLElement>('.qsheet')!;
		[...qc.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Grind')!.click();
		flushSync();
		expect(qc.querySelector('.qdg-marker')?.textContent).toContain('Bag 4.2');
		expect(qc.querySelector('.qchip.is-saved')?.textContent?.trim()).toBe('4.2');
	});

	it('no marker when the grind matches the bag', async () => {
		setup('4.2');
		const root = await render();
		expect(root.querySelector('.bh-block-grinder .gmark')).toBeNull();
	});

	it('no grind set is blank: "Grind —", an empty stepper, no marker, no chips, nothing to save (DESIGN)', async () => {
		setup(null, 'bookoo-motto-80');
		const root = await render();
		const block = root.querySelector<HTMLElement>('.bh-block-grinder')!;
		expect(block.textContent).toContain('Grind —');
		expect(block.querySelector('.gmark'), 'no marker without a grind').toBeNull();
		const qc = root.querySelector<HTMLElement>('.qsheet')!;
		[...qc.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Grind')!.click();
		flushSync();
		const value = qc.querySelector<HTMLButtonElement>('[aria-label="Grind: not set, edit"]');
		expect(value, 'the stepper value is blank').not.toBeNull();
		expect(value!.textContent?.trim()).toBe('');
		expect(qc.textContent, 'no 500 placeholder').not.toContain('500');
		expect(qc.querySelectorAll('[aria-label="Grind presets"] .qchip')).toHaveLength(0);
		expect(qc.querySelector('.qdg-marker')).toBeNull();
		expect(getBrewGrind().value).toBeNull();
	});

	it('the first + from blank goes to the middle of the scale on its step (500 on the Motto 80)', async () => {
		setup(null, 'bookoo-motto-80');
		const root = await render();
		const qc = root.querySelector<HTMLElement>('.qsheet')!;
		[...qc.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Grind')!.click();
		flushSync();
		qc.querySelector<HTMLButtonElement>('[aria-label="Increase Grind"]')!.click();
		flushSync();
		expect(getBrewGrind().value, 'the start, not a step from it').toBe('500');
		expect(root.querySelector('.bh-block-grinder')!.textContent).toContain('Grind 500 μm');
		qc.querySelector<HTMLButtonElement>('[aria-label="Increase Grind"]')!.click();
		flushSync();
		expect(getBrewGrind().value).toBe('501');
	});

	it('a grind that isn\'t a plain number shows as typed, in the header and the stepper (review N2)', async () => {
		setup('2.1.3');
		const root = await render();
		expect(root.querySelector('.bh-block-grinder')!.textContent).toContain('Grind 2.1.3');
		const qc = root.querySelector<HTMLElement>('.qsheet')!;
		[...qc.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Grind')!.click();
		flushSync();
		expect(qc.querySelector('[aria-label="Grind: 2.1.3, edit"]')).not.toBeNull();
		expect(qc.querySelectorAll('[aria-label="Grind presets"] .qchip'), 'no chips around a text grind').toHaveLength(0);
	});

	it('the picker lists the grinders with what the bag saved, and picks one', async () => {
		const g = setup('4.2');
		const other = getBeanStore().addGrinder('DF64')!;
		const root = await render();
		root.querySelector<HTMLElement>('.bh-block-grinder .bh-block-tap')!.click();
		flushSync();
		const panel = root.querySelector<HTMLElement>('[aria-label="Choose grinder"]')!;
		expect(panel.textContent).toContain("This bag's grinder · Saved 4.2");
		expect(panel.textContent).toContain('Nothing saved');
		[...panel.querySelectorAll<HTMLButtonElement>('.hpick-item')].find((b) => b.textContent?.includes('DF64'))!.click();
		await vi.waitFor(() => expect(getBeanStore().activeGrinder?.id).toBe(other.id));
		expect(g.id).not.toBe(other.id);
	});

	/** Type `name` into the picker's Add box and take its "Create" row. */
	async function createInPicker(root: HTMLElement, name: string): Promise<void> {
		root.querySelector<HTMLElement>('.bh-block-grinder .bh-block-tap')!.click();
		flushSync();
		root.querySelector<HTMLButtonElement>('[aria-label="Add a grinder"]')!.click();
		flushSync();
		await tick();
		const input = root.querySelector<HTMLInputElement>('[aria-label="Choose grinder"] input[role="combobox"]')!;
		input.value = name;
		input.dispatchEvent(new Event('input', { bubbles: true }));
		flushSync();
		const create = [...root.querySelectorAll('[role="option"]')].find((o) => o.textContent?.includes(`Create “${name}”`));
		if (!create) throw new Error('no Create row');
		create.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
		flushSync();
	}

	it('adding a model already owned picks it and offers "Add another", as Android (#46)', async () => {
		for (const t of getToasts()) dismissToast(t.id);
		const g = setup('4.2', 'niche-zero');
		const other = getBeanStore().addGrinder('DF64')!;
		getBeanStore().setActiveGrinder(other.id);
		const root = await render();
		await createInPicker(root, g.name);
		await vi.waitFor(() => expect(getBeanStore().activeGrinder?.id, 'the owned one is picked').toBe(g.id));
		expect(getBeanStore().grinders, 'nothing added yet').toHaveLength(2);
		const offer = getToasts().find((t) => t.action?.label === 'Add another');
		expect(offer?.message).toBe(`${g.name} is already one of your grinders`);

		offer!.action!.run();
		const second = getBeanStore().grinders.find((x) => x.id !== g.id && x.id !== other.id);
		expect(second?.name).toBe(`${g.name} 2`);
		expect(second?.presetId).toBe(g.presetId);
		expect(getBeanStore().activeGrinder?.id, 'Brew keeps the grinder it picked').toBe(g.id);
		expect(getToasts().some((t) => t.message === `Added ${g.name} 2`)).toBe(true);
		for (const t of getToasts()) dismissToast(t.id);
	});
});
