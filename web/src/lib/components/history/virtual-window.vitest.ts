/** #310 P5w: the windowed list's arithmetic. */
import { describe, expect, it } from 'vitest';
import { indexAt, offsetsOf, revealDelta, windowOf } from './virtual-window';

describe('virtual-window', () => {
	const heights = [60, 78, 60, 34, 60];
	const offsets = offsetsOf(heights.length, (i) => heights[i]);

	it('sums the heights', () => {
		expect([...offsets]).toEqual([0, 60, 138, 198, 232, 292]);
	});

	it('finds the item at a height, clamped to the list', () => {
		expect(indexAt(offsets, -5)).toBe(0);
		expect(indexAt(offsets, 0)).toBe(0);
		expect(indexAt(offsets, 59.9)).toBe(0);
		expect(indexAt(offsets, 60)).toBe(1);
		expect(indexAt(offsets, 200)).toBe(3);
		expect(indexAt(offsets, 10_000)).toBe(4);
		expect(indexAt(offsetsOf(0, () => 0), 10)).toBe(0);
	});

	it('takes the viewport plus the overscan', () => {
		expect(windowOf(offsets, 100, 50, 0)).toEqual({ start: 1, end: 3 });
		expect(windowOf(offsets, 100, 50, 70)).toEqual({ start: 0, end: 4 });
		expect(windowOf(offsets, 0, 0, 0)).toEqual({ start: 0, end: 1 });
		expect(windowOf(offsetsOf(0, () => 0), 0, 100, 10)).toEqual({ start: 0, end: 0 });
	});

	it('scrolls an item in, centred, only when it is out of view', () => {
		expect(revealDelta(100, 160, 50, 400)).toBeNull();
		expect(revealDelta(1000, 1060, 0, 400)).toBe(1000 - 170);
		expect(revealDelta(0, 60, 500, 400)).toBe(-500 - 170);
	});
});
