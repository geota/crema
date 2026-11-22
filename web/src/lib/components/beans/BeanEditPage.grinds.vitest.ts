/**
 * The bag editor's "Grind per grinder" block (#488, "Bag grinds"): rows are
 * part of the editor's draft, so editing, starring, removing and adding
 * write nothing until Save, Cancel discards them, and Save refuses a row with
 * an empty grind. A grinder removed from Devices keeps its row, read-only and
 * removable. Bag detail shows the same list read-only.
 */

import 'fake-indexeddb/auto';
import '#lib/testing/dom-stubs.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, tick, unmount, type Component } from 'svelte';

const nav = vi.hoisted(() => ({ goto: vi.fn(async () => {}) }));
vi.mock('$app/navigation', () => ({
	goto: nav.goto,
	afterNavigate: () => {},
	beforeNavigate: () => {}
}));
vi.mock('#lib/shell/app-context.js', () => ({
	getCremaAppContext: () => () => ({ services: null, app: null })
}));

import { blankBean, blankRoaster, getBeanStore, type Bean } from '#lib/bean/index.js';
import type { BagGrind, Grinder } from '#lib/bean/grinder.js';
import { dismissConfirm, getActiveDialog } from '#lib/components/shared/confirm-dialog.svelte.js';
import { setHistoryReadsForTest, type HistoryReads } from '#lib/history/reads.svelte.js';
import BeanEditPage from './BeanEditPage.svelte';
import BeanDrawer from './BeanDrawer.svelte';

const library = getBeanStore();
let component: ReturnType<typeof mount> | null = null;

function grinder(name: string): Grinder {
	return library.addGrinder(name)!;
}

/** Motto 80 (0–999 μm) and a custom "Hand" grinder (default scale). */
function setup(grinds: (g: { motto: Grinder; hand: Grinder }) => BagGrind[]): { motto: Grinder; hand: Grinder; bean: Bean } {
	const motto = library.saveGrinderEdit(grinder('Motto 80'), {
		...grinder('Motto 80'),
		scale: { min: 0, max: 999, step: 1, unit: 'μm' }
	})!;
	const hand = grinder('Hand');
	const roaster = { ...blankRoaster('Onyx'), id: 'roaster:onyx' };
	library.upsertRoaster(roaster);
	library.upsertBean({
		...blankBean('bean:a'),
		name: 'Guji',
		roasterId: roaster.id,
		roastedOn: '2026-11-01',
		bagSize: 250,
		remaining: 200,
		grinds: grinds({ motto, hand })
	});
	return { motto, hand, bean: library.getBean('bean:a')! };
}

function row(g: Grinder, grind: string, starred = false): BagGrind {
	return { grinderId: g.id, grinderName: g.name, grind, starred };
}

function mountComp(c: Component<Record<string, unknown>>, props: Record<string, unknown>): HTMLElement {
	const target = document.createElement('div');
	document.body.appendChild(target);
	component = mount(c, { target, props });
	flushSync();
	return target;
}

