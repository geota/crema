import { afterEach, describe, expect, it, vi } from 'vitest';
import { HISTORY_RANGES, minuteBucket, msToNextMinute, rangeCutoff } from './range.js';

const DAY = 24 * 60 * 60 * 1000;

describe('rangeCutoff', () => {
	afterEach(() => {
		vi.unstubAllEnvs();
	});

	it('keeps Android’s four ranges, labels and order', () => {
		expect(HISTORY_RANGES.map((r) => [r.id, r.label])).toEqual([
			['all', 'All time'],
			['30d', '30 days'],
			['7d', '7 days'],
			['today', 'Today']
		]);
	});

	it('has no limit for all time', () => {
		expect(rangeCutoff('all', Date.now())).toBeNull();
	});

	it('starts today at local midnight, not 24 hours back', () => {
		const now = new Date(2026, 5, 15, 0, 30).getTime();
		const cutoff = rangeCutoff('today', now);
		expect(cutoff).toBe(new Date(2026, 5, 15, 0, 0, 0, 0).getTime());
		// A brew from 23:59 yesterday is under an hour old and still not today.
		expect(new Date(2026, 5, 14, 23, 59).getTime()).toBeLessThan(cutoff as number);
	});

	it('uses rolling windows for 7 and 30 days', () => {
		const now = new Date(2026, 5, 15, 18, 0).getTime();
		expect(rangeCutoff('7d', now)).toBe(now - 7 * DAY);
		expect(rangeCutoff('30d', now)).toBe(now - 30 * DAY);
	});

	it('finds midnight in a zone with a DST change', () => {
		vi.stubEnv('TZ', 'America/New_York');
		// The spring-forward day is 23 hours long; midnight is not now - hours * 3600 s.
		const now = new Date(2026, 2, 8, 12, 0).getTime();
		expect(rangeCutoff('today', now)).toBe(new Date(2026, 2, 8, 0, 0, 0, 0).getTime());
	});
});

describe('minute clock', () => {
	it('floors to the minute', () => {
		expect(minuteBucket(120_000)).toBe(120_000);
		expect(minuteBucket(179_999)).toBe(120_000);
	});

	it('always waits for a later minute', () => {
		expect(msToNextMinute(120_000)).toBe(60_000);
		expect(msToNextMinute(179_999)).toBe(1);
	});
});
