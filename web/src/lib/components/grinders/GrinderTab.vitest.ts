/**
 * Devices → Grinder (#488): the scale fields are a draft until "Save scale"
 * (Cancel drops it), save through the core's merge and refuse a bad scale;
 * Remove asks first, then archives (clearing the active pointer); a model
 * already owned offers "Add another" (FINAL N1, follow-up 18);
 * Restore brings the grinder back, the auto-save switch writes its setting,
 * and `?tab=grinder` opens the tab on the Devices page.
 */
import '#lib/testing/dom-stubs.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import { dismissConfirm, getActiveDialog } from '#lib/components/shared/confirm-dialog.svelte.js';
import { page } from '$app/state';
import { blankBean, getBeanStore } from '#lib/bean/index.js';
import { DEFAULT_GRIND_SCALE, grinderCatalogue, ScaleTier, type Grinder } from '#lib/bean/grinder.js';
import { getSettingsStore } from '#lib/settings/index.js';
import WithAppContext from '#lib/testing/WithAppContext.svelte';
import DevicesPage from '../../../routes/scale/+page.svelte';
import GrinderTab from './GrinderTab.svelte';
import { devicesHref, devicesTabOf } from './devices-link';
import { catalogueOfferLine, draftOf, parseScaleDraft, presetLabel, rangeNotSet, savedOnBags } from './grinder-devices';

const lib = getBeanStore();
const settings = getSettingsStore();

let component: ReturnType<typeof mount> | null = null;

beforeEach(() => {
	lib.clearAll();
	settings.reset();
	localStorage.clear();
});

afterEach(() => {
	const open = getActiveDialog();
	if (open) dismissConfirm(open.options);
	if (component) unmount(component);
	component = null;
	document.body.innerHTML = '';
	page.url = new URL('http://localhost/');
	vi.restoreAllMocks();
});

function render(): HTMLElement {
	const target = document.body.appendChild(document.createElement('div'));
	component = mount(GrinderTab, { target, props: {} });
	flushSync();
	return target;
}

function field(root: HTMLElement, label: string): HTMLInputElement {
	const l = [...root.querySelectorAll('label')].find((x) => x.textContent?.trim().startsWith(label));
	const input = l?.querySelector('input');
	if (!input) throw new Error(`no ${label} field`);
	return input;
}

function type(input: HTMLInputElement, value: string): void {
	input.value = value;
	input.dispatchEvent(new Event('input', { bubbles: true }));
	input.dispatchEvent(new Event('change', { bubbles: true }));
	flushSync();
}

function button(root: HTMLElement, name: string): HTMLButtonElement {
	const b = [...root.querySelectorAll('button')].find(
		(x) => (x.getAttribute('aria-label') ?? x.textContent?.trim()) === name
	);
	if (!b) throw new Error(`no ${name} button`);
	return b;
}

function click(root: HTMLElement, name: string): void {
	button(root, name).click();
	flushSync();
}

/** Answer the open dialog (`true`/`false` for a confirm, `'primary'`/`'secondary'`/`null` for a choice). */
async function answer(value: boolean | string | null): Promise<void> {
	await tick();
	const d = getActiveDialog();
	if (!d) throw new Error('no dialog open');
	d.resolve(value);
	dismissConfirm(d.options);
	await tick();
	flushSync();
}

/** A typed-name grinder: the default scale, no preset. */
function typedGrinder(name = 'Garage Grinder'): Grinder {
	const g = lib.addGrinder(name);
	if (!g) throw new Error('not added');
	return g;
}

