/**
 * REVIEW-FINAL-2 N3 (bean-profile-pin "Deletion cascade"): the Profiles
 * page's delete confirm says how many bags prefer the profile and that their
 * preference will be cleared, since the delete can't be undone.
 */

import '#lib/testing/dom-stubs.js';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import WithAppContext from '#lib/testing/WithAppContext.svelte';
import { getProfileStore, importProfileFiles } from '#lib/profiles/index.js';
import { bagsPreferring, profileDeleteMessage } from '#lib/profiles/delete-confirm.js';
import { blankBean, getBeanStore } from '#lib/bean/index.js';
import { dismissConfirm, getActiveDialog } from '#lib/components/shared/confirm-dialog.svelte.js';
import ProfilesPage from './+page.svelte';

const FIXTURES = path.resolve(process.cwd(), '../core/de1-domain/tests/fixtures/profiles');
const fixture = (name: string): string => readFileSync(path.resolve(FIXTURES, name), 'utf8');

describe('the profile delete confirm (REVIEW-FINAL-2 N3)', () => {
	let component: ReturnType<typeof mount> | null = null;

	beforeEach(async () => {
		await initTestWasm();
		localStorage.clear();
		getBeanStore().clearAll();
		const store = getProfileStore();
		await store.ensureLoaded();
		store.clearAllCustom();
		await importProfileFiles([new File([fixture('android-profiles.json')], 'android-profiles.json')], (p) =>
			store.save(p)
		);
	});

	afterEach(() => {
		const open = getActiveDialog();
		if (open) dismissConfirm(open.options);
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	/** The "My 7g" custom profile, with `linked` bags preferring it (and one deleted bag that did). */
	function seed(linked: number): string {
		const profile = getProfileStore().all.find((p) => p.source === 'custom' && p.name === 'My 7g')!;
		const beans = getBeanStore();
		for (let i = 0; i < linked; i++) {
			beans.upsertBean({ ...blankBean(`bean:${i}`), name: `Bag ${i}`, linkedProfileId: profile.id });
		}
		beans.upsertBean({ ...blankBean('bean:other'), name: 'Other', linkedProfileId: null });
		beans.upsertBean({ ...blankBean('bean:gone'), name: 'Gone', linkedProfileId: profile.id, deletedAt: 1 });
		return profile.id;
	}

	async function askToDelete(): Promise<string | undefined> {
		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(WithAppContext, { target, props: { app: null, component: ProfilesPage } });
		flushSync();
		await tick();
		const card = [...target.querySelectorAll('div')].find(
			(el) =>
				el.querySelector('button[title="Delete this custom profile"]') &&
				el.textContent?.includes('My 7g') &&
				!el.textContent?.includes('7g basket')
		);
		card!.querySelector<HTMLButtonElement>('button[title="Delete this custom profile"]')!.click();
		await tick();
		return getActiveDialog()?.options.message;
	}

	it('says how many bags prefer it and that their preference will be cleared', async () => {
		const id = seed(2);
		expect(bagsPreferring(getBeanStore().allBeans, id), 'live bags only').toBe(2);
		expect(await askToDelete()).toBe(
			'Delete "My 7g"? 2 beans prefer this profile; their preference will be cleared. This cannot be undone.'
		);
	});

	it('says nothing about bags when none prefers it', async () => {
		seed(0);
		expect(await askToDelete()).toBe('Delete "My 7g"? This cannot be undone.');
	});

	it('one bag reads in the singular', () => {
		expect(profileDeleteMessage('Lever', 1)).toBe(
			'Delete "Lever"? 1 bean prefers this profile; its preference will be cleared. This cannot be undone.'
		);
	});
});
