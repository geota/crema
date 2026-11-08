import { describe, expect, it } from 'vitest';
import { calendarDay, formatCalendarDay } from './calendar-day.ts';

describe('calendar days', () => {
	it('a bare day is that day here, not the evening before', () => {
		const d = calendarDay('2026-11-08');
		expect(d && [d.getFullYear(), d.getMonth(), d.getDate()]).toEqual([2026, 10, 8]);
		expect(formatCalendarDay('2026-11-08')).toBe(
			new Date(2026, 10, 8).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
		);
	});

	it('a full timestamp is still read as one', () => {
		expect(calendarDay('2026-11-08T12:00:00Z')?.getTime()).toBe(Date.UTC(2026, 10, 8, 12));
	});

	it('nothing, or something unreadable, shows a dash', () => {
		expect(formatCalendarDay(null)).toBe('—');
		expect(formatCalendarDay('')).toBe('—');
		expect(formatCalendarDay('not a day')).toBe('—');
	});
});
