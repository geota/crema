/**
 * `$lib/bean/catalogue-clash.vitest` — the catalogue clash prompt end to end:
 * the core clash list (real wasm), the "Keep mine" / "Use catalogue" /
 * dismiss dialog (the shared `ConfirmDialog` host, mounted), and what each
 * answer applies — for a bag pick (incl. the roaster it resolves to) and a
 * roaster-form pick.
 */

import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import { initTestWasm } from '$lib/testing/test-init';
import ConfirmDialog from '$lib/components/shared/ConfirmDialog.svelte';
import { blankBean, blankRoaster, type Bean, type Roaster } from './model.ts';
import {
	CatalogueField,
	CLASH_TITLE,
	clashMessage,
	joinFieldLabels,
	parseCataloguePage,
	pickedRoasterRow,
	runCataloguePick,
	runCatalogueRoasterPick,
	ROASTER_FORM_FIELD_LABELS,
	type CatalogueCoffeeBag,
	type CataloguePickInput,
	type CataloguePickResult,
	type CatalogueRoaster
} from './catalogue.ts';

beforeAll(async () => {
	await initTestWasm();
});

const ENTRY: CatalogueCoffeeBag = parseCataloguePage({
	data: [
		{
			id: 'cb-1',
			canonical_roaster_id: 'cr-1',
			canonical_roaster_name: 'Onyx Coffee Lab',
			name: 'Ethiopia Guji Hambela',
			roast_level: 'Light',
			country: 'Ethiopia',
			region: 'Guji',
			processing: 'Washed',
			tasting_notes: 'Bergamot, peach',
			created_at: 'x',
			updated_at: 'x'
		}
	]
}).entries[0];

const ONYX: CatalogueRoaster = {
	id: 'cr-1',
	name: 'Onyx Coffee Lab',
	website: 'https://onyxcoffeelab.com',
	country: 'USA'
};

let host: ReturnType<typeof mount> | null = null;

beforeEach(() => {
	const target = document.createElement('div');
	document.body.appendChild(target);
	host = mount(ConfirmDialog, { target });
	flushSync();
});

afterEach(() => {
	if (host) unmount(host);
	host = null;
	document.body.innerHTML = '';
});

const dialogEl = () => document.querySelector<HTMLElement>('[role="dialog"]');
const button = (label: string) =>
	[...document.querySelectorAll<HTMLButtonElement>('[role="dialog"] button')].find(
		(b) => b.textContent?.trim() === label
	);

/** Let the pick reach the prompt (or finish), then render. */
async function settle(): Promise<void> {
	await tick();
	await Promise.resolve();
	flushSync();
}

function input(bean: Bean, roasterInput = '', roasters: Roaster[] = []): () => CataloguePickInput {
	return () => ({ bean, entry: ENTRY, roasterInput, roasters, fetched: ONYX });
}

/** A bean whose processing / roast level / tasting notes the user typed. */
function typedBean(): Bean {
	const b = { ...blankBean('bean:1'), roastLevel: 8, tastingNotes: 'jammy' };
	b.origin = { ...b.origin, processing: 'Natural', country: 'ethiopia ' };
	return b;
}

describe('clash labels', () => {
	it('joins naturally and caps long lists at four + "and N more"', () => {
		expect(joinFieldLabels(['A'])).toBe('A');
		expect(joinFieldLabels(['A', 'B'])).toBe('A and B');
		expect(joinFieldLabels(['A', 'B', 'C'])).toBe('A, B and C');
		expect(joinFieldLabels(['A', 'B', 'C', 'D', 'E'])).toBe('A, B, C, D and E');
		expect(joinFieldLabels(['A', 'B', 'C', 'D', 'E', 'F', 'G'])).toBe('A, B, C, D and 3 more');
	});

	it('builds the body from human labels', () => {
		expect(
			clashMessage([CatalogueField.RoastLevel, CatalogueField.Processing, CatalogueField.TastingNotes])
		).toBe('The catalogue has different values for Roast level, Process and Tasting notes.');
		expect(clashMessage([CatalogueField.RoasterWebsite])).toContain('Roaster website');
		expect(clashMessage([CatalogueField.RoasterWebsite], ROASTER_FORM_FIELD_LABELS)).toBe(
			'The catalogue has different values for Website.'
		);
	});
});

