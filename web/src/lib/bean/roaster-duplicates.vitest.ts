/**
 * `#lib/bean/roaster-duplicates.vitest.js` — the wasm-backed duplicate detection +
 * merge plan the Roasters tab runs (the rules are pinned in the core tests;
 * this proves the bridge and the camelCase shapes). Run: `pnpm test:vitest`.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { blankBean, blankRoaster, type Roaster } from './model.ts';
import { detectRoasterDuplicates, planRoasterMerge } from './roaster-duplicates.ts';

beforeAll(async () => {
	await initTestWasm();
});

function roaster(name: string, updatedAt: number): Roaster {
	return { ...blankRoaster(name), updatedAt };
}

describe('detectRoasterDuplicates', () => {
	it('pairs same-named rows, newest as canonical, skipping tagged rows', () => {
		const a = roaster('Sey', 10);
		const b = roaster(' sey ', 30);
		const tagged = { ...roaster('SEY', 50), canonicalRoasterId: a.id };
		const other = roaster('Onyx', 1);
		expect(detectRoasterDuplicates([a, b, tagged, other])).toEqual([
			{ canonicalId: b.id, dupeId: a.id }
		]);
	});
});

describe('planRoasterMerge', () => {
	it("lists the dupe's bags and refuses a self-merge", () => {
		const keep = roaster('Sey', 10);
		const dupe = roaster('sey', 5);
		const bag = { ...blankBean('bean:1'), roasterId: dupe.id };
		const other = { ...blankBean('bean:2'), roasterId: keep.id };
		expect(planRoasterMerge([keep, dupe], [bag, other], keep.id, dupe.id)).toEqual({
			canonicalId: keep.id,
			dupeId: dupe.id,
			beanIds: ['bean:1']
		});
		expect(planRoasterMerge([keep, dupe], [bag], keep.id, keep.id)).toBeNull();
	});
});

describe('planRoasterDelete', () => {
	it('detach keeps bags; cascade deletes them and lists their Visualizer ids', async () => {
		const { planRoasterDelete } = await import('./roaster-delete.ts');
		const r = { ...roaster('Sey', 1), visualizerId: 'vz-r' };
		const synced = { ...blankBean('bean:a'), roasterId: r.id, visualizerId: 'vz-a' };
		const local = { ...blankBean('bean:b'), roasterId: r.id };
		const detach = planRoasterDelete([r], [synced, local], r.id, false);
		expect(detach?.detachedBeanIds).toEqual(['bean:a', 'bean:b']);
		expect(detach?.remoteBeanIds).toEqual([]);
		expect(detach?.remoteRoasterId).toBe('vz-r');
		const cascade = planRoasterDelete([r], [synced, local], r.id, true);
		expect(cascade?.deletedBeanIds).toEqual(['bean:a', 'bean:b']);
		expect(cascade?.remoteBeanIds).toEqual(['vz-a']);
		expect(planRoasterDelete([r], [], 'roaster:none', true)).toBeNull();
	});
});

describe('deleting a roaster with its bags (REVIEW-CORE M3)', () => {
	it('clears the active bag when the cascade deletes it, and never makes another bag active', async () => {
		const { getBeanStore } = await import('./store.svelte');
		const library = getBeanStore();
		localStorage.clear();
		library.clearAll();
		const r = { ...roaster('Sey', 1), visualizerId: 'vz-r' };
		library.upsertRoaster(r);
		library.upsertBean({ ...blankBean('bean:a'), roasterId: r.id, visualizerId: 'vz-a' });
		library.upsertBean({ ...blankBean('bean:other') });
		library.setActiveBean('bean:a');

		const plan = library.deleteRoasterAndBeans(r.id, { localOnly: true });
		expect(plan?.deletedBeanIds).toEqual(['bean:a']);
		expect(plan?.remoteBeanIds, 'the remote DELETEs come from the same plan').toEqual(['vz-a']);
		expect(plan?.remoteRoasterId).toBe('vz-r');
		expect(plan?.activeBeanIdAfter ?? null).toBeNull();
		expect(library.activeBeanId, 'Brew shows "No bag"').toBeNull();
		expect(library.beans.map((b) => b.id)).toEqual(['bean:other']);
	});

	it('keeps the active bag when it is another roaster\'s', async () => {
		const { getBeanStore } = await import('./store.svelte');
		const library = getBeanStore();
		localStorage.clear();
		library.clearAll();
		const r = roaster('Sey', 1);
		library.upsertRoaster(r);
		library.upsertBean({ ...blankBean('bean:a'), roasterId: r.id });
		library.upsertBean({ ...blankBean('bean:other') });
		library.setActiveBean('bean:other');

		const plan = library.deleteRoasterAndBeans(r.id, { localOnly: true });
		expect(plan?.activeBeanIdAfter).toBe('bean:other');
		expect(library.activeBeanId).toBe('bean:other');
	});
});
