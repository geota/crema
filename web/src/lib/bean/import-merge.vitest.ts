/**
 * `#lib/bean/import-merge.vitest` — F11c: the core's bean-import merge rule
 * through the wasm bridge (the rule itself is pinned by the core tests in
 * `bean_import.rs`; Android's `BeanInterchangeTest` runs the same cases).
 */

import { beforeAll, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { blankBean, type Bean } from './model.ts';
import { mergeBeanImport } from './import-merge.ts';

beforeAll(async () => {
	await initTestWasm();
});

const bag = (updatedAt: number, extra: Partial<Bean> = {}): Bean => ({
	...blankBean('bean:1'),
	name: 'Gesha',
	updatedAt,
	...extra
});

describe('mergeBeanImport (core merge_bean_import)', () => {
	it('a newer incoming bean replaces the library row with the same id', () => {
		const incoming = bag(200, { name: 'Gesha (new bag)' });
		const merge = mergeBeanImport([incoming], [], [bag(100)], []);
		expect(merge.updatedBeans.map((b) => b.name)).toEqual(['Gesha (new bag)']);
		expect(merge.newBeans).toEqual([]);
		expect(merge.beanIdRemap).toEqual({ 'bean:1': 'bean:1' });
	});

	it('an older or tied incoming bean keeps the library row', () => {
		for (const stamp of [100, 200]) {
			const merge = mergeBeanImport([bag(stamp)], [], [bag(200, { name: 'Edited here' })], []);
			expect(merge.updatedBeans).toEqual([]);
			expect(merge.newBeans).toEqual([]);
			expect(merge.skipped).toBe(1);
		}
	});

	it('an older incoming bean does not resurrect a local tombstone', () => {
		const deleted = bag(100, { deletedAt: 300 });
		expect(mergeBeanImport([bag(250)], [], [deleted], []).updatedBeans).toEqual([]);
		expect(mergeBeanImport([bag(400)], [], [deleted], []).updatedBeans).toHaveLength(1);
	});

	it('a bean the library does not have is new', () => {
		const merge = mergeBeanImport([bag(1, { id: 'bean:2' })], [], [bag(1)], []);
		expect(merge.newBeans.map((b) => b.id)).toEqual(['bean:2']);
	});
});
