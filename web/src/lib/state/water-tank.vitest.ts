import { describe, expect, it } from 'vitest';

import {
	applyEvent,
	INITIAL_SNAPSHOT,
	waterRefillSoon,
	waterTankDepthMm,
	waterTankMl,
	waterTankPercent
} from './ui-state.svelte';

/**
 * The +5 mm tank-sensor offset is applied exactly once, in core, when it
 * decodes a `WaterLevels` notification — so `Event.WaterLevel.level` (and
 * `snapshot.waterLevel`) is already a depth. Regression cover for the double
 * offset (core and `water_tank_ml` both adding it), which read ~135 ml high.
 */
describe('water tank readouts', () => {
	it('converts the event depth to ml / % without adding the offset again', () => {
		// Raw 17 mm on the wire → the core emits 22 mm of depth → 592 ml /
		// 54 %, what de1app and Decenza show for that tank.
		expect(waterTankMl(22)).toBe(592);
		expect(waterTankPercent(22)).toBe(54);
		expect(waterTankDepthMm(17)).toBe(22);
	});

	it('keeps the depth on the snapshot as the event carried it', () => {
		const s = applyEvent(INITIAL_SNAPSHOT, {
			type: 'WaterLevel',
			content: { level: 22, refill_threshold: 5 }
		});
		expect(s.waterLevel).toBe(22);
		expect(s.waterRefillThreshold).toBe(5);
		expect(waterTankMl(s.waterLevel)).toBe(592);
	});

	it('compares the level with the raw refill threshold in depth units', () => {
		// Raw threshold 5 → 10 mm of depth; the cue's margin is 5 mm more.
		expect(waterRefillSoon(15, 5)).toBe(true);
		expect(waterRefillSoon(15.5, 5)).toBe(false);
		// A tank sitting exactly at the threshold (raw 5 → depth 10) is low.
		expect(waterRefillSoon(waterTankDepthMm(5), 5)).toBe(true);
		expect(waterRefillSoon(null, 5)).toBe(false);
		expect(waterRefillSoon(10, null)).toBe(false);
	});
});
