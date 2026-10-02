/**
 * `$lib/bean/roaster-duplicates.vitest` — the wasm-backed duplicate detection +
 * merge plan the Roasters tab runs (the rules are pinned in the core tests;
 * this proves the bridge and the camelCase shapes). Run: `pnpm test:vitest`.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import { initTestWasm } from '$lib/testing/test-init';
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
