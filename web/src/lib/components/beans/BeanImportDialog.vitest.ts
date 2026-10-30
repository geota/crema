import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { initTestWasm } from '#lib/testing/test-init.js';
import { getBeanStore, type Bean } from '#lib/bean/index.js';
import BeanImportDialog from './BeanImportDialog.svelte';

/**
 * F11b: Android's bean export (`crema-beans.crema.jsonl`, the core's Crema
 * JSONL) imports on web through the real import dialog. The fixture in
 * `core/de1-domain/tests/fixtures/beans/` was written by Android's
 * `beanLibraryJsonl` (`BeanInterchangeTest.kt`).
 */
const FIXTURE = resolve(
	process.cwd(),
	'../core/de1-domain/tests/fixtures/beans/android-beans.crema.jsonl'
);

async function settle(until: () => boolean): Promise<void> {
	for (let i = 0; i < 50 && !until(); i++) {
		await new Promise((r) => setTimeout(r, 10));
		await tick();
	}
}

describe('BeanImportDialog: an Android bean export (F11b)', () => {
	let component: ReturnType<typeof mount> | null = null;
	beforeEach(async () => {
		await initTestWasm();
		getBeanStore().clearAll();
		localStorage.clear();
	});
	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	/** Open the dialog, pick the Android file and press Import; returns the button's label. */
	async function importFixture(): Promise<string> {
		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(BeanImportDialog, { target, props: { onClose: () => {} } });
		flushSync();

		const input = target.querySelector<HTMLInputElement>('input[type="file"]')!;
		const file = new File([readFileSync(FIXTURE, 'utf8')], 'crema-beans.crema.jsonl');
		Object.defineProperty(input, 'files', { value: [file], configurable: true });
		input.dispatchEvent(new Event('change', { bubbles: true }));

		const importButton = (): HTMLButtonElement | undefined =>
			[...target.querySelectorAll('button')].find((b) => /^\s*Import \d/.test(b.textContent ?? ''));
		await settle(() => importButton() !== undefined);
		const label = importButton()?.textContent?.trim() ?? '';
		importButton()!.click();
		await settle(() => !importButton());
		return label;
	}

	it('imports every bean and roaster from the Android file', async () => {
		expect(await importFixture()).toMatch(/Import 2 items/);
		await settle(() => getBeanStore().beans.length > 0);

		const library = getBeanStore();
		expect(library.beans.map((b) => b.id).sort()).toEqual(['bean:android-1', 'bean:android-2']);
		const halo = library.getBean('bean:android-1')!;
		expect(halo.name).toBe('Halo Hambela');
		expect(halo.roasterId).toBe('roaster:android-1');
		expect(halo.tags).toEqual(['comp', 'washed']);
		expect([halo.frozenOn, halo.defrostedOn]).toEqual(['2026-09-05', '2026-09-20']);
		expect(halo.visualizerId).toBe('vb-1');
		expect(halo.metadata).toEqual({ note: 'kept' });
		expect(library.getBean('bean:android-2')?.favourite).toBe(true);
		expect(library.roasters.map((r) => [r.id, r.name, r.city])).toEqual([
			['roaster:android-1', 'Onyx', 'Rogers']
		]);
	});

	// F11c: the core merge rule decides a shared id — the newer row wins.
	it('re-importing updates older library rows and keeps newer ones', async () => {
		const fixtureBeans = readFileSync(FIXTURE, 'utf8')
			.split('\n')
			.filter((l) => l.includes('"kind":"bean"'))
			.map((l) => JSON.parse(l) as Bean);
		const [halo, house] = fixtureBeans;
		getBeanStore().bulkAdd(
			[
				{ ...halo, name: 'Halo (older here)', updatedAt: halo.updatedAt - 1 },
				{ ...house, name: 'House (edited here)', updatedAt: house.updatedAt + 1 }
			],
			[]
		);
		expect(await importFixture()).toMatch(/Import 1 item\b/);
		await settle(() => getBeanStore().getBean('bean:android-1')?.name === 'Halo Hambela');
		expect(getBeanStore().getBean('bean:android-1')?.name).toBe('Halo Hambela');
		expect(getBeanStore().getBean('bean:android-2')?.name).toBe('House (edited here)');
		expect(getBeanStore().beans).toHaveLength(2);
	});
});
