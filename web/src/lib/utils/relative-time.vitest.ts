import { describe, it, expect } from 'vitest';
import { relativeAgo } from './relative-time';

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;
// A fixed "now" so the table is deterministic (no Date.now()).
const NOW = 1_700_000_000_000;

describe('relativeAgo', () => {
	const cases: Array<[string, number, string]> = [
		['0', 0, 'just now'],
		['30s', 30_000, 'just now'],
		['1 min', 1 * MIN, '1m ago'],
		['59 min', 59 * MIN, '59m ago'],
		['1 hour', 60 * MIN, '1h ago'],
		['23h59m', 23 * HOUR + 59 * MIN, '23h ago'],
		['1 day', 1 * DAY, '1d ago'],
		['6 days', 6 * DAY, '6d ago'],
		['1 week', 7 * DAY, '1w ago'],
		// weeks gate is `< 5`, so 30 days (= 4 weeks) is still weeks, not months.
		['30 days', 30 * DAY, '4w ago'],
		['34 days', 34 * DAY, '4w ago'],
		['35 days', 35 * DAY, '1mo ago'],
		['60 days', 60 * DAY, '2mo ago'],
		['11 months', 11 * 30 * DAY, '11mo ago'],
		['1 year', 365 * DAY, '1y ago'],
		['2 years', 730 * DAY, '2y ago']
	];
	for (const [label, deltaMs, expected] of cases) {
		it(`${label} → "${expected}"`, () => {
			expect(relativeAgo(NOW - deltaMs, NOW)).toBe(expected);
		});
	}

	// Known parity quirk (matches the Android shell): months hits 12 and falls
	// through to years = floor(days/365) = 0 for days in [360, 364]. Locked in a
	// test so it can't silently diverge — fix in BOTH shells together if ever.
	it.each([360, 361, 364])('days=%i → "0y ago" (shared quirk)', (days) => {
		expect(relativeAgo(NOW - days * DAY, NOW)).toBe('0y ago');
	});

	it('clamps future timestamps to "just now"', () => {
		expect(relativeAgo(NOW + 5 * DAY, NOW)).toBe('just now');
	});

	it('defaults asOf to now', () => {
		expect(relativeAgo(Date.now())).toBe('just now');
	});
});
