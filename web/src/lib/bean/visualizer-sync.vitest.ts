/**
 * `$lib/bean/visualizer-sync.vitest` — round-trip coverage for the Crema⇄Visualizer
 * bean / roaster wire converters + the roast-level banding (GEN11; the
 * roast-level half is the GEN1 regression guard).
 *
 * The converters are the highest cross-shell-drift risk (CORE1 moves them to
 * Rust), so we pin: (a) the roast-level mapping is lossy-by-design but
 * idempotent after the first hop, and (b) a Crema bean / roaster survives an
 * encode→decode through the `metadata.crema` escape valve. Run: `pnpm test:vitest`.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import { initTestWasm } from '$lib/testing/test-init';
import { blankBean, blankRoaster } from './model.ts';
import {
	beanFromWire,
	beanToWire,
	roastLevelFromWire,
	roastLevelToWire,
	coffeeBagWriteRequest,
	roasterWriteRequest,
	roasterFromWire,
	roasterToWire
} from './visualizer-sync.ts';

beforeAll(async () => {
	// `blankRoaster` (and `blankBean` without an id) mint ids via wasm.
	await initTestWasm();
});

describe('roastLevel banding (GEN1)', () => {
	const IN_BAND_REPS = new Set([2, 3, 5, 7, 9]);

	it('lands every 1..10 level on an in-band representative', () => {
		for (let lvl = 1; lvl <= 10; lvl++) {
			const back = roastLevelFromWire(roastLevelToWire(lvl));
			expect(IN_BAND_REPS.has(back as number)).toBe(true);
		}
	});

	it('is idempotent after the first hop (no further drift)', () => {
		for (let lvl = 1; lvl <= 10; lvl++) {
			const once = roastLevelFromWire(roastLevelToWire(lvl));
			const twice = roastLevelFromWire(roastLevelToWire(once));
			expect(twice).toBe(once);
		}
	});

	it('maps the canonical band labels (Medium-Dark → 7, not 6)', () => {
		expect(roastLevelFromWire('Light')).toBe(2);
		expect(roastLevelFromWire('Medium-Light')).toBe(3);
		expect(roastLevelFromWire('Medium')).toBe(5);
		expect(roastLevelFromWire('Medium-Dark')).toBe(7);
		expect(roastLevelFromWire('Dark')).toBe(9);
	});

	it('returns null for unset / unrecognised input', () => {
		expect(roastLevelToWire(null)).toBeNull();
		expect(roastLevelFromWire(null)).toBeNull();
		expect(roastLevelFromWire('')).toBeNull();
		expect(roastLevelFromWire('chartreuse')).toBeNull();
	});
});

describe('bean wire round-trip', () => {
	it('preserves Crema fields through metadata.crema', () => {
		const bean = blankBean('bean-1');
		bean.name = 'Yirgacheffe';
		bean.roasterId = 'r-local';
		bean.roastedOn = '2026-05-01';
		bean.roastLevel = 5; // a band representative, so it survives losslessly
		bean.mix = 'single';
		bean.decaf = true;
		bean.favourite = true;
		bean.bagSize = 250;
		bean.remaining = 137;
		bean.grinder = 'Niche Zero';
		bean.grinderSetting = '18';
		bean.origin = { ...bean.origin, country: 'Ethiopia', variety: 'Heirloom' };
		bean.notes = 'floral';
		bean.beanconquerorId = 'bc-9';
		bean.metadata = { custom: 'keep-me' };

		const wire = beanToWire(bean, 'rv-1');
		const back = beanFromWire(wire, (rid) => (rid === 'rv-1' ? 'r-local' : null));

		expect(back.name).toBe('Yirgacheffe');
		expect(back.roasterId).toBe('r-local');
		expect(back.roastedOn).toBe('2026-05-01');
		expect(back.roastLevel).toBe(5);
		expect(back.mix).toBe('single');
		expect(back.decaf).toBe(true);
		expect(back.favourite).toBe(true);
		expect(back.bagSize).toBe(250);
		expect(back.remaining).toBe(137);
		expect(back.grinder).toBe('Niche Zero');
		expect(back.grinderSetting).toBe('18');
		expect(back.origin.country).toBe('Ethiopia');
		expect(back.origin.variety).toBe('Heirloom');
		expect(back.notes).toBe('floral');
		expect(back.beanconquerorId).toBe('bc-9');
		// The user metadata blob round-trips; the Crema-only `crema` block is stripped.
		expect(back.metadata).toEqual({ custom: 'keep-me' });
	});
});

describe('roaster wire round-trip', () => {
	it('preserves the Visualizer-modelled fields', () => {
		const roaster = blankRoaster('Onyx');
		roaster.visualizerId = 'rv-7';
		roaster.website = 'https://onyx.coffee';
		roaster.imageUrl = 'https://onyx.coffee/logo.png';
		roaster.catalogueRoasterId = 'canon-3';

		const back = roasterFromWire(roasterToWire(roaster));

		expect(back.name).toBe('Onyx');
		expect(back.visualizerId).toBe('rv-7');
		expect(back.website).toBe('https://onyx.coffee');
		expect(back.imageUrl).toBe('https://onyx.coffee/logo.png');
		expect(back.catalogueRoasterId).toBe('canon-3');
		expect(back.canonicalRoasterId).toBeNull();
	});

	it('keeps the local dedup pointer off the wire', () => {
		const roaster = blankRoaster('Onyx');
		roaster.canonicalRoasterId = 'roaster:other';
		expect(roasterToWire(roaster).canonical_roaster_id).toBeNull();
	});
});

describe('catalogue links in the write payloads', () => {
	it('sends canonical_coffee_bag_id and the roaster link when the bean has them', () => {
		const bean = blankBean('bean:1');
		bean.name = 'Hambela';
		bean.canonicalCoffeeBagId = 'cb-1';
		bean.canonicalRoasterId = 'cr-1';
		const body = coffeeBagWriteRequest(bean, 'rv-1');
		expect(body.coffee_bag.canonical_coffee_bag_id).toBe('cb-1');
		expect(body.coffee_bag.roaster_id).toBe('rv-1');
		const back = beanFromWire(beanToWire(bean, 'rv-1'), () => null);
		expect(back.canonicalCoffeeBagId).toBe('cb-1');
		expect(back.canonicalRoasterId).toBe('cr-1');

		const roaster = blankRoaster('Onyx');
		roaster.catalogueRoasterId = 'cr-1';
		expect(roasterWriteRequest(roaster).roaster.canonical_roaster_id).toBe(
			'cr-1'
		);
	});

	it('omits the link keys for an unlinked bag / roaster so a PATCH never unlinks', () => {
		const body = coffeeBagWriteRequest(blankBean('bean:2'), null);
		expect('canonical_coffee_bag_id' in body.coffee_bag).toBe(false);
		const rbody = roasterWriteRequest(blankRoaster('Sey'));
		expect('canonical_roaster_id' in rbody.roaster).toBe(false);
	});

	it('never sends the local duplicate-of pointer', () => {
		const roaster = blankRoaster('Sey');
		roaster.canonicalRoasterId = 'roaster:other';
		const body = roasterWriteRequest(roaster);
		expect(JSON.stringify(body)).not.toContain('roaster:other');
		expect(Object.keys(body.roaster).sort()).toEqual(['name', 'website']);
	});

	it('keeps a link set on Visualizer when pulling a bag-list summary', () => {
		const back = beanFromWire(
			{ id: 'vb-1', name: 'Gesha', roaster_id: null, canonical_coffee_bag_id: 'cb-7' },
			() => null
		);
		expect(back.canonicalCoffeeBagId).toBe('cb-7');
	});
});