describe('the Grinder tab', () => {
	it('an edited scale is a draft: typing writes nothing, Save scale saves it through saveGrinderEdit (FINAL N1)', () => {
		const g = typedGrinder();
		const save = vi.spyOn(lib, 'saveGrinderEdit');
		const root = render();
		expect(root.textContent).not.toContain('Save scale');

		type(field(root, 'Highest'), '40');
		type(field(root, 'Unit'), 'clicks');
		expect(save, 'nothing saved while typing').not.toHaveBeenCalled();
		expect(lib.getGrinder(g.id)?.scale).toEqual(DEFAULT_GRIND_SCALE);
		expect(root.textContent).toContain('Changes apply to Brew once saved.');

		click(root, 'Save scale');
		expect(save).toHaveBeenCalledTimes(1);
		const [base, edited] = save.mock.calls[0];
		expect(base.id).toBe(g.id);
		expect(edited.scale).toEqual({ ...DEFAULT_GRIND_SCALE, max: 40, unit: 'clicks' });
		expect(lib.getGrinder(g.id)?.scale).toEqual({ min: 0, max: 40, step: 0.1, unit: 'clicks' });
		expect(root.textContent).toContain('0–40 clicks');
		expect(root.textContent, 'saved: no draft left').not.toContain('Save scale');
	});

	it('Cancel drops the typed scale and shows the stored one again', () => {
		const g = typedGrinder();
		const root = render();
		type(field(root, 'Highest'), '40');
		click(root, 'Cancel');
		expect(field(root, 'Highest').value).toBe('20');
		expect(lib.getGrinder(g.id)?.scale).toEqual(DEFAULT_GRIND_SCALE);
		expect(root.textContent).not.toContain('Save scale');
	});

	it('typing the stored value back leaves nothing to save; Enter saves', () => {
		const g = typedGrinder();
		const root = render();
		type(field(root, 'Highest'), '40');
		type(field(root, 'Highest'), '20');
		expect(root.textContent).not.toContain('Save scale');
		type(field(root, 'Highest'), '30');
		field(root, 'Highest').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
		flushSync();
		expect(lib.getGrinder(g.id)?.scale.max).toBe(30);
	});

	it('refuses a bad scale with an inline error and saves nothing', () => {
		const g = typedGrinder();
		const save = vi.spyOn(lib, 'saveGrinderEdit');
		const root = render();

		type(field(root, 'Lowest'), '30');
		expect(root.querySelector('[role="alert"]')?.textContent).toBe('Lowest must be below Highest.');
		expect(field(root, 'Lowest').getAttribute('aria-invalid')).toBe('true');
		expect(button(root, 'Save scale').disabled, 'a refused scale cannot be saved').toBe(true);

		type(field(root, 'Lowest'), '0');
		type(field(root, 'Step'), '0');
		expect(root.querySelector('[role="alert"]')?.textContent).toBe('Step must be more than 0.');

		type(field(root, 'Step'), 'abc');
		expect(root.querySelector('[role="alert"]')?.textContent).toContain('Enter a number');

		expect(save).not.toHaveBeenCalled();
		expect(lib.getGrinder(g.id)?.scale).toEqual(DEFAULT_GRIND_SCALE);

		type(field(root, 'Step'), '0,5');
		expect(root.querySelector('[role="alert"]')?.textContent).toBe('');
		click(root, 'Save scale');
		expect(lib.getGrinder(g.id)?.scale.step).toBe(0.5);
	});

	it('Remove asks first; Cancel keeps the grinder (FINAL N1)', async () => {
		const g = typedGrinder();
		const root = render();
		click(root, 'Remove grinder');
		await tick();
		expect(getActiveDialog()?.options.title).toBe('Remove Garage Grinder?');
		expect(lib.getGrinder(g.id)?.archivedAt ?? null, 'nothing archived before the answer').toBeNull();
		await answer(false);
		expect(lib.grinders.map((x) => x.id)).toEqual([g.id]);
	});

	it('badges the active grinder and Remove archives it, clearing the pointer', async () => {
		const g = typedGrinder();
		lib.setActiveGrinder(g.id);
		const root = render();
		expect(root.querySelector('.gdv-badge')?.textContent).toBe('In use');

		click(root, 'Remove grinder');
		await answer(true);

		const stored = lib.getGrinder(g.id);
		expect(stored, 'archived, never deleted').not.toBeNull();
		expect(stored?.archivedAt).toEqual(expect.any(Number));
		expect(stored?.deletedAt ?? null).toBeNull();
		expect(lib.activeGrinderId).toBeNull();
		expect(lib.grinders).toHaveLength(0);
		expect(root.querySelector('.gdv-archived')?.textContent).toContain('Garage Grinder');
	});

	it('Restore brings an archived grinder back with the same id', () => {
		const g = typedGrinder();
		lib.archiveGrinder(g.id);
		const root = render();

		click(root, 'Restore Garage Grinder');

		expect(lib.grinders.map((x) => x.id)).toEqual([g.id]);
		expect(lib.archivedGrinders).toHaveLength(0);
		expect(lib.getGrinder(g.id)?.archivedAt ?? null).toBeNull();
		expect(root.querySelector('.gdv-archived')).toBeNull();
		expect(root.querySelector('.gdv-item')?.textContent).toContain('Garage Grinder');
	});

	it('the auto-save switch writes grindAutoSave; null reads as off', () => {
		expect(settings.current.grindAutoSave ?? null).toBeNull();
		const root = render();
		const sw = root.querySelector<HTMLButtonElement>('[role="switch"]')!;
		expect(sw.getAttribute('aria-checked')).toBe('false');

		sw.click();
		flushSync();
		expect(settings.current.grindAutoSave).toBe(true);
		expect(sw.getAttribute('aria-checked')).toBe('true');

		sw.click();
		flushSync();
		expect(settings.current.grindAutoSave).toBe(false);
	});

	it('renames, and refuses an empty name', () => {
		const g = typedGrinder();
		const root = render();
		click(root, 'Rename');
		const name = field(root, 'Name');
		name.value = '  ';
		name.dispatchEvent(new Event('input', { bubbles: true }));
		flushSync();
		click(root, 'Save name');
		expect(root.textContent).toContain('Enter a name.');
		expect(lib.getGrinder(g.id)?.name).toBe('Garage Grinder');

		name.value = 'Office grinder';
		name.dispatchEvent(new Event('input', { bubbles: true }));
		flushSync();
		click(root, 'Save name');
		expect(lib.getGrinder(g.id)?.name).toBe('Office grinder');
	});

	it('shows "Range not set" for a grinder from no verified scale, and Reset to preset for one with', () => {
		typedGrinder();
		let root = render();
		expect(root.textContent).toContain("Range not set: check your grinder's dial");
		expect(root.textContent).not.toContain('Reset to preset');
		unmount(component!);
		component = null;
		document.body.innerHTML = '';
		lib.clearAll();

		const entry = grinderCatalogue().find((c) => c.scale);
		if (!entry?.scale) throw new Error('no catalogue entry with a scale');
		const g = lib.addGrinder('', entry.id)!;
		lib.saveGrinderEdit(g, { ...g, scale: { ...entry.scale, max: entry.scale.max + 1 } });
		root = render();
		expect(root.textContent).not.toContain('Range not set');
		click(root, 'Reset to preset');
		expect(lib.getGrinder(g.id)?.scale).toEqual(entry.scale);
	});

	it('labels a typical preset "Typical range — check your dial" and a verified one by name (#501)', () => {
		const typical = grinderCatalogue().find((c) => c.tier === ScaleTier.Typical && c.scale)!;
		const verified = grinderCatalogue().find((c) => c.tier === ScaleTier.Verified && c.scale)!;
		expect(presetLabel(typical)).toBe('Typical range — check your dial');
		expect(presetLabel(verified)).toBe(`From the ${verified.name} preset`);
		lib.addGrinder('', typical.id);
		const root = render();
		expect(root.textContent).toContain('Typical range — check your dial');
		expect(root.textContent).not.toContain(`From the ${typical.name} preset`);
	});

	it('shows a Fellow Ode in thirds: the step as ⅓, the preview as a third (#488)', () => {
		const g = lib.addGrinder('', 'fellow-ode')!;
		expect(draftOf(g.scale).step).toBe('⅓');
		const root = render();
		expect(root.textContent).toContain('Steps of ⅓');
		expect(root.textContent).not.toContain('0.333');
		const step = [...root.querySelectorAll<HTMLInputElement>('.gdv-field input')][2];
		expect(step.value).toBe('⅓');
		expect(root.textContent, 'the ⅓ step reads as the stored third: no draft').not.toContain('Save scale');
		// Saving another field keeps the thirds step.
		const max = [...root.querySelectorAll<HTMLInputElement>('.gdv-field input')][1];
		max.value = '10';
		max.dispatchEvent(new Event('input', { bubbles: true }));
		flushSync();
		click(root, 'Save scale');
		expect(lib.getGrinder(g.id)?.scale.max).toBe(10);
		expect(lib.getGrinder(g.id)?.scale.step).toBeCloseTo(1 / 3, 6);
	});

	it('lists the grind each bag saved for the grinder', () => {
		const g = typedGrinder();
		lib.upsertBean({
			...blankBean('bean:guji'),
			name: 'Guji',
			grinds: [{ grinderId: g.id, grinderName: g.name, grind: '14', starred: true }]
		});
		lib.upsertBean({
			...blankBean('bean:kenya'),
			name: 'Kenya',
			grinds: [{ grinderId: 'grinder:other', grinderName: 'Other', grind: '3', starred: false }]
		});
		expect(savedOnBags(lib.beans, g.id).map((b) => b.bagName)).toEqual(['Guji']);
		const root = render();
		const panel = root.querySelector('.gdv-bag')!;
		expect(panel.textContent).toContain('Guji');
		expect(panel.textContent).toContain('14');
		expect(panel.querySelector('[aria-label="starred"]')).not.toBeNull();
		expect(root.textContent).not.toContain('Kenya');
	});

	it('adds a grinder through the type-or-pick box', async () => {
		const root = render();
		button(root, 'Add grinder').click();
		flushSync();
		await tick();
		const input = root.querySelector<HTMLInputElement>('input[role="combobox"]')!;
		input.value = 'My hand grinder';
		input.dispatchEvent(new Event('input', { bubbles: true }));
		flushSync();
		input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }));
		flushSync();
		const create = [...root.querySelectorAll('[role="option"]')].find((o) =>
			o.textContent?.includes('Create “My hand grinder”')
		);
		create?.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
		flushSync();
		expect(lib.grinders.map((g) => g.name)).toContain('My hand grinder');
	});

	/** Type `name` into the Add box and take "+ Create". */
	async function createInAddBox(root: HTMLElement, name: string): Promise<void> {
		button(root, 'Add grinder').click();
		flushSync();
		await tick();
		const input = root.querySelector<HTMLInputElement>('input[role="combobox"]')!;
		input.value = name;
		input.dispatchEvent(new Event('input', { bubbles: true }));
		flushSync();
		const create = [...root.querySelectorAll('[role="option"]')].find((o) => o.textContent?.includes(`Create “${name}”`));
		if (!create) throw new Error('no Create row');
		create.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
		flushSync();
	}

	it('adding a model already owned offers "Add another": a second Niche Zero (follow-up 18)', async () => {
		const first = lib.addGrinder('', 'niche-zero')!;
		const root = render();
		await createInAddBox(root, first.name);
		expect(getActiveDialog()?.options.title).toBe(`You already have ${first.name}`);
		await answer('secondary');
		const owned = lib.grinders;
		expect(owned).toHaveLength(2);
		const second = owned.find((g) => g.id !== first.id)!;
		expect(second.name).toBe(`${first.name} 2`);
		expect(second.presetId).toBe(first.presetId);
		expect(second.scale).toEqual(first.scale);
		expect(root.querySelector('.gdv-item.is-on')?.textContent, 'the new one is shown').toContain(`${first.name} 2`);
	});

	it('"Show it" adds nothing and shows the owned grinder; dismissing does nothing', async () => {
		const first = lib.addGrinder('', 'niche-zero')!;
		const other = typedGrinder();
		const root = render();
		[...root.querySelectorAll<HTMLButtonElement>('.gdv-item')].find((b) => b.textContent?.includes(other.name))!.click();
		flushSync();
		expect(root.querySelector('.gdv-item.is-on')?.textContent).toContain(other.name);
		await createInAddBox(root, first.name);
		await answer('primary');
		expect(lib.grinders).toHaveLength(2);
		expect(root.querySelector('.gdv-item.is-on')?.textContent).toContain(first.name);

		await createInAddBox(root, first.name);
		await answer(null);
		expect(lib.grinders).toHaveLength(2);
	});
});

