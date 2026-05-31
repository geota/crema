/**
 * `$lib/bean/model.vitest` — defensive-coercion coverage for `coerceBean` /
 * `coerceRoaster` (GEN11). These normalise untrusted localStorage rows, so a
 * stored-shape regression must never crash a load. Also the baseline that pins
 * behaviour before CORE2 moves this logic to Rust serde. Run: `pnpm test:vitest`.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import { initTestWasm } from '$lib/wasm/test-init';
import { coerceBean, coerceRoaster } from './model.ts';

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
