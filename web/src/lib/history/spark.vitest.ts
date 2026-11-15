/**
 * #310 P4w: the row chart reads the core's spark (`shot_summary`) back, with
 * no samples and no core call. The two sparks pinned by the core's golden
 * test (`core/de1-app/tests/shot_summary_golden.rs`) read back as the core
 * reads them; a shot's own summary reads back; anything else is no spark.
 */
import { beforeAll, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { decodeSpark, SPARK_POINTS } from './spark';
import { summarize } from './summary';
import type { StoredShot } from './model';

/** The core's golden sparks, byte for byte. */
const GOLDEN_NO_SCALE =
	'AQEABRIvkJqZmJiYmJiYlJGPjYyKiIaFg4F/fXt6eHZ1c3HO/vtyHRUXGx4iJikqLTAyMzQ0NTc2ODg5Pj8/Pjs2Nv//////////////////////////////////////////';
const GOLDEN_SCALE =
	'AQMABRIvkJqZmJiYmJiYlJGPjYyKiIaFg4F/fXt6eHZ1c3HO/vtyHRUXGx4iJikqLTAyMzQ0NTc2ODg5Pj8/Pjs2Nv8NKUJKTVBTV1xgZmxyeH+GjZSbo6qyucHJ0trj6/P7';

beforeAll(async () => {
	await initTestWasm();
});

describe('decodeSpark', () => {
	it('reads the core’s no-scale golden spark as the core does', () => {
		const s = decodeSpark(GOLDEN_NO_SCALE)!;
		expect(s).toMatchObject({ machine: true, brew: false, hasWeight: false, ticks: [] });
		expect(s.pressure).toHaveLength(SPARK_POINTS);
		expect(s.flow).toHaveLength(SPARK_POINTS);
		expect(s.weight.every((v) => v === null)).toBe(true);
		expect(s.pressure.some((v) => v !== null && v * 254 > 100)).toBe(true);
		// Bytes 3..5 of the golden: 0x05, 0x12, 0x2f.
		expect(s.pressure.slice(0, 3).map((v) => Math.round(v! * 254))).toEqual([5, 18, 47]);
	});

	it('reads the scale golden: a gap before the first reading, then a cup that only fills to its peak', () => {
		const s = decodeSpark(GOLDEN_SCALE)!;
		expect(s).toMatchObject({ machine: true, hasWeight: true });
		expect(s.weight[0]).toBeNull();
		const drawn = s.weight.filter((v): v is number => v !== null);
		expect(drawn.every((v, i) => i === 0 || drawn[i - 1] <= v)).toBe(true);
		expect(s.weight[SPARK_POINTS - 1]! * 254).toBeGreaterThanOrEqual(245);
	});

	it('reads a shot’s own summary, and a brew’s ticks', () => {
		const machine = decodeSpark(summarize(realShot(1, realSamples(180))).spark)!;
		expect(machine.machine).toBe(true);
		const brew: StoredShot = {
			...realShot(2, []),
			brewMethod: 'pourover',
			brewSeries: {
				samples: Array.from({ length: 40 }, (_, k) => ({ elapsedMs: k * 5_000, weightG: k * 6, flowGS: 1.2 })),
				stageMarks: [
					{ elapsedMs: 0, stepIndex: 0 },
					{ elapsedMs: 50_000, stepIndex: 1 },
					{ elapsedMs: 120_000, stepIndex: 2 }
				]
			}
		} as StoredShot;
		const b = decodeSpark(summarize(brew).spark)!;
		expect(b).toMatchObject({ brew: true, machine: false, hasWeight: true });
		expect(b.pressure.every((v) => v === null)).toBe(true);
		expect(b.ticks).toHaveLength(3);
		expect(b.ticks[0]).toBe(0);
		expect(b.ticks[1]).toBeCloseTo(50 / 195, 1);
	});

	it('is null for nothing, a wrong version, a cut-short spark, or text that isn’t base64', () => {
		expect(decodeSpark(null)).toBeNull();
		expect(decodeSpark(undefined)).toBeNull();
		expect(decodeSpark('')).toBeNull();
		expect(decodeSpark('Ag' + GOLDEN_NO_SCALE.slice(2))).toBeNull();
		expect(decodeSpark(GOLDEN_NO_SCALE.slice(0, 40))).toBeNull();
		expect(decodeSpark('not base64 at all!')).toBeNull();
	});
});