describe('parseScaleDraft', () => {
	it('reads decimal commas and refuses min ≥ max, step ≤ 0 and blanks', () => {
		expect(parseScaleDraft({ min: '0', max: '999', step: '1', unit: ' μm ' })).toEqual({
			scale: { min: 0, max: 999, step: 1, unit: 'μm' },
			error: null
		});
		expect(parseScaleDraft({ min: '0', max: '2,5', step: '0,1', unit: '' }).error).toBeNull();
		expect(parseScaleDraft({ min: '5', max: '5', step: '1', unit: '' }).error).not.toBeNull();
		expect(parseScaleDraft({ min: '0', max: '5', step: '-1', unit: '' }).error).not.toBeNull();
		expect(parseScaleDraft({ min: '', max: '5', step: '1', unit: '' }).error).not.toBeNull();
	});

	it('reads a thirds step typed as ⅓ or 1/3', () => {
		for (const step of ['⅓', '1/3']) {
			const { scale, error } = parseScaleDraft({ min: '1', max: '11', step, unit: '' });
			expect(error, step).toBeNull();
			expect(scale.step, step).toBeCloseTo(1 / 3, 9);
		}
	});

	it('says what a catalogue offer brings: its scale, a typical range, or Devices', () => {
		const typical = grinderCatalogue().find((c) => c.tier === ScaleTier.Typical && c.scale)!;
		const verified = grinderCatalogue().find((c) => c.tier === ScaleTier.Verified && c.scale)!;
		expect(catalogueOfferLine(verified)).toBe('Adds it to your grinders, with its scale');
		expect(catalogueOfferLine(typical)).toBe('Adds it to your grinders · Typical range — check your dial');
		expect(catalogueOfferLine(undefined)).toBe('Adds it to your grinders; set its range on Devices');
	});

	it('"Range not set" clears once the scale is set by hand', () => {
		const g = typedGrinder();
		expect(rangeNotSet(g)).toBe(true);
		expect(rangeNotSet({ ...g, scale: { ...g.scale, max: 30 } })).toBe(false);
	});
});

