/**
 * `#lib/bean/model.vitest` — defensive-coercion coverage for `coerceBean` /
 * `coerceRoaster` (GEN11). These normalise untrusted localStorage rows, so a
 * stored-shape regression must never crash a load. Also the baseline that pins
 * behaviour before CORE2 moves this logic to Rust serde. Run: `pnpm test:vitest`.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import {
	beanDaysOffRoast,
	blankBean,
	coerceBean,
	coerceRoaster,
	duplicateBean,
	setBeanFrozen,
	type Bean
} from './model.ts';

beforeAll(async () => {
	await initTestWasm();
});

describe('coerceBean', () => {
	it('rejects non-objects and rows missing id / name', () => {
		expect(coerceBean(null)).toBeNull();
		expect(coerceBean(42)).toBeNull();
		expect(coerceBean({ name: 'x' })).toBeNull(); // no id
		expect(coerceBean({ id: 'b1' })).toBeNull(); // no name
	});

	it('fills defaults for a minimal valid row', () => {
		const bean = coerceBean({ id: 'b1', name: 'House' });
		expect(bean).not.toBeNull();
		expect(bean!.id).toBe('b1');
		expect(bean!.name).toBe('House');
		expect(bean!.bagSize).toBe(0);
		expect(bean!.remaining).toBe(0);
		expect(bean!.tags).toEqual([]);
		expect(bean!.mix).toBeNull();
	});

	it('migrates the legacy bagSizeG / remainingG keys', () => {
		const bean = coerceBean({ id: 'b1', name: 'House', bagSizeG: 340, remainingG: 200 });
		expect(bean!.bagSize).toBe(340);
		expect(bean!.remaining).toBe(200);
	});

	it('drops non-string tags and ignores bad enum / cost values', () => {
		const bean = coerceBean({
			id: 'b1',
			name: 'House',
			tags: ['a', 7, null, 'b'],
			mix: 'nonsense',
			cost: -5
		});
		expect(bean!.tags).toEqual(['a', 'b']);
		expect(bean!.mix).toBeNull();
		expect(bean!.cost).toBeNull();
	});

	it('reads a nested origin defensively', () => {
		const bean = coerceBean({
			id: 'b1',
			name: 'House',
			origin: { country: 'Kenya', region: 12, farm: 'Gatomboya' }
		});
		expect(bean!.origin.country).toBe('Kenya');
		expect(bean!.origin.farm).toBe('Gatomboya');
		expect(bean!.origin.region).toBeNull(); // non-string dropped
	});
});

describe('coerceRoaster', () => {
	it('rejects garbage and rows missing id / name', () => {
		expect(coerceRoaster(null)).toBeNull();
		expect(coerceRoaster({ name: 'x' })).toBeNull();
		expect(coerceRoaster({ id: 'r1' })).toBeNull();
	});

	it('reads the modelled fields and fills defaults', () => {
		const roaster = coerceRoaster({
			id: 'r1',
			name: 'Onyx',
			website: 'https://onyx.coffee',
			visualizerId: 'rv-1',
			notes: 7 // non-string ignored
		});
		expect(roaster!.id).toBe('r1');
		expect(roaster!.name).toBe('Onyx');
		expect(roaster!.website).toBe('https://onyx.coffee');
		expect(roaster!.visualizerId).toBe('rv-1');
		expect(roaster!.notes).toBe('');
	});
});

describe('duplicateBean (core duplicate_bean)', () => {
	it('copies the details and drops every remote link, photo ref, tombstone, archive and pin', () => {
		const src: Bean = {
			...blankBean('bean:orig'),
			name: 'Gesha',
			roasterId: 'roaster:1',
			roastedOn: '2026-09-01',
			tags: ['daily'],
			favourite: true,
			archivedAt: 10,
			deletedAt: 20,
			visualizerId: 'vb-orig',
			beanconquerorId: 'bc-orig',
			canonicalCoffeeBagId: 'cat-bag-1',
			imageRef: 'bean-image:bean:orig',
			metadata: { co2eKg: 1.4 }
		};
		const copy = duplicateBean(src, 'bean:copy', 1_000);
		expect(copy).toEqual({
			...src,
			id: 'bean:copy',
			name: 'Gesha (copy)',
			favourite: false,
			archivedAt: null,
			deletedAt: null,
			visualizerId: null,
			beanconquerorId: null,
			imageRef: null,
			createdAt: 1_000,
			updatedAt: 1_000
		});
		expect(src.visualizerId).toBe('vb-orig');
	});

	it('names a blank bag and mints a bean id by default', () => {
		const copy = duplicateBean(blankBean('bean:x'));
		expect(copy.name).toBe('Untitled bag (copy)');
		expect(copy.id).toMatch(/^bean:/);
		expect(copy.id).not.toBe('bean:x');
	});
});

describe('setBeanFrozen (core set_bean_frozen)', () => {
	// 2026-05-22 12:00 UTC.
	const now = Date.UTC(2026, 4, 22, 12);

	it('switching Frozen off defrosts today, keeps the freeze date and the count', () => {
		const bag: Bean = {
			...blankBean('bean:f'),
			roastedOn: '2026-04-24',
			frozenOn: '2026-05-07'
		};
		const off = setBeanFrozen(bag, false, now);
		expect(off).toEqual({ ...bag, defrostedOn: '2026-05-22' });
		expect(beanDaysOffRoast(off, now)).toBe(13);
		expect(beanDaysOffRoast(bag, now)).toBe(13);
	});

	it('switching Frozen on stamps today, or carries an earlier freeze', () => {
		const plain: Bean = { ...blankBean('bean:p'), roastedOn: '2026-05-12' };
		expect(setBeanFrozen(plain, true, now)).toEqual({ ...plain, frozenOn: '2026-05-22' });
		const thawed: Bean = {
			...plain,
			roastedOn: '2026-04-24',
			frozenOn: '2026-05-07',
			defrostedOn: '2026-05-12'
		};
		const on = setBeanFrozen(thawed, true, now);
		expect(on.frozenOn).toBe('2026-05-17');
		expect(on.defrostedOn).toBeNull();
		expect(beanDaysOffRoast(on, now)).toBe(beanDaysOffRoast(thawed, now));
	});
});
