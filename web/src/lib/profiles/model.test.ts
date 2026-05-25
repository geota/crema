/**
 * `$lib/profiles/model.test` — node:test suite for the profile-id helpers.
 *
 * The web shell does not (yet) wire vitest into the build — these tests
 * are runnable with the platform's built-in test runner:
 *
 * ```
 * cd web && node \
 *   --experimental-strip-types \
 *   --experimental-detect-module \
 *   --test src/lib/profiles/model.test.ts
 * ```
 *
 * They lock in the contract behind {@link uuidFromString} (deterministic,
 * collision-resistant, well-formed v4-shape) and the bare-UUID id shape
 * built-ins now adopt via {@link fromCoreProfile}.
 */

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
	fromCoreProfile,
	newProfileUuid,
	uuidFromString
} from './model.ts';
import type { Profile } from './core-types.ts';

/** The canonical UUID v4 form (8-4-4-4-12 hex, version 4, variant 10). */
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('uuidFromString', () => {
	it('returns a well-formed v4-shaped UUID', () => {
		const id = uuidFromString('Gagné/Adaptive Shot 92C v1.0');
		assert.match(id, UUID_V4);
	});

	it('is deterministic — same input → same UUID', () => {
		const a = uuidFromString('Best Latte');
		const b = uuidFromString('Best Latte');
		assert.equal(a, b);
	});

	it('produces different UUIDs for different titles', () => {
		const a = uuidFromString('TurboBloom');
		const b = uuidFromString('TurboTurbo');
		assert.notEqual(a, b);
	});

	it('avoids collisions across a representative set of titles', () => {
		const titles = [
			'',
			'Gagné/Adaptive Shot 92C v1.0',
			'Easy blooming - active pressure decline',
			'Adaptive v2',
			'I got your back',
			'TurboBloom',
			'TurboTurbo',
			'Extractamundo Dos!',
			'default-dark',
			'default-medium',
			'default-light',
			'Tea/Green',
			'Tea/Black',
			'Tea portafilter/Green',
			'Cleaning/Backflush',
			'Test/Leak',
			'Steam only'
		];
		const seen = new Set<string>();
		for (const t of titles) seen.add(uuidFromString(t));
		assert.equal(seen.size, titles.length);
	});
});

describe('newProfileUuid', () => {
	it('returns a well-formed v4 UUID', () => {
		assert.match(newProfileUuid(), UUID_V4);
	});

	it('produces unique values across many draws', () => {
		const seen = new Set<string>();
		for (let i = 0; i < 100; i++) seen.add(newProfileUuid());
		assert.equal(seen.size, 100);
	});
});

describe('fromCoreProfile id assignment', () => {
	/** Minimal `Profile` stub — only the fields the adapter actually reads. */
	function stub(title: string): Profile {
		return {
			title,
			notes: '',
			steps: [],
			preinfuse_step_count: 0,
			minimum_pressure: 0,
			maximum_flow: 0,
			max_total_volume_ml: 0,
			target_weight: 0,
			dose: 0,
			author: '',
			beverage_type: 'espresso',
			tank_temperature: 0,
			version: '2'
		};
	}

	it('built-in id is the deterministic UUID of the title', () => {
		const a = fromCoreProfile(stub('Best Latte'), 0);
		const b = fromCoreProfile(stub('Best Latte'), 42);
		assert.match(a.id, UUID_V4);
		// Same title → same id even if the index shifts (the whole point —
		// reorderings in `builtin.json` no longer shuffle ids).
		assert.equal(a.id, b.id);
		assert.equal(a.id, uuidFromString('Best Latte'));
	});

	it('different titles produce different ids', () => {
		const a = fromCoreProfile(stub('Gagné/Adaptive Shot 92C v1.0'), 0);
		const b = fromCoreProfile(stub('TurboBloom'), 1);
		assert.notEqual(a.id, b.id);
	});
});
