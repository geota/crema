import { describe, expect, it } from 'vitest';
import { dayGroup, groupShots } from './phone-list';

const now = new Date(2026, 10, 6, 15, 0, 0);
const at = (days: number, hour = 9): number => new Date(2026, 10, 6 - days, hour).getTime();

describe('dayGroup', () => {
	it('names the group by the calendar day, not a rolling 24 hours', () => {
		expect(dayGroup(at(0, 0), now)).toBe('Today');
		expect(dayGroup(at(1, 23), now)).toBe('Yesterday');
		expect(dayGroup(at(1, 0), now)).toBe('Yesterday');
		expect(dayGroup(at(2), now)).toBe('This week');
		expect(dayGroup(at(6), now)).toBe('This week');
		expect(dayGroup(at(7), now)).toBe('Earlier');
	});
});

describe('groupShots', () => {
	const shots = [at(0), at(0, 8), at(1), at(9)].map((completedAt) => ({ completedAt }));

	it('groups a date-sorted list under one heading per run', () => {
		const groups = groupShots(shots, true, now);
		expect(groups.map((g) => [g.label, g.shots.length])).toEqual([
			['Today', 2],
			['Yesterday', 1],
			['Earlier', 1]
		]);
	});

	it('leaves other sorts as one headless run', () => {
		expect(groupShots(shots, false, now)).toEqual([{ label: null, shots }]);
	});

	it('has no groups for an empty list', () => {
		expect(groupShots([], true, now)).toEqual([]);
		expect(groupShots([], false, now)).toEqual([]);
	});
});
