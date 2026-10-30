/**
 * F12 (validation P2): the Profiles page's Export buttons hand out the core's
 * profile file. The page is mounted and its buttons clicked; the downloads are
 * compared with the core's output (`crema-profile.json` is the core's export of
 * Android's "My 7g", see `core/de1-domain/src/profile_file.rs`).
 */

import '#lib/testing/dom-stubs.js';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';

const downloads: { name: string; blob: Blob }[] = [];
vi.mock('#lib/utils/download.js', () => ({
	downloadBlob: (name: string, blob: Blob) => downloads.push({ name, blob }),
	filenameStamp: () => 'stamp'
}));

import { initTestWasm } from '#lib/testing/test-init.js';
import WithAppContext from '#lib/testing/WithAppContext.svelte';
import { getProfileStore, importProfileFiles } from '#lib/profiles/index.js';
import { exportProfileFile as coreExportProfileFile } from '#lib/wasm/de1_wasm.js';
import ProfilesPage from './+page.svelte';

const FIXTURES = path.resolve(process.cwd(), '../core/de1-domain/tests/fixtures/profiles');
const fixture = (name: string): string => readFileSync(path.resolve(FIXTURES, name), 'utf8');

describe('the Profiles page exports the core file (AC-F12.6, web)', () => {
	let component: ReturnType<typeof mount> | null = null;

	beforeEach(async () => {
		await initTestWasm();
		localStorage.clear();
		downloads.length = 0;
		const store = getProfileStore();
		await store.ensureLoaded();
		store.clearAllCustom();
		await importProfileFiles([new File([fixture('android-profiles.json')], 'android-profiles.json')], (p) =>
			store.save(p)
		);
	});

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	async function mountPage(): Promise<HTMLElement> {
		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(WithAppContext, { target, props: { app: null, component: ProfilesPage } });
		flushSync();
		await tick();
		return target;
	}

	it("a card's Download is the core's .json for that profile", async () => {
		const page = await mountPage();
		const card = [...page.querySelectorAll('.pp-card, article, li, div')].find(
			(el) => el.querySelector('button[title^="Download as community v2"]') && el.textContent?.includes('My 7g')
				&& !el.textContent?.includes('7g basket')
		);
		expect(card, 'the "My 7g" card').toBeDefined();
		(card!.querySelector('button[title^="Download as community v2"]') as HTMLButtonElement).click();
		expect(downloads.map((d) => d.name)).toEqual(['My_7g.json']);
		expect(await downloads[0].blob.text()).toBe(fixture('crema-profile.json'));
	});

	it("Export is the core's .jsonl of the whole library", async () => {
		const page = await mountPage();
		const button = page.querySelector('button[title^="Download every profile"]') as HTMLButtonElement;
		expect(button, 'the Export button').not.toBeNull();
		button.click();
		expect(downloads.map((d) => d.name)).toEqual(['crema-profiles-stamp.jsonl']);
		const all = getProfileStore().all;
		expect(await downloads[0].blob.text()).toBe(coreExportProfileFile(JSON.stringify(all)));
		expect((await downloads[0].blob.text()).split('\n').filter(Boolean)).toHaveLength(all.length);
	});
	it('F12b Import skips what the library has, hidden built-ins included, and says so', async () => {
		const store = getProfileStore();
		// A built-in without a custom copy (the setup imported three).
		const customNames = new Set(store.all.filter((p) => p.source === 'custom').map((p) => p.name));
		const builtin = store.all.find((p) => p.source === 'builtin' && !customNames.has(p.name))!;
		const builtinFile = new File([coreExportProfileFile(JSON.stringify([builtin]))], 'b.json');
		store.delete(builtin.id);
		try {
			const page = await mountPage();
			const input = page.querySelector('input[type="file"]') as HTMLInputElement;
			const files = [builtinFile, new File([fixture('android-profile.json')], 'android-profile.json')];
			Object.defineProperty(input, 'files', { configurable: true, value: files });
			input.dispatchEvent(new Event('change', { bubbles: true }));
			await vi.waitFor(() => expect(page.textContent).toContain('already in your library'));
			expect(page.textContent).toContain('Imported 0 profiles; 2 already in your library.');
		} finally {
			store.unhideBuiltin(builtin.id);
		}
	});
});
