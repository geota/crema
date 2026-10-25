/**
 * History search + "same coffee" grouping (dye2 audit #8, #9): the text
 * search reaches the bean, roaster, grinder and people fields, and every
 * bag of one coffee shares a key.
 */
import { describe, expect, it } from 'vitest';
import type { StoredShot } from './model';
import { coffeeKey, recentNames, shotCoffee, shotMatchesQuery } from './search';

function shot(over: Partial<StoredShot> = {}): StoredShot {
	return {
		formatVersion: 3,
		id: over.id ?? 'shot:1',
		completedAt: 1_700_000_000_000,
		profileName: 'Adaptive v3',
		metadata: {},
		record: { duration: 27_000, samples: [] },
		...over
	};
}

describe('shotMatchesQuery', () => {
	const s = shot({
		bean: { beanId: 'b1', name: 'Kenya AA', roasterName: 'Sey', grinder: 'Niche Zero' },
		grinderModel: 'EG-1',
		metadata: { barista: 'Ana', drinker: 'Ben', notes: 'syrupy' }
	});

	it('matches the bean name, roaster and grinders', () => {
		expect(shotMatchesQuery(s, 'kenya')).toBe(true);
		expect(shotMatchesQuery(s, 'SEY')).toBe(true);
		expect(shotMatchesQuery(s, 'niche')).toBe(true);
		expect(shotMatchesQuery(s, 'eg-1')).toBe(true);
	});

	it('matches barista and drinker', () => {
		expect(shotMatchesQuery(s, 'ana')).toBe(true);
		expect(shotMatchesQuery(s, ' ben ')).toBe(true);
	});

	it('still matches the profile and notes, and an empty query matches all', () => {
		expect(shotMatchesQuery(s, 'adaptive')).toBe(true);
		expect(shotMatchesQuery(s, 'syrup')).toBe(true);
		expect(shotMatchesQuery(s, '')).toBe(true);
		expect(shotMatchesQuery(s, 'ethiopia')).toBe(false);
	});

	it('matches the legacy flat bean label on rows without a snapshot', () => {
		expect(shotMatchesQuery(shot({ metadata: { beans: 'Onyx · Geometry' } }), 'geometry')).toBe(true);
	});
});

describe('coffeeKey', () => {
	it('folds case and whitespace and keeps roasters apart', () => {
		expect(coffeeKey('  Kenya   AA ', 'Sey')).toBe(coffeeKey('kenya aa', ' sey'));
		expect(coffeeKey('Kenya AA', 'Sey')).not.toBe(coffeeKey('Kenya AA', 'Onyx'));
		expect(coffeeKey('', 'Sey')).toBeNull();
	});
});

describe('shotCoffee', () => {
	const none = () => null;

	it('groups two bags of one coffee under one key', () => {
		const a = shot({ id: 'a', bean: { beanId: 'bag-1', name: 'Kenya AA', roasterName: 'Sey' } });
		const b = shot({ id: 'b', bean: { beanId: 'bag-2', name: 'kenya aa', roasterName: 'SEY' } });
		expect(shotCoffee(a, none)?.key).toBe(shotCoffee(b, none)?.key);
	});

	it('prefers the live bag, so a renamed bag keeps its older shots', () => {
		const old = shot({ bean: { beanId: 'bag-1', name: 'Kenia', roasterName: 'Sey' } });
		const live = (id: string) => (id === 'bag-1' ? { name: 'Kenya AA', roaster: 'Sey' } : null);
		expect(shotCoffee(old, live)).toEqual({ key: 'sey|kenya aa', name: 'Kenya AA', roaster: 'Sey' });
	});

	it('is null for a beanless shot', () => {
		expect(shotCoffee(shot(), none)).toBeNull();
	});
});

describe('recentNames', () => {
	it('lists names newest first, once each', () => {
		const shots = [
			shot({ id: '1', completedAt: 1, metadata: { barista: 'Ana' } }),
			shot({ id: '2', completedAt: 3, metadata: { barista: 'ana ' } }),
			shot({ id: '3', completedAt: 2, metadata: { barista: 'Ben' } }),
			shot({ id: '4', completedAt: 4, metadata: { drinker: 'Cy' } })
		];
		expect(recentNames(shots, 'barista')).toEqual(['ana', 'Ben']);
		expect(recentNames(shots, 'drinker')).toEqual(['Cy']);
	});
});