describe('bag pick', () => {
	it('no clash → no dialog, every empty field filled, roaster seeded', async () => {
		const result = await runCataloguePick(input(blankBean('bean:1')));
		expect(dialogEl()).toBeNull();
		expect(result).not.toBeNull();
		const r = result as CataloguePickResult;
		expect(r.bean.name).toBe('Ethiopia Guji Hambela');
		expect(r.bean.origin.processing).toBe('Washed');
		expect(r.bean.roastLevel).toBe(2);
		expect(r.bean.canonicalCoffeeBagId).toBe('cb-1');
		expect(r.roaster?.isNew).toBe(true);
		const row = pickedRoasterRow(r.roaster!);
		expect(row.id).toMatch(/^roaster:/);
		expect(row).toMatchObject({
			name: 'Onyx Coffee Lab',
			website: 'https://onyxcoffeelab.com',
			country: 'USA',
			catalogueRoasterId: 'cr-1',
			canonicalRoasterId: null
		});
	});

	it('clash → dialog with the clashing labels, Keep mine focused', async () => {
		const pending = runCataloguePick(input(typedBean()));
		await settle();
		const d = dialogEl();
		expect(d).not.toBeNull();
		expect(d!.textContent).toContain(CLASH_TITLE);
		// Country is case/space-equal → not listed.
		expect(d!.textContent).toContain(
			'The catalogue has different values for Process, Roast level and Tasting notes.'
		);
		await Promise.resolve();
		expect(document.activeElement).toBe(button('Keep mine'));
		button('Keep mine')!.click();
		await pending;
	});

	it('Keep mine fills only the empty fields', async () => {
		const pending = runCataloguePick(input(typedBean()));
		await settle();
		button('Keep mine')!.click();
		const r = (await pending)!;
		expect(dialogEl()).toBeNull();
		expect(r.bean.origin.processing).toBe('Natural');
		expect(r.bean.roastLevel).toBe(8);
		expect(r.bean.tastingNotes).toBe('jammy');
		expect(r.bean.origin.region).toBe('Guji');
		expect(r.bean.name).toBe('Ethiopia Guji Hambela');
	});

	it('Use catalogue replaces the clashing fields too', async () => {
		const pending = runCataloguePick(input(typedBean()));
		await settle();
		button('Use catalogue')!.click();
		const r = (await pending)!;
		expect(r.bean.origin.processing).toBe('Washed');
		expect(r.bean.roastLevel).toBe(2);
		expect(r.bean.tastingNotes).toBe('Bergamot, peach');
	});

	it('dismiss (Escape) applies nothing', async () => {
		const bean = typedBean();
		const before = JSON.stringify(bean);
		const pending = runCataloguePick(input(bean));
		await settle();
		dialogEl()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
		expect(await pending).toBeNull();
		expect(JSON.stringify(bean)).toBe(before);
		expect(dialogEl()).toBeNull();
	});

	it('dismiss (scrim click) applies nothing', async () => {
		const pending = runCataloguePick(input(typedBean()));
		await settle();
		document.querySelector<HTMLElement>('.cd-scrim')!.click();
		expect(await pending).toBeNull();
	});

	it("lists the matched roaster's fields in the same dialog", async () => {
		const mine = { ...blankRoaster('Onyx Coffee Lab', 'roaster:1'), website: 'https://onyx.example' };
		const pending = runCataloguePick(input(typedBean(), '', [mine]));
		await settle();
		expect(dialogEl()!.textContent).toContain(
			'Process, Roast level, Tasting notes and Roaster website.'
		);
		button('Use catalogue')!.click();
		const r = (await pending)!;
		expect(r.roaster).toMatchObject({ isNew: false, name: 'Onyx Coffee Lab' });
		expect(r.roaster!.roaster).toMatchObject({
			id: 'roaster:1',
			website: 'https://onyxcoffeelab.com',
			country: 'USA',
			catalogueRoasterId: 'cr-1'
		});
	});

	it('Tab stays inside the dialog', async () => {
		const pending = runCataloguePick(input(typedBean()));
		await settle();
		await Promise.resolve();
		const keep = button('Keep mine')!;
		const use = button('Use catalogue')!;
		keep.focus();
		keep.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
		expect(document.activeElement).toBe(use);
		use.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true }));
		expect(document.activeElement).toBe(keep);
		keep.click();
		await pending;
	});
});

describe('roaster form pick', () => {
	it('no clash fills empties and links the catalogue roaster, never the dup pointer', async () => {
		const mine = { ...blankRoaster('', 'roaster:1'), canonicalRoasterId: 'roaster:canon' };
		const r = (await runCatalogueRoasterPick(() => mine, ONYX))!;
		expect(dialogEl()).toBeNull();
		expect(r.roaster).toMatchObject({
			name: 'Onyx Coffee Lab',
			website: 'https://onyxcoffeelab.com',
			country: 'USA',
			catalogueRoasterId: 'cr-1',
			canonicalRoasterId: 'roaster:canon'
		});
	});

	it('a clash asks with roaster-form labels; dismiss changes nothing', async () => {
		const mine = { ...blankRoaster('Onyx', 'roaster:1'), country: 'Canada' };
		const pending = runCatalogueRoasterPick(() => mine, ONYX);
		await settle();
		expect(dialogEl()!.textContent).toContain(
			'The catalogue has different values for Name and Country.'
		);
		dialogEl()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
		expect(await pending).toBeNull();
	});

	it('Keep mine keeps the typed fields; Use catalogue replaces them', async () => {
		const mine = { ...blankRoaster('Onyx', 'roaster:1'), country: 'Canada' };
		let pending = runCatalogueRoasterPick(() => mine, ONYX);
		await settle();
		button('Keep mine')!.click();
		let r = (await pending)!;
		expect(r.roaster).toMatchObject({ name: 'Onyx', country: 'Canada', website: 'https://onyxcoffeelab.com' });
		pending = runCatalogueRoasterPick(() => mine, ONYX);
		await settle();
		button('Use catalogue')!.click();
		r = (await pending)!;
		expect(r.roaster).toMatchObject({ name: 'Onyx Coffee Lab', country: 'USA' });
	});
});