describe('the Devices page tabs', () => {
	it('?tab=grinder names the Grinder tab; anything else is Scale', () => {
		expect(devicesTabOf(new URLSearchParams('tab=grinder'))).toBe('grinder');
		expect(devicesTabOf(new URLSearchParams(''))).toBe('scale');
		expect(devicesTabOf(new URLSearchParams('tab=scale'))).toBe('scale');
		expect(devicesHref('grinder')).toBe('/scale?tab=grinder');
		expect(devicesHref()).toBe('/scale');
	});

	function renderPage(url: string): HTMLElement {
		page.url = new URL(url);
		const target = document.body.appendChild(document.createElement('div'));
		component = mount(WithAppContext, { target, props: { app: null, component: DevicesPage } });
		flushSync();
		return target;
	}

	it('opens on the Grinder tab with ?tab=grinder', () => {
		const root = renderPage('http://localhost/scale?tab=grinder');
		expect(button(root, 'Grinder 0').getAttribute('aria-pressed')).toBe('true');
		expect(root.textContent).toContain('Your grinders');
		expect(root.querySelector('.sc-hero')).toBeNull();
	});

	it('opens on the Scale tab without it', () => {
		const root = renderPage('http://localhost/scale');
		expect(root.querySelector('.dv-tab[aria-pressed="true"]')?.textContent).toContain('Scale');
		expect(root.textContent).not.toContain('Your grinders');
		expect(root.querySelector('.sc-hero')).not.toBeNull();
	});
});