function byLabel(root: HTMLElement, label: string): HTMLButtonElement {
	const b = root.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`);
	if (!b) throw new Error(`no button labelled ${label}`);
	return b;
}

function button(root: HTMLElement, text: string): HTMLButtonElement {
	const b = [...root.querySelectorAll('button')].find((el) => el.textContent?.trim() === text);
	if (!b) throw new Error(`no ${text} button`);
	return b;
}

async function save(root: HTMLElement): Promise<void> {
	button(root, 'Save').click();
	await tick();
	await tick();
	flushSync();
}

const stored = (): BagGrind[] => library.getBean('bean:a')!.grinds ?? [];

beforeEach(() => {
	localStorage.clear();
	library.clearAll();
	nav.goto.mockClear();
});

afterEach(() => {
	const open = getActiveDialog();
	if (open) dismissConfirm(open.options);
	if (component) unmount(component);
	component = null;
	setHistoryReadsForTest(null);
	document.body.innerHTML = '';
});

describe('Grind per grinder', () => {
	it('an edited row is marked, and Save writes it', async () => {
		const { motto } = setup(({ motto }) => [row(motto, '470', true)]);
		const root = mountComp(BeanEditPage as Component<Record<string, unknown>>, { bean: library.getBean('bean:a')! });
		byLabel(root, 'Increase Motto 80 grind').click();
		flushSync();
		expect(root.textContent).toContain('Changed from 470 μm · saved when you tap Save');
		expect(stored()[0].grind, 'nothing written before Save').toBe('470');

		// Type a value outside the scale: kept as typed, with a warning.
		byLabel(root, 'Motto 80 grind: 471 μm, edit').click();
		flushSync();
		const input = root.querySelector<HTMLInputElement>('input[aria-label="Motto 80 grind"]')!;
		input.value = '1200';
		input.dispatchEvent(new Event('input', { bubbles: true }));
		input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
		flushSync();
		expect(root.textContent).toContain("Outside this grinder's scale (0–999 μm)");

		await save(root);
		expect(stored()).toEqual([row(motto, '1200', true)]);
	});

	it('Cancel writes nothing', async () => {
		const { bean } = setup(({ motto, hand }) => [row(motto, '470', true), row(hand, '4')]);
		const root = mountComp(BeanEditPage as Component<Record<string, unknown>>, { bean });
		byLabel(root, 'Increase Motto 80 grind').click();
		byLabel(root, 'Hand: this bag\'s grinder').click();
		byLabel(root, 'Remove Motto 80 from this bag').click();
		flushSync();
		button(root, 'Cancel').click();
		await tick();
		const d = getActiveDialog();
		expect(d?.options.message).toBe('Discard unsaved changes?');
		d!.resolve(true);
		dismissConfirm(d!.options);
		await tick();
		expect(library.getBean('bean:a')).toEqual(bean);
	});

	it('starring moves the single star; toggling it off leaves none', async () => {
		const { motto, hand } = setup(({ motto, hand }) => [row(motto, '470', true), row(hand, '4')]);
		const root = mountComp(BeanEditPage as Component<Record<string, unknown>>, { bean: library.getBean('bean:a')! });
		const handStar = byLabel(root, "Hand: this bag's grinder");
		handStar.click();
		flushSync();
		expect(handStar.getAttribute('aria-pressed')).toBe('true');
		expect(byLabel(root, "Motto 80: this bag's grinder").getAttribute('aria-pressed')).toBe('false');
		await save(root);
		expect(stored()).toEqual([row(motto, '470', false), row(hand, '4', true)]);
	});

	it('toggling the starred row off saves no star', async () => {
		const { motto } = setup(({ motto }) => [row(motto, '470', true)]);
		const root = mountComp(BeanEditPage as Component<Record<string, unknown>>, { bean: library.getBean('bean:a')! });
		byLabel(root, "Motto 80: this bag's grinder").click();
		await save(root);
		expect(stored()).toEqual([row(motto, '470', false)]);
	});

	it('removing a row removes it on Save', async () => {
		const { hand } = setup(({ motto, hand }) => [row(motto, '470', true), row(hand, '4')]);
		const root = mountComp(BeanEditPage as Component<Record<string, unknown>>, { bean: library.getBean('bean:a')! });
		byLabel(root, 'Remove Motto 80 from this bag').click();
		flushSync();
		expect(stored()).toHaveLength(2);
		await save(root);
		expect(stored()).toEqual([row(hand, '4')]);
		expect(library.getGrinder(hand.id), 'the grinder stays on Devices').not.toBeNull();
	});

	it('Save refuses a row with an empty grind', async () => {
		setup(({ motto, hand }) => [row(motto, '470'), row(hand, '')]);
		const root = mountComp(BeanEditPage as Component<Record<string, unknown>>, { bean: library.getBean('bean:a')! });
		byLabel(root, 'Increase Motto 80 grind').click();
		flushSync();
		await save(root);
		expect(root.textContent).toContain('Enter a grind or remove this row');
		expect(stored()[0].grind, 'nothing saved').toBe('470');
		expect(nav.goto).not.toHaveBeenCalled();

		byLabel(root, 'Remove Hand from this bag').click();
		flushSync();
		await save(root);
		expect(stored().map((g) => g.grind)).toEqual(['471']);
	});

	it("a grinder removed from Devices: its row is read-only, but can be removed", async () => {
		const { hand } = setup(({ motto, hand }) => [row(motto, '470', true), row(hand, '6.5')]);
		library.archiveGrinder(hand.id);
		const root = mountComp(BeanEditPage as Component<Record<string, unknown>>, { bean: library.getBean('bean:a')! });
		expect(root.textContent).toContain('No longer on Devices · kept until you remove it');
		expect(root.querySelector('button[aria-label="Increase Hand grind"]'), 'no stepper').toBeNull();
		expect(root.querySelector(`button[aria-label="Hand: this bag's grinder"]`), 'no star').toBeNull();
		expect(root.querySelector('[aria-label="Hand grind: 6.5"]')?.textContent?.trim()).toBe('6.5');

		byLabel(root, 'Remove Hand from this bag').click();
		await save(root);
		expect(stored().map((g) => g.grinderId)).not.toContain(hand.id);
	});

	/** Type in the Add box and pick the option titled `title`. */
	function addGrinder(root: HTMLElement, typed: string, title = `+ Create “${typed}”`): void {
		const box = root.querySelector<HTMLInputElement>('input[role="combobox"]')!;
		box.focus();
		box.value = typed;
		box.dispatchEvent(new Event('input', { bubbles: true }));
		flushSync();
		const opt = [...root.querySelectorAll('[role="option"]')].find(
			(o) => o.querySelector('.gtp-title')?.textContent?.trim() === title
		);
		if (!opt) throw new Error(`no option ${title}`);
		opt.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
		flushSync();
	}

	it('Add from the catalogue stages the grinder: written, and made active, only on Save (FINAL S7)', async () => {
		setup(() => []);
		library.setActiveGrinder(null);
		setHistoryReadsForTest({
			answer: async () => [{ grinder: 'niche zero', grind: '14', completedAt: 1 }]
		} as unknown as HistoryReads);
		const root = mountComp(BeanEditPage as Component<Record<string, unknown>>, { bean: library.getBean('bean:a')! });
		const box = root.querySelector<HTMLInputElement>('input[role="combobox"]')!;
		box.focus();
		box.value = 'niche';
		box.dispatchEvent(new Event('input', { bubbles: true }));
		flushSync();
		expect(root.textContent).toContain('Well-known grinders');
		box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
		flushSync();

		expect(library.grinders.some((g) => /niche/i.test(g.name)), 'not on Devices before Save').toBe(false);
		expect(library.activeGrinderId, 'Brew unchanged before Save').toBeNull();
		expect(stored(), 'the bag waits for Save').toEqual([]);
		for (let i = 0; i < 5; i++) await tick();
		flushSync();
		const label = root.querySelector('button[aria-label^="Niche"][aria-label*=" grind: 14"]');
		expect(label, 'the row is seeded from the last shot, on the catalogue scale').not.toBeNull();

		await save(root);
		const niche = library.grinders.find((g) => /niche/i.test(g.name));
		expect(niche, 'on Devices after Save').toBeDefined();
		expect(niche!.presetId, 'from the catalogue').toBeTruthy();
		expect(stored()).toEqual([{ grinderId: niche!.id, grinderName: niche!.name, grind: '14', starred: false }]);
		expect(library.activeGrinderId, 'with none active, the saved one becomes it').toBe(niche!.id);
	});

	it('Cancel after "+ Create" on a typo writes no grinder and leaves Brew as it was', async () => {
		setup(() => []);
		library.setActiveGrinder(null);
		const before = library.allGrinders;
		const root = mountComp(BeanEditPage as Component<Record<string, unknown>>, { bean: library.getBean('bean:a')! });
		addGrinder(root, 'Nichx');
		expect(root.querySelector('button[aria-label="Remove Nichx from this bag"]')).not.toBeNull();
		expect(library.allGrinders, 'nothing on Devices').toEqual(before);

		button(root, 'Cancel').click();
		await tick();
		const d = getActiveDialog();
		d!.resolve(true);
		dismissConfirm(d!.options);
		await tick();
		expect(library.allGrinders.some((g) => g.name === 'Nichx')).toBe(false);
		expect(library.activeGrinderId).toBeNull();
		expect(stored()).toEqual([]);
	});

	it('an archived grinder picked comes back only on Save, with its id', async () => {
		const { hand } = setup(() => []);
		library.archiveGrinder(hand.id);
		const root = mountComp(BeanEditPage as Component<Record<string, unknown>>, { bean: library.getBean('bean:a')! });
		addGrinder(root, 'Hand', 'Hand');
		expect(root.querySelector('button[aria-label="Increase Hand grind"]'), 'an editable row').not.toBeNull();
		expect(library.getGrinder(hand.id)!.archivedAt, 'still archived before Save').not.toBeNull();

		byLabel(root, 'Increase Hand grind').click();
		flushSync();
		await save(root);
		expect(library.getGrinder(hand.id)!.archivedAt ?? null, 'restored on Save').toBeNull();
		expect(stored().map((g) => g.grinderId)).toEqual([hand.id]);
	});

	it('a staged grinder whose row was removed again writes nothing', async () => {
		setup(() => []);
		const before = library.allGrinders;
		const root = mountComp(BeanEditPage as Component<Record<string, unknown>>, { bean: library.getBean('bean:a')! });
		addGrinder(root, 'Zqx Mill');
		byLabel(root, 'Remove Zqx Mill from this bag').click();
		flushSync();
		await save(root);
		expect(library.allGrinders).toEqual(before);
	});

	it('a new bag shows only the Add box', () => {
		const root = mountComp(BeanEditPage as Component<Record<string, unknown>>, {
			bean: blankBean('bean:new'),
			isNew: true
		});
		const block = root.querySelector('#be-bag')!;
		expect(block.querySelector('input[role="combobox"]')).not.toBeNull();
		expect(block.querySelectorAll('button[aria-label^="Remove "]')).toHaveLength(0);
		expect(block.textContent).not.toContain('Grinder\n');
	});
});

describe('bag detail', () => {
	it('lists the saved grinds read-only, with ★ and the unit', () => {
		const { bean } = setup(({ motto, hand }) => [row(motto, '480', true), row(hand, '4')]);
		const root = mountComp(BeanDrawer as Component<Record<string, unknown>>, {
			bean,
			roaster: null,
			isActive: false,
			onClose: () => {},
			onSetActive: () => {},
			onEdit: () => {},
			onToggleArchived: () => {},
			onToggleFavourite: () => {},
			onOpenShot: () => {},
			onSeeAllShots: () => {}
		});
		const text = root.textContent ?? '';
		expect(text).toContain('Motto 80');
		expect(text).toContain('★');
		expect(text).toContain('480 μm');
		expect(text).toContain("this bag's grinder");
		expect(text).not.toContain('Setting');
	});
});
