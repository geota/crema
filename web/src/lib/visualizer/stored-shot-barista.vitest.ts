import { describe, expect, it } from 'vitest';
import { storedShotFromWire, type WireShot } from './shot-sync-signatures.js';

const wire = (over: Partial<WireShot> = {}): WireShot => ({
	id: 'r-1',
	clock: 1_700_000_000_000,
	duration_ms: 30_000,
	profile_title: 'p',
	final_weight_g: 36,
	notes: null,
	rating: null,
	updated_at_ms: null,
	tag_list: [],
	...over
});

describe('storedShotFromWire barista (final review 06 #1)', () => {
	it('a pulled shot carries its Visualizer barista', () => {
		expect(storedShotFromWire(wire({ barista: ' Ana ' })).metadata.barista).toBe('Ana');
	});

	it('a remote without one leaves the field unset', () => {
		expect(storedShotFromWire(wire()).metadata).not.toHaveProperty('barista');
		expect(storedShotFromWire(wire({ barista: '  ' })).metadata).not.toHaveProperty('barista');
	});
});

describe('storedShotFromWire grind speed (review #554 M1)', () => {
	it('a pulled "480 700rpm" stays whole and carries the speed', () => {
		const s = storedShotFromWire(
			wire({ bean: { bean_type: 'Guji', grinder_setting: '480 700rpm', grind_rpm: 700 } })
		);
		expect(s.bean?.grinderSetting).toBe('480 700rpm');
		expect(s.grindRpm).toBe(700);
	});

	it('a grind with no speed adds no field', () => {
		const s = storedShotFromWire(wire({ bean: { bean_type: 'Guji', grinder_setting: '18 clicks' } }));
		expect(s.bean?.grinderSetting).toBe('18 clicks');
		expect(s).not.toHaveProperty('grindRpm');
	});
});
