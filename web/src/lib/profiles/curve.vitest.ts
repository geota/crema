/**
 * `$lib/profiles/curve.vitest` — unit coverage for `sampleCurve`, the
 * segment-list → uPlot column sampler (GEN11). Pure math, no wasm.
 *
 * Pins the GEN9 empty-segments guard (an empty profile must yield empty columns,
 * not a lone `(0,0)` seed point that uPlot auto-ranges into a misleading x-span)
 * plus the fast-step / smooth-ramp shapes and the strictly-increasing time axis
 * uPlot's line path depends on. Run: `pnpm test:vitest`.
 */

import { describe, expect, it } from 'vitest';
import { sampleCurve } from './curve.ts';
import type { ProfileSegment } from './model.ts';

/** A minimal segment — `sampleCurve` only reads `ramp` / `target` / `time`. */
const seg = (ramp: 'fast' | 'smooth', target: number, time: number): ProfileSegment =>
	({ ramp, target, time }) as unknown as ProfileSegment;

const strictlyIncreasing = (xs: readonly number[]): boolean =>
	xs.every((x, i) => i === 0 || x > xs[i - 1]);

describe('sampleCurve', () => {
	it('returns empty columns for an empty segment list (GEN9)', () => {
		expect(sampleCurve([])).toEqual({ time: [], value: [] });
	});

	it('emits a near-vertical step for a fast ramp', () => {
		const { time, value } = sampleCurve([seg('fast', 6, 10)]);
		// Seed (0,0), the stepped jump just after t=0, then the held value at t=10.
		expect(time).toEqual([0, expect.closeTo(0, 2), 10]);
		expect(value).toEqual([0, 6, 6]);
		expect(strictlyIncreasing(time)).toBe(true);
	});

	it('emits a dense eased path for a smooth ramp ending on target', () => {
		const { time, value } = sampleCurve([seg('smooth', 8, 5)]);
		expect(time).toHaveLength(25); // seed + SMOOTH_STEPS(24)
		expect(value).toHaveLength(25);
		expect(time[0]).toBe(0);
		expect(time.at(-1)).toBeCloseTo(5, 6);
		expect(value.at(-1)).toBeCloseTo(8, 6);
		expect(strictlyIncreasing(time)).toBe(true);
	});

	it('applies the damp transform to every target before sampling', () => {
		const { value } = sampleCurve([seg('fast', 10, 5)], (t) => t / 2);
		expect(value.at(-1)).toBe(5); // 10 damped to 5
	});

	it('keeps the time axis strictly increasing across mixed segments', () => {
		const { time } = sampleCurve([seg('fast', 6, 4), seg('smooth', 2, 6), seg('fast', 9, 3)]);
		expect(strictlyIncreasing(time)).toBe(true);
		expect(time.at(-1)).toBeCloseTo(13, 6); // 4 + 6 + 3
	});
});
